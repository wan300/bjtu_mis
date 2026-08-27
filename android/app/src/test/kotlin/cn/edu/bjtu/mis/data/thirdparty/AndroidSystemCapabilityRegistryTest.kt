package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidSystemCapabilityRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun ungrantedAndroidCapabilityFailsBeforeProvider() = runBlocking {
        val service = service(
            required = setOf("runtime.lifecycle@1"),
            optional = setOf("android.accessibility.nodes@1"),
            granted = setOf("runtime.lifecycle@1"),
        )

        val response = registry().invoke(
            service = service,
            capability = "android.accessibility.nodes@1",
            method = "getRoot",
            confirmer = denyAll,
            currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(service),
            runtimeEnvironment = runtimeEnvironment,
        )

        assertError("permission_denied", response)
    }

    @Test
    fun disconnectedAccessibilityServiceReturnsCapabilityUnavailable() = runBlocking {
        val service = service(
            required = setOf("android.accessibility.nodes@1"),
            granted = setOf("android.accessibility.nodes@1"),
        )

        val response = registry().invoke(
            service = service,
            capability = "android.accessibility.nodes@1",
            method = "getRoot",
            confirmer = denyAll,
            currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(service),
            runtimeEnvironment = runtimeEnvironment,
        )

        assertError("capability_unavailable", response)
        assertEquals(
            "android.settings.ACCESSIBILITY_SETTINGS",
            response["error"]!!.jsonObject["details"]!!.jsonObject["settingsAction"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun settingsAndPackageNamesAreRejectedByGeneratedValidation() = runBlocking {
        val service = service(
            required = setOf("android.settings.open@1", "android.packages.read@1"),
            granted = setOf("android.settings.open@1", "android.packages.read@1"),
        )
        val origin = ThirdPartyServiceSandbox.entrypointUrlFor(service)

        val settings = registry().invoke(
            service,
            "android.settings.open@1",
            "open",
            buildJsonObject { put("action", "android.intent.action.VIEW") },
            confirmer = denyAll,
            currentPageUrl = origin,
            runtimeEnvironment = runtimeEnvironment,
        )
        val packages = registry().invoke(
            service,
            "android.packages.read@1",
            "get",
            buildJsonObject { put("packageName", "../private") },
            confirmer = denyAll,
            currentPageUrl = origin,
            runtimeEnvironment = runtimeEnvironment,
        )

        assertError("invalid_request", settings)
        assertError("invalid_request", packages)
    }

    @Test
    fun accessibilityActionsAreIdempotentWithoutPerCallConfirmation() = runBlocking {
        var confirmations = 0
        var executions = 0
        val receiptStore = FilePluginCommandReceiptStore(
            temp.newFolder("receipts"),
            RegistryAutomationCipher,
        )
        val provider = LambdaPluginCapabilityProvider(setOf("android.accessibility.actions@1")) {
            executions += 1
            buildJsonObject { put("performed", true) }
        }
        val registry = ThirdPartyServiceApiRegistry(
            moduleRepository = null,
            mailRepository = null,
            commandReceiptStore = receiptStore,
            providerOverrides = listOf(provider),
        )
        val service = service(
            required = setOf("android.accessibility.actions@1"),
            granted = setOf("android.accessibility.actions@1"),
        )
        val params = buildJsonObject {
            put("idempotencyKey", "home-once")
            put("action", "home")
        }
        val confirmer = ThirdPartySensitiveActionConfirmer { _, _ ->
            confirmations += 1
            false
        }
        val origin = ThirdPartyServiceSandbox.entrypointUrlFor(service)

        val first = registry.invoke(
            service,
            "android.accessibility.actions@1",
            "performGlobal",
            params,
            confirmer = confirmer,
            currentPageUrl = origin,
            runtimeEnvironment = runtimeEnvironment,
        )
        val second = registry.invoke(
            service,
            "android.accessibility.actions@1",
            "performGlobal",
            params,
            confirmer = confirmer,
            currentPageUrl = origin,
            runtimeEnvironment = runtimeEnvironment,
        )

        assertTrue(first["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(first["result"], second["result"])
        assertEquals(1, executions)
        assertEquals(0, confirmations)
    }

    @Test
    fun persistentNativeCalendarWritesAreIdempotentWithoutPerCallConfirmation() = runBlocking {
        var confirmations = 0
        var executions = 0
        val receiptStore = FilePluginCommandReceiptStore(
            temp.newFolder("calendar-receipts"),
            RegistryAutomationCipher,
        )
        val provider = LambdaPluginCapabilityProvider(setOf("android.calendar.write@1")) {
            executions += 1
            buildJsonObject { put("id", "42") }
        }
        val registry = ThirdPartyServiceApiRegistry(
            moduleRepository = null,
            mailRepository = null,
            commandReceiptStore = receiptStore,
            providerOverrides = listOf(provider),
        )
        val service = service(
            required = setOf("android.calendar.write@1"),
            granted = setOf("android.calendar.write@1"),
        )
        val params = buildJsonObject {
            put("idempotencyKey", "calendar-create-once")
            put("title", "Test event")
            put("startMs", 1L)
            put("endMs", 2L)
        }
        val confirmer = ThirdPartySensitiveActionConfirmer { _, _ ->
            confirmations += 1
            false
        }
        val origin = ThirdPartyServiceSandbox.entrypointUrlFor(service)

        val first = registry.invoke(
            service,
            "android.calendar.write@1",
            "create",
            params,
            confirmer = confirmer,
            currentPageUrl = origin,
            runtimeEnvironment = runtimeEnvironment,
        )
        val second = registry.invoke(
            service,
            "android.calendar.write@1",
            "create",
            params,
            confirmer = confirmer,
            currentPageUrl = origin,
            runtimeEnvironment = runtimeEnvironment,
        )

        assertTrue(first["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(first["result"], second["result"])
        assertEquals(1, executions)
        assertEquals(0, confirmations)
    }

    @Test
    fun notificationStatusDoesNotRequireCommandIdempotencyKey() = runBlocking {
        var executions = 0
        val provider = LambdaPluginCapabilityProvider(setOf("android.notifications.post@1")) {
            executions += 1
            buildJsonObject {
                put("granted", true)
                put("enabled", true)
            }
        }
        val registry = ThirdPartyServiceApiRegistry(
            moduleRepository = null,
            mailRepository = null,
            providerOverrides = listOf(provider),
        )
        val service = service(
            required = setOf("android.notifications.post@1"),
            granted = setOf("android.notifications.post@1"),
        )

        val response = registry.invoke(
            service,
            "android.notifications.post@1",
            "getStatus",
            buildJsonObject { },
            confirmer = denyAll,
            currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(service),
            runtimeEnvironment = runtimeEnvironment,
        )

        assertTrue(response["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, response["result"]!!.jsonObject["granted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, response["result"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, executions)
    }

    private fun registry() = ThirdPartyServiceApiRegistry(
        moduleRepository = null,
        mailRepository = null,
    )

    private fun service(
        required: Set<String>,
        optional: Set<String> = emptySet(),
        granted: Set<String>,
    ): ThirdPartyService {
        val manifest = ThirdPartyServiceManifest(
            schemaVersion = 3,
            id = "bjtu.android-test",
            name = "Android Test",
            version = "1.0.0",
            entrypoint = "index.html",
            capabilities = ThirdPartyCapabilityDeclaration(
                required = required.toList(),
                optional = optional.toList(),
            ),
        )
        return ThirdPartyService(
            serviceId = manifest.id,
            manifest = manifest,
            sourceUrl = "https://github.com/alice/android-test",
            githubOwner = "alice",
            githubRepo = "android-test",
            defaultBranch = "main",
            commitSha = "abcdef1234567",
            packageDigestSha256 = "a".repeat(64),
            installDir = temp.root.absolutePath,
            grantedCapabilities = granted,
            allowedOrigins = emptyList(),
            enabled = true,
            needsReview = false,
            installedAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            publisherSubjectId = "github-owner:12345",
            runtimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            runtimeFloor = 2,
            compatibilityState = ThirdPartyCompatibilityState.Compatible.value,
        )
    }

    private fun assertError(code: String, response: kotlinx.serialization.json.JsonObject) {
        assertEquals(code, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    private val denyAll = ThirdPartySensitiveActionConfirmer { _, _ -> false }
    private val runtimeEnvironment = PluginWebViewRuntimeEnvironment(
        providerPackageName = "com.android.webview",
        providerVersionName = "1",
        documentStartScriptSupported = true,
        webMessageListenerSupported = true,
        webMessageArrayBufferSupported = true,
    )
}

private object RegistryAutomationCipher : ThirdPartyKvCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext
    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray = payload
}
