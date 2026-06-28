package org.example.x2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.google.gson.Gson
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaPlayer: android.media.MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            val context = LocalContext.current
            
            // Load application configuration
            var config by remember { 
                mutableStateOf(PersistenceManager.load(context).apply {
                    val externalDir = context.getExternalFilesDir(null)?.absolutePath ?: "/storage/emulated/0/Android/data/org.example.multitts/files"
                    if (supertonicOnnxDir.isBlank()) {
                        supertonicOnnxDir = "$externalDir/supertonic/onnx"
                    }
                    if (supertonicVoiceStylePath.isBlank()) {
                        supertonicVoiceStylePath = "$externalDir/supertonic/M1.json"
                    }
                }) 
            }
            
            var currentTts by remember { mutableStateOf<TextToSpeech?>(null) }
            
            // Dynamic TTS engine initialization
            LaunchedEffect(config.ttsEngine, config.ttsEnginePackage) {
                if (config.ttsEngine == "Supertonic" || config.ttsEngine == "Qwen3") {
                    try {
                        currentTts?.stop()
                        currentTts?.shutdown()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    currentTts = null
                    tts = null
                    AppLogManager.log("로컬 TTS 엔진(${config.ttsEngine})이 선택되었습니다. 시스템 TTS를 해제합니다.")
                    return@LaunchedEffect
                }
                
                val targetPackage = if (config.ttsEngine == "System") null else config.ttsEnginePackage
                try {
                    currentTts?.stop()
                    currentTts?.shutdown()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                val newTts = if (targetPackage.isNullOrBlank()) {
                    TextToSpeech(context) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            currentTts?.language = java.util.Locale.KOREAN
                            AppLogManager.log("TTS 엔진 초기화 성공 (System)")
                        } else {
                            AppLogManager.log("오류: TTS 엔진 초기화 실패: status = $status")
                        }
                    }
                } else {
                    TextToSpeech(context, { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            currentTts?.language = java.util.Locale.KOREAN
                            AppLogManager.log("TTS 엔진 초기화 성공 ($targetPackage)")
                        } else {
                            AppLogManager.log("오류: TTS 엔진 초기화 실패 ($targetPackage): status = $status")
                        }
                    }, targetPackage)
                }
                currentTts = newTts
                tts = newTts
            }
            
            MaterialTheme(colorScheme = getThemeColorScheme(config.theme)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TTSMainDashboard(
                        initialConfig = config,
                        onConfigSave = { newConfig ->
                            config = newConfig
                            PersistenceManager.save(context, newConfig)
                        },
                        tts = currentTts,
                        mediaPlayer = mediaPlayer,
                        onPlayAudio = { path, pitch, onComplete ->
                            playAudioFile(path, pitch, onComplete)
                        },
                        onStopAudio = {
                            stopAudioPlayback()
                        }
                    )
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = java.util.Locale.KOREAN
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        stopAudioPlayback()
        SupertonicTTSManager.clear()
        mainScope.cancel()
    }

    private fun playAudioFile(path: String, pitch: Float, onComplete: () -> Unit) {
        try {
            stopAudioPlayback()
            val mp = android.media.MediaPlayer().apply {
                setDataSource(path)
                prepare()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    playbackParams = playbackParams.setPitch(pitch)
                }
                start()
            }
            mediaPlayer = mp
            mp.setOnCompletionListener {
                it.release()
                if (mediaPlayer == it) {
                    mediaPlayer = null
                }
                onComplete()
                AppLogManager.log("오디오 재생 완료")
            }
        } catch (e: Exception) {
            AppLogManager.log("오류: 재생 실패: ${e.message}")
            onComplete()
        }
    }

    private fun stopAudioPlayback() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            it.release()
        }
        mediaPlayer = null
    }
}

