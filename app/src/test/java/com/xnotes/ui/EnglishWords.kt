package com.xnotes.ui

import java.time.DayOfWeek
import java.time.Month

/** The explorer's English wording, standing in for the string resources on the plain JVM. */
internal object EnglishWords : ExplorerWords {
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    override fun month(m: Month) = months[m.value - 1]
    override fun shortMonth(m: Month) = months[m.value - 1].take(3)
    override fun shortWeekday(d: DayOfWeek) = weekdays[d.value - 1]
    override val today = "Today"
    override val yesterday = "Yesterday"
    override val earlierThisWeek = "Earlier this week"
    override val lastWeek = "Last week"
    override fun earlierIn(m: Month) = "Earlier in ${month(m)}"
    override fun monthYear(m: Month, year: Int) = "${month(m)} $year"
    override val justNow = "Just now"
    override fun minutesAgo(n: Long) = "$n min ago"
    override fun hoursAgo(n: Long) = "$n h ago"
    override fun daysAgo(n: Long) = if (n == 1L) "1 day ago" else "$n days ago"
    override fun dayMonth(day: Int, m: Month) = "$day ${shortMonth(m)}"
    override fun dayMonthYear(day: Int, m: Month, year: Int) = "$day ${shortMonth(m)} $year"
    override fun withTime(date: String, clock: String) = "$date, $clock"
    override fun clock12(hour: Int, minute: Int, pm: Boolean) = "%d:%02d %s".format(hour, minute, if (pm) "PM" else "AM")
    override fun opened(ago: String) = "Opened $ago"
    override fun folders(n: Int) = if (n == 1) "1 folder" else "$n folders"
    override fun files(n: Int) = if (n == 1) "1 file" else "$n files"
    override fun items(n: Int) = if (n == 1) "1 item" else "$n items"
    override fun kinds(k: EntryKind) = when (k) {
        EntryKind.FOLDER -> "Folders"
        EntryKind.NOTE -> "Notes"
        EntryKind.PDF -> "PDF notes"
        EntryKind.CANVAS -> "Canvases"
        EntryKind.FILE -> "Read-only files"
    }
    override fun hue(h: Hue) = h.name.lowercase().replaceFirstChar { it.uppercase() }
    override val noColour = "No colour"
}
