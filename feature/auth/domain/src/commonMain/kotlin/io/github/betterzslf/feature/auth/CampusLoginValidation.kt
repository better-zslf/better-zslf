package io.github.betterzslf.feature.auth

/**
 * Pure local rules only: no transport, no session, no credential storage, no Ktor. The current
 * login surface is single-school, so there is no school-selection input to validate here; the
 * backend choice is fixed by the core ApiConfig and never a login-form parameter.
 */

/**
 * Mirrors the core admission only: both fields must be non-blank. Old login passwords carry NO
 * local policy — the school's new-password complexity rule applies to password changes, never to
 * an existing credential's sign-in (see the sixold contract test).
 */
fun campusLoginCredentialsIssue(username: String, password: String): CampusLoginIssue? =
    if (username.isBlank() || password.isBlank()) CampusLoginIssue.VALIDATION else null

/**
 * Optional display-input cleanup for the student-number field only: a student number never
 * contains whitespace, so trimming pasted input cannot change a valid credential. The password
 * is NEVER normalized — it is submitted exactly as typed.
 */
fun normalizeCampusLoginUsername(username: String): String = username.trim()
