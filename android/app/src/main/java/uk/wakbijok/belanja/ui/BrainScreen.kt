package uk.wakbijok.belanja.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.llm.LlmClient
import uk.wakbijok.belanja.llm.LlmError
import uk.wakbijok.belanja.llm.LlmResult
import uk.wakbijok.belanja.llm.Preset
import uk.wakbijok.belanja.settings.SettingsRepository
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/**
 * Otak — BYOK (design/otak-mockup-v2.html, screens 01–05).
 *
 * One screen, four steps: pick a preset, give it an endpoint and a key, pick a
 * model from the list the endpoint itself returned, then live with it. There
 * is deliberately no "test connection" button: loading the model list IS the
 * test, so there is nothing left for the user to forget to press.
 */
@Composable
fun BrainScreen(
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }

    val cfg by settings.llmConfig.collectAsStateWithLifecycle(initialValue = null)
    val features by settings.llmFeatures.collectAsStateWithLifecycle(initialValue = emptySet())
    val testedAt by settings.llmTestedAt.collectAsStateWithLifecycle(initialValue = 0L)
    val latency by settings.llmLatencyMs.collectAsStateWithLifecycle(initialValue = 0L)
    val month = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }
    val usage by settings.llmUsage(month).collectAsStateWithLifecycle(
        initialValue = Triple(0L, 0L, 0L)
    )

    // Wizard state. Null step = "show whatever the stored config implies".
    var step by rememberSaveable { mutableStateOf<String?>(null) }
    var presetId by rememberSaveable { mutableStateOf(Preset.ALL[0].id) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var models by rememberSaveable { mutableStateOf(listOf<String>()) }
    var resolvedBase by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    var manualModel by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LlmError?>(null) }

    val stored = cfg
    LaunchedEffect(stored) {
        if (step == null && stored != null) {
            step = if (stored.configured) STEP_READY else STEP_PROVIDER
            if (stored.configured) {
                presetId = stored.presetId.ifBlank { Preset.ALL[0].id }
                baseUrl = stored.baseUrl
                apiKey = stored.key
            }
        }
    }

    val preset = Preset.byId(presetId)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        when (step) {
            null -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                CircularProgressIndicator(color = hum.pearDeep)
            }

            // ---- 01 · pick a preset ---------------------------------------
            STEP_PROVIDER -> {
                BrainLede(stringResource(R.string.brain_lede))
                BrainSection(stringResource(R.string.brain_direct))
                BrainCard {
                    Preset.ALL.filter { !it.bringYourOwnUrl }.forEach { p ->
                        PresetRow(p, p.id == presetId) { presetId = p.id }
                    }
                }
                BrainSection(stringResource(R.string.brain_compatible))
                BrainCard {
                    Preset.ALL.filter { it.bringYourOwnUrl }.forEach { p ->
                        PresetRow(p, p.id == presetId) { presetId = p.id }
                    }
                }
                BrainNote(stringResource(R.string.brain_wire_note))
                Spacer(Modifier.height(14.dp))
                PushButton(
                    stringResource(R.string.brain_next),
                    onClick = {
                        baseUrl = if (preset.bringYourOwnUrl) "" else preset.base
                        error = null
                        step = STEP_CONNECT
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- 02 · endpoint + key --------------------------------------
            STEP_CONNECT -> {
                BrainSection(preset.label)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; error = null },
                    label = { Text(stringResource(R.string.brain_endpoint)) },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.brain_endpoint_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; error = null },
                    label = { Text(stringResource(R.string.brain_key)) },
                    singleLine = true,
                    visualTransformation =
                        if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                        else PasswordVisualTransformation(),
                    supportingText = { Text(stringResource(R.string.brain_key_hint)) },
                    trailingIcon = {
                        Text(
                            stringResource(if (showKey) R.string.brain_hide else R.string.brain_show),
                            color = hum.cyan,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { showKey = !showKey }
                                .padding(horizontal = 12.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = hum.pearDeep,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.brain_connecting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = hum.ink60,
                        )
                    }
                } else {
                    PushButton(
                        stringResource(R.string.brain_connect),
                        enabled = baseUrl.isNotBlank(),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                when (val r = LlmClient.listModels(preset.wire, baseUrl, apiKey)) {
                                    is LlmResult.Ok -> {
                                        models = r.value.models
                                        resolvedBase = r.value.resolvedBase
                                        settings.recordLlmTest(
                                            System.currentTimeMillis(),
                                            r.value.latencyMs,
                                        )
                                        filter = ""
                                        step = STEP_MODELS
                                    }
                                    is LlmResult.Err -> error = r.error
                                }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let { e ->
                    Spacer(Modifier.height(12.dp))
                    ErrorCard(e)
                    // 404 is the one failure the user can still work around:
                    // the endpoint is alive, it just serves no model list.
                    if (e is LlmError.NoModelList) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualModel,
                            onValueChange = { manualModel = it },
                            label = { Text(stringResource(R.string.brain_model_manual)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        SoftButton(
                            stringResource(R.string.brain_use_id),
                            enabled = manualModel.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    settings.setLlmConnection(
                                        preset.id, preset.wire, baseUrl.trim().trimEnd('/'), apiKey,
                                    )
                                    settings.setLlmModel(manualModel.trim())
                                    step = STEP_READY
                                    onSnackbar(context.getString(R.string.brain_saved))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                BrainNote(stringResource(R.string.brain_where_key))
                Spacer(Modifier.height(10.dp))
                SoftButton(
                    stringResource(R.string.brain_back),
                    onClick = { error = null; step = STEP_PROVIDER },
                )
            }

            // ---- 03 · pick a model ----------------------------------------
            STEP_MODELS -> {
                OkLine(
                    stringResource(
                        R.string.brain_connected_v,
                        models.size,
                        hostOf(resolvedBase),
                        latency,
                    )
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(R.string.brain_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val shown = models.filter { it.contains(filter, ignoreCase = true) }
                BrainCard {
                    if (shown.isEmpty()) {
                        Text(
                            stringResource(R.string.brain_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = hum.ink60,
                            modifier = Modifier.padding(vertical = 14.dp),
                        )
                    }
                    shown.forEach { id ->
                        ModelRow(id, id == cfg?.model) {
                            scope.launch {
                                settings.setLlmConnection(preset.id, preset.wire, resolvedBase, apiKey)
                                settings.setLlmModel(id)
                                step = STEP_READY
                                onSnackbar(context.getString(R.string.brain_saved))
                            }
                        }
                    }
                }
                BrainNote(stringResource(R.string.brain_fetched_note))
                Spacer(Modifier.height(10.dp))
                SoftButton(stringResource(R.string.brain_back), onClick = { step = STEP_CONNECT })
            }

            // ---- 05 · ready ------------------------------------------------
            else -> {
                val live = stored ?: return@Column
                BrainSection(stringResource(R.string.brain_current))
                Surface(color = hum.paper3, shape = CardShape, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            live.model.ifBlank { stringResource(R.string.brain_no_model) },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${Preset.byId(live.presetId).label} · ${hostOf(live.baseUrl)}" +
                                if (testedAt > 0) " · ${timeOf(testedAt)} · ${latency}ms" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = hum.ink60,
                        )
                        // A blank key with a stored endpoint means the KeyStore
                        // entry is gone (restored onto another device, or the
                        // app data was cleared). Say so — a silent 401 later
                        // would send the user hunting the wrong problem.
                        if (live.key.isBlank() && live.baseUrl.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.brain_key_lost),
                                style = MaterialTheme.typography.bodySmall,
                                color = hum.coral,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton(
                                stringResource(R.string.brain_change_model),
                                compact = true,
                                onClick = {
                                    presetId = live.presetId.ifBlank { Preset.ALL[0].id }
                                    baseUrl = live.baseUrl
                                    apiKey = live.key
                                    error = null
                                    step = STEP_CONNECT
                                },
                            )
                            SoftButton(
                                stringResource(R.string.brain_remove),
                                compact = true,
                                onClick = {
                                    scope.launch {
                                        settings.clearLlm()
                                        presetId = Preset.ALL[0].id
                                        baseUrl = ""
                                        apiKey = ""
                                        models = emptyList()
                                        step = STEP_PROVIDER
                                        onSnackbar(context.getString(R.string.brain_removed))
                                    }
                                },
                            )
                        }
                    }
                }

                val enabled by settings.llmEnabled.collectAsStateWithLifecycle(initialValue = true)
                BrainCard {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.brain_master),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            // Usage as information, not as a guard to manage:
                            // the internal cap still protects, silently.
                            Text(
                                stringResource(
                                    R.string.brain_usage_v,
                                    usage.first, usage.second, usage.third,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = hum.ink60,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on -> scope.launch { settings.setLlmEnabled(on) } },
                        )
                    }
                }

                BrainSection(stringResource(R.string.brain_allowed))
                BrainCard {
                    FeatureRow(
                        R.string.brain_f_category, R.string.brain_f_category_sub,
                        features.contains(SettingsRepository.LLM_F_CATEGORY),
                    ) { on -> scope.launch { settings.setLlmFeature(SettingsRepository.LLM_F_CATEGORY, on) } }
                    FeatureRow(
                        R.string.brain_f_story, R.string.brain_f_story_sub,
                        features.contains(SettingsRepository.LLM_F_STORY),
                    ) { on -> scope.launch { settings.setLlmFeature(SettingsRepository.LLM_F_STORY, on) } }
                    FeatureRow(
                        R.string.brain_f_makcik, R.string.brain_f_makcik_sub,
                        features.contains(SettingsRepository.LLM_F_MAKCIK),
                    ) { on -> scope.launch { settings.setLlmFeature(SettingsRepository.LLM_F_MAKCIK, on) } }
                    if (features.contains(SettingsRepository.LLM_F_MAKCIK)) {
                        val persona by settings.makcikPersona.collectAsStateWithLifecycle(
                            initialValue = SettingsRepository.MAKCIK_MAKCIK
                        )
                        Row(Modifier.padding(start = 2.dp, bottom = 8.dp)) {
                            listOf(
                                SettingsRepository.MAKCIK_SOPAN to R.string.makcik_p_sopan,
                                SettingsRepository.MAKCIK_MAKCIK to R.string.makcik_p_makcik,
                                SettingsRepository.MAKCIK_PEDAS to R.string.makcik_p_pedas,
                            ).forEachIndexed { i, (id, label) ->
                                if (i > 0) Text(
                                    " · ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = hum.ink.copy(alpha = 0.4f),
                                )
                                val active = persona == id
                                Text(
                                    stringResource(label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (active) hum.ink else hum.cyan,
                                    modifier = Modifier.clickable(enabled = !active) {
                                        scope.launch { settings.setMakcikPersona(id) }
                                    },
                                )
                            }
                        }
                    }
                    FeatureRow(
                        R.string.brain_f_advisor, R.string.brain_f_advisor_sub,
                        features.contains(SettingsRepository.LLM_F_ADVISOR),
                    ) { on -> scope.launch { settings.setLlmFeature(SettingsRepository.LLM_F_ADVISOR, on) } }
                }

                BrainSection(stringResource(R.string.brain_privacy))
                BrainNote(stringResource(R.string.brain_privacy_body))
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private const val STEP_PROVIDER = "provider"
private const val STEP_CONNECT = "connect"
private const val STEP_MODELS = "models"
private const val STEP_READY = "ready"

/** Host only — a full URL wraps badly and the host is what identifies it. */
private fun hostOf(url: String): String = runCatching {
    java.net.URI(url).host ?: url
}.getOrDefault(url).ifBlank { url }

private fun timeOf(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

@Composable
private fun BrainSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun BrainCard(content: @Composable () -> Unit) {
    val hum = LocalHumColors.current
    HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
private fun BrainLede(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalHumColors.current.ink.copy(alpha = 0.88f),
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun BrainNote(text: String) {
    val hum = LocalHumColors.current
    HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = hum.ink60,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun PresetRow(preset: Preset, selected: Boolean, onPick: () -> Unit) {
    val hum = LocalHumColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                preset.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (preset.bringYourOwnUrl) {
                    stringResource(
                        if (preset.wire == uk.wakbijok.belanja.llm.Wire.OPENAI) {
                            R.string.brain_compat_openai_eg
                        } else {
                            R.string.brain_compat_anthropic_eg
                        }
                    )
                } else {
                    preset.base.removePrefix("https://")
                },
                style = MaterialTheme.typography.bodySmall,
                color = hum.ink60,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        WireTag(preset.wire)
    }
}

/** Makes it visible that Grok is the OpenAI form, not a third code path. */
@Composable
private fun WireTag(wire: uk.wakbijok.belanja.llm.Wire) {
    val hum = LocalHumColors.current
    val openAi = wire == uk.wakbijok.belanja.llm.Wire.OPENAI
    Surface(
        color = (if (openAi) hum.cyan else hum.pearDeep).copy(alpha = 0.16f),
        shape = CircleShape,
    ) {
        Text(
            stringResource(if (openAi) R.string.brain_wire_openai else R.string.brain_wire_anthropic),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (openAi) hum.cyan else hum.ink,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ModelRow(id: String, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick)
        Spacer(Modifier.width(4.dp))
        Text(
            id,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FeatureRow(titleRes: Int, subRes: Int, on: Boolean, onToggle: (Boolean) -> Unit) {
    val hum = LocalHumColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(subRes),
                style = MaterialTheme.typography.bodySmall,
                color = hum.ink60,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

@Composable
private fun OkLine(text: String) {
    val hum = LocalHumColors.current
    Surface(
        color = hum.mint.copy(alpha = 0.16f),
        shape = CardShape,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}


/**
 * Each failure names its code and what it means for the user. "An error
 * occurred" helps nobody, and a spinner that never resolves is worse than an
 * error — that was the Cukai tab, and it cost an evening of confusion.
 */
@Composable
private fun ErrorCard(e: LlmError) {
    val hum = LocalHumColors.current
    val code = when (e) {
        is LlmError.Unauthorized -> e.code.toString()
        is LlmError.NoModelList -> "404"
        is LlmError.Http -> e.code.toString()
        is LlmError.ThinkingRanOut -> "—"
        is LlmError.Unreachable, is LlmError.BadShape -> "—"
    }
    val title = when (e) {
        is LlmError.Unauthorized -> stringResource(R.string.brain_e_key_title)
        is LlmError.NoModelList -> stringResource(R.string.brain_e_nolist_title)
        is LlmError.Http -> stringResource(R.string.brain_e_http_title)
        is LlmError.Unreachable -> stringResource(R.string.brain_e_unreach_title)
        is LlmError.BadShape -> stringResource(R.string.brain_e_shape_title)
        is LlmError.ThinkingRanOut -> stringResource(R.string.brain_e_think_title)
    }
    val body = when (e) {
        is LlmError.Unauthorized -> stringResource(R.string.brain_e_key_body)
        is LlmError.NoModelList -> stringResource(R.string.brain_e_nolist_body)
        is LlmError.Http -> stringResource(R.string.brain_e_http_body, e.detail)
        is LlmError.Unreachable -> stringResource(R.string.brain_e_unreach_body, e.reason)
        is LlmError.BadShape -> stringResource(R.string.brain_e_shape_body, e.reason)
        is LlmError.ThinkingRanOut -> stringResource(R.string.brain_e_think_body, e.tokensSpent)
    }
    Surface(
        color = hum.coral.copy(alpha = 0.11f),
        shape = CardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = hum.coral.copy(alpha = 0.18f), shape = CircleShape) {
                    Text(
                        code,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = hum.coral,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
