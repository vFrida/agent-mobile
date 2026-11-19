package net.agents.mobile.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.agents.mobile.ChatLine
import net.agents.mobile.Credentials
import net.agents.mobile.DirectoryPickerState
import net.agents.mobile.R
import net.agents.mobile.Endpoint
import net.agents.mobile.ModelRef
import net.agents.mobile.RegistryDefaults
import net.agents.mobile.RegistryServer
import net.agents.mobile.SessionRef
import net.agents.mobile.ServerStatus
import net.agents.mobile.StreamState
import net.agents.mobile.UiState
import kotlin.math.roundToInt
import net.agents.mobile.VoiceInput
import net.agents.mobile.decodeImageSource

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

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    registryUrl: String,
    username: String,
    showHiddenDirs: Boolean,
    hiddenAgentCount: Int,
    onToggleHiddenDirs: (Boolean) -> Unit,
    onRestoreHiddenAgents: () -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { BackButton(onClose) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingRow("注册中心 IP", registryHost(registryUrl))
                    HorizontalDivider()
                    SettingRow("账号", username)
                }
            }
            Text("会话配置", style = MaterialTheme.typography.titleSmall)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示隐藏目录", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "新建会话选择工作目录时显示以 . 开头的目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = showHiddenDirs, onCheckedChange = onToggleHiddenDirs)
                }
            }
            if (hiddenAgentCount > 0) {
                OutlinedButton(
                    onClick = onRestoreHiddenAgents,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("恢复已删除的 agents（$hiddenAgentCount）")
                }
            }
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("退出登录", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    registryUrl: String,
    registryUrls: List<String>,
    loading: Boolean,
    error: String?,
    onSelectAddress: (String) -> Unit,
    onAddAddress: (String) -> Unit,
    onDeleteAddress: (String) -> Unit,
    onSubmit: (url: String, username: String, password: String) -> Unit
) {
    var username by rememberSaveable { mutableStateOf(Credentials.username) }
    var password by rememberSaveable { mutableStateOf(Credentials.password) }
    var expanded by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加注册中心 IP") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("192.168.100.136") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddAddress(draft)
                    draft = ""
                    adding = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text("取消") }
            }
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Text("Agents", style = MaterialTheme.typography.headlineMedium)
            Text(
                "登录注册中心，自动发现 agents",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = registryHost(registryUrl),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("注册中心 IP") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    registryUrls.forEach { url ->
                        DropdownMenuItem(
                            text = {
                                Text(registryHost(url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            onClick = {
                                onSelectAddress(url)
                                expanded = false
                            },
                            trailingIcon = {
                                if (registryUrls.size > 1) {
                                    IconButton(onClick = { onDeleteAddress(url) }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("添加 IP") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        onClick = {
                            draft = ""
                            adding = true
                            expanded = false
                        }
                    )
                }
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("账号") },
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
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Button(
                onClick = { onSubmit(registryUrl, username, password) },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("登录")
                }
            }
        }
    }
}

private fun registryHost(url: String): String {
    val withoutScheme = url.substringAfter("://", url)
    return withoutScheme.substringBefore('/').substringBefore(':').ifBlank { url }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    servers: List<Endpoint>,
    active: Endpoint,
    statuses: Map<String, ServerStatus>,
    onSelect: (Endpoint) -> Unit,
    onDelete: (Endpoint) -> Unit,
    registryLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<Endpoint?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("agents")
                        Text(
                            "${servers.size} 个 agents",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onLogout) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "退出登录",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !registryLoading) {
                        if (registryLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (servers.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有 agents", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "点击右上角刷新，从注册中心发现 agents",
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
                        SwipeableServerCard(
                            server = server,
                            isActive = server.id == active.id,
                            status = statuses[server.id],
                            onSelect = { onSelect(server) },
                            onDelete = { pendingDelete = server }
                        )
                    }
                }
            }

        }
    }
}


