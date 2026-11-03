package uk.wakbijok.belanja.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import uk.wakbijok.belanja.data.Cycle
import uk.wakbijok.belanja.llm.KeyVault
import uk.wakbijok.belanja.llm.LlmConfig
import uk.wakbijok.belanja.llm.Wire

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Zero-config app: the sync base URL is baked into BuildConfig and source
 * packages are fixed. Persisted here: the login session (JWT) and the overall
 * monthly budget. Per-category budgets live in Room.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        /** Verified against the device, never guessed — a wrong prefix drops
         *  everything silently (com.tngdigital vs my.com.tngdigital, 27-07). */
        val SOURCE_PACKAGES = setOf(
            "com.samsung.android.spay",   // Samsung Wallet
            "my.com.maybank2u.life",      // Maybank MAE
            "com.maybank2u.life",         // Maybank2u
            "com.shopee.my",              // Shopee (ShopeePay/SPayLater notifications)
            "com.shopeepay.my",           // ShopeePay standalone
            "my.com.tngdigital.ewallet",  // Touch 'n Go eWallet
            "com.setel.mobile",           // Setel
            "com.grabtaxi.passenger",     // Grab (GrabPay/GrabFood)
            "com.cimb.cimbocto",          // CIMB OCTO
            "com.rhbgroup.rhbmobilebanking",
        )
        const val DEFAULT_BUDGET_CENTS = 150_000L // RM1500.00

        private val KEY_BUDGET = longPreferencesKey("budget_cents")
        private val KEY_CYCLE_DAY = longPreferencesKey("cycle_start_day")
        private val KEY_JWT = stringPreferencesKey("session_jwt")
        private val KEY_SYNC_AT = longPreferencesKey("last_sync_at")
        private val KEY_SYNC_MSG = stringPreferencesKey("last_sync_msg")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_MODE = stringPreferencesKey("app_mode")
        private val KEY_SAF_TREE = stringPreferencesKey("saf_tree_uri")
        private val KEY_SAF_NAME = stringPreferencesKey("saf_folder_name")
        private val KEY_BACKUP_AT = longPreferencesKey("last_backup_at")
        private val KEY_BACKUP_MSG = stringPreferencesKey("last_backup_msg")
        private val KEY_DB_BACKUP_DAY = stringPreferencesKey("last_db_backup_day")
        private val KEY_EXTRA_SOURCES = stringSetPreferencesKey("extra_sources")
        private val KEY_CANDIDATES = stringSetPreferencesKey("candidate_sources")

        // ---- Otak (BYOK). The key is stored SEALED; see llm/KeyVault.kt. ----
        private val KEY_LLM_PRESET = stringPreferencesKey("llm_preset")
        private val KEY_LLM_WIRE = stringPreferencesKey("llm_wire")
        private val KEY_LLM_BASE = stringPreferencesKey("llm_base")
        private val KEY_LLM_KEY_SEALED = stringPreferencesKey("llm_key_sealed")
        private val KEY_LLM_MODEL = stringPreferencesKey("llm_model")
        private val KEY_LLM_TESTED_AT = longPreferencesKey("llm_tested_at")
        private val KEY_LLM_LATENCY = longPreferencesKey("llm_latency_ms")
        private val KEY_LLM_FEATURES = stringSetPreferencesKey("llm_features")
        private val KEY_LLM_MONTH = stringPreferencesKey("llm_usage_month")
        private val KEY_LLM_CALLS = longPreferencesKey("llm_calls")
        private val KEY_LLM_TOK_IN = longPreferencesKey("llm_tok_in")
        private val KEY_LLM_TOK_OUT = longPreferencesKey("llm_tok_out")
        private val KEY_LLM_ENABLED = booleanPreferencesKey("llm_enabled")

        /** Feature switches, all off until the user turns them on. */
        const val LLM_F_CATEGORY = "category"
        const val LLM_F_STORY = "story"
        const val LLM_F_MAKCIK = "makcik"
        const val LLM_F_ADVISOR = "advisor"

        // No call cap: per-purchase roasts alone can pass 300/month, and the
        // master switch is the real control. Usage is counted for DISPLAY so
        // the user can price it against their provider's own bill.

        // ---- Makcik Bawang + cerita bulan ----
        private val KEY_MAKCIK_PERSONA = stringPreferencesKey("makcik_persona")
        private val KEY_MAKCIK_FIRED = stringSetPreferencesKey("makcik_fired")
        private val KEY_MURAI_CHAT_DAY = stringPreferencesKey("murai_chat_day")
        private val KEY_MURAI_SAID = stringSetPreferencesKey("murai_said")
        private val KEY_PROFILE_NAME = stringPreferencesKey("profile_name")
        private val KEY_PROFILE_PHOTO = stringPreferencesKey("profile_photo")
        private val KEY_STORY_MONTH = stringPreferencesKey("story_month")
        private val KEY_STORY_TEXT = stringPreferencesKey("story_text")
        private val KEY_STORY_COUNT = longPreferencesKey("story_txn_count")

        const val MAKCIK_SOPAN = "sopan"
        const val MAKCIK_MAKCIK = "makcik"
        const val MAKCIK_PEDAS = "pedas"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** @deprecated Linked server is unsupported; kept only so old DataStore keys decode. */
        const val MODE_SERVER = "server"

        /** Standalone on-device product (the only supported mode). */
        const val MODE_SOLO = "solo"
    }

    /**
     * App mode. Product is standalone Android only — always [MODE_SOLO].
     * Linked/server mode is not supported in current or future releases.
     * Stored KEY_MODE is ignored so upgrades leave any old "server" choice.
     */
    val appMode: Flow<String> = context.dataStore.data.map { MODE_SOLO }

    suspend fun currentAppMode(): String = MODE_SOLO

    /** No-op for callers that still flip mode; product path stays solo. */
    suspend fun setAppMode(mode: String) =
        context.dataStore.edit { it[KEY_MODE] = MODE_SOLO }

    /** SAF backup tree URI (persisted permission); empty = not picked yet. */
    val safTreeUri: Flow<String> = context.dataStore.data.map { p -> p[KEY_SAF_TREE] ?: "" }

    suspend fun currentSafTree(): String = safTreeUri.first()

    val safFolderName: Flow<String> = context.dataStore.data.map { p -> p[KEY_SAF_NAME] ?: "" }

    suspend fun setSafTree(uri: String, name: String) = context.dataStore.edit {
        it[KEY_SAF_TREE] = uri
        it[KEY_SAF_NAME] = name
    }

    /** Last SAF backup pass (epoch millis, 0 = never) and its outcome. */
    val lastBackupAt: Flow<Long> = context.dataStore.data.map { p -> p[KEY_BACKUP_AT] ?: 0L }

    val lastBackupMsg: Flow<String> =
        context.dataStore.data.map { p -> p[KEY_BACKUP_MSG] ?: "belum pernah cuba" }

    suspend fun recordBackup(at: Long, msg: String) = context.dataStore.edit {
        it[KEY_BACKUP_AT] = at
        it[KEY_BACKUP_MSG] = msg
    }

    /** Local day ("yyyy-MM-dd") of the last daily DB copy to the SAF tree. */
    suspend fun lastDbBackupDay(): String =
        context.dataStore.data.map { p -> p[KEY_DB_BACKUP_DAY] ?: "" }.first()

    suspend fun setLastDbBackupDay(day: String) =
        context.dataStore.edit { it[KEY_DB_BACKUP_DAY] = day }

    /** Packages the user added themselves (on top of SOURCE_PACKAGES). */
    val extraSources: Flow<Set<String>> =
        context.dataStore.data.map { p -> p[KEY_EXTRA_SOURCES] ?: emptySet() }

    suspend fun currentSources(): Set<String> =
        SOURCE_PACKAGES + (extraSources.first())

    suspend fun addSource(pkg: String) = context.dataStore.edit { p ->
        p[KEY_EXTRA_SOURCES] = (p[KEY_EXTRA_SOURCES] ?: emptySet()) + pkg
        p[KEY_CANDIDATES] = (p[KEY_CANDIDATES] ?: emptySet()) - pkg
    }

    /**
     * Unmonitored packages caught pushing payment-shaped notifications.
     * The Shopee (beta8) and TNG (beta3) misses were both a wrong or missing
     * package name; this lets the phone report the gap instead of hiding it.
     */
    val candidateSources: Flow<Set<String>> =
        context.dataStore.data.map { p -> p[KEY_CANDIDATES] ?: emptySet() }

    suspend fun noteCandidate(pkg: String) = context.dataStore.edit { p ->
        if (pkg !in (p[KEY_EXTRA_SOURCES] ?: emptySet())) {
            p[KEY_CANDIDATES] = (p[KEY_CANDIDATES] ?: emptySet()) + pkg
        }
    }

    suspend fun dismissCandidate(pkg: String) = context.dataStore.edit { p ->
        p[KEY_CANDIDATES] = (p[KEY_CANDIDATES] ?: emptySet()) - pkg
    }

    // ---- Otak (BYOK) ----------------------------------------------------

    /**
     * The brain as configured, with the key unsealed for use. Emits a config
     * with a blank key when the KeyStore entry is gone (app data wiped, or a
     * restore onto another device) — the caller then asks for the key again
     * rather than failing with a confusing 401.
     */
    val llmConfig: Flow<LlmConfig> = context.dataStore.data.map { p ->
        LlmConfig(
            presetId = p[KEY_LLM_PRESET] ?: "",
            wire = Wire.from(p[KEY_LLM_WIRE]),
            baseUrl = p[KEY_LLM_BASE] ?: "",
            key = KeyVault.unseal(p[KEY_LLM_KEY_SEALED] ?: ""),
            model = p[KEY_LLM_MODEL] ?: "",
        )
    }

    suspend fun currentLlmConfig(): LlmConfig = llmConfig.first()

    /** Stores the connection. [baseUrl] should be the base that actually worked. */
    suspend fun setLlmConnection(presetId: String, wire: Wire, baseUrl: String, key: String) =
        context.dataStore.edit { p ->
            p[KEY_LLM_PRESET] = presetId
            p[KEY_LLM_WIRE] = wire.id
            p[KEY_LLM_BASE] = baseUrl
            p[KEY_LLM_KEY_SEALED] = KeyVault.seal(key)
        }

    suspend fun setLlmModel(model: String) = context.dataStore.edit { it[KEY_LLM_MODEL] = model }

    suspend fun recordLlmTest(at: Long, latencyMs: Long) = context.dataStore.edit {
        it[KEY_LLM_TESTED_AT] = at
        it[KEY_LLM_LATENCY] = latencyMs
    }

    val llmTestedAt: Flow<Long> = context.dataStore.data.map { it[KEY_LLM_TESTED_AT] ?: 0L }

    val llmLatencyMs: Flow<Long> = context.dataStore.data.map { it[KEY_LLM_LATENCY] ?: 0L }

    /** Forgets the connection and destroys the sealing key with it. */
    suspend fun clearLlm() {
        context.dataStore.edit { p ->
            listOf(
                KEY_LLM_PRESET, KEY_LLM_WIRE, KEY_LLM_BASE, KEY_LLM_KEY_SEALED, KEY_LLM_MODEL,
            ).forEach { p.remove(it) }
            listOf(KEY_LLM_TESTED_AT, KEY_LLM_LATENCY).forEach { p.remove(it) }
            p[KEY_LLM_FEATURES] = emptySet()
        }
        KeyVault.destroy()
    }

    /** Master switch for Akal. On by default once a brain is connected. */
    val llmEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_LLM_ENABLED] ?: true }

    suspend fun setLlmEnabled(on: Boolean) =
        context.dataStore.edit { it[KEY_LLM_ENABLED] = on }

    /** Which brain features the user has switched on. Empty until they do. */
    val llmFeatures: Flow<Set<String>> =
        context.dataStore.data.map { it[KEY_LLM_FEATURES] ?: emptySet() }

    /**
     * What the engines consult: the master switch gates everything, so one
     * toggle silences every worker without touching per-feature choices.
     */
    suspend fun currentLlmFeatures(): Set<String> =
        if (llmEnabled.first()) llmFeatures.first() else emptySet()

    suspend fun setLlmFeature(feature: String, on: Boolean) = context.dataStore.edit { p ->
        val cur = p[KEY_LLM_FEATURES] ?: emptySet()
        p[KEY_LLM_FEATURES] = if (on) cur + feature else cur - feature
    }

    /** Calls and tokens used in [month] ("yyyy-MM"); resets when the month turns. */
    fun llmUsage(month: String): Flow<Triple<Long, Long, Long>> =
        context.dataStore.data.map { p ->
            if ((p[KEY_LLM_MONTH] ?: "") != month) Triple(0L, 0L, 0L)
            else Triple(p[KEY_LLM_CALLS] ?: 0L, p[KEY_LLM_TOK_IN] ?: 0L, p[KEY_LLM_TOK_OUT] ?: 0L)
        }

    /** Counts one call against [month] for the usage display. Never blocks. */
    suspend fun noteLlmCall(month: String, tokensIn: Int, tokensOut: Int) {
        context.dataStore.edit { p ->
            val fresh = (p[KEY_LLM_MONTH] ?: "") != month
            p[KEY_LLM_MONTH] = month
            p[KEY_LLM_CALLS] = (if (fresh) 0L else p[KEY_LLM_CALLS] ?: 0L) + 1
            p[KEY_LLM_TOK_IN] = (if (fresh) 0L else p[KEY_LLM_TOK_IN] ?: 0L) + tokensIn
            p[KEY_LLM_TOK_OUT] = (if (fresh) 0L else p[KEY_LLM_TOK_OUT] ?: 0L) + tokensOut
        }
    }

    // ---- Profil ------------------------------------------------------------

    /** Owner's name — doubles as the self-transfer signal (DuitNow to self). */
    val profileName: Flow<String> =
        context.dataStore.data.map { it[KEY_PROFILE_NAME] ?: "" }

    suspend fun currentProfileName(): String = profileName.first()

    suspend fun setProfileName(name: String) =
        context.dataStore.edit { it[KEY_PROFILE_NAME] = name.trim() }

    /** Path (in filesDir) of the profile photo; empty = initial-letter avatar. */
    val profilePhoto: Flow<String> =
        context.dataStore.data.map { it[KEY_PROFILE_PHOTO] ?: "" }

    suspend fun setProfilePhoto(path: String) =
        context.dataStore.edit { it[KEY_PROFILE_PHOTO] = path }

    // ---- Makcik Bawang -----------------------------------------------------

    val makcikPersona: Flow<String> =
        context.dataStore.data.map { it[KEY_MAKCIK_PERSONA] ?: MAKCIK_MAKCIK }

    suspend fun currentMakcikPersona(): String = makcikPersona.first()

    suspend fun setMakcikPersona(p: String) =
        context.dataStore.edit { it[KEY_MAKCIK_PERSONA] = p }

    /**
     * "yyyy-MM:threshold" markers for nags already sent. Old months are pruned
     * on write so the set never grows past a handful of entries.
     */
    suspend fun currentMakcikFired(): Set<String> =
        context.dataStore.data.map { it[KEY_MAKCIK_FIRED] ?: emptySet() }.first()

    suspend fun addMakcikFired(marker: String) = context.dataStore.edit { p ->
        val month = marker.substringBefore(":")
        p[KEY_MAKCIK_FIRED] = ((p[KEY_MAKCIK_FIRED] ?: emptySet()) + marker)
            .filter { it.startsWith(month) }.toSet()
    }

    /** Local day of the last bebel comment — Murai jokes at most once a day. */
    suspend fun currentMuraiChatDay(): String =
        context.dataStore.data.map { it[KEY_MURAI_CHAT_DAY] ?: "" }.first()

    suspend fun setMuraiChatDay(day: String) =
        context.dataStore.edit { it[KEY_MURAI_CHAT_DAY] = day }

    // ---- What Murai has already said ----------------------------------------
    // A habit mentioned once is an observation; mentioned at every capture it
    // is a nag. Entries are "epochDay|signalId" and expire after 45 days.

    suspend fun currentMuraiSaid(): Set<String> =
        context.dataStore.data.map { p ->
            (p[KEY_MURAI_SAID] ?: emptySet()).map { it.substringAfter('|') }.toSet()
        }.first()

    suspend fun addMuraiSaid(ids: Collection<String>, now: Long = System.currentTimeMillis()) =
        context.dataStore.edit { p ->
            val today = now / 86_400_000L
            val kept = (p[KEY_MURAI_SAID] ?: emptySet())
                .filter { today - (it.substringBefore('|').toLongOrNull() ?: 0L) <= 45 }
            p[KEY_MURAI_SAID] = (kept + ids.map { "$today|$it" }).toSet()
        }

    // ---- Cerita bulan (cache satu panggilan sebulan) -----------------------

    suspend fun currentStory(): Triple<String, String, Int> =
        context.dataStore.data.map { p ->
            Triple(p[KEY_STORY_MONTH] ?: "", p[KEY_STORY_TEXT] ?: "", (p[KEY_STORY_COUNT] ?: 0L).toInt())
        }.first()

    suspend fun setStory(month: String, text: String, txnCount: Int) =
        context.dataStore.edit { p ->
            p[KEY_STORY_MONTH] = month
            p[KEY_STORY_TEXT] = text
            p[KEY_STORY_COUNT] = txnCount.toLong()
        }

    /** UI theme: system / light / dark (design/design.md — Hum, two palettes). */
    val themeMode: Flow<String> = context.dataStore.data.map { p ->
        p[KEY_THEME] ?: THEME_SYSTEM
    }

    suspend fun setThemeMode(mode: String) =
        context.dataStore.edit { it[KEY_THEME] = mode }

    /** Username shown in Tetapan → Akaun; recorded at login. */
    val username: Flow<String> = context.dataStore.data.map { p -> p[KEY_USERNAME] ?: "" }

    suspend fun setUsername(name: String) =
        context.dataStore.edit { it[KEY_USERNAME] = name }

    val budgetCents: Flow<Long> = context.dataStore.data.map { p ->
        p[KEY_BUDGET] ?: DEFAULT_BUDGET_CENTS
    }

    suspend fun setBudgetCents(cents: Long) =
        context.dataStore.edit { it[KEY_BUDGET] = cents }

    /**
     * Day of month the budget cycle starts (payday), 1..28; 1 = calendar
     * month. Every "month" figure in the app — dashboard, history, trend,
     * the brain's facts — is computed against this boundary.
     */
    val cycleStartDay: Flow<Int> = context.dataStore.data.map { p ->
        (p[KEY_CYCLE_DAY] ?: 1L).toInt().coerceIn(Cycle.MIN_DAY, Cycle.MAX_DAY)
    }

    suspend fun currentCycleStartDay(): Int = cycleStartDay.first()

    suspend fun setCycleStartDay(day: Int) = context.dataStore.edit {
        it[KEY_CYCLE_DAY] = day.coerceIn(Cycle.MIN_DAY, Cycle.MAX_DAY).toLong()
    }

    /** JWT session token; empty = not logged in. */
    val sessionToken: Flow<String> = context.dataStore.data.map { p -> p[KEY_JWT] ?: "" }

    suspend fun currentSessionToken(): String = sessionToken.first()

    suspend fun setSessionToken(token: String) =
        context.dataStore.edit { it[KEY_JWT] = token }

    suspend fun clearSession() = context.dataStore.edit { it.remove(KEY_JWT) }

    /** Last sync attempt (epoch millis, 0 = never) and its outcome. */
    val lastSyncAt: Flow<Long> = context.dataStore.data.map { p -> p[KEY_SYNC_AT] ?: 0L }

    val lastSyncMsg: Flow<String> = context.dataStore.data.map { p -> p[KEY_SYNC_MSG] ?: "belum pernah cuba" }

    suspend fun recordSync(at: Long, msg: String) = context.dataStore.edit {
        it[KEY_SYNC_AT] = at
        it[KEY_SYNC_MSG] = msg
    }
}
