package com.condroid.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.condroid.app.haptics.HapticEngine
import com.condroid.app.model.ElementConfig
import com.condroid.app.model.ElementShape
import com.condroid.app.model.ElementType
import com.condroid.app.model.LayoutPreset
import com.condroid.app.network.UdpSender
import com.condroid.app.protocol.Buttons
import com.google.android.material.color.MaterialColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var udpSender: UdpSender? = null
    var hapticEngine: HapticEngine? = null
    val layoutManager = LayoutManager(context)

    var currentPreset: LayoutPreset = layoutManager.getActivePreset()
        set(value) {
            field = value
            setupLayout(width.toFloat(), height.toFloat())
            invalidate()
        }

    var onHomeClicked: (() -> Unit)? = null
    var onSettingsClicked: (() -> Unit)? = null
    var onGyroToggleClicked: (() -> Unit)? = null
    var onEditModeChanged: ((Boolean) -> Unit)? = null

    // Telemetry HUD state
    var telemetryPingMs: Float = -1f
    var telemetryRateHz: Float = 0f
    var isGyroActive: Boolean = false
    var showHud: Boolean = true
    var assignedPlayerSlot: Int = 0

    // Quick Ball Floating Menu State
    var isQuickBallExpanded: Boolean = false
    private var quickBallCenter = Pair(0f, 0f)
    private var quickBallRadius = 0f
    private val quickBallDockRect = RectF()

    // Edit Mode State
    var isEditMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                isQuickBallExpanded = false
                selectedElementId = LayoutManager.ID_BTN_A
                resetAllPointers()
            } else {
                layoutManager.savePreset(currentPreset)
            }
            onEditModeChanged?.invoke(value)
            setupLayout(width.toFloat(), height.toFloat())
            invalidate()
        }

    var selectedElementId: String? = null

    // Material 3 Paints
    private val bgPaint = Paint().apply { color = Color.parseColor("#090B0E") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#1C232E")
        pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
    }
    private val trackRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#3300E5FF")
    }
    private val surfaceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#141922")
    }
    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#2D3542")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        isFakeBoldText = true
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#89929B")
        textAlign = Paint.Align.CENTER
        textSize = 26f
    }
    private val stickKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }
    private val selectionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00E5FF")
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
    }
    private val toolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F2141922")
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66000000")
    }
    private val quickBallGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#00E5FF")
    }

    // Material You Dynamic Colors
    private var dynamicPrimary: Int = Color.parseColor("#00E5FF")
    private var dynamicSecondary: Int = Color.parseColor("#D500F9")
    private var dynamicSurfaceContainer: Int = Color.parseColor("#141922")
    private var dynamicOutline: Int = Color.parseColor("#2D3542")

    fun updateThemeColors() {
        dynamicPrimary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#00E5FF"))
        dynamicSecondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, Color.parseColor("#D500F9"))
        dynamicSurfaceContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainer, Color.parseColor("#141922"))
        dynamicOutline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.parseColor("#2D3542"))

        trackRingPaint.color = (dynamicPrimary and 0x00FFFFFF) or 0x33000000
        stickKnobPaint.color = dynamicPrimary
        selectionBoxPaint.color = dynamicPrimary
        quickBallGlowPaint.color = dynamicPrimary
        surfaceFillPaint.color = dynamicSurfaceContainer
        buttonBorderPaint.color = dynamicOutline
    }

    // Geometry data
    private var leftStickCenter = Pair(0f, 0f)
    private var leftStickKnob = Pair(0f, 0f)
    private var rightStickCenter = Pair(0f, 0f)
    private var rightStickKnob = Pair(0f, 0f)
    private var baseStickRadius = 130f

    private var leftStickPointerId = -1
    private var rightStickPointerId = -1

    // Dragging state in edit mode
    private var draggingElementId: String? = null
    private var dragTouchOffset = Pair(0f, 0f)

    // Rendered buttons
    private class RenderedButton(
        val config: ElementConfig,
        val rect: RectF,
        val shape: ElementShape,
        val color: Int,
        var isPressed: Boolean = false
    )

    private val renderedButtons = mutableListOf<RenderedButton>()
    private val elementBoundingBoxes = mutableMapOf<String, RectF>()

    // Quick Ball Dock Items and Edit Toolbar Items
    private class MenuItem(
        val actionId: Int,
        val label: String,
        val rect: RectF,
        val color: Int
    )
    private val quickBallMenuItems = mutableListOf<MenuItem>()
    private val editToolbarButtons = mutableListOf<MenuItem>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setupLayout(w.toFloat(), h.toFloat())
    }

    fun setupLayout(w: Float, h: Float) {
        if (w <= 0 || h <= 0) return
        updateThemeColors()
        renderedButtons.clear()
        elementBoundingBoxes.clear()
        quickBallMenuItems.clear()
        editToolbarButtons.clear()

        val baseDimension = min(w, h)
        baseStickRadius = baseDimension * 0.17f
        val baseBtnSize = baseDimension * 0.12f

        // 1. Position Bottom Quick Ball
        quickBallRadius = baseDimension * 0.055f // ~36dp
        quickBallCenter = Pair(w * 0.50f, h * 0.91f)

        // Quick Ball Expanded Dock Geometry (Floating pill dock centered above quick ball)
        val dockW = w * 0.58f
        val dockH = h * 0.11f
        val dockY = h * 0.77f
        quickBallDockRect.set(w * 0.5f - dockW / 2f, dockY, w * 0.5f + dockW / 2f, dockY + dockH)

        // 5 Items inside Quick Ball dock
        val itemW = dockW / 5.2f
        val itemH = dockH * 0.80f
        val itemTop = dockY + (dockH - itemH) / 2f
        val startX = quickBallDockRect.left + (dockW - itemW * 5f) / 2f

        quickBallMenuItems.add(MenuItem(1, "⌂ Home", RectF(startX + itemW * 0f, itemTop, startX + itemW * 1f - 8f, itemTop + itemH), Color.LTGRAY))
        quickBallMenuItems.add(MenuItem(2, "✎ Edit", RectF(startX + itemW * 1f, itemTop, startX + itemW * 2f - 8f, itemTop + itemH), Color.parseColor("#FFD600")))
        quickBallMenuItems.add(MenuItem(3, "⚙ Config", RectF(startX + itemW * 2f, itemTop, startX + itemW * 3f - 8f, itemTop + itemH), dynamicPrimary))
        quickBallMenuItems.add(MenuItem(4, if (isGyroActive) "◎ Gyro: ON" else "◎ Gyro: OFF", RectF(startX + itemW * 3f, itemTop, startX + itemW * 4f - 8f, itemTop + itemH), if (isGyroActive) Color.parseColor("#00E676") else Color.parseColor("#89929B")))
        quickBallMenuItems.add(MenuItem(5, "✕ Close", RectF(startX + itemW * 4f, itemTop, startX + itemW * 5f - 8f, itemTop + itemH), Color.parseColor("#FF5252")))

        // 2. Process Sticks
        currentPreset.elements[LayoutManager.ID_STICK_LEFT]?.let { cfg ->
            if (cfg.visible || isEditMode) {
                val r = baseStickRadius * cfg.scale
                leftStickCenter = Pair(w * cfg.xRatio, h * cfg.yRatio)
                leftStickKnob = leftStickCenter
                elementBoundingBoxes[cfg.id] = RectF(
                    leftStickCenter.first - r * 1.15f, leftStickCenter.second - r * 1.15f,
                    leftStickCenter.first + r * 1.15f, leftStickCenter.second + r * 1.15f
                )
            }
        }

        currentPreset.elements[LayoutManager.ID_STICK_RIGHT]?.let { cfg ->
            if (cfg.visible || isEditMode) {
                val r = baseStickRadius * cfg.scale
                rightStickCenter = Pair(w * cfg.xRatio, h * cfg.yRatio)
                rightStickKnob = rightStickCenter
                elementBoundingBoxes[cfg.id] = RectF(
                    rightStickCenter.first - r * 1.15f, rightStickCenter.second - r * 1.15f,
                    rightStickCenter.first + r * 1.15f, rightStickCenter.second + r * 1.15f
                )
            }
        }

        // 3. Process All Individual Buttons & Triggers
        for (cfg in currentPreset.elements.values) {
            if (cfg.type == ElementType.STICK) continue
            if (!cfg.visible && !isEditMode) continue

            val cx = w * cfg.xRatio
            val cy = h * cfg.yRatio

            val size = baseBtnSize * cfg.scale
            val halfW: Float
            val halfH: Float

            if (cfg.shape == ElementShape.ROUNDED_RECT || cfg.type == ElementType.TRIGGER) {
                halfW = size * 0.70f
                halfH = size * 0.40f
            } else {
                halfW = size * 0.50f
                halfH = size * 0.50f
            }

            val rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            val btnColor = if (cfg.defaultColor != 0 && cfg.defaultColor != Color.parseColor("#00E5FF")) cfg.defaultColor else dynamicPrimary
            renderedButtons.add(RenderedButton(cfg, rect, cfg.shape, btnColor))
            elementBoundingBoxes[cfg.id] = RectF(rect.left - 10f, rect.top - 10f, rect.right + 10f, rect.bottom + 10f)
        }

        // 4. Edit Toolbar (Only in Edit Mode)
        if (isEditMode) {
            val barH = h * 0.12f
            val barY = h * 0.02f
            val bW = w * 0.12f

            val selectedCfg = selectedElementId?.let { currentPreset.elements[it] }
            val shapeSymbol = selectedCfg?.shape?.symbol ?: "○"
            val isVisible = selectedCfg?.visible != false

            editToolbarButtons.add(MenuItem(-10, "SHAPE: $shapeSymbol", RectF(w * 0.26f - bW/2, barY, w * 0.26f + bW/2, barY + barH), Color.parseColor("#00E5FF")))
            editToolbarButtons.add(MenuItem(-11, "− SIZE", RectF(w * 0.40f - bW/2, barY, w * 0.40f + bW/2, barY + barH), Color.WHITE))
            editToolbarButtons.add(MenuItem(-12, "+ SIZE", RectF(w * 0.54f - bW/2, barY, w * 0.54f + bW/2, barY + barH), Color.WHITE))
            editToolbarButtons.add(MenuItem(-13, if (isVisible) "VISIBLE" else "HIDDEN", RectF(w * 0.68f - bW/2, barY, w * 0.68f + bW/2, barY + barH), if (isVisible) Color.CYAN else Color.GRAY))
            editToolbarButtons.add(MenuItem(-14, "RESET", RectF(w * 0.81f - bW/2, barY, w * 0.81f + bW/2, barY + barH), Color.parseColor("#FF5252")))
            editToolbarButtons.add(MenuItem(-15, "DONE ✓", RectF(w * 0.93f - bW/2, barY, w * 0.93f + bW/2, barY + barH), Color.parseColor("#00E676")))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Grid in edit mode
        if (isEditMode) {
            var gx = 0f
            while (gx < width) {
                canvas.drawLine(gx, 0f, gx, height.toFloat(), gridPaint)
                gx += 60f
            }
            var gy = 0f
            while (gy < height) {
                canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint)
                gy += 60f
            }
        }

        // HUD overlay in play mode
        if (!isEditMode && showHud) {
            val isConnected = udpSender?.isConnected() == true
            hudTextPaint.color = if (isConnected) Color.parseColor("#00E676") else Color.parseColor("#FF1744")
            val playerPrefix = if (assignedPlayerSlot > 0) "PLAYER $assignedPlayerSlot • " else ""
            val statusText = if (isConnected) "● ${playerPrefix}CONNECTED (${currentPreset.name})" else "○ DISCONNECTED"
            canvas.drawText(statusText, width * 0.5f, height * 0.28f, hudTextPaint)

            hudTextPaint.color = Color.parseColor("#89929B")
            val pingStr = if (telemetryPingMs >= 0) String.format("%.1f ms", telemetryPingMs) else "-- ms"
            val rateStr = String.format("%.0f Hz", telemetryRateHz)
            val gyroStr = if (isGyroActive) "GYRO ON" else "GYRO OFF"
            canvas.drawText("RTT: $pingStr  |  Rate: $rateStr  |  $gyroStr", width * 0.5f, height * 0.33f, hudTextPaint)
        }

        // Draw Left Stick
        currentPreset.elements[LayoutManager.ID_STICK_LEFT]?.let { cfg ->
            if (cfg.visible || isEditMode) {
                val r = baseStickRadius * cfg.scale
                val knobR = r * 0.44f
                val alpha = if (cfg.visible) 255 else 90

                surfaceFillPaint.alpha = (alpha * 0.6).toInt()
                canvas.drawCircle(leftStickCenter.first, leftStickCenter.second, r, surfaceFillPaint)
                trackRingPaint.alpha = alpha
                canvas.drawCircle(leftStickCenter.first, leftStickCenter.second, r, trackRingPaint)
                canvas.drawCircle(leftStickCenter.first, leftStickCenter.second, r * 0.5f, trackRingPaint)

                stickKnobPaint.color = if (leftStickPointerId != -1) Color.parseColor("#00E5FF") else Color.parseColor("#4000E5FF")
                stickKnobPaint.alpha = alpha
                canvas.drawCircle(leftStickKnob.first, leftStickKnob.second, knobR, stickKnobPaint)
            }
        }

        // Draw Right Stick
        currentPreset.elements[LayoutManager.ID_STICK_RIGHT]?.let { cfg ->
            if (cfg.visible || isEditMode) {
                val r = baseStickRadius * cfg.scale
                val knobR = r * 0.44f
                val alpha = if (cfg.visible) 255 else 90

                surfaceFillPaint.alpha = (alpha * 0.6).toInt()
                canvas.drawCircle(rightStickCenter.first, rightStickCenter.second, r, surfaceFillPaint)
                trackRingPaint.alpha = alpha
                canvas.drawCircle(rightStickCenter.first, rightStickCenter.second, r, trackRingPaint)
                canvas.drawCircle(rightStickCenter.first, rightStickCenter.second, r * 0.5f, trackRingPaint)

                stickKnobPaint.color = if (rightStickPointerId != -1) Color.parseColor("#00E5FF") else Color.parseColor("#4000E5FF")
                stickKnobPaint.alpha = alpha
                canvas.drawCircle(rightStickKnob.first, rightStickKnob.second, knobR, stickKnobPaint)
            }
        }

        // Draw All Individual Buttons
        for (btn in renderedButtons) {
            val isVisible = btn.config.visible
            if (!isVisible && !isEditMode) continue

            val alpha = if (isVisible) 255 else 90
            val isPressed = btn.isPressed

            surfaceFillPaint.color = if (isPressed) btn.color else Color.parseColor("#141922")
            surfaceFillPaint.alpha = if (isPressed) 220 else (alpha * 0.85).toInt()
            buttonBorderPaint.color = if (isPressed) btn.color else Color.parseColor("#2D3542")
            buttonBorderPaint.alpha = alpha

            drawCustomButtonShape(canvas, btn.rect, btn.shape, surfaceFillPaint, buttonBorderPaint)

            textPaint.color = if (isPressed) Color.BLACK else Color.WHITE
            textPaint.alpha = alpha
            val label = btn.config.label.ifEmpty { btn.config.name }
            textPaint.textSize = if (label.contains("\n") || label.length > 3) 22f else 34f

            if (label.contains("\n")) {
                val lines = label.split("\n")
                val lineH = textPaint.descent() - textPaint.ascent()
                var y = btn.rect.centerY() - (lineH * lines.size) / 4f
                for (line in lines) {
                    canvas.drawText(line, btn.rect.centerX(), y, textPaint)
                    y += lineH
                }
            } else {
                val textY = btn.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(label, btn.rect.centerX(), textY, textPaint)
            }
        }

        // Draw Quick Ball (Play Mode only)
        if (!isEditMode) {
            drawQuickBall(canvas)
        }

        // Draw Edit Mode Highlights & Toolbar
        if (isEditMode) {
            for ((id, box) in elementBoundingBoxes) {
                val isSelected = (id == selectedElementId)
                selectionBoxPaint.color = if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#4D89929B")
                selectionBoxPaint.strokeWidth = if (isSelected) 5f else 2f
                canvas.drawRoundRect(box, 14f, 14f, selectionBoxPaint)

                if (isSelected) {
                    val cfg = currentPreset.elements[id]
                    val label = "${cfg?.name ?: id} (${cfg?.shape?.displayName ?: ""})"
                    hudTextPaint.color = Color.parseColor("#00E5FF")
                    hudTextPaint.textSize = 22f
                    canvas.drawText(label, box.centerX(), box.top - 8f, hudTextPaint)
                }
            }

            // Edit Toolbar
            val toolbarRect = RectF(0f, 0f, width.toFloat(), height * 0.16f)
            canvas.drawRect(toolbarRect, toolbarBgPaint)

            val selName = selectedElementId?.let { currentPreset.elements[it]?.name } ?: "Select an element to customize"
            hudTextPaint.color = Color.WHITE
            hudTextPaint.textSize = 26f
            canvas.drawText("Editing: $selName", width * 0.13f, height * 0.08f, hudTextPaint)

            for (eb in editToolbarButtons) {
                surfaceFillPaint.color = Color.parseColor("#212631")
                surfaceFillPaint.alpha = 255
                buttonBorderPaint.color = eb.color
                buttonBorderPaint.strokeWidth = 3f

                canvas.drawRoundRect(eb.rect, 14f, 14f, surfaceFillPaint)
                canvas.drawRoundRect(eb.rect, 14f, 14f, buttonBorderPaint)

                textPaint.color = eb.color
                textPaint.textSize = 22f
                val textY = eb.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(eb.label, eb.rect.centerX(), textY, textPaint)
            }
        }
    }

    private fun drawQuickBall(canvas: Canvas) {
        if (isQuickBallExpanded) {
            // Draw dark background scrim
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

            // Draw floating dock container
            surfaceFillPaint.color = Color.parseColor("#E6141922")
            surfaceFillPaint.alpha = 245
            buttonBorderPaint.color = dynamicPrimary
            buttonBorderPaint.strokeWidth = 2.5f

            canvas.drawRoundRect(quickBallDockRect, 24f, 24f, surfaceFillPaint)
            canvas.drawRoundRect(quickBallDockRect, 24f, 24f, buttonBorderPaint)

            // Draw dock items
            for (item in quickBallMenuItems) {
                surfaceFillPaint.color = Color.parseColor("#212631")
                buttonBorderPaint.color = item.color
                buttonBorderPaint.strokeWidth = 2f

                canvas.drawRoundRect(item.rect, 16f, 16f, surfaceFillPaint)
                canvas.drawRoundRect(item.rect, 16f, 16f, buttonBorderPaint)

                textPaint.color = item.color
                textPaint.textSize = 21f
                val textY = item.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(item.label, item.rect.centerX(), textY, textPaint)
            }
        }

        // Draw Quick Ball Button at Bottom Center
        val cx = quickBallCenter.first
        val cy = quickBallCenter.second
        val r = quickBallRadius

        surfaceFillPaint.color = Color.parseColor("#E6141922")
        surfaceFillPaint.alpha = if (isQuickBallExpanded) 255 else 200
        canvas.drawCircle(cx, cy, r, surfaceFillPaint)

        quickBallGlowPaint.color = if (isQuickBallExpanded) Color.parseColor("#FFD600") else dynamicPrimary
        quickBallGlowPaint.alpha = 220
        canvas.drawCircle(cx, cy, r, quickBallGlowPaint)

        textPaint.color = if (isQuickBallExpanded) Color.parseColor("#FFD600") else dynamicPrimary
        textPaint.textSize = r * 0.95f
        val icon = if (isQuickBallExpanded) "✕" else "⚡"
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(icon, cx, textY, textPaint)
    }

    private fun drawCustomButtonShape(canvas: Canvas, rect: RectF, shape: ElementShape, fillPaint: Paint, borderPaint: Paint) {
        when (shape) {
            ElementShape.CIRCLE -> {
                val radius = min(rect.width(), rect.height()) / 2f
                canvas.drawCircle(rect.centerX(), rect.centerY(), radius, fillPaint)
                canvas.drawCircle(rect.centerX(), rect.centerY(), radius, borderPaint)
            }
            ElementShape.SQUARE -> {
                val side = min(rect.width(), rect.height())
                val sqRect = RectF(rect.centerX() - side/2f, rect.centerY() - side/2f, rect.centerX() + side/2f, rect.centerY() + side/2f)
                canvas.drawRect(sqRect, fillPaint)
                canvas.drawRect(sqRect, borderPaint)
            }
            ElementShape.ROUNDED_RECT -> {
                canvas.drawRoundRect(rect, 20f, 20f, fillPaint)
                canvas.drawRoundRect(rect, 20f, 20f, borderPaint)
            }
            ElementShape.TRIANGLE_UP -> {
                val path = Path().apply {
                    moveTo(rect.centerX(), rect.top)
                    lineTo(rect.right, rect.bottom)
                    lineTo(rect.left, rect.bottom)
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, borderPaint)
            }
            ElementShape.TRIANGLE_DOWN -> {
                val path = Path().apply {
                    moveTo(rect.centerX(), rect.bottom)
                    lineTo(rect.right, rect.top)
                    lineTo(rect.left, rect.top)
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, borderPaint)
            }
            ElementShape.TRIANGLE_LEFT -> {
                val path = Path().apply {
                    moveTo(rect.left, rect.centerY())
                    lineTo(rect.right, rect.top)
                    lineTo(rect.right, rect.bottom)
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, borderPaint)
            }
            ElementShape.TRIANGLE_RIGHT -> {
                val path = Path().apply {
                    moveTo(rect.right, rect.centerY())
                    lineTo(rect.left, rect.top)
                    lineTo(rect.left, rect.bottom)
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, borderPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEditMode) {
            return handleEditModeTouch(event)
        }
        return handlePlayModeTouch(event)
    }

    private fun handlePlayModeTouch(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val actionPointerId = event.getPointerId(actionIndex)
        val x = event.getX(actionIndex)
        val y = event.getY(actionIndex)

        // 1. Intercept Quick Ball Touches
        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Check tapping the Quick Ball circle
            val distToQb = hypot(x - quickBallCenter.first, y - quickBallCenter.second)
            if (distToQb <= quickBallRadius * 1.5f) {
                hapticEngine?.performClick()
                isQuickBallExpanded = !isQuickBallExpanded
                if (isQuickBallExpanded) {
                    setupLayout(width.toFloat(), height.toFloat()) // Refresh menu states
                }
                invalidate()
                return true
            }

            // If Quick Ball is expanded: check menu dock items or outside touch to dismiss
            if (isQuickBallExpanded) {
                for (item in quickBallMenuItems) {
                    if (item.rect.contains(x, y)) {
                        hapticEngine?.performClick()
                        handleQuickBallAction(item.actionId)
                        invalidate()
                        return true
                    }
                }
                // Tapped anywhere outside when expanded -> dismiss Quick Ball
                isQuickBallExpanded = false
                invalidate()
                return true
            }
        }

        // If Quick Ball is expanded, modal intercept prevents game input
        if (isQuickBallExpanded) {
            return true
        }

        // 2. Gamepad Input Handling (Joysticks, Chords, Sliding)
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Check Left Stick
                currentPreset.elements[LayoutManager.ID_STICK_LEFT]?.let { cfg ->
                    if (cfg.visible) {
                        val r = baseStickRadius * cfg.scale
                        if (hypot(x - leftStickCenter.first, y - leftStickCenter.second) <= r * 1.3f && leftStickPointerId == -1) {
                            leftStickPointerId = actionPointerId
                        }
                    }
                }

                // Check Right Stick
                currentPreset.elements[LayoutManager.ID_STICK_RIGHT]?.let { cfg ->
                    if (cfg.visible) {
                        val r = baseStickRadius * cfg.scale
                        if (hypot(x - rightStickCenter.first, y - rightStickCenter.second) <= r * 1.3f && rightStickPointerId == -1) {
                            rightStickPointerId = actionPointerId
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (actionPointerId == leftStickPointerId) {
                    leftStickPointerId = -1
                    leftStickKnob = leftStickCenter
                    udpSender?.updateState { it.leftStickX = 0; it.leftStickY = 0 }
                }
                if (actionPointerId == rightStickPointerId) {
                    rightStickPointerId = -1
                    rightStickKnob = rightStickCenter
                    udpSender?.updateState { it.rightStickX = 0; it.rightStickY = 0 }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetAllPointers()
                invalidate()
                return true
            }
        }

        // Update active sticks
        for (i in 0 until event.pointerCount) {
            val pId = event.getPointerId(i)
            val px = event.getX(i)
            val py = event.getY(i)
            if (pId == leftStickPointerId) {
                updateStickPosition(true, px, py)
            } else if (pId == rightStickPointerId) {
                updateStickPosition(false, px, py)
            }
        }

        // Evaluate all buttons for chords and frictionless sliding
        val ignoreIndex = if (actionMasked == MotionEvent.ACTION_POINTER_UP) actionIndex else -1
        updateActiveButtonsFromPointers(event, ignoreIndex)

        invalidate()
        return true
    }

    private fun handleQuickBallAction(actionId: Int) {
        when (actionId) {
            1 -> { // Home
                isQuickBallExpanded = false
                onHomeClicked?.invoke()
            }
            2 -> { // Edit Layout
                isQuickBallExpanded = false
                isEditMode = true
            }
            3 -> { // Config / Settings
                isQuickBallExpanded = false
                onSettingsClicked?.invoke()
            }
            4 -> { // Gyro toggle
                onGyroToggleClicked?.invoke()
                setupLayout(width.toFloat(), height.toFloat())
            }
            5 -> { // Close
                isQuickBallExpanded = false
            }
        }
    }

    private fun updateActiveButtonsFromPointers(event: MotionEvent, ignoreIndex: Int) {
        val touchedButtons = mutableSetOf<RenderedButton>()

        for (i in 0 until event.pointerCount) {
            if (i == ignoreIndex) continue
            val pId = event.getPointerId(i)
            if (pId == leftStickPointerId || pId == rightStickPointerId) continue

            val px = event.getX(i)
            val py = event.getY(i)

            // Ignore touches that hit the quick ball area
            if (hypot(px - quickBallCenter.first, py - quickBallCenter.second) <= quickBallRadius * 1.5f) {
                continue
            }

            for (btn in renderedButtons) {
                if (!btn.config.visible) continue
                if (isPointTouchingButton(btn, px, py)) {
                    touchedButtons.add(btn)
                }
            }
        }

        var buttonsMask = 0
        var leftTriggerVal = 0
        var rightTriggerVal = 0

        for (btn in renderedButtons) {
            val isNowPressed = touchedButtons.contains(btn)
            val wasPressed = btn.isPressed

            if (!wasPressed && isNowPressed) {
                hapticEngine?.performClick()
            }

            btn.isPressed = isNowPressed

            if (isNowPressed) {
                when (btn.config.mask) {
                    -1 -> leftTriggerVal = 255
                    -2 -> rightTriggerVal = 255
                    else -> if (btn.config.mask > 0) {
                        buttonsMask = buttonsMask or btn.config.mask
                    }
                }
            }
        }

        udpSender?.updateState { state ->
            state.buttons = buttonsMask
            state.leftTrigger = leftTriggerVal
            state.rightTrigger = rightTriggerVal
        }
    }

    private fun isPointTouchingButton(btn: RenderedButton, x: Float, y: Float): Boolean {
        if (btn.shape == ElementShape.CIRCLE) {
            val cx = btn.rect.centerX()
            val cy = btn.rect.centerY()
            val touchR = (min(btn.rect.width(), btn.rect.height()) / 2f) * 1.35f
            return hypot(x - cx, y - cy) <= touchR
        }

        val padX = btn.rect.width() * 0.25f
        val padY = btn.rect.height() * 0.25f
        val expanded = RectF(btn.rect.left - padX, btn.rect.top - padY, btn.rect.right + padX, btn.rect.bottom + padY)
        return expanded.contains(x, y)
    }

    private fun updateStickPosition(isLeft: Boolean, x: Float, y: Float) {
        val cfg = currentPreset.elements[if (isLeft) LayoutManager.ID_STICK_LEFT else LayoutManager.ID_STICK_RIGHT] ?: return
        val center = if (isLeft) leftStickCenter else rightStickCenter
        val r = baseStickRadius * cfg.scale

        val dx = x - center.first
        val dy = y - center.second
        val dist = hypot(dx, dy)
        val angle = atan2(dy, dx)

        val clampedDist = min(dist, r)
        val knobX = center.first + clampedDist * cos(angle)
        val knobY = center.second + clampedDist * sin(angle)

        if (isLeft) {
            leftStickKnob = Pair(knobX, knobY)
            val normX = (clampedDist * cos(angle) / r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            val normY = (clampedDist * sin(angle) / r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            udpSender?.updateState {
                it.leftStickX = normX
                it.leftStickY = normY
            }
        } else {
            rightStickKnob = Pair(knobX, knobY)
            val normX = (clampedDist * cos(angle) / r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            val normY = (clampedDist * sin(angle) / r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            udpSender?.updateState {
                it.rightStickX = normX
                it.rightStickY = normY
            }
        }
    }

    private fun handleEditModeTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                for (eb in editToolbarButtons) {
                    if (eb.rect.contains(x, y)) {
                        hapticEngine?.performClick()
                        handleEditToolbarAction(eb.actionId)
                        invalidate()
                        return true
                    }
                }

                var hitId: String? = null
                for ((id, box) in elementBoundingBoxes) {
                    if (box.contains(x, y)) {
                        hitId = id
                        break
                    }
                }

                if (hitId != null) {
                    selectedElementId = hitId
                    draggingElementId = hitId
                    val cfg = currentPreset.elements[hitId]
                    if (cfg != null) {
                        dragTouchOffset = Pair(x - width * cfg.xRatio, y - height * cfg.yRatio)
                    }
                    hapticEngine?.performClick()
                    setupLayout(width.toFloat(), height.toFloat())
                } else {
                    draggingElementId = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = draggingElementId
                if (id != null) {
                    val cfg = currentPreset.elements[id]
                    if (cfg != null) {
                        val newCenterX = (x - dragTouchOffset.first).coerceIn(30f, width - 30f)
                        val newCenterY = (y - dragTouchOffset.second).coerceIn(height * 0.18f, height - 30f)

                        cfg.xRatio = newCenterX / width
                        cfg.yRatio = newCenterY / height

                        setupLayout(width.toFloat(), height.toFloat())
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingElementId = null
            }
        }

        invalidate()
        return true
    }

    private fun handleEditToolbarAction(action: Int) {
        val selectedId = selectedElementId
        val cfg = if (selectedId != null) currentPreset.elements[selectedId] else null

        when (action) {
            -10 -> {
                cfg?.let {
                    it.shape = it.shape.next()
                    setupLayout(width.toFloat(), height.toFloat())
                }
            }
            -11 -> {
                cfg?.let {
                    it.scale = (it.scale - 0.1f).coerceAtLeast(0.4f)
                    setupLayout(width.toFloat(), height.toFloat())
                }
            }
            -12 -> {
                cfg?.let {
                    it.scale = (it.scale + 0.1f).coerceAtMost(2.5f)
                    setupLayout(width.toFloat(), height.toFloat())
                }
            }
            -13 -> {
                cfg?.let {
                    it.visible = !it.visible
                    setupLayout(width.toFloat(), height.toFloat())
                }
            }
            -14 -> {
                layoutManager.resetPresetToDefault(currentPreset.id)
                currentPreset = layoutManager.getActivePreset()
            }
            -15 -> {
                isEditMode = false
            }
        }
    }

    private fun resetAllPointers() {
        leftStickPointerId = -1
        rightStickPointerId = -1
        leftStickKnob = leftStickCenter
        rightStickKnob = rightStickCenter
        for (btn in renderedButtons) {
            btn.isPressed = false
        }
        udpSender?.updateState { it.reset() }
    }
}
