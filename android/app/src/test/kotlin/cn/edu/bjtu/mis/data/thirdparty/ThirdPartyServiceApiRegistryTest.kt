package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.data.security.LoginCredentials
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyServiceApiRegistryTest {
    private val registry = ThirdPartyServiceApiRegistry(null, null)

    @Test
    fun rejectsDisabledServicesBeforePermissionOrRepositoryAccess() = runBlocking {
        val response = registry.invoke(
            service = service(enabled = false, needsReview = true, grants = setOf("identity.profile.read")),
            method = "identity.get_profile",
            confirmer = denyConfirmer(),
        )

        assertFalse(response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("service_not_enabled", response.errorCode())
    }

    @Test
    fun rejectsUnknownMethods() = runBlocking {
        val response = registry.invoke(
            service = service(grants = setOf("identity.profile.read")),
            method = "identity.nope",
            confirmer = denyConfirmer(),
        )

        assertFalse(response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("unknown_method", response.errorCode())
    }

    @Test
    fun rejectsUngradedPermissions() = runBlocking {
        val response = registry.invoke(
            service = service(grants = emptySet()),
            method = "identity.get_profile",
            confirmer = denyConfirmer(),
        )

        assertFalse(response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("permission_denied", response.errorCode())
    }

    @Test
    fun highRiskMethodsRequirePerCallConfirmationBeforeRepositoryAccess() = runBlocking {
        var confirmationMessage = ""
        val response = registry.invoke(
            service = service(grants = setOf("mail.send")),
            method = "mail.send",
            confirmer = ThirdPartySensitiveActionConfirmer { _, message ->
                confirmationMessage = message
                false
            },
            currentPageUrl = "https://api.example.com/plugin.html",
        )

        assertFalse(response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("user_denied", response.errorCode())
        assertTrue(confirmationMessage.contains("Demo"))
        assertTrue(confirmationMessage.contains("https://api.example.com/plugin.html"))
    }

    @Test
    fun credentialsMethodRequiresPerCallConfirmation() = runBlocking {
        var confirmationCalled = false
        val registry = ThirdPartyServiceApiRegistry(
            moduleRepository = null,
            mailRepository = null,
            credentialStore = FakeCredentialStore(LoginCredentials(" 22123456 ", "secret-password")),
        )

        val response = registry.invoke(
            service = service(grants = setOf("identity.credentials.read")),
            method = "identity.get_credentials",
            confirmer = ThirdPartySensitiveActionConfirmer { _, _ ->
                confirmationCalled = true
                false
            },
            currentPageUrl = "https://bjtu.demo.third-party.bjtu-mis.local/index.html",
        )

        assertFalse(response["ok"]!!.jsonPrimitive.boolean)
        assertTrue(confirmationCalled)
        assertEquals("user_denied", response.errorCode())
    }

    @Test
    fun credentialsMethodReturnsSavedLoginAfterPerCallConfirmation() = runBlocking {
        val registry = ThirdPartyServiceApiRegistry(
            moduleRepository = null,
            mailRepository = null,
            credentialStore = FakeCredentialStore(LoginCredentials(" 22123456 ", "secret-password")),
        )

        val response = registry.invoke(
            service = service(grants = setOf("identity.credentials.read")),
            method = "identity.get_credentials",
            confirmer = ThirdPartySensitiveActionConfirmer { _, _ -> true },
            currentPageUrl = "https://bjtu.demo.third-party.bjtu-mis.local/index.html",
        )

        assertTrue(response["ok"]!!.jsonPrimitive.boolean)
        val data = response["data"]!!.jsonObject
        assertEquals("22123456", data["student_id"]!!.jsonPrimitive.contentOrNull)
        assertEquals("22123456", data["login_name"]!!.jsonPrimitive.contentOrNull)
        assertEquals("secret-password", data["password"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun configurationCanOnlyBeReadFromTheLocalSandboxOrigin() = runBlocking {
        val configured = service(grants = setOf("app.configuration.read")).copy(
            manifest = service(grants = emptySet()).manifest.copy(
                schemaVersion = 2,
                permissions = ThirdPartyServicePermissionDeclaration(required = listOf("app.configuration.read")),
                configuration = listOf(
                    ThirdPartyConfigurationDefinition(key = "API_TOKEN", label = "Token", type = "secret", required = true),
                ),
            ),
        )
        val registry = ThirdPartyServiceApiRegistry(null, null, configurationReader = { _, key -> if (key == "API_TOKEN") "secret" else null })
        val params = buildJsonObject { put("key", "API_TOKEN") }

        val remote = registry.invoke(configured, "app.get_configuration", params, denyConfirmer(), "https://api.example.com/plugin")
        assertEquals("origin_not_allowed", remote.errorCode())

        val local = registry.invoke(
            configured,
            "app.get_configuration",
            params,
            denyConfirmer(),
            ThirdPartyServiceSandbox.originFor(configured.serviceId, configured.commitSha) + "/index.html",
        )
        assertTrue(local["ok"]!!.jsonPrimitive.boolean)
        assertEquals("secret", local["data"]!!.jsonPrimitive.contentOrNull)
    }

    private fun service(
        enabled: Boolean = true,
        needsReview: Boolean = false,
        grants: Set<String>,
    ): ThirdPartyService =
        ThirdPartyService(
            serviceId = "bjtu.demo",
            manifest = ThirdPartyServiceManifest(
                schemaVersion = 1,
                id = "bjtu.demo",
                name = "Demo",
                description = "Demo",
                version = "1.0.0",
                entrypoint = "index.html",
                icon = "icon.svg",
                author = "Alice",
                permissions = ThirdPartyServicePermissionDeclaration(
                    required = listOf("identity.profile.read"),
                    optional = listOf("identity.credentials.read", "mail.send"),
                ),
            ),
            sourceUrl = "https://github.com/alice/demo",
            githubOwner = "alice",
            githubRepo = "demo",
            defaultBranch = "main",
            commitSha = "abcdef1",
            packageDigestSha256 = "0123456789abcdef",
            installDir = "/tmp/demo",
            grantedPermissions = grants,
            allowedOrigins = emptyList(),
            enabled = enabled,
            needsReview = needsReview,
            installedAt = "2026-06-06T00:00:00Z",
            updatedAt = "2026-06-06T00:00:00Z",
        )

    private fun denyConfirmer(): ThirdPartySensitiveActionConfirmer =
        ThirdPartySensitiveActionConfirmer { _, _ -> false }

    private fun kotlinx.serialization.json.JsonObject.errorCode(): String =
        this["error"]!!.jsonObject["code"]!!.jsonPrimitive.contentOrNull.orEmpty()
}

private class FakeCredentialStore(
    private val credentials: LoginCredentials?,
) : CredentialStore {
    override fun save(credentials: LoginCredentials) = Unit

    override fun load(): LoginCredentials? = credentials

    override fun clear() = Unit
}
