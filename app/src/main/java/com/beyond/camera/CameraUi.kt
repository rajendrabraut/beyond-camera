package com.beyond.camera

import android.hardware.camera2.CameraMetadata
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beyond.camera.camera.CameraMode
import com.beyond.camera.camera.ProCaps
import com.beyond.camera.camera.ProState
import com.beyond.camera.engine.ProcessingQueue
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun CircleIcon(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (active) Ui.Accent else Ui.Chip)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun TextPill(label: String, active: Boolean, onClick: (() -> Unit)? = null) {
    var modifier = Modifier
        .clip(RoundedCornerShape(50))
        .background(if (active) Ui.Accent else Ui.Chip)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Text(
        text = label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    sub: ((T) -> String)? = null,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(4.dp)
    ) {
        for (option in options) {
            val isSelected = option == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) Ui.Accent else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(label(option), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (sub != null) Text(sub(option), color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun PanelLabel(text: String) {
    Text(text, color = Ui.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp))
}

/** Scrollable row of capture modes; the chosen one is a filled pill. */
@Composable
fun ModeStrip(selected: CameraMode, enabled: Boolean, onSelect: (CameraMode) -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(selected) {
        // Roughly centre the chosen mode.
        val target = with(density) { (selected.ordinal * 84 - 110).dp.roundToPx() }
        scroll.animateScrollTo(target.coerceAtLeast(0))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (mode in CameraMode.entries) {
            val isSelected = mode == selected
            Text(
                text = mode.label,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .alpha(if (enabled || isSelected) 1f else 0.35f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Ui.Accent else Color.Transparent)
                    .clickable(enabled = enabled && !isSelected) { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun ShutterButton(
    video: Boolean,
    recording: Boolean,
    progress: Float?,
    spinning: Boolean,
    burstCount: Int,
    enabled: Boolean,
    busy: Boolean,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    onRelease: () -> Unit
) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val release by rememberUpdatedState(onRelease)
    val inner by animateDpAsState(if (recording) 28.dp else 58.dp, label = "shutterInner")
    val corner by animateDpAsState(if (recording) 8.dp else 29.dp, label = "shutterCorner")
    val spin = if (spinning) {
        rememberInfiniteTransition(label = "nightCapture").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing)),
            label = "nightRing"
        )
    } else {
        null
    }

    Box(
        modifier = Modifier
            .size(82.dp)
            .alpha(if (enabled && !busy) 1f else 0.45f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { tap() },
                    onLongPress = { long?.invoke() },
                    onPress = {
                        tryAwaitRelease()
                        release()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.9f), radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
            if (progress != null || spin != null) {
                val w = 5.dp.toPx()
                drawArc(
                    color = Ui.Accent,
                    startAngle = spin?.value ?: -90f,
                    sweepAngle = if (spin != null) 95f else 360f * (progress ?: 0f).coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    style = Stroke(w, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            Modifier
                .size(inner)
                .clip(RoundedCornerShape(corner))
                .background(if (video) Ui.Record else Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (burstCount > 0) {
                Text(burstCount.toString(), color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun Thumb(item: MediaItem?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(enabled = item != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (item != null) {
            val context = LocalContext.current
            val revision = if (item.isVideo) 0 else ProcessingQueue.revision(item.uri)
            val request = remember(item.uri, item.isVideo, revision) {
                ImageRequest.Builder(context)
                    .data(item.uri)
                    .apply { if (!item.isVideo) memoryCacheKey("${item.uri}:thumb:$revision") }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = "Last capture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (item.isVideo) {
                Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun zoomLabel(value: Float): String =
    if (value >= 10f || (value * 10).roundToInt() % 10 == 0) "${value.roundToInt()}x" else "%.1fx".format(value)

/** Quick zoom stops plus a fine slider (tap the highlighted stop to show it). */
@Composable
fun ZoomBar(ratio: Float, min: Float, max: Float, onZoom: (Float) -> Unit) {
    if (max <= min + 0.05f) return
    var slider by remember { mutableStateOf(false) }
    val stops = remember(min, max) {
        buildList {
            if (min < 0.95f) add(min)
            add(1f)
            for (s in listOf(2f, 4f, 8f)) if (s <= max + 0.01f) add(s)
        }
    }
    val current = stops.lastOrNull { it <= ratio + 0.05f } ?: stops.first()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (slider) {
            val span = ln(max / min)
            Slider(
                value = (ln(ratio.coerceIn(min, max) / min) / span).coerceIn(0f, 1f),
                onValueChange = { onZoom(min * (max / min).pow(it)) },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Ui.Accent),
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(36.dp)
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Ui.Chip)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (stop in stops) {
                val isCurrent = stop == current
                Text(
                    text = if (isCurrent) zoomLabel(ratio) else zoomLabel(stop).removeSuffix("x"),
                    color = Color.White,
                    fontSize = if (isCurrent) 13.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isCurrent) Ui.Accent else Color.Transparent)
                        .clickable {
                            if (isCurrent) slider = !slider else onZoom(stop)
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.35f)
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

@Composable
fun FocusRing(point: Offset, tick: Long, onDone: () -> Unit) {
    var shown by remember(tick) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (shown) 1f else 1.5f, tween(220), label = "focusScale")
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(tick) {
        shown = true
        delay(2500)
        done()
    }
    val sizeDp = 70.dp
    val half = with(LocalDensity.current) { (sizeDp / 2).toPx() }
    Box(
        Modifier
            .offset { IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt()) }
            .size(sizeDp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * scale * 0.66f
            drawCircle(Ui.AccentSoft, radius = r, style = Stroke(1.6.dp.toPx()))
            drawCircle(Ui.AccentSoft, radius = 2.5.dp.toPx())
        }
    }
}

/** Framing guide for the Scan mode with a moving line. */
@Composable
fun ScanFrame() {
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanLine"
    )
    Canvas(Modifier.fillMaxSize()) {
        val side = size.width * 0.68f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2.3f
        val len = side * 0.14f
        val stroke = 4.dp.toPx()
        val radius = 18.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.25f),
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(1.dp.toPx())
        )
        val c = Ui.Accent
        // Corner marks.
        for ((cx, dxSign) in listOf(left to 1f, left + side to -1f)) {
            for ((cy, dySign) in listOf(top to 1f, top + side to -1f)) {
                drawLine(c, Offset(cx, cy + dySign * radius), Offset(cx, cy + dySign * (radius + len)), stroke, StrokeCap.Round)
                drawLine(c, Offset(cx + dxSign * radius, cy), Offset(cx + dxSign * (radius + len), cy), stroke, StrokeCap.Round)
            }
        }
        val y = top + side * (0.08f + 0.84f * sweep)
        drawLine(c.copy(alpha = 0.8f), Offset(left + side * 0.08f, y), Offset(left + side * 0.92f, y), 2.dp.toPx(), StrokeCap.Round)
    }
}

/** Brief white flash over the preview when a photo is taken. */
@Composable
fun ShutterFlash(tick: Long) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick == 0L) return@LaunchedEffect
        visible = true
        delay(60)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.65f else 0f,
        animationSpec = tween(if (visible) 40 else 180),
        label = "shutterFlash"
    )
    if (alpha > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(alpha)
                .background(Color.White)
        )
    }
}

private enum class ProParam(val label: String) { WB("WB"), FOCUS("Focus"), SHUTTER("Shutter"), ISO("ISO"), EV("EV") }

private val SHUTTER_STEPS = longArrayOf(
    250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L,
    66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L
)

private fun shutterLabel(ns: Long): String =
    if (ns >= 1_000_000_000L) "${ns / 1_000_000_000L}s" else "1/${(1_000_000_000.0 / ns).roundToInt()}"

private val AWB_NAMES = listOf(
    CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Sunny",
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Bulb",
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Tube"
)

/** Manual controls. Anything the phone's camera can't do is shown as unavailable instead of hidden. */
@Composable
fun ProPanel(caps: ProCaps?, state: ProState, onChange: (ProState) -> Unit) {
    var open by remember { mutableStateOf<ProParam?>(null) }
    if (caps == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        when (open) {
            ProParam.WB -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                for ((mode, name) in AWB_NAMES) {
                    if (mode in caps.awbModes) TextPill(name, state.awb == mode) { onChange(state.copy(awb = mode)) }
                }
            }

            ProParam.FOCUS -> if (caps.minFocusDistance > 0f) {
                ProSlider(
                    value = (state.focus ?: 0f) / caps.minFocusDistance,
                    auto = state.focus == null,
                    left = "Far",
                    right = "Near",
                    onAuto = { onChange(state.copy(focus = null)) },
                    onValue = { onChange(state.copy(focus = it * caps.minFocusDistance)) }
                )
            } else {
                Unsupported("Manual focus isn't available on this camera.")
            }

            ProParam.SHUTTER -> {
                val range = caps.exposureRangeNs
                val steps = if (range == null) emptyList() else SHUTTER_STEPS.filter { it in range }
                if (caps.manualSensor && steps.size > 1) {
                    val index = state.shutterNs?.let { steps.indexOf(it).coerceAtLeast(0) } ?: (steps.size / 2)
                    ProSlider(
                        value = index / (steps.size - 1).toFloat(),
                        auto = state.shutterNs == null,
                        left = shutterLabel(steps.first()),
                        right = shutterLabel(steps.last()),
                        onAuto = { onChange(state.copy(shutterNs = null, iso = null)) },
                        onValue = { onChange(state.copy(shutterNs = steps[(it * (steps.size - 1)).roundToInt()])) }
                    )
                } else {
                    Unsupported("Manual shutter speed isn't available on this camera.")
                }
            }

            ProParam.ISO -> {
                val range = caps.isoRange
                if (caps.manualSensor && range != null && range.last > range.first) {
                    val span = ln(range.last.toFloat() / range.first)
                    ProSlider(
                        value = state.iso?.let { ln(it.toFloat() / range.first) / span } ?: 0.3f,
                        auto = state.iso == null,
                        left = "${range.first}",
                        right = "${range.last}",
                        onAuto = { onChange(state.copy(iso = null, shutterNs = null)) },
                        onValue = {
                            val iso = (range.first * (range.last.toFloat() / range.first).pow(it)).roundToInt()
                            onChange(state.copy(iso = iso.coerceIn(range.first, range.last)))
                        }
                    )
                } else {
                    Unsupported("Manual ISO isn't available on this camera.")
                }
            }

            ProParam.EV -> {
                val range = caps.evRange
                if (range.last > range.first) {
                    ProSlider(
                        value = (state.ev - range.first) / (range.last - range.first).toFloat(),
                        auto = state.ev == 0,
                        left = "%+.1f".format(range.first * caps.evStep),
                        right = "%+.1f".format(range.last * caps.evStep),
                        onAuto = { onChange(state.copy(ev = 0)) },
                        onValue = {
                            onChange(state.copy(ev = (range.first + it * (range.last - range.first)).roundToInt()))
                        }
                    )
                } else {
                    Unsupported("Exposure adjustment isn't available on this camera.")
                }
            }

            null -> Unit
        }
        if (open != null) Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (param in ProParam.entries) {
                val value = when (param) {
                    ProParam.WB -> AWB_NAMES.firstOrNull { it.first == state.awb }?.second ?: "Auto"
                    ProParam.FOCUS -> if (state.focus == null) "Auto" else "Manual"
                    ProParam.SHUTTER -> state.shutterNs?.let { shutterLabel(it) } ?: "Auto"
                    ProParam.ISO -> state.iso?.toString() ?: "Auto"
                    ProParam.EV -> "%+.1f".format(state.ev * caps.evStep)
                }
                val isOpen = open == param
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isOpen) Ui.Accent.copy(alpha = 0.35f) else Color.Transparent)
                        .clickable { open = if (isOpen) null else param }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(param.label, color = Ui.Dim, fontSize = 11.sp)
                    Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Unsupported(text: String) {
    Text(text, color = Ui.Dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun ProSlider(
    value: Float,
    auto: Boolean,
    left: String,
    right: String,
    onAuto: () -> Unit,
    onValue: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextPill("Auto", auto, onAuto)
        Spacer(Modifier.width(8.dp))
        Text(left, color = Ui.Dim, fontSize = 11.sp)
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValue,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Ui.Accent),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
        )
        Text(right, color = Ui.Dim, fontSize = 11.sp)
    }
}
