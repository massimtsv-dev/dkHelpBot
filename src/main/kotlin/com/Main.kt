package com

import com.repository.CandidateRepository
import com.repository.KnowledgeRepository
import com.service.FunnelService
import com.service.KnowledgeService
import com.service.OpenAiService
import com.telegram.TelegramBotHandler
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

val TELEGRAM_BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
val OPENAI_API_KEY = System.getenv("OPENAI_API_KEY") ?: ""

fun main() {
    val gson = Gson()
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val candidateRepository = CandidateRepository(gson)
    val knowledgeRepository = KnowledgeRepository()

    candidateRepository.loadDatabase()

    val openAiService = OpenAiService(OPENAI_API_KEY, httpClient, gson)
    val knowledgeService = KnowledgeService(openAiService, knowledgeRepository)
    val funnelService = FunnelService(openAiService, knowledgeRepository)

    println("HR-бот запущен. Ограничение сессии: 24 часа. Самообучение и аудио включены.")

    val handler = TelegramBotHandler(
        botToken = TELEGRAM_BOT_TOKEN,
        candidateRepository = candidateRepository,
        knowledgeRepository = knowledgeRepository,
        funnelService = funnelService,
        knowledgeService = knowledgeService
    )

    handler.start()

    while (true) { Thread.sleep(1000) }
}