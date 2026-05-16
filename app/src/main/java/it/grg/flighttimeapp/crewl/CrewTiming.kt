package it.grg.flighttimeapp.crewl

object CrewTiming {
    const val LOCATION_REQUEST_INTERVAL_MS = 8_000L
    const val LOCATION_MIN_UPDATE_INTERVAL_MS = 5_000L
    const val SYSTEM_LOCATION_INTERVAL_MS = 8_000L
    const val SYSTEM_LOCATION_MIN_DISTANCE_M = 10f

    const val PRESENCE_INITIAL_DELAY_MS = 1_000L
    const val PRESENCE_HEARTBEAT_INTERVAL_MS = 8_000L
    const val PRESENCE_OFFLINE_RETRY_DELAY_MS = 20_000L
}
