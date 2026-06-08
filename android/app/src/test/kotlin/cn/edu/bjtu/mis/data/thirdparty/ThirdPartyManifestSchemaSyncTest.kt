package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThirdPartyManifestSchemaSyncTest {
    @Test
    fun docsAndWebSchemasMatchPermissionRegistry() {
        val docsSchema = repoFile("docs/third-party-service-manifest.schema.json")
        val webSchema = repoFile("web/assets/schemas/third-party-service-manifest.schema.json")

        assertTrue("Missing docs schema", docsSchema.isFile)
        assertTrue("Missing web schema", webSchema.isFile)
        assertEquals(docsSchema.readText(), webSchema.readText())

        val schema = AppJson.parseToJsonElement(docsSchema.readText()).jsonObject
        val enumValues = schema["\$defs"]!!.jsonObject["permission_array"]!!
            .jsonObject["items"]!!
            .jsonObject["enum"]!!
            .jsonArray
            .map { it.jsonPrimitive.contentOrNull.orEmpty() }
            .toSet()

        assertEquals(ThirdPartyPermissionRegistry.allIds(), enumValues)
    }

    private fun repoFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
        )
        return candidates.firstOrNull { it.isFile } ?: candidates.last()
    }
}
