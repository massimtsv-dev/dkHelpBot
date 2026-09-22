package com.service

import com.model.CandidateData
import com.model.GptMessage
import com.model.Profession
import com.model.UserState
import com.repository.KnowledgeRepository

class FunnelService(
    private val openAiService: OpenAiService,
    private val knowledgeRepository: KnowledgeRepository
) {
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
                val isAgreed = openAiService.checkIntent(
                    text,
                    "Пользователь сообщает, что заполнил гугл-форму, согласен на условия или готов к тесту?",
                    candidate.chatHistory
                )
                if (isAgreed) candidate.state = UserState.WAITING_FOR_TEST
            }
            UserState.WAITING_FOR_TEST -> {
                val isDone = openAiService.checkIntent(
                    text,
                    "Пользователь отправил ссылку на работу, файл или сообщает, что выполнил тестовое задание?",
                    candidate.chatHistory
                )
                if (isDone) candidate.state = UserState.FINISHED
            }
            UserState.FINISHED -> {}
        }
    }

    suspend fun generateSmartResponse(candidate: CandidateData, userText: String): String? {
        val referenceInfo = knowledgeRepository.getReferenceKnowledge(candidate.profession)

        val stepInstruction = when (candidate.state) {
            UserState.NEW -> {
                "ИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат пока не назвал точную вакансию. Ответьте на его вопросы (если есть) и мягко спросите, на какую должность он откликался на HH.ru, чтобы Вы могли выслать детали."
            }
            UserState.WAITING_FOR_FORM -> {
                "ИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат идет на должность: ${candidate.profession.name}. Обязательно расскажите, что вы студия из Лос-Анджелеса, обучение бесплатное, и попросите его заполнить Гугл Форму: https://docs.google.com/forms/d/e/1FAIpQLSfxrgtk4V3r_L5CJxdbe2wAbZF2yTvVvdJPh3X4bxnl2Jwgkg/viewform?usp=dialog. Попросите написать, как заполнит."
            }
            UserState.WAITING_FOR_TEST -> {
                val testTask = getTestTaskStrict(candidate.profession)
                if (testTask != null) {
                    "ИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат заполнил форму. Поблагодарите его и выдайте тестовое задание. Точные данные задания (ОБЯЗАТЕЛЬНО ОТПРАВЬТЕ ЭТУ ССЫЛКУ): $testTask"
                } else {
                    "ИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат заполнил форму. Скажите, что передали его кандидатуру специалисту и скоро с ним свяжутся для созвона в Google Meet."
                }
            }
            UserState.FINISHED -> {
                "ИНСТРУКЦИЯ ДЛЯ ТЕКУЩЕГО ШАГА: Кандидат всё сдал. Скажите, что его работа проверяется, и попросите ожидать обратной связи от специалистов."
            }
        }

        val systemPrompt = """
            Ты — Рома Нгуен, HR-ассистент независимой киностудии DK FILMS (Лос-Анджелес).
            Твоя задача — ответить на сообщение соискателя максимально живо, естественно и по-человечески, НО строго соблюдая факты и ссылки из Справочной Информации ниже.
            
            --- СПРАВОЧНАЯ ИНФОРМАЦИЯ НА ДАННЫЙ МОМЕНТ ---
            $referenceInfo
            
            $stepInstruction
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
            7. ЕСЛИ НЕ ЗНАЕШЬ ОТВЕТА: Если кандидат задает вопрос, информации о котором НЕТ в справочной информации, напиши СТРОГО один тег '[HUMAN_REQUIRED]' без какого-либо дополнительного текста.
        """.trimIndent()

        val gptAnswer = openAiService.sendRequest(systemPrompt, userText, candidate.chatHistory) ?: return null

        candidate.chatHistory.add(GptMessage("user", userText))
        candidate.chatHistory.add(GptMessage("assistant", gptAnswer))

        if (candidate.chatHistory.size > 24) {
            candidate.chatHistory = candidate.chatHistory.drop(candidate.chatHistory.size - 24).toMutableList()
        }

        return gptAnswer
    }

    private fun getTestTaskStrict(prof: Profession): String? {
        return when (prof) {
            Profession.SCREENWRITER -> "Ссылка: https://drive.google.com/drive/folders/1ejZWjUsli0RAUSFxG7UD30BgktX8v7l-?usp=sharing. Дедлайн 1-2 недели."
            Profession.DIRECTOR -> "Нужно прочесть «Спасите котика!» и «Путешествие Писателя». Ссылка: https://1drv.ms/f/s!AuzaU4Gs1DMUwy7S5SFBAmHmabwQ?e=D4f2ve. Дедлайн 1-2 недели."
            Profession.WEB_DEV -> "Проанализировать сайты incubator.dkfilms.tv и dkfilms.tv. Дедлайн 3 дня."
            Profession.EDITOR -> "Ссылка на материалы: https://drive.google.com/drive/folders/1EaJy01YKrQ6nYgmx3m3FVWytNEjk4rhw. Дедлайн 7 дней."
            Profession.AI_CREATOR -> "Ссылка на материалы: https://drive.google.com/drive/folders/1juYhiegOwAjcub7DH4DiaFxgIOWcxhAY. Дедлайн 7 дней."
            Profession.RESEARCHER -> "Ссылка на материалы: https://drive.google.com/drive/folders/1g0d4ZNu6_nmmxVPcu9hnvCrNJmjPNJX-. Дедлайн 7 дней."
            Profession.COLORIST -> "Ссылка на материалы: https://drive.google.com/drive/folders/1EZaFyB_Kxk_Ko0FGX01ZTZNho0EfpvcJ. Дедлайн 7 дней."
            else -> null
        }
    }

    private fun detectProfession(text: String): Profession {
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("сценарист") || lowerText.contains("writer") -> Profession.SCREENWRITER
            lowerText.contains("таргетолог") || lowerText.contains("target") -> Profession.TARGETOLOGIST
            lowerText.contains("ai") || lowerText.contains("ии") -> Profession.AI_CREATOR
            lowerText.contains("путеводител") || lowerText.contains("путешеств") || lowerText.contains("travel") -> Profession.TRAVEL
            lowerText.contains("бухгалтер") || lowerText.contains("accountant") -> Profession.ACCOUNTANT
            lowerText.contains("верстал") || lowerText.contains("web") -> Profession.WEB_DEV
            lowerText.contains("графическ") || lowerText.contains("дизайнер") -> Profession.DESIGNER
            lowerText.contains("инвестор") || lowerText.contains("investor") -> Profession.INVESTOR
            lowerText.contains("академ") -> Profession.ACADEMY_MANAGER
            lowerText.contains("продаж") || lowerText.contains("sales") -> Profession.SALES
            lowerText.contains("моушн") || lowerText.contains("motion") -> Profession.MOTION_DESIGNER
            lowerText.contains("программист") || lowerText.contains("dev") || lowerText.contains("developer") -> Profession.PROGRAMMER
            lowerText.contains("продюсер") || lowerText.contains("producer") -> Profession.PRODUCER
            lowerText.contains("публицист") || lowerText.contains("publicist") -> Profession.PUBLICIST
            lowerText.contains("монтаж") || lowerText.contains("editor") -> Profession.EDITOR
            lowerText.contains("ресерчер") || lowerText.contains("research") -> Profession.RESEARCHER
            lowerText.contains("ретушер") || lowerText.contains("фото") -> Profession.RETOUCHER
            lowerText.contains("нетворкинг") || lowerText.contains("networking") -> Profession.NETWORKING
            lowerText.contains("режиссер") -> Profession.DIRECTOR
            lowerText.contains("цветокор") || lowerText.contains("колорист") -> Profession.COLORIST
            else -> Profession.UNKNOWN
        }
    }
}