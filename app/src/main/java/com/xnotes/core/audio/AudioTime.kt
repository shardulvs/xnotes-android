package com.xnotes.core.audio

import java.util.Locale

/** Times as the audio controls show them. */
object AudioTime {

    /** [ms] as minutes and seconds ("03:12"), with hours in front when [long] ("1:03:12"). */
    fun format(ms: Int, long: Boolean = ms >= 3_600_000): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val sec = s % 60
        val min = s / 60 % 60
        val hours = s / 3600
        return if (long || hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, min, sec) else String.format(Locale.ROOT, "%02d:%02d", s / 60, sec)
    }
}
