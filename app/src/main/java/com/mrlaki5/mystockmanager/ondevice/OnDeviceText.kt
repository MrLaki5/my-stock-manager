package com.mrlaki5.mystockmanager.ondevice

/** Prompts for the on-device captioner, and the cleanup and keyword merge around its answers. */
object OnDeviceText {

    const val TITLE_PROMPT = "Now write a short title for this photo, at most 10 words. Return only the title."

    // Short and literal: a 450M model follows one plain instruction far better than a style guide.
    fun descriptionPrompt(hint: String?): String = buildString {
        append("Describe what is visible in this photo in one factual sentence. Return only the sentence.")
        if (!hint.isNullOrBlank()) append(" Context from the photographer: ${hint.trim()}.")
    }

    /** The first sentence of the answer, without labels or quotes. */
    fun sentence(raw: String): String {
        val line = clean(raw, "description")
        val end = Regex("""[.!?](\s|$)""").find(line)?.range?.first
        val first = if (end != null) line.substring(0, end + 1) else line
        return if (first.isEmpty() || first.last() in ".!?") first else "$first."
    }

    /** The model's title, or one cut from the description when it gave none that fits. */
    fun title(raw: String, description: String, maxLength: Int): String {
        val title = clean(raw, "title").trimEnd('.', '!', ' ')
        if (title.isNotEmpty() && title.length <= maxLength && !title.contains('\n')) return title
        val fromDescription = description.trimEnd('.', '!', '?', ' ')
            .replaceFirst(Regex("""^(a|an|the)\s+""", RegexOption.IGNORE_CASE), "")
            .replaceFirstChar { it.uppercase() }
        return cutAtWord(fromDescription, maxLength)
    }

    /** Hint terms first, then words the captioner used, then the tagger's ranking. */
    fun keywords(hint: String?, description: String, title: String, tags: KeywordTagger.Tags): List<String> {
        val out = LinkedHashMap<String, String>()
        fun add(term: String) {
            val t = term.trim().lowercase()
            if (t.length >= 3 && t !in STOPWORDS) out.putIfAbsent(t.removeSuffix("s"), t)
        }

        hint?.split(',', ';')?.forEach { part ->
            if (part.trim().split(Regex("\\s+")).size <= 3) add(part)
            words(part).forEach(::add)
        }

        // Caption words need the tagger to agree too, since the captioner sometimes invents a setting.
        val captionWords = words("$title $description")
        (captionWords + captionWords.zipWithNext { a, b -> "$a $b" })
            .filter { it in tags.vocabulary && (tags.scores[it] ?: Float.NEGATIVE_INFINITY) >= CAPTION_MIN_Z }
            .distinct()
            .sortedByDescending { tags.scores[it] }
            .forEach(::add)

        // At most a few terms per shared word, so one subject cannot fill the list with "dog x" variants.
        val uses = HashMap<String, Int>()
        out.values.forEach { term -> term.split(' ').forEach { uses.merge(it, 1, Int::plus) } }
        // A dog scores high on dozens of breeds; the top ones are usually right and the rest crowd out the scene.
        var breeds = out.values.count { it in tags.breeds }
        for (term in tags.ranked) {
            if (out.size >= TARGET_KEYWORDS) break
            val parts = term.split(' ')
            if (parts.any { (uses[it] ?: 0) >= MAX_PER_WORD }) continue
            val breed = term in tags.breeds
            if (breed && breeds >= MAX_BREEDS) continue
            val before = out.size
            add(term)
            if (out.size > before) {
                parts.forEach { uses.merge(it, 1, Int::plus) }
                if (breed) breeds++
            }
        }
        return out.values.toList()
    }

    private fun clean(raw: String, label: String): String = raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
        .replaceFirst(Regex("""^$label\s*:\s*""", RegexOption.IGNORE_CASE), "")
        .trim('"', '\'', '*', ' ')

    private fun cutAtWord(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        val cut = text.substring(0, maxLength + 1).substringBeforeLast(' ')
        return cut.trimEnd(',', ' ').ifEmpty { text.take(maxLength) }
    }

    private fun words(text: String): List<String> =
        Regex("[a-z]+").findAll(text.lowercase()).map { it.value }.toList()

    // Enough for agencies to rank well without padding past what the photo supports.
    private const val TARGET_KEYWORDS = 20
    private const val MAX_PER_WORD = 3
    private const val MAX_BREEDS = 2
    private const val CAPTION_MIN_Z = 1.5f

    private val STOPWORDS = setOf(
        "the", "and", "with", "for", "from", "into", "onto", "over", "under", "near", "its", "his", "her", "their",
        "this", "that", "these", "those", "are", "was", "were", "has", "have", "while", "who", "which", "there",
        "some", "front", "photo", "image", "picture", "visible", "background", "foreground",
    )
}
