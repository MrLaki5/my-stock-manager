package com.mrlaki5.mystockmanager.ondevice

import com.mrlaki5.mystockmanager.data.db.entity.MediaKind
import com.mrlaki5.mystockmanager.openai.SHUTTERSTOCK_CATEGORIES

/** Prompts for the on-device model, and the cleanup of its answers into metadata. */
object OnDeviceText {

    // The model sees one frame of a clip, so it is asked about that frame; photo wording is what was benchmarked.
    private fun MediaKind.shown(): String = when (this) {
        MediaKind.IMAGE -> "photo"
        MediaKind.VIDEO -> "video frame"
    }

    private fun MediaKind.stock(): String = when (this) {
        MediaKind.IMAGE -> "stock photo"
        MediaKind.VIDEO -> "stock video"
    }

    fun titlePrompt(kind: MediaKind): String =
        "Now write a short title for this ${kind.shown()}, at most 10 words. Return only the title."

    // Short and literal: a 450M model follows one plain instruction far better than a style guide. Asked to
    // "describe what is visible", it says "The image shows..." in a third of answers; a caption names the subject.
    fun descriptionPrompt(hint: String?, kind: MediaKind): String = buildString {
        append("Write a one-sentence ${kind.stock()} caption for this ${kind.shown()}. Return only the caption.")
        if (!hint.isNullOrBlank()) append(" Context from the photographer: ${hint.trim()}.")
    }

    /** The first sentence of the answer, without labels, quotes, talk about the photo itself or filler praise. */
    fun sentence(raw: String): String {
        val line = clean(raw, "description")
        val end = Regex("""[.!?](\s|$)""").find(line)?.range?.first
        val first = withoutFiller(withoutPhotoTalk(if (end != null) line.substring(0, end + 1) else line))
        return if (first.isEmpty() || first.last() in ".!?") first else "$first."
    }

    // "The image shows a car in the photo." says nothing a buyer needs; keep only the content.
    private fun withoutPhotoTalk(sentence: String): String = sentence
        .replaceFirst(Regex("""^(in|on) (this|the) $PHOTO,?\s+""", RegexOption.IGNORE_CASE), "")
        .replaceFirst(Regex("""^(this|the) $PHOTO (shows|features|captures|depicts|displays|contains|presents)\s+""", RegexOption.IGNORE_CASE), "")
        .replaceFirst(Regex("""^an? $PHOTO of\s+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+(in|of) (this|the) $PHOTO\b""", RegexOption.IGNORE_CASE), "")
        .replaceFirstChar { it.uppercase() }

