package org.example.x2

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.ArrayList
import java.util.HashMap
import java.util.Random
import java.util.regex.Pattern

// --- 데이터 클래스 정의 ---

data class AEConfig(
    val sample_rate: Int,
    val base_chunk_size: Int
)

data class TTLConfig(
    val chunk_compress_factor: Int,
    val latent_dim: Int
)

data class SupertonicConfig(
    val ae: AEConfig,
    val ttl: TTLConfig
)

data class StyleTensorData(
    val dims: List<Long>,
    val data: List<List<List<Float>>>
)

data class VoiceStyleJson(
    val style_ttl: StyleTensorData,
    val style_dp: StyleTensorData
)

class Style(val ttlTensor: OnnxTensor, val dpTensor: OnnxTensor) {
    fun close() {
        ttlTensor.close()
        dpTensor.close()
    }
}

class TTSResult(val wav: FloatArray, val duration: FloatArray)

class NoisyLatentResult(val noisyLatent: Array<Array<FloatArray>>, val latentMask: Array<Array<FloatArray>>)

// --- Languages 객체 ---

object Languages {
    val AVAILABLE = listOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr",
        "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na"
    )

    fun normalize(lang: String): String {
        val lower = lang.trim().lowercase()
        return when (lower) {
            "kor" -> "ko"
            "eng" -> "en"
            "jpn" -> "ja"
            "ara" -> "ar"
            "bul" -> "bg"
            "ces" -> "cs"
            "dan" -> "da"
            "deu", "ger" -> "de"
            "ell", "gre" -> "el"
            "spa" -> "es"
            "est" -> "et"
            "fin" -> "fi"
            "fra", "fre" -> "fr"
            "hin" -> "hi"
            "hrv" -> "hr"
            "hun" -> "hu"
            "ind" -> "id"
            "ita" -> "it"
            "lit" -> "lt"
            "lav" -> "lv"
            "nld", "dut" -> "nl"
            "pol" -> "pl"
            "por" -> "pt"
            "ron", "rum" -> "ro"
            "rus" -> "ru"
            "slk", "slo" -> "sk"
            "slv" -> "sl"
            "swe" -> "sv"
            "tur" -> "tr"
            "ukr" -> "uk"
            "vie" -> "vi"
            else -> {
                if (AVAILABLE.contains(lower)) {
                    lower
                } else if (lower.length > 2 && AVAILABLE.contains(lower.substring(0, 2))) {
                    lower.substring(0, 2)
                } else {
                    lower
                }
            }
        }
    }

    fun isValid(lang: String): Boolean = AVAILABLE.contains(normalize(lang))
}

// --- UnicodeProcessor ---

class UnicodeProcessor(unicodeIndexerJsonPath: String) {
    private val indexer: LongArray

    init {
        val file = File(unicodeIndexerJsonPath)
        if (!file.exists()) {
            throw FileNotFoundException("unicode_indexer.json not found at: $unicodeIndexerJsonPath")
        }
        val gson = Gson()
        indexer = gson.fromJson(file.readText(), LongArray::class.java)
    }

    private fun removeEmojis(text: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < text.length) {
            val codePoint = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                val cp = Character.codePointAt(text, i)
                i++ // Skip low surrogate
                cp
            } else {
                text[i].code
            }

            // Check if code point is in emoji ranges
            val isEmoji = (codePoint in 0x1F600..0x1F64F) ||
                    (codePoint in 0x1F300..0x1F5FF) ||
                    (codePoint in 0x1F680..0x1F6FF) ||
                    (codePoint in 0x1F700..0x1F77F) ||
                    (codePoint in 0x1F780..0x1F7FF) ||
                    (codePoint in 0x1F800..0x1F8FF) ||
                    (codePoint in 0x1F900..0x1F9FF) ||
                    (codePoint in 0x1FA00..0x1FA6F) ||
                    (codePoint in 0x1FA70..0x1FAFF) ||
                    (codePoint in 0x2600..0x26FF) ||
                    (codePoint in 0x2700..0x27BF) ||
                    (codePoint in 0x1F1E6..0x1F1FF)

