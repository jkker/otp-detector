package com.example.otpdetector

internal enum class NotificationBodySource {
    TEXT,
    BIG_TEXT,
}

internal data class OtpMatch(
    val code: String,
    val source: NotificationBodySource,
    val confidence: Int,
)

object OtpExtractor {
    private const val OTP_MIN_LENGTH = 4
    private const val OTP_MAX_LENGTH = 8
    private const val MIN_CONFIDENCE = 70
    private const val LOCAL_WINDOW = 48

    private val candidatePattern = Regex("""(?<!\d)(\d{4,8})(?!\d)""")
    private val otpKeywords = listOf(
        "verification code",
        "security code",
        "authentication code",
        "auth code",
        "login code",
        "one-time code",
        "one time code",
        "passcode",
        "otp",
        "pin",
        "verify",
        "verification",
        "code",
        "sign in",
        "signin",
        "log in",
        "login",
    )
    private val highConfidencePatterns = listOf(
        Regex(
            """\b(?:your|the)?\s*(?:[a-z0-9&'/-]+\s+){0,3}(?:(?:verification|security|authentication|auth|login|one[- ]time)\s+)?code(?:\s+is)?\s*[:#-]?\s*(\d{4,8})\b""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """\b(?:otp|pin|passcode)(?:\s+is)?\s*[:#-]?\s*(\d{4,8})\b""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """\b(\d{4,8})\b\s+(?:is|was)\s+(?:your|the)?\s*(?:(?:verification|security|authentication|auth|login|one[- ]time)\s+)?(?:code|otp|pin|passcode)\b""",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun extractOtp(text: String, bigText: String): String? = extractMatch(text, bigText)?.code

    internal fun extractMatch(text: String, bigText: String): OtpMatch? {
        val sources = buildList {
            if (text.isNotBlank()) {
                add(NotificationBodySource.TEXT to text)
            }

            val trimmedBigText = bigText.trim()
            if (trimmedBigText.isNotBlank() && trimmedBigText != text.trim()) {
                add(NotificationBodySource.BIG_TEXT to trimmedBigText)
            }
        }

        return sources.firstNotNullOfOrNull { (source, value) ->
            extractFromSource(source, value)
        }
    }

    private fun extractFromSource(source: NotificationBodySource, rawText: String): OtpMatch? {
        val normalized = normalize(rawText)
        if (normalized.isBlank()) {
            return null
        }

        highConfidencePatterns.forEach { pattern ->
            pattern.findAll(normalized).forEach { match ->
                val candidateRange = match.groups[1]?.range ?: return@forEach
                val code = match.groupValues[1]
                if (isValidCandidate(normalized, candidateRange.first, candidateRange.last + 1, code)) {
                    return OtpMatch(code = code, source = source, confidence = 100)
                }
            }
        }

        return candidatePattern
            .findAll(normalized)
            .mapNotNull { match ->
                val code = match.groupValues[1]
                scoreCandidate(normalized, match.range.first, match.range.last + 1, code, source)
            }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .maxWithOrNull(compareBy<OtpMatch> { it.confidence }.thenBy { it.code.length })
    }

    private fun scoreCandidate(
        normalizedText: String,
        start: Int,
        endExclusive: Int,
        code: String,
        source: NotificationBodySource,
    ): OtpMatch? {
        if (!isValidCandidate(normalizedText, start, endExclusive, code)) {
            return null
        }

        val contextStart = (start - LOCAL_WINDOW).coerceAtLeast(0)
        val contextEnd = (endExclusive + LOCAL_WINDOW).coerceAtMost(normalizedText.length)
        val localContext = normalizedText.substring(contextStart, contextEnd).lowercase()

        if (!otpKeywords.any(localContext::contains)) {
            return null
        }

        var confidence = lengthScore(code.length)
        if ("code" in localContext) {
            confidence += 28
        }
        if (listOf("verification", "verify", "otp", "pin", "passcode", "security", "authentication").any(localContext::contains)) {
            confidence += 24
        }
        if (listOf("sign in", "signin", "log in", "login").any(localContext::contains)) {
            confidence += 12
        }
        if (Regex("""(?:code|otp|pin|passcode)\s*[:#-]?\s*$code\b""", RegexOption.IGNORE_CASE).containsMatchIn(localContext)) {
            confidence += 24
        }
        if (Regex("""\b$code\b\s+(?:is|for)\s+(?:your|the)?\s*(?:verification|security|login|authentication|auth)?\s*(?:code|otp|pin|passcode)\b""", RegexOption.IGNORE_CASE).containsMatchIn(localContext)) {
            confidence += 24
        }
        if (source == NotificationBodySource.TEXT) {
            confidence += 4
        }

        return OtpMatch(code = code, source = source, confidence = confidence)
    }

    private fun isValidCandidate(
        normalizedText: String,
        start: Int,
        endExclusive: Int,
        code: String,
    ): Boolean {
        if (code.length !in OTP_MIN_LENGTH..OTP_MAX_LENGTH) {
            return false
        }

        val before = normalizedText.getOrNull(start - 1)
        val after = normalizedText.getOrNull(endExclusive)
        val beforeBefore = normalizedText.getOrNull(start - 2)
        val afterAfter = normalizedText.getOrNull(endExclusive + 1)
        if ((before == '-' || before == '.') && beforeBefore?.isDigit() == true) {
            return false
        }
        if ((after == '-' || after == '.') && afterAfter?.isDigit() == true) {
            return false
        }

        val lineStart = normalizedText.lastIndexOf('\n', startIndex = start - 1).let { if (it == -1) 0 else it + 1 }
        val lineEnd = normalizedText.indexOf('\n', startIndex = endExclusive).let { if (it == -1) normalizedText.length else it }
        val line = normalizedText.substring(lineStart, lineEnd).trim().lowercase()

        if (line.matches(Regex("""\d+"""))) {
            return false
        }

        if (Regex("""https?://|\b[a-z0-9-]+\.(com|net|org|app|io|co)\b""").containsMatchIn(line) &&
            !otpKeywords.any(line::contains)
        ) {
            return false
        }

        return true
    }

    private fun lengthScore(length: Int): Int =
        when (length) {
            6 -> 42
            8 -> 40
            7 -> 36
            5 -> 28
            4 -> 24
            else -> 0
        }

    private fun normalize(text: String): String =
        text
            .replace("\r", "\n")
            .replace(Regex("""[ \t\x0B\f]+"""), " ")
            .replace(Regex("""\n+"""), "\n")
            .trim()
}
