package net.pdd.comagic.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pdd.comagic.mobile.ChatLine
import net.pdd.comagic.mobile.DirectoryPickerState
import net.pdd.comagic.mobile.R
import net.pdd.comagic.mobile.Endpoint
import net.pdd.comagic.mobile.ModelRef
import net.pdd.comagic.mobile.PairPayload
import net.pdd.comagic.mobile.SessionRef
import net.pdd.comagic.mobile.ServerStatus
import net.pdd.comagic.mobile.StreamState
import net.pdd.comagic.mobile.UiState
import kotlin.math.roundToInt
import net.pdd.comagic.mobile.VoiceInput
import net.pdd.comagic.mobile.decodeImageSource

private val ChatBackground = Color(0xFFEDEDED)
private val UserBubbleColor = Color(0xFF95EC69)
private val AssistantBubbleColor = Color(0xFFFFFFFF)
private val InputBarColor = Color(0xFFF7F7F7)
private val SystemTextColor = Color(0xFF9B9B9B)
private val SendButtonColor = Color(0xFF07C160)
private val AccentBlue = Color(0xFF4C9EEA)
private val AccentRed = Color(0xFFFA5151)
private val AccentOrange = Color(0xFFFF9F0A)
private val SoftBlue = Color(0xFFEDF5FF)
private val SoftGreen = Color(0xFFEAF9EF)
private val AccentPurple = Color(0xFF8B5CF6)
private val TopBarTint = Color(0xFFF1FAF4)
private val AssistantBubbleTint = Color(0xFFFBFDFF)
private val SessionPalette = listOf(
    Color(0xFF4C9EEA),
    Color(0xFF07C160),
    Color(0xFFFF9F0A),
    Color(0xFF8B5CF6),
    Color(0xFFEC4899),
    Color(0xFF14B8A6)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    initial: Endpoint,
    error: String?,
    onCancel: (() -> Unit)?,
    onConnect: (Endpoint) -> Unit
) {
    var name by rememberSaveable(initial.name) { mutableStateOf(initial.name) }
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
                    onConnect(
                        Endpoint(
                            baseUrl = payload.baseUrl,
                            username = payload.username,
                            password = payload.password,
                            directory = directory
                        )
                    )
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
                .imePadding()
                .verticalScroll(rememberScrollState()),
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
                value = name,
                onValueChange = { name = it },
                label = { Text("名称（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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
                        initial.copy(
                            name = name.trim(),
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
fun ServersScreen(
    servers: List<Endpoint>,
    active: Endpoint,
    statuses: Map<String, ServerStatus>,
    onSelect: (Endpoint) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Endpoint) -> Unit,
    onDelete: (Endpoint) -> Unit,
    onClose: () -> Unit,
    batteryOptimized: Boolean,
    onRequestIgnoreBattery: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<Endpoint?>(null) }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除服务器") },
            text = { Text("确定删除「${server.label}」吗？会话仍保留在对应机器上。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(server)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("返回") }
                },
                title = {
                    Column {
                        Text("服务器")
                        Text(
                            "${servers.size} 台已配对",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onAdd) { Text("添加") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (servers.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有服务器", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "添加一台运行 comagic web 的机器",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(servers, key = { it.id }) { server ->
                        val isActive = server.id == active.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            border = if (isActive) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(server) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        server.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isActive) {
                                        Text(
                                            "当前",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                                Text(
                                    server.baseUrl.ifBlank { "未填写地址" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    server.directory.ifBlank { "未设置工作目录" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ServerAvailability(statuses[server.id])
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onEdit(server) }) { Text("编辑") }
                                    TextButton(onClick = { pendingDelete = server }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }

            BatteryOptimizationCard(
                optimized = batteryOptimized,
                onRequest = onRequestIgnoreBattery
            )
        }
    }
}

@Composable
private fun ServerAvailability(status: ServerStatus?) {
    val color = when {
        status == null || status.checking -> MaterialTheme.colorScheme.onSurfaceVariant
        status.online -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.error
    }
    val label = when {
        status == null || status.checking -> "检测中…"
        status.online -> if (status.version.isBlank()) "服务可用" else "服务可用 · ${status.version}"
        else -> "服务不可用"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BatteryOptimizationCard(optimized: Boolean, onRequest: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("后台保活", style = MaterialTheme.typography.titleSmall)
            Text(
                if (optimized) {
                    "系统可能休眠 App，导致聊天页断线重连。建议关闭电池优化；MIUI/HyperOS 还需允许自启动，并把省电策略设为「无限制」。"
                } else {
                    "已关闭电池优化。MIUI/HyperOS 仍需允许自启动，并把省电策略设为「无限制」。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (optimized) {
                OutlinedButton(onClick = onRequest) { Text("关闭电池优化") }
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
    onDelete: (SessionRef) -> Unit,
    onServers: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SessionRef?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.sessions, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) state.sessions else state.sessions.filter { session ->
            listOfNotNull(
                session.title,
                session.agent,
                session.model?.id,
                session.model?.providerID,
                session.location?.directory
            ).any { it.contains(keyword, ignoreCase = true) }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话") },
            text = { Text("确定删除「${session.title.ifBlank { session.id }}」吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("会话")
                        Text(
                            listOfNotNull(
                                state.endpoint.label.takeIf { it.isNotBlank() },
                                state.sessions.size.takeIf { it > 0 }?.let { "$it 个会话" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                    TextButton(onClick = onServers) { Text("服务器") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索标题、目录或模型") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (state.loading && state.sessions.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                if (query.isBlank()) "还没有会话" else "没有匹配的会话",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (query.isBlank()) "创建一个会话开始和 agent 协作" else "换个关键词试试",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { session ->
                        SwipeableSessionCard(
                            session = session,
                            modelLabel = sessionModelLabel(state, session),
                            onOpen = { onOpen(session) },
                            onDelete = { pendingDelete = session }
                        )
                    }
                }
            }

            Button(
                onClick = onNew,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(48.dp)
            ) { Text("新建会话") }
        }
    }
}

@Composable
private fun SwipeableSessionCard(
    session: SessionRef,
    modelLabel: String?,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val actionWidth = with(density) { 152.dp.toPx() }
    val offset = remember(session.id) { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun close() {
        scope.launch { offset.animateTo(0f) }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { close() },
                contentAlignment = Alignment.Center
            ) {
                Text("取消", style = MaterialTheme.typography.labelMedium)
            }
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                    .clickable {
                        close()
                        onDelete()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(session.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = if (offset.value < -actionWidth / 2) -actionWidth else 0f
                            scope.launch { offset.animateTo(target) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(-actionWidth, 0f))
                            }
                        }
                    )
                }
        ) {
            SessionCard(
                session = session,
                modelLabel = modelLabel,
                onOpen = {
                    if (offset.value < -1f) close() else onOpen()
                },
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionRef,
    modelLabel: String?,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val title = session.title.ifBlank { "未命名会话" }
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tint = SessionPalette[(session.id.hashCode() and 0x7fffffff) % SessionPalette.size]
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tint),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    relativeTime(session.time?.updated ?: session.time?.created ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    session.location?.directory?.takeIf { it.isNotBlank() } ?: "未设置工作目录",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sessionBadge(session.agent.ifBlank { "build" }, AccentBlue)
                modelLabel?.takeIf { it.isNotBlank() }?.let { sessionBadge(it, AccentOrange) }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(
                        onClick = { expanded = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("···", style = MaterialTheme.typography.titleSmall)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text("删除会话") },
                            onClick = {
                                expanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sessionBadge(text: String, tint: Color = Color(0xFF6F6F6F)) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private fun sessionModelLabel(state: UiState, session: SessionRef): String? {
    val ref = session.model ?: return null
    return state.models.firstOrNull {
        it.id == ref.id && (it.providerID == ref.providerID || it.providerID.isBlank())
    }?.name?.ifBlank { ref.id } ?: ref.id
}

private fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 172_800_000 -> "昨天"
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPickerScreen(
    picker: DirectoryPickerState,
    onNavigate: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit
) {
    var manualPath by rememberSaveable(picker.current) { mutableStateOf(picker.current) }
    var manualVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(picker.current) { manualPath = picker.current }

    val directories = remember(picker.entries, query) {
        val keyword = query.trim()
        picker.entries
            .filter { it.type == "directory" }
            .filter { keyword.isEmpty() || it.path.contains(keyword, ignoreCase = true) }
            .sortedBy { it.path.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择工作目录") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("取消") } },
                actions = { TextButton(onClick = onRefresh) { Text("刷新") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "当前目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${directories.size} 个目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        picker.current.ifBlank { "-" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(
                            onClick = { parentDirectory(picker.current)?.let(onNavigate) },
                            enabled = parentDirectory(picker.current) != null && !picker.loading,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("上一级") }
                        TextButton(
                            onClick = { onNavigate(picker.home) },
                            enabled = picker.home.isNotBlank() &&
                                picker.home != picker.current && !picker.loading,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("主目录") }
                        TextButton(
                            onClick = { manualVisible = !manualVisible },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text(if (manualVisible) "收起输入" else "手动输入") }
                    }
                }
            }

            if (manualVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        label = { Text("目录路径") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { if (manualPath.isNotBlank()) onNavigate(manualPath.trim()) },
                        enabled = manualPath.isNotBlank() && !picker.loading,
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) { Text("前往") }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("筛选子目录") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (picker.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            picker.error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (directories.isEmpty() && !picker.loading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            if (query.isBlank()) "没有子目录" else "没有匹配的目录",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            if (query.isBlank()) "可以直接选择当前目录或手动输入路径" else "换个关键词试试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(directories, key = { it.path }) { entry ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(entry.path) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.path.substringAfterLast('/')
                                            .ifBlank { entry.path },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        entry.path,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "将在此目录创建会话",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            picker.current.ifBlank { "-" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { onSelect(picker.current) },
                        enabled = picker.current.isNotBlank() && !picker.loading,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) { Text("选择此目录") }
                }
            }
        }
    }
}

private fun parentDirectory(path: String): String? {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return null
    val parent = trimmed.substringBeforeLast('/', "")
    return parent.ifBlank { "/" }.takeIf { it != path }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onSendImage: (Uri, String) -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectModel: (ModelRef) -> Unit,
    onInterrupt: () -> Unit,
    onBack: () -> Unit,
    onPermission: (String) -> Unit
) {
    var draft by rememberSaveable(state.sessionID) { mutableStateOf("") }
    var voiceMode by rememberSaveable(state.sessionID) { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var showActions by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) voiceError = "需要录音权限才能使用语音输入"
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pendingImage = uri }

    val voice = remember {
        VoiceInput(
            context = context,
            onPartial = { draft = it },
            onFinal = {
                draft = it
                recording = false
                voiceMode = false
            },
            onError = {
                recording = false
                voiceError = it
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { voice.destroy() }
    }

    fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voiceError = null
        recording = true
        voice.start()
    }

    fun stopRecording() {
        if (recording) voice.stop()
    }

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
        modifier = Modifier.imePadding(),
        containerColor = ChatBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TopBarTint),
                title = {
                    Text(
                        state.sessionTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    if (state.busy) TextButton(onClick = onInterrupt) { Text("中断", color = AccentRed) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFFF3F6FA), Color(0xFFEBEFF4))))
        ) {
            SessionInfoBar(directory = state.sessionDirectory)

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.lines) { line -> MessageBubble(line) }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
                )
            }

            voiceError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
                )
            }

            when (state.streamState) {
                StreamState.RECONNECTING -> SystemNotice("连接断开，正在重连…", AccentOrange)
                StreamState.CONNECTING -> SystemNotice("正在连接…", AccentBlue)
                else -> Unit
            }

            pendingImage?.let { uri ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .background(AssistantBubbleColor, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocalImage(
                        source = uri.toString(),
                        modifier = Modifier.size(56.dp),
                        corner = 8
                    )
                    Text("发送图片", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pendingImage = null }) { Text("取消") }
                    Button(
                        onClick = {
                            onSendImage(uri, draft)
                            draft = ""
                            pendingImage = null
                        },
                        enabled = !state.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = SendButtonColor)
                    ) { Text("发送") }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InputBarColor)
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable {
                                voiceMode = !voiceMode
                                if (voiceMode) {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(if (voiceMode) R.drawable.ic_keyboard else R.drawable.ic_mic),
                            contentDescription = if (voiceMode) "键盘输入" else "语音输入",
                            modifier = Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }

                    if (voiceMode) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (recording) MaterialTheme.colorScheme.surfaceVariant else AssistantBubbleColor)
                                .border(
                                    1.dp,
                                    if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(8.dp)
                                )
                                .pointerInput(voiceMode) {
                                    detectTapGestures(
                                        onPress = {
                                            startRecording()
                                            tryAwaitRelease()
                                            stopRecording()
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (recording) "松开结束" else "按住 说话",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (recording) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = AssistantBubbleColor
                        ) {
                            TextField(
                                value = draft,
                                onValueChange = { draft = it },
                                placeholder = { Text("给 agent 发消息") },
                                maxLines = 5,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { showActions = !showActions },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (showActions) "×" else "+",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!voiceMode && draft.isNotBlank()) {
                        Button(
                            onClick = {
                                onSend(draft)
                                draft = ""
                                showActions = false
                            },
                            enabled = !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = SendButtonColor),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.height(40.dp)
                        ) { Text("发送") }
                    }
                }

                if (showActions) {
                    ChatActionPanel(
                        busy = state.busy,
                        onPhoto = {
                            showActions = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onVoice = {
                            showActions = false
                            voiceMode = true
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onInterrupt = {
                            showActions = false
                            onInterrupt()
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SelectorChip(
                        prefix = "模式",
                        prefixColor = AccentBlue,
                        label = state.sessionAgent.ifBlank { "build" },
                        options = state.agents.map { it.id to it.id },
                        onSelect = onSelectAgent
                    )
                    SelectorChip(
                        prefix = "模型",
                        prefixColor = AccentOrange,
                        label = modelLabel(state),
                        options = state.models.map { it.name.ifBlank { it.id } to it.ref() },
                        onSelect = onSelectModel
                    )
                }

            }
        }
    }
}

@Composable
private fun SystemNotice(text: String, color: Color = SystemTextColor) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ChatActionPanel(
    busy: Boolean,
    onPhoto: () -> Unit,
    onVoice: () -> Unit,
    onInterrupt: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        ChatAction("图", "相片", AccentBlue, onPhoto)
        ChatAction("音", "语音", SendButtonColor, onVoice)
        ChatAction("断", "中断", AccentRed, onInterrupt, enabled = busy)
    }
}

@Composable
private fun ChatAction(
    glyph: String,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) tint else Color(0xFFEAEAEA)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                glyph,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.White else SystemTextColor
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else SystemTextColor
        )
    }
}

private fun modelLabel(state: UiState): String {
    val current = state.sessionModel ?: return "模型"
    val known = state.models.firstOrNull {
        it.id == current.id && (it.providerID == current.providerID || it.providerID.isBlank())
    }
    return known?.name?.ifBlank { current.id } ?: current.id
}

@Composable
private fun <T> SelectorChip(
    label: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
    prefix: String? = null,
    prefixColor: Color = Color(0xFF6F6F6F)
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            if (!prefix.isNullOrBlank()) {
                Text(
                    "$prefix  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = prefixColor,
                    maxLines = 1
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(" ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name.ifBlank { "默认" }) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}

@Composable
private fun LocalImage(source: String, modifier: Modifier = Modifier, corner: Int = 10) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) {
            decodeImageSource(context, source)?.asImageBitmap()
        }
    }
    val shape = RoundedCornerShape(corner.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = "图片",
            modifier = modifier.clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("图片", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    val isUser = line.role == "user"

    when (line.role) {
        "tool" -> ToolBubble(line)
        "error" -> SystemNotice(line.text)
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isUser) {
                AgentAvatar()
                Spacer(Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .background(
                        if (isUser) UserBubbleColor else AssistantBubbleTint,
                        RoundedCornerShape(
                            topStart = 10.dp,
                            topEnd = 10.dp,
                            bottomStart = if (isUser) 10.dp else 2.dp,
                            bottomEnd = if (isUser) 2.dp else 10.dp
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                when {
                    line.image != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LocalImage(source = line.image!!, modifier = Modifier.size(180.dp), corner = 8)
                        if (line.text.isNotBlank()) {
                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    line.role == "assistant" -> Text(
                        line.text.ifBlank { "思考中…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    else -> Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (isUser) {
                Spacer(Modifier.width(8.dp))
                UserAvatar()
            }
        }
    }
}

private fun toolTint(name: String?): Color {
    val value = name.orEmpty().lowercase()
    return when {
        value.contains("write") || value.contains("edit") || value.contains("patch") -> AccentOrange
        value.contains("read") || value.contains("grep") || value.contains("glob") -> SendButtonColor
        value.contains("web") || value.contains("fetch") -> AccentPurple
        value.contains("todo") || value.contains("task") -> AccentRed
        else -> AccentBlue
    }
}

@Composable
private fun ToolBubble(line: ChatLine) {
    val tint = toolTint(line.tool)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(tint.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "▸ ${line.tool ?: "tool"}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = tint
            )
            line.toolTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            line.toolOutput?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it.trim().take(1500),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = SystemTextColor,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AgentAvatar() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "agent",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun UserAvatar() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(AccentBlue),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "我",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

@Composable
private fun SessionInfoBar(directory: String) {
    Surface(color = Color(0xFFF5F9FF), modifier = Modifier.fillMaxWidth()) {
        Text(
            directory.ifBlank { "未设置工作目录" },
            style = MaterialTheme.typography.bodySmall,
            color = AccentBlue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
