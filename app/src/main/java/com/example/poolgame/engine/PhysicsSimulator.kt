package com.example.poolgame.engine

import com.example.poolgame.model.Ball
import com.example.poolgame.model.PhysicsConstants
import com.example.poolgame.model.Table
import com.example.poolgame.model.Vec2
import kotlin.math.max

/**
 * Steps the real physics simulation forward by [dt] seconds: friction,
 * cushion rebounds, ball-to-ball elastic collisions, and pocket capture.
 * This runs every frame after a shot is taken, independent of the
 * predictive [TrajectoryEngine] used while aiming.
 */
object PhysicsSimulator {

    /** Advances all balls by one physics tick. Returns true if any ball is still moving. */
    fun step(table: Table, balls: List<Ball>, dt: Float): Boolean {
        var anyMoving = false

        for (ball in balls) {
            if (ball.pocketed) continue
            applyFriction(ball, dt)
            if (ball.isMoving) {
                anyMoving = true
                integrate(ball, dt)
                resolveCushionCollision(table, ball)
            }
        }

        resolveBallCollisions(balls)
        resolvePockets(table, balls)

        return anyMoving
    }

    private fun applyFriction(ball: Ball, dt: Float) {
        val speed = ball.velocity.length()
        if (speed <= PhysicsConstants.STOP_THRESHOLD) {
            ball.velocity = Vec2(0f, 0f)
            return
        }
        val newSpeed = max(0f, speed - PhysicsConstants.FRICTION_DECEL * dt)
        ball.velocity = if (newSpeed <= PhysicsConstants.STOP_THRESHOLD) {
            Vec2(0f, 0f)
        } else {
            ball.velocity.normalized() * newSpeed
        }
    }

    private fun integrate(ball: Ball, dt: Float) {
        ball.position = ball.position + ball.velocity * dt
    }

    /**
     * Table is an axis-aligned rectangle (see Table.kt), so cushion collision
     * reduces to bounds checking against the four straight rails, reflecting
     * whichever velocity component drove the ball past the boundary.
     */
    private fun resolveCushionCollision(table: Table, ball: Ball) {
        val r = ball.radius
        var pos = ball.position
        var vel = ball.velocity
        var bounced = false

        if (pos.x - r < 0f) {
            pos = Vec2(r, pos.y)
            vel = Vec2(-vel.x * PhysicsConstants.CUSHION_RESTITUTION, vel.y)
            bounced = true
        } else if (pos.x + r > table.playWidth) {
            pos = Vec2(table.playWidth - r, pos.y)
            vel = Vec2(-vel.x * PhysicsConstants.CUSHION_RESTITUTION, vel.y)
            bounced = true
        }

        if (pos.y - r < 0f) {
            pos = Vec2(pos.x, r)
            vel = Vec2(vel.x, -vel.y * PhysicsConstants.CUSHION_RESTITUTION)
            bounced = true
        } else if (pos.y + r > table.playHeight) {
            pos = Vec2(pos.x, table.playHeight - r)
            vel = Vec2(vel.x, -vel.y * PhysicsConstants.CUSHION_RESTITUTION)
            bounced = true
        }

        if (bounced) {
            ball.position = pos
            ball.velocity = vel
        }
    }

    /**
     * Resolves all overlapping ball pairs using an equal-mass elastic
     * collision: velocity components along the collision normal are
     * exchanged, tangential components are untouched.
     */
    private fun resolveBallCollisions(balls: List<Ball>) {
        val active = balls.filter { !it.pocketed }
        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                val a = active[i]
                val b = active[j]
                val delta = b.position - a.position
                val dist = delta.length()
                val minDist = a.radius + b.radius
                if (dist <= 0f || dist >= minDist) continue

                val normal = delta.normalized()

                // Separate overlapping balls so they don't stick/tunnel.
                val overlap = minDist - dist
                val correction = normal * (overlap / 2f)
                a.position = a.position - correction
                b.position = b.position + correction

                // Equal-mass elastic collision: swap the velocity components
                // along the normal; tangential components are unchanged.
                val aNormalSpeed = a.velocity.dot(normal)
                val bNormalSpeed = b.velocity.dot(normal)
                val aTangent = a.velocity - normal * aNormalSpeed
                val bTangent = b.velocity - normal * bNormalSpeed

                a.velocity = aTangent + normal * bNormalSpeed
                b.velocity = bTangent + normal * aNormalSpeed
            }
        }
    }

    private fun resolvePockets(table: Table, balls: List<Ball>) {
        for (ball in balls) {
            if (ball.pocketed) continue
            for (pocket in table.pockets) {
                val dist = (ball.position - pocket.center).length()
                if (dist <= pocket.radius) {
                    ball.pocketed = true
                    ball.velocity = Vec2(0f, 0f)
                    break
                }
            }
        }
    }
}
