package com.remoteclaude.plugin.terminal

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color
import java.awt.Font

/**
 * Converts IntelliJ editor markup (colors, bold, italic) into ANSI escape codes
 * for rendering in xterm.js on the Android client.
 */
object AnsiColorEncoder {

    private const val ESC = "\u001b["
    private const val RESET = "\u001b[0m"

    // 16-color palette matching xterm.html theme (Dark+ / VS Code style)
    // Index 0-7: normal colors, 8-15: bright colors
    private val PALETTE = arrayOf(
        // Normal colors (30-37)
        Color(0x1e, 0x1e, 0x1e), // 0 black
        Color(0xf4, 0x47, 0x47), // 1 red
        Color(0x60, 0x8b, 0x4e), // 2 green
        Color(0xdc, 0xdc, 0xaa), // 3 yellow
        Color(0x56, 0x9c, 0xd6), // 4 blue
        Color(0xc5, 0x86, 0xc0), // 5 magenta
        Color(0x4e, 0xc9, 0xb0), // 6 cyan
        Color(0xd4, 0xd4, 0xd4), // 7 white
        // Bright colors (90-97)
        Color(0x80, 0x80, 0x80), // 8  brightBlack
        Color(0xf4, 0x47, 0x47), // 9  brightRed
        Color(0xb5, 0xce, 0xa8), // 10 brightGreen
        Color(0xdc, 0xdc, 0xaa), // 11 brightYellow
        Color(0x9c, 0xdc, 0xfe), // 12 brightBlue
        Color(0xc5, 0x86, 0xc0), // 13 brightMagenta
        Color(0x4e, 0xc9, 0xb0), // 14 brightCyan
        Color(0xff, 0xff, 0xff), // 15 brightWhite
    )

    // SGR codes for foreground: normal 30-37, bright 90-97
    private val FG_SGR = intArrayOf(30, 31, 32, 33, 34, 35, 36, 37, 90, 91, 92, 93, 94, 95, 96, 97)
    // SGR codes for background: normal 40-47, bright 100-107
    private val BG_SGR = intArrayOf(40, 41, 42, 43, 44, 45, 46, 47, 100, 101, 102, 103, 104, 105, 106, 107)

    private const val MAX_PALETTE_DISTANCE = 50.0

    /**
     * Filters editor.markupModel.allHighlighters to only those overlapping [startOffset, endOffset)
     * and having non-null textAttributes.
     */
    fun getHighlightersForRange(
        editor: Editor,
        startOffset: Int,
        endOffset: Int,
    ): List<RangeHighlighter> {
        if (startOffset >= endOffset) return emptyList()
        return editor.markupModel.allHighlighters.filter { h ->
            h.isValid &&
                h.startOffset < endOffset &&
                h.endOffset > startOffset &&
                h.getTextAttributes(editor.colorsScheme) != null
        }
    }

    /**
     * Encodes a text range [startOffset, endOffset) with ANSI color codes based on highlighters.
     *
     * @param text       Full document text
     * @param highlighters  Filtered highlighters overlapping the range
     * @param startOffset   Start of the range in document coordinates
     * @param endOffset     End of the range in document coordinates
     * @param defaultFg     The editor's default foreground color (text with this color won't get ANSI codes)
     * @return Text with embedded ANSI escape sequences
     */
    fun encode(
        text: String,
        highlighters: List<RangeHighlighter>,
        startOffset: Int,
        endOffset: Int,
        defaultFg: Color?,
    ): String {
        val rangeText = text.substring(startOffset, endOffset.coerceAtMost(text.length))

        // Fast path: no highlighters → raw text
        if (highlighters.isEmpty()) return rangeText

        // Collect boundary offsets (clamped to our range)
        val boundaries = mutableSetOf(startOffset, endOffset)
        for (h in highlighters) {
            boundaries.add(h.startOffset.coerceAtLeast(startOffset))
            boundaries.add(h.endOffset.coerceAtMost(endOffset))
        }
        val sorted = boundaries.sorted()

        val sb = StringBuilder(rangeText.length + highlighters.size * 12)
        var lastSgr = "" // track current SGR state to avoid redundant codes

        for (i in 0 until sorted.size - 1) {
            val segStart = sorted[i]
            val segEnd = sorted[i + 1]
            if (segStart >= segEnd) continue

            // Find the active highlighter with the highest layer for this segment
            var bestHighlighter: RangeHighlighter? = null
            var bestLayer = Int.MIN_VALUE
            for (h in highlighters) {
                if (h.startOffset <= segStart && h.endOffset >= segEnd && h.layer > bestLayer) {
                    bestHighlighter = h
                    bestLayer = h.layer
                }
            }

            // Build SGR for this segment
            val sgr = if (bestHighlighter != null) {
                val attrs = bestHighlighter.getTextAttributes(null) // already verified non-null
                buildSgr(attrs?.foregroundColor, attrs?.backgroundColor, attrs?.fontType ?: 0, defaultFg)
            } else {
                ""
            }

            // Emit SGR change if needed
            if (sgr != lastSgr) {
                if (lastSgr.isNotEmpty()) sb.append(RESET)
                if (sgr.isNotEmpty()) sb.append(sgr)
                lastSgr = sgr
            }

            // Emit text for this segment
            val relStart = segStart - startOffset
            val relEnd = segEnd - startOffset
            sb.append(rangeText, relStart, relEnd.coerceAtMost(rangeText.length))
        }

        // Reset at the end if we had any active styling
        if (lastSgr.isNotEmpty()) sb.append(RESET)

        return sb.toString()
    }

    /**
     * Builds an SGR escape sequence from foreground color, background color, and font type.
     * Returns empty string if no styling is needed.
     */
    private fun buildSgr(fg: Color?, bg: Color?, fontType: Int, defaultFg: Color?): String {
        val parts = mutableListOf<String>()

        if (fontType and Font.BOLD != 0) parts.add("1")
        if (fontType and Font.ITALIC != 0) parts.add("3")

        if (fg != null && fg != defaultFg) {
            parts.add(colorToAnsiFg(fg))
        }
        if (bg != null) {
            parts.add(colorToAnsiBg(bg))
        }

        if (parts.isEmpty()) return ""
        return ESC + parts.joinToString(";") + "m"
    }

    /**
     * Converts a Color to ANSI foreground SGR parameters.
     * Uses 16-color palette code if Euclidean distance < threshold, otherwise 24-bit RGB.
     */
    private fun colorToAnsiFg(color: Color): String {
        val (idx, dist) = findClosestPaletteIndex(color)
        return if (dist < MAX_PALETTE_DISTANCE) {
            FG_SGR[idx].toString()
        } else {
            "38;2;${color.red};${color.green};${color.blue}"
        }
    }

    /**
     * Converts a Color to ANSI background SGR parameters.
     */
    private fun colorToAnsiBg(color: Color): String {
        val (idx, dist) = findClosestPaletteIndex(color)
        return if (dist < MAX_PALETTE_DISTANCE) {
            BG_SGR[idx].toString()
        } else {
            "48;2;${color.red};${color.green};${color.blue}"
        }
    }

    /**
     * Finds the closest palette color index and Euclidean distance.
     */
    private fun findClosestPaletteIndex(color: Color): Pair<Int, Double> {
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in PALETTE.indices) {
            val dr = color.red - PALETTE[i].red
            val dg = color.green - PALETTE[i].green
            val db = color.blue - PALETTE[i].blue
            val dist = Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx to bestDist
    }
}
