package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.FunctionSpec

/** A chip in the shape picker: a kind, with its own point count, knob values and heads. */
class ShapePreset(
    val key: String,
    val kind: ShapeKind,
    val n: Int = kind.defaultN,
    val adj: DoubleArray = kind.defaultAdj,
    val headStart: Head = kind.defaultHeadStart,
    val headEnd: Head = kind.defaultHeadEnd,
    /** For a free kind, the outline its chip shows, normalized to the icon's box. */
    val iconPath: List<com.xnotes.core.vector.VectorContour>? = null,
) {
    /** True when the picker offers a minus/plus for this chip's point or side count. */
    val counted: Boolean get() = kind.nRange != null
}

/** One page of the picker: a family's caption key and up to [ShapePresets.ROWS] rows of chips. */
class ShapePage(val key: String, val presets: List<ShapePreset>)

/** The chips the shape picker offers, page by page. */
object ShapePresets {

    const val ROWS = 4
    const val COLUMNS = 6

    /** The key of the chip that places a polygon corner by corner rather than by dragging a box. */
    const val FREE_POLYGON = "free_polygon"

    val basic = ShapePage(
        "basic",
        listOf(
            ShapePreset("line", ShapeKind.LINE),
            ShapePreset("arrow", ShapeKind.ARROW),
            ShapePreset("rectangle", ShapeKind.RECTANGLE),
            ShapePreset("rounded_rect", ShapeKind.ROUNDED_RECT),
            ShapePreset("square", ShapeKind.SQUARE),
            ShapePreset("capsule", ShapeKind.CAPSULE),
            ShapePreset("ellipse", ShapeKind.ELLIPSE),
            ShapePreset("circle", ShapeKind.CIRCLE),
            ShapePreset("triangle", ShapeKind.TRIANGLE),
            ShapePreset("right_triangle", ShapeKind.RIGHT_TRIANGLE),
            ShapePreset("diamond", ShapeKind.DIAMOND),
            ShapePreset("parallelogram", ShapeKind.PARALLELOGRAM),
            ShapePreset("trapezoid", ShapeKind.TRAPEZOID),
            ShapePreset("pentagon", ShapeKind.REGULAR_POLYGON, n = 5),
            ShapePreset("hexagon", ShapeKind.REGULAR_POLYGON, n = 6),
            ShapePreset("octagon", ShapeKind.REGULAR_POLYGON, n = 8),
            ShapePreset("star", ShapeKind.STAR),
            ShapePreset("cross", ShapeKind.CROSS),
            ShapePreset("heart", ShapeKind.HEART),
            ShapePreset("cloud", ShapeKind.CLOUD),
            ShapePreset("ring", ShapeKind.RING),
            ShapePreset("pie", ShapeKind.PIE),
            ShapePreset("arc", ShapeKind.ARC),
            ShapePreset(
                FREE_POLYGON, ShapeKind.POLYGON,
                iconPath = listOf(
                    FreePath.lines(listOf(Pt(0.05, 0.75), Pt(0.3, 0.05), Pt(0.8, 0.25), Pt(0.95, 0.8), Pt(0.45, 0.95)), closed = true),
                ),
            ),
        ),
    )

    val flowchart = ShapePage(
        "flowchart",
        listOf(
            ShapePreset("flow_process", ShapeKind.RECTANGLE),
            ShapePreset("flow_alt_process", ShapeKind.ROUNDED_RECT),
            ShapePreset("flow_decision", ShapeKind.DIAMOND),
            ShapePreset("flow_data", ShapeKind.PARALLELOGRAM),
            ShapePreset("flow_terminator", ShapeKind.CAPSULE),
            ShapePreset("flow_preparation", FlowchartKinds.PREPARATION),
            ShapePreset("flow_predefined", FlowchartKinds.PREDEFINED),
            ShapePreset("flow_internal_storage", FlowchartKinds.INTERNAL_STORAGE),
            ShapePreset("flow_document", FlowchartKinds.DOCUMENT),
            ShapePreset("flow_multidocument", FlowchartKinds.MULTIDOCUMENT),
            ShapePreset("flow_manual_input", FlowchartKinds.MANUAL_INPUT),
            ShapePreset("flow_manual_operation", FlowchartKinds.MANUAL_OPERATION),
            ShapePreset("flow_connector", ShapeKind.CIRCLE),
            ShapePreset("flow_off_page", FlowchartKinds.OFF_PAGE),
            ShapePreset("flow_database", FlowchartKinds.DATABASE),
            ShapePreset("flow_direct_access", FlowchartKinds.DIRECT_ACCESS),
            ShapePreset("flow_display", FlowchartKinds.DISPLAY),
            ShapePreset("flow_delay", FlowchartKinds.DELAY),
            ShapePreset("flow_stored_data", FlowchartKinds.STORED_DATA),
            ShapePreset("flow_sort", FlowchartKinds.SORT),
            ShapePreset("flow_merge", FlowchartKinds.MERGE),
            ShapePreset("flow_extract", ShapeKind.TRIANGLE),
            ShapePreset("flow_summing", FlowchartKinds.SUMMING),
            ShapePreset("flow_or", FlowchartKinds.OR),
        ),
    )

