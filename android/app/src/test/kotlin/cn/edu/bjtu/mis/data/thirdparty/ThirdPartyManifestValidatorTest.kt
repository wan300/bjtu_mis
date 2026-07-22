package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThirdPartyManifestValidatorTest {
    @Test
    fun acceptsAndNormalizesValidManifest() {
        val manifest = ThirdPartyManifestValidator.validate(
            validManifest(
                allowedOrigins = listOf("https://API.Example.com/", "http://47.95.238.140:8080/"),
            )
        )

        assertEquals("bjtu.demo", manifest.id)
        assertEquals(listOf("https://api.example.com", "http://47.95.238.140:8080"), manifest.allowedOrigins)
    }

    @Test
    fun rejectsUnknownPermissionsAndDuplicates() {
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(
                validManifest(required = listOf("identity.profile.read"), optional = listOf("identity.profile.read"))
            )
        }

        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest(required = listOf("unknown.permission")))
        }
    }

    @Test
    fun rejectsInvalidServiceIdsAndAssetPaths() {
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest(id = "Bad Id"))
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest(entrypoint = "../index.html"))
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest(entrypoint = "/index.html"))
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest(icon = "https://example.com/icon.svg"))
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest(entrypoint = "nested\\index.html"))
        }
    }

    @Test
    fun rejectsNonHttpHttpsOrPathOrigins() {
        listOf(
            "ftp://api.example.com",
            "https://api.example.com/v1",
            "https://user@api.example.com",
            "https://api.example.com?x=1",
        ).forEach { origin ->
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.validate(validManifest(allowedOrigins = listOf(origin)))
            }
        }
    }

    @Test
    fun acceptsSchemaV2MarketplaceAndConfiguration() {
        val manifest = ThirdPartyManifestValidator.validate(
            validManifest(
                schemaVersion = 2,
                required = listOf("app.configuration.read"),
                marketplace = ThirdPartyMarketplaceMetadata(
                    category = "Productivity",
                    tags = listOf("API", "效率"),
                ),
                configuration = listOf(
                    ThirdPartyConfigurationDefinition(
                        key = "API_URL",
                        label = "API 地址",
                        description = "第三方服务地址",
                        type = "url",
                        required = true,
                        default = "https://api.example.com/v1",
                    ),
                    ThirdPartyConfigurationDefinition(
                        key = "API_TOKEN",
                        label = "API Token",
                        description = "第三方访问令牌",
                        type = "secret",
                        required = true,
                    ),
                ),
            )
        )

        assertEquals("productivity", manifest.marketplace?.category)
        assertEquals(listOf("API", "效率"), manifest.marketplace?.tags)
        assertEquals(listOf("API_URL", "API_TOKEN"), manifest.configuration.map { it.key })
    }

    @Test
    fun rejectsInvalidSchemaV2Configuration() {
        val base = validManifest(
            schemaVersion = 2,
            required = listOf("app.configuration.read"),
            marketplace = ThirdPartyMarketplaceMetadata(category = "other"),
        )
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(
                base.copy(
                    configuration = listOf(
                        ThirdPartyConfigurationDefinition(
                            key = "api-key",
                            label = "Key",
                            description = "",
                            type = "secret",
                            required = true,
                            default = "must-not-be-stored",
                        )
                    )
                )
            )
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(
                base.copy(
                    permissions = ThirdPartyServicePermissionDeclaration(),
                    configuration = listOf(
                        ThirdPartyConfigurationDefinition(
                            key = "API_KEY",
                            label = "Key",
                            description = "",
                            type = "text",
                            required = true,
                        )
                    )
                )
            )
        }
    }

    @Test
    fun rejectsSchemaV2NonSemverVersion() {
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(
                validManifest(
                    schemaVersion = 2,
                    version = "latest",
                    marketplace = ThirdPartyMarketplaceMetadata(category = "other"),
                )
            )
        }
    }

    private fun validManifest(
        schemaVersion: Int = 1,
        id: String = "bjtu.demo",
        version: String = "1.0.0",
        entrypoint: String = "index.html",
        icon: String = "icon.svg",
        required: List<String> = listOf("identity.profile.read"),
        optional: List<String> = listOf("academic.timetable.read"),
        allowedOrigins: List<String> = emptyList(),
        marketplace: ThirdPartyMarketplaceMetadata? = null,
        configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
    ): ThirdPartyServiceManifest =
        ThirdPartyServiceManifest(
            schemaVersion = schemaVersion,
            id = id,
            name = "Demo",
            description = "Demo service",
            version = version,
            entrypoint = entrypoint,
            icon = icon,
            author = "Alice",
            permissions = ThirdPartyServicePermissionDeclaration(required = required, optional = optional),
            allowedOrigins = allowedOrigins,
            marketplace = marketplace,
            configuration = configuration,
        )
}
