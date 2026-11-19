package io.github.betterzslf.feature.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The map-boundaries task's no-credential / no-session lexical boundary, checked
 * three ways so the guarantee does not rest on convention alone:
 *
 *  1. reflection — no String survives in the controller/state/ticket fields, and
 *     [CampusLoginController.submit] is the single public method that may accept
 *     String parameters (the credentials' only entry and exit, as call-frame
 *     locals); nothing references a forbidden core type;
 *  2. source scan — the frozen commonMain files import nothing outside the
 *     allowed boundary (domain same-package types, core text, coroutines) and
 *     declare no username/password fields;
 *  3. ticket opacity — the submission exposes no String and no source accessors
 *     beyond the redacted toString.
 */
class CampusPresentationBoundaryTest {

    // --- 1. reflection -------------------------------------------------------

    private val frozenTypes = listOf(
        CampusLoginController::class,
        CampusLoginState::class,
        CampusLoginSubmission::class,
        CampusLoginPhase::class,
    )

    @Test
    fun noCredentialStringFieldsInAnyFrozenType() {
        frozenTypes.forEach { klass ->
            klass.java.declaredFields.forEach { field ->
                assertFalse(
                    field.type == java.lang.String::class.java,
                    "${klass.simpleName}.${field.name} must not be a String field",
                )
                assertFalse(
                    field.genericType.typeName.contains("java.lang.String"),
                    "${klass.simpleName}.${field.name} must not carry String in its generic type",
                )
            }
        }
    }

    @Test
    fun submitIsTheOnlyMethodEverAcceptingStrings() {
        CampusLoginController::class.java.methods.forEach { method ->
            val takesStrings = method.parameterTypes.any { it == java.lang.String::class.java }
            if (method.name == "submit") {
                assertTrue(takesStrings, "submit is the one credential call-frame entry")
            } else {
                assertFalse(
                    takesStrings,
                    "CampusLoginController.${method.name} must not accept String parameters",
                )
            }
        }
        // The state, phase and ticket never accept or return any String at all.
        listOf(CampusLoginState::class, CampusLoginSubmission::class, CampusLoginPhase::class).forEach { klass ->
            klass.java.methods.forEach { method ->
                if (method.declaringClass == klass.java && method.name != "toString") {
                    // The one compiler-generated enum bridge, not a designed entry: the
                    // closed constant set is the whole state, so valueOf(String) is the
                    // platform's own reflection bridge. Every other String-taking method
                    // on any class — including non-bridge methods on an enum — still fails.
                    if (klass.java.isEnum && method.name == "valueOf") return@forEach
                    assertFalse(
                        method.parameterTypes.any { it == java.lang.String::class.java },
                        "${klass.simpleName}.${method.name} must not accept String",
                    )
                    assertFalse(
                        method.returnType == java.lang.String::class.java,
                        "${klass.simpleName}.${method.name} must not return String",
                    )
                }
            }
        }
    }

    private val forbiddenTypePrefixes = listOf(
        "io.github.betterzslf.core.net.",
        "io.github.betterzslf.core.storage.",
        "io.github.betterzslf.core.result.",
        "io.github.betterzslf.core.model.",
        "io.github.betterzslf.data.",
        "io.github.betterzslf.ui.",
        "io.github.betterzslf.AppGraph",
    )

    @Test
    fun noFrozenSignatureReferencesAForbiddenCoreOrSharedType() {
        frozenTypes.forEach { klass ->
            val referenced = buildList {
                klass.java.declaredFields.forEach { add(it.type.name); add(it.genericType.typeName) }
                klass.java.declaredConstructors.forEach { constructor ->
                    constructor.parameterTypes.forEach { add(it.name) }
                }
                klass.java.declaredMethods.forEach { method ->
                    add(method.returnType.name)
                    method.parameterTypes.forEach { add(it.name) }
                }
            }
            forbiddenTypePrefixes.forEach { prefix ->
                assertTrue(
                    referenced.none { it.startsWith(prefix) },
                    "${klass.simpleName} must not reference $prefix types (no transport, storage, ApiError, Session, Graph)",
                )
            }
        }
    }

