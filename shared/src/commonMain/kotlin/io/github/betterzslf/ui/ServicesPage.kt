package io.github.betterzslf.ui

import androidx.compose.runtime.Composable
import io.github.betterzslf.feature.services.presentation.ServicesEvent
import io.github.betterzslf.feature.services.presentation.ServicesUiState
import io.github.betterzslf.feature.services.ui.ServicesContent

/** Application binder supplies a catalog and handles intents; rendering owns no application graph. */
@Composable
fun ServicesPage(state: ServicesUiState, onEvent: (ServicesEvent) -> Unit) = ServicesContent(state, onEvent)
