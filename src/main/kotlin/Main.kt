import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.ChatAction
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.google.gson.reflect.TypeToken
import java.io.File

val TELEGRAM_BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
val OPENAI_API_KEY = System.getenv("OPENAI_API_KEY") ?: ""
const val SESSION_DURATION_MS = 24 * 60 * 60 * 1000L // 24 часа в миллисекундах

val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
val gson = Gson()

// ================= ВОРОНКА И ДАННЫЕ =================
enum class UserState { NEW, WAITING_FOR_FORM, WAITING_FOR_TEST, FINISHED }
enum class Profession { EDITOR, SCREENWRITER, DESIGNER, AI_CREATOR, RESEARCHER, COLORIST, WEB_DEV, DIRECTOR, PUBLICIST, NETWORKING, ACADEMY_MANAGER, MOTION_DESIGNER, SALES, RETOUCHER, INVESTOR, TARGETOLOGIST, TRAVEL, UNKNOWN }

data class CandidateData(
    var state: UserState = UserState.NEW,
    var profession: Profession = Profession.UNKNOWN,
    var chatHistory: MutableList<GptMessage> = mutableListOf(),
    val firstMessageTime: Long = System.currentTimeMillis(), // Время старта сессии
    var humanRequired: Boolean = false // Флаг передачи диалога человеку
)

val candidatesDb = mutableMapOf<Long, CandidateData>()

fun main() {
    loadDatabase()
    println("HR-бот запущен. Ограничение сессии: 24 часа.")

    val telegramBot = bot {
        token = TELEGRAM_BOT_TOKEN

        dispatch {
            addHandler(object : Handler {
                override fun checkUpdate(update: Update): Boolean = true

                override suspend fun handleUpdate(bot: Bot, update: Update) {
                    val bizMessage = update.businessMessage ?: return
                    val text = bizMessage.text ?: return

// ================= ЗАЩИТА ОТ СТАРЫХ СООБЩЕНИЙ ИЗ ОФЛАЙНА =================
                    val messageTimeSeconds = bizMessage.date // Время отправки сообщения соискателем
                    val currentTimeSeconds = System.currentTimeMillis() / 1000 // Текущее время сервера

// Если сообщение отправлено более 180 секунд (3 минут) назад — молча пропускаем
                    if (currentTimeSeconds - messageTimeSeconds > 180) {
                        println("Сообщение из офлайна от чата ${bizMessage.chat.id} пропущено (отправлено ${currentTimeSeconds - messageTimeSeconds} сек. назад)")
                        return
                    }

                    val chatIdLong = bizMessage.chat.id
                    val senderId = bizMessage.from?.id

                    if (senderId != chatIdLong) return

                    val candidate = candidatesDb.getOrPut(chatIdLong) { CandidateData() }

                    // --- ПРОВЕРКА: ЕСЛИ ДИАЛОГ УЖЕ ПЕРЕДАН ЧЕЛОВЕКУ ИЛИ ЗАВЕРШЕН ---
                    if (candidate.humanRequired || candidate.state == UserState.FINISHED) {
                        println("Диалог $chatIdLong игнорируется: передан человеку или успешно завершен.")
                        return
                    }

                    // --- ПРОВЕРКА ВРЕМЕНИ (24 ЧАСА) ---
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - candidate.firstMessageTime > SESSION_DURATION_MS) {
                        println("Сессия для пользователя $chatIdLong истекла.")
                        return
                    }

                    val chatId = ChatId.fromId(chatIdLong)
                    val bizConnectionId = bizMessage.businessConnectionId

                    // Обновляем состояние воронки (с контекстом истории)
                    updateCandidateState(candidate, text)

                    // Генерируем ответ. Если вернулся null (ошибка/сбой сети) — молча игнорируем сообщение
                    val gptAnswer = generateSmartResponse(candidate, text) ?: return

                    // Обрабатываем запрос на передачу человеку
                    val finalAnswer = if (gptAnswer.contains("[HUMAN_REQUIRED]")) {
                        candidate.humanRequired = true
                        gptAnswer.replace("[HUMAN_REQUIRED]", "").trim()
                    } else {
                        gptAnswer
                    }

                    // --- ИМИТАЦИЯ ПЕЧАТАНИЯ ---
                    val delayMs = (finalAnswer.length * 40L).coerceIn(1500L, 7000L)
                    bot.sendChatAction(chatId = chatId, action = ChatAction.TYPING)
                    if (delayMs > 5000L) {
                        delay(4000L)
                        bot.sendChatAction(chatId = chatId, action = ChatAction.TYPING)
                        delay(delayMs - 4000L)
                    } else {
                        delay(delayMs)
                    }

                    bot.sendMessage(chatId = chatId, businessConnectionId = bizConnectionId, text = finalAnswer)

                    saveDatabase()
                }
            })
            telegramError { println("TELEGRAM ERROR: ${error.getErrorMessage()}") }
        }
    }
    telegramBot.deleteWebhook()
    telegramBot.startPolling()
    while (true) { Thread.sleep(1000) }
}

