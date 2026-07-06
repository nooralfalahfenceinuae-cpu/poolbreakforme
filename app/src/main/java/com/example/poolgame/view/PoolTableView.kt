package com.example.poolgame.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.poolgame.engine.TrajectoryEngine
import com.example.poolgame.model.Ball
import com.example.poolgame.model.Table
import com.example.poolgame.model.Vec2

/**
 * Renders the table, balls, and (optionally) a predicted aim trajectory.
 * This is a normal in-game view — it draws using your game's own world
 * coordinates via [worldToScreen], not screen-captured pixels.
 *
 * Wire it up by calling [setState] whenever the cue ball's aim direction
 * or the ball layout changes (e.g. from touch-drag input during aiming).
 */
class PoolTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var table: Table? = null
    private var cueBall: Ball? = null
    private var otherBalls: List<Ball> = emptyList()
    private var aimDirection: Vec2? = null

    // World-space play area is mapped onto the view's pixel canvas.
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val feltPaint = Paint().apply { color = Color.rgb(20, 110, 60) }
    private val cushionPaint = Paint().apply {
        color = Color.rgb(90, 60, 30)
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val pocketPaint = Paint().apply { color = Color.BLACK }
    private val cueBallPaint = Paint().apply { color = Color.WHITE }
    private val ballPaint = Paint().apply { color = Color.rgb(200, 60, 40) }

    private val aimLinePaint = Paint().apply {
        color = Color.argb(230, 255, 255, 255)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val bankLinePaint = Paint().apply {
        color = Color.argb(230, 190, 255, 60) // neon green
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val ghostBallPaint = Paint().apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val potPaint = Paint().apply {
        color = Color.argb(230, 255, 210, 40) // gold — indicates a lined-up pot
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    /** Called by your game/input layer whenever the aim or table state changes. */
    fun setState(table: Table, cueBall: Ball, otherBalls: List<Ball>, aimDirection: Vec2?) {
        this.table = table
        this.cueBall = cueBall
        this.otherBalls = otherBalls
        this.aimDirection = aimDirection
        computeTransform()
        invalidate()
    }

    private fun computeTransform() {
        val t = table ?: return
        val pad = 40f
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        if (availW <= 0f || availH <= 0f) return
        scale = minOf(availW / t.playWidth, availH / t.playHeight)
        offsetX = (width - t.playWidth * scale) / 2f
        offsetY = (height - t.playHeight * scale) / 2f
    }

    private fun worldToScreen(p: Vec2): Vec2 =
        Vec2(p.x * scale + offsetX, p.y * scale + offsetY)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeTransform()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = table ?: return
        val cue = cueBall ?: return

        drawTable(canvas, t)
        drawBalls(canvas, cue)

        val dir = aimDirection ?: return
        val result = TrajectoryEngine.project(t, cue, dir, otherBalls, maxBounces = 3)

        for (seg in result.segments) {
            val a = worldToScreen(seg.start)
            val b = worldToScreen(seg.end)
            val paint = if (seg.isBank) bankLinePaint else aimLinePaint
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }

        result.impact?.let { impact ->
            val ghost = worldToScreen(impact.contactPoint)
            val radiusPx = cue.radius * scale
            canvas.drawCircle(ghost.x, ghost.y, radiusPx, ghostBallPaint)

            // Draw the target ball's predicted outgoing path.
            val targetScreen = worldToScreen(impact.targetBall.position)
            val outEnd = impact.targetBall.position + impact.outgoingDirection * (t.playWidth + t.playHeight)
            val outEndScreen = worldToScreen(outEnd)
            val outPaint = if (impact.pocketedInto != null) potPaint else bankLinePaint
            canvas.drawLine(targetScreen.x, targetScreen.y, outEndScreen.x, outEndScreen.y, outPaint)
        }
    }

    private fun drawTable(canvas: Canvas, t: Table) {
        val tl = worldToScreen(Vec2(0f, 0f))
        val br = worldToScreen(Vec2(t.playWidth, t.playHeight))
        canvas.drawRect(tl.x, tl.y, br.x, br.y, feltPaint)
        canvas.drawRect(tl.x, tl.y, br.x, br.y, cushionPaint)

        for (pocket in t.pockets) {
            val c = worldToScreen(pocket.center)
            canvas.drawCircle(c.x, c.y, pocket.radius * scale, pocketPaint)
        }
    }

    private fun drawBalls(canvas: Canvas, cue: Ball) {
        for (ball in otherBalls) {
            val c = worldToScreen(ball.position)
            canvas.drawCircle(c.x, c.y, ball.radius * scale, ballPaint)
        }
        val cueScreen = worldToScreen(cue.position)
        canvas.drawCircle(cueScreen.x, cueScreen.y, cue.radius * scale, cueBallPaint)
    }
}
