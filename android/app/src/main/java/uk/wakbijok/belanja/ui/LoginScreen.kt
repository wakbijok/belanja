package uk.wakbijok.belanja.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/** Log masuk (mockup screen 09): pulsing pear mark, BM copy, autofill kept. */
@Composable
fun LoginScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val hum = LocalHumColors.current
    val busy by viewModel.loginBusy.collectAsStateWithLifecycle()
    val error by viewModel.loginError.collectAsStateWithLifecycle()

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PulsingDot(56.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            stringResource(R.string.login_tag),
            style = MaterialTheme.typography.bodyMedium,
            color = hum.ink60,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.login_user)) },
            shape = RoundedCornerShape(12.dp),
            // Autofill hints so a password manager (Bitwarden) offers to fill and save.
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username },
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.login_pass)) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Password },
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = hum.coral,
            )
        }
        Spacer(Modifier.height(24.dp))
        if (busy) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            PushButton(
                stringResource(R.string.login_btn),
                onClick = { viewModel.login(username, password) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        // Quiet escape hatch: full app, no server — SAF backup instead.
        Text(
            stringResource(R.string.login_solo),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = hum.cyan,
            modifier = Modifier
                .clickable(enabled = !busy) { viewModel.useSoloMode() }
                .padding(8.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.login_vpn),
            style = MaterialTheme.typography.bodySmall,
            color = hum.ink40,
        )
    }
}
