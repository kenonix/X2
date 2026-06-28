package org.example.x2

import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisRequest
import android.speech.tts.SynthesisCallback
import android.media.AudioFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MyTTSService : TextToSpeechService() {

    private var currentLanguage = arrayOf("ko", "KOR", "")
    @Volatile
    private var isStopRequested = false

    override fun onCreate() {
        super.onCreate()
        AppLogManager.log("시스템 TTS 서비스가 생성되었습니다.")
    }

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int {
        // 지원 가능한 언어 범위 지정
        return if (lang.equals("ko", ignoreCase = true) || lang.equals("kor", ignoreCase = true) ||
            lang.equals("en", ignoreCase = true) || lang.equals("ja", ignoreCase = true)
        ) {
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        return currentLanguage
    }

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int {
        currentLanguage = arrayOf(lang, country, variant)
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        isStopRequested = true
        AppLogManager.log("시스템 TTS 서비스가 정지되었습니다.")
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        isStopRequested = false
        val text = request.charSequenceText.toString()
        val lang = request.language ?: "ko"
        
        // 시스템이 요청한 속도(speechRate) 및 피치(pitch). 기본값은 100 (1.0x)
        val requestSpeed = request.speechRate / 100.0f
        val requestPitch = request.pitch / 100.0f

        AppLogManager.log("시스템 TTS 합성 요청 접수: \"$text\", 언어: $lang, 속도: $requestSpeed, 피치: $requestPitch")

        val config = PersistenceManager.load(applicationContext)

        when (config.ttsEngine) {
            "Supertonic" -> {
                try {
                    val onnxDir = config.supertonicOnnxDir
                    val voiceStylePath = config.supertonicVoiceStylePath
                    
                    if (onnxDir.isBlank() || voiceStylePath.isBlank()) {
                        AppLogManager.log("시스템 TTS 오류: Supertonic 모델 폴더 또는 목소리 스타일 경로가 비어 있습니다.")
                        callback.error(TextToSpeech.ERROR_SYNTHESIS)
                        return
                    }

                    // GPU 가속 설정에 맞춰 엔진 호출
                    val useGpu = config.backendType.uppercase() == "GPU"
                    val ttsEngine = SupertonicTTSManager.getOrCreate(onnxDir, useGpu)

                    // Supertonic의 자체 스피드 보정 계수 및 피치 보정
                    // 피치 조절로 인한 속도 변화를 상쇄하기 위해 speed 파라미터를 보정함
                    val targetSpeed = (requestSpeed / requestPitch).coerceIn(0.5f, 2.0f)
                    
                    callback.start(ttsEngine.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)

                    ttsEngine.speakStream(
                        text = text,
                        voiceStylePath = voiceStylePath,
                        lang = lang,
                        speed = targetSpeed,
                        steps = config.supertonicSteps,
                        checkCancel = { isStopRequested }
                    ) { chunkData ->
                        // FloatArray [-1.0, 1.0] -> 16-bit PCM Bytearray 변환
                        val pcmBytes = ByteArray(chunkData.size * 2)
                        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (sample in chunkData) {
                            val s = (sample * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()
                            buffer.putShort(s)
                        }

                        // 피치 조절을 위해 선형 보간 리샘플링 적용
                        val finalPcm = if (requestPitch != 1.0f && requestPitch > 0.1f) {
                            resamplePcm(pcmBytes, requestPitch)
                        } else {
                            pcmBytes
                        }

                        writeAudioInChunks(callback, finalPcm)
                    }

                    callback.done()
                    AppLogManager.log("시스템 TTS (Supertonic) 합성 및 전송 성공")
                } catch (e: java.util.concurrent.CancellationException) {
                    AppLogManager.log("시스템 TTS (Supertonic) 합성 취소됨")
                    callback.error()
                } catch (e: Exception) {
                    AppLogManager.logException("시스템 TTS (Supertonic) 합성 실패", e)
                    callback.error()
                }
            }
            "Qwen3" -> {
                try {
                    val qwen3Service = Qwen3SpeechService(config)
                    val result = qwen3Service.synthesize(text, applicationContext, requestSpeed, requestPitch)
                    
                    if (result != null) {
                        // Qwen3 바이너리 합성물에 대한 피치 및 속도 리샘플링 적용
                        val finalPcm = if (requestPitch != 1.0f || requestSpeed != 1.0f) {
                            resamplePcm(result.samples, requestSpeed * requestPitch)
                        } else {
                            result.samples
                        }

                        callback.start(result.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                        writeAudioInChunks(callback, finalPcm)
                        callback.done()
                        AppLogManager.log("시스템 TTS (Qwen3) 합성 및 전송 성공")
                    } else {
                        callback.error()
                    }
                } catch (e: Exception) {
                    AppLogManager.logException("시스템 TTS (Qwen3) 합성 실패", e)
                    callback.error()
                }
            }
            else -> {
                AppLogManager.log("시스템 TTS 오류: 지원하지 않거나 설정되지 않은 TTS 엔진 유형: ${config.ttsEngine}")
                callback.error()
            }
        }
    }

    /**
     * 16비트 PCM 바이트 배열에 대해 선형 보간 리샘플링을 적용하여 피치/속도를 변경합니다.
     * speedRatio > 1.0 이면 재생시간이 단축되고 피치/속도가 올라가며, < 1.0 이면 늘어나고 내려갑니다.
     */
    private fun resamplePcm(pcmBytes: ByteArray, speedRatio: Float): ByteArray {
        if (speedRatio == 1.0f || speedRatio <= 0.1f) return pcmBytes
        
        val numShorts = pcmBytes.size / 2
        val inputBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val input = ShortArray(numShorts)
        inputBuffer.get(input)
        
        val outputLength = (numShorts / speedRatio).toInt()
        if (outputLength <= 0) return pcmBytes
        val output = ShortArray(outputLength)
        
        for (i in 0 until outputLength) {
            val srcIndex = i * speedRatio
            val index = srcIndex.toInt()
            val frac = srcIndex - index
            
            if (index >= numShorts - 1) {
                output[i] = input[numShorts - 1]
            } else {
                val s0 = input[index].toFloat()
                val s1 = input[index + 1].toFloat()
                output[i] = (s0 + frac * (s1 - s0)).toInt().toShort()
            }
        }
        
        val outputBytes = ByteArray(outputLength * 2)
        ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return outputBytes
    }

    /**
     * SynthesisCallback의 getMaxBufferSize() 크기에 맞춰 PCM 데이터를 청크 단위로 분할 전송합니다.
     * 버퍼가 너무 크면 PlaybackSynthesisCallback에서 IllegalArgumentException이 발생합니다.
     */
    private fun writeAudioInChunks(callback: SynthesisCallback, pcmData: ByteArray) {
        val maxBufferSize = try {
            callback.maxBufferSize
        } catch (e: Exception) {
            16384
        }.coerceAtLeast(4096)

        var bytesWritten = 0
        while (bytesWritten < pcmData.size) {
            val bytesToWrite = Math.min(pcmData.size - bytesWritten, maxBufferSize)
            val result = callback.audioAvailable(pcmData, bytesWritten, bytesToWrite)
            if (result != TextToSpeech.SUCCESS) {
                AppLogManager.log("시스템 TTS 전송 중단 또는 에러 발생 (결과 코드: $result)")
                break
            }
            bytesWritten += bytesToWrite
        }
    }
}
