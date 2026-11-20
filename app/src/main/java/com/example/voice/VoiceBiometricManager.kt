package com.example.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

data class VoiceFingerprint(
    val pitchHz: Double,
    val zcr: Double,
    val bandRatios: List<Double>,
    val rmsStability: Double
) {
    fun toJson(): String {
        val bands = bandRatios.joinToString(",")
        return "$pitchHz|$zcr|$rmsStability|$bands"
    }

    companion object {
        fun fromJson(json: String): VoiceFingerprint? {
            return try {
                val parts = json.split("|")
                if (parts.size != 4) return null
                val pitchHz = parts[0].toDouble()
                val zcr = parts[1].toDouble()
                val rmsStability = parts[2].toDouble()
                val bands = parts[3].split(",").filter { it.isNotBlank() }.map { it.toDouble() }
                if (bands.isEmpty()) return null
                VoiceFingerprint(
                    pitchHz = pitchHz,
                    zcr = zcr,
                    bandRatios = bands,
                    rmsStability = rmsStability
                )
            } catch (_: Exception) {
                null
            }
        }

        fun average(samples: List<VoiceFingerprint>): VoiceFingerprint {
            require(samples.isNotEmpty())
            val avgPitch = samples.map { it.pitchHz }.average()
            val avgZcr = samples.map { it.zcr }.average()
            val avgStab = samples.map { it.rmsStability }.average()
            val bandCount = samples.first().bandRatios.size
            val avgBands = (0 until bandCount).map { idx ->
                samples.map { it.bandRatios.getOrElse(idx) { 0.0 } }.average()
            }
            return VoiceFingerprint(avgPitch, avgZcr, avgBands, avgStab)
        }
    }
}

data class VoiceCaptureResult(
    val fingerprint: VoiceFingerprint?,
    val hadVoiceActivity: Boolean,
    val errorMessage: String? = null
)

data class VoiceVerificationResult(
    val scorePercent: Int,
    val isMatch: Boolean,
    val detail: String
)

class VoiceBiometricManager(private val context: Context) {

    private val _liveAmplitude = MutableStateFlow(0f)
    val liveAmplitude: StateFlow<Float> = _liveAmplitude.asStateFlow()

