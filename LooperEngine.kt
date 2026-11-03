package com.example.looper

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val SR = 48000
const val NUM_TRACKS = 9
const val PREROLL = SR * 2     // audio kept from BEFORE you hit record, so you can pull the start earlier
const val POSTROLL = SR * 2    // audio kept AFTER you stop, so you can push the end later
const val MIN_LOOP = SR / 5
const val CHUNK = 256

enum class TrackState { EMPTY, ARMED, RECORDING, PLAYING, MUTED }

class Track {
    /** Raw recording, including pre-roll and post-roll. */
    @Volatile var data = ShortArray(0)
    /** Index into [data] where this track's loop begins. */
    @Volatile var trimStart = 0
    @Volatile var state = TrackState.EMPTY
    @Volatile var volume = 1f
}

private class Rec(val idx: Int, val isMaster: Boolean, pre: ShortArray) {
    var buf = ShortArray(max(SR * 20, pre.size * 2))
    var len = 0
    val preLen = pre.size
    var postLeft = 0
    var cancelled = false

    init {
        System.arraycopy(pre, 0, buf, 0, pre.size)
        len = pre.size
    }

    fun append(src: ShortArray, from: Int, to: Int) {
        val c = to - from
        if (c <= 0) return
        if (len + c > buf.size) buf = buf.copyOf(max(buf.size * 2, len + c))
        System.arraycopy(src, from, buf, len, c)
        len += c
    }
}

/**
 * One audio thread does everything: read mic -> record -> mix all tracks -> write to speaker.
 *
 * Track 1 (the "master") sets the loop length. Every later track records exactly one loop,
 * starting at the loop start, and is then locked to the same length.
 */
class LooperEngine {
    val tracks = Array(NUM_TRACKS) { Track() }

    @Volatile var masterLen = 0
        private set
    @Volatile var masterPos = 0
        private set
    @Volatile var masterIdx = -1
        private set
    @Volatile var paused = false
        private set
    @Volatile var monitor = false
    @Volatile var latencyMs = 0
    @Volatile var inLevel = 0f

    @Volatile private var running = false
    private var thread: Thread? = null
    private val cmds = ConcurrentLinkedQueue<() -> Unit>()

    // ---- audio-thread-only state ----
    private val ring = ShortArray(PREROLL)
    private var ringW = 0
    private var ringFilled = 0
    private var active: Rec? = null
    private val finishing = ArrayList<Rec>()
    private var armedIdx = -1
    private val acc = FloatArray(CHUNK)

    // ------------------------------------------------------------------ public API

