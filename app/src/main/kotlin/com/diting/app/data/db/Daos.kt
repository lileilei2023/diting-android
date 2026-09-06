package com.diting.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import com.diting.domain.memory.MemoryNodeType
import com.diting.domain.model.DeviceKind
import com.diting.domain.task.TaskState
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Upsert suspend fun upsert(session: SessionEntity)

    @Upsert suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT * FROM sessions
        WHERE startedAtEpochMs >= :fromEpochMs AND startedAtEpochMs < :toEpochMs
        ORDER BY startedAtEpochMs DESC
        """
    )
    fun observeBetween(fromEpochMs: Long, toEpochMs: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun find(id: String): SessionEntity?

    /** Dedup key for sync: the device's own path is the only stable identity. */
    @Query("SELECT * FROM sessions WHERE device = :device AND deviceFilePath = :path")
    suspend fun findByDeviceFile(device: DeviceKind, path: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE transcriptState = 'PENDING' ORDER BY startedAtEpochMs")
    suspend fun pendingTranscription(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    /** Audio past its window and uncited — the retention sweeper's work list. */
    @Query(
        """
        SELECT * FROM sessions
        WHERE audioPath IS NOT NULL AND isCited = 0 AND startedAtEpochMs < :cutoffEpochMs
        """
    )
    suspend fun audioExpiredBefore(cutoffEpochMs: Long): List<SessionEntity>

    @Query("UPDATE sessions SET audioPath = NULL WHERE id = :id")
    suspend fun clearAudioPath(id: String)

    @Query("UPDATE sessions SET isCited = 1 WHERE id = :id")
    suspend fun markCited(id: String)

    /**
     * Device path -> bytes already stored, used to skip finished files and to
     * resume partial ones via the protocol's `haveBytes` form.
     */
    @Query(
        """
        SELECT deviceFilePath AS path, syncedBytes AS bytes FROM sessions
        WHERE device = :device AND deviceFilePath IS NOT NULL
        """
    )
    suspend fun syncedByDevice(device: DeviceKind): List<SyncedFileRow>

    /**
     * Summaries only. 今日谛听 needs every summarised session in a day but not
     * their transcripts, and pulling whole rows for that would drag the stored
     * JSON of every session through the UI thread's diffing.
     */
    @Query(
        """
        SELECT id, summaryJson FROM sessions
        WHERE startedAtEpochMs >= :fromEpochMs AND startedAtEpochMs < :toEpochMs
          AND summaryJson IS NOT NULL
        ORDER BY startedAtEpochMs
        """
    )
    fun observeSummariesBetween(
        fromEpochMs: Long,
        toEpochMs: Long,
    ): Flow<List<SessionSummaryRow>>

    @Query("SELECT COUNT(*) FROM sessions")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM sessions WHERE startedAtEpochMs >= :since")
    fun observeDurationSince(since: Long): Flow<Long>
}

/** Projection for [SessionDao.syncedByDevice]. */
data class SyncedFileRow(val path: String, val bytes: Long)

/** Projection for [SessionDao.observeSummariesBetween]. */
data class SessionSummaryRow(val id: String, val summaryJson: String)

@Dao
interface SegmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<SegmentEntity>)

    @Update suspend fun update(segment: SegmentEntity)

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY startMs")
    fun observeForSession(sessionId: String): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY startMs")
    suspend fun forSession(sessionId: String): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun find(id: String): SegmentEntity?

    @Query("SELECT * FROM segments WHERE text LIKE '%' || :query || '%' ORDER BY startMs LIMIT 200")
    suspend fun search(query: String): List<SegmentEntity>

    @Query("UPDATE segments SET isCited = 1 WHERE id IN (:ids)")
    suspend fun markCited(ids: List<String>)

    @Query("SELECT COUNT(*) FROM segments WHERE correctedFrom IS NOT NULL")
    fun observeCorrectionCount(): Flow<Int>

    /**
     * Uncited transcript text past the compression age. Returned whole so the
     * caller can summarise a session's worth at once rather than per segment.
     */
    @Query(
        """
        SELECT s.* FROM segments s
        JOIN sessions ss ON ss.id = s.sessionId
        WHERE s.isCited = 0 AND ss.startedAtEpochMs < :cutoffEpochMs
        """
    )
    suspend fun uncitedBefore(cutoffEpochMs: Long): List<SegmentEntity>
}

@Dao
interface SpeakerDao {
    @Upsert suspend fun upsertAll(speakers: List<SpeakerEntity>)

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId")
    fun observeForSession(sessionId: String): Flow<List<SpeakerEntity>>

    @Query("UPDATE speakers SET personId = :personId, displayName = :name WHERE id = :id")
    suspend fun assignPerson(id: String, personId: String?, name: String?)
}

@Dao
interface TaskDao {

    @Upsert suspend fun upsert(task: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE state IN (:states) ORDER BY updatedAtEpochMs DESC")
    fun observeByState(states: List<TaskState>): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun find(id: String): TaskEntity?

    /**
     * Used to de-duplicate: a repeated intent must not start a second agent run
     * while the first is still open.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE intentKey = :intentKey AND state NOT IN ('PUBLISHED', 'REJECTED')
        LIMIT 1
        """
    )
    suspend fun findOpenByIntent(intentKey: String): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE state IN ('AWAITING_GOAL_CONFIRMATION', 'AWAITING_RESULT_CONFIRMATION')")
    fun observeGateCount(): Flow<Int>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MemoryDao {

    @Upsert suspend fun upsertNode(node: MemoryNodeEntity)

    @Upsert suspend fun upsertNodes(nodes: List<MemoryNodeEntity>)

    @Upsert suspend fun upsertEdges(edges: List<MemoryEdgeEntity>)

    @Query("SELECT * FROM memory_nodes WHERE archived = 0 ORDER BY lastSeenEpochMs DESC")
    fun observeLive(): Flow<List<MemoryNodeEntity>>

    @Query("SELECT * FROM memory_nodes WHERE type = :type ORDER BY lastSeenEpochMs DESC")
    fun observeByType(type: MemoryNodeType): Flow<List<MemoryNodeEntity>>

    @Query("SELECT * FROM memory_nodes WHERE id = :id")
    suspend fun findNode(id: String): MemoryNodeEntity?

    @Query("SELECT * FROM memory_nodes WHERE label = :label AND type = :type LIMIT 1")
    suspend fun findNodeByLabel(label: String, type: MemoryNodeType): MemoryNodeEntity?

    @Query(
        """
        SELECT * FROM memory_nodes
        WHERE archived = 0 AND isCited = 0 AND lastSeenEpochMs < :cutoffEpochMs
        """
    )
    suspend fun coldNodes(cutoffEpochMs: Long): List<MemoryNodeEntity>

    @Query("UPDATE memory_nodes SET archived = 1 WHERE id IN (:ids)")
    suspend fun archive(ids: List<String>)

    @Query("UPDATE memory_nodes SET confidence = :confidence WHERE id = :id")
    suspend fun setConfidence(id: String, confidence: String)

    @Query("SELECT COUNT(*) FROM memory_nodes WHERE archived = 0")
    fun observeLiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memory_nodes WHERE archived = 1")
    fun observeArchivedCount(): Flow<Int>

    @Query("SELECT * FROM memory_edges WHERE fromId = :nodeId OR toId = :nodeId")
    suspend fun edgesFor(nodeId: String): List<MemoryEdgeEntity>

    @Query("DELETE FROM memory_nodes")
    suspend fun deleteAllNodes()

    @Query("DELETE FROM memory_edges")
    suspend fun deleteAllEdges()

    /** 「全部遗忘」 — behind a second confirmation in the UI. */
    @Transaction
    suspend fun forgetEverything() {
        deleteAllEdges()
        deleteAllNodes()
    }
}

