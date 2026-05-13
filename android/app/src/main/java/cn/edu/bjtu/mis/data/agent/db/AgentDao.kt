package cn.edu.bjtu.mis.data.agent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(entity: AgentTaskEntity)

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId")
    suspend fun getTask(taskId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId")
    fun observeTask(taskId: String): Flow<AgentTaskEntity?>

    @Query("SELECT * FROM agent_tasks ORDER BY updated_at DESC")
    fun observeTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE status IN ('queued', 'running') ORDER BY created_at LIMIT 1")
    suspend fun getNextActiveTask(): AgentTaskEntity?

    @Query(
        """
        UPDATE agent_tasks
        SET status = :status,
            updated_at = :updatedAt,
            started_at = COALESCE(started_at, :startedAt)
        WHERE id = :taskId
        """
    )
    suspend fun markTaskStarted(taskId: String, status: String, startedAt: String, updatedAt: String)

    @Query(
        """
        UPDATE agent_tasks
        SET status = :status,
            final_answer = :finalAnswer,
            error_message = :errorMessage,
            updated_at = :updatedAt,
            finished_at = :finishedAt
        WHERE id = :taskId
        """
    )
    suspend fun finishTask(
        taskId: String,
        status: String,
        finalAnswer: String?,
        errorMessage: String?,
        updatedAt: String,
        finishedAt: String,
    )

    @Query(
        """
        UPDATE agent_tasks
        SET status = 'failed',
            error_message = :message,
            updated_at = :updatedAt,
            finished_at = :updatedAt
        WHERE status IN ('queued', 'running')
        """
    )
    suspend fun failStaleActiveTasks(message: String, updatedAt: String)

    @Query(
        """
        UPDATE agent_tasks
        SET status = 'canceled',
            error_message = :message,
            updated_at = :updatedAt,
            finished_at = :updatedAt
        WHERE id = :taskId AND status IN ('queued', 'running')
        """
    )
    suspend fun cancelTask(taskId: String, message: String, updatedAt: String)

    @Query("SELECT COALESCE(MAX(step_index) + 1, 0) FROM agent_steps WHERE task_id = :taskId")
    suspend fun nextStepIndex(taskId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(entity: AgentStepEntity)

    @Query(
        """
        UPDATE agent_steps
        SET status = :status,
            stdout = :stdout,
            stderr = :stderr,
            error_message = :errorMessage,
            finished_at = :finishedAt
        WHERE id = :stepId
        """
    )
    suspend fun finishStep(
        stepId: String,
        status: String,
        stdout: String?,
        stderr: String?,
        errorMessage: String?,
        finishedAt: String,
    )

    @Query("SELECT * FROM agent_steps WHERE task_id = :taskId ORDER BY step_index")
    fun observeSteps(taskId: String): Flow<List<AgentStepEntity>>

    @Query("SELECT * FROM agent_steps WHERE task_id = :taskId ORDER BY step_index")
    suspend fun getSteps(taskId: String): List<AgentStepEntity>

    @Query("SELECT COALESCE(MAX(message_index) + 1, 0) FROM agent_messages WHERE task_id = :taskId")
    suspend fun nextMessageIndex(taskId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(entity: AgentMessageEntity)

    @Query(
        """
        UPDATE agent_messages
        SET content = :content,
            metadata_json = :metadataJson,
            updated_at = :updatedAt
        WHERE id = :messageId
        """
    )
    suspend fun updateMessage(messageId: String, content: String, metadataJson: String?, updatedAt: String)

    @Query("SELECT * FROM agent_messages WHERE task_id = :taskId ORDER BY message_index")
    fun observeMessages(taskId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM agent_messages WHERE task_id = :taskId ORDER BY message_index")
    suspend fun getMessages(taskId: String): List<AgentMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtifact(entity: AgentArtifactEntity)

    @Query("SELECT * FROM agent_artifacts WHERE task_id = :taskId ORDER BY created_at")
    fun observeArtifacts(taskId: String): Flow<List<AgentArtifactEntity>>

    @Query("SELECT * FROM agent_artifacts WHERE task_id = :taskId ORDER BY created_at")
    suspend fun getArtifacts(taskId: String): List<AgentArtifactEntity>

    @Query("DELETE FROM agent_tasks")
    suspend fun clearTasks()

    @Query("DELETE FROM agent_steps")
    suspend fun clearSteps()

    @Query("DELETE FROM agent_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM agent_artifacts")
    suspend fun clearArtifacts()
}