            if (!isEmoji) {
                if (codePoint > 0xFFFF) {
                    result.append(Character.toChars(codePoint))
                } else {
                    result.append(codePoint.toChar())
                }
            }
            i++
        }
        return result.toString()
    }

    fun call(textList: List<String>, langList: List<String>): TextProcessResult {
        val processedTexts = ArrayList<String>()
        for (i in textList.indices) {
            processedTexts.add(preprocessText(textList[i], langList[i]))
        }

        val allUnicodeVals = ArrayList<IntArray>()
        for (text in processedTexts) {
            allUnicodeVals.add(textToUnicodeValues(text))
        }

        val textIdsLengths = IntArray(processedTexts.size)
        var maxLen = 0
        for (i in allUnicodeVals.indices) {
            textIdsLengths[i] = allUnicodeVals[i].size
            maxLen = Math.max(maxLen, textIdsLengths[i])
        }

        val textIds = Array(processedTexts.size) { LongArray(maxLen) }
        for (i in allUnicodeVals.indices) {
            val unicodeVals = allUnicodeVals[i]
            for (j in unicodeVals.indices) {
                textIds[i][j] = indexer[unicodeVals[j]]
            }
        }

        val textMask = getTextMask(textIdsLengths)
        return TextProcessResult(textIds, textMask)
    }

    private fun preprocessText(text: String, lang: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        t = removeEmojis(t)

        val replacements = mapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ",
            "\u201C" to "\"", "\u201D" to "\"", "\u2018" to "'", "\u2019" to "'",
            "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ",
            "/" to " ", "#" to " ", "→" to " ", "←" to " "
        )

        for ((key, value) in replacements) {
            t = t.replace(key, value)
        }

        t = t.replace(Regex("[♥☆♡©\\\\]"), "")

        val exprReplacements = mapOf(
            "@" to " at ",
            "e.g.," to "for example, ",
            "i.e.," to "that is, "
        )

        for ((key, value) in exprReplacements) {
            t = t.replace(key, value)
        }

        t = t.replace(" ,", ",")
        t = t.replace(Regex(" \\."), ".")
        t = t.replace(" !", "!")
        t = t.replace(Regex(" \\?"), "?")
        t = t.replace(" ;", ";")
        t = t.replace(" :", ":")
        t = t.replace(" '", "'")

        while (t.contains("\"\"")) {
            t = t.replace("\"\"", "\"")
        }
        while (t.contains("''")) {
            t = t.replace("''", "'")
        }
        while (t.contains("``")) {
            t = t.replace("``", "`")
        }

        t = t.replace(Regex("\\s+"), " ").trim()

        if (!t.matches(Regex(".*[.!?;:,'\"\\u201C\\u201D\\u2018\\u2019)\\]}…。」』】〉》›»]$"))) {
            t += "."
        }

        val normalizedLang = Languages.normalize(lang)
        if (!Languages.AVAILABLE.contains(normalizedLang)) {
            throw IllegalArgumentException("Invalid language: $lang. Available: ${Languages.AVAILABLE}")
        }

        return "<$normalizedLang>$t</$normalizedLang>"
    }

    private fun textToUnicodeValues(text: String): IntArray {
        return text.codePoints().toArray()
    }

    private fun getTextMask(lengths: IntArray): Array<Array<FloatArray>> {
        val bsz = lengths.size
        var maxLen = 0
        for (len in lengths) {
            maxLen = Math.max(maxLen, len)
        }

        val mask = Array(bsz) { Array(1) { FloatArray(maxLen) } }
        for (i in 0 until bsz) {
            for (j in 0 until maxLen) {
                mask[i][0][j] = if (j < lengths[i]) 1.0f else 0.0f
            }
        }
        return mask
    }

    class TextProcessResult(val textIds: Array<LongArray>, val textMask: Array<Array<FloatArray>>)
}

// --- SupertonicTTS ---