// ================= ЛОГИКА ВОРОНКИ =================

suspend fun updateCandidateState(candidate: CandidateData, text: String) {
    when (candidate.state) {
        UserState.NEW -> {
            val prof = detectProfession(text)
            if (prof != Profession.UNKNOWN) {
                candidate.profession = prof
                candidate.state = UserState.WAITING_FOR_FORM
            }
        }
        UserState.WAITING_FOR_FORM -> {
            val isAgreed = checkIntentWithGPT(
                text,
                "Пользователь сообщает, что заполнил гугл-форму, согласен на условия или готов к тесту?",
                candidate.chatHistory
            )
            if (isAgreed) candidate.state = UserState.WAITING_FOR_TEST
        }
        UserState.WAITING_FOR_TEST -> {
            val isDone = checkIntentWithGPT(
                text,
                "Пользователь отправил ссылку на работу, файл или сообщает, что выполнил тестовое задание?",
                candidate.chatHistory
            )
            if (isDone) candidate.state = UserState.FINISHED
        }
        UserState.FINISHED -> { /* Конец воронки */ }
    }
}

// ================= ИНТЕГРАЦИЯ ДОКУМЕНТА, ПАМЯТИ И AI =================

suspend fun generateSmartResponse(candidate: CandidateData, userText: String): String? {
    val referenceInfo = getReferenceFromDocx(candidate)

    val systemPrompt = """
        Ты — Рома Нгуен, HR-ассистент независимой киностудии DK FILMS (Лос-Анджелес).
        Твоя задача — ответить на сообщение соискателя максимально живо, естественно и по-человечески, НО строго соблюдая факты и ссылки из Справочной Информации ниже.
        
        --- СПРАВОЧНАЯ ИНФОРМАЦИЯ НА ДАННЫЙ МОМЕНТ ---
        $referenceInfo
        ----------------------------------------------
        
        ПРАВИЛА ОТВЕТА (ВЫПОЛНЯТЬ СТРОГО!):
        1. Отвечай кратко (1-3 предложения), используй разговорный, но уважительный стиль.
        2. ОБРАЩЕНИЕ НА «ВЫ»: Ко всем кандидатам обращайся СТРОГО на «Вы» (с заглавной буквы «В»: Вы, Вас, Вам, Ваш). Используй уважительные глагольные формы во множественном числе (например: "Вы заполнили", "напишите", "сделайте"). Любое обращение на "ты" категорически запрещено!
        3. Обязательно ответь на вопрос человека, если он его задал, используя факты из справочника.
        4. НИКОГДА не здоровайся повторно, если это не первое сообщение в диалоге! Продолжай беседу естественно.
        5. ПРАВИЛА ДЛЯ ССЫЛОК (КРИТИЧЕСКИ ВАЖНО):
           - Бери ссылки ТОЛЬКО из блока "СПРАВОЧНАЯ ИНФОРМАЦИЯ". Не придумывай свои!
           - Выводи ссылки ПОЛНОСТЬЮ открытым текстом. 
           - ЗАПРЕЩЕНО использовать формат Markdown (без квадратных и круглых скобок).
           - Просто вставь ссылку в текст как она есть.
        6. НИКОГДА не говори, что ты ИИ.
        7. ЕСЛИ НЕ ЗНАЕШЬ ОТВЕТА: Если кандидат задает вопрос, информации о котором НЕТ в справочной информации, или ты не знаешь, что ответить, начни свой ответ СТРОГО со специального тега '[HUMAN_REQUIRED]'.
           Пример ответа: "[HUMAN_REQUIRED] К сожалению, я не владею этой информацией. Сейчас позову нашего специалиста, он ответит Вам подробно!"
    """.trimIndent()

    val gptAnswer = sendRequestToOpenAI(systemPrompt, userText, candidate.chatHistory) ?: return null

    candidate.chatHistory.add(GptMessage("user", userText))
    candidate.chatHistory.add(GptMessage("assistant", gptAnswer))

    if (candidate.chatHistory.size > 12) {
        candidate.chatHistory = candidate.chatHistory.drop(candidate.chatHistory.size - 12).toMutableList()
    }

    return gptAnswer
}

