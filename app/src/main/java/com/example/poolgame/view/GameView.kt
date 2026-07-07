package com.example.poolgame.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.poolgame.engine.PhysicsSimulator
import com.example.poolgame.engine.ScoringEngine
import com.example.poolgame.engine.TrajectoryEngine
import com.example.poolgame.model.Ball
import com.example.poolgame.model.PhysicsConstants
import com.example.poolgame.model.Table
import com.example.poolgame.model.Vec2
import kotlin.math.min

/**
 * A playable English Billiards prototype: striker's cue ball, opponent's
 * white ball, and the red ball. Scores cannons, winning hazards (potting an
 * object ball), and losing hazards (potting the cue ball after contact),
 * per standard English Billiards rules.
 *
 *  - Touch-drag from the cue ball aims (live trajectory preview + power meter)
 *  - Release to shoot; power scales with drag distance
 *  - Real physics simulation runs until all balls stop, then scores the shot
 *  - Object balls respot when potted; cue ball respots in the "D" when potted
 *
 * Turn order, fouls beyond the basic no-contact case, and a full match/game
 * UI are left for you to layer on top of this.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private var renderThread: Thread? = null
    @Volatile private var running = false

    private val table = Table(playWidth = 2000f, playHeight = 1000f, ballRadius = 28f)

    // --- English Billiards ball ids ---
    private val cueId = 0
    private val opponentWhiteId = 1
    private val redId = 2

    // Fixed reference spots (world coordinates), matching a real billiards table layout.
    private val dSpot = Vec2(300f, 500f)          // cue ball starts/respots in the "D"
    private val centerSpot = Vec2(1000f, 500f)     // opponent white respot
    private val pyramidSpot = Vec2(1500f, 500f)    // red ball respot

    private val cueBall = Ball(id = cueId, position = dSpot, radius = table.ballRadius, isCue = true, colorArgb = Color.WHITE)
    private val opponentWhite = Ball(id = opponentWhiteId, position = centerSpot, radius = table.ballRadius, colorArgb = Color.rgb(235, 235, 210))
    private val redBall = Ball(id = redId, position = pyramidSpot, radius = table.ballRadius, colorArgb = Color.rgb(200, 30, 30))

    private val objectBalls: List<Ball> = listOf(opponentWhite, redBall)
    private val allBalls: List<Ball> get() = listOf(cueBall) + objectBalls

    // Aiming state
    private var isAiming = false
    private var dragCurrent = Vec2(0f, 0f)
    private var isSimulating = false

    /** Drag distance (world units) that counts as 100% power. Tune to taste. */
    private val maxDragForFullPower = 420f

    // Score tracking
    private var totalScore = 0
    private var lastShotSummary = ""
    private val shotContactedIds = mutableSetOf<Int>()
    private val shotPottedIds = mutableSetOf<Int>()

    // World <-> screen transform
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // --- Top HUD bar (New Rack / Table Color buttons) ---
    private val hudHeight = 160f
    private val resetButtonRect = RectF(30f, 30f, 260f, 90f)
    private val colorButtonRect = RectF(290f, 30f, 560f, 90f)
    private val feltColorOptions = listOf(
        Color.rgb(20, 110, 60),   // classic green
        Color.rgb(20, 70, 130),   // tournament blue
        Color.rgb(110, 20, 30),  // burgundy
        Color.rgb(30, 30, 30)    // black diamond
    )
    private var feltColorIndex = 0

    private val feltPaint = Paint().apply { color = feltColorOptions[feltColorIndex] }
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

    private val hudButtonPaint = Paint().apply { color = Color.argb(210, 40, 40, 40) }
    private val hudTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 30f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    private val scoreTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 42f; isAntiAlias = true; textAlign = Paint.Align.LEFT
    }
    private val shotSummaryPaint = Paint().apply {
        color = Color.argb(230, 230, 200, 60); textSize = 26f; isAntiAlias = true; textAlign = Paint.Align.LEFT
    }

    private val powerMeterBgPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val powerMeterFillPaint = Paint().apply { isAntiAlias = true }
    private val powerMeterTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 26f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    /** Resets all three balls back to their starting spots and clears the score. */
    private fun resetRack() {
        cueBall.position = dSpot; cueBall.velocity = Vec2(0f, 0f); cueBall.pocketed = false
        opponentWhite.position = centerSpot; opponentWhite.velocity = Vec2(0f, 0f); opponentWhite.pocketed = false
        redBall.position = pyramidSpot; redBall.velocity = Vec2(0f, 0f); redBall.pocketed = false

        totalScore = 0
        lastShotSummary = ""
        shotContactedIds.clear()
        shotPottedIds.clear()
        isSimulating = false
        isAiming = false
    }

    private fun cycleFeltColor() {
        feltColorIndex = (feltColorIndex + 1) % feltColorOptions.size
        feltPaint.color = feltColorOptions[feltColorIndex]
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
        val availH = height - 2 * pad - hudHeight
        if (availW <= 0f || availH <= 0f) return
        scale = min(availW / table.playWidth, availH / table.playHeight)
        offsetX = (width - table.playWidth * scale) / 2f
        offsetY = hudHeight + (height - hudHeight - table.playHeight * scale) / 2f
    }

    private fun worldToScreen(p: Vec2) = Vec2(p.x * scale + offsetX, p.y * scale + offsetY)
    private fun screenToWorld(p: Vec2) = Vec2((p.x - offsetX) / scale, (p.y - offsetY) / scale)

    // --- Touch input: HUD buttons, or drag from behind the cue ball to aim ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (resetButtonRect.contains(event.x, event.y)) {
                resetRack()
                return true
            }
            if (colorButtonRect.contains(event.x, event.y)) {
                cycleFeltColor()
                return true
            }
        }

        if (isSimulating) return true // ignore aim input while balls are moving

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
        return toTouch
    }

    /** Current pull-back distance as a 0..1 fraction of max power, for the meter and the shot itself. */
    private fun currentPowerFraction(): Float {
        val dragDist = (dragCurrent - cueBall.position).length()
        return (dragDist / maxDragForFullPower).coerceIn(0f, 1f)
    }

    private fun shoot(releasePoint: Vec2) {
        val toRelease = releasePoint - cueBall.position
        val dragDist = toRelease.length()
        if (dragDist < 1f) return

        // Start tracking this shot's contacts/pockets fresh.
        shotContactedIds.clear()
        shotPottedIds.clear()

        val powerFraction = (dragDist / maxDragForFullPower).coerceIn(0f, 1f)
        val power = powerFraction * PhysicsConstants.MAX_SHOT_SPEED
        cueBall.velocity = toRelease.normalized() * power
        isSimulating = true
    }

    /** Called once all balls have stopped moving after a shot: scores it and handles respots. */
    private fun finishShot() {
        val breakdown = ScoringEngine.score(
            cueId = cueId,
            redId = redId,
            opponentWhiteId = opponentWhiteId,
            contactedIds = shotContactedIds,
            pottedIds = shotPottedIds
        )
        totalScore += breakdown.totalPoints

        val parts = mutableListOf<String>()
        if (breakdown.cannon) parts.add("Cannon +${ScoringEngine.CANNON_POINTS}")
        if (breakdown.winningHazardPoints > 0) parts.add("Winning hazard +${breakdown.winningHazardPoints}")
        if (breakdown.losingHazardPoints > 0) parts.add("Losing hazard +${breakdown.losingHazardPoints}")
        lastShotSummary = if (parts.isEmpty()) "No score" else parts.joinToString("  •  ")

        // Respot any potted balls per standard billiards placement.
        if (shotPottedIds.contains(redId)) {
            redBall.position = pyramidSpot; redBall.velocity = Vec2(0f, 0f); redBall.pocketed = false
        }
        if (shotPottedIds.contains(opponentWhiteId)) {
            opponentWhite.position = centerSpot; opponentWhite.velocity = Vec2(0f, 0f); opponentWhite.pocketed = false
        }
        if (shotPottedIds.contains(cueId)) {
            cueBall.position = dSpot; cueBall.velocity = Vec2(0f, 0f); cueBall.pocketed = false
        }
    }

    // --- Game loop ---

    override fun run() {
        var lastTime = System.nanoTime()
        val targetFrameNanos = 1_000_000_000L / 60L

        while (running) {
            val frameStart = System.nanoTime()
            val dt = min((frameStart - lastTime) / 1_000_000_000f, 1f / 30f) // clamp dt to avoid spiral of death
            lastTime = frameStart

            if (isSimulating) {
                val result = PhysicsSimulator.step(table, allBalls, dt)

                // Track every ball the cue ball touches this shot (for cannon/losing-hazard scoring).
                for ((idA, idB) in result.collisions) {
                    if (idA == cueId) shotContactedIds.add(idB)
                    if (idB == cueId) shotContactedIds.add(idA)
                }
                shotPottedIds.addAll(result.newlyPocketed)

                if (!result.anyMoving) {
                    isSimulating = false
                    finishShot()
                }
            }

            val canvas = holder.lockCanvas() ?: continue
            try {
                renderFrame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }

            // Frame pacing: sleep just enough to hit ~60fps, measured AFTER
            // the render finished so we account for however long it took.
            val elapsedNanos = System.nanoTime() - frameStart
            val sleepNanos = targetFrameNanos - elapsedNanos
            if (sleepNanos > 0) {
                Thread.sleep(sleepNanos / 1_000_000L, (sleepNanos % 1_000_000L).toInt())
            }
        }
    }

    private fun renderFrame(canvas: Canvas) {
        canvas.drawColor(Color.rgb(15, 15, 15)) // letterbox background

        drawTable(canvas)
        drawBalls(canvas)
        drawHud(canvas)

        if (isAiming && !isSimulating) {
            drawAimPreview(canvas)
            drawPowerMeter(canvas)
        }
    }

    private fun drawHud(canvas: Canvas) {
        canvas.drawRoundRect(resetButtonRect, 12f, 12f, hudButtonPaint)
        canvas.drawText("New rack", resetButtonRect.centerX(), resetButtonRect.centerY() + 10f, hudTextPaint)

        canvas.drawRoundRect(colorButtonRect, 12f, 12f, hudButtonPaint)
        canvas.drawText("Table color", colorButtonRect.centerX(), colorButtonRect.centerY() + 10f, hudTextPaint)

        canvas.drawText("Score: $totalScore", 600f, 70f, scoreTextPaint)
        if (lastShotSummary.isNotEmpty()) {
            canvas.drawText(lastShotSummary, 600f, 115f, shotSummaryPaint)
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

    /** Vertical power meter on the right edge of the screen while aiming. */
    private fun drawPowerMeter(canvas: Canvas) {
        val fraction = currentPowerFraction()

        val barWidth = 50f
        val barHeight = 400f
        val barRight = width - 40f
        val barLeft = barRight - barWidth
        val barTop = (height - barHeight) / 2f
        val barBottom = barTop + barHeight

        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 10f, 10f, powerMeterBgPaint)

        val fillHeight = barHeight * fraction
        val fillTop = barBottom - fillHeight
        powerMeterFillPaint.color = when {
            fraction < 0.5f -> Color.rgb(90, 200, 90)
            fraction < 0.8f -> Color.rgb(230, 190, 40)
            else -> Color.rgb(220, 60, 50)
        }
        canvas.drawRoundRect(barLeft, fillTop, barRight, barBottom, 10f, 10f, powerMeterFillPaint)

        val percentText = "${(fraction * 100).toInt()}%"
        canvas.drawText(percentText, barLeft + barWidth / 2f, barTop - 16f, powerMeterTextPaint)
    }
}