// Themes mapping matching premium colors
fun getThemeColorScheme(themeName: String): ColorScheme {
    return when (themeName) {
        "Light" -> lightColorScheme()
        "Ocean" -> darkColorScheme(
            primary = Color(0xFF80D8FF),
            onPrimary = Color(0xFF00364D),
            primaryContainer = Color(0xFF004D69),
            onPrimaryContainer = Color(0xFFB3E5FC),
            secondary = Color(0xFFB2EBF2),
            onSecondary = Color(0xFF00363A),
            secondaryContainer = Color(0xFF004F53),
            onSecondaryContainer = Color(0xFFE0F7FA),
            background = Color(0xFF001F24),
            surface = Color(0xFF001F24),
            surfaceVariant = Color(0xFF3F484B),
            onSurface = Color(0xFFE0E3E3),
            onSurfaceVariant = Color(0xFFBFC8CB)
        )
        "Forest" -> darkColorScheme(
            primary = Color(0xFFB9F6CA),
            onPrimary = Color(0xFF00391C),
            primaryContainer = Color(0xFF00522B),
            onPrimaryContainer = Color(0xFFD5FFE1),
            secondary = Color(0xFFBCEBE3),
            onSecondary = Color(0xFF003732),
            secondaryContainer = Color(0xFF005049),
            onSecondaryContainer = Color(0xFFD8F7F0),
            background = Color(0xFF00201A),
            surface = Color(0xFF00201A),
            surfaceVariant = Color(0xFF3F4945),
            onSurface = Color(0xFFE0E3E0),
            onSurfaceVariant = Color(0xFFBFC9C4)
        )
        "Sunset" -> darkColorScheme(
            primary = Color(0xFFFFFF8A80),
            onPrimary = Color(0xFF690005),
            primaryContainer = Color(0xFF93000A),
            onPrimaryContainer = Color(0xFFFFDAD6),
            secondary = Color(0xFFFFB4AB),
            onSecondary = Color(0xFF690005),
            secondaryContainer = Color(0xFF93000A),
            onSecondaryContainer = Color(0xFFFFDAD6),
            background = Color(0xFF201A19),
            surface = Color(0xFF201A19),
            surfaceVariant = Color(0xFF534341),
            onSurface = Color(0xFFEDE0DE),
            onSurfaceVariant = Color(0xFFD8C2BF)
        )
        else -> darkColorScheme() // Dark default
    }
}

