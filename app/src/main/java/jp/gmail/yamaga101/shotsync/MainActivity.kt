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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.CloudUpload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "shot-sync",
                                        fontWeight = FontWeight.SemiBold,
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
        OutlinedTextField(
            value = folderInput,
            onValueChange = { folderInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("1DBOVzP...") },
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            vm.saveFolderId(folderInput.trim()) { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }) { Text("保存") }
    }

    SectionCard(
        icon = if (state.autoSyncEnabled) Icons.Filled.Sync else Icons.Filled.SyncDisabled,
        title = "自動アップロード",
        accent = state.autoSyncEnabled && state.permissions.allGreen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.autoSyncEnabled) "ON — Foreground service 監視中"
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
            Spacer(Modifier.height(4.dp))
            Text(
                "上のセットアップを全部緑にしてから ON にできます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                "新規スクショを検知 → Drive にinstant 送信。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    Spacer(Modifier.height(16.dp))
}

@Composable
private fun StatusBanner(state: jp.gmail.yamaga101.shotsync.ui.UiState) {
    val signed = state.signedInEmail != null
    val syncing = state.autoSyncEnabled && state.permissions.allGreen
    val brush = Brush.linearGradient(
        listOf(
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
