package com.example.poolgame.engine

import com.example.poolgame.model.*
import kotlin.math.sqrt

/** One straight leg of a projected path (either the direct shot or a post-cushion bank). */
data class PathSegment(val start: Vec2, val end: Vec2, val isBank: Boolean)

/** Result of a ball-to-ball collision prediction along the aim ray. */
data class BallImpact(
    val targetBall: Ball,
    val contactPoint: Vec2,       // point where cue ball's center sits at moment of impact (ghost ball center)
    val outgoingDirection: Vec2,  // direction the target ball travels after impact
    val pocketedInto: Pocket?     // non-null if the outgoing path runs directly into a pocket
)

data class TrajectoryResult(
    val segments: List<PathSegment>,
    val impact: BallImpact?
)

/**
 * Pure geometry/physics engine. Takes world-space state that the game itself
 * owns (table dimensions, ball positions) and returns a predicted path.
 * No screen capture, no image recognition — everything here is exact math
 * over known coordinates, the way a game's own aim-assist feature would work.
 */
object TrajectoryEngine {

    private const val EPS = 1e-4f
    /** Energy/speed retained per cushion bounce (structural friction). */
    private const val CUSHION_RESTITUTION = 0.95f

    /**
     * Projects the cue ball's path from [origin] in [direction], bouncing off
     * cushions up to [maxBounces] times, and stopping early if it strikes
     * another ball. [remainingBalls] should exclude the cue ball itself.
     */
    fun project(
        table: Table,
        cueBall: Ball,
        direction: Vec2,
        remainingBalls: List<Ball>,
        maxBounces: Int = 3
    ): TrajectoryResult {
        val segments = mutableListOf<PathSegment>()
        val activeBalls = remainingBalls.filter { !it.pocketed }
        // Cushions offset inward by the ball radius: the ball's *center*
        // can never get closer to a rail than one radius.
        val effectiveCushions = table.cushions.map { offsetInward(it, cueBall.radius) }

        var origin = cueBall.position
        var dir = direction.normalized()
        var bounce = 0
        var impact: BallImpact? = null

        while (bounce <= maxBounces) {
            val ballHit = nearestBallIntersection(origin, dir, cueBall.radius, activeBalls)
            val cushionHit = nearestCushionIntersection(origin, dir, effectiveCushions)

            val ballDist = ballHit?.let { (it.first - origin).length() } ?: Float.MAX_VALUE
            val cushionDist = cushionHit?.let { (it.first - origin).length() } ?: Float.MAX_VALUE

            if (ballHit != null && ballDist < cushionDist) {
                // Cue ball reaches another ball before hitting a rail.
                segments.add(PathSegment(origin, ballHit.first, isBank = bounce > 0))
                impact = computeBallImpact(ballHit.first, ballHit.second, dir, table)
                break
            } else if (cushionHit != null) {
                val (hitPoint, segment) = cushionHit
                segments.add(PathSegment(origin, hitPoint, isBank = bounce > 0))
                val normal = segment.normal()
                dir = dir.reflect(normal).normalized()
                // Speed bleeds off each bounce; we still draw full remaining
                // length, but you can scale visual alpha/length by this if desired.
                origin = hitPoint + dir * EPS
                bounce++
            } else {
                // Should not happen inside a closed table, but guard anyway.
                break
            }
        }

        return TrajectoryResult(segments, impact)
    }

    /** Moves a cushion segment inward (toward the table center) by [distance]. */
    private fun offsetInward(segment: CushionSegment, distance: Float): CushionSegment {
        val inward = segment.normal() * -1f // normal() points outward; flip it
        val offset = inward * distance
        return CushionSegment(segment.a + offset, segment.b + offset)
    }

