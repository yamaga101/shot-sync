package jp.gmail.yamaga101.shotsync

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.gmail.yamaga101.shotsync.ui.MainViewModel
import jp.gmail.yamaga101.shotsync.ui.theme.ShotSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShotSyncTheme {
                Scaffold(
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = {
                                // standards.md §4: version は header に常時可視。最新かどうか即判別。
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.CloudUpload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("shot-sync", fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .padding(horizontal = 16.dp)
                            .padding(top = 8.dp, bottom = 16.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MainScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { vm.refreshPermissions(context) }
    val mediaPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { vm.refreshPermissions(context) }
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result -> vm.onSignInResult(context, result.data) }
    val batterySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { vm.refreshPermissions(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshPermissions(context)
                vm.refreshSignInState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    StatusBanner(state)

    if (!state.permissions.allGreen) {
        PermissionChecklistCard(
            status = state.permissions,
            onRequestNotifications = {
                Permissions.notificationPerm()?.let { notifPermLauncher.launch(it) }
            },
            onRequestMedia = { mediaPermLauncher.launch(Permissions.mediaPerm()) },
            onOpenBattery = {
                runCatching {
                    batterySettingsLauncher.launch(
                        Permissions.requestIgnoreBatteryOptimizationsIntent(context)
                    )
                }.onFailure {
                    ContextCompat.startActivity(
                        context,
                        Permissions.appDetailsSettingsIntent(context),
                        null,
                    )
                }
            },
        )
    }

    SectionCard(
        icon = Icons.Filled.AccountCircle,
        title = "Google アカウント",
    ) {
        Text(
            state.signedInEmail ?: "未サインイン",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.signedInEmail != null) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { signInLauncher.launch(vm.signInIntent(context)) }) {
                Text(if (state.signedInEmail == null) "サインイン" else "切り替え")
            }
            if (state.signedInEmail != null) {
                OutlinedButton(onClick = { vm.signOut(context) }) { Text("サインアウト") }
            }
        }
    }

    var folderInput by remember(state.driveFolderId) { mutableStateOf(state.driveFolderId ?: "") }
    var cameraFolderInput by remember(state.cameraDriveFolderId) {
        mutableStateOf(state.cameraDriveFolderId ?: "")
    }
    SectionCard(
        icon = Icons.Filled.Folder,
        title = "送り先 Drive folder ID",
    ) {
        Text(
            "Drive web で対象フォルダを開いた URL 末尾の ID。\n例: /folders/1DBOVzP2x8q...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "📸 Screenshots 用",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = folderInput,
            onValueChange = { folderInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("1DBOVzP...") },
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            vm.saveFolderId(folderInput.trim()) { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }) { Text("Screenshots 保存") }
        Spacer(Modifier.height(12.dp))
        Text(
            "📷 Camera 用 (空欄なら default folder)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = cameraFolderInput,
            onValueChange = { cameraFolderInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("(空欄で default: 12y4EpdE...)") },
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            vm.saveCameraFolderId(cameraFolderInput.trim()) { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }) { Text("Camera 保存") }
    }

    SectionCard(
        icon = if (state.autoSyncEnabled) Icons.Filled.Sync else Icons.Filled.SyncDisabled,
        title = "自動アップロード",
        accent = state.autoSyncEnabled && state.permissions.allGreen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.autoSyncEnabled) "ON — JobScheduler trigger + catch-up"
                else "OFF",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.autoSyncEnabled,
                onCheckedChange = { vm.toggleAutoSync(context, it) },
                enabled = state.permissions.allGreen,
            )
        }
        if (!state.permissions.allGreen) {
            Text(
                "上のセットアップを全部緑にしてから ON にできます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "MediaStore content trigger で OS が wake → catch-up scan → Drive 送信。アプリを開いた時にも必ず追いつき。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        // 撮影写真 toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("📷 撮影写真も同期", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "DCIM/Camera/ も watch (位置情報含む可能性あり)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.syncCameraPhotos,
                onCheckedChange = { vm.toggleSyncCameraPhotos(context, it) },
                enabled = state.autoSyncEnabled,
            )
        }
        // Wi-Fi のみ toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("📶 Wi-Fi 接続時のみ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "従量回線でのアップロードを抑制 (推奨 ON)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.wifiOnly,
                onCheckedChange = { vm.toggleWifiOnly(context, it) },
                enabled = state.autoSyncEnabled,
            )
        }
    }

    SectionCard(
        icon = Icons.Filled.PhotoCamera,
        title = "手動 upload テスト",
    ) {
        Text(
            "Screenshots フォルダの最新 1 枚を即 Drive に送る。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                vm.uploadLatestScreenshot(context) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
            enabled = state.signedInEmail != null && state.driveFolderId != null,
        ) { Text("最新 1 枚を upload") }
    }

    SectionCard(
        icon = Icons.Filled.History,
        title = "最近の upload",
    ) {
        if (state.recent.isEmpty()) {
            EmptyRecentState()
        } else {
            state.recent.take(10).forEach { entry ->
                RecentRow(entry)
            }
        }
    }

    SamsungSettingsCard(
        onOpenAppDetails = {
            ContextCompat.startActivity(
                context,
                Permissions.appDetailsSettingsIntent(context),
                null,
            )
        },
    )

    DiagnosticsCard()

    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SamsungSettingsCard(onOpenAppDetails: () -> Unit) {
    SectionCard(
        icon = Icons.Filled.Warning,
        title = "Samsung 設定 (推奨)",
    ) {
        Text(
            "One UI の Deep sleep / 自動最適化に入ると、JobScheduler trigger も WorkManager も遅延 / 取消されます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text("手動で 1 度だけ設定:", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "1. アプリ情報 → バッテリー → 「制限なし」",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "2. 設定 → デバイスケア → バッテリー → バックグラウンド使用制限 → 「Deep sleeping apps」から shot-sync を除外",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "3. 設定 → デバイスケア → 自動最適化 → shot-sync を除外",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenAppDetails) {
            Text("アプリ情報を開く")
        }
    }
}

