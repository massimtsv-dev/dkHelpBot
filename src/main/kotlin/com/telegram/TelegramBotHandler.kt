package com.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.model.UserState
import com.repository.CandidateRepository
import com.repository.KnowledgeRepository
import com.service.FunnelService
import com.service.KnowledgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TelegramBotHandler(
    private val botToken: String,
    private val candidateRepository: CandidateRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val funnelService: FunnelService,
    private val knowledgeService: KnowledgeService,
    private val sessionDurationMs: Long = 24 * 60 * 60 * 1000L
) {
    fun start() {
        val telegramBot = bot {
            token = botToken

            dispatch {
                // ================= ОБРАБОТКА КЛИКОВ ПО КНОПКАМ ПОДТВЕРЖДЕНИЯ =================
                callbackQuery {
                    val queryData = callbackQuery.data
                    val message = callbackQuery.message ?: return@callbackQuery
                    val chatId = ChatId.fromId(message.chat.id)

                    when {
                        queryData.startsWith("approve_rule:") -> {
                            val ruleId = queryData.removePrefix("approve_rule:")
                            val approvedRule = knowledgeService.approveRule(ruleId)

                            if (approvedRule != null) {
                                bot.editMessageText(
                                    chatId = chatId,
                                    messageId = message.messageId,
                                    text = "✅ **Правило успешно сохранено в базу знаний:**\n\n$approvedRule"
                                )
                            } else {
                                bot.editMessageText(
                                    chatId = chatId,
                                    messageId = message.messageId,
                                    text = "⚠️ Это предложение уже было обработано или устарело."
                                )
                            }
                        }
                        queryData.startsWith("reject_rule:") -> {
                            val ruleId = queryData.removePrefix("reject_rule:")
                            knowledgeService.rejectRule(ruleId)

                            bot.editMessageText(
                                chatId = chatId,
                                messageId = message.messageId,
                                text = "❌ **Правило отклонено и не будет сохранено.**"
                            )
                        }
                    }
                }

                addHandler(object : Handler {
                    override fun checkUpdate(update: Update): Boolean = true

                    override suspend fun handleUpdate(bot: com.github.kotlintelegrambot.Bot, update: Update) {
                        // Перехват file_id для отладки
                        val voiceOrAudioId = update.message?.audio?.fileId
                            ?: update.businessMessage?.audio?.fileId
                            ?: update.message?.voice?.fileId
                            ?: update.businessMessage?.voice?.fileId
                        if (voiceOrAudioId != null) {
                            println("ПЕРЕХВАЧЕН FILE_ID: $voiceOrAudioId")
                        }

                        val bizMessage = update.businessMessage ?: return
                        val text = bizMessage.text ?: return

                        val messageTimeSeconds = bizMessage.date
                        val currentTimeSeconds = System.currentTimeMillis() / 1000
                        if (currentTimeSeconds - messageTimeSeconds > 180) return

                        val chatIdLong = bizMessage.chat.id
                        val senderId = bizMessage.from?.id ?: return
                        val candidate = candidateRepository.getOrCreateCandidate(chatIdLong)

                        // ================= ОБРАБОТКА ОТВЕТОВ HR (САМООБУЧЕНИЕ С ПОДТВЕРЖДЕНИЕМ) =================
                        if (senderId != chatIdLong) {
                            if (candidate.humanRequired) {
                                val lastQuestion = candidate.chatHistory.lastOrNull { it.role == "user" }?.content
                                if (lastQuestion != null) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val proposed = knowledgeService.proposeKnowledgeRule(lastQuestion, text, candidate.profession)
                                        if (proposed != null) {
                                            val (ruleId, ruleText) = proposed
                                            val inlineKeyboard = InlineKeyboardMarkup.create(
                                                listOf(
                                                    listOf(
                                                        InlineKeyboardButton.CallbackData(
                                                            text = "✅ Сохранить в базу",
                                                            callbackData = "approve_rule:$ruleId"
                                                        ),
                                                        InlineKeyboardButton.CallbackData(
                                                            text = "❌ Отклонить",
                                                            callbackData = "reject_rule:$ruleId"
                                                        )
                                                    )
                                                )
                                            )

                                            // Отправляем личное предложение с кнопками напрямую в чат HR-пользователя
                                            bot.sendMessage(
                                                chatId = ChatId.fromId(senderId),
                                                text = "💡 Найдено новое правило для базы знаний:\n\n$ruleText\n\nХотите внести его в базу?",
                                                replyMarkup = inlineKeyboard
                                            )
                                        }
                                    }
                                    candidate.humanRequired = false
                                    candidateRepository.saveDatabase()
                                }
                            }
                            return
                        }

                        // ================= ОБРАБОТКА СООБЩЕНИЙ СОИСКАТЕЛЯ =================
                        if (candidate.humanRequired || candidate.state == UserState.FINISHED) return

                        if (System.currentTimeMillis() - candidate.firstMessageTime > sessionDurationMs) return

                        val chatId = ChatId.fromId(chatIdLong)
                        val bizConnectionId = bizMessage.businessConnectionId

                        funnelService.updateCandidateState(candidate, text)

                        val rawGptAnswer = funnelService.generateSmartResponse(candidate, text) ?: return

                        if (rawGptAnswer.contains("[HUMAN_REQUIRED]")) {
                            candidate.humanRequired = true
                            candidateRepository.saveDatabase()
                            println("Диалог $chatIdLong требует участия HR. Бот молчит и передает чат человеку.")
                            return
                        }

                        val finalAnswer = rawGptAnswer

                        val delayMs = (finalAnswer.length * 80L).coerceIn(3000L, 14000L)
                        var remainingDelay = delayMs
                        while (remainingDelay > 0) {
                            bot.sendChatAction(chatId = chatId, action = ChatAction.TYPING)
                            val currentChunk = remainingDelay.coerceAtMost(4000L)
                            delay(currentChunk)
                            remainingDelay -= currentChunk
                        }

                        bot.sendMessage(chatId = chatId, businessConnectionId = bizConnectionId, text = finalAnswer)

                        val audioIds = knowledgeRepository.getAudioFileIds(candidate.profession, candidate.state)
                        for (audioId in audioIds) {
                            delay(1000L)
                            bot.sendAudio(
                                chatId = chatId,
                                businessConnectionId = bizConnectionId,
                                audio = TelegramFile.ByFileId(audioId)
                            )
                        }

                        candidateRepository.saveDatabase()
                    }
                })
                telegramError { println("TELEGRAM ERROR: ${error.getErrorMessage()}") }
            }
        }

        telegramBot.deleteWebhook()
        telegramBot.startPolling()
    }
}