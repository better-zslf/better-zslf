package io.github.betterzslf.feature.accountrecovery.ui

import io.github.betterzslf.feature.accountrecovery.AccountRecoveryPreview
import io.github.betterzslf.feature.accountrecovery.AccountRecoveryState
import java.lang.reflect.Modifier
import kotlin.test.*

class RecoveryContentPrivacyTest {
    @Test fun publicControlsAndPreviewHaveNoSensitiveAuthorityFields() {
        val controls = RecoveryContentControls::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(controls.all { it.type == Boolean::class.javaPrimitiveType ||
            it.name in setOf("smsMaskedPhone", "resetMaskedPhone") && it.type == String::class.java })
        assertEquals(listOf("maskedPhone"), AccountRecoveryPreview::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }.map { it.name })
        val stateFields = AccountRecoveryState::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(setOf("phase", "sending", "verifying", "codeRequested", "cooldownSeconds", "verified",
            "maskedPhone", "preview", "message", "inputRevision"), stateFields.map { it.name }.toSet())
        assertEquals(setOf("maskedPhone", "message"), stateFields.filter { it.type == String::class.java }.map { it.name }.toSet())
        val forbidden = listOf("Ticket", "Target", "Controller", "Source", "Graph", "Route")
        assertTrue(stateFields.none { field -> forbidden.any { field.type.name.contains(it) } })
    }

    @Test fun everyUiEventIsAnEnumConstantWithNoPayloadField() {
        assertTrue(RecoveryUiEvent::class.java.isEnum)
        assertTrue(RecoveryUiEvent::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.isEmpty())
        assertEquals(12, RecoveryUiEvent.entries.size)
    }

    @Test fun composableEntryAcceptsOnlyDisplayStateControlsAndCallbacksNotRawStrings() {
        val entry = Class.forName("io.github.betterzslf.feature.accountrecovery.ui.AccountRecoveryContentKt")
            .declaredMethods.single { it.name == "AccountRecoveryContent" }
        assertTrue(entry.parameterTypes.any { it == AccountRecoveryState::class.java })
        assertTrue(entry.parameterTypes.any { it == RecoveryContentControls::class.java })
        assertTrue(entry.parameterTypes.none { it == String::class.java })
        assertTrue(entry.parameterTypes.none { type -> listOf("Controller", "Source", "Ticket", "Target", "Graph", "Route")
            .any { type.name.contains(it) } })
        val receipt = Class.forName("io.github.betterzslf.feature.accountrecovery.ui.AccountRecoveryContentKt")
            .declaredMethods.single { it.name == "RecoveryReceiptContent" }
        assertTrue(receipt.parameterTypes.none { it == String::class.java || it.name.contains("Logout") })
    }
}
