package com.notekeep.local.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var data: GraphData = GraphData(mutableListOf(), mutableListOf())
        set(value) {
            field = value
            simulation = ForceSimulation(value)
            requestSimTick()
        }

    private var simulation = ForceSimulation(data)

    var showArrows: Boolean = false
    var nodeSizeSetting: Float = 14f
    var linkThicknessSetting: Float = 2f
    var fadeThreshold: Float = 0.8f
    var searchQuery: String = ""
        set(value) { field = value; invalidate() }
    var groups: List<GraphGroup> = emptyList()
        set(value) { field = value; invalidate() }

    var onNoteTapped: ((Long) -> Unit)? = null

    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var draggingNode: GraphNode? = null
    private var dragStartWorldX = 0f
    private var dragStartWorldY = 0f
    private var dragStartNodeX = 0f
    private var dragStartNodeY = 0f
    private var didDrag = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.15f, 6f)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            translateX += dx
            translateY += dy
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }
    })

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A5A5A")
        strokeWidth = 3f
    }
    private val tagNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50") }
    private val noteNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6E1E5") }
    private val labelNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8A33D") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6E1E5")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            simulation.centerX = width / 2f
            simulation.centerY = height / 2f
            simulation.tick()
            invalidate()
            if (simulation.isActive) postOnAnimation(this)
        }
    }

    fun requestSimTick() {
        removeCallbacks(tickRunnable)
        post(tickRunnable)
    }

    fun applyForceSettings(center: Float, repel: Float, linkStrength: Float, linkDistance: Float) {
        simulation.centerStrength = center
        simulation.repelStrength = repel
        simulation.linkStrength = linkStrength
        simulation.linkDistance = linkDistance
    }

    fun restart() {
        simulation.reheat()
        requestSimTick()
    }

    private fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val worldX = (screenX + translateX - width / 2f) / scaleFactor + width / 2f
        val worldY = (screenY + translateY - height / 2f) / scaleFactor + height / 2f
        return worldX to worldY
    }

    private fun findNodeAt(worldX: Float, worldY: Float): GraphNode? {
        var closest: GraphNode? = null
        var closestDist = Float.MAX_VALUE
        for (node in data.nodes) {
            val dx = node.x - worldX
            val dy = node.y - worldY
            val d = sqrt(dx * dx + dy * dy)
            if (d < closestDist) {
                closestDist = d
                closest = node
            }
        }
        val radius = nodeRadius(closest) + 24f
        return if (closest != null && closestDist <= radius) closest else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (wx, wy) = screenToWorld(event.x, event.y)
                val hit = findNodeAt(wx, wy)
                if (hit != null) {
                    draggingNode = hit
                    didDrag = false
                    hit.fixed = true
                    dragStartWorldX = wx
                    dragStartWorldY = wy
                    dragStartNodeX = hit.x
                    dragStartNodeY = hit.y
                    // wake the simulation back up so linked nodes are pulled along through their
                    // spring connections while this one is being dragged, like it's all floating in a fluid
                    simulation.reheat()
                    requestSimTick()
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // a second finger arrived; hand off to pinch/pan instead of node-dragging
                draggingNode?.fixed = false
                draggingNode = null
            }
            MotionEvent.ACTION_MOVE -> {
                val node = draggingNode
                if (node != null) {
                    val (wx, wy) = screenToWorld(event.x, event.y)
                    node.x = dragStartNodeX + (wx - dragStartWorldX)
                    node.y = dragStartNodeY + (wy - dragStartWorldY)
                    didDrag = true
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val node = draggingNode
                if (node != null) {
                    node.fixed = false
                    draggingNode = null
                    if (!didDrag) {
                        // it was a tap, not a drag
                        handleTapOnNode(node)
                    }
                    invalidate()
                    return true
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val (worldX, worldY) = screenToWorld(screenX, screenY)
        val node = findNodeAt(worldX, worldY) ?: return
        handleTapOnNode(node)
    }

    private fun handleTapOnNode(node: GraphNode) {
        if (node.type == GraphNodeType.NOTE) {
            node.noteId?.let { onNoteTapped?.invoke(it) }
        }
    }

    private fun nodeRadius(node: GraphNode?): Float {
        if (node == null) return nodeSizeSetting
        return nodeSizeSetting + (node.degree.coerceAtMost(8)) * 1.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(-translateX, -translateY)
        canvas.scale(scaleFactor, scaleFactor, width / 2f, height / 2f)

        edgePaint.strokeWidth = linkThicknessSetting
        for (edge in data.edges) {
            val a = data.nodeById(edge.sourceId) ?: continue
            val b = data.nodeById(edge.targetId) ?: continue
            canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint)
            if (showArrows) drawArrowHead(canvas, a.x, a.y, b.x, b.y)
        }

        val showLabels = scaleFactor >= fadeThreshold
        val hasQuery = searchQuery.isNotBlank()
        for (node in data.nodes) {
            val matchesSearch = !hasQuery || node.label.contains(searchQuery, ignoreCase = true)
            val groupColor = groups.firstOrNull { node.label.contains(it.query, ignoreCase = true) }?.color
            val basePaint = when (node.type) {
                GraphNodeType.TAG -> tagNodePaint
                GraphNodeType.LABEL -> labelNodePaint
                GraphNodeType.NOTE -> noteNodePaint
            }
            val paint = if (groupColor != null) Paint(basePaint).apply { color = groupColor } else basePaint
            val originalAlpha = paint.alpha
            if (!matchesSearch) paint.alpha = 70
            val radius = nodeRadius(node)
            canvas.drawCircle(node.x, node.y, radius, paint)
            paint.alpha = originalAlpha
            if (showLabels) {
                val wasAlpha = labelPaint.alpha
                if (!matchesSearch) labelPaint.alpha = 90
                canvas.drawText(node.label, node.x, node.y - radius - 10f, labelPaint)
                labelPaint.alpha = wasAlpha
            }
        }
        canvas.restore()
    }

    private fun drawArrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val midX = (x1 + x2) / 2f
        val midY = (y1 + y2) / 2f
        val arrowLen = 14f
        val a1 = angle + Math.PI - 0.4
        val a2 = angle + Math.PI + 0.4
        canvas.drawLine(midX, midY, (midX + arrowLen * cos(a1)).toFloat(), (midY + arrowLen * sin(a1)).toFloat(), edgePaint)
        canvas.drawLine(midX, midY, (midX + arrowLen * cos(a2)).toFloat(), (midY + arrowLen * sin(a2)).toFloat(), edgePaint)
    }
}
