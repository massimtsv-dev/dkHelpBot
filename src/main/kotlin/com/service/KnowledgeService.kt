package com.service

import com.model.Profession
import com.repository.KnowledgeRepository
import java.util.UUID

class KnowledgeService(
    private val openAiService: OpenAiService,
    private val knowledgeRepository: KnowledgeRepository
) {
    // Временное хранилище предложенных правил перед подтверждением: ruleId -> ruleText
    private val pendingRules = mutableMapOf<String, String>()

    suspend fun proposeKnowledgeRule(
        candidateQuestion: String,
        hrAnswer: String,
        profession: Profession
    ): Pair<String, String>? {
        val systemPrompt = """
            Ты — аналитик базы знаний HR-студии DK FILMS.
            Твоя задача — извлечь из ответа HR-менеджера новое фактологическое системное правило для базы знаний.
            
            Вопрос соискателя: "$candidateQuestion"
            Ответ HR-менеджера: "$hrAnswer"
            
            КРИТЕРИИ И СТРОГИЙ ФИЛЬТР:
            1. Если ответ содержит одноразовую уступку, персональное имя, индивидуальную дату, личный перенос дедлайна конкретному соискателю — выведи СТРОГО слово "SKIP".
            2. Если ответ содержит общее правило компании, регламент, требования к технике или софту — сформулируй 1 краткое и понятное правило.
            3. Выведи ответ в формате: "- [${profession.name}] Тема: Правило".
        """.trimIndent()

        val extractedRule = openAiService.sendRequest(systemPrompt, "Сформулируй правило") ?: return null

        if (extractedRule.isNotBlank() && !extractedRule.contains("SKIP", ignoreCase = true)) {
            val ruleId = UUID.randomUUID().toString().take(8)
            pendingRules[ruleId] = extractedRule
            return Pair(ruleId, extractedRule)
        }
        return null
    }

    fun approveRule(ruleId: String): String? {
        val rule = pendingRules.remove(ruleId)
        if (rule != null) {
            knowledgeRepository.appendLearnedRule(rule)
            println("[САМООБУЧЕНИЕ УТВЕРЖДЕНО] Правило записано в learned_faq.txt:\n$rule")
        }
        return rule
    }

    fun rejectRule(ruleId: String): Boolean {
        return pendingRules.remove(ruleId) != null
    }
}