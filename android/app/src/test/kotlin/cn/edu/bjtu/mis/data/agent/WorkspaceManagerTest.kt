package cn.edu.bjtu.mis.data.agent

import cn.edu.bjtu.mis.data.agent.tools.WorkspaceSecurityException
import cn.edu.bjtu.mis.data.agent.tools.validateAgentRelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceManagerTest {
    @Test
    fun acceptsSafeWorkspacePaths() {
        assertEquals(emptyList<String>(), validateAgentRelativePath(".", writable = false))
        assertEquals(listOf("work", "answer.md"), validateAgentRelativePath("work/answer.md", writable = true))
        assertEquals(listOf("inbox", "ref.txt"), validateAgentRelativePath("inbox/ref.txt", writable = false))
    }

    @Test
    fun rejectsEscapingPaths() {
        listOf("../secret.txt", "work/../secret.txt", "/data/local/tmp/a", "C:/Users/a.txt", "https://example.com/a").forEach { path ->
            assertThrows(WorkspaceSecurityException::class.java) {
                validateAgentRelativePath(path, writable = false)
            }
        }
    }

    @Test
    fun rejectsWritesOutsideWorkOutputOrLogs() {
        assertThrows(WorkspaceSecurityException::class.java) {
            validateAgentRelativePath("inbox/original.txt", writable = true)
        }
        assertThrows(WorkspaceSecurityException::class.java) {
            validateAgentRelativePath(".", writable = true)
        }
    }
}
