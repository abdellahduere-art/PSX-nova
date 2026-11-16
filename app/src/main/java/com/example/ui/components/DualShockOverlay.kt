package com.example.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.psx.Ps1Button
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PsCircleRed
import com.example.ui.theme.PsCrossBlue
import com.example.ui.theme.PsSquarePink
import com.example.ui.theme.PsTriangleGreen
import com.example.ui.theme.SlateSurfaceBright
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun DualShockOverlay(
    isLandscape: Boolean,
    analogMode: Boolean,
    opacity: Float,
    hapticsEnabled: Boolean,
    onButtonChanged: (Ps1Button, Boolean) -> Unit,
    onStickChanged: (isLeft: Boolean, x: Float, y: Float) -> Unit,
    onToggleAnalog: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isLandscape) {
        LandscapeController(
            analogMode = analogMode,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onButtonChanged = onButtonChanged,
            onStickChanged = onStickChanged,
            onToggleAnalog = onToggleAnalog,
            modifier = modifier
        )
    } else {
        PortraitController(
            analogMode = analogMode,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onButtonChanged = onButtonChanged,
            onStickChanged = onStickChanged,
            onToggleAnalog = onToggleAnalog,
            modifier = modifier
        )
    }
}

@Composable
private fun LandscapeController(
    analogMode: Boolean,
    opacity: Float,
    hapticsEnabled: Boolean,
    onButtonChanged: (Ps1Button, Boolean) -> Unit,
    onStickChanged: (Boolean, Float, Float) -> Unit,
    onToggleAnalog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(opacity)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Left shoulder triggers
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShoulderPillButton("L2", Ps1Button.L2, hapticsEnabled, onButtonChanged)
            ShoulderPillButton("L1", Ps1Button.L1, hapticsEnabled, onButtonChanged)
        }

        // Right shoulder triggers
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShoulderPillButton("R1", Ps1Button.R1, hapticsEnabled, onButtonChanged)
            ShoulderPillButton("R2", Ps1Button.R2, hapticsEnabled, onButtonChanged)
        }

        // Left side: D-Pad + Optional Left Analog
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TouchDPad(size = 126.dp, hapticsEnabled = hapticsEnabled, onButtonChanged = onButtonChanged)
            if (analogMode) {
                TouchAnalogStick(
                    label = "L3",
                    size = 86.dp,
                    onStickChanged = { x, y -> onStickChanged(true, x, y) }
                )
            }
        }

        // Right side: Face Buttons + Optional Right Analog
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FaceButtonDiamond(size = 136.dp, hapticsEnabled = hapticsEnabled, onButtonChanged = onButtonChanged)
            if (analogMode) {
                TouchAnalogStick(
                    label = "R3",
                    size = 86.dp,
                    onStickChanged = { x, y -> onStickChanged(false, x, y) }
                )
            }
        }

        // Bottom Center: SELECT / ANALOG / START
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CenterMetaButton("SELECT", Ps1Button.SELECT, hapticsEnabled, onButtonChanged)
            AnalogModeLedButton(analogMode = analogMode, onClick = onToggleAnalog)
            CenterMetaButton("START", Ps1Button.START, hapticsEnabled, onButtonChanged)
        }
    }
}

