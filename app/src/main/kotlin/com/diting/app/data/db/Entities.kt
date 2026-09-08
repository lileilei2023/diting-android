package com.diting.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.diting.domain.memory.MemoryNodeType
import com.diting.domain.memory.NodeConfidence
import com.diting.domain.model.DeviceKind
import com.diting.domain.model.Intent
import com.diting.domain.model.TranscriptState
import com.diting.domain.task.ArtifactKind
import com.diting.domain.task.Destination
import com.diting.domain.task.TaskOrigin
import com.diting.domain.task.TaskState

@Entity(
    tableName = "sessions",
    indices = [
        Index("startedAtEpochMs"),
        // Sync dedup: a device file must map to at most one session.
        Index(value = ["device", "deviceFilePath"], unique = true),
    ],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val device: DeviceKind,
    /** Local file; null once the 30-day audio window closes. */
    val audioPath: String?,
    /** `2025-08-13/REC0001.MP3` on an MR20, or the Farosh recordId. */
    val deviceFilePath: String?,
    val sceneId: String?,
    val sceneOverridden: Boolean = false,
    val transcriptState: TranscriptState = TranscriptState.PENDING,
    val transcriptError: String? = null,
    /** Bytes already synced, so an interrupted transfer can resume. */
    val syncedBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Summary, once generated. */
    val summaryJson: String? = null,
    val isCited: Boolean = false,
    /** Epoch ms when the brain accepted this recording through /upload; null = not yet. */
    val brainUploadedAt: Long? = null,
    /**
     * Set when this recording was folded into a longer episode: the row stays for
     * sync de-duplication and citations, but every list hides it.
     */
    val mergedIntoId: String? = null,
)

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId"), Index(value = ["sessionId", "startMs"])],
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val speakerLabel: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Set when the user corrected the ASR output; the original is kept. */
    val correctedFrom: String? = null,
    val intent: Intent? = null,
    /**
     * True once a task, insight or report cites this segment. This is the flag
     * the retention sweeper reads, and the reason it must be set at citation
     * time rather than derived by a join at sweep time.
     */
    val isCited: Boolean = false,
)

@Entity(tableName = "speakers", indices = [Index("sessionId")])
data class SpeakerEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val label: String,
    val personId: String? = null,
    val displayName: String? = null,
    val isOwner: Boolean = false,
)

@Entity(tableName = "tasks", indices = [Index("state"), Index("updatedAtEpochMs")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val state: TaskState,
    val origin: TaskOrigin,
    val artifactKind: ArtifactKind? = null,
    val artifactTitle: String? = null,
    val artifactBody: String? = null,
    val skillsUsedCsv: String? = null,
    val destination: Destination? = null,
    val revisionRequest: String? = null,
    val failureReason: String? = null,
    /** JSON array of citations — see [Converters]. */
    val citationsJson: String = "[]",
    /**
     * Hash of the normalised goal. Used to suppress a second agent run for the
     * same intent: "同一意图去抖，不反复起 Agent".
     */
    val intentKey: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "memory_nodes", indices = [Index("type"), Index("lastSeenEpochMs")])
data class MemoryNodeEntity(
    @PrimaryKey val id: String,
    val type: MemoryNodeType,
    val label: String,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val mentionCount: Int = 1,
    val archived: Boolean = false,
    val isCited: Boolean = false,
    val confidence: NodeConfidence = NodeConfidence.PROPOSED,
    val citationsJson: String = "[]",
)

@Entity(
    tableName = "memory_edges",
    primaryKeys = ["fromId", "toId", "relation"],
    indices = [Index("fromId"), Index("toId")],
)
data class MemoryEdgeEntity(
    val fromId: String,
    val toId: String,
    val relation: String,
    val weight: Float = 1f,
)

/** 洞察 — a cross-session observation. */
@Entity(tableName = "insights", indices = [Index("createdAtEpochMs")])
data class InsightEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val kind: String,
    val citationsJson: String = "[]",
    /** PROPOSED until the user confirms it into the graph. */
    val confidence: NodeConfidence = NodeConfidence.PROPOSED,
    val createdAtEpochMs: Long,
    val dismissed: Boolean = false,
)

/** 主动卡 — "被你忽略的提醒". */
@Entity(tableName = "proactive_cards", indices = [Index("createdAtEpochMs")])
data class ProactiveCardEntity(
    @PrimaryKey val id: String,
    val headline: String,
    val quote: String?,
    val rationale: String,
    val sourceNodeId: String?,
    val citationsJson: String = "[]",
    val createdAtEpochMs: Long,
    val actedOn: Boolean = false,
    /** Set when the user says it did not matter — feeds noise gate 4. */
    val dismissedAsUnimportant: Boolean = false,
)

@Entity(tableName = "hotwords", indices = [Index(value = ["word"], unique = true)])
data class HotwordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val pronunciation: String? = null,
    /** ONBOARDING / CORRECTION / DISCOVERED / MANUAL / IMPORTED — shown as a chip. */
    val source: String,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val kind: DeviceKind,
    val name: String,
    /** BLE MAC for an MR20. */
    val address: String? = null,
    /**
     * MR20 bind key. Stored here as the value the *device* accepted, which is the
     * input truncated to 16 characters — see Mr20Command.SetSecretKey.
     */
    val bindKey: String? = null,
    val firmwareVersion: String? = null,
    val wifiFirmwareVersion: String? = null,
    val batteryPercent: Int? = null,
    val freeMb: Long? = null,
    val totalMb: Long? = null,
    val lastSeenEpochMs: Long = 0,
    val isDefault: Boolean = false,
)

/** Type-safe enums and JSON blobs. */
class Converters {
    @TypeConverter fun deviceKindTo(v: DeviceKind) = v.name
    @TypeConverter fun deviceKindFrom(v: String) = DeviceKind.valueOf(v)

    @TypeConverter fun transcriptStateTo(v: TranscriptState) = v.name
    @TypeConverter fun transcriptStateFrom(v: String) = TranscriptState.valueOf(v)

    @TypeConverter fun intentTo(v: Intent?) = v?.name
    @TypeConverter fun intentFrom(v: String?) = v?.let { Intent.valueOf(it) }

    @TypeConverter fun taskStateTo(v: TaskState) = v.name
    @TypeConverter fun taskStateFrom(v: String) = TaskState.valueOf(v)

    @TypeConverter fun taskOriginTo(v: TaskOrigin) = v.name
    @TypeConverter fun taskOriginFrom(v: String) = TaskOrigin.valueOf(v)

    @TypeConverter fun artifactKindTo(v: ArtifactKind?) = v?.name
    @TypeConverter fun artifactKindFrom(v: String?) = v?.let { ArtifactKind.valueOf(it) }

    @TypeConverter fun destinationTo(v: Destination?) = v?.name
    @TypeConverter fun destinationFrom(v: String?) = v?.let { Destination.valueOf(it) }

    @TypeConverter fun nodeTypeTo(v: MemoryNodeType) = v.name
    @TypeConverter fun nodeTypeFrom(v: String) = MemoryNodeType.valueOf(v)

    @TypeConverter fun confidenceTo(v: NodeConfidence) = v.name
    @TypeConverter fun confidenceFrom(v: String) = NodeConfidence.valueOf(v)
}
