package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThirdPartyConfigurationStoreTest {
    @Test
    fun updateKeepsOnlyValuesWhoseKeyAndTypeAreUnchanged() {
        val previous = listOf(
            ThirdPartyConfigurationDefinition(key = "API_URL", label = "URL", type = "url"),
            ThirdPartyConfigurationDefinition(key = "TOKEN", label = "Token", type = "secret"),
            ThirdPartyConfigurationDefinition(key = "COUNT", label = "Count", type = "number"),
        )
        val next = listOf(
            ThirdPartyConfigurationDefinition(key = "API_URL", label = "URL", type = "url"),
            ThirdPartyConfigurationDefinition(key = "TOKEN", label = "Token", type = "text"),
            ThirdPartyConfigurationDefinition(key = "NEW_KEY", label = "New", type = "text"),
        )

        val merged = mergeThirdPartyConfiguration(
            previousDefinitions = previous,
            nextDefinitions = next,
            previousValues = mapOf("API_URL" to "https://example.com", "TOKEN" to "secret", "COUNT" to "2"),
        )

        assertEquals(mapOf("API_URL" to "https://example.com"), merged)
        assertFalse(merged.containsKey("TOKEN"))
        assertFalse(merged.containsKey("COUNT"))
    }
}
