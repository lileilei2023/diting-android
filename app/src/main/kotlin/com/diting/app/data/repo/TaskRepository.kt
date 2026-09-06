package com.diting.app.data.repo

import com.diting.app.data.db.TaskDao
import com.diting.app.data.db.TaskEntity
import com.diting.domain.model.Citation
import com.diting.domain.task.Artifact
import com.diting.domain.task.ArtifactKind
import com.diting.domain.task.Destination
import com.diting.domain.task.Task
import com.diting.domain.task.TaskGate
import com.diting.domain.task.TaskOrigin
import com.diting.domain.task.TaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val TaskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Singleton
class TaskRepository @Inject constructor(private val taskDao: TaskDao) {

    fun observeAll(): Flow<List<Task>> =
        taskDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeByState(vararg states: TaskState): Flow<List<Task>> =
        taskDao.observeByState(states.toList()).map { rows -> rows.map { it.toDomain() } }

    fun observe(id: String): Flow<Task?> = taskDao.observe(id).map { it?.toDomain() }

    /** Count of tasks sitting at either gate — the amber badge on the 任务 tab. */
    fun observeGateCount(): Flow<Int> = taskDao.observeGateCount()

    suspend fun find(id: String): Task? = taskDao.find(id)?.toDomain()

    /**
     * Creates a task at gate ①, unless an open task already covers the same
     * intent.
     *
     * The design calls for "同一意图去抖，不反复起 Agent": a customer who asks for
     * the same thing in three consecutive calls should not produce three agent
     * runs and three amber cards.
     *
     * @return the existing task if one covers this intent, otherwise the new one.
     */
    suspend fun propose(
        goal: String,
        origin: TaskOrigin,
        citations: List<Citation> = emptyList(),
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Task {
        val key = intentKeyFor(goal)
        taskDao.findOpenByIntent(key)?.let { existing ->
            // Fold the new evidence into the task already waiting, so the user sees
            // "asked three times" rather than three identical cards.
            val merged = existing.copy(
                citationsJson = TaskJson.encodeToString(
                    ListSerializer(Citation.serializer()),
                    (existing.citations() + citations).distinct(),
                ),
                updatedAtEpochMs = nowEpochMs,
            )
            taskDao.upsert(merged)
            return merged.toDomain()
        }

        val task = Task(
            id = UUID.randomUUID().toString(),
            goal = goal,
            state = TaskState.AWAITING_GOAL_CONFIRMATION,
            origin = origin,
            citations = citations,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        taskDao.upsert(task.toEntity(key))
        return task
    }

    // -- gate transitions ----------------------------------------------------
    //
    // Every one of these goes through TaskGate rather than writing `state`
    // directly, so an illegal move fails here instead of silently producing an
    // agent run the user never authorised.

    suspend fun confirmGoal(id: String) = mutate(id) { TaskGate.confirmGoal(it, now()) }

    suspend fun beginExecution(id: String) = mutate(id) { TaskGate.beginExecution(it, now()) }

    suspend fun deliverArtifact(id: String, artifact: Artifact) =
        mutate(id) { TaskGate.deliverArtifact(it, artifact, now()) }

    suspend fun requestRevision(id: String, note: String) =
        mutate(id) { TaskGate.requestRevision(it, note, now()) }

    /**
     * Gate ③. [secondConfirmationGiven] must come from an actual dialog — the
     * gate refuses otherwise for any destination that writes outward.
     */
    suspend fun adopt(
        id: String,
        destination: Destination? = null,
        secondConfirmationGiven: Boolean = false,
    ) = mutate(id) { TaskGate.adopt(it, destination, secondConfirmationGiven, now()) }

    suspend fun markPublished(id: String) = mutate(id) { TaskGate.markPublished(it, now()) }

    suspend fun fail(id: String, reason: String) = mutate(id) { TaskGate.fail(it, reason, now()) }

    suspend fun reject(id: String) = mutate(id) { TaskGate.reject(it, now()) }

    suspend fun delete(id: String) = taskDao.delete(id)

    private suspend fun mutate(id: String, block: (Task) -> Task): Task? {
        val row = taskDao.find(id) ?: return null
        val updated = block(row.toDomain())
        taskDao.upsert(updated.toEntity(row.intentKey))
        return updated
    }

    private fun now() = System.currentTimeMillis()

    private fun intentKeyFor(goal: String): String = normalizeIntent(goal)
}

/**
 * Normalises a goal into a de-duplication key.
 *
 * Whitespace and punctuation are dropped so "把导出方案发给李总" and
 * "把导出方案，发给李总。" collapse to one intent. Deliberately crude: an
 * over-eager key would merge genuinely different asks, so it only catches
 * near-identical phrasing and leaves the rest to the user.
 *
 * Public because the weekly commitment ledger has to decide whether a published
 * task covers a promise, and it must use exactly the same rule the de-duplicator
 * used when the task was created — otherwise the two disagree.
 */
fun normalizeIntent(goal: String): String = goal.filter { it.isLetterOrDigit() }.lowercase()

// -- mapping --------------------------------------------------------------------

private fun TaskEntity.citations(): List<Citation> =
    runCatching {
        TaskJson.decodeFromString(ListSerializer(Citation.serializer()), citationsJson)
    }.getOrDefault(emptyList())

fun TaskEntity.toDomain() = Task(
    id = id,
    goal = goal,
    state = state,
    origin = origin,
    citations = citations(),
    artifact = artifactKind?.let { kind ->
        Artifact(
            kind = kind,
            title = artifactTitle.orEmpty(),
            body = artifactBody.orEmpty(),
            citations = citations(),
            skillsUsed = skillsUsedCsv?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
        )
    },
    destination = destination,
    revisionRequest = revisionRequest,
    failureReason = failureReason,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

fun Task.toEntity(intentKey: String) = TaskEntity(
    id = id,
    goal = goal,
    state = state,
    origin = origin,
    artifactKind = artifact?.kind,
    artifactTitle = artifact?.title,
    artifactBody = artifact?.body,
    skillsUsedCsv = artifact?.skillsUsed?.joinToString(","),
    destination = destination,
    revisionRequest = revisionRequest,
    failureReason = failureReason,
    citationsJson = TaskJson.encodeToString(ListSerializer(Citation.serializer()), citations),
    intentKey = intentKey,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

/** Default destination for an artifact kind, exposed for the picker UI. */
val ArtifactKind.destinationLabel: String
    get() = when (defaultDestination) {
        Destination.CALENDAR -> "系统日历 + 提醒"
        Destination.NOTION -> "Notion"
        Destination.EMAIL -> "邮箱"
        Destination.LARK -> "飞书"
        Destination.OPENCLAW -> "龙虾 OpenClaw"
        Destination.NONE -> "留在谛听"
    }
