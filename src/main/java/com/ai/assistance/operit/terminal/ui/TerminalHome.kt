package com.ai.assistance.operit.terminal.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import com.ai.assistance.operit.terminal.view.canvas.RenderConfig
import com.ai.assistance.operit.terminal.view.canvas.TerminalTabRenderItem
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.operit.terminal.TerminalEnv
import com.ai.assistance.operit.terminal.utils.TerminalFontConfigManager
import com.ai.assistance.operit.terminal.utils.VirtualKeyAction
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardButtonConfig
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardConfigManager
import com.ai.assistance.operit.terminal.view.SyntaxColors
import com.ai.assistance.operit.terminal.view.SyntaxHighlightingVisualTransformation
import com.ai.assistance.operit.terminal.view.highlight
import androidx.compose.material.icons.filled.Settings
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalHome(
    env: TerminalEnv,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val context = LocalContext.current
    val fontConfigManager = remember { TerminalFontConfigManager.getInstance(context) }
    val virtualKeyboardConfigManager = remember { VirtualKeyboardConfigManager.getInstance(context) }
    
    // 字体配置状态
    var fontConfig by remember { 
        mutableStateOf(fontConfigManager.loadRenderConfig())
    }
    var virtualKeyboardLayout by remember {
        mutableStateOf(virtualKeyboardConfigManager.loadLayout())
    }
    
    // 监听字体配置变化（当从设置界面返回时）
    LaunchedEffect(Unit) {
        // 每次进入时重新读取配置
        fontConfig = fontConfigManager.loadRenderConfig()
    }
    
    // 当组件重新组合时，检查配置是否变化并更新
    DisposableEffect(Unit) {
        val newConfig = fontConfigManager.loadRenderConfig()
        
        if (fontConfig != newConfig) {
            fontConfig = newConfig
        }
        
        onDispose { }
    }

    DisposableEffect(context, virtualKeyboardConfigManager) {
        val settingsPrefs =
            context.getSharedPreferences(VirtualKeyboardConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == VirtualKeyboardConfigManager.PREF_KEY_VIRTUAL_KEYBOARD_LAYOUT) {
                virtualKeyboardLayout = virtualKeyboardConfigManager.loadLayout()
            }
        }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    
    // 语法高亮
    val visualTransformation = remember { SyntaxHighlightingVisualTransformation() }

    // 缩放状态
    var scaleFactor by remember { mutableStateOf(1f) }

    // 删除确认弹窗状态
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }
    
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    fun applyCtrlModifierChar(ch: Char): String? {
        return when {
            ch in 'a'..'z' || ch in 'A'..'Z' -> {
                val code = ch.uppercaseChar().code - 64
                code.toChar().toString()
            }
            ch == ' ' || ch == '@' -> "\u0000"
            ch == '[' -> "\u001b"
            ch == '\\' -> "\u001c"
            ch == ']' -> "\u001d"
            ch == '^' -> "\u001e"
            ch == '_' -> "\u001f"
            else -> null
        }
    }

    fun applyCtrlModifierToText(input: String): String {
        if (!ctrlActive) return input
        val sb = StringBuilder()
        input.forEach { ch ->
            val mapped = applyCtrlModifierChar(ch)
            if (mapped != null) sb.append(mapped) else sb.append(ch)
        }
        return sb.toString()
    }

    fun applyModifiers(input: String): String {
        var output = applyCtrlModifierToText(input)
        if (altActive) {
            output = if (output.startsWith("\u001b")) output else "\u001b$output"
        }
        return output
    }

    fun consumeModifiers() {
        if (ctrlActive) ctrlActive = false
        if (altActive) altActive = false
    }

    fun decodeVirtualKeyValue(rawValue: String): String {
        val output = StringBuilder()
        var index = 0

        while (index < rawValue.length) {
            val char = rawValue[index]
            if (char == '\\' && index + 1 < rawValue.length) {
                val next = rawValue[index + 1]
                when (next) {
                    'e' -> output.append('\u001b')
                    't' -> output.append('\t')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    '\\' -> output.append('\\')
                    else -> {
                        output.append('\\')
                        output.append(next)
                    }
                }
                index += 2
                continue
            }

            output.append(char)
            index++
        }

        return output.toString()
    }

    fun sendDirectInput(input: String) {
        val output = if (ctrlActive || altActive) applyModifiers(input) else input
        env.onSendInput(output, false)
        if (ctrlActive || altActive) {
            consumeModifiers()
        }
    }

    // 计算基于缩放因子的字体大小和间距
    val baseFontSize = 14.sp
    val fontSize = with(LocalDensity.current) {
        (baseFontSize.toPx() * scaleFactor).toSp()
    }
    val basePadding = 8.dp
    val padding = basePadding * scaleFactor

    // 获取当前 session 的 PTY
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }
    val tabItems = remember(env.sessions) {
        val closable = env.sessions.size > 1
        env.sessions.map { session ->
            TerminalTabRenderItem(
                id = session.id,
                title = session.title,
                canClose = closable
            )
        }
    }
    val onTabCloseRequest: (String) -> Unit = { sessionId ->
        if (env.sessions.size > 1) {
            sessionToDelete = sessionId
            showDeleteConfirmDialog = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (env.isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // 终端输出区域
                CanvasTerminalScreen(
                    emulator = env.terminalEmulator,
                    modifier = Modifier.weight(1f),
                    config = fontConfig,
                    pty = currentPty,
                    imeAnimationOffsetPx = 0,
                    committedImeBottomInsetPx = 0,
                    onInput = { sendDirectInput(it) },
                    sessionId = env.currentSessionId,
                    onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                    getScrollOffset = { id -> env.getScrollOffset(id) },
                    tabs = tabItems,
                    currentTabId = env.currentSessionId,
                    onTabClick = env::onSwitchSession,
                    onTabClose = onTabCloseRequest,
                    onNewTab = env::onNewSession
                )

                Box {
                    VirtualKeyboard(
                        onKeyPress = { key -> sendDirectInput(decodeVirtualKeyValue(key)) },
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleAlt = { altActive = !altActive },
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        keyRows = virtualKeyboardLayout.rows,
                        fontSize = fontSize * 0.7f,
                        padding = padding * 0.5f
                    )
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
            ) {
                // 非全屏模式下也直接在终端画布里输入，避免底部输入框导致界面整体被顶起
                CanvasTerminalScreen(
                    emulator = env.terminalEmulator,
                    modifier = Modifier.weight(1f),
                    config = fontConfig,
                    pty = currentPty,
                    imeAnimationOffsetPx = 0,
                    committedImeBottomInsetPx = 0,
                    onInput = { sendDirectInput(it) },
                    sessionId = env.currentSessionId,
                    onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                    getScrollOffset = { id -> env.getScrollOffset(id) },
                    tabs = tabItems,
                    currentTabId = env.currentSessionId,
                    onTabClick = env::onSwitchSession,
                    onTabClose = onTabCloseRequest,
                    onNewTab = env::onNewSession
                )

                Column {
                    TerminalToolbar(
                        onInterrupt = env::onInterrupt,
                        fontSize = fontSize * 0.8f,
                        padding = padding,
                        onNavigateToSetup = onNavigateToSetup,
                        onNavigateToSettings = onNavigateToSettings,
                        onOpenWorkspace = onOpenWorkspace
                    )

                    VirtualKeyboard(
                        onKeyPress = { key -> sendDirectInput(decodeVirtualKeyValue(key)) },
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleAlt = { altActive = !altActive },
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        keyRows = virtualKeyboardLayout.rows,
                        fontSize = fontSize * 0.7f,
                        padding = padding * 0.5f
                    )
                }
            }
        }
    }

    // 删除确认弹窗
    if (showDeleteConfirmDialog && sessionToDelete != null) {
        val context = LocalContext.current
        val sessionTitle = env.sessions.find { it.id == sessionToDelete }?.title ?: context.getString(com.ai.assistance.operit.terminal.R.string.unknown_session)

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                sessionToDelete = null
            },
            title = {
                Text(
                    text = context.getString(com.ai.assistance.operit.terminal.R.string.confirm_delete_session),
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = context.getString(com.ai.assistance.operit.terminal.R.string.delete_session_message, sessionTitle),
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { sessionId ->
                            env.onCloseSession(sessionId)
                        }
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.delete),
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.cancel),
                        color = Color.White
                    )
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }
}

