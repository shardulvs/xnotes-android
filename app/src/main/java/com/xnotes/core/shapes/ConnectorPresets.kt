package com.xnotes.core.shapes

import com.xnotes.core.model.Routing

/**
 * A chip in the connector picker: how it routes and the heads it ends in. A UML chip also says
 * whether its line is dashed, since that is half of what it means; the others leave the dash alone.
 */
class ConnectorPreset(
    val key: String,
    val routing: Routing,
    val headStart: Head = Head.NONE,
    val headEnd: Head = Head.NONE,
    val dashed: Boolean? = null,
)

/** One page of the connector picker: a caption key and up to [ConnectorPresets.ROWS] rows of chips. */
class ConnectorPage(val key: String, val presets: List<ConnectorPreset>)

/** The chips the connector picker offers, page by page. */
object ConnectorPresets {

    const val ROWS = 2
    const val COLUMNS = 6

    private fun family(stem: String, routing: Routing) = listOf(
        ConnectorPreset(stem, routing),
        ConnectorPreset("${stem}_arrow", routing, headEnd = Head.OPEN),
        ConnectorPreset("${stem}_both", routing, headStart = Head.OPEN, headEnd = Head.OPEN),
    )

    val basic = ConnectorPage(
        "connectors",
        family("conn_straight", Routing.STRAIGHT) + family("conn_elbow", Routing.ELBOW) +
            family("conn_curved", Routing.CURVED) + family("conn_rounded", Routing.ROUNDED),
    )

    private fun uml(suffix: String, routing: Routing) = listOf(
        ConnectorPreset("uml_association$suffix", routing, headEnd = Head.OPEN, dashed = false),
        ConnectorPreset("uml_inheritance$suffix", routing, headEnd = Head.HOLLOW_TRIANGLE, dashed = false),
        ConnectorPreset("uml_realization$suffix", routing, headEnd = Head.HOLLOW_TRIANGLE, dashed = true),
        ConnectorPreset("uml_dependency$suffix", routing, headEnd = Head.OPEN, dashed = true),
        ConnectorPreset("uml_aggregation$suffix", routing, headStart = Head.HOLLOW_DIAMOND, dashed = false),
        ConnectorPreset("uml_composition$suffix", routing, headStart = Head.FILLED_DIAMOND, dashed = false),
    )

    val umlPage = ConnectorPage("uml", uml("", Routing.STRAIGHT) + uml("_elbow", Routing.ELBOW))

    val pages: List<ConnectorPage> = listOf(basic, umlPage)

    val all: List<ConnectorPreset> = pages.flatMap { it.presets }

    private val byKey = all.associateBy { it.key }

    fun byKey(key: String?): ConnectorPreset? = key?.let { byKey[it] }

    /** The pages left once the [hidden] chips and pages (by key) are taken out, each without its hidden chips. */
    fun shown(hidden: Set<String>): List<ConnectorPage> =
        if (hidden.isEmpty()) pages
        else pages.filter { it.key !in hidden }.map { page -> ConnectorPage(page.key, page.presets.filter { it.key !in hidden }) }.filter { it.presets.isNotEmpty() }

    /** The page [preset] sits on. */
    fun pageOf(preset: ConnectorPreset): Int = pages.indexOfFirst { preset in it.presets }.coerceAtLeast(0)
}
