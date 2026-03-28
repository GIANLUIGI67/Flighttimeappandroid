package it.grg.flighttimeapp.crewl

import it.grg.flighttimeapp.R
import java.util.Date

enum class CrewRole(val raw: String, val labelResId: Int) {
    CABIN_CREW("cabinCrew", R.string.cl_role_cabin_crew),
    FLIGHT_DECK("flightDeck", R.string.cl_role_flight_deck);

    companion object {
        fun fromRaw(raw: String?): CrewRole {
            return entries.firstOrNull { it.raw.equals(raw, true) } ?: CABIN_CREW
        }
    }
}

enum class CrewVisibilityMode(val raw: String, val labelResId: Int) {
    EVERYONE("everyone", R.string.cl_visibility_everyone),
    SAME_ROLE_ONLY("sameRoleOnly", R.string.cl_visibility_same_role),
    SAME_COUNTRY_CODE_ONLY("sameCountryCodeOnly", R.string.cl_visibility_same_country_code),
    SAME_BASE_ONLY("sameBaseOnly", R.string.cl_visibility_same_base),
    CABIN_CREW_ALL("cabinCrewAll", R.string.cl_visibility_cabin_crew_all),
    FLIGHT_DECK_ALL("flightDeckAll", R.string.cl_visibility_flight_deck_all),
    CABIN_CREW_NOT_BASE("cabinCrewNotBase", R.string.cl_visibility_cabin_crew_not_base),
    FLIGHT_DECK_NOT_BASE("flightDeckNotBase", R.string.cl_visibility_flight_deck_not_base);

    companion object {
        fun fromRaw(raw: String?): CrewVisibilityMode {
            return entries.firstOrNull { it.raw.equals(raw, true) } ?: EVERYONE
        }
    }
}

data class CrewLayoverSettings(
    var nickname: String = "",
    var companyName: String? = null,
    var baseCountryCode: String = "",
    var phoneNumber: String? = null,
    var role: CrewRole = CrewRole.CABIN_CREW,
    var visibilityMode: CrewVisibilityMode = CrewVisibilityMode.EVERYONE,
    var excludedBaseCodes: MutableList<String> = mutableListOf(),
    var isEnabled: Boolean = true,
    var eventRemindersEnabled: Boolean = true
)

data class NearbyCrewUser(
    val userId: String,
    val nickname: String,
    val companyName: String?,
    val baseCountryCode: String,
    val phoneNumber: String?,
    val role: CrewRole,
    val visibilityMode: CrewVisibilityMode,
    val excludedBaseCodes: List<String>,
    val isOnline: Boolean,
    val lastSeenMs: Long,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
    val photoB64: String?
)

enum class MeetingType(val raw: String, val labelResId: Int) {
    COFFEE("coffee", R.string.cl_meeting_coffee),
    DINNER("dinner", R.string.cl_meeting_dinner),
    WALK("walk", R.string.cl_meeting_walk),
    OTHER("other", R.string.cl_meeting_other);

    companion object {
        fun fromRaw(raw: String?): MeetingType {
            return entries.firstOrNull { it.raw.equals(raw, true) } ?: COFFEE
        }
    }
}

enum class AlarmOption(val raw: String, val labelResId: Int, val minutes: Int?) {
    NONE("none", R.string.cl_alarm_none, null),
    MIN_5("min5", R.string.cl_alarm_5_min, 5),
    MIN_10("min10", R.string.cl_alarm_10_min, 10),
    MIN_15("min15", R.string.cl_alarm_15_min, 15),
    MIN_30("min30", R.string.cl_alarm_30_min, 30),
    HOUR_1("hour1", R.string.cl_alarm_1_hour, 60),
    HOUR_2("hour2", R.string.cl_alarm_2_hours, 120);

    companion object {
        fun fromRaw(raw: String?): AlarmOption {
            return entries.firstOrNull { it.raw.equals(raw, true) } ?: NONE
        }
    }
}

data class CrewLayoverEventDraft(
    var isEnabled: Boolean = false,
    var dateTime: Date = Date(),
    var whereText: String = "",
    var meetingType: MeetingType = MeetingType.COFFEE,
    var expirationDateTime: Date? = null,
    var alarmOption: AlarmOption = AlarmOption.NONE,
    var sendToAllNearby: Boolean = true
)

data class CrewLayoverEvent(
    val id: String,
    val meetingTypeRaw: String,
    val whereText: String,
    val creatorUid: String,
    val creatorNickname: String,
    val creatorCompany: String?,
    val createdAtMs: Long,
    val eventAtMs: Long,
    val expiresAtMs: Long,
    val sendToAllNearby: Boolean,
    val isClosed: Boolean,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusKm: Double = 0.0,
    val acceptedCount: Int = 0
)

data class CrewEventMessage(
    val id: String,
    val eventId: String,
    val senderUid: String,
    val text: String,
    val imageBase64: String?,
    val imageExpiresAtMs: Long,
    val createdAt: Date
)

data class CrewChatThread(
    val id: String,
    val peerId: String,
    val peerNickname: String,
    val peerCompany: String?,
    val createdAt: Date,
    val lastMessageAt: Date,
    val lastMessageText: String?,
    val lastMessageSender: String?,
    val lastReadAt: Date?,
    val members: List<String>
)

data class CrewChatMessage(
    val id: String,
    val threadId: String,
    val senderUid: String,
    val text: String,
    val imageBase64: String?,
    val imageExpiresAtMs: Long,
    val createdAt: Date
)
