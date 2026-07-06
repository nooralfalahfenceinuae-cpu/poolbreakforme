package com.example.poolgame.model

/** A single straight cushion rail, defined by its two endpoints. */
data class CushionSegment(val a: Vec2, val b: Vec2) {

    /** Outward-facing unit normal of this rail (assumes table interior is "inside"). */
    fun normal(): Vec2 {
        val edge = (b - a).normalized()
        return edge.perpendicular().normalized()
    }
}

data class Pocket(val center: Vec2, val radius: Float)

/**
 * Table geometry expressed in the game's own world-space coordinates
 * (e.g. table-relative units or your game's logical playfield size) —
 * NOT screen pixels captured from another app. Your game supplies these
 * numbers directly since it owns the layout.
 */
class Table(
    val playWidth: Float,
    val playHeight: Float,
    val ballRadius: Float,
    val pocketRadius: Float = ballRadius * 1.6f
) {
    // Four corners of the playable area (cushion inner edge).
    private val topLeft = Vec2(0f, 0f)
    private val topRight = Vec2(playWidth, 0f)
    private val bottomLeft = Vec2(0f, playHeight)
    private val bottomRight = Vec2(playWidth, playHeight)

    val cushions: List<CushionSegment> = listOf(
        CushionSegment(topLeft, topRight),       // top rail
        CushionSegment(topRight, bottomRight),   // right rail
        CushionSegment(bottomRight, bottomLeft), // bottom rail
        CushionSegment(bottomLeft, topLeft)      // left rail
    )

    val pockets: List<Pocket> = listOf(
        Pocket(Vec2(0f, 0f), pocketRadius),
        Pocket(Vec2(playWidth / 2f, 0f), pocketRadius),
        Pocket(Vec2(playWidth, 0f), pocketRadius),
        Pocket(Vec2(0f, playHeight), pocketRadius),
        Pocket(Vec2(playWidth / 2f, playHeight), pocketRadius),
        Pocket(Vec2(playWidth, playHeight), pocketRadius)
    )
}