    // "A stunning sunset" -> "A sunset"; the article is redone since the next word may start differently.
    private fun withoutFiller(sentence: String): String =
        Regex("""\b(?:(an?)\s+)?$FILLER\s+(?=([a-z]))""", RegexOption.IGNORE_CASE).replace(sentence) { match ->
            val article = match.groupValues[1]
            if (article.isEmpty()) return@replace ""
            val an = match.groupValues[3].lowercase() in "aeiou"
            val word = if (an) "an" else "a"
            (if (article[0].isUpperCase()) word.replaceFirstChar { it.uppercase() } else word) + " "
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

    // One narrow question per keyword: asked for "one more keyword", a 450M model repeats itself within a few turns.
    // Ordered by measured usefulness; the last ones only run when earlier answers were rejected.
    fun keywordQuestions(kind: MediaKind): List<String> = KEYWORD_QUESTIONS.map {
        "${it.replace("{shown}", kind.shown())} Answer with one word or a short phrase only."
    }

    private val KEYWORD_QUESTIONS = listOf(
        "What is the main subject of this {shown}?",
        "Name another object visible in this {shown}.",
        "What kind of place or setting is this?",
        "What is the main colour in this {shown}?",
        "What is the lighting or time of day in this {shown}?",
        "What material or texture stands out in this {shown}?",
        "What action or activity is shown in this {shown}? Say none if there is none.",
        "Is this a close-up, aerial view, landscape, portrait, interior or street scene?",
        "What general topic fits this {shown}, such as nature, city, food, people, travel, business or technology?",
        "Name one more object visible in this {shown}.",
        "Is there a person, animal, plant, vehicle or building in this {shown}? Name it.",
        "What is in the background of this {shown}?",
        "What is in the foreground of this {shown}?",
        "What second colour appears in this {shown}?",
        "What is the sky or weather like in this {shown}?",
        "Name one more detail visible in this {shown}.",
    )

    const val KEYWORD_TARGET = 10

    fun categoryPrompt(kind: MediaKind): String = "Which one of these categories fits this ${kind.shown()} best: " +
        SHUTTERSTOCK_CATEGORIES.joinToString(", ") + "? Answer with the category name only."

    /** The keyword in one answer, or null when it is empty, a non-answer, a repeat or a garbled word. */
    fun keyword(raw: String, kept: List<String>): String? {
        val term = firstLine(raw).lowercase()
            .replaceFirst(Regex("""^(the )?(main )?(keyword|subject|answer|colou?r|setting|object)\s*(is|:)\s*"""), "")
            .replace(Regex("[^a-z -]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            .replaceFirst(Regex("^(a|an|the) "), "")
            .trim(' ', '-')
        val words = term.split(' ')
        return when {
            term.isEmpty() || words.size > 3 || words[0] in NON_ANSWERS -> null
            // Looping output degrades into fragments such as "book iz".
            words.any { it.length <= 2 && it !in SHORT_WORDS } -> null
            kept.any { singular(it) == singular(term) || (term.startsWith(it) && ' ' !in term && term.length > it.length + 2) } -> null
            else -> term
        }
    }

    /** The Shutterstock category the answer names, or null when it names none. */
    fun category(raw: String): String? {
        val answer = firstLine(raw).lowercase().replace(Regex("[^a-z/ ]"), "").trim()
        return SHUTTERSTOCK_CATEGORIES.firstOrNull { it.lowercase() == answer }
            ?: SHUTTERSTOCK_CATEGORIES.firstOrNull { category ->
                answer.isNotEmpty() && category.lowercase().split("/", " and ").map { it.trim() }.contains(answer)
            }
    }

    /** Hint terms first, since they say what the model cannot see, then the model's keywords. */
    fun keywords(hint: String?, generated: List<String>): List<String> {
        val out = LinkedHashMap<String, String>()
        fun add(term: String) {
            val t = term.trim().lowercase()
            if (t.length >= 3 && t !in STOPWORDS) out.putIfAbsent(singular(t), t)
        }
        hint?.split(',', ';')?.forEach { part ->
            if (part.trim().split(Regex("\\s+")).size <= 3) add(part)
            Regex("[a-z]+").findAll(part.lowercase()).forEach { add(it.value) }
        }
        generated.forEach(::add)
        return out.values.toList()
    }

    private fun firstLine(raw: String): String = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun singular(term: String) = term.removeSuffix("s")

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

    private const val PHOTO = "(image|photo|picture|photograph|video frame|video|clip|footage)"
    private const val FILLER = "(stunning|serene|beautiful|breathtaking|majestic|picturesque|idyllic|gorgeous|tranquil|" +
        "surreal|magnificent|spectacular|captivating|enchanting)"
    private val NON_ANSWERS = setOf("none", "no", "yes", "unknown", "not", "nothing")
    private val SHORT_WORDS = setOf("of", "on", "in", "at", "to")

    private val STOPWORDS = setOf(
        "the", "and", "with", "for", "from", "into", "onto", "over", "under", "near", "its", "his", "her", "their",
        "this", "that", "these", "those", "are", "was", "were", "has", "have", "while", "who", "which", "there",
        "some", "front", "photo", "image", "picture", "visible", "background", "foreground",
    )
}
