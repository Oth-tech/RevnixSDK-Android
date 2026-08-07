package com.revnix

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Error taxonomy mirroring revnix-react: every case is either RETRYABLE
 * (transient — offline, timeout, 429, 5xx, captive portal) or DELIBERATE
 * (401/403/404/409 — the server refused on purpose; a kill-switch must never
 * be defeated by a cache or a retry).
 */
public sealed class RevnixError(
    message: String,
    /** True when retrying, or serving the offline cache, is the right move. */
    public val isRetryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Connectivity failure (offline, DNS, reset). Retryable. */
    public class Network(detail: String, cause: Throwable? = null) :
        RevnixError("network failure: $detail", isRetryable = true, cause = cause)

    /** Request exceeded the configured timeout. Retryable. */
    public class Timeout(cause: Throwable? = null) :
        RevnixError("request timed out", isRetryable = true, cause = cause)

    /**
     * HTTP 429. Retryable with backoff. Carries the server's `Retry-After` as
     * milliseconds when it sent one.
     */
    public class RateLimited(public val retryAfterMs: Long?) :
        RevnixError("rate limited", isRetryable = true)

    /** HTTP 5xx. Retryable. */
    public class Server(public val status: Int) :
        RevnixError("server error $status", isRetryable = true)

    /** A 200 whose body was not the expected JSON (captive portal). Retryable. */
    public class BadResponse(detail: String = "unparseable response") :
        RevnixError(detail, isRetryable = true)

    /** HTTP 401/403 — missing/refused key or key-kind. Deliberate. */
    public class Auth(public val status: Int) :
        RevnixError("unauthorized ($status)", isRetryable = false)

    /** HTTP 404 — unknown route/resource. Deliberate. */
    public class NotFound :
        RevnixError("not found", isRetryable = false)

    /** HTTP 409 — purchase blocked by the app's transfer policy. Deliberate. */
    public class PurchaseBlocked(detail: String) :
        RevnixError(detail.ifEmpty { "purchase blocked" }, isRetryable = false)

    /** Any other non-2xx (400 validation, 413 payload cap). Deliberate. */
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

        /** `Retry-After` is either delta-seconds or an HTTP date (RFC 9110). */
        public fun parseRetryAfter(raw: String?, now: Instant = Instant.now()): Long? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            value.toDoubleOrNull()?.let { seconds ->
                return if (seconds > 0) (seconds * 1000).toLong() else 0L
            }
            return runCatching {
                val parsed = ZonedDateTime.parse(
                    value,
                    DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US),
                )
                (parsed.toInstant().toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0L)
            }.getOrNull()
        }
    }
}
