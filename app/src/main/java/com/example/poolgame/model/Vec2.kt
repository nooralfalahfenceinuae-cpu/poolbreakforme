package com.example.poolgame.model

import kotlin.math.sqrt

/**
 * Lightweight 2D vector. Used for positions, directions, and velocities
 * throughout the physics engine. Kept separate from android.graphics.PointF
 * so the math module has zero Android framework dependencies and can be
 * unit-tested on the JVM without an emulator.
 */
data class Vec2(val x: Float, val y: Float) {

    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)

    fun dot(o: Vec2): Float = x * o.x + y * o.y

    fun length(): Float = sqrt(x * x + y * y)

    fun normalized(): Vec2 {
        val len = length()
        return if (len < 1e-6f) Vec2(0f, 0f) else Vec2(x / len, y / len)
    }

    /** Perpendicular vector, rotated 90 degrees counter-clockwise. */
    fun perpendicular(): Vec2 = Vec2(-y, x)

    /**
     * Reflects this vector (treated as a direction) off a surface with
     * the given unit normal, using r = d - 2(d.n)n.
     */
    fun reflect(normal: Vec2): Vec2 {
        val n = normal.normalized()
        val d = this
        val factor = 2f * d.dot(n)
        return Vec2(d.x - factor * n.x, d.y - factor * n.y)
    }
}
