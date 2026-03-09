/*
 * Axiom — On-Device AI Assistant for Android
 * Copyright (C) 2024 Rayad
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.axiom.axiomnew

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.*
import kotlin.random.Random

// ════════════════════════════════════════════════════════════════════════════
//  DreamingAnimationView
//
//  Draws a slowly pulsing neural-network constellation on Canvas.
//  Nodes drift gently. Edges connect nearby nodes and fade in/out.
//  Everything is cyan-on-dark, matching Axiom's palette.
//
//  Usage in XML:
//    <com.axiom.axiomnew.DreamingAnimationView
//        android:id="@+id/dreamAnimation"
//        android:layout_width="match_parent"
//        android:layout_height="160dp"/>
//
//  Call start() / stop() to control animation.
//  Automatically pauses when view is detached from window.
// ════════════════════════════════════════════════════════════════════════════
class DreamingAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Config ────────────────────────────────────────────────────────────────
    private val NODE_COUNT    = 14
    private val CONNECT_DIST  = 220f   // max px distance to draw an edge
    private val DRIFT_SPEED   = 0.35f  // px per frame
    private val PULSE_SPEED   = 0.018f // radians per frame
    private val FRAME_MS      = 40L    // ~25fps — smooth but battery-friendly

    // ── Colors ────────────────────────────────────────────────────────────────
    private val COLOR_NODE_BRIGHT = 0xFF00E5FF.toInt()
    private val COLOR_NODE_DIM    = 0xFF1A4A5A.toInt()
    private val COLOR_EDGE        = 0xFF00C8FF.toInt()
    private val COLOR_BG          = 0xFF080B14.toInt()

    // ── Paints ────────────────────────────────────────────────────────────────
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }

    // ── Node state ────────────────────────────────────────────────────────────
    data class Node(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var phase: Float,          // pulse phase offset (0..2π)
        var radius: Float          // base radius
    )

    private val nodes = mutableListOf<Node>()
    private var tick  = 0f

    // ── Animation handler ─────────────────────────────────────────────────────
    private val handler  = Handler(Looper.getMainLooper())
    private var running  = false
    private val frameRun = object : Runnable {
        override fun run() {
            updatePhysics()
            invalidate()
            if (running) handler.postDelayed(this, FRAME_MS)
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    init { setBackgroundColor(COLOR_BG) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) initNodes(w.toFloat(), h.toFloat())
    }

    private fun initNodes(w: Float, h: Float) {
        nodes.clear()
        val rng = Random(42)
        repeat(NODE_COUNT) {
            val angle = rng.nextFloat() * 2f * PI.toFloat()
            val speed = DRIFT_SPEED * (0.5f + rng.nextFloat() * 0.8f)
            nodes += Node(
                x      = rng.nextFloat() * w,
                y      = rng.nextFloat() * h,
                vx     = cos(angle) * speed,
                vy     = sin(angle) * speed,
                phase  = rng.nextFloat() * 2f * PI.toFloat(),
                radius = 3f + rng.nextFloat() * 4f
            )
        }
    }

    // ── Physics ───────────────────────────────────────────────────────────────
    private fun updatePhysics() {
        tick += PULSE_SPEED
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        for (n in nodes) {
            n.x += n.vx
            n.y += n.vy
            // Bounce off walls with a little randomness
            if (n.x < 0f || n.x > w) { n.vx = -n.vx * (0.8f + Random.nextFloat() * 0.4f); n.x = n.x.coerceIn(0f, w) }
            if (n.y < 0f || n.y > h) { n.vy = -n.vy * (0.8f + Random.nextFloat() * 0.4f); n.y = n.y.coerceIn(0f, h) }
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return

        // Draw edges first (under nodes)
        for (i in 0 until nodes.size) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]; val b = nodes[j]
                val dx = a.x - b.x; val dy = a.y - b.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < CONNECT_DIST) {
                    // Alpha fades with distance; also pulses gently
                    val distAlpha  = ((1f - dist / CONNECT_DIST) * 180f).toInt()
                    val pulseAlpha = (sin(tick + a.phase) * 30f).toInt()
                    val alpha      = (distAlpha + pulseAlpha).coerceIn(0, 200)
                    edgePaint.color = COLOR_EDGE
                    edgePaint.alpha = alpha
                    canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint)
                }
            }
        }

        // Draw nodes
        for (n in nodes) {
            val pulse      = sin(tick + n.phase)                          // -1..1
            val r          = n.radius * (1f + pulse * 0.35f)             // pulsing radius
            val brightness = ((pulse + 1f) / 2f)                         // 0..1
            val alpha      = (140f + brightness * 115f).toInt().coerceIn(0, 255)

            // Outer glow
            nodePaint.color = COLOR_NODE_BRIGHT
            nodePaint.alpha = (alpha * 0.25f).toInt()
            canvas.drawCircle(n.x, n.y, r * 2.4f, nodePaint)

            // Core
            nodePaint.alpha = alpha
            canvas.drawCircle(n.x, n.y, r, nodePaint)
        }
    }

    // ── Public controls ───────────────────────────────────────────────────────
    fun start() {
        if (running) return
        running = true
        handler.post(frameRun)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(frameRun)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (running) handler.post(frameRun) }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}