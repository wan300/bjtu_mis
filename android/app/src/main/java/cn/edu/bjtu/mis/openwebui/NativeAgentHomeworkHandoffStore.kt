package cn.edu.bjtu.mis.openwebui

import cn.edu.bjtu.mis.model.HomeworkItem
import java.util.concurrent.atomic.AtomicReference

data class NativeAgentHomeworkHandoff(
    val homework: HomeworkItem,
    val userInstruction: String = "",
)

object NativeAgentHomeworkHandoffStore {
    private val pending = AtomicReference<NativeAgentHomeworkHandoff?>(null)

    fun set(handoff: NativeAgentHomeworkHandoff) {
        pending.set(handoff)
    }

    fun consume(): NativeAgentHomeworkHandoff? =
        pending.getAndSet(null)
}