class SupertonicTTS(onnxDir: String, val useGpu: Boolean = false) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val config: SupertonicConfig
    private val textProcessor: UnicodeProcessor

    private val dpSession: OrtSession
    private val textEncSession: OrtSession
    private val vectorEstSession: OrtSession
    private val vocoderSession: OrtSession

    val sampleRate: Int
    private val baseChunkSize: Int
    private val chunkCompress: Int
    private val ldim: Int

    init {
        val gson = Gson()
        val ttsJsonFile = File(onnxDir, "tts.json")
        if (!ttsJsonFile.exists()) {
            throw FileNotFoundException("tts.json not found at: ${ttsJsonFile.absolutePath}")
        }
        config = gson.fromJson(ttsJsonFile.readText(), SupertonicConfig::class.java)

        val unicodeIndexerPath = File(onnxDir, "unicode_indexer.json").absolutePath
        textProcessor = UnicodeProcessor(unicodeIndexerPath)

        sampleRate = config.ae.sample_rate
        baseChunkSize = config.ae.base_chunk_size
        chunkCompress = config.ttl.chunk_compress_factor
        ldim = config.ttl.latent_dim

        val opts = OrtSession.SessionOptions()

        val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        opts.setIntraOpNumThreads(numCores)
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        AppLogManager.log("Supertonic ONNX 설정 - CPU IntraOp Threads: $numCores")

        if (useGpu) {
            try {
                opts.addNnapi()
                AppLogManager.log("Supertonic ONNX: NNAPI (GPU/NPU) 가속 활성화 설정 성공")
            } catch (e: Exception) {
                AppLogManager.log("Supertonic ONNX: NNAPI 가속 설정 실패: ${e.message}")
            }
        }

        dpSession = env.createSession(File(onnxDir, "duration_predictor.onnx").absolutePath, opts)
        textEncSession = env.createSession(File(onnxDir, "text_encoder.onnx").absolutePath, opts)
        vectorEstSession = env.createSession(File(onnxDir, "vector_estimator.onnx").absolutePath, opts)
        vocoderSession = env.createSession(File(onnxDir, "vocoder.onnx").absolutePath, opts)
    }

    private fun _infer(
        textList: List<String>,
        langList: List<String>,
        style: Style,
        totalStep: Int,
        speed: Float,
        checkCancel: (() -> Boolean)? = null
    ): TTSResult {
        val bsz = textList.size

        if (checkCancel?.invoke() == true) {
            throw java.util.concurrent.CancellationException("TTS synthesis canceled.")
        }

        val textResult = textProcessor.call(textList, langList)
        val textIdsTensor = createLongTensor(textResult.textIds, env)
        val textMaskTensor = createFloatTensor(textResult.textMask, env)

        var dpResult: OrtSession.Result? = null
        var textEncResult: OrtSession.Result? = null
        var totalStepTensor: OnnxTensor? = null
        var finalLatentTensor: OnnxTensor? = null
        var vocoderResult: OrtSession.Result? = null

        try {
            if (checkCancel?.invoke() == true) {
                throw java.util.concurrent.CancellationException("TTS synthesis canceled.")
            }

            val dpInputs = HashMap<String, OnnxTensor>()
            dpInputs["text_ids"] = textIdsTensor
            dpInputs["style_dp"] = style.dpTensor
            dpInputs["text_mask"] = textMaskTensor

            dpResult = dpSession.run(dpInputs)
            val dpValue = dpResult.get(0).value
            val duration: FloatArray = when (dpValue) {
                is Array<*> -> {
                    if (dpValue.isArrayOf<FloatArray>()) {
                        (dpValue as Array<FloatArray>)[0]
                    } else {
                        throw RuntimeException("Unexpected inner array type in duration")
                    }
                }
                is FloatArray -> dpValue
                else -> throw RuntimeException("Unexpected duration type: ${dpValue?.javaClass?.name}")
            }

            for (i in duration.indices) {
                duration[i] /= speed
            }

            val textEncInputs = HashMap<String, OnnxTensor>()
            textEncInputs["text_ids"] = textIdsTensor
            textEncInputs["style_ttl"] = style.ttlTensor
            textEncInputs["text_mask"] = textMaskTensor

            textEncResult = textEncSession.run(textEncInputs)
            val textEmbTensor = textEncResult.get(0) as OnnxTensor

            val noisyLatentResult = sampleNoisyLatent(duration)
            var xt = noisyLatentResult.noisyLatent
            val latentMask = noisyLatentResult.latentMask

            val totalStepArray = FloatArray(bsz) { totalStep.toFloat() }
            totalStepTensor = OnnxTensor.createTensor(env, totalStepArray)

            val latentMaskTensor = createFloatTensor(latentMask, env)
            val textMaskTensor2 = createFloatTensor(textResult.textMask, env)
            val currentStepTensors = Array(totalStep) { step ->
                OnnxTensor.createTensor(env, FloatArray(bsz) { step.toFloat() })
            }

            try {
                for (step in 0 until totalStep) {
                    if (checkCancel?.invoke() == true) {
                        throw java.util.concurrent.CancellationException("TTS synthesis canceled.")
                    }
                    val currentStepTensor = currentStepTensors[step]
                    val noisyLatentTensor = createFloatTensor(xt, env)

                    try {
                        val vectorEstInputs = HashMap<String, OnnxTensor>()
                        vectorEstInputs["noisy_latent"] = noisyLatentTensor
                        vectorEstInputs["text_emb"] = textEmbTensor
                        vectorEstInputs["style_ttl"] = style.ttlTensor
                        vectorEstInputs["latent_mask"] = latentMaskTensor
                        vectorEstInputs["text_mask"] = textMaskTensor2
                        vectorEstInputs["current_step"] = currentStepTensor
                        vectorEstInputs["total_step"] = totalStepTensor

                        val vectorEstResult = vectorEstSession.run(vectorEstInputs)
                        try {
                            val denoised = vectorEstResult.get(0).value as Array<Array<FloatArray>>
                            xt = denoised
                        } finally {
                            vectorEstResult.close()
                        }
                    } finally {
                        noisyLatentTensor.close()
                    }
                }
            } finally {
                latentMaskTensor.close()
                textMaskTensor2.close()
                for (tensor in currentStepTensors) {
                    tensor.close()
                }
            }

            if (checkCancel?.invoke() == true) {
                throw java.util.concurrent.CancellationException("TTS synthesis canceled.")
            }

            finalLatentTensor = createFloatTensor(xt, env)
            val vocoderInputs = HashMap<String, OnnxTensor>()
            vocoderInputs["latent"] = finalLatentTensor

            vocoderResult = vocoderSession.run(vocoderInputs)
            val wavBatch = vocoderResult.get(0).value as Array<FloatArray>

            var totalSamples = 0
            for (w in wavBatch) {
                totalSamples += w.size
            }
            val wav = FloatArray(totalSamples)
            var offset = 0
            for (w in wavBatch) {
                System.arraycopy(w, 0, wav, offset, w.size)
                offset += w.size
            }

            return TTSResult(wav, duration)
        } finally {
            textIdsTensor.close()
            textMaskTensor.close()
            dpResult?.close()
            textEncResult?.close()
            totalStepTensor?.close()
            finalLatentTensor?.close()
            vocoderResult?.close()
        }
    }

    private fun sampleNoisyLatent(duration: FloatArray): NoisyLatentResult {
        val bsz = duration.size
        var maxDur = 0.0f
        for (d in duration) {
            maxDur = Math.max(maxDur, d)
        }

        val wavLenMax = (maxDur * sampleRate).toLong()
        val wavLengths = LongArray(bsz)
        for (i in 0 until bsz) {
            wavLengths[i] = (duration[i] * sampleRate).toLong()
        }

        val chunkSize = baseChunkSize * chunkCompress
        val latentLen = ((wavLenMax + chunkSize - 1) / chunkSize).toInt()
        val latentDim = ldim * chunkCompress

        val rng = Random()
        val noisyLatent = Array(bsz) { Array(latentDim) { FloatArray(latentLen) } }
        for (b in 0 until bsz) {
            for (d in 0 until latentDim) {
                for (t in 0 until latentLen) {
                    val u1 = Math.max(1e-10, rng.nextDouble())
                    val u2 = rng.nextDouble()
                    noisyLatent[b][d][t] = (Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2)).toFloat()
                }
            }
        }

        val latentMask = getLatentMask(wavLengths)

        for (b in 0 until bsz) {
            for (d in 0 until latentDim) {
                for (t in 0 until latentLen) {
                    noisyLatent[b][d][t] *= latentMask[b][0][t]
                }
            }
        }

        return NoisyLatentResult(noisyLatent, latentMask)
    }

    private fun getLatentMask(wavLengths: LongArray): Array<Array<FloatArray>> {
        val latentSize = (baseChunkSize * chunkCompress).toLong()
        val latentLengths = LongArray(wavLengths.size)
        var maxLen = 0L
        for (i in wavLengths.indices) {
            latentLengths[i] = (wavLengths[i] + latentSize - 1) / latentSize
            maxLen = Math.max(maxLen, latentLengths[i])
        }

        val mask = Array(wavLengths.size) { Array(1) { FloatArray(maxLen.toInt()) } }
        for (i in wavLengths.indices) {
            for (j in 0 until maxLen.toInt()) {
                mask[i][0][j] = if (j < latentLengths[i]) 1.0f else 0.0f
            }
        }
        return mask
    }

    fun speak(
        text: String,
        voiceStylePath: String,
        lang: String = "ko",
        speed: Float = 1.05f,
        silenceDuration: Float = 0.3f,
        steps: Int = 8,
        checkCancel: (() -> Boolean)? = null
    ): FloatArray {
        val style = loadVoiceStyle(voiceStylePath, env)
        try {
            val maxLen = if (lang == "ko" || lang == "ja") 120 else 300
            val chunks = chunkText(text, maxLen)

            val wavCat = ArrayList<Float>()

            for (i in chunks.indices) {
                if (checkCancel?.invoke() == true) {
                    throw java.util.concurrent.CancellationException("TTS synthesis canceled.")
                }
                val result = _infer(listOf(chunks[i]), listOf(lang), style, totalStep = steps, speed = speed, checkCancel = checkCancel)

                val dur = result.duration[0]
                val wavLen = (sampleRate * dur).toInt()
                val wavChunk = FloatArray(wavLen)
                System.arraycopy(result.wav, 0, wavChunk, 0, Math.min(wavLen, result.wav.size))

                if (i == 0) {
                    for (valItem in wavChunk) {
                        wavCat.add(valItem)
                    }
                } else {
                    val silenceLen = (silenceDuration * sampleRate).toInt()
                    for (j in 0 until silenceLen) {
                        wavCat.add(0.0f)
                    }
                    for (valItem in wavChunk) {
                        wavCat.add(valItem)
                    }
                }
            }

            val wavArray = FloatArray(wavCat.size)
            for (i in wavCat.indices) {
                wavArray[i] = wavCat[i]
            }
            return wavArray
        } finally {
            style.close()
        }
    }

    fun speakStream(
        text: String,
        voiceStylePath: String,
        lang: String = "ko",
        speed: Float = 1.05f,
        silenceDuration: Float = 0.3f,
        steps: Int = 8,
        checkCancel: (() -> Boolean)? = null,
        onAudioChunkAvailable: (FloatArray) -> Unit
    ) {
        val style = loadVoiceStyle(voiceStylePath, env)
        try {
            val maxLen = if (lang == "ko" || lang == "ja") 120 else 300
            val chunks = chunkText(text, maxLen)

            for (i in chunks.indices) {
                if (checkCancel?.invoke() == true) {
                    throw java.util.concurrent.CancellationException("TTS synthesis canceled.")
                }
                val result = _infer(listOf(chunks[i]), listOf(lang), style, totalStep = steps, speed = speed, checkCancel = checkCancel)

                val dur = result.duration[0]
                val wavLen = (sampleRate * dur).toInt()
                val wavChunk = FloatArray(wavLen)
                System.arraycopy(result.wav, 0, wavChunk, 0, Math.min(wavLen, result.wav.size))

                if (i > 0 && silenceDuration > 0.0f) {
                    val silenceLen = (silenceDuration * sampleRate).toInt()
                    val silenceChunk = FloatArray(silenceLen)
                    onAudioChunkAvailable(silenceChunk)
                }
                onAudioChunkAvailable(wavChunk)
            }
        } finally {
            style.close()
        }
    }

    fun close() {
        dpSession.close()
        textEncSession.close()
        vectorEstSession.close()
        vocoderSession.close()
    }
}

