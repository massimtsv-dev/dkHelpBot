package com.repository

import com.model.Profession
import com.model.UserState
import java.io.File

class KnowledgeRepository {
    private val faqFile = File("faq.txt")
    private val learnedFile = File("learned_faq.txt")

    fun getReferenceKnowledge(profession: Profession): String {
        var faqBase = if (faqFile.exists()) {
            "FAQ ИЗ ДОКУМЕНТА:\n" + faqFile.readText()
        } else {
            "ВНИМАНИЕ: Файл базы знаний (faq.txt) не найден на сервере! Отвечай только на основе общих знаний."
        }

        if (learnedFile.exists() && learnedFile.readText().isNotBlank()) {
            val learnedRules = learnedFile.readLines().filter { line ->
                line.contains("[GLOBAL]") || line.contains("[${profession.name}]") || line.startsWith("-")
            }
            if (learnedRules.isNotEmpty()) {
                faqBase += "\n\nНАКОПЛЕННЫЙ ОПЫТ (ИЗ ОТВЕТОВ ВЛАДЕЛЬЦА):\n" + learnedRules.joinToString("\n")
            }
        }

        return faqBase
    }

    fun appendLearnedRule(rule: String) {
        learnedFile.appendText("\n$rule")
    }

    fun getAudioFileIds(profession: Profession, state: UserState): List<String> {
        if (state != UserState.WAITING_FOR_FORM && state != UserState.WAITING_FOR_TEST) {
            return emptyList()
        }

        return when (profession) {
            Profession.PROGRAMMER -> listOf(
                "CQACAgIAAxkBAAM9aqmJfmU7BsPk3M91fk3HhIiFXjEAAl-xAAL-81FJsGuV5fm4Fvc9BA",
                "CQACAgIAAxkBAAM-aqmJnvT-g4noZm_Zs0mLjv-9Mq8AAmOxAAL-81FJLWLNp5SKWFE9BA",
                "CQACAgIAAxkBAAM_aqmJ7sGIxWy8Y6StklLFaaHydpQAAmaxAAL-81FJtiWEDIVVF2o9BA"
            )

            Profession.TARGETOLOGIST -> listOf(
                "CQACAgIAAxkBAANLaqmQueSwWJJwNrC2o0_bGOpw8zgAArCxAAL-81FJwriFW95m4DQ9BA"
            )

            Profession.RETOUCHER -> listOf(
                "CQACAgIAAxkBAANAaqmKK82w4B_rHALfFiCD97KtAc8AAmixAAL-81FJ2w5HZE6mlkM9BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.AI_CREATOR -> listOf(
                "CQACAgIAAxkBAANCaqmLZj0fMBDCt9E9JGRbxnbN18IAAnGxAAL-81FJ4sec_I4nzWw9BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA" // сотрудничество
            )

            Profession.ACCOUNTANT -> listOf(
                "CQACAgIAAxkBAANDaqmLkiKrssu_2P9E5BhXCVKIp5cAAnOxAAL-81FJKcuvlSAMhOs9BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.WEB_DEV -> listOf(
                "CQACAgIAAxkBAANEaqmNEMTekHutvh8IYNamg5wyc0gAAoexAAL-81FJosne-WChy5Q9BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.DESIGNER -> listOf(
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.INVESTOR -> listOf(
                "CQACAgIAAxkBAANFaqmNQKEh-Y6QMZ0P_XX0PQIGmkEAAoixAAL-81FJ-0onYA_wXZ49BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.ACADEMY_MANAGER -> listOf(
                "CQACAgIAAxkBAANGaqmNbzbmadOaBJx-Qa9Ma_P8t64AAo6xAAL-81FJ6qQf7WZoVo49BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.SALES -> listOf(
                "CQACAgIAAxkBAANHaqmNkKQbRBxkqKmVUowpFvFNKhgAAo-xAAL-81FJzrVBZAei3Aw9BA"
            )

            Profession.MOTION_DESIGNER -> listOf(
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.PRODUCER -> listOf(
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.PUBLICIST -> listOf(
                "CQACAgIAAxkBAANIaqmOTT9_6d1VUVw7ONClsZjHn_EAApaxAAL-81FJj3myZGS1L3w9BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.EDITOR -> listOf(
                "CQACAgIAAxkBAANJaqmQT8OGyA75eY1ERkcPZTT7XDQAAqqxAAL-81FJr2mUs8ZaTgQ9BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.RESEARCHER -> listOf(
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.NETWORKING -> listOf(
                "CQACAgIAAxkBAANKaqmQb9-h9CZxYaAslpoPg7TXtqkAAq2xAAL-81FJw74I96B2uVI9BA",
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.SCREENWRITER -> listOf(
                "CQACAgIAAxkBAANBaqmKTDygwVgc5EuDMM7t5m4SoSkAAmqxAAL-81FJe0ezyuvFO8k9BA"
            )

            Profession.TRAVEL -> listOf(
                "CQACAgIAAxkBAAM8aqmJNtAseX8YyeNOD2r9JTwKnO8AAlqxAAL-81FJExcsMLSEFOc9BA"
            )

            Profession.DIRECTOR -> listOf("FILE_ID_РЕЖИССЕР")
            Profession.COLORIST -> listOf("FILE_ID_ЦВЕТОКОР")

            Profession.UNKNOWN -> emptyList()
        }
    }
}