package net.agents.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.agents.mobile.ui.ChatScreen
import net.agents.mobile.ui.DirectoryPickerScreen
import net.agents.mobile.ui.ComagicTheme
import net.agents.mobile.ui.ServersScreen
import net.agents.mobile.ui.LoginScreen
import net.agents.mobile.ui.SessionListScreen
import net.agents.mobile.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComagicTheme {
                val viewModel: AppViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                Root(state, viewModel)
            }
        }
    }
}

@Composable
private fun Root(state: UiState, viewModel: AppViewModel) {
    var serversOpen by rememberSaveable { mutableStateOf(true) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.ensureStream()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModel.ensureStream()
            }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.endpoint.ready) {
        if (state.endpoint.ready) viewModel.refreshSessions()
    }

    LaunchedEffect(serversOpen) {
        if (serversOpen) viewModel.refreshServerStatuses()
    }

    BackHandler(enabled = state.sessionID != null || state.directoryPicker != null || settingsOpen) {
        when {
            state.sessionID != null -> viewModel.closeSession()
            state.directoryPicker != null -> viewModel.closeDirectoryPicker()
            settingsOpen -> settingsOpen = false
        }
    }

    BackHandler(
        enabled = state.sessionID == null &&
            state.directoryPicker == null &&
            !serversOpen &&
            !settingsOpen
    ) {
        serversOpen = true
    }

    when {
        state.account == null -> LoginScreen(
            registryUrl = state.registryUrl,
            registryUrls = state.registryUrls,
            loading = state.loginLoading,
            error = state.loginError,
            onSelectAddress = viewModel::selectRegistryAddress,
            onAddAddress = viewModel::addRegistryAddress,
            onDeleteAddress = viewModel::removeRegistryAddress,
            onSubmit = viewModel::login
        )

        state.sessionID != null -> ChatScreen(
            state = state,
            onSend = viewModel::send,
            onSendImage = viewModel::sendImage,
            onSelectAgent = viewModel::selectAgent,
            onSelectModel = viewModel::selectModel,
            onInterrupt = viewModel::interrupt,
            onBack = viewModel::closeSession,
            onLoadOlder = viewModel::loadOlder,
            onPermission = viewModel::replyPermission
        )

        state.directoryPicker != null -> DirectoryPickerScreen(
            picker = state.directoryPicker!!,
            showHiddenDirs = state.showHiddenDirs,
            onNavigate = viewModel::navigateDirectory,
            onSelect = viewModel::confirmDirectory,
            onRefresh = viewModel::openDirectoryPicker,
            onCancel = viewModel::closeDirectoryPicker
        )

        settingsOpen -> SettingsScreen(
            registryUrl = state.registryUrl,
            username = state.account?.username ?: "",
            showHiddenDirs = state.showHiddenDirs,
            hiddenAgentCount = state.hiddenAgentIds.size,
            onToggleHiddenDirs = viewModel::setShowHiddenDirs,
            onRestoreHiddenAgents = viewModel::restoreHiddenAgents,
            onLogout = {
                settingsOpen = false
                viewModel.logout()
            },
            onClose = { settingsOpen = false }
        )

        serversOpen -> ServersScreen(
            servers = state.servers,
            active = state.endpoint,
            statuses = state.serverStatus,
            onSelect = { server ->
                viewModel.selectServer(server)
                serversOpen = false
            },
            onDelete = viewModel::deleteServer,
            registryLoading = state.registryLoading,
            onRefresh = viewModel::refreshAgents,
            onOpenSettings = { settingsOpen = true },
            onLogout = viewModel::logout
        )

        else -> SessionListScreen(
            state = state,
            onRefresh = viewModel::refreshSessions,
            onNew = viewModel::openDirectoryPicker,
            onOpen = viewModel::openSession,
            onDelete = viewModel::deleteSession,
            onServers = { serversOpen = true }
        )
    }
}
