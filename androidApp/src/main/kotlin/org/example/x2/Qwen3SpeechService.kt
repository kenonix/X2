package org.example.x2

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class Qwen3SpeechService(private val config: AppConfig) {

    data class AudioResult(val samples: ByteArray, val sampleRate: Int)

    /**
     * Qwen3-TTS-CLI를 사용하여 음성을 합성합니다.
     * Android 환경에 특화되어, SELinux 실행 권한 차단 우회를 위해
     * 바이너리를 앱 내부 실행 가능한 저장소(filesDir/bin)로 복사한 뒤 실행합니다.
     */
    fun synthesize(text: String, context: Context, speed: Float = 1.0f, pitch: Float = 1.0f): AudioResult? {
        if (config.qwen3BinaryPath.isBlank() || config.qwen3ModelDir.isBlank()) {
            AppLogManager.log("[Qwen3] 실행 파일 또는 모델 경로가 비어 있습니다.")
            return null
        }

        val originalBinaryFile = File(config.qwen3BinaryPath)
        if (!originalBinaryFile.exists()) {
            AppLogManager.log("[Qwen3] 실행 파일이 지정된 경로에 존재하지 않습니다: ${config.qwen3BinaryPath}")
            return null
        }

        // Android 10+ 우회: 외부 저장소에서 실행이 제한되므로 내부 저장소로 복사
        val executablePath = getExecutableBinaryPath(context, originalBinaryFile)
        if (executablePath.isBlank()) {
            AppLogManager.log("[Qwen3] 실행 가능 바이너리 준비 실패")
            return null
        }

        val outputWav = File.createTempFile("qwen3_out_", ".wav", context.cacheDir)
        
        try {
            val command = mutableListOf<String>()
            command.add(executablePath)
            command.add("--model")
            command.add(config.qwen3ModelDir)
            command.add("--text")
            command.add(text)
            command.add("--output")
            command.add(outputWav.absolutePath)

            // 참조 오디오 (목소리 복제) 설정
            if (config.qwen3ReferenceAudioPath.isNotBlank()) {
                val refFile = File(config.qwen3ReferenceAudioPath)
                if (refFile.exists()) {
                    command.add("--reference")
                    command.add(refFile.absolutePath)
                    AppLogManager.log("[Qwen3] 목소리 복제용 참조 오디오 적용: ${refFile.name}")
                }
            }

            // GPU 가속 설정 (-ngl)
            if (config.qwen3GpuLayers > 0) {
                command.add("-ngl")
                command.add(config.qwen3GpuLayers.toString())
            }

            // 고급 튜닝 설정 추가 지원
            if (config.qwen3Temperature != 0.8f) {
                command.add("--temp")
                command.add(config.qwen3Temperature.toString())
            }
            if (config.qwen3TopP != 0.9f) {
                command.add("--top-p")
                command.add(config.qwen3TopP.toString())
            }
            if (config.qwen3TopK != 40) {
                command.add("--top-k")
                command.add(config.qwen3TopK.toString())
            }

            // 추가 사용자 지정 CLI 인수
            if (config.qwen3CustomArgs.isNotBlank()) {
                val customArgsList = config.qwen3CustomArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
                command.addAll(customArgsList)
            }

            AppLogManager.log("[Qwen3] CLI 실행 명령어: ${command.joinToString(" ")}")
            
            val process = ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            
            // 디버그 출력 파이프라인
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line -> AppLogManager.log("[Qwen3-Log] $line") }
            }
            
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                AppLogManager.log("[Qwen3] CLI 프로세스 실행 시간 초과 (60초)")
                return null
            }

            if (process.exitValue() != 0) {
                AppLogManager.log("[Qwen3] CLI 프로세스 종료 코드 에러: ${process.exitValue()}")
                return null
            }

            if (outputWav.exists() && outputWav.length() > 44) {
                val bytes = outputWav.readBytes()
                val pcmData = bytes.sliceArray(44 until bytes.size)
                
                // WAV 헤더의 오프셋 24~27에서 샘플 레이트 파싱
                val sampleRate = ((bytes[27].toInt() and 0xFF) shl 24) or
                                 ((bytes[26].toInt() and 0xFF) shl 16) or
                                 ((bytes[25].toInt() and 0xFF) shl 8) or
                                 (bytes[24].toInt() and 0xFF)
                
                AppLogManager.log("[Qwen3] 음성 합성 성공 (샘플 레이트: $sampleRate, 데이터 크기: ${pcmData.size} bytes)")
                return AudioResult(pcmData, sampleRate)
            }

        } catch (e: Exception) {
            AppLogManager.logException("[Qwen3] 음성 합성 중 예외 발생", e)
        } finally {
            try {
                if (outputWav.exists()) {
                    outputWav.delete()
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        return null
    }

    /**
     * Android W^X 차단 정책을 우회하기 위해 바이너리를 앱 내부 전용 폴더로 안전하게 복사하고 실행 권한을 적용합니다.
     */
    private fun getExecutableBinaryPath(context: Context, srcFile: File): String {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
        val destFile = File(binDir, srcFile.name)
        
        // 용량 및 수정 시간 비교 후 변경사항이 있거나 파일이 없으면 복사
        if (!destFile.exists() || destFile.length() != srcFile.length() || destFile.lastModified() != srcFile.lastModified()) {
            try {
                AppLogManager.log("[Qwen3] CLI 실행 파일을 안전한 내부 영역으로 복사 중: ${destFile.name}")
                srcFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.setLastModified(srcFile.lastModified())
                destFile.setExecutable(true, false)
            } catch (e: Exception) {
                AppLogManager.log("[Qwen3] CLI 실행 파일 복사 실패: ${e.message}")
                return ""
            }
        } else {
            destFile.setExecutable(true, false)
        }
        return destFile.absolutePath
    }
}
