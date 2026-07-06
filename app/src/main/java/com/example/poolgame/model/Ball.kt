package com.example.poolgame.model

/**
 * A ball on the table. Position/velocity are mutable since the physics
 * simulator updates them every frame while the ball is moving.
 */
data class Ball(
    val id: Int,
    var position: Vec2,
    val radius: Float,
    val isCue: Boolean = false,
    var velocity: Vec2 = Vec2(0f, 0f),
    var pocketed: Boolean = false,
    val colorArgb: Int = 0xFFD03C28.toInt()
) {
    val isMoving: Boolean
        get() = velocity.length() > PhysicsConstants.STOP_THRESHOLD
}

/** Shared tunable constants for the physics simulation. */
object PhysicsConstants {
    /** Deceleration applied to every moving ball, in units/sec^2 (rolling friction). */
    const val FRICTION_DECEL = 900f
    /** Below this speed a ball is considered stopped, to avoid infinite creep. */
    const val STOP_THRESHOLD = 4f
    /** Speed retained after bouncing off a cushion. */
    const val CUSHION_RESTITUTION = 0.92f
    /** Max speed a shot can impart to the cue ball, units/sec. Tune to your world scale. */
    const val MAX_SHOT_SPEED = 2200f
}
