package cn.edu.bjtu.mis.data.thirdparty

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyIconPolicyTest {
    @Test
    fun resolvesSupportedLocalIconInsideInstallDirectory() {
        val installDirectory = createTempDirectory("third-party-icon").toFile()
        try {
            val icon = File(installDirectory, "assets/icon.svg").apply {
                parentFile?.mkdirs()
                writeText("<svg></svg>")
            }

            val source = resolveLocalThirdPartyIconSource(
                rootDirectory = installDirectory,
                iconPath = "assets/icon.svg",
            )

            assertTrue(source is ThirdPartyIconSource.LocalFile)
            assertEquals(icon.canonicalFile, (source as ThirdPartyIconSource.LocalFile).file)
        } finally {
            installDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafeUnsupportedMissingAndOversizedLocalIcons() {
        val installDirectory = createTempDirectory("third-party-icon-boundaries").toFile()
        val outsideIcon = File(installDirectory.parentFile, "${installDirectory.name}-outside.png")
        try {
            outsideIcon.writeBytes(byteArrayOf(1))
            File(installDirectory, "icon.txt").writeText("not an image")
            File(installDirectory, "oversized.png")
                .writeBytes(ByteArray((MAX_THIRD_PARTY_ICON_BYTES + 1).toInt()))

            assertNull(resolveLocalThirdPartyIconSource(installDirectory, "../${outsideIcon.name}"))
            assertNull(resolveLocalThirdPartyIconSource(installDirectory, "missing.svg"))
            assertNull(resolveLocalThirdPartyIconSource(installDirectory, "icon.txt"))
            assertNull(resolveLocalThirdPartyIconSource(installDirectory, "oversized.png"))
        } finally {
            outsideIcon.delete()
            installDirectory.deleteRecursively()
        }
    }

    @Test
    fun acceptsOnlySafeHttpsRemoteIconUrls() {
        assertEquals(
            ThirdPartyIconSource.RemoteUrl("https://bjtu.cc/api/plugins/demo/icon?version=1"),
            resolveRemoteThirdPartyIconSource("https://bjtu.cc/api/plugins/demo/icon?version=1"),
        )
        assertNull(resolveRemoteThirdPartyIconSource("http://bjtu.cc/icon.svg"))
        assertNull(resolveRemoteThirdPartyIconSource("javascript:alert(1)"))
        assertNull(resolveRemoteThirdPartyIconSource("https://user@bjtu.cc/icon.svg"))
        assertNull(resolveRemoteThirdPartyIconSource("https://bjtu.cc/icon.svg#fragment"))
    }
}
