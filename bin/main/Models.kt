import java.util.UUID

data class AudioResult(val data: ByteArray, val sampleRate: Int)

data class TTSHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val engine: String,
    val speed: Float,
    val pitch: Float,
    var audioPath: String? = null // Generated/Saved WAV file path
)

data class AppConfig(
    var theme: String = "Dark",
    var ttsEngine: String = "Sherpa", // "Sherpa" or "Qwen3"

    // Sherpa-ONNX Settings
    var ttsModelPath: String = "",
    var ttsLexiconPath: String = "",
    var ttsTokensPath: String = "",

    // Qwen3-TTS Settings
    var qwen3BinaryPath: String = "",
    var qwen3ModelDir: String = "",
    var ttsReferenceAudioPath: String = "",

    // TTS Parameters
    var speed: Float = 1.0f,
    var pitch: Float = 1.0f,
    var steps: Int = 8,
    var temperature: Float = 0.8f,
    var topP: Float = 0.9f,
    var topK: Int = 40,
    var gpuLayers: Int = 99,
    var customArgs: String = "",

    // History
    val history: MutableList<TTSHistoryItem> = mutableListOf()
)
