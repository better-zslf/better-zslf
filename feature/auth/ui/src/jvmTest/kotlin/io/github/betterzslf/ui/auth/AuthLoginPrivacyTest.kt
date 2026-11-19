package io.github.betterzslf.ui.auth

import java.lang.reflect.Modifier
import kotlin.test.*

class AuthLoginPrivacyTest {
    @Test
    fun publicDisplayTypesHaveNoSensitiveAuthorityFields() {
        val chooserFields = AuthChooserDisplay::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(listOf("showDormLink", "campusBound", "academicBound"), chooserFields.map { it.name })
        assertTrue(chooserFields.all { it.type == Boolean::class.javaPrimitiveType })

        val displayFields = CampusLoginDisplay::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(setOf("busy", "notice", "extraLabel", "extraKey"), displayFields.map { it.name }.toSet())
        assertEquals(Boolean::class.javaPrimitiveType, CampusLoginDisplay::class.java.getDeclaredField("busy").type)
        assertEquals(CampusLoginNotice::class.java, CampusLoginDisplay::class.java.getDeclaredField("notice").type)
        assertTrue(displayFields.filter { it.type == String::class.java }.map { it.name }.all { it in setOf("extraLabel", "extraKey") })

        val noticeFields = CampusLoginNotice::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(setOf("title", "message"), noticeFields.map { it.name }.toSet())
        assertTrue(noticeFields.all { it.type == String::class.java })

        val controlsFields = CampusLoginControls::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(listOf("submitEnabled", "recoveryAvailable"), controlsFields.map { it.name })
        assertTrue(controlsFields.all { it.type == Boolean::class.javaPrimitiveType })

        val forbidden = listOf("Ticket", "Target", "Controller", "Source", "Graph", "Route", "NavKey", "Session", "ApiResult", "Credential")
        val allFields = chooserFields + displayFields + noticeFields + controlsFields
        assertTrue(allFields.none { field -> forbidden.any { field.type.name.contains(it) } })
    }

    @Test
    fun everyUiEventIsAnEnumConstantWithNoPayloadField() {
        assertTrue(AuthLoginUiEvent::class.java.isEnum)
        assertTrue(AuthLoginUiEvent::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }.isEmpty())
        assertEquals(9, AuthLoginUiEvent.entries.size)
    }

    @Test
    fun composableEntriesAcceptOnlySafeDisplayTypesNotRawAuthority() {
        val chooser = Class.forName("io.github.betterzslf.ui.auth.AuthChooserContentKt")
            .declaredMethods.single { it.name == "AuthChooserContent" }
        assertTrue(chooser.parameterTypes.any { it == AuthChooserDisplay::class.java })
        assertTrue(chooser.parameterTypes.none { it == String::class.java })
        val authorityTypes = listOf("Controller", "Source", "Ticket", "Target", "Graph", "Route", "NavKey", "Session", "ApiResult")
        assertTrue(chooser.parameterTypes.none { type -> authorityTypes.any { type.name.contains(it) } })

        val form = Class.forName("io.github.betterzslf.ui.auth.CampusLoginContentKt")
            .declaredMethods.single { it.name == "CampusLoginContent" }
        assertTrue(form.parameterTypes.any { it == CampusLoginDisplay::class.java })
        assertTrue(form.parameterTypes.any { it == CampusLoginControls::class.java })
        assertTrue(form.parameterTypes.none { it == String::class.java })
        assertTrue(form.parameterTypes.none { type -> authorityTypes.any { type.name.contains(it) } })
    }
}
