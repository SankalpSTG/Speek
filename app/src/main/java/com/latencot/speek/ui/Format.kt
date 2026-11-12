package com.latencot.speek.ui

import java.time.LocalDate

/** Chat-list time: "14:05" today, "Yesterday", else "02/11". [at] is "yyyy-MM-dd HH:mm". */
fun listTime(at: String, today: LocalDate = LocalDate.now()): String {
    if (at.isEmpty()) return ""
    val day = LocalDate.parse(at.substring(0, 10))
    return when (day) {
        today -> at.substring(11, 16)
        today.minusDays(1) -> "Yesterday"
        else -> "${at.substring(8, 10)}/${at.substring(5, 7)}"
    }
}

/** Bubble time: "14:05" today, else "02/11 14:05". */
fun bubbleTime(at: String, today: LocalDate = LocalDate.now()): String =
    if (LocalDate.parse(at.substring(0, 10)) == today) at.substring(11, 16) else "${at.substring(8, 10)}/${at.substring(5, 7)} ${at.substring(11, 16)}"
