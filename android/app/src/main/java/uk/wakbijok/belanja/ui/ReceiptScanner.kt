package uk.wakbijok.belanja.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Play Services document scanner: a full-screen camera that locks onto the
 * paper's edges, deskews the perspective, and cleans lighting/shadows, then
 * hands back one PDF. The whole flow runs inside Play Services, so the app
 * needs no CAMERA permission and ships no CV code of its own.
 *
 * Returns a launch function; call it from a button. Cancelling the scanner
 * is silent — only a genuinely unavailable scanner reports [onUnavailable]
 * (no Play Services, or the on-demand module failed to download).
 */
@Composable
fun rememberReceiptScanner(
    onScanned: (Uri) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scanned by rememberUpdatedState(onScanned)
    val unavailable by rememberUpdatedState(onUnavailable)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri
        if (result.resultCode == Activity.RESULT_OK && pdf != null) scanned(pdf)
    }
    return {
        val activity = context.findActivity()
        if (activity == null) {
            unavailable()
        } else {
            GmsDocumentScanning.getClient(SCAN_OPTIONS)
                .getStartScanIntent(activity)
                .addOnSuccessListener { intent ->
                    launcher.launch(IntentSenderRequest.Builder(intent).build())
                }
                .addOnFailureListener { unavailable() }
        }
    }
}

// FULL mode = auto-capture once the edges lock, plus the user-facing
// filter/cleanup step. Gallery import stays off — the file picker beside
// this button already covers existing photos. Long kedai receipts fold
// into a few pages; more than 3 is a document, not a resit.
private val SCAN_OPTIONS = GmsDocumentScannerOptions.Builder()
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .setGalleryImportAllowed(false)
    .setPageLimit(3)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
    .build()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
