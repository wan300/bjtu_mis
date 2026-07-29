package cn.edu.bjtu.mis.ui

import cn.edu.bjtu.mis.data.thirdparty.thirdPartyServiceRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDisplayBehaviorTest {
    @Test
    fun `plugin top bar is hidden only for plugin routes when enabled`() {
        val pluginRoute = thirdPartyServiceRoute("jmcomic")

        assertFalse(
            shouldHideAppTopBarForThirdPartyService(
                route = pluginRoute,
                hideThirdPartyServiceTopBar = false,
            ),
        )
        assertTrue(
            shouldHideAppTopBarForThirdPartyService(
                route = pluginRoute,
                hideThirdPartyServiceTopBar = true,
            ),
        )
        assertFalse(
            shouldHideAppTopBarForThirdPartyService(
                route = "services",
                hideThirdPartyServiceTopBar = true,
            ),
        )
    }
}
