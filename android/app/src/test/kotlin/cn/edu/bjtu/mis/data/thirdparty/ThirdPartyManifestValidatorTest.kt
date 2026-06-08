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

    private fun validManifest(
        id: String = "bjtu.demo",
        entrypoint: String = "index.html",
        icon: String = "icon.svg",
        required: List<String> = listOf("identity.profile.read"),
        optional: List<String> = listOf("academic.timetable.read"),
        allowedOrigins: List<String> = emptyList(),
    ): ThirdPartyServiceManifest =
        ThirdPartyServiceManifest(
            schemaVersion = 1,
            id = id,
            name = "Demo",
            description = "Demo service",
            version = "1.0.0",
            entrypoint = entrypoint,
            icon = icon,
            author = "Alice",
            permissions = ThirdPartyServicePermissionDeclaration(required = required, optional = optional),
            allowedOrigins = allowedOrigins,
        )
}
