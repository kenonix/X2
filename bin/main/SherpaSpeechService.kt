import com.k2fsa.sherpa.onnx.*
import java.io.File

class SherpaSpeechService(private val config: AppConfig) {
    private var tts: OfflineTts? = null

    private fun initTts() {
        if (tts != null || config.ttsModelPath.isBlank()) return
        
        try {
            val vitsConfig = OfflineTtsVitsModelConfig.builder()
                .setModel(config.ttsModelPath)
                .setLexicon(config.ttsLexiconPath)
                .setTokens(config.ttsTokensPath)
                .build()

            val modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vitsConfig)
                .setNumThreads(4)
                .setDebug(true)
                .build()

            val ttsConfig = OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .build()

            tts = OfflineTts(ttsConfig)
            println("TTS Initialized: ${config.ttsModelPath}")
        } catch (e: Exception) {
            println("TTS Init Error: ${e.message}")
        }
    }

    fun synthesize(text: String): AudioResult? {
        initTts()
        val engine = tts ?: return null
        
        return try {
            val audio = engine.generate(text, 0, config.speed)
            val samples = audio.samples
            val sampleRate = audio.sampleRate
            
            val buffer = java.nio.ByteBuffer.allocate(samples.size * 2)
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val s = (sample * 32767).toInt().coerceIn(-32768, 32767)
                buffer.putShort(s.toShort())
            }
            AudioResult(buffer.array(), sampleRate)
        } catch (e: Exception) {
            println("TTS Synthesis Error: ${e.message}")
            null
        }
    }
}
