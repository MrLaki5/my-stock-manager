package com.mrlaki5.mystockmanager.metadata.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the editorial caption both agencies expect as the asset description:
 *
 *     Belgrade, Serbia - May 23, 2026: Large Serbian national flags wave above crowds
 *     marching toward Slavija Square during a student-led anti-government rally.
 *
 * The app prepends the lead rather than asking the model for it, so the shape is
 * guaranteed instead of merely requested — a format the model reproduces "usually" is not a
 * format. The model writes only the body.
 *
 * Either leading part can be missing and simply drops out along with its separator. The
 * date is omitted rather than guessed when a photo carries no EXIF capture date: a wrong
 * date on an editorial caption is worse than no date, because it is a factual claim about
 * when something happened.
 */
object EditorialCaption {

    /**
     * Fixed to US English rather than the device locale. The caption is written for
     * English-language marketplaces, so it must read "May 23, 2026" on a phone set to
     * Serbian just as it does on one set to English.
     */
    private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

    fun build(location: String?, capturedOn: String?, body: String): String {
        val lead = listOfNotNull(
            location?.trim()?.takeIf { it.isNotEmpty() },
            capturedOn?.let(::formatDate),
        ).joinToString(SEPARATOR)

        val text = body.trim()
        return if (lead.isEmpty()) text else "$lead: $text"
    }

    data class Parts(val location: String?, val body: String)

    /** The inverse of [build]: splits off only a lead [build] could have produced, never a guessed one. */
    fun parse(caption: String, capturedOn: String?, knownLocation: String?): Parts {
        val text = caption.trim()
        capturedOn?.let(::formatDate)?.let { date ->
            if (text.startsWith("$date: ")) return Parts(null, text.removePrefix("$date: ").trim())
            val marker = "$SEPARATOR$date: "
            val at = text.indexOf(marker)
            if (at > 0) return Parts(text.substring(0, at).trim(), text.substring(at + marker.length).trim())
        }
        // Without a date the lead is only recognisable when the event's location is already known.
        val location = knownLocation?.trim()?.takeIf { it.isNotEmpty() }
        if (location != null && text.startsWith("$location: ")) {
            return Parts(location, text.removePrefix("$location: ").trim())
        }
        return Parts(null, text)
    }

    /** The place in a caption's lead, found exactly by cutting off the [body] the row already stores; null when there is none. */
    fun placeOf(caption: String, body: String, capturedOn: String?): String? {
        val text = caption.trim()
        val lead = text.removeSuffix(": ${body.trim()}").takeIf { it != text }?.trim() ?: return null
        val date = capturedOn?.let(::formatDate)
        val place = if (date != null && lead == date) "" else if (date != null) lead.removeSuffix("$SEPARATOR$date") else lead
        return place.trim().ifEmpty { null }
    }

    /** [capturedOn] is an ISO local date; anything unparseable is treated as absent. */
    private fun formatDate(capturedOn: String): String? =
        runCatching { LocalDate.parse(capturedOn).format(MONTH_DAY_YEAR) }.getOrNull()

    private const val SEPARATOR = " - "
}
