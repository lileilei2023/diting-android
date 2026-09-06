package com.diting.app.di

import android.content.Context
import androidx.room.Room
import com.diting.ai.AiSettings
import com.diting.ai.asr.AsrClient
import com.diting.ai.asr.OpenAiCompatibleAsrClient
import com.diting.ai.llm.OpenAiCompatibleLlmClient
import com.diting.ai.understanding.UnderstandingService
import com.diting.app.data.db.DeviceDao
import com.diting.app.data.db.DitingDatabase
import com.diting.app.data.db.HotwordDao
import com.diting.app.data.db.InsightDao
import com.diting.app.data.db.MemoryDao
import com.diting.app.data.db.ProactiveCardDao
import com.diting.app.data.db.SegmentDao
import com.diting.app.data.db.SessionDao
import com.diting.app.data.db.SpeakerDao
import com.diting.app.data.db.TaskDao
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.device.Mr20DeviceManager
import com.diting.app.device.wifi.Mr20WifiTransfer
import com.diting.protocol.farosh.FaroshClient
import com.diting.protocol.farosh.FaroshTokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Directory holding synced recordings. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RecordingsDir

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Outlives any one screen: the BLE pumps and a running file sync must survive
     * the user navigating away, and are cancelled only when the process dies.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DitingDatabase =
        Room.databaseBuilder(context, DitingDatabase::class.java, DitingDatabase.NAME)
            // No fallbackToDestructiveMigration: these are the user's recordings
            // and transcripts, and silently dropping them on a schema change is
            // not an acceptable failure mode. Ship a Migration instead.
            .build()

    @Provides fun provideSessionDao(db: DitingDatabase): SessionDao = db.sessions()
    @Provides fun provideSegmentDao(db: DitingDatabase): SegmentDao = db.segments()
    @Provides fun provideSpeakerDao(db: DitingDatabase): SpeakerDao = db.speakers()
    @Provides fun provideTaskDao(db: DitingDatabase): TaskDao = db.tasks()
    @Provides fun provideMemoryDao(db: DitingDatabase): MemoryDao = db.memory()
    @Provides fun provideInsightDao(db: DitingDatabase): InsightDao = db.insights()
    @Provides fun provideCardDao(db: DitingDatabase): ProactiveCardDao = db.proactiveCards()
    @Provides fun provideHotwordDao(db: DitingDatabase): HotwordDao = db.hotwords()
    @Provides fun provideDeviceDao(db: DitingDatabase): DeviceDao = db.devices()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        // Transcribing an hour of audio can legitimately take minutes; the default
        // 10s read timeout would abort every long recording.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    @RecordingsDir
    fun provideRecordingsDir(@ApplicationContext context: Context): File =
        File(context.filesDir, "recordings").apply { mkdirs() }

    @Provides
    @Singleton
    fun provideWifiTransfer(@ApplicationContext context: Context) = Mr20WifiTransfer(context)

    @Provides
    @Singleton
    fun provideDeviceManager(
        @ApplicationContext context: Context,
        deviceDao: DeviceDao,
        wifiTransfer: Mr20WifiTransfer,
        @ApplicationScope scope: CoroutineScope,
    ) = Mr20DeviceManager(context, deviceDao, wifiTransfer, scope)

    @Provides
    @Singleton
    fun provideFaroshClient(okHttp: OkHttpClient): FaroshClient =
        FaroshClient(okHttp, FaroshTokenStore.inMemory())
}

/**
 * Builds AI clients from whatever the user configured.
 *
 * Deliberately *not* injected as singletons: the endpoints are user-editable at
 * runtime, and a client cached at graph-construction time would keep using the
 * old key after the user fixes it — which reads to them as "the app ignored my
 * change".
 */
@Singleton
class AiClientFactory @Inject constructor(
    private val settings: SettingsStore,
    private val okHttp: OkHttpClient,
) {

    suspend fun current(): AiSettings = settings.aiSettings.first()

    /** Null when transcription is unconfigured; callers surface that to the user. */
    suspend fun asrClient(): AsrClient? =
        current().transcription?.let { OpenAiCompatibleAsrClient(it, okHttp) }

    suspend fun understanding(): UnderstandingService? =
        current().understanding?.let {
            UnderstandingService(OpenAiCompatibleLlmClient(it, okHttp))
        }

    /** Falls back to the understanding model when no agent model is set. */
    suspend fun agent(): UnderstandingService? =
        current().agentOrUnderstanding?.let {
            UnderstandingService(OpenAiCompatibleLlmClient(it, okHttp))
        }
}
