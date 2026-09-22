package com.repository

import com.model.CandidateData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class CandidateRepository(private val gson: Gson) {
    val candidatesDb = mutableMapOf<Long, CandidateData>()
    private val dbFile = File("database.json")

    fun loadDatabase() {
        if (dbFile.exists()) {
            try {
                val json = dbFile.readText()
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

    fun saveDatabase() {
        try {
            val json = gson.toJson(candidatesDb)
            dbFile.writeText(json)
        } catch (e: Exception) {
            println("Ошибка при сохранении базы: ${e.message}")
        }
    }

    fun getOrCreateCandidate(chatId: Long): CandidateData {
        return candidatesDb.getOrPut(chatId) { CandidateData() }
    }
}