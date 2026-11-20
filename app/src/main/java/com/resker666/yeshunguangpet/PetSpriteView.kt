package com.resker666.yeshunguangpet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

internal data class FramePosition(val index: Int, val remainingMs: Long)

internal fun Animation.frameAt(elapsedMs: Long): FramePosition? {
    val length = durationsMs.sum().toLong()
    val elapsed = elapsedMs.coerceAtLeast(0)
    if (!loop && elapsed >= length) return null
    var offset = if (loop) elapsed % length else elapsed
    for ((index, duration) in durationsMs.withIndex()) {
        if (offset < duration) return FramePosition(index, duration - offset)
        offset -= duration
    }
    error("Animation has no playable frames")
}

internal class PetSpriteView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private val refresh = Runnable { invalidate() }
    private var sprite: Sprite? = null
    private var clip = "idle"
    private var started = SystemClock.uptimeMillis()
    private var alternateTap = false

    fun show(sprite: Sprite?) {
        this.sprite = sprite
        alternateTap = false
        play("idle")
    }

    fun react(): String? {
        val first = if (alternateTap) "jumping" else "waving"
        val second = if (alternateTap) "waving" else "jumping"
        alternateTap = !alternateTap
        val actions = sprite?.skin?.manifest?.animations.orEmpty()
        play(listOf(first, second).firstOrNull(actions::containsKey) ?: "idle")
        return sprite?.skin?.manifest?.clickLines?.randomOrNull()
    }

    fun drag(horizontalDelta: Float) {
        val action = if (horizontalDelta < 0) "runningLeft" else "runningRight"
        val selected = action.takeIf { sprite?.skin?.manifest?.animations?.containsKey(it) == true } ?: "idle"
        if (selected != clip) play(selected)
    }

    fun idle() = play("idle")

    private fun play(name: String) {
        clip = name
        started = SystemClock.uptimeMillis()
        removeCallbacks(refresh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val asset = sprite ?: return
        val manifest = asset.skin.manifest
        val now = SystemClock.uptimeMillis()
        var animation = manifest.animations.getValue(clip)
        var frame = animation.frameAt(now - started)
        if (frame == null) {
            clip = "idle"
            started = now
            animation = manifest.animations.getValue(clip)
            frame = animation.frameAt(0) ?: return
        }
        val left = (animation.startColumn + frame.index) * manifest.cellWidth
        val top = animation.row * manifest.cellHeight
        source.set(left, top, left + manifest.cellWidth, top + manifest.cellHeight)
        val scale = min(width.toFloat() / manifest.cellWidth, height.toFloat() / manifest.cellHeight)
        val spriteWidth = manifest.cellWidth * scale
        val spriteHeight = manifest.cellHeight * scale
        val x = (width - spriteWidth) / 2
        val y = (height - spriteHeight) / 2
        destination.set(x, y, x + spriteWidth, y + spriteHeight)
        canvas.drawBitmap(asset.bitmap, source, destination, paint)
        removeCallbacks(refresh)
        if (windowVisibility == VISIBLE && isShown && (animation.durationsMs.size > 1 || !animation.loop)) {
            postDelayed(refresh, frame.remainingMs)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) play("idle") else removeCallbacks(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }
}
