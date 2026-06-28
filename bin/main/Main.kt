import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.*
import java.io.File
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser

fun main() {
    // Create cache directory
    val cacheDir = File("tts_cache")
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }

    application {
        val config = PersistenceManager.load()
        
        Window(
            onCloseRequest = {
                exitApplication()
            },
            title = "Multi-TTS Dashboard",
            state = rememberWindowState(width = 1100.dp, height = 750.dp)
        ) {
            TTSAppWrapper(config)
        }
    }
}

@Composable
fun TTSAppWrapper(initialConfig: AppConfig) {
    var config by remember { mutableStateOf(initialConfig) }
    
    // Save settings helper
    val saveConfig = { newConfig: AppConfig ->
        config = newConfig
        PersistenceManager.save(newConfig)
    }

    val darkScheme = darkColorScheme(
        primary = Color(0xFF6366F1), // Indigo 500
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF312E81), // Indigo 900
        onPrimaryContainer = Color(0xFFE0E7FF),
        secondary = Color(0xFF10B981), // Emerald 500
        onSecondary = Color(0xFFFFFFFF),
        background = Color(0xFF0B0F19), // Deep Midnight
        surface = Color(0xFF1E293B), // Slate 800
        onBackground = Color(0xFFF1F5F9),
        onSurface = Color(0xFFE2E8F0)
    )

    MaterialTheme(colorScheme = darkScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainTTSDashboard(config, onConfigUpdate = saveConfig)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainTTSDashboard(config: AppConfig, onConfigUpdate: (AppConfig) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var isSynthesizing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(if (config.ttsEngine == "Sherpa") 0 else 1) }

    // TTS Services & Player
    val audioPlayer = remember { AudioPlayer() }
    val sherpaService = remember(config.ttsModelPath, config.ttsLexiconPath, config.ttsTokensPath, config.speed) {
        SherpaSpeechService(config)
    }
    val qwen3Service = remember(config.qwen3BinaryPath, config.qwen3ModelDir, config.ttsReferenceAudioPath, config.speed, config.pitch, config.steps, config.temperature, config.topP, config.topK, config.gpuLayers, config.customArgs) {
        Qwen3SpeechService(config)
    }

    var currentlyPlayingId by remember { mutableStateOf<String?>(null) }

    // Synthesize process helper
    val runSynthesis = { textToSpeak: String, onComplete: (File?) -> Unit ->
        if (textToSpeak.isBlank()) {
            statusMessage = "텍스트를 입력해 주세요."
        } else {
            isSynthesizing = true
            statusMessage = "음성을 합성하고 있습니다..."
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val result = if (config.ttsEngine == "Qwen3") {
                        qwen3Service.synthesize(textToSpeak)
                    } else {
                        sherpaService.synthesize(textToSpeak)
                    }

                    withContext(Dispatchers.Main) {
                        isSynthesizing = false
                        if (result != null) {
                            // Play audio
                            audioPlayer.play(result.data, result.sampleRate)
                            
                            // Save to cache
                            val cacheFile = File("tts_cache/tts_${System.currentTimeMillis()}.wav")
                            audioPlayer.savePcmToWav(result.data, result.sampleRate, cacheFile)
                            
                            statusMessage = "음성 합성 성공!"
                            onComplete(cacheFile)
                        } else {
                            statusMessage = "음성 합성에 실패했습니다. 설정을 확인해 주세요."
                            onComplete(null)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isSynthesizing = false
                        statusMessage = "에러 발생: ${e.message}"
                        onComplete(null)
                    }
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // --- 1. LEFT SIDEBAR: History ---
        Card(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "합성 이력 (History)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (config.history.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                // Delete all cache files and clear list
                                config.history.forEach { item ->
                                    item.audioPath?.let { File(it).delete() }
                                }
                                onConfigUpdate(config.copy(history = mutableListOf()))
                                statusMessage = "이력이 삭제되었습니다."
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear History",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(8.dp))

                if (config.history.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "합성된 이력이 없습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(config.history.asReversed()) { item ->
                            val fileExists = item.audioPath?.let { File(it).exists() } ?: false
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        inputText = item.text
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${item.engine} | 속도: ${item.speed}x",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            // Play cached audio button
                                            IconButton(
                                                onClick = {
                                                    if (currentlyPlayingId == item.id) {
                                                        audioPlayer.stop()
                                                        currentlyPlayingId = null
                                                    } else {
                                                        if (fileExists) {
                                                            audioPlayer.playWav(File(item.audioPath!!))
                                                            currentlyPlayingId = item.id
                                                        } else {
                                                            // Regenerate and play
                                                            runSynthesis(item.text) { newFile ->
                                                                if (newFile != null) {
                                                                    item.audioPath = newFile.absolutePath
                                                                    onConfigUpdate(config)
                                                                }
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (currentlyPlayingId == item.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                    contentDescription = "Replay",
                                                    tint = if (fileExists) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            // Delete single item
                                            IconButton(
                                                onClick = {
                                                    item.audioPath?.let { File(it).delete() }
                                                    val newHistory = config.history.toMutableList()
                                                    newHistory.remove(item)
                                                    onConfigUpdate(config.copy(history = newHistory))
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Item",
                                                    tint = Color.Gray,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 2. MAIN WORKSPACE ---
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(top = 12.dp, bottom = 12.dp, end = 12.dp)
        ) {
            // Header / App Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Multi-TTS Dashboard",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "다양한 로컬 음성 합성 엔진 제어판",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
                
                // Active Engine Indicator
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.SettingsVoice, null, modifier = Modifier.size(16.dp))
                        Text(
                            text = if (config.ttsEngine == "Sherpa") "Sherpa-ONNX 활성화" else "Qwen3-TTS 활성화",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }

            // Input Text Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "합성할 텍스트 입력",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("여기에 말로 바꿀 문장을 입력하세요...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF334155)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Controls row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${inputText.length} 자",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Stop Playback Button
                            OutlinedButton(
                                onClick = {
                                    audioPlayer.stop()
                                    currentlyPlayingId = null
                                    statusMessage = "재생 중단"
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("중지")
                            }

                            // Export WAV Button
                            OutlinedButton(
                                onClick = {
                                    if (inputText.isBlank()) {
                                        statusMessage = "텍스트가 입력되지 않았습니다."
                                    } else {
                                        isSynthesizing = true
                                        statusMessage = "내보내기를 위한 음성 합성 중..."
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val result = if (config.ttsEngine == "Qwen3") {
                                                qwen3Service.synthesize(inputText)
                                            } else {
                                                sherpaService.synthesize(inputText)
                                            }
                                            
                                            withContext(Dispatchers.Main) {
                                                isSynthesizing = false
                                                if (result != null) {
                                                    val dialog = FileDialog(null as Frame?, "WAV 저장", FileDialog.SAVE)
                                                    dialog.file = "output.wav"
                                                    dialog.isVisible = true
                                                    dialog.file?.let { fileName ->
                                                        val saveFile = File(dialog.directory, fileName)
                                                        audioPlayer.savePcmToWav(result.data, result.sampleRate, saveFile)
                                                        statusMessage = "내보내기 완료: ${saveFile.name}"
                                                    }
                                                } else {
                                                    statusMessage = "음성 합성에 실패했습니다."
                                                }
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF334155)),
                                enabled = !isSynthesizing
                            ) {
                                Icon(Icons.Default.Download, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("파일 저장")
                            }

                            // Synthesize Button
                            Button(
                                onClick = {
                                    runSynthesis(inputText) { cacheFile ->
                                        if (cacheFile != null) {
                                            val newHistory = config.history.toMutableList()
                                            newHistory.add(
                                                TTSHistoryItem(
                                                    text = inputText,
                                                    engine = config.ttsEngine,
                                                    speed = config.speed,
                                                    pitch = config.pitch,
                                                    audioPath = cacheFile.absolutePath
                                                )
                                            )
                                            onConfigUpdate(config.copy(history = newHistory))
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isSynthesizing
                            ) {
                                if (isSynthesizing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("합성 중...")
                                } else {
                                    Icon(Icons.Default.RecordVoiceOver, null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("음성 듣기")
                                }
                            }
                        }
                    }
                }
            }

            // Status message
            if (statusMessage.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info, 
                            contentDescription = "Status",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Engine Settings Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Engine Switch Tab
                    TabRow(
                        selectedTabIndex = activeTab,
                        containerColor = Color.Transparent,
                        divider = {}
                    ) {
                        Tab(
                            selected = activeTab == 0,
                            onClick = {
                                activeTab = 0
                                onConfigUpdate(config.copy(ttsEngine = "Sherpa"))
                            },
                            text = { Text("Sherpa-ONNX (로컬 VITS)") }
                        )
                        Tab(
                            selected = activeTab == 1,
                            onClick = {
                                activeTab = 1
                                onConfigUpdate(config.copy(ttsEngine = "Qwen3"))
                            },
                            text = { Text("Qwen3-TTS (GGUF)") }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Engine-specific options (Left side of settings)
                        Column(modifier = Modifier.weight(1f)) {
                            if (activeTab == 0) {
                                // Sherpa Settings
                                Text("Sherpa VITS 모델 설정", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Model Path
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = config.ttsModelPath,
                                        onValueChange = { onConfigUpdate(config.copy(ttsModelPath = it)) },
                                        label = { Text("TTS 모델 파일 (.onnx)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    IconButton(onClick = {
                                        val dialog = FileDialog(null as Frame?, "ONNX 모델 선택", FileDialog.LOAD)
                                        dialog.isVisible = true
                                        dialog.file?.let { onConfigUpdate(config.copy(ttsModelPath = File(dialog.directory, it).absolutePath)) }
                                    }) { Icon(Icons.Default.FolderOpen, null) }
                                }
                                
                                Spacer(modifier = Modifier.height(6.dp))

                                // Lexicon Path
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = config.ttsLexiconPath,
                                        onValueChange = { onConfigUpdate(config.copy(ttsLexiconPath = it)) },
                                        label = { Text("Lexicon 파일 (.txt)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    IconButton(onClick = {
                                        val dialog = FileDialog(null as Frame?, "Lexicon 파일 선택", FileDialog.LOAD)
                                        dialog.isVisible = true
                                        dialog.file?.let { onConfigUpdate(config.copy(ttsLexiconPath = File(dialog.directory, it).absolutePath)) }
                                    }) { Icon(Icons.Default.FolderOpen, null) }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Tokens Path
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = config.ttsTokensPath,
                                        onValueChange = { onConfigUpdate(config.copy(ttsTokensPath = it)) },
                                        label = { Text("Tokens 파일 (.txt)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    IconButton(onClick = {
                                        val dialog = FileDialog(null as Frame?, "Tokens 파일 선택", FileDialog.LOAD)
                                        dialog.isVisible = true
                                        dialog.file?.let { onConfigUpdate(config.copy(ttsTokensPath = File(dialog.directory, it).absolutePath)) }
                                    }) { Icon(Icons.Default.FolderOpen, null) }
                                }
                            } else {
                                // Qwen3 Settings
                                Text("Qwen3-TTS CLI 설정", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))

                                // Binary Path
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = config.qwen3BinaryPath,
                                        onValueChange = { onConfigUpdate(config.copy(qwen3BinaryPath = it)) },
                                        label = { Text("Qwen3 실행 파일 (Binary)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    IconButton(onClick = {
                                        val dialog = FileDialog(null as Frame?, "Qwen3 실행 파일 선택", FileDialog.LOAD)
                                        dialog.isVisible = true
                                        dialog.file?.let { onConfigUpdate(config.copy(qwen3BinaryPath = File(dialog.directory, it).absolutePath)) }
                                    }) { Icon(Icons.Default.FolderOpen, null) }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Model Directory
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = config.qwen3ModelDir,
                                        onValueChange = { onConfigUpdate(config.copy(qwen3ModelDir = it)) },
                                        label = { Text("Qwen3 모델 폴더 (GGUF)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    IconButton(onClick = {
                                        val chooser = JFileChooser()
                                        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                        val result = chooser.showOpenDialog(null)
                                        if (result == JFileChooser.APPROVE_OPTION) {
                                            onConfigUpdate(config.copy(qwen3ModelDir = chooser.selectedFile.absolutePath))
                                        }
                                    }) { Icon(Icons.Default.FolderOpen, null) }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Reference Audio
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = config.ttsReferenceAudioPath,
                                        onValueChange = { onConfigUpdate(config.copy(ttsReferenceAudioPath = it)) },
                                        label = { Text("참조 오디오 (목소리 복제 WAV)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    IconButton(onClick = {
                                        val dialog = FileDialog(null as Frame?, "참조 오디오 (.wav) 선택", FileDialog.LOAD)
                                        dialog.isVisible = true
                                        dialog.file?.let { onConfigUpdate(config.copy(ttsReferenceAudioPath = File(dialog.directory, it).absolutePath)) }
                                    }) { Icon(Icons.Default.FolderOpen, null) }
                                }
                            }
                        }

                        // Common Audio Settings (Right side of settings)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text("오디오 파라미터", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            // Speed
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("속도 (Speed)", style = MaterialTheme.typography.bodySmall)
                                    Text(String.format("%.2fx", config.speed), style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                                Slider(
                                    value = config.speed,
                                    onValueChange = { onConfigUpdate(config.copy(speed = it)) },
                                    valueRange = 0.5f..2.5f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Pitch (Only Qwen3 uses this usually, but keep it common)
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("피치 (Pitch)", style = MaterialTheme.typography.bodySmall)
                                    Text(String.format("%.2fx", config.pitch), style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                                Slider(
                                    value = config.pitch,
                                    onValueChange = { onConfigUpdate(config.copy(pitch = it)) },
                                    valueRange = 0.5f..2.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (activeTab == 1) {
                                // Qwen3 Advanced Slider Options
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Steps
                                    OutlinedTextField(
                                        value = config.steps.toString(),
                                        onValueChange = { input ->
                                            input.toIntOrNull()?.let { onConfigUpdate(config.copy(steps = it)) }
                                        },
                                        label = { Text("Steps") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    // GPU Layers
                                    OutlinedTextField(
                                        value = config.gpuLayers.toString(),
                                        onValueChange = { input ->
                                            input.toIntOrNull()?.let { onConfigUpdate(config.copy(gpuLayers = it)) }
                                        },
                                        label = { Text("GPU Layers") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