// --- 유틸리티 및 헬퍼 함수 ---

private fun createFloatTensor(array: Array<Array<FloatArray>>, env: OrtEnvironment): OnnxTensor {
    val dim0 = array.size
    val dim1 = array[0].size
    val dim2 = array[0][0].size
    val flat = FloatArray(dim0 * dim1 * dim2)
    var idx = 0
    for (i in 0 until dim0) {
        for (j in 0 until dim1) {
            for (k in 0 until dim2) {
                flat[idx++] = array[i][j][k]
            }
        }
    }
    val shape = longArrayOf(dim0.toLong(), dim1.toLong(), dim2.toLong())
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
}

private fun createLongTensor(array: Array<LongArray>, env: OrtEnvironment): OnnxTensor {
    val dim0 = array.size
    val dim1 = array[0].size
    val flat = LongArray(dim0 * dim1)
    var idx = 0
    for (i in 0 until dim0) {
        for (j in 0 until dim1) {
            flat[idx++] = array[i][j]
        }
    }
    val shape = longArrayOf(dim0.toLong(), dim1.toLong())
    return OnnxTensor.createTensor(env, LongBuffer.wrap(flat), shape)
}

private fun loadVoiceStyle(voiceStylePath: String, env: OrtEnvironment): Style {
    val file = File(voiceStylePath)
    if (!file.exists()) {
        throw FileNotFoundException("Voice style JSON file not found at: $voiceStylePath")
    }

    val gson = Gson()
    val styleJson = gson.fromJson(file.readText(), VoiceStyleJson::class.java)

    val ttl = styleJson.style_ttl
    val dp = styleJson.style_dp

    val ttlDims = ttl.dims.toLongArray()
    val dpDims = dp.dims.toLongArray()

    val ttlFlat = FloatArray((ttlDims[0] * ttlDims[1] * ttlDims[2]).toInt())
    var idx = 0
    for (i in ttl.data) {
        for (j in i) {
            for (k in j) {
                ttlFlat[idx++] = k
            }
        }
    }

    idx = 0
    val dpFlat = FloatArray((dpDims[0] * dpDims[1] * dpDims[2]).toInt())
    for (i in dp.data) {
        for (j in i) {
            for (k in j) {
                dpFlat[idx++] = k
            }
        }
    }

    val ttlTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(ttlFlat), ttlDims)
    val dpTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(dpFlat), dpDims)

    return Style(ttlTensor, dpTensor)
}