    val arrows = ShapePage(
        "arrows",
        listOf(
            ShapePreset("arrow_right", ArrowKinds.RIGHT),
            ShapePreset("arrow_left", ArrowKinds.LEFT),
            ShapePreset("arrow_up", ArrowKinds.UP),
            ShapePreset("arrow_down", ArrowKinds.DOWN),
            ShapePreset("arrow_left_right", ArrowKinds.LEFT_RIGHT),
            ShapePreset("arrow_up_down", ArrowKinds.UP_DOWN),
            ShapePreset("arrow_quad", ArrowKinds.QUAD),
            ShapePreset("arrow_bent", ArrowKinds.BENT),
            ShapePreset("arrow_u_turn", ArrowKinds.U_TURN),
            ShapePreset("arrow_chevron", ArrowKinds.CHEVRON),
            ShapePreset("arrow_pentagon", ArrowKinds.PENTAGON),
            ShapePreset("arrow_notched", ArrowKinds.NOTCHED),
            ShapePreset("arrow_curved_right", ArrowKinds.CURVED_RIGHT),
            ShapePreset("arrow_curved_left", ArrowKinds.CURVED_LEFT),
            ShapePreset("arrow_circular", ArrowKinds.CIRCULAR),
            ShapePreset("arrow_striped", ArrowKinds.STRIPED),
            ShapePreset("arrow_bent_up", ArrowKinds.BENT_UP),
            ShapePreset("arrow_left_up", ArrowKinds.LEFT_UP),
            ShapePreset("callout_rect", ArrowKinds.RECT_CALLOUT),
            ShapePreset("callout_round", ArrowKinds.ROUND_CALLOUT),
            ShapePreset("callout_oval", ArrowKinds.OVAL_CALLOUT),
            ShapePreset("callout_thought", ArrowKinds.THOUGHT),
            ShapePreset("callout_line", ArrowKinds.LINE_CALLOUT),
            ShapePreset("burst", ArrowKinds.BURST),
        ),
    )

    val solidsMath = ShapePage(
        "solids_math",
        listOf(
            ShapePreset("solid_cube", SolidMathKinds.CUBE),
            ShapePreset("solid_cylinder", SolidMathKinds.CYLINDER),
            ShapePreset("solid_cone", SolidMathKinds.CONE),
            ShapePreset("solid_sphere", SolidMathKinds.SPHERE),
            ShapePreset("solid_pyramid", SolidMathKinds.PYRAMID),
            ShapePreset("solid_tetrahedron", SolidMathKinds.TETRAHEDRON),
            ShapePreset("solid_tri_prism", SolidMathKinds.TRI_PRISM),
            ShapePreset("solid_hex_prism", SolidMathKinds.HEX_PRISM),
            ShapePreset("solid_frustum", SolidMathKinds.FRUSTUM),
            ShapePreset("solid_hemisphere", SolidMathKinds.HEMISPHERE),
            ShapePreset("math_axes_2d", SolidMathKinds.AXES_2D),
            ShapePreset("math_axes_3d", SolidMathKinds.AXES_3D),
            ShapePreset("math_number_line", SolidMathKinds.NUMBER_LINE),
            ShapePreset("math_grid", SolidMathKinds.GRID),
            ShapePreset("math_angle", SolidMathKinds.ANGLE),
            ShapePreset("math_right_angle", SolidMathKinds.RIGHT_ANGLE),
            ShapePreset("math_curly_brace", SolidMathKinds.CURLY_BRACE),
            ShapePreset("math_square_bracket", SolidMathKinds.SQUARE_BRACKET),
            ShapePreset("math_round_bracket", SolidMathKinds.ROUND_BRACKET),
        ),
    )

    /** What a function chip's key starts with; the rest is the expression it plots. */
    const val FUNCTION_PREFIX = "fn:"

    val functions = ShapePage(
        "functions",
        FunctionSpec.PRESETS.map { spec ->
            ShapePreset(FUNCTION_PREFIX + spec.expr, ShapeKind.FUNCTION, iconPath = spec.normalizedRuns()?.map { FreePath.lines(it, false) })
        },
    )

    val pages: List<ShapePage> = listOf(basic, flowchart, arrows, solidsMath, functions)

    val all: List<ShapePreset> = pages.flatMap { it.presets }

    private val byKey = all.associateBy { it.key }

    fun byKey(key: String?): ShapePreset? = key?.let { byKey[it] }

    /** The chip a setting written before chips existed names by its bare kind. */
    fun forKind(kind: ShapeKind): ShapePreset = all.firstOrNull { it.kind == kind } ?: byKey.getValue("rectangle")

    /** The page [preset] sits on. */
    fun pageOf(preset: ShapePreset): Int = pages.indexOfFirst { preset in it.presets }.coerceAtLeast(0)
}
