package io.github.betterzslf.feature.schedule

import io.ktor.client.HttpClient

/** The supplied fresh client belongs exclusively to this source; no automatic reauthentication is performed. */
fun createAcademicSource(client: HttpClient): AcademicSource = KtorAcademicSource(client)