    /**
     * Finds the closest point along the ray (origin, dir) where it intersects
     * any cushion segment. Returns the hit point plus the segment hit.
     */
    private fun nearestCushionIntersection(
        origin: Vec2,
        dir: Vec2,
        cushions: List<CushionSegment>
    ): Pair<Vec2, CushionSegment>? {
        var closest: Pair<Vec2, CushionSegment>? = null
        var closestDist = Float.MAX_VALUE

        for (seg in cushions) {
            val hit = rayIntersectSegment(origin, dir, seg.a, seg.b) ?: continue
            val dist = (hit - origin).length()
            if (dist > EPS && dist < closestDist) {
                closestDist = dist
                closest = hit to seg
            }
        }
        return closest
    }

    /** Ray vs. line-segment intersection using standard parametric form. */
    private fun rayIntersectSegment(
        origin: Vec2, dir: Vec2, segA: Vec2, segB: Vec2
    ): Vec2? {
        val v1 = origin - segA
        val v2 = segB - segA
        val v3 = Vec2(-dir.y, dir.x) // perpendicular of ray direction

        val denom = v2.dot(v3)
        if (kotlin.math.abs(denom) < EPS) return null // parallel

        val t1 = (v2.x * v1.y - v2.y * v1.x) / denom // distance along ray
        val t2 = v1.dot(v3) / denom                  // position along segment [0,1]

        if (t1 < EPS || t2 < 0f || t2 > 1f) return null
        return origin + dir * t1
    }

    /**
     * Finds the nearest ball (by center) whose surface the ray intersects,
     * accounting for both ball radii (cue ball radius + target ball radius,
     * since we care about when their surfaces touch).
     */
    private fun nearestBallIntersection(
        origin: Vec2, dir: Vec2, cueRadius: Float, balls: List<Ball>
    ): Pair<Vec2, Ball>? {
        var closest: Pair<Vec2, Ball>? = null
        var closestDist = Float.MAX_VALUE

        for (ball in balls) {
            val combinedRadius = cueRadius + ball.radius
            val toCenter = ball.position - origin
            val projLength = toCenter.dot(dir)
            if (projLength < 0f) continue // ball is behind the ray

            val closestApproachSq = toCenter.dot(toCenter) - projLength * projLength
            val radiusSq = combinedRadius * combinedRadius
            if (closestApproachSq > radiusSq) continue // ray passes outside the ball

            val backOffset = sqrt(radiusSq - closestApproachSq)
            val hitDist = projLength - backOffset
            if (hitDist < EPS) continue

            if (hitDist < closestDist) {
                closestDist = hitDist
                val cueCenterAtImpact = origin + dir * hitDist
                closest = cueCenterAtImpact to ball
            }
        }
        return closest
    }

    /**
     * Given the cue ball's center at the moment of impact and the target ball,
     * computes the target ball's outgoing direction (along the line connecting
     * the two centers at contact — standard "ghost ball" model) and checks
     * whether that path runs directly into a pocket.
     */
    private fun computeBallImpact(
        cueCenterAtImpact: Vec2,
        target: Ball,
        incomingDir: Vec2,
        table: Table
    ): BallImpact {
        val outgoingDir = (target.position - cueCenterAtImpact).normalized()
        val pocket = firstPocketAlongPath(target.position, outgoingDir, table)
        return BallImpact(
            targetBall = target,
            contactPoint = cueCenterAtImpact,
            outgoingDirection = outgoingDir,
            pocketedInto = pocket
        )
    }

    /**
     * Walks the straight path from [start] in [dir] and returns the first
     * pocket whose mouth the path passes through, or null if none.
     */
    private fun firstPocketAlongPath(start: Vec2, dir: Vec2, table: Table): Pocket? {
        for (pocket in table.pockets) {
            val toCenter = pocket.center - start
            val projLength = toCenter.dot(dir)
            if (projLength < 0f) continue
            val closestApproachSq = toCenter.dot(toCenter) - projLength * projLength
            if (closestApproachSq <= pocket.radius * pocket.radius) {
                return pocket
            }
        }
        return null
    }
}
