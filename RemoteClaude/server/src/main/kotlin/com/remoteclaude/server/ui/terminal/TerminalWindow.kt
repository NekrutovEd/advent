package com.remoteclaude.server.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.remoteclaude.server.net.MessageRouter
import com.remoteclaude.server.protocol.GlobalTabInfo
import com.remoteclaude.server.state.GlobalTabRegistry
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import java.awt.Color as AwtColor
import java.awt.Font as AwtFont

private val TERM_BG = Color(0xFF1A1A1A)
private val INPUT_BG = Color(0xFF252525)
private val INPUT_TEXT = Color(0xFFD4D4D4)
private val PROMPT_COLOR = Color(0xFF4EC9B0)
private val CTRL_BTN_BG = Color(0xFF333333)
private val CTRL_BTN_TEXT = Color(0xFFCCCCCC)
private val MONO = FontFamily.Monospace

@Composable
fun TerminalWindow(
    tab: GlobalTabInfo,
    tabRegistry: GlobalTabRegistry,
    router: MessageRouter,
    onClose: () -> Unit,
) {
    val windowState = rememberWindowState(width = 900.dp, height = 600.dp)
    val scope = rememberCoroutineScope()

    // Create Swing terminal components (created on EDT during composition)
    val textPane = remember {
        object : JTextPane() {
            // Force text wrapping to viewport width
            override fun getScrollableTracksViewportWidth() = true
        }.apply {
            isEditable = false
            background = AwtColor(0x1A, 0x1A, 0x1A)
            foreground = AwtColor(0xD4, 0xD4, 0xD4)
            caretColor = AwtColor(0xD4, 0xD4, 0xD4)
            font = resolveMonoFont()
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        }
    }
    val appender = remember { AnsiTextAppender(textPane) }
    val swingPanel = remember {
        val scrollPane = JScrollPane(textPane).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            viewport.background = AwtColor(0x1A, 0x1A, 0x1A)
        }
        JPanel(BorderLayout()).apply {
            background = AwtColor(0x1A, 0x1A, 0x1A)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    // Load initial buffer + collect live output
    LaunchedEffect(tab.id) {
        val snapshot = tabRegistry.getBuffer(tab.id)?.getSnapshot() ?: ""
        if (snapshot.isNotEmpty()) {
            SwingUtilities.invokeLater { appender.append(snapshot) }
        }

        tabRegistry.outputFlow
            .filter { (tabId, _) -> tabId == tab.id }
            .collect { (_, data) ->
                SwingUtilities.invokeLater { appender.append(data) }
            }
    }

    // Detect tab removal
    val currentTabs by tabRegistry.tabsFlow.collectAsState()
    val tabExists = currentTabs.any { it.id == tab.id }

    // Helper to send input
    fun sendInput(data: String) {
        scope.launch { router.sendInputToTab(tab.id, data) }
    }

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "${tab.title} - ${tab.pluginName}",
    ) {
        Column(modifier = Modifier.fillMaxSize().background(TERM_BG)) {
            // Terminal output (Swing JTextPane)
            SwingPanel(
                factory = { swingPanel },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // Tab removed overlay
            if (!tabExists) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Terminal session closed",
                        color = Color(0xFFEF9A9A),
                        fontFamily = MONO,
                        fontSize = 13.sp,
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)

            // Input bar
            var inputText by remember { mutableStateOf("") }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(INPUT_BG)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("> ", color = PROMPT_COLOR, fontFamily = MONO, fontSize = 13.sp)
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                        fontFamily = MONO,
                        fontSize = 13.sp,
                        color = INPUT_TEXT,
                    ),
                    cursorBrush = SolidColor(INPUT_TEXT),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.Enter -> {
                                        sendInput(inputText + "\r")
                                        inputText = ""
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        sendInput("\u001b[A")
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        sendInput("\u001b[B")
                                        true
                                    }
                                    Key.Escape -> {
                                        sendInput("\u001b")
                                        true
                                    }
                                    Key.Tab -> {
                                        sendInput("\t")
                                        true
                                    }
                                    else -> {
                                        // Ctrl+C
                                        if (event.isCtrlPressed && event.key == Key.C) {
                                            sendInput("\u0003")
                                            true
                                        } else false
                                    }
                                }
                            } else false
                        },
                )
            }

            // Control bar with special keys
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton("Esc") { sendInput("\u001b") }
                ControlButton("Ctrl+C") { sendInput("\u0003") }
                ControlButton("Tab") { sendInput("\t") }
                Spacer(Modifier.weight(1f))
                ControlButton("\u2191") { sendInput("\u001b[A") }   // Up arrow
                ControlButton("\u2193") { sendInput("\u001b[B") }   // Down arrow
                ControlButton("\u21B5") { sendInput("\r") }          // Enter
            }
        }
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(CTRL_BTN_BG)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = CTRL_BTN_TEXT,
            fontFamily = MONO,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun resolveMonoFont(): AwtFont {
    val available = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .availableFontFamilyNames.toSet()
    val family = listOf("Cascadia Code", "Cascadia Mono", "Consolas", "Courier New")
        .firstOrNull { it in available } ?: AwtFont.MONOSPACED
    return AwtFont(family, AwtFont.PLAIN, 14)
}