    // --- 2. source scan -------------------------------------------------------

    private fun moduleMainSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(10) {
            val current = dir ?: return@repeat
            val asModuleRoot = File(current, "src/commonMain/kotlin/io/github/betterzslf/feature/auth")
            if (File(asModuleRoot, "CampusLoginController.kt").isFile) return asModuleRoot
            val asRepoRoot = File(current, "feature/auth/presentation/src/commonMain/kotlin/io/github/betterzslf/feature/auth")
            if (File(asRepoRoot, "CampusLoginController.kt").isFile) return asRepoRoot
            dir = current.parentFile
        }
        error(
            "Cannot locate feature/auth/presentation commonMain sources from " +
                System.getProperty("user.dir") + "; run this test from the module or the repository root",
        )
    }

    private fun kotlinSources(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.name }.toList()

    private val forbiddenImports = listOf(
        "io.github.betterzslf.core.net",
        "io.github.betterzslf.core.storage",
        "io.github.betterzslf.core.result",
        "io.github.betterzslf.core.model",
        "io.github.betterzslf.data",
        "io.github.betterzslf.ui",
        "io.github.betterzslf.AppGraph",
        "kotlinx.serialization",
        "androidx.compose",
    )

    @Test
    fun frozenSourcesImportNothingOutsideTheAllowedBoundary() {
        val sources = kotlinSources(moduleMainSourceDir())
        // The five frozen files must all be present; a stale or moved tree fails loudly.
        assertEquals(
            listOf("CampusLoginController.kt", "CampusLoginPhase.kt", "CampusLoginState.kt", "CampusLoginSubmission.kt", "CampusLoginText.kt"),
            sources.map { it.name }.sorted(),
            "the frozen commonMain file set changed; update this test together with the freeze",
        )
        sources.forEach { file ->
            val text = file.readText()
            forbiddenImports.forEach { prefix ->
                assertFalse(
                    text.contains("import $prefix"),
                    "${file.name} imports forbidden $prefix",
                )
            }
        }
    }

    @Test
    fun frozenSourcesDeclareNoCredentialFieldsAndNoRawServerTextFields() {
        val credentialField = Regex("""\b(?:val|var)\s+(username|password|account|secret)\b""")
        val rawMessageField = Regex("""\b(?:val|var)\s+\w*[Mm]essage\w*\s*:\s*String""")
        kotlinSources(moduleMainSourceDir()).forEach { file ->
            val text = file.readText()
            // The only tolerated occurrence is the submit call-frame parameter.
            assertFalse(
                credentialField.containsMatchIn(text),
                "${file.name} declares a credential field; credentials may exist only as submit call-frame locals",
            )
            assertFalse(
                rawMessageField.containsMatchIn(text),
                "${file.name} declares a message String field; server text must never be stored",
            )
        }
    }

    // --- 3. ticket opacity ------------------------------------------------------

    @Test
    fun theTicketPrintsRedactedAndItsOnlyPublicSurfaceIsObjectContract() {
        val controller = CampusLoginController(sourceFactory = { null }, isCurrent = { true })
        val ticket = CampusLoginSubmission(controller, FakeBareSource())
        assertEquals("CampusLoginSubmission(redacted)", ticket.toString())
        // Its class declares no String field and no extra public constructors.
        assertEquals(0, CampusLoginSubmission::class.java.declaredFields.count { it.type == java.lang.String::class.java })
        assertEquals(1, CampusLoginSubmission::class.java.declaredConstructors.size)
    }

    private class FakeBareSource : CampusLoginSource {
        override fun claim(): Boolean = true
        override fun isCurrent(): Boolean = true
        override suspend fun submit(username: String, password: String): CampusLoginOutcome =
            CampusLoginOutcome.Rejected(CampusLoginIssue.VALIDATION)
        override fun cancel() = Unit
        override fun consumeAcceptedContinuation(): Boolean = true
    }
}