    @Synchronized fun start() {
        if (running) return
        running = true
        thread = Thread({ audioLoop() }, "looper-audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
        thread?.join()
        thread = null
        cmds.clear()
        active?.let { tracks[it.idx].state = TrackState.EMPTY }
        active = null
        finishing.clear()
        if (armedIdx >= 0) tracks[armedIdx].state = TrackState.EMPTY
        armedIdx = -1
        ringW = 0
        ringFilled = 0
    }

    fun tap(i: Int) { cmds.add { doTap(i) } }
    fun clear(i: Int) { cmds.add { if (i == masterIdx) clearAll() else resetTrack(i) } }
    fun setPaused(p: Boolean) {
        cmds.add {
            paused = p
            masterPos = 0
            if (p) {
                active?.takeIf { !it.isMaster }?.let { tracks[it.idx].state = TrackState.EMPTY; active = null }
                if (armedIdx >= 0) { tracks[armedIdx].state = TrackState.EMPTY; armedIdx = -1 }
            }
        }
    }

    /** Move where a track starts (in samples into its raw recording). For the master track this
     *  also shifts every other track by the same amount so they stay lined up with each other. */
    fun moveStart(i: Int, target: Int) {
        val t = tracks[i]
        if (t.state == TrackState.EMPTY) return
        if (i == masterIdx) {
            val end = t.trimStart + masterLen
            val ns = target.coerceIn(0, end - MIN_LOOP)
            val d = ns - t.trimStart
            if (d == 0) return
            for (j in tracks.indices) {
                val o = tracks[j]
                if (j != i && o.state != TrackState.EMPTY) o.trimStart = (o.trimStart + d).coerceIn(0, o.data.size)
            }
            masterLen = end - ns      // keep the loop END where it was
            t.trimStart = ns
        } else {
            t.trimStart = target.coerceIn(0, t.data.size)
        }
    }

    /** Move the loop end (only meaningful for the master track; changes the loop length). */
    fun setLoopEnd(targetEnd: Int) {
        val m = masterIdx
        if (m < 0) return
        val t = tracks[m]
        val maxLen = max(MIN_LOOP, t.data.size - t.trimStart)
        masterLen = (targetEnd - t.trimStart).coerceIn(MIN_LOOP, maxLen)
    }

    fun nudgeStart(i: Int, ms: Int) = moveStart(i, tracks[i].trimStart + ms * SR / 1000)
    fun nudgeEnd(ms: Int) = setLoopEnd(tracks[masterIdx.coerceAtLeast(0)].trimStart + masterLen + ms * SR / 1000)

    // ------------------------------------------------------------------ commands (audio thread)

    private fun doTap(i: Int) {
        val t = tracks[i]
        when (t.state) {
            TrackState.EMPTY -> {
                if (masterLen == 0 && active == null) {
                    startRec(i, true)
                } else if (masterLen > 0) {
                    if (paused) { paused = false; masterPos = 0 }
                    if (armedIdx >= 0) tracks[armedIdx].state = TrackState.EMPTY
                    armedIdx = i
                    t.state = TrackState.ARMED
                }
            }
            TrackState.ARMED -> { armedIdx = -1; t.state = TrackState.EMPTY }
            TrackState.RECORDING -> {
                val r = active
                if (r != null && r.idx == i) {
                    if (r.isMaster) finishRec(r) else { active = null; t.state = TrackState.EMPTY }
                }
            }
            TrackState.PLAYING -> t.state = TrackState.MUTED
            TrackState.MUTED -> t.state = TrackState.PLAYING
        }
    }

    private fun resetTrack(i: Int) {
        val t = tracks[i]
        t.state = TrackState.EMPTY
        t.data = ShortArray(0)
        t.trimStart = 0
        t.volume = 1f
        finishing.filter { it.idx == i }.forEach { it.cancelled = true }
        if (active?.idx == i) active = null
        if (armedIdx == i) armedIdx = -1
    }

    private fun clearAll() {
        for (i in tracks.indices) resetTrack(i)
        masterLen = 0
        masterIdx = -1
        masterPos = 0
        paused = false
    }

    private fun startRec(i: Int, master: Boolean) {
        active = Rec(i, master, ringSnapshot())
        tracks[i].state = TrackState.RECORDING
    }

    private fun finishRec(r: Rec) {
        val t = tracks[r.idx]
        val loopLen = r.len - r.preLen
        if (r.isMaster) {
            if (loopLen < MIN_LOOP) { t.state = TrackState.EMPTY; active = null; return }
            t.data = r.buf.copyOf(r.len)
            t.trimStart = r.preLen
            masterIdx = r.idx
            masterLen = loopLen
            masterPos = 0
        } else {
            t.data = r.buf.copyOf(r.len)
            t.trimStart = min(r.preLen + latencyMs * SR / 1000, r.len)
        }
        t.state = TrackState.PLAYING
        r.postLeft = POSTROLL
        finishing.add(r)
        active = null
    }

    private fun onWrap() {
        active?.let { if (!it.isMaster) finishRec(it) }
        if (armedIdx >= 0) {
            val i = armedIdx
            armedIdx = -1
            startRec(i, false)
        }
    }

    // ------------------------------------------------------------------ audio thread

    private fun ringSnapshot(): ShortArray {
        val n = ringFilled
        val o = ShortArray(n)
        val start = (ringW - n + PREROLL) % PREROLL
        for (k in 0 until n) o[k] = ring[(start + k) % PREROLL]
        return o
    }

    private fun pushInput(b: ShortArray, from: Int, to: Int) {
        if (to <= from) return
        for (k in from until to) {
            ring[ringW] = b[k]
            ringW = (ringW + 1) % PREROLL
        }
        ringFilled = min(PREROLL, ringFilled + (to - from))
        active?.append(b, from, to)
        val it = finishing.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val c = min(to - from, r.postLeft)
            r.append(b, from, from + c)
            r.postLeft -= c
            if (r.postLeft <= 0) {
                if (!r.cancelled) tracks[r.idx].data = r.buf.copyOf(r.len)
                it.remove()
            }
        }
    }

    private fun processChunk(inBuf: ShortArray, outBuf: ShortArray) {
        var peak = 0
        for (s in inBuf) peak = max(peak, abs(s.toInt()))
        inLevel = max(peak / 32768f, inLevel * 0.9f)

        val len = masterLen
        val isPaused = paused
        var evt = -1
        if (len > 0 && !isPaused) {
            if (masterPos >= len) masterPos %= len
            evt = if (masterPos == 0) 0 else if (len - masterPos < CHUNK) len - masterPos else -1
        }

        // record input, splitting the chunk at the exact sample where the loop wraps
        var cursor = 0
        if (evt > 0) { pushInput(inBuf, 0, evt); cursor = evt }
        if (evt >= 0) onWrap()
        pushInput(inBuf, cursor, CHUNK)

        // mix playback
        java.util.Arrays.fill(acc, 0f)
        if (len > 0 && !isPaused) {
            for (t in tracks) {
                if (t.state != TrackState.PLAYING) continue
                val d = t.data
                val ts = t.trimStart
                val v = t.volume / 32768f
                var p = masterPos
                for (i in 0 until CHUNK) {
                    val ix = ts + p
                    if (ix < d.size) acc[i] += d[ix] * v
                    p++
                    if (p >= len) p = 0
                }
            }
            masterPos = (masterPos + CHUNK) % len
        }
        val mon = monitor
        for (i in 0 until CHUNK) {
            var s = acc[i]
            if (mon) s += inBuf[i]
            outBuf[i] = s.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        val inMin = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outMin = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        var rec = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(inMin, CHUNK * 8)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(inMin, CHUNK * 8)
            )
        }
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(outMin, CHUNK * 2 * 4))
            .build()

        val inBuf = ShortArray(CHUNK)
        val outBuf = ShortArray(CHUNK)
        rec.startRecording()
        out.play()

        while (running) {
            while (true) { (cmds.poll() ?: break)() }
            val r = rec.read(inBuf, 0, CHUNK)
            if (r <= 0) { Thread.sleep(5); continue }
            for (k in r until CHUNK) inBuf[k] = 0
            processChunk(inBuf, outBuf)
            out.write(outBuf, 0, CHUNK)
        }

        rec.stop(); rec.release()
        out.stop(); out.release()
    }
}
