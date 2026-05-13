package cn.edu.bjtu.mis.data.homework

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.db.BjtuMisDao
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.serialization.decodeFromString
import java.time.LocalDateTime

class HomeworkReminderRunner(
    private val dao: BjtuMisDao,
    private val coordinator: HomeworkReminderCoordinator,
) {
    suspend fun checkSnapshot(now: LocalDateTime = LocalDateTime.now()): Boolean {
        val snapshot = dao.getSnapshot(ModuleKeys.Homework) ?: return false
        val envelope = runCatching {
            AppJson.decodeFromString<ModuleEnvelope<HomeworkData>>(snapshot.payloadJson)
        }.getOrNull() ?: return false
        return coordinator.maybeSend(envelope.data.items, now)
    }
}
