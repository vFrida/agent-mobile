package net.pdd.comagic.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.pdd.comagic.mobile.ChatLine
import net.pdd.comagic.mobile.Endpoint
import net.pdd.comagic.mobile.PairPayload
import net.pdd.comagic.mobile.SessionRef
import net.pdd.comagic.mobile.StreamState
import net.pdd.comagic.mobile.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    initial: Endpoint,
    error: String?,
    onCancel: (() -> Unit)?,
    onConnect: (Endpoint) -> Unit
) {
    var baseUrl by rememberSaveable(initial.baseUrl) { mutableStateOf(initial.baseUrl) }
    var username by rememberSaveable(initial.username) { mutableStateOf(initial.username) }
    var password by rememberSaveable(initial.password) { mutableStateOf(initial.password) }
    var directory by rememberSaveable(initial.directory) { mutableStateOf(initial.directory) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            val payload = PairPayload.parse(contents)
            if (payload == null) {
                scanError = "二维码内容无法识别"
            } else {
                baseUrl = payload.baseUrl
                username = payload.username
                directory = payload.directory.ifBlank { directory }
                scanError = null
                if (payload.password.isNotBlank()) {
                    password = payload.password
                    onConnect(Endpoint(payload.baseUrl, payload.username, payload.password, directory))
                }
            }
        }
    }

    fun startScan() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("对准终端里的配对二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("连接 Comagic") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "在开发机上运行：comagic web --hostname 0.0.0.0 --port 4907",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "地址填 http://<开发机局域网 IP>:4907，密码用终端里打印的那一串。",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()) {
                Text("扫码配对")
            }

            if (scanError != null) {
                Text(scanError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("服务地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = directory,
                onValueChange = { directory = it },
                label = { Text("工作目录（新建会话用）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    onConnect(
                        Endpoint(
                            baseUrl = baseUrl,
                            username = username.ifBlank { "comagic" },
                            password = password,
                            directory = directory
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("连接") }

            if (onCancel != null) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("返回") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onOpen: (SessionRef) -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话") },
                actions = {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                    TextButton(onClick = onSettings) { Text("设置") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(session.title.ifBlank { session.id }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(session.agent, session.model?.let { "${it.providerID}/${it.id}" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick = { onOpen(session) },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("打开") }
                    }
                }
            }
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("新建会话") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onBack: () -> Unit,
    onPermission: (String) -> Unit
) {
    var draft by rememberSaveable(state.sessionID) { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
    }

    state.pendingPermission?.let { ask ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("需要确认") },
            text = { Text(ask.title) },
            confirmButton = {
                TextButton(onClick = { onPermission("once") }) { Text("允许一次") }
            },
            dismissButton = {
                TextButton(onClick = { onPermission("reject") }) { Text("拒绝") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.sessionTitle) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    if (state.busy) TextButton(onClick = onInterrupt) { Text("中断") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.lines) { line -> MessageBubble(line) }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            if (state.streamState == StreamState.RECONNECTING || state.streamState == StreamState.CONNECTING) {
                Text(
                    "连接断开，正在重连…",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("给 agent 发消息") },
                    modifier = Modifier.weight(1f).heightIn(max = 140.dp)
                )
                Button(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.busy
                ) { Text("发送") }
            }
        }
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    val background = when (line.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "tool" -> MaterialTheme.colorScheme.secondaryContainer
        "error" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        when (line.role) {
            "tool" -> Text("工具调用 · ${line.tool ?: ""}", style = MaterialTheme.typography.bodySmall)
            "assistant" -> Text(
                line.text.ifBlank { "思考中…" },
                style = MaterialTheme.typography.bodyMedium
            )
            else -> Text(line.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
