package com.model

enum class UserState { NEW, WAITING_FOR_FORM, WAITING_FOR_TEST, FINISHED }

enum class Profession {
    SCREENWRITER,       // Сценарист\ученик
    TARGETOLOGIST,      // Таргетолог
    AI_CREATOR,         // Ai video creator/ученик
    TRAVEL,             // Создатель путеводителя для путешественников\ученик
    ACCOUNTANT,         // Бухгалтер
    WEB_DEV,            // Веб-верстальщик\дизайнер\ученик
    DESIGNER,           // Графический дизайнер\ученик
    INVESTOR,           // Инвестор
    ACADEMY_MANAGER,    // Менеджер по академии\ученик
    SALES,              // Менеджер по продажам
    MOTION_DESIGNER,    // Моушн-дизайнер\ученик
    PROGRAMMER,         // Программист
    PRODUCER,           // Продюсер\ученик
    PUBLICIST,          // Публицисты\ученик
    EDITOR,             // Режиссер монтажа\ученик
    RESEARCHER,         // Ресечер\ученик
    RETOUCHER,          // Ретушер фотографий\ученик
    NETWORKING,         // Специалист по нетворкингу в Голливуде\ученик
    DIRECTOR,           // Режиссер
    COLORIST,           // Цветокорректор
    UNKNOWN
}

data class GptMessage(val role: String, val content: String)

data class CandidateData(
    var state: UserState = UserState.NEW,
    var profession: Profession = Profession.UNKNOWN,
    var chatHistory: MutableList<GptMessage> = mutableListOf(),
    val firstMessageTime: Long = System.currentTimeMillis(),
    var humanRequired: Boolean = false
)

data class GptRequest(
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.7,
    val messages: List<GptMessage>
)

data class GptResponse(val choices: List<Choice>)
data class Choice(val message: GptMessage)