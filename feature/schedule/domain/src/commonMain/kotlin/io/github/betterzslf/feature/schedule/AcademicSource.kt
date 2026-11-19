package io.github.betterzslf.feature.schedule

data class AcademicIdentity(val account: String, val displayName: String = "") {
    override fun toString(): String = "AcademicIdentity(redacted)"
}

/** Fixed, non-server-controlled messages; no account, password, cookies, URL query or response body in errors. */
class AcademicSessionExpiredException : Exception("Academic session expired")
class AcademicAuthenticationException : Exception("Academic authentication failed")
class AcademicProtocolException : Exception("Academic response could not be validated")
class AcademicNetworkException : Exception("Academic request failed")

interface AcademicSource {
    suspend fun authenticate(account: String, password: String): AcademicIdentity
    suspend fun terms(): List<ScheduleTerm>
    suspend fun fetch(term: ScheduleTerm): ScheduleSnapshot
    fun close()
}
