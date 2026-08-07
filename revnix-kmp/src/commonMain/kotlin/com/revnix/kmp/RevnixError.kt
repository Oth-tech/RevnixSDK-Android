package com.revnix.kmp

/**
 * Error taxonomy mirroring revnix-react and the JVM `revnix-core`: every case
 * is either RETRYABLE (transient — offline, timeout, 429, 5xx, captive portal)
 * or DELIBERATE (401/403/404/409 — the server refused on purpose; a kill-switch
 * must never be defeated by a cache or a retry).
 */
public sealed class RevnixError(
    message: String,
    public val isRetryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause) {

    public class Network(detail: String, cause: Throwable? = null) :
        RevnixError("network failure: $detail", isRetryable = true, cause = cause)

    public class Timeout(cause: Throwable? = null) :
        RevnixError("request timed out", isRetryable = true, cause = cause)

    public class RateLimited(public val retryAfterMs: Long?) :
        RevnixError("rate limited", isRetryable = true)

    public class Server(public val status: Int) :
        RevnixError("server error $status", isRetryable = true)

    public class BadResponse(detail: String = "unparseable response") :
        RevnixError(detail, isRetryable = true)

    public class Auth(public val status: Int) :
        RevnixError("unauthorized ($status)", isRetryable = false)

    public class NotFound :
        RevnixError("not found", isRetryable = false)

    public class PurchaseBlocked(detail: String) :
        RevnixError(detail.ifEmpty { "purchase blocked" }, isRetryable = false)

    public class Invalid(public val status: Int, detail: String) :
        RevnixError(detail.ifEmpty { "request rejected ($status)" }, isRetryable = false)

    public companion object {
        public fun fromHttp(status: Int, message: String, retryAfter: String? = null): RevnixError =
            when (status) {
                401, 403 -> Auth(status)
                404 -> NotFound()
                409 -> PurchaseBlocked(message)
                429 -> RateLimited(parseRetryAfter(retryAfter))
                in 500..599 -> Server(status)
                else -> Invalid(status, message)
            }

        private val months = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )

        /**
         * `Retry-After` is either delta-seconds or an HTTP date (RFC 9110).
         * Hand-rolled rather than using a JVM date parser so this stays in
         * commonMain; behaviour matches the Swift and JVM ports.
         */
        public fun parseRetryAfter(raw: String?, nowMs: Long = 0L): Long? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            value.toDoubleOrNull()?.let { seconds ->
                return if (seconds > 0) (seconds * 1000).toLong() else 0L
            }
            // "Thu, 01 Jan 1970 00:16:50 GMT"
            val parts = value.removeSuffix("GMT").trim().split(" ", ",").filter { it.isNotEmpty() }
            if (parts.size < 5) return null
            val day = parts[1].toIntOrNull() ?: return null
            val month = months.indexOf(parts[2]).takeIf { it >= 0 } ?: return null
            val year = parts[3].toIntOrNull() ?: return null
            val hms = parts[4].split(":")
            if (hms.size != 3) return null
            val h = hms[0].toIntOrNull() ?: return null
            val m = hms[1].toIntOrNull() ?: return null
            val s = hms[2].toIntOrNull() ?: return null
            val epochDay = daysFromCivil(year, month + 1, day)
            val target = epochDay * 86_400_000L + h * 3_600_000L + m * 60_000L + s * 1000L
            return (target - nowMs).coerceAtLeast(0L)
        }

        /** Howard Hinnant's days_from_civil — proleptic Gregorian, no deps. */
        private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
            val year = if (m <= 2) y - 1 else y
            val era = (if (year >= 0) year else year - 399) / 400
            val yoe = year - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era.toLong() * 146_097L + doe.toLong() - 719_468L
        }
    }
}