fun chunkText(text: String, maxLen: Int): List<String> {
    var actualMaxLen = maxLen
    if (actualMaxLen == 0) {
        actualMaxLen = 300
    }

    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
        return listOf("")
    }

    val paragraphs = trimmed.split(Regex("\\n\\s*\\n"))
    val chunks = ArrayList<String>()

    for (para in paragraphs) {
        val p = para.trim()
        if (p.isEmpty()) continue

        if (p.length <= actualMaxLen) {
            chunks.add(p)
            continue
        }

        val sentences = splitSentences(p)
        val current = StringBuilder()
        var currentLen = 0

        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.isEmpty()) continue

            val sentenceLen = s.length
            if (sentenceLen > actualMaxLen) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.clear()
                    currentLen = 0
                }

                val parts = s.split(",")
                for (part in parts) {
                    val pt = part.trim()
                    if (pt.isEmpty()) continue

                    val partLen = pt.length
                    if (partLen > actualMaxLen) {
                        val words = pt.split(Regex("\\s+"))
                        val wordChunk = StringBuilder()
                        var wordChunkLen = 0

                        for (word in words) {
                            val wordLen = word.length
                            if (wordChunkLen + wordLen + 1 > actualMaxLen && wordChunk.isNotEmpty()) {
                                chunks.add(wordChunk.toString().trim())
                                wordChunk.clear()
                                wordChunkLen = 0
                            }
                            if (wordChunk.isNotEmpty()) {
                                wordChunk.append(" ")
                                wordChunkLen++
                            }
                            wordChunk.append(word)
                            wordChunkLen += wordLen
                        }
                        if (wordChunk.isNotEmpty()) {
                            chunks.add(wordChunk.toString().trim())
                        }
                    } else {
                        if (currentLen + partLen + 1 > actualMaxLen && current.isNotEmpty()) {
                            chunks.add(current.toString().trim())
                            current.clear()
                            currentLen = 0
                        }
                        if (current.isNotEmpty()) {
                            current.append(", ")
                            currentLen += 2
                        }
                        current.append(pt)
                        currentLen += partLen
                    }
                }
                continue
            }

            if (currentLen + sentenceLen + 1 > actualMaxLen && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
                currentLen = 0
            }
            if (current.isNotEmpty()) {
                current.append(" ")
                currentLen++
            }
            current.append(s)
            currentLen += sentenceLen
        }

        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
        }
    }

    return if (chunks.isEmpty()) listOf("") else chunks
}

