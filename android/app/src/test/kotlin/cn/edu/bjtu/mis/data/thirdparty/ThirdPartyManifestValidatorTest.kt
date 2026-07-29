package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThirdPartyManifestValidatorTest {
    @Test
    fun acceptsAndNormalizesValidManifest() {
        val manifest = ThirdPartyManifestValidator.validate(
            validManifest(
                connectOrigins = listOf("https://API.Example.com/"),
            )
        )

        assertEquals("bjtu.demo", manifest.id)
        assertEquals(listOf("https://api.example.com"), manifest.connectOrigins)
        assertEquals(listOf("self"), manifest.bridgeOrigins)
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
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest(icon = "icon.txt"))
        }
    }

    @Test
    fun validatesIconFileSizeInsidePackageRoot() {
        val packageRoot = createTempDirectory("third-party-manifest-icon").toFile()
        try {
            val dist = File(packageRoot, "dist").apply { mkdirs() }
            File(dist, "index.html").writeText("<html></html>")
            val icon = File(dist, "icon.svg")

            icon.writeText("<svg></svg>")
            ThirdPartyManifestValidator.validate(validManifest(), packageRoot)

            icon.writeBytes(byteArrayOf())
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.validate(validManifest(), packageRoot)
            }

            icon.writeBytes(ByteArray((MAX_THIRD_PARTY_ICON_BYTES + 1).toInt()))
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.validate(validManifest(), packageRoot)
            }
        } finally {
            packageRoot.deleteRecursively()
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
                ThirdPartyManifestValidator.validate(validManifest(connectOrigins = listOf(origin)))
            }
        }
    }

    @Test
    fun allowsHttpOnlyForLocalhostInDeveloperMode() {
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(
                validManifest(connectOrigins = listOf("http://localhost:4173")),
            )
        }

        val manifest = ThirdPartyManifestValidator.validate(
            validManifest(connectOrigins = listOf("http://localhost:4173")),
            allowDevelopmentOrigins = true,
        )

        assertEquals(listOf("http://localhost:4173"), manifest.connectOrigins)
        listOf(
            "http://192.168.1.10:4173",
            "http://10.0.2.2:4173",
            "http://example.com",
        ).forEach { origin ->
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.validate(
                    validManifest(connectOrigins = listOf(origin)),
                    allowDevelopmentOrigins = true,
                )
            }
        }
    }

    @Test
    fun rejectsPrivateIpv6AndCampusOrigins() {
        listOf(
            "https://[fd00::1]",
            "https://[fe80::1]",
            "https://[ff02::1]",
            "https://mis.bjtu.edu.cn",
            "https://192.168.1.10",
        ).forEach { origin ->
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.validate(
                    validManifest(connectOrigins = listOf(origin)),
                )
            }
        }
    }

    @Test
    fun acceptsSchemaV3MarketplaceAndConfiguration() {
        val manifest = ThirdPartyManifestValidator.validate(
            validManifest(
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
    fun rejectsInvalidSchemaV3Configuration() {
        val base = validManifest(
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
    fun rejectsLegacySchemasAndNonSemverVersions() {
        listOf(1, 2).forEach { schema ->
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.validate(validManifest(schemaVersion = schema))
            }
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest(version = "latest"))
        }
    }

    @Test
    fun rawDecoderRejectsAllowedOriginsAndMissingV3Fields() {
        val validJson = AppJson.encodeToString(validManifest())
        val withLegacyField = validJson.dropLast(1) + ",\"allowed_origins\":[]}"
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(withLegacyField)
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                """{"schema_version":3,"id":"bjtu.demo"}"""
            )
        }
        val withNestedUnknown = validJson.replace(
            "\"permissions\":{\"required\"",
            "\"permissions\":{\"credential_passthrough\":true,\"required\"",
        )
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(withNestedUnknown)
        }
        val withNullMigration = validJson.dropLast(1) + ",\"migration_entrypoint\":null}"
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(withNullMigration)
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.validate(validManifest().copy(name = "x".repeat(81)))
        }
    }

    private fun validManifest(
        schemaVersion: Int = 3,
        id: String = "bjtu.demo",
        version: String = "1.0.0",
        entrypoint: String = "index.html",
        icon: String = "icon.svg",
        required: List<String> = listOf("identity.profile.read"),
        optional: List<String> = listOf("academic.timetable.read"),
        connectOrigins: List<String> = emptyList(),
        marketplace: ThirdPartyMarketplaceMetadata? = ThirdPartyMarketplaceMetadata(category = "other"),
        configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
    ): ThirdPartyServiceManifest =
        ThirdPartyServiceManifest(
            schemaVersion = schemaVersion,
            id = id,
            name = "Demo",
            description = "Demo service",
            version = version,
            runtimeVersion = 1,
            minRuntimeVersion = 1,
            requiredCapabilities = listOf("runtime.lifecycle.v1"),
            optionalCapabilities = emptyList(),
            dataSchemaVersion = 1,
            entrypoint = entrypoint,
            icon = icon,
            author = "Alice",
            permissions = ThirdPartyServicePermissionDeclaration(required = required, optional = optional),
            connectOrigins = connectOrigins,
            mediaOrigins = emptyList(),
            frameOrigins = emptyList(),
            navigationOrigins = emptyList(),
            bridgeOrigins = listOf("self"),
            marketplace = marketplace,
            configuration = configuration,
        )
}
