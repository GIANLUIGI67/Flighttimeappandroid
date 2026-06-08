package it.grg.flighttimeapp.crewl

import java.util.Date

data class CrewSocialPost(
    val id: String,
    val uid: String,
    val nickname: String,
    val role: CrewRole,
    val airline: String?,
    val text: String,
    val photoStorageUrl: String?,
    val videoStorageUrl: String?,
    val profilePhotoUrl: String?,
    val layoverLocation: String?,
    var likesCount: Int,
    var thumbUpCount: Int,
    var thumbDownCount: Int,
    var commentsCount: Int,
    val createdAt: Date,
    var isLikedByMe: Boolean = false,
    var isThumbedUpByMe: Boolean = false,
    var isThumbedDownByMe: Boolean = false
) {
    fun hasMedia(): Boolean = !photoStorageUrl.isNullOrBlank() || !videoStorageUrl.isNullOrBlank()
}

data class CrewSocialComment(
    val id: String,
    val uid: String,
    val nickname: String,
    val profilePhotoUrl: String?,
    var text: String,
    var likesCount: Int,
    var thumbUpCount: Int,
    var thumbDownCount: Int,
    val createdAt: Date,
    var isLikedByMe: Boolean = false,
    var isThumbedUpByMe: Boolean = false,
    var isThumbedDownByMe: Boolean = false
)

enum class CrewSocialReactionKind {
    HEART,
    THUMB_UP,
    THUMB_DOWN
}

data class CrewSocialWeatherStatus(
    val city: String?,
    val timeText: String,
    val temperatureText: String?,
    val conditionText: String?,
    val weatherCode: Int?,
    val windText: String?
)
