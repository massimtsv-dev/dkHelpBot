package com.service

import com.model.GptMessage
import com.model.GptRequest
import com.model.GptResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiService(
    private val apiKey: String,
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {
    suspend fun sendRequest(
        systemPrompt: String,
        userText: String,
        chatHistory: List<GptMessage> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
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
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val gptResponse = gson.fromJson(responseBody, GptResponse::class.java)
                gptResponse.choices.firstOrNull()?.message?.content
            } else {
                println("ОШИБКА OPENAI (HTTP ${response.code}): $responseBody")
                null
            }
        } catch (e: Exception) {
            println("КРИТИЧЕСКАЯ ОШИБКА СЕТИ: ${e.message}")
            null
        }
    }

    suspend fun checkIntent(
        userText: String,
        question: String,
        chatHistory: List<GptMessage> = emptyList()
    ): Boolean {
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

        val response = sendRequest(prompt, userText) ?: ""
        return response.contains("YES", ignoreCase = true)
    }
}