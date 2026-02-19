package com.remoteclaude.server.ui.terminal

import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import java.awt.Color as AwtColor

/**
 * Appends ANSI-encoded terminal text to a Swing JTextPane's StyledDocument,
 * rendering SGR color/style codes and handling basic terminal control characters.
 *
 * Handles: SGR (colors, bold, italic, underline), \r\n, \r (line overwrite),
 * \n, \b (backspace), OSC sequences (stripped), other CSI sequences (stripped).
 *
 * Color palette matches the plugin's AnsiColorEncoder (VS Code Dark+ theme).
 */
class AnsiTextAppender(private val textPane: JTextPane) {

    private val doc: StyledDocument = textPane.styledDocument

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val fg: AwtColor? = null,
        val bg: AwtColor? = null,
    )

    private var style = Style()
    private var lineStartOffset = 0
    private var overwritePos = -1 // -1 = normal append mode

    private val maxChars = 200_000

    // VS Code Dark+ 16-color palette
    private val PALETTE_16 = arrayOf(
        AwtColor(0x1E, 0x1E, 0x1E), // 0  black
        AwtColor(0xF4, 0x47, 0x47), // 1  red
        AwtColor(0x60, 0x8B, 0x4E), // 2  green
        AwtColor(0xDC, 0xDC, 0xAA), // 3  yellow
        AwtColor(0x56, 0x9C, 0xD6), // 4  blue
        AwtColor(0xC5, 0x86, 0xC0), // 5  magenta
        AwtColor(0x4E, 0xC9, 0xB0), // 6  cyan
        AwtColor(0xD4, 0xD4, 0xD4), // 7  white
        AwtColor(0x80, 0x80, 0x80), // 8  bright black
        AwtColor(0xF4, 0x47, 0x47), // 9  bright red
        AwtColor(0xB5, 0xCE, 0xA8), // 10 bright green
        AwtColor(0xDC, 0xDC, 0xAA), // 11 bright yellow
        AwtColor(0x9C, 0xDC, 0xFE), // 12 bright blue
        AwtColor(0xC5, 0x86, 0xC0), // 13 bright magenta
        AwtColor(0x4E, 0xC9, 0xB0), // 14 bright cyan
        AwtColor(0xFF, 0xFF, 0xFF), // 15 bright white
    )

    /**
     * Append raw ANSI terminal data to the document.
     * Parses escape sequences for styling, handles control characters.
     * Must be called on the EDT.
     */
    fun append(data: String) {
        var i = 0
        val len = data.length

        while (i < len) {
            val ch = data[i]
            when {
                // ESC [ = CSI sequence
                ch == '\u001b' && i + 1 < len && data[i + 1] == '[' -> {
                    i += 2
                    val seqStart = i
                    while (i < len && data[i] in '\u0020'..'\u003F') i++
                    if (i < len) {
                        val cmd = data[i]
                        val params = data.substring(seqStart, i)
                        i++
                        when (cmd) {
                            'm' -> style = applySgr(params, style)
                            'J' -> handleEraseDisplay(params)
                            'K' -> handleEraseLine(params)
                            // All other CSI (cursor movement, scroll, etc.) → stripped
                        }
                    }
                }
                // OSC sequence (ESC ]) → skip until BEL or ST
                ch == '\u001b' && i + 1 < len && data[i + 1] == ']' -> {
                    i += 2
                    while (i < len && data[i] != '\u0007' &&
                        !(data[i] == '\u001b' && i + 1 < len && data[i + 1] == '\\')
                    ) i++
                    if (i < len) {
                        if (data[i] == '\u0007') i++ else i += 2
                    }
                }
                // Other ESC sequences → skip
                ch == '\u001b' -> i = (i + 2).coerceAtMost(len)
                // CRLF
                ch == '\r' && i + 1 < len && data[i + 1] == '\n' -> {
                    writeText("\n")
                    lineStartOffset = writePosition()
                    overwritePos = -1
                    i += 2
                }
                // Standalone LF
                ch == '\n' -> {
                    writeText("\n")
                    lineStartOffset = writePosition()
                    overwritePos = -1
                    i++
                }
                // Standalone CR → overwrite current line
                ch == '\r' -> {
                    overwritePos = lineStartOffset
                    i++
                }
                // Backspace
                ch == '\b' -> {
                    if (overwritePos >= 0 && overwritePos > lineStartOffset) {
                        overwritePos--
                    } else if (overwritePos < 0 && doc.length > lineStartOffset) {
                        doc.remove(doc.length - 1, 1)
                    }
                    i++
                }
                // BEL (bell) → ignore
                ch == '\u0007' -> i++
                // Regular text → batch consecutive printable chars
                else -> {
                    val textStart = i
                    while (i < len) {
                        val c = data[i]
                        if (c == '\u001b' || c == '\r' || c == '\n' || c == '\b' || c == '\u0007') break
                        i++
                    }
                    writeText(data.substring(textStart, i))
                }
            }
        }

        trimIfNeeded()
        scrollToBottom()
    }

    fun clear() {
        doc.remove(0, doc.length)
        lineStartOffset = 0
        overwritePos = -1
        style = Style()
    }

    // ── Document manipulation ─────────────────────────────────────────────

    private fun writePosition(): Int =
        if (overwritePos >= 0) overwritePos else doc.length

    private fun writeText(text: String) {
        val attrs = styleToAttributes()
        if (overwritePos >= 0) {
            // Overwrite mode: replace existing chars on the line
            val endPos = (overwritePos + text.length).coerceAtMost(doc.length)
            val removeLen = endPos - overwritePos
            if (removeLen > 0) {
                doc.remove(overwritePos, removeLen)
            }
            doc.insertString(overwritePos, text, attrs)
            overwritePos += text.length
        } else {
            // Append mode
            doc.insertString(doc.length, text, attrs)
        }
    }

    private fun handleEraseDisplay(params: String) {
        when (params) {
            "", "0" -> {
                // Erase from cursor to end — approximate: erase from current position to end
                val pos = writePosition()
                if (pos < doc.length) {
                    doc.remove(pos, doc.length - pos)
                }
            }
            "2", "3" -> {
                // Erase entire screen
                clear()
            }
        }
    }

    private fun handleEraseLine(params: String) {
        when (params) {
            "", "0" -> {
                // Erase from cursor to end of line
                val pos = writePosition()
                val text = doc.getText(pos, doc.length - pos)
                val nlIdx = text.indexOf('\n')
                val eraseLen = if (nlIdx >= 0) nlIdx else text.length
                if (eraseLen > 0) {
                    doc.remove(pos, eraseLen)
                }
            }
            "2" -> {
                // Erase entire line
                val pos = lineStartOffset
                val text = doc.getText(pos, doc.length - pos)
                val nlIdx = text.indexOf('\n')
                val eraseLen = if (nlIdx >= 0) nlIdx else text.length
                if (eraseLen > 0) {
                    doc.remove(pos, eraseLen)
                }
                overwritePos = lineStartOffset
            }
        }
    }

    private fun trimIfNeeded() {
        if (doc.length > maxChars) {
            val excess = doc.length - maxChars
            doc.remove(0, excess)
            lineStartOffset = (lineStartOffset - excess).coerceAtLeast(0)
            if (overwritePos >= 0) {
                overwritePos = (overwritePos - excess).coerceAtLeast(0)
            }
        }
    }

    private fun scrollToBottom() {
        textPane.caretPosition = doc.length
    }

    // ── SGR parsing ───────────────────────────────────────────────────────

    private fun styleToAttributes(): SimpleAttributeSet {
        val attrs = SimpleAttributeSet()
        style.fg?.let { StyleConstants.setForeground(attrs, it) }
        style.bg?.let { StyleConstants.setBackground(attrs, it) }
        if (style.bold) StyleConstants.setBold(attrs, true)
        if (style.italic) StyleConstants.setItalic(attrs, true)
        if (style.underline) StyleConstants.setUnderline(attrs, true)
        return attrs
    }

    private fun applySgr(params: String, current: Style): Style {
        if (params.isEmpty()) return Style() // ESC[m = reset

        val codes = params.split(';').mapNotNull { it.toIntOrNull() }
        if (codes.isEmpty()) return Style()

        var s = current
        var idx = 0
        while (idx < codes.size) {
            when (val code = codes[idx]) {
                0 -> s = Style()
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(bold = false) // dim → treat as not bold
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> { // Reverse video
                    val fg = s.fg ?: AwtColor(0xD4, 0xD4, 0xD4)
                    val bg = s.bg ?: AwtColor(0x1E, 0x1E, 0x1E)
                    s = s.copy(fg = bg, bg = fg)
                }
                22 -> s = s.copy(bold = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> { // Reverse off — just reset to no swap
                    s = s.copy(fg = s.fg, bg = s.bg)
                }
                in 30..37 -> s = s.copy(fg = PALETTE_16[code - 30])
                39 -> s = s.copy(fg = null)
                in 40..47 -> s = s.copy(bg = PALETTE_16[code - 40])
                49 -> s = s.copy(bg = null)
                in 90..97 -> s = s.copy(fg = PALETTE_16[code - 90 + 8])
                in 100..107 -> s = s.copy(bg = PALETTE_16[code - 100 + 8])
                38 -> {
                    val (color, skip) = parseExtendedColor(codes, idx + 1)
                    if (color != null) s = s.copy(fg = color)
                    idx += skip
                }
                48 -> {
                    val (color, skip) = parseExtendedColor(codes, idx + 1)
                    if (color != null) s = s.copy(bg = color)
                    idx += skip
                }
            }
            idx++
        }
        return s
    }

    private fun parseExtendedColor(codes: List<Int>, start: Int): Pair<AwtColor?, Int> {
        if (start >= codes.size) return null to 0
        return when (codes[start]) {
            5 -> {
                if (start + 1 < codes.size) {
                    color256(codes[start + 1]) to 2
                } else null to 1
            }
            2 -> {
                if (start + 3 < codes.size) {
                    val r = codes[start + 1].coerceIn(0, 255)
                    val g = codes[start + 2].coerceIn(0, 255)
                    val b = codes[start + 3].coerceIn(0, 255)
                    AwtColor(r, g, b) to 4
                } else null to 1
            }
            else -> null to 0
        }
    }

    private fun color256(n: Int): AwtColor {
        return when {
            n < 16 -> PALETTE_16[n]
            n < 232 -> {
                val idx = n - 16
                val r = (idx / 36) * 51
                val g = ((idx % 36) / 6) * 51
                val b = (idx % 6) * 51
                AwtColor(r, g, b)
            }
            else -> {
                val gray = 8 + (n - 232) * 10
                AwtColor(gray, gray, gray)
            }
        }
    }
}
