package com.mitas.ppnam.station2aa.domain.model

import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorEntryDto

/** One row of the login dropdown. Display-only; [username] is what SCRAM authenticates. */
data class OperatorEntry(val username: String, val displayName: String) {

    /** Dropdown row text, username first so the eye lands on what will be submitted. */
    val label: String get() = "$username — $displayName"

    companion object {
        /**
         * The fleet rules for a directory, wherever it comes from (the wire or the cache): blank
         * usernames are dropped, a missing displayName falls back to the username, and the list is
         * sorted by displayName, case-insensitively.
         */
        fun fromWire(entries: List<OperatorEntryDto>): List<OperatorEntry> = entries
            .mapNotNull { dto ->
                val username = dto.username.trim()
                if (username.isEmpty()) return@mapNotNull null
                OperatorEntry(username, dto.displayName.trim().ifEmpty { username })
            }
            .sortedBy { it.displayName.lowercase() }
    }
}
