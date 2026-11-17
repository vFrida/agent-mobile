package net.pdd.comagic.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.pdd.comagic.mobile.ui.ChatScreen
import net.pdd.comagic.mobile.ui.ComagicTheme
import net.pdd.comagic.mobile.ui.PairingScreen
import net.pdd.comagic.mobile.ui.SessionListScreen

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
    var editing by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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

    when {
        state.sessionID != null -> ChatScreen(
            state = state,
            onSend = viewModel::send,
            onSendImage = viewModel::sendImage,
            onSelectAgent = viewModel::selectAgent,
            onSelectModel = viewModel::selectModel,
            onInterrupt = viewModel::interrupt,
            onBack = viewModel::closeSession,
            onPermission = viewModel::replyPermission
        )

        !state.endpoint.ready || editing -> PairingScreen(
            initial = state.endpoint,
            error = state.error,
            onCancel = if (state.endpoint.ready) ({ editing = false }) else null,
            onConnect = { endpoint ->
                viewModel.connect(endpoint)
                editing = false
            }
        )

        else -> SessionListScreen(
            state = state,
            onRefresh = viewModel::refreshSessions,
            onNew = viewModel::newSession,
            onOpen = viewModel::openSession,
            onDelete = viewModel::deleteSession,
            onSettings = { editing = true }
        )
    }
}
