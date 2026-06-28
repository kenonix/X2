import java.io.File
import java.util.concurrent.TimeUnit

class Qwen3SpeechService(private val config: AppConfig) {

    /**
     * Qwen3-TTS-CLI를 사용하여 음성을 합성합니다.
     * GGUF 모델과 실행 파일을 호출합니다.
     */
    fun synthesize(text: String): AudioResult? {
        if (config.qwen3BinaryPath.isBlank() || config.qwen3ModelDir.isBlank()) {
            println("[Qwen3] Binary or Model path is empty")
            return null
        }

        val binaryFile = File(config.qwen3BinaryPath)
        if (!binaryFile.exists()) {
            println("[Qwen3] Binary not found at ${config.qwen3BinaryPath}")
            return null
        }

        val outputWav = File.createTempFile("qwen3_out_", ".wav")
        
        try {
            val command = mutableListOf<String>()
            command.add(binaryFile.absolutePath)
            command.add("--model")
            command.add(config.qwen3ModelDir)
            command.add("--text")
            command.add(text)
            command.add("--output")
            command.add(outputWav.absolutePath)

            command.add("--speed")
            command.add(config.speed.toString())
            command.add("--pitch")
            command.add(config.pitch.toString())
            command.add("--steps")
            command.add(config.steps.toString())
            command.add("--temperature")
            command.add(config.temperature.toString())
            command.add("--top-p")
            command.add(config.topP.toString())
            command.add("--top-k")
            command.add(config.topK.toString())
            
            if (config.gpuLayers > 0) {
                command.add("-ngl")
                command.add(config.gpuLayers.toString())
            }

            // 참조 오디오가 설정되어 있으면 목소리 복제(Cloning) 모드로 실행
            if (config.ttsReferenceAudioPath.isNotBlank()) {
                val refFile = File(config.ttsReferenceAudioPath)
                if (refFile.exists()) {
                    command.add("--reference")
                    command.add(refFile.absolutePath)
                    println("[Qwen3] Using reference audio for cloning: ${refFile.name}")
                }
            }

            if (config.customArgs.isNotBlank()) {
                command.addAll(config.customArgs.trim().split(Regex("\\s+")))
            }

            println("[Qwen3] Executing: ${command.joinToString(" ")}")
            
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            
            // 로그 확인 (디버깅용)
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line -> println("[Qwen3-Log] $line") }
            }
            
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                println("[Qwen3] Process timed out")
                return null
            }

            if (process.exitValue() != 0) {
                println("[Qwen3] Process failed with exit code ${process.exitValue()}")
                return null
            }

            if (outputWav.exists() && outputWav.length() > 44) {
                // WAV 파일 로드
                val bytes = outputWav.readBytes()
                val pcmData = bytes.sliceArray(44 until bytes.size)
                
                // WAV 파일의 샘플 레이트 읽기 (offset 24~27)
                val sampleRate = ((bytes[27].toInt() and 0xFF) shl 24) or
                                 ((bytes[26].toInt() and 0xFF) shl 16) or
                                 ((bytes[25].toInt() and 0xFF) shl 8) or
                                 (bytes[24].toInt() and 0xFF)
                
                println("[Qwen3] Success! Sample rate: $sampleRate, size: ${pcmData.size}")
                return AudioResult(pcmData, sampleRate)
            }

        } catch (e: Exception) {
            println("[Qwen3] Error: ${e.message}")
            e.printStackTrace()
        } finally {
            // outputWav.delete()
        }

        return null
    }
}
