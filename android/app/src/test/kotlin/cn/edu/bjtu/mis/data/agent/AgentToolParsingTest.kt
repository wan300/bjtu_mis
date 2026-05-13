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
    fun rejectsMissingRequiredString() {
        val args = parseToolArguments("""{"append":true}""")
        assertThrows(IllegalArgumentException::class.java) {
            args.requiredString("path")
        }
    }
}