    suspend fun captureVoiceSample(durationMs: Long = 2600L): VoiceCaptureResult =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext VoiceCaptureResult(
                    fingerprint = null,
                    hadVoiceActivity = false,
                    errorMessage = "Microphone permission is required for voice enrollment."
                )
            }

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuffer <= 0) {
                return@withContext VoiceCaptureResult(
                    fingerprint = null,
                    hadVoiceActivity = false,
                    errorMessage = "Audio hardware unavailable on this device."
                )
            }

            val bufferSize = maxOf(minBuffer, 4096)
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    return@withContext VoiceCaptureResult(
                        fingerprint = null,
                        hadVoiceActivity = false,
                        errorMessage = "Could not initialize microphone recorder."
                    )
                }

                val targetSamples = (sampleRate * durationMs / 1000L).toInt()
                val collected = ShortArray(targetSamples)
                val readBuf = ShortArray(1024)
                var offset = 0

                recorder.startRecording()
                while (offset < targetSamples) {
                    val toRead = minOf(readBuf.size, targetSamples - offset)
                    val readCount = recorder.read(readBuf, 0, toRead)
                    if (readCount > 0) {
                        System.arraycopy(readBuf, 0, collected, offset, readCount)
                        offset += readCount
                        var sumSq = 0.0
                        for (i in 0 until readCount) {
                            val norm = readBuf[i] / 32768.0
                            sumSq += norm * norm
                        }
                        val rms = sqrt(sumSq / readCount).toFloat()
                        _liveAmplitude.value = (rms * 5.5f).coerceIn(0f, 1f)
                    } else {
                        break
                    }
                }
                _liveAmplitude.value = 0f
                extractFingerprint(collected, offset, sampleRate)
            } catch (e: SecurityException) {
                Log.e(TAG, "Audio recording permission error", e)
                VoiceCaptureResult(null, false, "Microphone permission denied.")
            } catch (e: Exception) {
                Log.e(TAG, "Voice capture failed", e)
                VoiceCaptureResult(null, false, e.localizedMessage ?: "Voice capture failed.")
            } finally {
                _liveAmplitude.value = 0f
                try {
                    recorder?.stop()
                } catch (_: Exception) {
                }
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
            }
        }

    private fun extractFingerprint(samples: ShortArray, count: Int, sampleRate: Int): VoiceCaptureResult {
        if (count < sampleRate / 2) {
            return VoiceCaptureResult(null, false, "Sample too short. Please speak clearly.")
        }

        // Convert to normalized doubles and filter active speech frames
        val frameSize = 512
        val frameRmsList = mutableListOf<Double>()
        val activeFrames = mutableListOf<DoubleArray>()

        var idx = 0
        while (idx + frameSize <= count) {
            val frame = DoubleArray(frameSize)
            var sumSq = 0.0
            for (i in 0 until frameSize) {
                val v = samples[idx + i] / 32768.0
                frame[i] = v
                sumSq += v * v
            }
            val rms = sqrt(sumSq / frameSize)
            frameRmsList.add(rms)
            if (rms > 0.012) {
                activeFrames.add(frame)
            }
            idx += frameSize
        }

        if (activeFrames.size < 4) {
            return VoiceCaptureResult(
                fingerprint = null,
                hadVoiceActivity = false,
                errorMessage = "No clear voice detected. Please speak closer to the microphone."
            )
        }

        // Compute Zero Crossing Rate & Pitch across active frames
        val pitches = mutableListOf<Double>()
        var zcrSum = 0.0
        val bandsHz = listOf(160.0, 320.0, 640.0, 1280.0, 2400.0, 3800.0)
        val bandEnergySum = DoubleArray(bandsHz.size)

        for (frame in activeFrames) {
            var crossings = 0
            for (i in 1 until frame.size) {
                if ((frame[i] >= 0 && frame[i - 1] < 0) || (frame[i] < 0 && frame[i - 1] >= 0)) {
                    crossings++
                }
            }
            zcrSum += crossings.toDouble() / frame.size

            estimatePitchHz(frame, sampleRate)?.let { pitches.add(it) }

            bandsHz.forEachIndexed { bIdx, freq ->
                bandEnergySum[bIdx] += goertzelMagnitude(frame, sampleRate, freq)
            }
        }

        val totalBandEnergy = bandEnergySum.sum().coerceAtLeast(1e-6)
        val bandRatios = bandEnergySum.map { it / totalBandEnergy }
        val medianPitch = if (pitches.isNotEmpty()) {
            pitches.sorted()[pitches.size / 2]
        } else {
            155.0
        }
        val avgZcr = zcrSum / activeFrames.size
        val activeMean = activeFrames.map { frame ->
            sqrt(frame.sumOf { it * it } / frame.size)
        }.average()
        val stability = (1.0 - (activeMean * 0.5)).coerceIn(0.2, 0.99)

        return VoiceCaptureResult(
            fingerprint = VoiceFingerprint(
                pitchHz = medianPitch,
                zcr = avgZcr,
                bandRatios = bandRatios,
                rmsStability = stability
            ),
            hadVoiceActivity = true
        )
    }

    private fun estimatePitchHz(frame: DoubleArray, sampleRate: Int): Double? {
        val minLag = sampleRate / 380 // ~42 samples
        val maxLag = sampleRate / 75  // ~213 samples
        if (maxLag >= frame.size) return null

        var bestLag = -1
        var bestCorr = 0.0
        var zeroLag = 0.0
        for (i in frame.indices) zeroLag += frame[i] * frame[i]
        if (zeroLag <= 1e-6) return null

        for (lag in minLag..maxLag) {
            var corr = 0.0
            for (i in 0 until frame.size - lag) {
                corr += frame[i] * frame[i + lag]
            }
            val normalized = corr / zeroLag
            if (normalized > bestCorr) {
                bestCorr = normalized
                bestLag = lag
            }
        }
        return if (bestLag > 0 && bestCorr > 0.32) {
            sampleRate.toDouble() / bestLag
        } else {
            null
        }
    }

    private fun goertzelMagnitude(frame: DoubleArray, sampleRate: Int, targetFreq: Double): Double {
        val k = (0.5 + (frame.size * targetFreq) / sampleRate).toInt()
        val omega = (2.0 * PI * k) / frame.size
        val coeff = 2.0 * cos(omega)
        var q0: Double
        var q1 = 0.0
        var q2 = 0.0
        for (sample in frame) {
            q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        val power = q1 * q1 + q2 * q2 - q1 * q2 * coeff
        return ln(1.0 + sqrt(maxOf(0.0, power)))
    }

    fun verifyVoice(enrolled: VoiceFingerprint, probe: VoiceFingerprint): VoiceVerificationResult {
        val bandCosine = cosineSimilarity(enrolled.bandRatios, probe.bandRatios)
        val pitchDiffRatio = abs(enrolled.pitchHz - probe.pitchHz) / enrolled.pitchHz.coerceAtLeast(80.0)
        val pitchScore = (1.0 - pitchDiffRatio.pow(1.1) * 1.4).coerceIn(0.0, 1.0)
        val zcrDiff = abs(enrolled.zcr - probe.zcr)
        val zcrScore = (1.0 - zcrDiff * 4.0).coerceIn(0.0, 1.0)

        val weighted = (bandCosine * 0.55 + pitchScore * 0.30 + zcrScore * 0.15)
        val scorePercent = (weighted * 100.0).toInt().coerceIn(0, 99)
        val isMatch = scorePercent >= 72
        val detail = if (isMatch) {
            "Owner voice confirmed ($scorePercent% acoustic match, pitch ${probe.pitchHz.toInt()} Hz)"
        } else {
            "Low speaker match ($scorePercent%, pitch ${probe.pitchHz.toInt()} Hz vs ${enrolled.pitchHz.toInt()} Hz)"
        }
        return VoiceVerificationResult(scorePercent, isMatch, detail)
    }

    private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var dot = 0.0
        var magA = 0.0
        var magB = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        val denom = sqrt(magA) * sqrt(magB)
        return if (denom > 1e-9) (dot / denom).coerceIn(0.0, 1.0) else 0.0
    }

    companion object {
        private const val TAG = "VoiceBiometric"
    }
}
