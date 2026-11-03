package uk.wakbijok.belanja.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.data.OwnAccount
import uk.wakbijok.belanja.settings.SettingsRepository
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/**
 * Profil & akaun sendiri. The account list is not decoration: any captured
 * notification that reads as money moving between these accounts (or carrying
 * the owner's name) is a lateral transfer and never enters the ledger.
 */
@Composable
fun ProfileScreen(
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val dao = remember { AppDb.get(context).ownAccountDao() }

    val name by settings.profileName.collectAsStateWithLifecycle(initialValue = "")
    val photoPath by settings.profilePhoto.collectAsStateWithLifecycle(initialValue = "")
    val accounts by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    var nameField by rememberSaveable { mutableStateOf<String?>(null) }
    var newLabel by rememberSaveable { mutableStateOf("") }
    var newLast4 by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(name) { if (nameField == null && name.isNotBlank()) nameField = name }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dest = File(context.filesDir, "profile.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                settings.setProfilePhoto(dest.absolutePath)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // ---- avatar + nama -------------------------------------------------
        Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
            Avatar(
                photoPath = photoPath,
                name = nameField ?: name,
                size = 96,
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )
        }
        Text(
            stringResource(R.string.prof_photo_hint),
            style = MaterialTheme.typography.labelSmall,
            color = hum.ink40,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = nameField ?: "",
            onValueChange = { nameField = it },
            label = { Text(stringResource(R.string.prof_name)) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.prof_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        PushButton(
            stringResource(R.string.prof_save),
            enabled = (nameField ?: "").isNotBlank() && nameField != name,
            onClick = {
                scope.launch {
                    settings.setProfileName(nameField ?: "")
                    onSnackbar(context.getString(R.string.prof_saved))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- akaun sendiri -------------------------------------------------
        Text(
            stringResource(R.string.prof_accounts),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )
        Text(
            stringResource(R.string.prof_accounts_hint),
            style = MaterialTheme.typography.bodySmall,
            color = hum.ink60,
        )
        Spacer(Modifier.height(10.dp))
        HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                if (accounts.isEmpty()) {
                    Text(
                        stringResource(R.string.prof_accounts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = hum.ink40,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                accounts.forEach { acc ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                acc.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (acc.last4.isNotBlank()) {
                                Text(
                                    "•••• ${acc.last4}",
                                    style = MaterialTheme.typography.bodySmall.tnum(),
                                    color = hum.ink60,
                                )
                            }
                        }
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.prof_remove),
                            tint = hum.ink40,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { scope.launch { dao.delete(acc.id) } },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row {
            OutlinedTextField(
                value = newLabel,
                onValueChange = { newLabel = it },
                label = { Text(stringResource(R.string.prof_acc_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = newLast4,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) newLast4 = it },
                label = { Text(stringResource(R.string.prof_acc_last4)) },
                singleLine = true,
                modifier = Modifier.width(120.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        SoftButton(
            stringResource(R.string.prof_acc_add),
            enabled = newLabel.trim().length >= 3,
            onClick = {
                scope.launch {
                    dao.insert(OwnAccount(label = newLabel.trim(), last4 = newLast4))
                    newLabel = ""; newLast4 = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** Photo if picked, else the first letter of the name on pear. */
@Composable
fun Avatar(photoPath: String, name: String, size: Int, onClick: (() -> Unit)? = null) {
    val hum = LocalHumColors.current
    val bmp = remember(photoPath) {
        photoPath.takeIf { it.isNotBlank() && File(it).exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    val base = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = base,
        )
    } else {
        Box(base.background(hum.pear), contentAlignment = Alignment.Center) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "•",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = hum.ink,
            )
        }
    }
}
