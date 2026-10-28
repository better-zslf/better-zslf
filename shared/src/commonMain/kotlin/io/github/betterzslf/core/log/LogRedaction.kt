package io.github.betterzslf.core.log

/**
 * Pattern based redaction for the local debug log. Only shapes that are known to carry credentials
 * or identifiers are replaced; unrelated text is preserved so a log stays useful.
 */
internal object LogRedaction {
    private val rules: List<Pair<Regex, String>> = listOf(
        // Header-ish forms: "Authorization: Bearer xyz", "Cookie: a=b".
        Regex("(?i)\\b(authorization)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;&\"]+") to "$1=***",
        Regex("(?i)\\b(cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]+") to "$1=***",
        // Query / body-ish key=value pairs for sensitive names.
        Regex("(?i)\\b(password|passwd|passWord|pwd|oldPassword|newPassword|newPassWord)\\s*[=:]\\s*[^&\\s,;\"]+") to "$1=***",
        Regex("(?i)\\b(accessToken|refreshToken|token|ticket|smsCode)\\s*[=:]\\s*[^&\\s,;\"]+") to "$1=***",
        Regex("(?i)\\b(username|userId|userName|account|studentId|idCard|phone|mobile|telephone)\\s*[=:]\\s*[^&\\s,;\"]+") to "$1=***",
        // Already-structured JSON values.
        Regex("(?i)\"(password|passWord|accessToken|refreshToken|token)\"\\s*:\\s*\"[^\"]*\"") to "\"\$1\":\"***\"",
        Regex("(?i)\"(username|userId|userName|account|studentId|idCard|phone|mobile)\"\\s*:\\s*\"[^\"]*\"") to "\"\$1\":\"***\"",
    )

    fun apply(text: String): String {
        var result = text
        for ((pattern, replacement) in rules) result = pattern.replace(result, replacement)
        return result
    }
}