fun applyVoiceConfig(tts: TextToSpeech?, jsonStr: String) {
    val engine = tts ?: return
    if (jsonStr.isBlank()) return
    try {
        val gson = Gson()
        val map = gson.fromJson(jsonStr, Map::class.java) ?: return
        
        val pitchVal = map["pitch"] ?: map["pitch_rate"]
        if (pitchVal is Number) {
            engine.setPitch(pitchVal.toFloat())
        }
        
        val speedVal = map["speed"] ?: map["speech_rate"] ?: map["rate"]
        if (speedVal is Number) {
            engine.setSpeechRate(speedVal.toFloat())
        }
        
        val langVal = map["language"] ?: map["lang"] ?: map["locale"]
        if (langVal is String) {
            engine.language = java.util.Locale(langVal)
        }
        
        val voiceName = map["voice"] ?: map["voice_name"] ?: map["speaker"]
        if (voiceName is String) {
            val voices = engine.voices
            if (!voices.isNullOrEmpty()) {
                val matchedVoice = voices.find { it.name.equals(voiceName, ignoreCase = true) }
                if (matchedVoice != null) {
                    engine.voice = matchedVoice
                    AppLogManager.log("TTS 보이스 매칭 성공: ${matchedVoice.name}")
                }
            }
        }
    } catch (e: Exception) {
        AppLogManager.log("오류: TTS 보이스 JSON 파싱 오류: ${e.message}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TTSMainDashboard(
    initialConfig: AppConfig,
    onConfigSave: (AppConfig) -> Unit,
    tts: TextToSpeech?,
    mediaPlayer: android.media.MediaPlayer?,
    onPlayAudio: (String, Float, () -> Unit) -> Unit,
    onStopAudio: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember(initialConfig) { mutableStateOf(initialConfig) }
    
    var activeTab by remember { mutableIntStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var isCopyingModel by remember { mutableStateOf(false) }
    var copyProgress by remember { mutableStateOf(0) }
    var showDebugLogs by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    
    // File copying SAF launchers
    val supertonicOnnxDirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isCopyingModel = true
                copyProgress = 0
                AppLogManager.log("사용자가 Supertonic ONNX 폴더를 선택함 (URI: $uri). 앱 내부 전용 저장소로 복사 중...")
                
                val destDir = File(context.getExternalFilesDir(null), "supertonic/onnx")
                if (!destDir.exists()) destDir.mkdirs()
                
                val success = withContext(Dispatchers.IO) {
                    copyDocumentTreeToFolder(context, uri, destDir) { progress ->
                        copyProgress = progress
                    }
                }
                isCopyingModel = false
                
                if (success) {
                    val path = destDir.absolutePath
                    val newConfig = config.copy(supertonicOnnxDir = path)
                    config = newConfig
                    onConfigSave(newConfig)
                    AppLogManager.log("Supertonic ONNX 폴더 복사 완료! 설정 경로: $path")
                    Toast.makeText(context, "Supertonic ONNX 폴더 복사 완료!", Toast.LENGTH_SHORT).show()
                } else {
                    AppLogManager.log("오류: Supertonic ONNX 폴더 복사 실패")
                    Toast.makeText(context, "ONNX 폴더 복사 실패. 파일을 확인하세요.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val supertonicOnnxFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isCopyingModel = true
                val destDir = File(context.getExternalFilesDir(null), "supertonic/onnx")
                if (!destDir.exists()) destDir.mkdirs()
                
                val fileName = getFileNameFromUri(context, uri) ?: "temp.onnx"
                val destFile = File(destDir, fileName)
                
                val success = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                isCopyingModel = false
                
                if (success) {
                    val path = destDir.absolutePath
                    val newConfig = config.copy(supertonicOnnxDir = path)
                    config = newConfig
                    onConfigSave(newConfig)
                    AppLogManager.log("Supertonic ONNX 파일 복사 성공: $fileName -> $path")
                    Toast.makeText(context, "$fileName 복사 완료!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "파일 복사 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val supertonicVoiceStylePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isCopyingModel = true
                val copiedPath = withContext(Dispatchers.IO) {
                    copyStyleUriToAppStorage(context, uri)
                }
                isCopyingModel = false
                if (copiedPath != null) {
                    val newConfig = config.copy(supertonicVoiceStylePath = copiedPath)
                    config = newConfig
                    onConfigSave(newConfig)
                    AppLogManager.log("Supertonic 목소리 스타일 JSON 복사 및 설정 완료: $copiedPath")
                    Toast.makeText(context, "목소리 스타일 파일 복사 완료!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "목소리 스타일 복사 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val qwen3BinaryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isCopyingModel = true
                val destDir = File(context.getExternalFilesDir(null), "qwen3/bin")
                if (!destDir.exists()) destDir.mkdirs()
                
                val fileName = getFileNameFromUri(context, uri) ?: "qwen3-tts"
                val destFile = File(destDir, fileName)
                
                val success = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                isCopyingModel = false
                if (success) {
                    val path = destFile.absolutePath
                    val newConfig = config.copy(qwen3BinaryPath = path)
                    config = newConfig
                    onConfigSave(newConfig)
                    AppLogManager.log("Qwen3 실행 파일 복사 완료: $path")
                    Toast.makeText(context, "Qwen3 실행 파일 복사 완료!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Qwen3 실행 파일 복사 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val qwen3ModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isCopyingModel = true
                val destDir = File(context.getExternalFilesDir(null), "qwen3/models")
                if (!destDir.exists()) destDir.mkdirs()
                
                val fileName = getFileNameFromUri(context, uri) ?: "qwen3-model.gguf"
                val destFile = File(destDir, fileName)
                
                val success = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                isCopyingModel = false
                if (success) {
                    val path = destFile.absolutePath
                    val newConfig = config.copy(qwen3ModelDir = path)
                    config = newConfig
                    onConfigSave(newConfig)
                    AppLogManager.log("Qwen3 모델 복사 완료: $path")
                    Toast.makeText(context, "Qwen3 모델 복사 완료!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Qwen3 모델 복사 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val qwen3ReferenceAudioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isCopyingModel = true
                val destDir = File(context.getExternalFilesDir(null), "qwen3/reference")
                if (!destDir.exists()) destDir.mkdirs()
                
                val fileName = getFileNameFromUri(context, uri) ?: "ref.wav"
                val destFile = File(destDir, fileName)
                
                val success = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                isCopyingModel = false
                if (success) {
                    val path = destFile.absolutePath
                    val newConfig = config.copy(qwen3ReferenceAudioPath = path)
                    config = newConfig
                    onConfigSave(newConfig)
                    AppLogManager.log("Qwen3 참조 오디오 복사 완료: $path")
                    Toast.makeText(context, "참조 오디오 복사 완료!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "참조 오디오 복사 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multi-TTS Dashboard", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { 
                        showDebugLogs = !showDebugLogs 
                    }) {
                        Icon(
                            imageVector = Icons.Default.BugReport, 
                            contentDescription = "디버그 로그",
                            tint = if (showDebugLogs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (showDebugLogs) {
                DebugLogsScreen(onClose = { showDebugLogs = false })
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = activeTab) {
                        Tab(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0 },
                            text = { Text("스피치 (Speech)", fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1 },
                            text = { Text("엔진 설정 (Settings)", fontWeight = FontWeight.SemiBold) }
                        )
                    }

                    when (activeTab) {
                        0 -> {
                            // Dashboard Tab
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                // Text Input Card
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        OutlinedTextField(
                                            value = inputText,
                                            onValueChange = { inputText = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(120.dp),
                                            placeholder = { Text("합성할 텍스트를 입력하세요...") },
                                            maxLines = 5,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (inputText.isNotBlank()) {
                                                        synthesizeAndPlayTTS(
                                                            text = inputText,
                                                            config = config,
                                                            context = context,
                                                            tts = tts,
                                                            scope = scope,
                                                            onHistoryAdded = { newItem ->
                                                                val updatedHistory = config.history.toMutableList().apply { add(0, newItem) }
                                                                val newConfig = config.copy(history = updatedHistory)
                                                                config = newConfig
                                                                onConfigSave(newConfig)
                                                            },
                                                            onStatusChange = { isGenerating = it },
                                                            onPlayAudio = onPlayAudio
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                enabled = !isGenerating && inputText.isNotBlank()
                                            ) {
                                                if (isGenerating) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(20.dp),
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Icon(Icons.Default.PlayArrow, null)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("합성 및 재생")
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    onStopAudio()
                                                    tts?.stop()
                                                    isGenerating = false
                                                    AppLogManager.log("재생 정지 요청됨")
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Stop, null)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("정지")
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // History section
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "합성 기록 (${config.history.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (config.history.isNotEmpty()) {
                                        TextButton(onClick = {
                                            // Delete all history files
                                            config.history.forEach { item ->
                                                item.audioPath?.let { path ->
                                                    try {
                                                        File(path).delete()
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                            }
                                            val newConfig = config.copy(history = mutableListOf())
                                            config = newConfig
                                            onConfigSave(newConfig)
                                            AppLogManager.log("합성 기록이 모두 삭제되었습니다.")
                                        }) {
                                            Text("기록 비우기", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (config.history.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "기록이 없습니다. 첫 음성을 합성해보세요!",
                                            color = Color.Gray,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(config.history, key = { it.id }) { item ->
                                            HistoryCard(
                                                item = item,
                                                onPlay = {
                                                    if (item.audioPath != null && File(item.audioPath!!).exists()) {
                                                        onPlayAudio(item.audioPath!!, item.pitch) {
                                                            isGenerating = false
                                                        }
                                                    } else if (item.engine == "System" && tts != null) {
                                                        applyVoiceConfig(tts, config.ttsVoiceConfigJson)
                                                        tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, null)
                                                    } else {
                                                        Toast.makeText(context, "음성 파일이 존재하지 않습니다.", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onDelete = {
                                                    item.audioPath?.let { path ->
                                                        try {
                                                            File(path).delete()
                                                        } catch (e: Exception) {
                                                            e.printStackTrace()
                                                        }
                                                    }
                                                    val updatedList = config.history.toMutableList().apply { remove(item) }
                                                    val newConfig = config.copy(history = updatedList)
                                                    config = newConfig
                                                    onConfigSave(newConfig)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            // Settings Tab
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                            ) {
                                Text("애플리케이션 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(12.dp))

                                // Theme selection
                                Text("UI 테마 설정", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("Dark", "Light", "Ocean", "Forest", "Sunset").forEach { themeName ->
                                        FilterChip(
                                            selected = config.theme == themeName,
                                            onClick = { 
                                                val newConfig = config.copy(theme = themeName)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            label = { Text(themeName) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text("연산 가속 장치", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("CPU", "GPU").forEach { type ->
                                        FilterChip(
                                            selected = config.backendType == type,
                                            onClick = { 
                                                val newConfig = config.copy(backendType = type)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            label = { Text(type) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                                Text("TTS 엔진 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))

                                // TTS Engine selectors
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("System", "Supertonic", "Qwen3").forEach { engine ->
                                        FilterChip(
                                            selected = config.ttsEngine == engine,
                                            onClick = { 
                                                val newConfig = config.copy(ttsEngine = engine)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            label = { Text(if (engine == "System") "시스템 기본" else engine) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                when (config.ttsEngine) {
                                    "System" -> {
                                        Text("TTS 패키지명 (선택사항)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = config.ttsEnginePackage,
                                            onValueChange = { 
                                                val newConfig = config.copy(ttsEnginePackage = it)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            placeholder = { Text("기본값: 시스템 기본") },
                                            singleLine = true
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("보이스 상세 설정 (JSON Format)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = config.ttsVoiceConfigJson,
                                            onValueChange = { 
                                                val newConfig = config.copy(ttsVoiceConfigJson = it)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(120.dp)
                                                .padding(vertical = 8.dp),
                                            placeholder = { Text("예:\n{\n  \"voice\": \"ko-KR-Standard-A\",\n  \"speed\": 1.0,\n  \"pitch\": 1.0\n}") },
                                            maxLines = 6
                                        )
                                    }
                                    "Supertonic" -> {
                                        Text("Supertonic ONNX 폴더 경로", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = config.supertonicOnnxDir,
                                            onValueChange = { 
                                                val newConfig = config.copy(supertonicOnnxDir = it)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            singleLine = true
                                        )
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { supertonicOnnxDirPickerLauncher.launch(null) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("폴더 브라우즈")
                                            }
                                            OutlinedButton(
                                                onClick = { supertonicOnnxFilePickerLauncher.launch("*/*") },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("파일로 폴더지정")
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("Supertonic 목소리 스타일 JSON 경로", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = config.supertonicVoiceStylePath,
                                            onValueChange = { 
                                                val newConfig = config.copy(supertonicVoiceStylePath = it)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            singleLine = true
                                        )
                                        Button(
                                            onClick = { supertonicVoiceStylePickerLauncher.launch("*/*") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("스타일 파일 브라우즈")
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("파라미터 조절", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        
                                        ParameterControl(
                                            label = "합성 속도 (Speed)",
                                            value = config.supertonicSpeed.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(supertonicSpeed = it.toFloat())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 0.5f..2.0f
                                        )

                                        ParameterControl(
                                            label = "합성 피치 (Pitch)",
                                            value = config.supertonicPitch.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(supertonicPitch = it.toFloat())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 0.5f..2.0f
                                        )

                                        ParameterControl(
                                            label = "추론 스텝 수 (Steps)",
                                            value = config.supertonicSteps.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(supertonicSteps = it.toInt())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 1f..32f,
                                            isInt = true
                                        )
                                    }
                                    "Qwen3" -> {
                                        Text("Qwen3 실행 파일 경로", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = config.qwen3BinaryPath,
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3BinaryPath = it)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            singleLine = true
                                        )
                                        Button(
                                            onClick = { qwen3BinaryPickerLauncher.launch("*/*") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("바이너리 브라우즈")
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("Qwen3 모델 경로 (GGUF)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = config.qwen3ModelDir,
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3ModelDir = it)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            singleLine = true
                                        )
                                        Button(
                                            onClick = { qwen3ModelPickerLauncher.launch("*/*") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("모델 브라우즈")
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("목소리 복제 참조 오디오 경로", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = config.qwen3ReferenceAudioPath,
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3ReferenceAudioPath = it)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            singleLine = true
                                        )
                                        Button(
                                            onClick = { qwen3ReferenceAudioPickerLauncher.launch("*/*") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("참조 오디오 브라우즈")
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("파라미터 조절", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

                                        ParameterControl(
                                            label = "합성 속도 (Speed)",
                                            value = config.qwen3Speed.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3Speed = it.toFloat())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 0.5f..2.0f
                                        )

                                        ParameterControl(
                                            label = "합성 피치 (Pitch)",
                                            value = config.qwen3Pitch.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3Pitch = it.toFloat())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 0.5f..2.0f
                                        )

                                        ParameterControl(
                                            label = "GPU 오프로드 레이어 수 (-ngl)",
                                            value = config.qwen3GpuLayers.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3GpuLayers = it.toInt())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 0f..99f,
                                            isInt = true
                                        )

                                        ParameterControl(
                                            label = "추론 온도 (Temperature)",
                                            value = config.qwen3Temperature.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3Temperature = it.toFloat())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 0.0f..2.0f
                                        )

                                        ParameterControl(
                                            label = "Top P",
                                            value = config.qwen3TopP.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3TopP = it.toFloat())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 0.0f..1.0f
                                        )

                                        ParameterControl(
                                            label = "Top K",
                                            value = config.qwen3TopK.toDouble(),
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3TopK = it.toInt())
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            range = 1f..100f,
                                            isInt = true
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("추가 사용자 지정 CLI 인수", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = config.qwen3CustomArgs,
                                            onValueChange = { 
                                                val newConfig = config.copy(qwen3CustomArgs = it)
                                                config = newConfig
                                                onConfigSave(newConfig)
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            placeholder = { Text("예: --threads 4") },
                                            singleLine = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Copying loading overlay
            if (isCopyingModel) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("파일 복사 및 처리 중") },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("대용량 파일/폴더를 앱 내부 전용 스토리지로 안전하게 복사하고 있습니다. 잠시만 기다려주세요...")
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = copyProgress / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("$copyProgress% 완료")
                        }
                    },
                    confirmButton = {}
                )
            }
        }
    }
}

@Composable
fun HistoryCard(
    item: TTSHistoryItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val date = remember(item.timestamp) {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        sdf.format(Date(item.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(item.engine, fontSize = 10.sp) },
                        modifier = Modifier.height(20.dp)
                    )
                    Text(
                        text = date,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
            
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.VolumeUp, "재생")
            }
            
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "삭제", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun ParameterControl(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    isInt: Boolean = false
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = if (isInt) value.toInt().toString() else String.format("%.2f", value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun DebugLogsScreen(onClose: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val logList = AppLogManager.logs
    val listState = rememberLazyListState()

    LaunchedEffect(logList.size) {
        if (logList.isNotEmpty()) {
            listState.animateScrollToItem(logList.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "디버그 로그",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            
            TextButton(
                onClick = {
                    val fullLogs = logList.joinToString("\n")
                    clipboardManager.setText(AnnotatedString(fullLogs))
                    Toast.makeText(context, "클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("복사", color = MaterialTheme.colorScheme.primary)
            }
            
            Spacer(Modifier.width(8.dp))
            
            TextButton(
                onClick = { AppLogManager.clear() }
            ) {
                Text("비우기", color = MaterialTheme.colorScheme.error)
            }
            
            Spacer(Modifier.width(8.dp))
            
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "닫기", tint = Color.White)
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E))
                .padding(8.dp)
        ) {
            if (logList.isEmpty()) {
                Text(
                    text = "로그 기록이 없습니다.",
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logList) { logLine ->
                        val color = when {
                            logLine.contains("오류") || logLine.contains("실패") || logLine.contains("Exception") -> Color(0xFFEF5350)
                            logLine.contains("경고") -> Color(0xFFFFCA28)
                            logLine.contains("성공") || logLine.contains("완료") -> Color(0xFF66BB6A)
                            else -> Color(0xFFB0BEC5)
                        }
                        
                        Text(
                            text = logLine,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

fun copyStyleUriToAppStorage(context: Context, uri: Uri): String? {
    val contentResolver = context.contentResolver
    var fileName = "voice_style.json"
    
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1 && cursor.moveToFirst()) {
            fileName = cursor.getString(nameIndex)
        }
    }

    if (!fileName.endsWith(".json", ignoreCase = true)) {
        fileName = "$fileName.json"
    }

    val destFile = File(context.getExternalFilesDir(null), fileName)
    if (destFile.exists()) {
        destFile.delete()
    }

    return try {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val outputStream = FileOutputStream(destFile)
        inputStream.copyTo(outputStream)
        outputStream.flush()
        outputStream.close()
        inputStream.close()
        destFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun copyDocumentTreeToFolder(
    context: Context,
    treeUri: Uri,
    destDir: File,
    onProgress: (Int) -> Unit
): Boolean {
    return try {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val files = rootDoc.listFiles()
        if (files.isEmpty()) return false
        
        val totalFiles = files.size
        var copiedFiles = 0
        
        for (doc in files) {
            if (doc.isFile) {
                val fileName = doc.name ?: continue
                if (fileName.endsWith(".onnx", ignoreCase = true) || fileName.endsWith(".json", ignoreCase = true)) {
                    val destFile = File(destDir, fileName)
                    context.contentResolver.openInputStream(doc.uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            copiedFiles++
            onProgress((copiedFiles * 100) / totalFiles)
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var fileName: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1 && cursor.moveToFirst()) {
            fileName = cursor.getString(nameIndex)
        }
    }
    return fileName
}

fun synthesizeAndPlayTTS(
    text: String,
    config: AppConfig,
    context: Context,
    tts: TextToSpeech?,
    scope: CoroutineScope,
    onHistoryAdded: (TTSHistoryItem) -> Unit,
    onStatusChange: (Boolean) -> Unit,
    onPlayAudio: (String, Float, () -> Unit) -> Unit
) {
    if (text.isBlank()) return
    onStatusChange(true)

    val id = UUID.randomUUID().toString()
    val historyItem = TTSHistoryItem(
        id = id,
        text = text,
        engine = config.ttsEngine,
        speed = if (config.ttsEngine == "Supertonic") config.supertonicSpeed else if (config.ttsEngine == "Qwen3") config.qwen3Speed else 1.0f,
        pitch = if (config.ttsEngine == "Supertonic") config.supertonicPitch else if (config.ttsEngine == "Qwen3") config.qwen3Pitch else 1.0f
    )

    scope.launch(Dispatchers.IO) {
        try {
            when (config.ttsEngine) {
                "Supertonic" -> {
                    if (config.supertonicOnnxDir.isBlank() || config.supertonicVoiceStylePath.isBlank()) {
                        AppLogManager.log("오류: Supertonic 설정이 비어 있습니다.")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Supertonic 설정을 완료해 주세요.", Toast.LENGTH_LONG).show()
                            onStatusChange(false)
                        }
                        return@launch
                    }
                    val useGpu = config.backendType.uppercase() == "GPU"
                    val ttsEngine = SupertonicTTSManager.getOrCreate(config.supertonicOnnxDir, useGpu)
                    val audioData = ttsEngine.speak(
                        text = text,
                        voiceStylePath = config.supertonicVoiceStylePath,
                        lang = "ko",
                        speed = config.supertonicSpeed,
                        steps = config.supertonicSteps,
                        checkCancel = { !isActive }
                    )
                    
                    val cachedFile = File(context.cacheDir, "tts_history_${id}.wav")
                    writeWavFile(cachedFile, ttsEngine.sampleRate, audioData)
                    historyItem.audioPath = cachedFile.absolutePath

                    withContext(Dispatchers.Main) {
                        onHistoryAdded(historyItem)
                        onPlayAudio(cachedFile.absolutePath, config.supertonicPitch) {
                            onStatusChange(false)
                        }
                    }
                }
                "Qwen3" -> {
                    if (config.qwen3BinaryPath.isBlank() || config.qwen3ModelDir.isBlank()) {
                        AppLogManager.log("오류: Qwen3 설정이 비어 있습니다.")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Qwen3 설정을 완료해 주세요.", Toast.LENGTH_LONG).show()
                            onStatusChange(false)
                        }
                        return@launch
                    }
                    val qwen3Service = Qwen3SpeechService(config)
                    val result = qwen3Service.synthesize(text, context)
                    if (result == null) {
                        AppLogManager.log("오류: Qwen3 합성 실패")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Qwen3 합성에 실패했습니다.", Toast.LENGTH_LONG).show()
                            onStatusChange(false)
                        }
                        return@launch
                    }

                    val cachedFile = File(context.cacheDir, "tts_history_${id}.wav")
                    val numSamples = result.samples.size / 2
                    val floatData = FloatArray(numSamples)
                    val pcmBuffer = java.nio.ByteBuffer.wrap(result.samples).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (i in 0 until numSamples) {
                        floatData[i] = pcmBuffer.get(i).toFloat() / 32767.0f
                    }
                    writeWavFile(cachedFile, result.sampleRate, floatData)
                    historyItem.audioPath = cachedFile.absolutePath

                    withContext(Dispatchers.Main) {
                        onHistoryAdded(historyItem)
                        onPlayAudio(cachedFile.absolutePath, config.qwen3Pitch) {
                            onStatusChange(false)
                        }
                    }
                }
                else -> { // System
                    withContext(Dispatchers.Main) {
                        if (tts != null) {
                            applyVoiceConfig(tts, config.ttsVoiceConfigJson)
                            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                            onHistoryAdded(historyItem)
                        } else {
                            AppLogManager.log("오류: 시스템 TTS 엔진이 준비되지 않았습니다.")
                        }
                        onStatusChange(false)
                    }
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            AppLogManager.log("합성 취소됨")
            withContext(Dispatchers.Main) {
                onStatusChange(false)
            }
        } catch (e: Exception) {
            AppLogManager.logException("합성 중 오류 발생", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "합성 오류: ${e.message}", Toast.LENGTH_LONG).show()
                onStatusChange(false)
            }
        }
    }
}
