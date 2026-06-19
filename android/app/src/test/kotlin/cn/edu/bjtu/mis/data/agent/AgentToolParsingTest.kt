package cn.edu.bjtu.mis.data.agent

import cn.edu.bjtu.mis.data.agent.tools.parseToolArguments
import cn.edu.bjtu.mis.data.agent.tools.requiredString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentToolParsingTest {
    @Test
    fun parsesJsonObjectArguments() {
        val args = parseToolArguments("""{"path":"work/a.txt"}""")
        assertEquals("work/a.txt", args.requiredString("path"))
    }

    @Test
    fun preservesCamelCaseAgentToolArguments() {
        val args = parseToolArguments("""{"archivePath":"inbox/homework.zip","targetDir":"work/attachments/homework"}""")
        assertEquals("inbox/homework.zip", args.requiredString("archivePath"))
        assertEquals("work/attachments/homework", args.requiredString("targetDir"))
    }

    @Test
    fun rejectsMissingRequiredString() {
        val args = parseToolArguments("""{"append":true}""")
        assertThrows(IllegalArgumentException::class.java) {
            args.requiredString("path")
        }
    }
}
