package uk.wakbijok.belanja.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.settings.SettingsRepository
import uk.wakbijok.belanja.ui.theme.JetMono
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/**
 * Diagnostik → Lihat log: the DiagLog ring, newest first, monospace. Share
 * builds the full bundle (log + DB copy + settings snapshot) into a zip and
 * opens the share sheet; server mode adds "Hantar ke pelayan" (/api/diag).
 */
@Composable
fun DiagScreen(
    viewModel: MainViewModel,
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val context = LocalContext.current
    val lines by viewModel.diagLines.collectAsStateWithLifecycle()
    val appMode by viewModel.appMode.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshDiagLines() }

    val snack: (Int) -> Unit = { onSnackbar(context.getString(it)) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(
                stringResource(R.string.diag_export),
                onClick = { viewModel.exportDiag { res -> snack(res) } },
                modifier = Modifier.weight(1f),
                compact = true,
            )
            if (appMode == SettingsRepository.MODE_SERVER) {
                SoftButton(
                    stringResource(R.string.diag_send),
                    onClick = { viewModel.sendDiagToServer { res -> snack(res) } },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (lines.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.diag_title),
                body = stringResource(R.string.diag_empty),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(lines) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetMono),
                        color = hum.ink60,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}
