package com.example.voicechanger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

enum class VoiceEffect(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val pitchFactor: Float,
    val speedFactor: Float,
    val robotFreq: Float = 0f,
    val echoDelayMs: Int = 0,
    val echoDecay: Float = 0f,
    val lowPassCutoff: Float = 20000f,
    val highPassCutoff: Float = 20f
) {
    ORIGINAL("Original", "Natural voice", Icons.Default.Person, 1.0f, 1.0f),
    CHIPMUNK("Chipmunk", "High-pitched & fast", Icons.Default.Pets, 1.65f, 1.15f),
    DEMON("Demon", "Deep, slow & ominous", Icons.Default.MoodBad, 0.65f, 0.9f),
    ROBOT("Cyborg", "Metallic ring modulation", Icons.Default.SmartToy, 1.0f, 1.0f, robotFreq = 65f),
    RADIO("Walkie-Talkie", "Narrow bandpass military comms", Icons.Default.SettingsVoice, 1.0f, 1.0f, lowPassCutoff = 3200f, highPassCutoff = 650f),
    ECHO_CAVE("Echo Chamber", "Spacious canyon reverberation", Icons.Default.SurroundSound, 1.0f, 1.0f, echoDelayMs = 220, echoDecay = 0.45f),
    ALIEN("Extraterrestrial", "High resonant oscillation", Icons.Default.Psychology, 1.35f, 0.95f, robotFreq = 140f),
    GIANT("Titan", "Massive crushing presence", Icons.Default.SportsKabaddi, 0.55f, 0.85f, echoDelayMs = 120, echoDecay = 0.3f)
}

object AudioDSPProcessor {
    private const val SAMPLE_RATE = 44100

    /**
     * Applies real-time digital signal processing effects on PCM 16-bit audio data.
     */
    fun process(
        rawPcm: ByteArray,
        effect: VoiceEffect
    ): ByteArray {
        val sampleCount = rawPcm.size / 2
        val shortBuffer = ShortArray(sampleCount)
        ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)

        // Convert to normalized floating point array [-1.0 .. 1.0]
        var floatSamples = FloatArray(sampleCount) { idx ->
            shortBuffer[idx] / 32768.0f
        }

        // 1. Time-Domain Pitch Shifting & Resampling
        if (effect.pitchFactor != 1.0f || effect.speedFactor != 1.0f) {
            floatSamples = resampleAndPitch(floatSamples, effect.pitchFactor, effect.speedFactor)
        }

        // 2. Ring Modulation (Cyborg/Robot/Alien voice effect)
        if (effect.robotFreq > 0f) {
            val carrierPhaseIncrement = (2.0 * Math.PI * effect.robotFreq) / SAMPLE_RATE
            var phase = 0.0
            for (i in floatSamples.indices) {
                val modulation = sin(phase).toFloat()
                floatSamples[i] = floatSamples[i] * 0.5f + (floatSamples[i] * modulation * 0.85f)
                phase += carrierPhaseIncrement
                if (phase >= 2 * Math.PI) phase -= 2 * Math.PI
            }
        }

        // 3. Simple Delay / Echo Loop
        if (effect.echoDelayMs > 0 && effect.echoDecay > 0f) {
            val delaySamples = (SAMPLE_RATE * (effect.echoDelayMs / 1000f)).toInt()
            val echoOutput = FloatArray(floatSamples.size)
            for (i in floatSamples.indices) {
                var sample = floatSamples[i]
                if (i >= delaySamples) {
                    sample += echoOutput[i - delaySamples] * effect.echoDecay
                }
                echoOutput[i] = sample.coerceIn(-1.0f, 1.0f)
            }
            floatSamples = echoOutput
        }

        // 4. Bandpass / Telephone filter (High-pass & Low-pass)
        if (effect.highPassCutoff > 20f || effect.lowPassCutoff < 20000f) {
            floatSamples = applyBandpass(floatSamples, effect.highPassCutoff, effect.lowPassCutoff, SAMPLE_RATE)
        }