@Composable
private fun PortraitController(
    analogMode: Boolean,
    opacity: Float,
    hapticsEnabled: Boolean,
    onButtonChanged: (Ps1Button, Boolean) -> Unit,
    onStickChanged: (Boolean, Float, Float) -> Unit,
    onToggleAnalog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(opacity)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Shoulder Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoulderPillButton("L2", Ps1Button.L2, hapticsEnabled, onButtonChanged)
                ShoulderPillButton("L1", Ps1Button.L1, hapticsEnabled, onButtonChanged)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoulderPillButton("R1", Ps1Button.R1, hapticsEnabled, onButtonChanged)
                ShoulderPillButton("R2", Ps1Button.R2, hapticsEnabled, onButtonChanged)
            }
        }

        // Main D-Pad & Face buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TouchDPad(size = 132.dp, hapticsEnabled = hapticsEnabled, onButtonChanged = onButtonChanged)
            FaceButtonDiamond(size = 140.dp, hapticsEnabled = hapticsEnabled, onButtonChanged = onButtonChanged)
        }

        // Analog Sticks + Center buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (analogMode) {
                TouchAnalogStick(
                    label = "L3",
                    size = 86.dp,
                    onStickChanged = { x, y -> onStickChanged(true, x, y) }
                )
            } else {
                Spacer(modifier = Modifier.width(64.dp))
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CenterMetaButton("SELECT", Ps1Button.SELECT, hapticsEnabled, onButtonChanged)
                    CenterMetaButton("START", Ps1Button.START, hapticsEnabled, onButtonChanged)
                }
                AnalogModeLedButton(analogMode = analogMode, onClick = onToggleAnalog)
            }

            if (analogMode) {
                TouchAnalogStick(
                    label = "R3",
                    size = 86.dp,
                    onStickChanged = { x, y -> onStickChanged(false, x, y) }
                )
            } else {
                Spacer(modifier = Modifier.width(64.dp))
            }
        }
    }
}

@Composable
private fun TouchDPad(
    size: Dp,
    hapticsEnabled: Boolean,
    onButtonChanged: (Ps1Button, Boolean) -> Unit
) {
    val view = LocalView.current
    var pressedDirs by remember { mutableStateOf(emptySet<Ps1Button>()) }

    BoxWithConstraints(
        modifier = Modifier
            .size(size)
            .testTag("dpad_pad")
            .pointerInput(hapticsEnabled) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val w = this.size.width.toFloat()
                        val h = this.size.height.toFloat()
                        val cx = w / 2f
                        val cy = h / 2f
                        val nextSet = mutableSetOf<Ps1Button>()
                        for (change in event.changes) {
                            if (change.pressed) {
                                val dx = change.position.x - cx
                                val dy = change.position.y - cy
                                val dead = w * 0.12f
                                if (dx < -dead) nextSet.add(Ps1Button.LEFT)
                                if (dx > dead) nextSet.add(Ps1Button.RIGHT)
                                if (dy < -dead) nextSet.add(Ps1Button.UP)
                                if (dy > dead) nextSet.add(Ps1Button.DOWN)
                                change.consume()
                            }
                        }
                        if (nextSet != pressedDirs) {
                            if (hapticsEnabled && (nextSet - pressedDirs).isNotEmpty()) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            for (b in listOf(Ps1Button.UP, Ps1Button.DOWN, Ps1Button.LEFT, Ps1Button.RIGHT)) {
                                val was = b in pressedDirs
                                val now = b in nextSet
                                if (was != now) onButtonChanged(b, now)
                            }
                            pressedDirs = nextSet
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val arm = w * 0.34f
            val halfArm = arm / 2f
            val cx = w / 2f
            val cy = h / 2f

            // Outer housing circle
            drawCircle(
                color = SlateSurfaceElevated.copy(alpha = 0.75f),
                radius = w * 0.48f
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = w * 0.48f,
                style = Stroke(width = 2f)
            )

            fun drawArm(button: Ps1Button, topLeft: Offset, boxSize: androidx.compose.ui.geometry.Size) {
                val active = button in pressedDirs
                drawRoundRect(
                    color = if (active) ElectricCyan.copy(alpha = 0.85f) else SlateSurfaceBright.copy(alpha = 0.92f),
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)
                )
            }

            drawArm(Ps1Button.UP, Offset(cx - halfArm, 6f), androidx.compose.ui.geometry.Size(arm, cy - 6f))
            drawArm(Ps1Button.DOWN, Offset(cx - halfArm, cy), androidx.compose.ui.geometry.Size(arm, cy - 6f))
            drawArm(Ps1Button.LEFT, Offset(6f, cy - halfArm), androidx.compose.ui.geometry.Size(cx - 6f, arm))
            drawArm(Ps1Button.RIGHT, Offset(cx, cy - halfArm), androidx.compose.ui.geometry.Size(cx - 6f, arm))

            // Center pivot dimple
            drawCircle(
                color = Color(0xFF0A0F1C),
                radius = arm * 0.28f,
                center = Offset(cx, cy)
            )
        }
    }
}

