package org.example.x2

import java.util.UUID

data class TTSHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val engine: String,
    val speed: Float,
    val pitch: Float,
    var audioPath: String? = null // Saved audio cache file path
)

data class AppConfig(
    var theme: String = "Dark",
    var backendType: String = "CPU", // CPU or GPU
    var ttsEngine: String = "System", // System, Supertonic, Qwen3
    var ttsEnginePackage: String = "", // Custom TTS engine package for system-based
    var ttsVoiceConfigJson: String = "",

    // Supertonic ONNX Settings
    var supertonicOnnxDir: String = "",
    var supertonicVoiceStylePath: String = "",
    var supertonicSteps: Int = 8,
    var supertonicSpeed: Float = 1.0f,
    var supertonicPitch: Float = 1.0f,

    // Qwen3-TTS Settings
    var qwen3BinaryPath: String = "",
    var qwen3ModelDir: String = "",
    var qwen3ReferenceAudioPath: String = "",
    var qwen3GpuLayers: Int = 99,
    var qwen3Speed: Float = 1.0f,
    var qwen3Pitch: Float = 1.0f,
    var qwen3Temperature: Float = 0.8f,
    var qwen3TopP: Float = 0.9f,
    var qwen3TopK: Int = 40,
    var qwen3CustomArgs: String = "",

    // TTS History
    val history: MutableList<TTSHistoryItem> = mutableListOf()
)
