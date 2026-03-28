package it.grg.flighttimeapp.crewl

import androidx.annotation.StringRes
import it.grg.flighttimeapp.R

enum class LayoverInfoCategory(
    val key: String,
    @StringRes val labelResId: Int,
    val iconResId: Int
) {
    TRANSPORT("transport", R.string.cl_layover_info_transport, R.drawable.ic_location),
    PLACES("places", R.string.cl_layover_info_places, R.drawable.ic_groups),
    RESTAURANTS("restaurants", R.string.cl_layover_info_restaurants, R.drawable.ic_people),
    CLUBS("clubs", R.string.cl_layover_info_clubs, R.drawable.ic_chat),
    SHOPPING("shopping", R.string.cl_layover_info_shopping, R.drawable.ic_money),
    ENTERTAINMENT("entertainment", R.string.cl_layover_info_entertainment, R.drawable.ic_school),
    HOTELS("hotels", R.string.cl_layover_info_hotels, R.drawable.ic_hotel),
    CREW_DISCOUNTS("crew_discounts", R.string.cl_layover_info_crew_discounts, R.drawable.ic_discount),
    OTHER("other", R.string.cl_layover_info_other, R.drawable.ic_school),
    WARNINGS("warnings", R.string.cl_layover_info_warnings, R.drawable.ic_settings);

    companion object {
        fun fromKey(key: String?): LayoverInfoCategory {
            return entries.firstOrNull { it.key == key } ?: TRANSPORT
        }
    }
}

data class LayoverInfoItem(
    val id: String,
    val title: String,
    val details: String,
    val location: String,
    val createdAtMs: Long,
    val creatorUid: String,
    val cityKey: String,
    val cityName: String
)

object LayoverInfoAdminConfig {
    val adminUids: Set<String> = setOf(
        "H13QC9UChpS6Qi1ftd96eYo4sqe2",
        "EUUBziqEKWhGuGpJ9E4Rdo-J3ca42",
        "rQoCGho8kEX0gHeWnvd5xjbYvpv2"
    )
}