        // Convert back to 16-bit PCM Byte Array
        val outputBytes = ByteArray(floatSamples.size * 2)
        val byteBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in floatSamples) {
            val clamped = (sample.coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort()
            byteBuffer.putShort(clamped)
        }
        return outputBytes
    }

    /**
     * Resamples the audio stream using linear interpolation for pitch/speed changes.
     */
    private fun resampleAndPitch(input: FloatArray, pitch: Float, speed: Float): FloatArray {
        val effectiveRatio = pitch * speed
        val newSize = (input.size / effectiveRatio).toInt()
        val output = FloatArray(newSize)

        for (i in 0 until newSize) {
            val originPos = i * effectiveRatio
            val index0 = originPos.toInt()
            val index1 = (index0 + 1).coerceAtMost(input.size - 1)
            val fraction = originPos - index0

            val val0 = if (index0 < input.size) input[index0] else 0f
            val val1 = if (index1 < input.size) input[index1] else 0f

            // Linear interpolation
            output[i] = val0 + fraction * (val1 - val0)
        }
        return output
    }

    /**
     * Single-pole simple bandpass filter for walkie-talkie / radio effect.
     */
    private fun applyBandpass(input: FloatArray, hpFreq: Float, lpFreq: Float, sampleRate: Int): FloatArray {
        val out = FloatArray(input.size)
        val rcLp = 1.0f / (2 * Math.PI.toFloat() * lpFreq)
        val dt = 1.0f / sampleRate
        val alphaLp = dt / (rcLp + dt)

        var lastLp = 0f
        var lastHp = 0f
        var lastIn = 0f

        val rcHp = 1.0f / (2 * Math.PI.toFloat() * hpFreq)
        val alphaHp = rcHp / (rcHp + dt)

        for (i in input.indices) {
            // Low-pass
            lastLp += alphaLp * (input[i] - lastLp)
            // High-pass on lowpassed signal
            lastHp = alphaHp * (lastHp + lastLp - lastIn)
            lastIn = lastLp
            out[i] = (lastHp * 1.5f).coerceIn(-1.0f, 1.0f)
        }
        return out
    }
}

class AudioRecorderPlayer(private val context: Context) {
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var playingJob: Job? = null
    private var audioTrack: AudioTrack? = null

    var rawRecordedBytes = ByteArray(0)
    var isPlaying = mutableStateOf(false)
    var currentAmplitudes = mutableStateOf(FloatArray(20) { 0.05f })

    /**
     * Starts microphone recording in a background coroutine.
     */
    fun startRecording(coroutineScope: CoroutineScope, onStop: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        val outputStream = ByteArrayOutputStream()
        audioRecord?.startRecording()
        isRecording = true

        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            val audioBuffer = ByteArray(bufferSize)
            while (isRecording) {
                val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readBytes > 0) {
                    outputStream.write(audioBuffer, 0, readBytes)

                    // Calculate amplitude for visualizer
                    var sum = 0.0
                    for (i in 0 until readBytes step 2) {
                        val sample = ((audioBuffer[i + 1].toInt() shl 8) or (audioBuffer[i].toInt() and 0xFF)).toShort()
                        sum += abs(sample.toDouble())
                    }
                    val avg = (sum / (readBytes / 2)).toFloat() / 32768.0f
                    withContext(Dispatchers.Main) {
                        val current = currentAmplitudes.value.toMutableList()
                        current.removeAt(0)
                        current.add(avg.coerceIn(0.05f, 1.0f))
                        currentAmplitudes.value = current.toFloatArray()
                    }
                }
            }
            rawRecordedBytes = outputStream.toByteArray()
            withContext(Dispatchers.Main) {
                onStop()
            }
        }
    }

    /**
     * Stops the active microphone recording.
     */
    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Plays the recorded audio with the selected voice effect applied.
     */
    fun playEffect(effect: VoiceEffect, coroutineScope: CoroutineScope) {
        if (rawRecordedBytes.isEmpty()) return

        stopPlayback()
        isPlaying.value = true

        playingJob = coroutineScope.launch(Dispatchers.IO) {
            // Apply DSP effect to raw PCM buffer
            val processedBytes = AudioDSPProcessor.process(rawRecordedBytes, effect)

            val outBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(outBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()

            var offset = 0
            val chunkSize = 4096
            while (offset < processedBytes.size && isPlaying.value) {
                val length = min(chunkSize, processedBytes.size - offset)
                audioTrack?.write(processedBytes, offset, length)
                offset += length
            }

            withContext(Dispatchers.Main) {
                isPlaying.value = false
            }
        }
    }

    /**
     * Stops current playback audio track.
     */
    fun stopPlayback() {
        isPlaying.value = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            playingJob?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Exports processed PCM audio data as a standard .WAV file into the device external files directory.
     */
    fun exportWavFile(effect: VoiceEffect): File? {
        if (rawRecordedBytes.isEmpty()) return null
        val processedPcm = AudioDSPProcessor.process(rawRecordedBytes, effect)
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "Voice_${effect.name.lowercase()}_${System.currentTimeMillis()}.wav"
        )

        try {
            FileOutputStream(file).use { out ->
                writeWavHeader(out, processedPcm.size, sampleRate, 1, 16)
                out.write(processedPcm)
            }
            return file
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }

    private fun writeWavHeader(
        out: OutputStream,
        pcmDataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = pcmDataSize + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)

        // RIFF chunk descriptor
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // 'fmt ' sub-chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // SubChunk1Size (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat 1 = PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // Block align
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0

        // 'data' sub-chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataSize and 0xff).toByte()
        header[41] = ((pcmDataSize shr 8) and 0xff).toByte()
        header[42] = ((pcmDataSize shr 16) and 0xff).toByte()
        header[43] = ((pcmDataSize shr 24) and 0xff).toByte()

        out.write(header, 0, 44)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0F172A))) {
                VoiceChangerScreen()
            }
        }
    }
}