@Composable
private fun SwipeableServerCard(
    server: Endpoint,
    isActive: Boolean,
    status: ServerStatus?,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val actionWidth = with(density) { 152.dp.toPx() }
    val offset = remember(server.id) { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun close() {
        scope.launch { offset.animateTo(0f) }
    }

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
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
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
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
                .pointerInput(server.id) {
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
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                border = if (isActive) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (offset.value < -1f) close() else onSelect() }
            ) {
                val accent = SessionPalette[Math.floorMod(server.id.hashCode(), SessionPalette.size)]
                Row(modifier = Modifier.padding(14.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            server.label.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            server.agentId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ServerAvailability(status, server.agentType, server.agentVersion)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerAvailability(status: ServerStatus?, agentType: String, agentVersion: String) {
    val typeLabel = agentType.ifBlank { "comagic" }
    val version = status?.version?.takeIf { it.isNotBlank() } ?: agentVersion
    val color = when {
        status == null || status.checking -> MaterialTheme.colorScheme.onSurfaceVariant
        status.online -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.error
    }
    val label = when {
        status == null || status.checking -> "$typeLabel · 检测中…"
        status.online -> if (version.isBlank()) {
            "$typeLabel · 服务可用"
        } else {
            "$typeLabel · 服务可用 · $version"
        }
        else -> "$typeLabel · 服务不可用"
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
                    TextButton(onClick = onServers) { Text("agents") }
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
    showHiddenDirs: Boolean,
    onNavigate: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit
) {
    var manualPath by rememberSaveable(picker.current) { mutableStateOf(picker.current) }
    var manualVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(picker.current) { manualPath = picker.current }

    val directories = remember(picker.entries, query, showHiddenDirs) {
        val keyword = query.trim()
        picker.entries
            .filter { it.type == "directory" }
            .filter {
                showHiddenDirs ||
                    !it.path.trimEnd('/').substringAfterLast('/').startsWith(".")
            }
            .filter { keyword.isEmpty() || it.path.contains(keyword, ignoreCase = true) }
            .sortedBy { it.path.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择工作目录") },
                navigationIcon = { BackButton(onCancel) },
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
    onLoadOlder: () -> Unit,
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

    var lastAutoScrollLine by remember(state.sessionID) { mutableStateOf<ChatLine?>(null) }
    LaunchedEffect(state.lines) {
        val last = state.lines.lastOrNull() ?: return@LaunchedEffect
        if (lastAutoScrollLine == null || last != lastAutoScrollLine) {
            lastAutoScrollLine = last
            listState.animateScrollToItem(state.lines.lastIndex)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .collect { (index, scrolling) ->
                if (index == 0 && scrolling && state.historyHasMore && !state.loadingOlder) onLoadOlder()
            }
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
                navigationIcon = { BackButton(onBack) },
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
            SessionInfoBar(
                directory = state.sessionDirectory,
                agentType = state.endpoint.agentType
            )

            val scope = rememberCoroutineScope()
            val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.loadingOlder) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    items(state.lines) { line -> MessageBubble(line) }
                }
                if (!atBottom) {
                    SmallFloatingActionButton(
                        onClick = {
                            if (state.lines.isNotEmpty()) {
                                scope.launch { listState.animateScrollToItem(state.lines.lastIndex) }
                            }
                        },
                        containerColor = AssistantBubbleColor,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 10.dp)
                    ) {
                        Text("↓", style = MaterialTheme.typography.titleMedium, color = AccentBlue)
                    }
                }
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
                if (voiceMode && draft.isNotBlank()) {
                    Text(
                        draft,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 6.dp)
                            .background(AssistantBubbleColor, RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    )
                }
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

private const val COLLAPSED_TOOL_LINES = 4

private val BashKeywords = setOf(
    "sudo", "cd", "ls", "git", "npm", "pnpm", "yarn", "rm", "mv", "cp", "echo", "cat", "grep", "rg",
    "find", "python", "python3", "bash", "sh", "curl", "docker", "kubectl", "make", "go", "cargo",
    "sed", "awk", "head", "tail", "xargs", "chmod", "mkdir", "touch", "export", "source"
)

private fun highlightCommand(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '#' -> {
                withStyle(SpanStyle(color = SystemTextColor, fontStyle = FontStyle.Italic)) {
                    append(text.substring(i))
                }
                return@buildAnnotatedString
            }

            c == '"' || c == '\'' -> {
                val close = text.indexOf(c, i + 1)
                val end = if (close < 0) text.length else close + 1
                withStyle(SpanStyle(color = SendButtonColor)) { append(text.substring(i, end)) }
                i = end
            }

            c == ' ' || c == '\t' -> {
                append(c)
                i++
            }

            c == '|' || c == '&' || c == ';' -> {
                withStyle(SpanStyle(color = AccentPurple)) { append(c) }
                i++
            }

            else -> {
                var end = i
                while (end < text.length && text[end] !in " \t|&;#\"'") end++
                val word = text.substring(i, end)
                val color = when {
                    word.startsWith("-") -> AccentOrange
                    word.lowercase() in BashKeywords -> AccentBlue
                    else -> null
                }
                if (color != null) {
                    withStyle(SpanStyle(color = color)) { append(word) }
                } else {
                    append(word)
                }
                i = end
            }
        }
    }
}

private fun highlightOutput(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    lines.forEachIndexed { index, line ->
        val lower = line.lowercase()
        val color = when {
            lower.contains("error") || lower.contains("failed") || lower.contains("traceback") -> AccentRed
            lower.contains("warning") -> AccentOrange
            else -> null
        }
        if (color != null) {
            withStyle(SpanStyle(color = color)) { append(line) }
        } else {
            append(line)
        }
        if (index != lines.lastIndex) append("\n")
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
    val output = line.toolOutput?.takeIf { it.isNotBlank() }?.trim()
    val expandable = output != null && (output.count { it == '\n' } + 1) > COLLAPSED_TOOL_LINES
    var expanded by remember(line.toolID, output) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 12.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(tint.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .clickable(enabled = expandable) { expanded = !expanded },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "▸ ${line.tool ?: "tool"}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = tint
            )
            line.toolTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    highlightCommand(it),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            output?.let {
                Text(
                    highlightOutput(it.take(4000)),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = SystemTextColor,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_TOOL_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (expandable) {
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
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
private fun SessionInfoBar(directory: String, agentType: String) {
    val typeLabel = agentType.ifBlank { "comagic" }
    Surface(color = Color(0xFFF5F9FF), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sessionBadge(typeLabel, AccentBlue)
            Text(
                directory.ifBlank { "未设置工作目录" },
                style = MaterialTheme.typography.bodySmall,
                color = AccentBlue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
