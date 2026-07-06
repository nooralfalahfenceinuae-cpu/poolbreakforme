package com.example.poolgame.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.poolgame.engine.PhysicsSimulator
import com.example.poolgame.engine.TrajectoryEngine
import com.example.poolgame.model.Ball
import com.example.poolgame.model.PhysicsConstants
import com.example.poolgame.model.Table
import com.example.poolgame.model.Vec2
import kotlin.math.min

/**
 * A fully playable single-view pool prototype:
 *  - Renders table, balls, pockets
 *  - Touch-drag from the cue ball aims (shows live trajectory preview)
 *  - Releasing the drag shoots the cue ball; power scales with drag distance
 *  - Runs a real physics simulation (friction, cushions, collisions, pockets)
 *    every frame until all balls stop, then re-enables aiming
 *
 * This is a starting point for a real game, not a finished product — turn
 * order, fouls, ball-type assignment (solids/stripes), and win conditions
 * are left for you to layer on top of this loop.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private var renderThread: Thread? = null
    @Volatile private var running = false

    private val table = Table(playWidth = 2000f, playHeight = 1000f, ballRadius = 28f)
    private val cueBall = Ball(id = 0, position = Vec2(500f, 500f), radius = 28f, isCue = true, colorArgb = Color.WHITE)
    private val objectBalls: MutableList<Ball> = buildRack()

    private val allBalls: List<Ball> get() = listOf(cueBall) + objectBalls

    // Aiming state
    private var isAiming = false
    private var dragCurrent = Vec2(0f, 0f)
    private var isSimulating = false

    // World <-> screen transform
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val feltPaint = Paint().apply { color = Color.rgb(20, 110, 60) }
    private val cushionPaint = Paint().apply {
        color = Color.rgb(90, 60, 30); style = Paint.Style.STROKE; strokeWidth = 10f
    }
    private val pocketPaint = Paint().apply { color = Color.BLACK }
    private val aimLinePaint = Paint().apply {
        color = Color.argb(230, 255, 255, 255); strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val bankLinePaint = Paint().apply {
        color = Color.argb(230, 190, 255, 60); strokeWidth = 4f; style = Paint.Style.STROKE
        isAntiAlias = true; pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val ghostBallPaint = Paint().apply {
        color = Color.argb(160, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    }
    private val potPaint = Paint().apply {
        color = Color.argb(230, 255, 210, 40); strokeWidth = 5f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val ballPaintCache = mutableMapOf<Int, Paint>()

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    private fun buildRack(): MutableList<Ball> {
        // Simple triangle rack of 6 object balls (a small subset for a quick playable demo).
        val startX = 1400f
        val startY = 500f
        val spacing = 60f
        val positions = listOf(
            Vec2(startX, startY),
            Vec2(startX + spacing, startY - spacing / 2f),
            Vec2(startX + spacing, startY + spacing / 2f),
            Vec2(startX + spacing * 2, startY - spacing),
            Vec2(startX + spacing * 2, startY),
            Vec2(startX + spacing * 2, startY + spacing)
        )
        val colors = listOf(
            Color.rgb(220, 60, 40), Color.rgb(40, 90, 220), Color.rgb(230, 200, 30),
            Color.rgb(120, 40, 160), Color.rgb(230, 130, 30), Color.rgb(30, 140, 60)
        )
        return positions.mapIndexed { i, p ->
            Ball(id = i + 1, position = p, radius = table.ballRadius, colorArgb = colors[i])
        }.toMutableList()
    }

    // --- Surface lifecycle ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        computeTransform()
        running = true
        renderThread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        computeTransform()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        renderThread?.join()
        renderThread = null
    }

    private fun computeTransform() {
        val pad = 40f
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        if (availW <= 0f || availH <= 0f) return
        scale = min(availW / table.playWidth, availH / table.playHeight)
        offsetX = (width - table.playWidth * scale) / 2f
        offsetY = (height - table.playHeight * scale) / 2f
    }

    private fun worldToScreen(p: Vec2) = Vec2(p.x * scale + offsetX, p.y * scale + offsetY)
    private fun screenToWorld(p: Vec2) = Vec2((p.x - offsetX) / scale, (p.y - offsetY) / scale)

    // --- Touch input: drag from behind the cue ball to aim, release to shoot ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isSimulating) return true // ignore input while balls are moving

        val worldTouch = screenToWorld(Vec2(event.x, event.y))
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isAiming = true
                dragCurrent = worldTouch
            }
            MotionEvent.ACTION_MOVE -> {
                if (isAiming) dragCurrent = worldTouch
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isAiming) {
                    shoot(worldTouch)
                    isAiming = false
                }
            }
        }
        return true
    }

    /** Aim direction is from the touch point back toward the cue ball (like pulling a cue stick back). */
    private fun currentAimDirection(): Vec2? {
        val toTouch = dragCurrent - cueBall.position
        if (toTouch.length() < 1e-3f) return null
        return toTouch // pointing from cue ball toward drag point = shot direction
    }

    private fun shoot(releasePoint: Vec2) {
        val toRelease = releasePoint - cueBall.position
        val dragDist = toRelease.length()
        if (dragDist < 1f) return

        // Power scales with drag distance, capped at MAX_SHOT_SPEED.
        val power = min(dragDist * 2.5f, PhysicsConstants.MAX_SHOT_SPEED)
        cueBall.velocity = toRelease.normalized() * power
        isSimulating = true
    }

    // --- Game loop ---

    override fun run() {
        var lastTime = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = min((now - lastTime) / 1_000_000_000f, 1f / 30f) // clamp dt to avoid spiral of death
            lastTime = now

            if (isSimulating) {
                val stillMoving = PhysicsSimulator.step(table, allBalls, dt)
                if (!stillMoving) isSimulating = false
            }

            val canvas = holder.lockCanvas() ?: continue
            try {
                renderFrame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }

            // Simple frame pacing (~60fps target).
            val frameMillis = (now - System.nanoTime()) / 1_000_000L + 16L
            if (frameMillis > 0) Thread.sleep(frameMillis)
        }
    }

    private fun renderFrame(canvas: Canvas) {
        canvas.drawColor(Color.rgb(15, 15, 15)) // letterbox background

        drawTable(canvas)
        drawBalls(canvas)

        if (isAiming && !isSimulating) {
            drawAimPreview(canvas)
        }
    }

    private fun drawTable(canvas: Canvas) {
        val tl = worldToScreen(Vec2(0f, 0f))
        val br = worldToScreen(Vec2(table.playWidth, table.playHeight))
        canvas.drawRect(tl.x, tl.y, br.x, br.y, feltPaint)
        canvas.drawRect(tl.x, tl.y, br.x, br.y, cushionPaint)
        for (pocket in table.pockets) {
            val c = worldToScreen(pocket.center)
            canvas.drawCircle(c.x, c.y, pocket.radius * scale, pocketPaint)
        }
    }

    private fun drawBalls(canvas: Canvas) {
        for (ball in allBalls) {
            if (ball.pocketed) continue
            val c = worldToScreen(ball.position)
            val paint = ballPaintCache.getOrPut(ball.colorArgb) { Paint().apply { color = ball.colorArgb } }
            canvas.drawCircle(c.x, c.y, ball.radius * scale, paint)
        }
    }

    private fun drawAimPreview(canvas: Canvas) {
        val dir = currentAimDirection() ?: return
        val result = TrajectoryEngine.project(table, cueBall, dir, objectBalls, maxBounces = 3)

        for (seg in result.segments) {
            val a = worldToScreen(seg.start)
            val b = worldToScreen(seg.end)
            canvas.drawLine(a.x, a.y, b.x, b.y, if (seg.isBank) bankLinePaint else aimLinePaint)
        }

        result.impact?.let { impact ->
            val ghost = worldToScreen(impact.contactPoint)
            canvas.drawCircle(ghost.x, ghost.y, cueBall.radius * scale, ghostBallPaint)

            val targetScreen = worldToScreen(impact.targetBall.position)
            val outEnd = impact.targetBall.position + impact.outgoingDirection * (table.playWidth + table.playHeight)
            val outEndScreen = worldToScreen(outEnd)
            canvas.drawLine(
                targetScreen.x, targetScreen.y, outEndScreen.x, outEndScreen.y,
                if (impact.pocketedInto != null) potPaint else bankLinePaint
            )
        }
    }
}
