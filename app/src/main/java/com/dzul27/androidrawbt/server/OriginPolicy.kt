package com.dzul27.androidrawbt.server

/** Outcome of the browser-origin check (FR-SVC-03). */
sealed interface OriginDecision {
    /**
     * Request accepted. [allowOrigin] is the value for `Access-Control-Allow-Origin`
     * (null: send none); [varyOrigin] is true when the origin was echoed and `Vary: Origin` must be sent.
     */
    data class Allowed(val allowOrigin: String?, val varyOrigin: Boolean) : OriginDecision

    /** Request must be answered with 403 before anything is decoded or printed. */
    data object Rejected : OriginDecision
}

/** Pure port of WinRawBT's origin rules: unit-testable, no Ktor/Android dependencies. */
object OriginPolicy {
    /**
     * - [allowAnyOrigin]: everything accepted, `Access-Control-Allow-Origin: *`.
     * - no `Origin` header (curl, native apps): accepted, no CORS origin header.
     * - otherwise accepted only if [origin] is listed in [allowedOrigins] (trimmed, trailing `/` ignored,
     *   case-insensitive); the request's origin is echoed together with `Vary: Origin`.
     */
    fun evaluate(origin: String?, allowAnyOrigin: Boolean, allowedOrigins: List<String>): OriginDecision {
        if (allowAnyOrigin) return OriginDecision.Allowed("*", varyOrigin = false)
        if (origin.isNullOrBlank()) return OriginDecision.Allowed(null, varyOrigin = false)
        val wanted = normalize(origin)
        return if (allowedOrigins.any { normalize(it).equals(wanted, ignoreCase = true) }) {
            OriginDecision.Allowed(origin, varyOrigin = true)
        } else {
            OriginDecision.Rejected
        }
    }

    private fun normalize(value: String): String = value.trim().trimEnd('/')
}