@Composable
fun VoiceChangerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioManager = remember { AudioRecorderPlayer(context) }

    var isRecording by remember { mutableStateOf(false) }
    var selectedEffect by remember { mutableStateOf(VoiceEffect.ORIGINAL) }
    var hasRecordedAudio by remember { mutableStateOf(false) }
    var exportedFilePath by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1D))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "NEO VOX",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF38BDF8),
                    letterSpacing = 2.sp
                )
                Text(
                    text = "DSP Voice Morphing Studio",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
            }
            IconButton(
                onClick = {
                    if (hasRecordedAudio) {
                        val file = audioManager.exportWavFile(selectedEffect)
                        exportedFilePath = file?.absolutePath
                    }
                },
                enabled = hasRecordedAudio
            ) {
                Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = "Save Audio",
                    tint = if (hasRecordedAudio) Color(0xFF38BDF8) else Color(0xFF475569)
                )
            }
        }

        // Live Audio Visualizer Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(vertical = 8.dp)
                .shadow(12.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val amplitudes = audioManager.currentAmplitudes.value
                Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                    val barWidth = size.width / (amplitudes.size * 1.5f)
                    val centerY = size.height / 2f
                    amplitudes.forEachIndexed { index, amp ->
                        val barHeight = (amp * size.height * 0.8f).coerceAtLeast(6f)
                        val x = index * (barWidth * 1.5f)
                        drawLine(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF38BDF8), Color(0xFF818CF8))
                            ),
                            start = Offset(x, centerY - barHeight / 2),
                            end = Offset(x, centerY + barHeight / 2),
                            strokeWidth = barWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
                if (!isRecording && !audioManager.isPlaying.value) {
                    Text(
                        text = if (hasRecordedAudio) "Voice Clip Ready" else "Tap Mic to Record",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Recording & Action Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Main Record / Stop Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRecording) Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFB91C1C)))
                        else Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF2563EB)))
                    )
                    .clickable {
                        if (!hasPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@clickable
                        }
                        if (isRecording) {
                            audioManager.stopRecording()
                            isRecording = false
                            hasRecordedAudio = true
                        } else {
                            audioManager.stopPlayback()
                            hasRecordedAudio = false
                            isRecording = true
                            audioManager.startRecording(scope) {
                                isRecording = false
                                hasRecordedAudio = true
                            }
                        }
                    }
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Record",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.width(28.dp))

            // Play / Preview Morph Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (hasRecordedAudio) Color(0xFF334155) else Color(0xFF1E293B))
                    .clickable(enabled = hasRecordedAudio) {
                        if (audioManager.isPlaying.value) {
                            audioManager.stopPlayback()
                        } else {
                            audioManager.playEffect(selectedEffect, scope)
                        }
                    }
            ) {
                Icon(
                    imageVector = if (audioManager.isPlaying.value) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play Morph",
                    tint = if (hasRecordedAudio) Color(0xFF38BDF8) else Color(0xFF475569),
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        // Export notification banner
        AnimatedVisibility(visible = exportedFilePath != null) {
            exportedFilePath?.let { path ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF065F46),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "Saved: ...${path.takeLast(24)}",
                        color = Color(0xFFA7F3D0),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        // Voice Presets Grid Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = "VOICE PRESETS",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }

        // Voice Presets Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(VoiceEffect.values()) { effect ->
                val isSelected = selectedEffect == effect
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(95.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            selectedEffect = effect
                            if (hasRecordedAudio) {
                                audioManager.playEffect(effect, scope)
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF1E293B) else Color(0xFF0F172A)
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = effect.icon,
                                contentDescription = effect.title,
                                tint = if (isSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                modifier = Modifier.size(24.dp)
                            )
                            if (isSelected && audioManager.isPlaying.value) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFF38BDF8),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Column {
                            Text(
                                text = effect.title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = effect.description,
                                color = Color(0xFF64748B),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
