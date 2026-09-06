package com.diting.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.diting.ai.AiEndpoint
import com.diting.ai.AiSettings
import com.diting.ai.AsrEndpoint
import com.diting.domain.memory.RetentionPolicy
import com.diting.domain.scene.GlobalIgnoreRules
import com.diting.domain.scene.Scene
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "diting_settings")

private val SettingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * All user configuration: 教小谛's four layers plus the app's own switches.
 *
 * API keys are held apart from everything else, in [EncryptedSharedPreferences]
 * rather than DataStore. A leaked 千问 or 豆包 key costs the user real money, and
 * DataStore writes plaintext to app storage — which is readable on a rooted or
 * backed-up device. The rest of the configuration is not secret and lives in
 * DataStore where it is easy to observe.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ---- AI endpoints ------------------------------------------------------

    /**
     * Endpoint configuration, reassembled from the non-secret part in DataStore
     * and the key in encrypted storage.
     */
    val aiSettings: Flow<AiSettings> = context.dataStore.data.map { prefs ->
        AiSettings(
            transcription = prefs[KEY_ASR_JSON]
                ?.let { runCatching { SettingsJson.decodeFromString<AsrEndpoint>(it) }.getOrNull() }
                ?.copy(apiKey = secure.getString(SECURE_ASR_KEY, "").orEmpty()),
            understanding = prefs[KEY_UNDERSTANDING_JSON]
                ?.let { runCatching { SettingsJson.decodeFromString<AiEndpoint>(it) }.getOrNull() }
                ?.copy(apiKey = secure.getString(SECURE_UNDERSTANDING_KEY, "").orEmpty()),
            agent = prefs[KEY_AGENT_JSON]
                ?.let { runCatching { SettingsJson.decodeFromString<AiEndpoint>(it) }.getOrNull() }
                ?.copy(apiKey = secure.getString(SECURE_AGENT_KEY, "").orEmpty()),
        )
    }

    suspend fun setTranscriptionEndpoint(endpoint: AsrEndpoint?) {
        secure.edit().putString(SECURE_ASR_KEY, endpoint?.apiKey.orEmpty()).apply()
        context.dataStore.edit { prefs ->
            // The persisted copy carries an empty key so a DataStore leak is inert.
            if (endpoint == null) prefs.remove(KEY_ASR_JSON)
            else prefs[KEY_ASR_JSON] = SettingsJson.encodeToString(endpoint.copy(apiKey = ""))
        }
    }

    suspend fun setUnderstandingEndpoint(endpoint: AiEndpoint?) =
        setAiEndpoint(endpoint, KEY_UNDERSTANDING_JSON, SECURE_UNDERSTANDING_KEY)

    suspend fun setAgentEndpoint(endpoint: AiEndpoint?) =
        setAiEndpoint(endpoint, KEY_AGENT_JSON, SECURE_AGENT_KEY)

    private suspend fun setAiEndpoint(
        endpoint: AiEndpoint?,
        jsonKey: Preferences.Key<String>,
        secureKey: String,
    ) {
        secure.edit().putString(secureKey, endpoint?.apiKey.orEmpty()).apply()
        context.dataStore.edit { prefs ->
            if (endpoint == null) prefs.remove(jsonKey)
            else prefs[jsonKey] = SettingsJson.encodeToString(endpoint.copy(apiKey = ""))
        }
    }

    // ---- scenes & rules ----------------------------------------------------

    val scenes: Flow<List<Scene>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SCENES_JSON]
            ?.let { runCatching { SettingsJson.decodeFromString<List<Scene>>(it) }.getOrNull() }
            ?: Scene.BuiltIns
    }

    suspend fun setScenes(scenes: List<Scene>) = context.dataStore.edit {
        it[KEY_SCENES_JSON] = SettingsJson.encodeToString(scenes)
    }

    val globalIgnoreRules: Flow<GlobalIgnoreRules> = context.dataStore.data.map { prefs ->
        prefs[KEY_IGNORE_JSON]
            ?.let {
                runCatching { SettingsJson.decodeFromString<GlobalIgnoreRules>(it) }.getOrNull()
            }
            ?: GlobalIgnoreRules()
    }

    suspend fun setGlobalIgnoreRules(rules: GlobalIgnoreRules) = context.dataStore.edit {
        it[KEY_IGNORE_JSON] = SettingsJson.encodeToString(rules)
    }

    // ---- memory ------------------------------------------------------------

    val retentionPolicy: Flow<RetentionPolicy> = context.dataStore.data.map { prefs ->
        prefs[KEY_RETENTION_JSON]
            ?.let { runCatching { SettingsJson.decodeFromString<RetentionPolicy>(it) }.getOrNull() }
            ?: RetentionPolicy.Default
    }

    suspend fun setRetentionPolicy(policy: RetentionPolicy) = context.dataStore.edit {
        it[KEY_RETENTION_JSON] = SettingsJson.encodeToString(policy)
    }

    // ---- prompts -----------------------------------------------------------

    val summaryPrompt: Flow<String?> = context.dataStore.data.map { it[KEY_SUMMARY_PROMPT] }
    val todoPrompt: Flow<String?> = context.dataStore.data.map { it[KEY_TODO_PROMPT] }
    val insightPrompt: Flow<String?> = context.dataStore.data.map { it[KEY_INSIGHT_PROMPT] }

    suspend fun setSummaryPrompt(value: String?) = putOrRemove(KEY_SUMMARY_PROMPT, value)
    suspend fun setTodoPrompt(value: String?) = putOrRemove(KEY_TODO_PROMPT, value)
    suspend fun setInsightPrompt(value: String?) = putOrRemove(KEY_INSIGHT_PROMPT, value)

    // ---- behaviour switches ------------------------------------------------

    /** Wi-Fi sync is far faster but joins a routeless AP, so it is opt-in. */
    val preferWifiSync: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_PREFER_WIFI] ?: false }

    suspend fun setPreferWifiSync(value: Boolean) =
        context.dataStore.edit { it[KEY_PREFER_WIFI] = value }

    /** Delete a recording from the recorder once it is safely stored locally. */
    val deleteAfterSync: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DELETE_AFTER_SYNC] ?: false }

    suspend fun setDeleteAfterSync(value: Boolean) =
        context.dataStore.edit { it[KEY_DELETE_AFTER_SYNC] = value }

    val onboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ONBOARDED] ?: false }

    suspend fun setOnboardingComplete(value: Boolean) =
        context.dataStore.edit { it[KEY_ONBOARDED] = value }

    val growthPoints: Flow<Int> = context.dataStore.data.map { it[KEY_GROWTH_POINTS] ?: 0 }

    suspend fun addGrowthPoints(delta: Int) = context.dataStore.edit {
        it[KEY_GROWTH_POINTS] = (it[KEY_GROWTH_POINTS] ?: 0) + delta
    }

    /** Phrases the user marked "这不重要" — noise gate 4's learned set. */
    val userIgnoredPhrases: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_IGNORED_PHRASES]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    suspend fun addIgnoredPhrase(phrase: String) = context.dataStore.edit { prefs ->
        val existing = prefs[KEY_IGNORED_PHRASES]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        prefs[KEY_IGNORED_PHRASES] = (existing + phrase).distinct().joinToString("\n")
    }

    private suspend fun putOrRemove(key: Preferences.Key<String>, value: String?) =
        context.dataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
        }

    private companion object {
        val KEY_ASR_JSON = stringPreferencesKey("asr_endpoint")
        val KEY_UNDERSTANDING_JSON = stringPreferencesKey("understanding_endpoint")
        val KEY_AGENT_JSON = stringPreferencesKey("agent_endpoint")
        val KEY_SCENES_JSON = stringPreferencesKey("scenes")
        val KEY_IGNORE_JSON = stringPreferencesKey("global_ignore_rules")
        val KEY_RETENTION_JSON = stringPreferencesKey("retention_policy")
        val KEY_SUMMARY_PROMPT = stringPreferencesKey("summary_prompt")
        val KEY_TODO_PROMPT = stringPreferencesKey("todo_prompt")
        val KEY_INSIGHT_PROMPT = stringPreferencesKey("insight_prompt")
        val KEY_IGNORED_PHRASES = stringPreferencesKey("ignored_phrases")
        val KEY_PREFER_WIFI = booleanPreferencesKey("prefer_wifi_sync")
        val KEY_DELETE_AFTER_SYNC = booleanPreferencesKey("delete_after_sync")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val KEY_GROWTH_POINTS = intPreferencesKey("growth_points")

        const val SECURE_ASR_KEY = "asr_api_key"
        const val SECURE_UNDERSTANDING_KEY = "understanding_api_key"
        const val SECURE_AGENT_KEY = "agent_api_key"
    }
}