@Composable
private fun FaceButtonDiamond(
    size: Dp,
    hapticsEnabled: Boolean,
    onButtonChanged: (Ps1Button, Boolean) -> Unit
) {
    val view = LocalView.current
    var pressedButtons by remember { mutableStateOf(emptySet<Ps1Button>()) }

    Box(
        modifier = Modifier
            .size(size)
            .testTag("face_buttons_pad")
            .pointerInput(hapticsEnabled) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val w = this.size.width.toFloat()
                        val h = this.size.height.toFloat()
                        val btnRadius = w * 0.21f
                        val centers = mapOf(
                            Ps1Button.TRIANGLE to Offset(w * 0.5f, h * 0.2f),
                            Ps1Button.CIRCLE to Offset(w * 0.8f, h * 0.5f),
                            Ps1Button.CROSS to Offset(w * 0.5f, h * 0.8f),
                            Ps1Button.SQUARE to Offset(w * 0.2f, h * 0.5f)
                        )
                        val nextSet = mutableSetOf<Ps1Button>()
                        for (change in event.changes) {
                            if (change.pressed) {
                                // Allow sliding or multi-touch across 4 buttons
                                var bestBtn: Ps1Button? = null
                                var bestDist = Float.MAX_VALUE
                                for ((btn, c) in centers) {
                                    val d = hypot(change.position.x - c.x, change.position.y - c.y)
                                    if (d <= btnRadius * 1.45f && d < bestDist) {
                                        bestDist = d
                                        bestBtn = btn
                                    }
                                }
                                if (bestBtn != null) nextSet.add(bestBtn)
                                change.consume()
                            }
                        }
                        if (nextSet != pressedButtons) {
                            if (hapticsEnabled && (nextSet - pressedButtons).isNotEmpty()) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            for (b in centers.keys) {
                                val was = b in pressedButtons
                                val now = b in nextSet
                                if (was != now) onButtonChanged(b, now)
                            }
                            pressedButtons = nextSet
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val r = w * 0.185f

            fun drawSymbolBtn(
                btn: Ps1Button,
                center: Offset,
                accent: Color,
                drawGlyph: (Offset, Float, Color) -> Unit
            ) {
                val active = btn in pressedButtons
                drawCircle(
                    color = if (active) accent.copy(alpha = 0.35f) else SlateSurfaceBright.copy(alpha = 0.88f),
                    radius = r,
                    center = center
                )
                drawCircle(
                    color = if (active) accent else Color.White.copy(alpha = 0.18f),
                    radius = r,
                    center = center,
                    style = Stroke(width = if (active) 4f else 2f)
                )
                drawGlyph(center, r * 0.48f, accent)
            }

            // Triangle (top)
            drawSymbolBtn(Ps1Button.TRIANGLE, Offset(w * 0.5f, h * 0.2f), PsTriangleGreen) { c, s, col ->
                val p = Path().apply {
                    moveTo(c.x, c.y - s)
                    lineTo(c.x + s * 0.9f, c.y + s * 0.75f)
                    lineTo(c.x - s * 0.9f, c.y + s * 0.75f)
                    close()
                }
                drawPath(p, color = col, style = Stroke(width = 4.5f))
            }

            // Circle (right)
            drawSymbolBtn(Ps1Button.CIRCLE, Offset(w * 0.8f, h * 0.5f), PsCircleRed) { c, s, col ->
                drawCircle(color = col, radius = s * 0.85f, center = c, style = Stroke(width = 4.5f))
            }

            // Cross (bottom)
            drawSymbolBtn(Ps1Button.CROSS, Offset(w * 0.5f, h * 0.8f), PsCrossBlue) { c, s, col ->
                val d = s * 0.75f
                drawLine(col, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = 4.8f, cap = StrokeCap.Round)
                drawLine(col, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), strokeWidth = 4.8f, cap = StrokeCap.Round)
            }

            // Square (left)
            drawSymbolBtn(Ps1Button.SQUARE, Offset(w * 0.2f, h * 0.5f), PsSquarePink) { c, s, col ->
                val d = s * 0.75f
                drawRect(
                    color = col,
                    topLeft = Offset(c.x - d, c.y - d),
                    size = androidx.compose.ui.geometry.Size(d * 2f, d * 2f),
                    style = Stroke(width = 4.5f)
                )
            }
        }
    }
}