private val ABBREVIATIONS = listOf(
    "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.",
    "St.", "Ave.", "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.",
    "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D."
)

private fun splitSentences(text: String): List<String> {
    val abbrevPattern = StringBuilder()
    for (i in ABBREVIATIONS.indices) {
        if (i > 0) abbrevPattern.append("|")
        abbrevPattern.append(Pattern.quote(ABBREVIATIONS[i]))
    }
    val patternStr = "(?<!(?:$abbrevPattern))(?<=[.!?])\\s+"
    val pattern = Pattern.compile(patternStr)
    return pattern.split(text).toList()
}

fun writeWavFile(file: File, sampleRate: Int, audioData: FloatArray) {
    val totalAudioLen = audioData.size * 2
    val totalDataLen = totalAudioLen + 36
    val longSampleRate = sampleRate.toLong()
    val channels = 1
    val byteRate = 16 * sampleRate * channels / 8

    val header = ByteArray(44)
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
    header[12] = 'f'.code.toByte()
    header[13] = 'm'.code.toByte()
    header[14] = 't'.code.toByte()
    header[15] = ' '.code.toByte()
    header[16] = 16
    header[17] = 0
    header[18] = 0
    header[19] = 0
    header[20] = 1
    header[21] = 0
    header[22] = channels.toByte()
    header[23] = 0
    header[24] = (longSampleRate and 0xff).toByte()
    header[25] = ((longSampleRate shr 8) and 0xff).toByte()
    header[26] = ((longSampleRate shr 16) and 0xff).toByte()
    header[27] = ((longSampleRate shr 24) and 0xff).toByte()
    header[28] = (byteRate and 0xff).toByte()
    header[29] = ((byteRate shr 8) and 0xff).toByte()
    header[30] = ((byteRate shr 16) and 0xff).toByte()
    header[31] = ((byteRate shr 24) and 0xff).toByte()
    header[32] = (channels * 16 / 8).toByte()
    header[33] = 0
    header[34] = 16
    header[35] = 0
    header[36] = 'd'.code.toByte()
    header[37] = 'a'.code.toByte()
    header[38] = 't'.code.toByte()
    header[39] = 'a'.code.toByte()
    header[40] = (totalAudioLen and 0xff).toByte()
    header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
    header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
    header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

    FileOutputStream(file).use { out ->
        out.write(header)
        val buffer = ByteBuffer.allocate(audioData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in audioData) {
            val s = (sample * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()
            buffer.putShort(s)
        }
        out.write(buffer.array())
    }
}

// --- SupertonicTTSManager ---

object SupertonicTTSManager {
    private var instance: SupertonicTTS? = null
    private var loadedOnnxDir: String? = null

    @Synchronized
    fun getOrCreate(onnxDir: String, useGpu: Boolean = false): SupertonicTTS {
        val currentInstance = instance
        if (currentInstance == null || loadedOnnxDir != onnxDir || currentInstance.useGpu != useGpu) {
            instance?.close()
            AppLogManager.log("Supertonic ONNX 엔진 새 인스턴스 생성 시작 (경로: $onnxDir, GPU: $useGpu)")
            val newInstance = SupertonicTTS(onnxDir, useGpu)
            instance = newInstance
            loadedOnnxDir = onnxDir
            AppLogManager.log("Supertonic ONNX 엔진 인스턴스 생성 성공")
            return newInstance
        }
        return currentInstance
    }

    @Synchronized
    fun clear() {
        instance?.close()
        instance = null
        loadedOnnxDir = null
        AppLogManager.log("Supertonic ONNX 엔진 자원 해제 완료")
    }
}
