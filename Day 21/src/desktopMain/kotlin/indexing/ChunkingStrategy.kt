package indexing

/**
 * Metadata attached to each document chunk.
 */
data class ChunkMetadata(
    val chunkId: String,
    val source: String,       // file path
    val title: String,        // file name or document title
    val section: String,      // section heading or "chunk N"
    val strategy: String,     // "fixed" or "structural"
    val charOffset: Int,      // offset in the original document
    val chunkIndex: Int       // sequential index within the document
)

/**
 * A single chunk of text with its metadata.
 */
data class DocumentChunk(
    val text: String,
    val metadata: ChunkMetadata
)

/**
 * Document loaded from disk.
 */
data class LoadedDocument(
    val path: String,
    val name: String,
    val content: String,
    val extension: String
)

/**
 * Two chunking strategies: fixed-size and structural.
 */
object ChunkingStrategy {

    // ───── Strategy 1: Fixed-size chunks with overlap ─────

    fun chunkFixed(
        doc: LoadedDocument,
        chunkSize: Int = 500,
        overlap: Int = 100
    ): List<DocumentChunk> {
        val text = doc.content
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<DocumentChunk>()
        var offset = 0
        var index = 0

        while (offset < text.length) {
            val end = (offset + chunkSize).coerceAtMost(text.length)
            var chunkText = text.substring(offset, end)

            // Try to break at a word/line boundary (look back up to 50 chars)
            if (end < text.length) {
                val lastBreak = chunkText.lastIndexOfAny(charArrayOf('\n', '.', ' '), chunkText.length - 1)
                if (lastBreak > chunkSize / 2) {
                    chunkText = chunkText.substring(0, lastBreak + 1)
                }
            }

            if (chunkText.isNotBlank()) {
                chunks.add(DocumentChunk(
                    text = chunkText.trim(),
                    metadata = ChunkMetadata(
                        chunkId = "${doc.name}:fixed:$index",
                        source = doc.path,
                        title = doc.name,
                        section = "chunk $index",
                        strategy = "fixed",
                        charOffset = offset,
                        chunkIndex = index
                    )
                ))
                index++
            }

            val advance = chunkText.length - overlap
            offset += if (advance > 0) advance else chunkText.length
        }

        return chunks
    }

    // ───── Strategy 2: Structural chunking (headings / functions / paragraphs) ─────

    fun chunkStructural(doc: LoadedDocument): List<DocumentChunk> {
        return when (doc.extension) {
            "md", "txt" -> chunkByHeadings(doc)
            "kt", "java", "py", "js", "ts" -> chunkByCodeStructure(doc)
            else -> chunkByParagraphs(doc)
        }
    }

    /**
     * Markdown/text: split by headings (# ## ###).
     * Each heading starts a new section. Content before the first heading → "preamble".
     */
    private fun chunkByHeadings(doc: LoadedDocument): List<DocumentChunk> {
        val lines = doc.content.lines()
        val sections = mutableListOf<Pair<String, StringBuilder>>() // (heading, content)
        var currentHeading = "preamble"
        var currentContent = StringBuilder()

        for (line in lines) {
            val headingMatch = HEADING_RE.matchEntire(line)
            if (headingMatch != null) {
                // Save previous section
                if (currentContent.isNotBlank()) {
                    sections.add(currentHeading to currentContent)
                }
                currentHeading = headingMatch.groupValues[2].trim()
                currentContent = StringBuilder()
                currentContent.appendLine(line)
            } else {
                currentContent.appendLine(line)
            }
        }
        // Last section
        if (currentContent.isNotBlank()) {
            sections.add(currentHeading to currentContent)
        }

        return sections.mapIndexed { index, (heading, content) ->
            DocumentChunk(
                text = content.toString().trim(),
                metadata = ChunkMetadata(
                    chunkId = "${doc.name}:structural:$index",
                    source = doc.path,
                    title = doc.name,
                    section = heading,
                    strategy = "structural",
                    charOffset = doc.content.indexOf(content.toString().trim()).coerceAtLeast(0),
                    chunkIndex = index
                )
            )
        }.filter { it.text.isNotBlank() }
    }

    /**
     * Code files: split by top-level declarations (fun, class, object, interface).
     * Imports/package → "header". Each declaration → its own chunk.
     */
    private fun chunkByCodeStructure(doc: LoadedDocument): List<DocumentChunk> {
        val lines = doc.content.lines()
        val sections = mutableListOf<Pair<String, StringBuilder>>()
        var currentSection = "header"
        var currentContent = StringBuilder()
        var braceDepth = 0
        var inDeclaration = false

        for (line in lines) {
            val trimmed = line.trimStart()
            val isTopLevelDecl = braceDepth == 0 && DECL_RE.containsMatchIn(trimmed)

            if (isTopLevelDecl && inDeclaration) {
                // Save previous declaration
                if (currentContent.isNotBlank()) {
                    sections.add(currentSection to currentContent)
                }
                currentSection = trimmed.take(80).trim()
                currentContent = StringBuilder()
                inDeclaration = true
            } else if (isTopLevelDecl && !inDeclaration) {
                // Save header
                if (currentContent.isNotBlank()) {
                    sections.add(currentSection to currentContent)
                }
                currentSection = trimmed.take(80).trim()
                currentContent = StringBuilder()
                inDeclaration = true
            }

            currentContent.appendLine(line)
            braceDepth += line.count { it == '{' } - line.count { it == '}' }
            if (braceDepth < 0) braceDepth = 0
        }

        if (currentContent.isNotBlank()) {
            sections.add(currentSection to currentContent)
        }

        return sections.mapIndexed { index, (section, content) ->
            DocumentChunk(
                text = content.toString().trim(),
                metadata = ChunkMetadata(
                    chunkId = "${doc.name}:structural:$index",
                    source = doc.path,
                    title = doc.name,
                    section = section,
                    strategy = "structural",
                    charOffset = doc.content.indexOf(content.toString().trim()).coerceAtLeast(0),
                    chunkIndex = index
                )
            )
        }.filter { it.text.isNotBlank() }
    }

    /**
     * Generic: split by double-newline paragraphs.
     */
    private fun chunkByParagraphs(doc: LoadedDocument): List<DocumentChunk> {
        val paragraphs = doc.content.split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return paragraphs.mapIndexed { index, text ->
            DocumentChunk(
                text = text,
                metadata = ChunkMetadata(
                    chunkId = "${doc.name}:structural:$index",
                    source = doc.path,
                    title = doc.name,
                    section = "paragraph $index",
                    strategy = "structural",
                    charOffset = doc.content.indexOf(text).coerceAtLeast(0),
                    chunkIndex = index
                )
            )
        }
    }

    private val HEADING_RE = Regex("""^(#{1,6})\s+(.+)$""")
    private val DECL_RE = Regex("""^(fun |class |object |interface |data class |sealed |enum |abstract |private fun |internal fun |override fun |suspend fun )""")
}
