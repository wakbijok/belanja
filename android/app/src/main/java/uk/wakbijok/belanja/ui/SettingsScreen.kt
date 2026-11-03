package uk.wakbijok.belanja.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uk.wakbijok.belanja.BuildConfig
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.settings.SettingsRepository
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/** Tetapan (mockup screen 11): Aplikasi / Pelayan / Penyata / Tangkapan / Akaun. */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    notificationAccessGranted: Boolean,
    onRequestNotificationAccess: () -> Unit,
    batteryUnrestricted: Boolean,
    onFixBattery: () -> Unit,
    onOpenStatement: () -> Unit,
    onOpenDiag: () -> Unit,
    onOpenBrain: () -> Unit,
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val context = LocalContext.current
    val pending by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val lastSyncMsg by viewModel.lastSyncMsg.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val statements by viewModel.statements.collectAsStateWithLifecycle()
    val candidates by viewModel.candidateSources.collectAsStateWithLifecycle()
    val extraSources by viewModel.extraSources.collectAsStateWithLifecycle()
    val appMode by viewModel.appMode.collectAsStateWithLifecycle()
    val safFolderName by viewModel.safFolderName.collectAsStateWithLifecycle()
    val lastBackupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val lastBackupMsg by viewModel.lastBackupMsg.collectAsStateWithLifecycle()
    val isSolo = appMode == SettingsRepository.MODE_SOLO
    val brainCfg by SettingsRepository(context).llmConfig
        .collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(appMode) { viewModel.refreshStatements() }

    var showChangePassword by rememberSaveable { mutableStateOf(false) }
    val snack: (Int) -> Unit = { onSnackbar(context.getString(it)) }

    val statementPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.uploadStatement(it) { res -> snack(res) } } }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Keep the grant across reboots — the hourly worker depends on it.
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setSafTree(it)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // ---- Aplikasi -----------------------------------------------------
                SectionHead(stringResource(R.string.set_app))
                SettingsCard {
                    KvSetting(stringResource(R.string.set_lang)) {
                        val isEn = AppCompatDelegate.getApplicationLocales()
                            .toLanguageTags().startsWith("en")
                        LinkChoice(
                            options = listOf(
                                stringResource(R.string.set_lang_ms) to !isEn,
                                stringResource(R.string.set_lang_en) to isEn,
                            ),
                            onSelect = { idx ->
                                AppCompatDelegate.setApplicationLocales(
                                    LocaleListCompat.forLanguageTags(if (idx == 1) "en" else "ms")
                                )
                            },
                        )
                    }
                    DashedDivider()
                    KvSetting(stringResource(R.string.set_theme)) {
                        LinkChoice(
                            options = listOf(
                                stringResource(R.string.set_theme_system) to
                                    (themeMode == SettingsRepository.THEME_SYSTEM),
                                stringResource(R.string.set_theme_light) to
                                    (themeMode == SettingsRepository.THEME_LIGHT),
                                stringResource(R.string.set_theme_dark) to
                                    (themeMode == SettingsRepository.THEME_DARK),
                            ),
                            onSelect = { idx ->
                                viewModel.setThemeMode(
                                    when (idx) {
                                        1 -> SettingsRepository.THEME_LIGHT
                                        2 -> SettingsRepository.THEME_DARK
                                        else -> SettingsRepository.THEME_SYSTEM
                                    }
                                )
                            },
                        )
                    }
                    // Mode is fixed standalone. No server/linked toggle.
                }

                // ---- Sandaran (SAF) — on-device backup folder ----
                SectionHead(stringResource(R.string.set_backup))
                if (safFolderName.isBlank()) {
                    HumCard(color = hum.coral.copy(alpha = 0.09f), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.set_backup_prompt),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(10.dp))
                            PushButton(
                                stringResource(R.string.set_backup_pick),
                                onClick = { treePicker.launch(null) },
                                compact = true,
                            )
                        }
                    }
                } else {
                    SettingsCard {
                        KvSetting(stringResource(R.string.set_backup_folder)) {
                            Row {
                                ValueText(safFolderName)
                                Text(" · ", color = hum.ink40)
                                Text(
                                    stringResource(R.string.set_backup_change),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = hum.cyan,
                                    modifier = Modifier.clickable { treePicker.launch(null) },
                                )
                            }
                        }
                        DashedDivider()
                        KvSetting(stringResource(R.string.set_status)) {
                            ValueText(lastBackupMsg)
                        }
                        DashedDivider()
                        KvSetting(stringResource(R.string.set_backup_last)) {
                            ValueText(
                                if (lastBackupAt == 0L) {
                                    stringResource(R.string.set_never)
                                } else {
                                    SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                                        .format(Date(lastBackupAt))
                                }
                            )
                        }
                        DashedDivider()
                        KvSetting("") {
                            Text(
                                stringResource(R.string.set_backup_now),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = hum.cyan,
                                modifier = Modifier.clickable {
                                    viewModel.backupNow()
                                    snack(R.string.backup_started)
                                },
                            )
                        }
                    }
                }

                // ---- Penyata --------------------------------------------------------
                SectionHead(stringResource(R.string.set_stmt))
                SettingsCard {
                    val latest = statements.firstOrNull()
                    KvSetting(latest?.let { monthTitle(it.month) } ?: stringResource(R.string.set_stmt)) {
                        if (latest == null) {
                            ValueText(stringResource(R.string.set_stmt_none), color = hum.ink40)
                        } else {
                            Text(
                                if (latest.unresolved > 0) {
                                    stringResource(R.string.set_stmt_open, latest.unresolved)
                                } else {
                                    stringResource(R.string.set_stmt_clear)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (latest.unresolved > 0) hum.coral else hum.mint,
                                modifier = Modifier.clickable(onClick = onOpenStatement),
                            )
                        }
                    }
                    DashedDivider()
                    KvSetting(stringResource(R.string.set_stmt_up)) {
                        Text(
                            stringResource(R.string.set_stmt_up_v),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = hum.cyan,
                            modifier = Modifier.clickable {
                                statementPicker.launch(arrayOf("application/pdf", "text/*"))
                            },
                        )
                    }
                }

                // ---- Tangkapan -------------------------------------------------------
                SectionHead(stringResource(R.string.set_capture))
                SettingsCard {
                    KvSetting(stringResource(R.string.set_notif)) {
                        if (notificationAccessGranted) {
                    ValueText(stringResource(R.string.set_active), color = hum.mint)
                } else {
                    Text(
                        stringResource(R.string.set_inactive),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = hum.coral,
                        modifier = Modifier.clickable(onClick = onRequestNotificationAccess),
                    )
                }
            }
            DashedDivider()
            KvSetting(stringResource(R.string.set_batt)) {
                if (batteryUnrestricted) {
                    ValueText(stringResource(R.string.set_unrestricted), color = hum.mint)
                } else {
                    Text(
                        stringResource(R.string.set_restricted),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = hum.coral,
                        modifier = Modifier.clickable(onClick = onFixBattery),
                    )
                }
            }
            // Monitored-sources list hidden 28-07: plumbing, not a decision —
            // users can't act on it. Candidates below stay: those ARE a decision.
            if (candidates.isNotEmpty()) {
                DashedDivider()
                Text(
                    stringResource(R.string.set_candidates),
                    style = MaterialTheme.typography.bodyMedium,
                    color = hum.ink60,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    stringResource(R.string.set_candidates_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = hum.ink40,
                )
                candidates.forEach { pkg ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pkg,
                            style = MaterialTheme.typography.bodySmall.tnum(),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.set_candidate_add),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = hum.cyan,
                            modifier = Modifier.clickable { viewModel.addSource(pkg) },
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            stringResource(R.string.set_candidate_no),
                            style = MaterialTheme.typography.bodyMedium,
                            color = hum.ink40,
                            modifier = Modifier.clickable { viewModel.dismissCandidate(pkg) },
                        )
                    }
                }
            }
        }

        // ---- Penyelenggaraan --------------------------------------------------
        SectionHead(stringResource(R.string.recat_section))
        SettingsCard {
            KvSetting(stringResource(R.string.recat_row)) {
                Text(
                    stringResource(R.string.recat_run),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = hum.cyan,
                    modifier = Modifier.clickable {
                        viewModel.recategorizeServer { res, count ->
                            onSnackbar(context.getString(res, count))
                        }
                    },
                )
            }
            Text(
                stringResource(R.string.recat_hint),
                style = MaterialTheme.typography.bodySmall,
                color = hum.ink60,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        // ---- Otak (BYOK; off until the user brings a key) --------------------
        SectionHead(stringResource(R.string.brain_title))
        SettingsCard {
            KvSetting(stringResource(R.string.set_brain)) {
                Text(
                    brainCfg?.takeIf { it.configured }?.model
                        ?: stringResource(R.string.set_brain_off),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = hum.cyan,
                    textAlign = TextAlign.End,
                    modifier = Modifier.clickable(onClick = onOpenBrain),
                )
            }
        }

        // ---- Diagnostik (both modes — Izu's phone-side visibility) -----------
        SectionHead(stringResource(R.string.diag_title))
        SettingsCard {
            KvSetting(stringResource(R.string.diag_view)) {
                Text(
                    stringResource(R.string.diag_open),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = hum.cyan,
                    modifier = Modifier.clickable(onClick = onOpenDiag),
                )
            }
            DashedDivider()
            KvSetting(stringResource(R.string.diag_export)) {
                Text(
                    stringResource(R.string.diag_export_v),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = hum.cyan,
                    modifier = Modifier.clickable {
                        viewModel.exportDiag { res -> snack(res) }
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        // Standalone product: no server account / logout block.

        Spacer(Modifier.height(20.dp))
        MonoLabel(
            "BELANJA v${BuildConfig.VERSION_NAME}",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }

    // Change-password dialog kept for legacy call sites; not shown in UI.
    if (false && showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            onSave = { cur, new ->
                showChangePassword = false
                viewModel.changePassword(cur, new) { res -> snack(res) }
            },
        )
    }
}

// ---- Bits -----------------------------------------------------------------------

@Composable
private fun SectionHead(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val hum = LocalHumColors.current
    HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
private fun KvSetting(key: String, value: @Composable () -> Unit) {
    val hum = LocalHumColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = hum.ink60,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

/** Label above, value below — for values too long to sit on one line
 *  (the monitored-sources list outgrew the row layout when the allowlist
 *  went from 3 packages to 8). */
@Composable
private fun KvStacked(key: String, value: String) {
    val hum = LocalHumColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = hum.ink60)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ValueText(text: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        textAlign = TextAlign.End,
    )
}

/** "A · B · C" where the active option is ink-bold and the rest are cyan links. */
@Composable
private fun LinkChoice(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    val hum = LocalHumColors.current
    Row {
        options.forEachIndexed { i, (label, active) ->
            if (i > 0) {
                Text(" · ", style = MaterialTheme.typography.bodyMedium, color = hum.ink40)
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                color = if (active) hum.ink else hum.cyan,
                modifier = Modifier.clickable(enabled = !active) { onSelect(i) },
            )
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSave: (current: String, new: String) -> Unit,
) {
    var current by rememberSaveable { mutableStateOf("") }
    var newPass by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chpass_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it },
                    label = { Text(stringResource(R.string.chpass_current)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPass,
                    onValueChange = { newPass = it },
                    label = { Text(stringResource(R.string.chpass_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(current, newPass) },
                enabled = current.isNotBlank() && newPass.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** "2026-07" → "Julai 2026" in the current locale. */
internal fun monthTitle(monthKey: String): String = runCatching {
    val d = SimpleDateFormat("yyyy-MM", Locale.US).parse(monthKey)!!
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(d)
}.getOrDefault(monthKey)

/** Human labels for the real monitored allowlist (SettingsRepository.SOURCE_PACKAGES). */
private fun sourceLabels(extra: Set<String> = emptySet()): String =
    (uk.wakbijok.belanja.settings.SettingsRepository.SOURCE_PACKAGES + extra)
    .map { pkg ->
        when {
            pkg.contains("spay") -> "Samsung Pay"
            pkg.contains("maybank2u.life") -> "MAE"
            pkg.contains("shopeepay") -> "ShopeePay"
            pkg.contains("shopee") -> "Shopee"
            pkg.contains("tngdigital") -> "TNG"
            pkg.contains("setel") -> "Setel"
            pkg.contains("grabtaxi") -> "Grab"
            else -> pkg
        }
    }
    .distinct()
    .joinToString(" · ")