fun getReferenceFromDocx(candidate: CandidateData): String {
    val faqFile = File("faq.txt")

    val faqBase = if (faqFile.exists()) {
        "FAQ ИЗ ДОКУМЕНТА:\n" + faqFile.readText()
    } else {
        "ВНИМАНИЕ: Файл базы знаний (faq.txt) не найден на сервере! Отвечай только на основе общих знаний."
    }

    return when (candidate.state) {
        UserState.NEW -> {
            "$faqBase\nИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат пока не назвал точную вакансию. Ответьте на его вопросы (если есть) и мягко спросите, на какую должность он откликался на HH.ru, чтобы Вы могли выслать детали."
        }
        UserState.WAITING_FOR_FORM -> {
            val profName = candidate.profession.name
            "$faqBase\nИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат идет на должность: $profName. Обязательно расскажите, что вы студия из Лос-Анджелеса, обучение бесплатное, и попросите его заполнить Гугл Форму: https://docs.google.com/forms/d/e/1FAIpQLSfxrgtk4V3r_L5CJxdbe2wAbZF2yTvVvdJPh3X4bxnl2Jwgkg/viewform?usp=dialog. Попросите написать, как заполнит. Упомяните аудио-информацию: https://drive.google.com/drive/folders/1yKY8qeVrvKnB7EB6LguuR25O5I37D9kQ?usp=drive_link"
        }
        UserState.WAITING_FOR_TEST -> {
            val testTask = getTestTaskStrict(candidate.profession)
            if (testTask != null) {
                "$faqBase\nИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат заполнил форму. Поблагодарите его и выдайте тестовое задание. Точные данные задания (ОБЯЗАТЕЛЬНО ОТПРАВЬТЕ ЭТУ ССЫЛКУ): $testTask"
            } else {
                "$faqBase\nИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат заполнил форму. Скажите, что передали его кандидатуру специалисту и скоро с ним свяжутся для созвона в Google Meet."
            }
        }
        UserState.FINISHED -> {
            "$faqBase\nИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат всё сдал. Скажите, что его работа проверяется, и попросите ожидать обратной связи от специалистов."
        }
    }
}

fun getTestTaskStrict(prof: Profession): String? {
    return when (prof) {
        Profession.SCREENWRITER -> "Ссылка: https://drive.google.com/drive/folders/1ejZWjUsli0RAUSFxG7UD30BgktX8v7l-?usp=sharing. Дедлайн 1-2 недели."
        Profession.DIRECTOR -> "Нужно прочесть «Спасите котика!» и «Путешествие Писателя». Ссылка: https://1drv.ms/f/s!AuzaU4Gs1DMUwy7S5SFBAmHmabwQ?e=D4f2ve. Дедлайн 1-2 недели."
        Profession.WEB_DEV -> "Проанализировать сайты incubator.dkfilms.tv and dkfilms.tv. Дедлайн 3 дня."
        Profession.EDITOR -> "Ссылка на материалы: https://drive.google.com/drive/folders/1EaJy01YKrQ6nYgmx3m3FVWytNEjk4rhw. Дедлайн 7 дней."
        Profession.AI_CREATOR -> "Ссылка на материалы: https://drive.google.com/drive/folders/1juYhiegOwAjcub7DH4DiaFxgIOWcxhAY. Дедлайн 7 дней."
        Profession.RESEARCHER -> "Ссылка на материалы: https://drive.google.com/drive/folders/1g0d4ZNu6_nmmxVPcu9hnvCrNJmjPNJX-. Дедлайн 7 дней."
        Profession.COLORIST -> "Ссылка на материалы: https://drive.google.com/drive/folders/1EZaFyB_Kxk_Ko0FGX01ZTZNho0EfpvcJ. Дедлайн 7 дней."
        else -> null
    }
}