@Dao
interface InsightDao {
    @Upsert suspend fun upsert(insight: InsightEntity)

    @Query("SELECT * FROM insights WHERE dismissed = 0 ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<InsightEntity>>

    @Query("SELECT * FROM insights WHERE id = :id")
    fun observe(id: String): Flow<InsightEntity?>

    @Query("UPDATE insights SET dismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: String)

    @Query("UPDATE insights SET confidence = :confidence WHERE id = :id")
    suspend fun setConfidence(id: String, confidence: String)
}

@Dao
interface ProactiveCardDao {
    @Upsert suspend fun upsert(card: ProactiveCardEntity)

    @Query(
        """
        SELECT * FROM proactive_cards
        WHERE actedOn = 0 AND dismissedAsUnimportant = 0
        ORDER BY createdAtEpochMs DESC
        """
    )
    fun observeOpen(): Flow<List<ProactiveCardEntity>>

    @Query("UPDATE proactive_cards SET actedOn = 1 WHERE id = :id")
    suspend fun markActedOn(id: String)

    @Query("UPDATE proactive_cards SET dismissedAsUnimportant = 1 WHERE id = :id")
    suspend fun dismissAsUnimportant(id: String)

    @Query("SELECT COUNT(*) FROM proactive_cards WHERE dismissedAsUnimportant = 1 AND createdAtEpochMs >= :since")
    fun observeDismissedSince(since: Long): Flow<Int>
}

@Dao
interface HotwordDao {
    @Upsert suspend fun upsert(hotword: HotwordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(hotword: HotwordEntity): Long

    @Query("SELECT * FROM hotwords ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<HotwordEntity>>

    @Query("SELECT word FROM hotwords WHERE enabled = 1")
    suspend fun enabledWords(): List<String>

    @Query("UPDATE hotwords SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM hotwords WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DeviceDao {
    @Upsert suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices ORDER BY lastSeenEpochMs DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun find(id: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE isDefault = 1 LIMIT 1")
    fun observeDefault(): Flow<DeviceEntity?>

    @Query("UPDATE devices SET isDefault = 0")
    suspend fun clearDefaults()

    @Transaction
    suspend fun makeDefault(device: DeviceEntity) {
        clearDefaults()
        upsert(device.copy(isDefault = true))
    }

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        SessionEntity::class,
        SegmentEntity::class,
        SpeakerEntity::class,
        TaskEntity::class,
        MemoryNodeEntity::class,
        MemoryEdgeEntity::class,
        InsightEntity::class,
        ProactiveCardEntity::class,
        HotwordEntity::class,
        DeviceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DitingDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun segments(): SegmentDao
    abstract fun speakers(): SpeakerDao
    abstract fun tasks(): TaskDao
    abstract fun memory(): MemoryDao
    abstract fun insights(): InsightDao
    abstract fun proactiveCards(): ProactiveCardDao
    abstract fun hotwords(): HotwordDao
    abstract fun devices(): DeviceDao

    companion object {
        const val NAME = "diting.db"
    }
}
