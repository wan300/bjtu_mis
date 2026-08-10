package cn.edu.bjtu.mis.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginReadmeRendererTest {
    @Test
    fun rendersGfmAndRemovesUnsafeMarkupAndImages() {
        val html = renderPluginReadmeHtml(
            markdown = """
                # Demo

                | Name | Value |
                | --- | --- |
                | one | **two** |

                ~~deprecated~~
                - [x] ready

                ```kotlin
                fun main() = Unit
                ```

                [docs](docs/install.md)
                [bad](javascript:alert(1))
                [local](file:///tmp/secrets)
                ![safe](https://raw.githubusercontent.com/alice/demo/abc1234/image.png)
                ![unsafe](https://evil.example/image.png)
                <script>alert(1)</script>
                <iframe src="https://evil.example/frame"></iframe>
                <form onsubmit="alert(1)"><input value="bad"></form>
            """.trimIndent(),
            owner = "alice",
            repository = "demo",
            commitSha = "abc1234def5678",
        )

        assertTrue(html.contains("<h1>Demo</h1>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("fun main()"))
        assertTrue(html.contains("<del>deprecated</del>"))
        assertTrue(html.contains("type=\"checkbox\""))
        assertTrue(html.contains("https://github.com/alice/demo/blob/abc1234def5678/docs/install.md"))
        assertTrue(html.contains("https://raw.githubusercontent.com/alice/demo/abc1234/image.png"))
        assertFalse(html.contains("evil.example"))
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("file:"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("onsubmit"))
        assertFalse(html.contains("<iframe"))
        assertFalse(html.contains("<form"))
    }

    @Test
    fun imageAllowlistOnlyAcceptsHttpsGithubContentHosts() {
        assertTrue(isAllowedPluginReadmeImageUrl("https://github.com/alice/demo/raw/abc/image.svg"))
        assertTrue(isAllowedPluginReadmeImageUrl("https://raw.githubusercontent.com/a/b/c.png"))
        assertTrue(isAllowedPluginReadmeImageUrl("https://user-images.githubusercontent.com/1/x.png"))
        assertTrue(isAllowedPluginReadmeImageUrl("https://github.githubassets.com/assets/x.png"))
        assertFalse(isAllowedPluginReadmeImageUrl("http://raw.githubusercontent.com/a/b/c.png"))
        assertFalse(isAllowedPluginReadmeImageUrl("https://evil.example/c.png"))
        assertFalse(isAllowedPluginReadmeImageUrl("data:image/png;base64,AA=="))
    }
}