// ================= OPENAI СЕТЕВЫЕ ЗАПРОСЫ =================

data class GptMessage(val role: String, val content: String)
data class GptRequest(
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.7,
    val messages: List<GptMessage>
)
data class GptResponse(val choices: List<Choice>)
data class Choice(val message: GptMessage)

suspend fun checkIntentWithGPT(userText: String, question: String, chatHistory: List<GptMessage> = emptyList()): Boolean {
    val historyContext = if (chatHistory.isEmpty()) "История пуста." else chatHistory.joinToString("\n") { "${it.role}: ${it.content}" }
    val prompt = """
        Ты — беспристрастный классификатор интента (намерений) пользователя.
        Изучи историю диалога и последнее сообщение соискателя, чтобы точно понять контекст.
        
        История диалога:
        $historyContext
        
        Последнее сообщение пользователя: $userText
        
        Вопрос: $question
        Ответь СТРОГО одним словом: YES (если ответ на вопрос утвердительный) или NO (если отрицательный или информации недостаточно).
    """.trimIndent()

    val response = sendRequestToOpenAI(prompt, userText) ?: ""
    return response.contains("YES", ignoreCase = true)
}

suspend fun sendRequestToOpenAI(
    systemPrompt: String,
    userText: String,
    chatHistory: List<GptMessage> = emptyList()
): String? {
    return withContext(Dispatchers.IO) {
        try {
            val requestMessages = mutableListOf<GptMessage>()
            requestMessages.add(GptMessage("system", systemPrompt))
            requestMessages.addAll(chatHistory)
            requestMessages.add(GptMessage("user", userText))

            val requestBody = GptRequest(messages = requestMessages)

            val jsonBody = gson.toJson(requestBody)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val gptResponse = gson.fromJson(responseBody, GptResponse::class.java)
                return@withContext gptResponse.choices.firstOrNull()?.message?.content
            } else {
                return@withContext null
            }
        } catch (e: Exception) {
            return@withContext null
        }
    }
}

fun saveDatabase() {
    try {
        val json = gson.toJson(candidatesDb)
        File("database.json").writeText(json)
    } catch (e: Exception) {
        println("Ошибка при сохранении базы: ${e.message}")
    }
}

fun loadDatabase() {
    val file = File("database.json")
    if (file.exists()) {
        try {
            val json = file.readText()
            val type = object : TypeToken<MutableMap<Long, CandidateData>>() {}.type
            val loadedDb: MutableMap<Long, CandidateData> = gson.fromJson(json, type)
            candidatesDb.clear()
            candidatesDb.putAll(loadedDb)
            println("База данных успешно загружена! Записей: ${candidatesDb.size}")
        } catch (e: Exception) {
            println("Ошибка при чтении базы: ${e.message}")
        }
    } else {
        println("Файл базы не найден. Создана новая пустая база.")
    }
}

// ================= УТИЛИТЫ =================
fun detectProfession(text: String): Profession {
    val lowerText = text.lowercase()
    return when {
        lowerText.contains("монтаж") || lowerText.contains("editor") -> Profession.EDITOR
        lowerText.contains("сценарист") || lowerText.contains("writer") -> Profession.SCREENWRITER
        lowerText.contains("дизайнер") -> Profession.DESIGNER
        lowerText.contains("ai") || lowerText.contains("ии") -> Profession.AI_CREATOR
        lowerText.contains("ресерчер") -> Profession.RESEARCHER
        lowerText.contains("цветокор") -> Profession.COLORIST
        lowerText.contains("верстал") -> Profession.WEB_DEV
        lowerText.contains("режиссер") -> Profession.DIRECTOR
        lowerText.contains("публицист") -> Profession.PUBLICIST
        lowerText.contains("нетворкинг") -> Profession.NETWORKING
        lowerText.contains("академ") -> Profession.ACADEMY_MANAGER
        lowerText.contains("моушн") -> Profession.MOTION_DESIGNER
        lowerText.contains("продаж") -> Profession.SALES
        lowerText.contains("ретушер") -> Profession.RETOUCHER
        lowerText.contains("инвестор") -> Profession.INVESTOR
        lowerText.contains("таргетолог") -> Profession.TARGETOLOGIST
        lowerText.contains("путешеств") -> Profession.TRAVEL
        else -> Profession.UNKNOWN
    }
}