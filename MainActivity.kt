package com.example.looper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val engine = LooperEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { ok -> if (ok) engine.start() }
                LaunchedEffect(Unit) {
                    if (!hasMic()) launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
                LooperScreen(engine)
            }
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onStart() { super.onStart(); if (hasMic()) engine.start() }
    override fun onStop() { super.onStop(); engine.stop() }
}

val padColors = listOf(Color(0xFFD400FF), Color(0xFFFF5CC8), Color(0xFF7DF9D6))

// ---------------------------------------------------------------- helpers

class PeakCache {
    var data: ShortArray? = null
    var start = -1
    var len = -1
    var bins = -1
    var peaks = FloatArray(0)
}

fun computePeaks(d: ShortArray, start: Int, len: Int, bins: Int): FloatArray {
    val out = FloatArray(bins)
    var mx = 2000f
    for (b in 0 until bins) {
        val a = start + (len.toLong() * b / bins).toInt()
        val e = min(d.size, start + (len.toLong() * (b + 1) / bins).toInt())
        if (a >= e) continue
        val step = max(1, (e - a) / 256)
        var p = 0
        var k = a
        while (k < e) { p = max(p, abs(d[k].toInt())); k += step }
        out[b] = p.toFloat()
        mx = max(mx, out[b])
    }
    for (b in out.indices) out[b] = out[b] / mx
    return out
}

// ---------------------------------------------------------------- screen

@Composable
fun LooperScreen(engine: LooperEngine) {
    val frame = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { frame.value = System.nanoTime(); delay(33) } }
    val slow by produceState(0) { while (true) { delay(250); value += 1 } }

    var selected by remember { mutableIntStateOf(-1) }
    var showSettings by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxSize().background(Color(0xFF1B1D1F)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            Modifier.fillMaxHeight().aspectRatio(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (r in 0 until 3) {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (c in 0 until 3) {
                        val i = r * 3 + c
                        Pad(
                            engine, i, frame, i == selected,
                            Modifier.weight(1f).fillMaxHeight(),
                            onLong = { selected = if (selected == i) -1 else i }
                        )
                    }
                }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val loopTxt =
                if (engine.masterLen > 0 && slow >= 0) "%.2f s".format(engine.masterLen / SR.toFloat()) else "--"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { engine.setPaused(!engine.paused) }) {
                    Text(if (slow >= 0 && engine.paused) "▶" else "■")
                }
                Column {
                    Text("loop", fontSize = 11.sp, color = Color.Gray)
                    Text(loopTxt, fontSize = 18.sp)
                }
                Canvas(Modifier.weight(1f).height(8.dp)) {
                    frame.value
                    drawRect(Color(0xFF2A2D30))
                    drawRect(Color(0xFF3DDC57), size = Size(size.width * engine.inLevel.coerceIn(0f, 1f), size.height))
                }
                TextButton(onClick = { showSettings = true }) { Text("⚙", fontSize = 22.sp) }
            }

            val sel = selected
            if (sel >= 0 && engine.tracks[sel].state != TrackState.EMPTY && slow >= 0) {
                TrimEditor(engine, sel, frame, onClose = { selected = -1 })
            } else {
                Text(
                    "Tap a circle to record. Tap again to stop. The first track sets the loop length.\n\n" +
                        "Tap an empty circle after that and it will start recording at the top of the next loop, " +
                        "for exactly one loop.\n\n" +
                        "Tap a filled circle to mute/unmute. Press and hold it to adjust its timing.",
                    color = Color.Gray, fontSize = 13.sp
                )
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Close") } },
            title = { Text("Settings") },
            text = {
                Column {
                    var lat by remember { mutableFloatStateOf(engine.latencyMs.toFloat()) }
                    Text("Latency compensation: ${lat.roundToInt()} ms (applies to new recordings)")
                    Slider(value = lat, onValueChange = { lat = it; engine.latencyMs = it.roundToInt() }, valueRange = 0f..250f)
                    var mon by remember { mutableStateOf(engine.monitor) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Monitor input (use headphones!)", Modifier.weight(1f))
                        Switch(checked = mon, onCheckedChange = { mon = it; engine.monitor = it })
                    }
                }
            }
        )
    }
}

// ---------------------------------------------------------------- pads