@Composable
private fun DiagnosticsCard() {
    val entries by DiagnosticsLog.entries.collectAsState()
    SectionCard(
        icon = Icons.Filled.BugReport,
        title = "診断ログ",
    ) {
        if (entries.isEmpty()) {
            Text(
                "イベント未発生",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.take(20).forEach { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        e.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (e.severity) {
                            Severity.INFO -> "•"
                            Severity.WARN -> "!"
                            Severity.ERROR -> "✗"
                        },
                        color = when (e.severity) {
                            Severity.INFO -> MaterialTheme.colorScheme.primary
                            Severity.WARN -> MaterialTheme.colorScheme.tertiary
                            Severity.ERROR -> MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${e.tag}: ${e.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(state: jp.gmail.yamaga101.shotsync.ui.UiState) {
    val signed = state.signedInEmail != null
    val syncing = state.autoSyncEnabled && state.permissions.allGreen
    val nowMs = System.currentTimeMillis()
    val triggerStaleMs = if (state.lastTriggerAt > 0) nowMs - state.lastTriggerAt else Long.MAX_VALUE
    val warnStale = syncing && triggerStaleMs > java.util.concurrent.TimeUnit.HOURS.toMillis(6)
    val brush = Brush.linearGradient(
        if (warnStale) listOf(
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
        ) else listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
        )
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush)
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (syncing) Icons.Filled.CloudUpload else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (syncing) "Drive と同期中" else "待機中",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (signed) state.signedInEmail!! else "未サインイン",
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (syncing) {
                    Text(
                        text = "Last trigger: ${formatAgo(state.lastTriggerAt, nowMs)}" +
                            " · Last scan: ${formatAgo(state.lastScanAt, nowMs)}" +
                            " · Last upload: ${formatAgo(state.lastUploadAt, nowMs)}",
                        color = Color.White.copy(alpha = 0.95f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (warnStale) {
                        Text(
                            "⚠ 6 時間以上 trigger が来ていません。Samsung 設定で deep sleep から除外推奨。",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        label = if (signed) "Auth ✓" else "Auth ✗",
                        ok = signed,
                    )
                    StatusPill(
                        label = if (state.autoSyncEnabled) "Auto ON" else "Auto OFF",
                        ok = state.autoSyncEnabled,
                    )
                    StatusPill(
                        label = "${state.recent.count { it.success }} ✓",
                        ok = state.recent.any { it.success },
                    )
                }
            }
        }
    }
}

private fun formatAgo(thenMs: Long, nowMs: Long): String {
    if (thenMs <= 0L) return "—"
    val diffMs = (nowMs - thenMs).coerceAtLeast(0L)
    val sec = diffMs / 1000L
    return when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m"
        sec < 86_400 -> "${sec / 3600}h"
        else -> "${sec / 86_400}d"
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (ok) Color.White.copy(alpha = 0.25f)
                else Color.Black.copy(alpha = 0.18f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (accent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun EmptyRecentState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "まだ upload なし",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "スクショ撮るか「最新 1 枚を upload」で起動確認",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun RecentRow(entry: jp.gmail.yamaga101.shotsync.ui.UploadEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.success) Icons.Filled.CheckCircle
            else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (entry.success) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (!entry.success && entry.error != null) {
                Text(
                    entry.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun PermissionChecklistCard(
    status: PermissionStatus,
    onRequestNotifications: () -> Unit,
    onRequestMedia: () -> Unit,
    onOpenBattery: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "セットアップが必要",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                "全部緑になるまでこのカードは消えません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            ChecklistRow(
                label = "通知の表示",
                granted = status.notifications,
                actionLabel = "許可する",
                onAction = onRequestNotifications,
            )
            ChecklistRow(
                label = "写真と動画 (Screenshots)",
                granted = status.media,
                actionLabel = "許可する",
                onAction = onRequestMedia,
            )
            ChecklistRow(
                label = "バッテリー最適化を除外",
                granted = status.ignoreBatteryOpt,
                actionLabel = "設定を開く",
                onAction = onOpenBattery,
            )
        }
    }
}

@Composable
private fun ChecklistRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (!granted) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
