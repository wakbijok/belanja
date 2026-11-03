package uk.wakbijok.belanja.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.settings.SettingsRepository
import uk.wakbijok.belanja.sync.SyncScheduler
import uk.wakbijok.belanja.ui.theme.BelanjaTheme

/** Screens pushed on top of the tab layer. */
private sealed interface Pushed {
    data class CategoryDetail(val category: String) : Pushed
    data class ReliefDetail(val code: String) : Pushed
    data object Settings : Pushed
    data object Statement : Pushed
    data object Diag : Pushed
    data object Brain : Pushed
    data object Profile : Pushed
}

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** Opens the system dialog; on OEMs that hide it, falls back to the settings list. */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        runCatching {
            startActivity(
                Intent(
                    SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncScheduler.ensurePeriodic(applicationContext)
        requestPostNotificationsIfNeeded()
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                SettingsRepository.THEME_LIGHT -> false
                SettingsRepository.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            // Keep the AppCompat window background (pre-compose frame) in step
            // with the in-app theme choice, not just the system setting.
            androidx.compose.runtime.LaunchedEffect(themeMode) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    when (themeMode) {
                        SettingsRepository.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        SettingsRepository.THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
            }
            BelanjaTheme(darkTheme = darkTheme) {
                val appMode by viewModel.appMode.collectAsStateWithLifecycle()
                // Standalone product: never gate on server login.
                if (appMode == null) {
                    Unit // DataStore still loading
                } else {
                    MainScaffold(
                        viewModel,
                        onOpenNotificationAccess = {
                            startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        isBatteryUnrestricted = { isIgnoringBatteryOptimizations() },
                        onFixBattery = { requestIgnoreBatteryOptimizations() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    viewModel: MainViewModel,
    onOpenNotificationAccess: () -> Unit,
    isBatteryUnrestricted: () -> Boolean,
    onFixBattery: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { list ->
                list.map {
                    when (it) {
                        is Pushed.CategoryDetail -> "cat:${it.category}"
                        is Pushed.ReliefDetail -> "relief:${it.code}"
                        Pushed.Settings -> "settings"
                        Pushed.Statement -> "statement"
                        Pushed.Diag -> "diag"
                        Pushed.Brain -> "brain"
                        Pushed.Profile -> "profile"
                    }
                }
            },
            restore = { saved ->
                saved.map {
                    when {
                        it.startsWith("cat:") -> Pushed.CategoryDetail(it.removePrefix("cat:"))
                        it.startsWith("relief:") -> Pushed.ReliefDetail(it.removePrefix("relief:"))
                        it == "statement" -> Pushed.Statement
                        it == "diag" -> Pushed.Diag
                        it == "brain" -> Pushed.Brain
                        it == "profile" -> Pushed.Profile
                        else -> Pushed.Settings
                    }
                }.toMutableStateList()
            },
        )
    ) { mutableListOf<Pushed>().toMutableStateList() }

    /** Butiran transaksi opens as a bottom sheet over whatever is showing. */
    var detailTxnId by rememberSaveable { mutableStateOf<String?>(null) }
    /** Expand "Notifikasi asal" when opening from the review/Akal queue. */
    var detailExpandRaw by rememberSaveable { mutableStateOf(false) }
    fun openTxnDetail(id: String, expandRaw: Boolean = false) {
        detailExpandRaw = expandRaw
        detailTxnId = id
    }

    val top = stack.lastOrNull()
    BackHandler(enabled = top != null) { stack.removeAt(stack.lastIndex) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showSnack: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    val context = LocalContext.current
    val notificationAccessGranted = rememberOnResume {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }
    val batteryUnrestricted = rememberOnResume(isBatteryUnrestricted)

    val needsReview by viewModel.needsReviewCount.collectAsStateWithLifecycle()
    val taxSummary by viewModel.taxSummary.collectAsStateWithLifecycle()

    // Drawer kiri (menu tiga baris): profil di kepala, pintasan di bawah.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val settingsRepo = remember { uk.wakbijok.belanja.settings.SettingsRepository(context) }
    val profileName by settingsRepo.profileName.collectAsStateWithLifecycle(initialValue = "")
    val profilePhoto by settingsRepo.profilePhoto.collectAsStateWithLifecycle(initialValue = "")
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    fun drawerGo(dest: Pushed) {
        scope.launch { drawerState.close() }
        if (stack.lastOrNull() != dest) stack.add(dest)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = top == null,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { drawerGo(Pushed.Profile) }
                            .padding(vertical = 8.dp),
                    ) {
                        Avatar(photoPath = profilePhoto, name = profileName, size = 52)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                profileName.ifBlank { stringResource(R.string.prof_unnamed) },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(R.string.prof_open),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    DrawerItem(stringResource(R.string.prof_title)) { drawerGo(Pushed.Profile) }
                    DrawerItem(stringResource(R.string.set_title)) { drawerGo(Pushed.Settings) }
                    DrawerItem(stringResource(R.string.brain_title)) { drawerGo(Pushed.Brain) }
                    DrawerItem(stringResource(R.string.stmt_title)) { drawerGo(Pushed.Statement) }
                    DrawerItem(stringResource(R.string.diag_title)) { drawerGo(Pushed.Diag) }
                }
            }
        },
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (top != null) {
                TopAppBar(
                    title = {
                        Text(
                            when (top) {
                                is Pushed.CategoryDetail -> categoryLabel(top.category)
                                is Pushed.ReliefDetail -> reliefName(top.code, taxSummary)
                                Pushed.Settings -> stringResource(R.string.set_title)
                                Pushed.Statement -> stringResource(R.string.stmt_title)
                                Pushed.Diag -> stringResource(R.string.diag_title)
                                Pushed.Brain -> stringResource(R.string.brain_title)
                                Pushed.Profile -> stringResource(R.string.prof_title)
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (top == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = {
                            BadgedBox(
                                badge = { if (needsReview > 0) Badge() }
                            ) {
                                Icon(Icons.Outlined.Home, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(R.string.nav_home)) },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_txn)) },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Outlined.BarChart, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_insights)) },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { tab = 3 },
                        icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_budget)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (top) {
            is Pushed.CategoryDetail -> CategoryDetailScreen(
                viewModel,
                category = top.category,
                onTxnClick = { openTxnDetail(it.id) },
                modifier = modifier,
            )
            is Pushed.ReliefDetail -> TaxReliefDetailScreen(
                viewModel,
                code = top.code,
                onTxnClick = { openTxnDetail(it) },
                modifier = modifier,
            )
            Pushed.Settings -> SettingsScreen(
                viewModel,
                notificationAccessGranted = notificationAccessGranted,
                onRequestNotificationAccess = onOpenNotificationAccess,
                batteryUnrestricted = batteryUnrestricted,
                onFixBattery = onFixBattery,
                onOpenStatement = { stack.add(Pushed.Statement) },
                onOpenDiag = { stack.add(Pushed.Diag) },
                onOpenBrain = { stack.add(Pushed.Brain) },
                onSnackbar = showSnack,
                modifier = modifier,
            )
            Pushed.Profile -> ProfileScreen(
                onSnackbar = showSnack,
                modifier = modifier,
            )
            Pushed.Brain -> BrainScreen(
                onSnackbar = showSnack,
                modifier = modifier,
            )
            Pushed.Diag -> DiagScreen(
                viewModel,
                onSnackbar = showSnack,
                modifier = modifier,
            )
            Pushed.Statement -> StatementScreen(
                viewModel,
                onSnackbar = showSnack,
                modifier = modifier,
            )
            null -> when (tab) {
                0 -> DashboardScreen(
                    viewModel,
                    onOpenDrawer = openDrawer,
                    notificationAccessGranted = notificationAccessGranted,
                    batteryUnrestricted = batteryUnrestricted,
                    onFixBattery = onFixBattery,
                    onRequestNotificationAccess = onOpenNotificationAccess,
                    onCategoryClick = { stack.add(Pushed.CategoryDetail(it)) },
                    onOpenBudgets = { tab = 3 },
                    onOpenSettings = { stack.add(Pushed.Settings) },
                    onOpenStatement = { stack.add(Pushed.Statement) },
                    onTxnClick = { openTxnDetail(it.id, expandRaw = true) },
                    modifier = modifier,
                )
                1 -> TxnListScreen(
                    viewModel,
                    onTxnClick = { openTxnDetail(it.id) },
                    onSnackbar = showSnack,
                    modifier = modifier,
                )
                2 -> AnalysisScreen(
                    viewModel,
                    onOpenRelief = { stack.add(Pushed.ReliefDetail(it)) },
                    modifier = modifier,
                )
                else -> {
                    val savedMsg = stringResource(R.string.bud_saved)
                    BudgetsScreen(
                        viewModel,
                        onSaved = { showSnack(savedMsg) },
                        modifier = modifier,
                    )
                }
            }
        }
    }

    detailTxnId?.let { id ->
        TxnDetailSheet(
            viewModel = viewModel,
            txnId = id,
            expandRawInitially = detailExpandRaw,
            onDismiss = {
                detailTxnId = null
                detailExpandRaw = false
            },
            onSnackbar = showSnack,
        )
    }
    } // ModalNavigationDrawer
}

@Composable
private fun DrawerItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/**
 * Re-evaluates [compute] on every resume. Permissions are granted in system
 * screens, so the only moment the app can learn the new answer is when it comes
 * back to the foreground; a value read once at startup stays wrong forever.
 */
@Composable
private fun rememberOnResume(compute: () -> Boolean): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var value by remember { mutableStateOf(compute()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) value = compute()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return value
}