@Composable
private fun TerminalToolbar(
    onInterrupt: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(padding * 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ctrl+C 中断按钮
            Surface(
                modifier = Modifier.clickable { onInterrupt() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(padding * 0.3f)
                ) {
                    Text(
                        text = "Ctrl+C",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.interrupt),
                        color = Color.Gray,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 0.9f
                    )
                }
            }

            // 分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(padding * 1.5f)
                    .background(Color(0xFF3A3A3A))
            )

            Spacer(Modifier.weight(1f))

            // 环境配置按钮
            Surface(
                modifier = Modifier.clickable { onNavigateToSetup() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.environment_setup),
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Workspace 快捷入口
            Surface(
                modifier = Modifier.clickable { onOpenWorkspace() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Workspace",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                        .size(padding * 1.8f)
                )
            }

            // 设置按钮
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.settings),
                tint = Color.Gray,
                modifier = Modifier
                    .clickable { onNavigateToSettings() }
                    .padding(start = padding)
                    .size(padding * 2.5f)
            )
        }
    }
}

@Composable
private fun VirtualKeyboard(
    onKeyPress: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    keyRows: List<List<VirtualKeyboardButtonConfig>>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            verticalArrangement = Arrangement.spacedBy(padding * 0.5f)
        ) {
            keyRows.forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
                ) {
                    rowKeys.forEach { keyConfig ->
                        val isActive = when (keyConfig.action) {
                            VirtualKeyAction.TOGGLE_CTRL -> ctrlActive
                            VirtualKeyAction.TOGGLE_ALT -> altActive
                            VirtualKeyAction.SEND_TEXT -> false
                        }
                        val clickOverride = when (keyConfig.action) {
                            VirtualKeyAction.TOGGLE_CTRL -> onToggleCtrl
                            VirtualKeyAction.TOGGLE_ALT -> onToggleAlt
                            VirtualKeyAction.SEND_TEXT -> null
                        }

                        KeyButton(
                            label = keyConfig.label,
                            key = keyConfig.value,
                            fontSize = fontSize,
                            padding = padding,
                            onKeyPress = onKeyPress,
                            modifier = Modifier.weight(1f),
                            isActive = isActive,
                            onClickOverride = clickOverride
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    key: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onClickOverride: (() -> Unit)? = null
) {
    val backgroundColor = if (isActive) Color(0xFF2563EB) else Color(0xFF3A3A3A)
    Surface(
        modifier = modifier
            .clickable { onClickOverride?.invoke() ?: onKeyPress(key) },
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = padding * 0.5f, vertical = padding * 0.8f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
} 