@Composable
fun Pad(
    engine: LooperEngine, i: Int, frame: State<Long>, selected: Boolean,
    modifier: Modifier, onLong: () -> Unit
) {
    val color = padColors[i / 3]
    val t = engine.tracks[i]
    val cache = remember { PeakCache() }

    Canvas(
        modifier.padding(2.dp).pointerInput(i) {
            detectTapGestures(onTap = { engine.tap(i) }, onLongPress = { onLong() })
        }
    ) {
        val now = frame.value
        val rad = min(size.width, size.height) / 2f - 3.dp.toPx()
        val c = center
        val st = t.state
        val ring = Stroke(width = if (selected) 5.dp.toPx() else 2.dp.toPx())

        when (st) {
            TrackState.EMPTY -> drawCircle(color.copy(alpha = 0.7f), rad, c, style = ring)
            TrackState.ARMED -> drawCircle(
                Color.Red, rad, c,
                style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), (now / 20_000_000L).toFloat()))
            )
            TrackState.RECORDING -> {
                val pulse = 0.35f + 0.15f * sin(now / 150_000_000.0).toFloat()
                drawCircle(Color.Red.copy(alpha = pulse), rad, c)
                drawCircle(Color.Red, rad, c, style = ring)
                drawCircle(Color.White.copy(alpha = 0.6f), rad * (0.15f + 0.8f * engine.inLevel.coerceIn(0f, 1f)), c, style = Stroke(2.dp.toPx()))
            }
            else -> {
                val alpha = if (st == TrackState.PLAYING) 1f else 0.3f
                drawCircle(color.copy(alpha = alpha), rad, c, style = ring)
                val len = engine.masterLen
                if (len > 0) {
                    val d = t.data
                    if (cache.data !== d || cache.start != t.trimStart || cache.len != len) {
                        cache.peaks = computePeaks(d, t.trimStart, len, 72)
                        cache.data = d; cache.start = t.trimStart; cache.len = len
                    }
                    val bins = cache.peaks.size
                    for (b in 0 until bins) {
                        val ang = b.toFloat() / bins * 2f * PI.toFloat() - PI.toFloat() / 2f
                        val r0 = rad * 0.28f
                        val r1 = r0 + (0.03f + cache.peaks[b]) * rad * 0.62f
                        drawLine(
                            color.copy(alpha = alpha),
                            Offset(c.x + cos(ang) * r0, c.y + sin(ang) * r0),
                            Offset(c.x + cos(ang) * r1, c.y + sin(ang) * r1),
                            strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round
                        )
                    }
                    val head = engine.masterPos.toFloat() / len * 360f - 90f
                    drawArc(
                        Color.White.copy(alpha = alpha), head - 5f, 10f, false,
                        topLeft = Offset(c.x - rad, c.y - rad), size = Size(rad * 2, rad * 2),
                        style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- trim editor

class WaveCache {
    var data: ShortArray? = null
    var bins = -1
    var peaks = FloatArray(0)
}

@Composable
fun TrimEditor(engine: LooperEngine, idx: Int, frame: State<Long>, onClose: () -> Unit) {
    val t = engine.tracks[idx]
    val isMaster = idx == engine.masterIdx
    val wc = remember(idx) { WaveCache() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Track ${idx + 1}", fontSize = 16.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { engine.clear(idx); onClose() }) { Text("Clear") }
            TextButton(onClick = onClose) { Text("Done") }
        }

        Canvas(
            Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF101214))
                .pointerInput(idx) {
                    var which = 0
                    detectDragGestures(
                        onDragStart = { o ->
                            val total = max(1, t.data.size).toFloat()
                            val sx = t.trimStart / total * size.width
                            val ex = (t.trimStart + engine.masterLen) / total * size.width
                            which = if (isMaster && abs(o.x - ex) < abs(o.x - sx)) 1 else 0
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val d = (drag.x / size.width * t.data.size).roundToInt()
                            if (which == 0) engine.moveStart(idx, t.trimStart + d)
                            else engine.setLoopEnd(t.trimStart + engine.masterLen + d)
                        }
                    )
                }
        ) {
            frame.value
            val w = size.width
            val h = size.height
            val d = t.data
            val bins = max(1, (w / 2f).toInt())
            if (wc.data !== d || wc.bins != bins) {
                wc.peaks = computePeaks(d, 0, d.size, bins)
                wc.data = d; wc.bins = bins
            }
            for (b in 0 until bins) {
                val x = b * 2f + 1f
                val ph = max(1f, wc.peaks[b] * h * 0.9f)
                drawLine(Color(0xFFB36BFF), Offset(x, (h - ph) / 2f), Offset(x, (h + ph) / 2f), strokeWidth = 1.6f)
            }
            val total = max(1, d.size).toFloat()
            val sx = t.trimStart / total * w
            val ex = min(w, (t.trimStart + engine.masterLen) / total * w)
            drawRect(Color(0xAA000000), Offset(0f, 0f), Size(sx, h))
            drawRect(Color(0xAA000000), Offset(ex, 0f), Size(max(0f, w - ex), h))
            drawLine(Color.White, Offset(sx, 0f), Offset(sx, h), strokeWidth = 3.dp.toPx())
            drawLine(if (isMaster) Color(0xFFFFB454) else Color(0x66FFFFFF), Offset(ex, 0f), Offset(ex, h), strokeWidth = 3.dp.toPx())
            val px = (t.trimStart + engine.masterPos) / total * w
            drawLine(Color(0xFF3DDC57), Offset(px, 0f), Offset(px, h), strokeWidth = 1.5.dp.toPx())
        }

        Text(
            if (isMaster) "Drag the white line = loop start, orange = loop end. Moving the start shifts all tracks together."
            else "Drag to slide this track earlier/later. The loop length is locked to track ${engine.masterIdx + 1}.",
            fontSize = 11.sp, color = Color.Gray
        )

        NudgeRow(if (isMaster) "Loop start" else "Slide") { ms -> engine.nudgeStart(idx, ms) }
        if (isMaster) NudgeRow("Loop end") { ms -> engine.nudgeEnd(ms) }

        var vol by remember(idx) { mutableFloatStateOf(t.volume) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", fontSize = 12.sp, modifier = Modifier.width(78.dp))
            Slider(value = vol, onValueChange = { vol = it; t.volume = it }, valueRange = 0f..1.5f)
        }
    }
}

@Composable
fun NudgeRow(label: String, onNudge: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.width(74.dp), fontSize = 12.sp)
        for (ms in listOf(-100, -10, -1, 1, 10, 100)) {
            FilledTonalButton(
                onClick = { onNudge(ms) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(1f).height(36.dp)
            ) { Text(if (ms > 0) "+$ms" else "$ms", fontSize = 11.sp) }
        }
    }
}