@Composable
private fun TouchAnalogStick(
    label: String,
    size: Dp,
    onStickChanged: (Float, Float) -> Unit
) {
    var knobX by remember { mutableFloatStateOf(0f) }
    var knobY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val radius = this.size.width / 2f
                        val maxTravel = radius * 0.65f
                        val active = event.changes.firstOrNull { it.pressed }
                        if (active != null) {
                            val dx = active.position.x - radius
                            val dy = active.position.y - radius
                            val dist = hypot(dx, dy)
                            val clamped = dist.coerceAtMost(maxTravel)
                            val ang = atan2(dy, dx)
                            val nx = if (maxTravel > 0f) (clamped * cos(ang)) / maxTravel else 0f
                            val ny = if (maxTravel > 0f) (clamped * sin(ang)) / maxTravel else 0f
                            knobX = nx
                            knobY = ny
                            onStickChanged(nx, ny)
                            active.consume()
                        } else {
                            knobX = 0f
                            knobY = 0f
                            onStickChanged(0f, 0f)
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.width / 2f
            drawCircle(color = SlateSurfaceElevated.copy(alpha = 0.85f), radius = r * 0.94f)
            drawCircle(color = Color.White.copy(alpha = 0.14f), radius = r * 0.94f, style = Stroke(width = 2f))
            val travel = r * 0.45f
            val center = Offset(r + knobX * travel, r + knobY * travel)
            drawCircle(color = SlateSurfaceBright, radius = r * 0.48f, center = center)
            drawCircle(
                color = if (knobX != 0f || knobY != 0f) ElectricCyan else Color.White.copy(alpha = 0.25f),
                radius = r * 0.48f,
                center = center,
                style = Stroke(width = 2.5f)
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = JetBrainsMonoFontFamily,
            color = TextSecondary
        )
    }
}

@Composable
private fun ShoulderPillButton(
    label: String,
    button: Ps1Button,
    hapticsEnabled: Boolean,
    onButtonChanged: (Ps1Button, Boolean) -> Unit
) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(width = 66.dp, height = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (pressed) ElectricCyan.copy(alpha = 0.35f)
                else SlateSurfaceBright.copy(alpha = 0.88f)
            )
            .border(
                width = 1.5.dp,
                color = if (pressed) ElectricCyan else Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(14.dp)
            )
            .pointerInput(button, hapticsEnabled) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed }
                        event.changes.forEach { it.consume() }
                        if (anyPressed != pressed) {
                            if (anyPressed && hapticsEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            pressed = anyPressed
                            onButtonChanged(button, anyPressed)
                        }
                        if (!anyPressed) break
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = if (pressed) ElectricCyan else TextPrimary
        )
    }
}

@Composable
private fun CenterMetaButton(
    label: String,
    button: Ps1Button,
    hapticsEnabled: Boolean,
    onButtonChanged: (Ps1Button, Boolean) -> Unit
) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(width = 70.dp, height = 48.dp)
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (pressed) ElectricCyan.copy(alpha = 0.35f) else SlateSurfaceBright.copy(alpha = 0.9f))
            .border(1.dp, if (pressed) ElectricCyan else Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
            .pointerInput(button, hapticsEnabled) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed }
                        event.changes.forEach { it.consume() }
                        if (anyPressed != pressed) {
                            if (anyPressed && hapticsEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            pressed = anyPressed
                            onButtonChanged(button, anyPressed)
                        }
                        if (!anyPressed) break
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = if (pressed) ElectricCyan else TextPrimary
        )
    }
}

@Composable
private fun AnalogModeLedButton(
    analogMode: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .padding(6.dp)
            .clip(CircleShape)
            .background(SlateSurfaceElevated)
            .border(1.5.dp, if (analogMode) PsTriangleGreen else Color.White.copy(alpha = 0.2f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.pressed }) {
                        event.changes.forEach { it.consume() }
                        onClick()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (analogMode) PsTriangleGreen else PsCircleRed)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "AN",
                fontSize = 8.sp,
                fontFamily = JetBrainsMonoFontFamily,
                color = TextSecondary
            )
        }
    }
}
