package jp.gmail.yamaga101.shotsync

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
                        TopAppBar(title = { Text("shot-sync") })
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .padding(16.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
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
    ) { result ->
        vm.onSignInResult(context, result.data)
    }
    val batterySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { vm.refreshPermissions(context) }

    // onResume 毎に権限状態を再評価。Settings で変更してアプリに戻った時に反映するため。
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

    // --- Permission checklist (allGreen になるまで案内し続ける) ---
    if (!state.permissions.allGreen) {
        PermissionChecklistCard(
            status = state.permissions,
            onRequestNotifications = {
                Permissions.notificationPerm()?.let { notifPermLauncher.launch(it) }
            },
            onRequestMedia = { mediaPermLauncher.launch(Permissions.mediaPerm()) },
            onOpenBattery = {
                runCatching {
                    batterySettingsLauncher.launch(Permissions.requestIgnoreBatteryOptimizationsIntent(context))
                }.onFailure {
                    // 機種によっては Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS が
                    // 受け付けない (Samsung 等)。アプリ詳細にフォールバック。
                    ContextCompat.startActivity(context, Permissions.appDetailsSettingsIntent(context), null)
                }
            },
        )
    }

    // --- Account ---
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google アカウント", style = MaterialTheme.typography.titleMedium)
            Text(state.signedInEmail ?: "未サインイン", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { signInLauncher.launch(vm.signInIntent(context)) }) {
                    Text(if (state.signedInEmail == null) "サインイン" else "切り替え")
                }
                if (state.signedInEmail != null) {
                    OutlinedButton(onClick = { vm.signOut(context) }) { Text("サインアウト") }
                }
            }
        }
    }

    // --- Drive folder ID ---
    var folderInput by remember(state.driveFolderId) { mutableStateOf(state.driveFolderId ?: "") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("送り先 Drive folder ID", style = MaterialTheme.typography.titleMedium)
            Text(
                "Drive web で対象フォルダを開いた時の URL 末尾の ID をコピペ。\n例: /folders/1DBOVzP2x8qS8ThsihMutfH_8IgzSMOWa",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = folderInput,
                onValueChange = { folderInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("1DBOVzP...") }
            )
            Button(onClick = {
                vm.saveFolderId(folderInput.trim()) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }) { Text("保存") }
        }
    }

    // --- Auto sync toggle ---
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自動アップロード (Foreground service)", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.autoSyncEnabled,
                    onCheckedChange = { vm.toggleAutoSync(context, it) },
                    enabled = state.permissions.allGreen,
                )
            }
            if (!state.permissions.allGreen) {
                Text(
                    "上の権限案内をすべて緑にしないと有効化できません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "ON で /Pictures/Screenshots/ を watch → 新規ファイルを Drive に instant 送信。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // --- Manual scan/upload ---
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("手動 upload テスト", style = MaterialTheme.typography.titleMedium)
            Text(
                "Screenshots フォルダの最新 1 枚を即 Drive に送る。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    vm.uploadLatestScreenshot(context) { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = state.signedInEmail != null && state.driveFolderId != null
            ) { Text("最新 1 枚を upload") }
        }
    }

    // --- Recent uploads ---
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近の upload", style = MaterialTheme.typography.titleMedium)
            if (state.recent.isEmpty()) {
                Text("(まだ無し)", style = MaterialTheme.typography.bodyMedium)
            } else {
                state.recent.take(10).forEach { entry ->
                    Text("• ${entry.name}  ${if (entry.success) "✔" else "✗"}", style = MaterialTheme.typography.bodySmall)
                    if (!entry.success && entry.error != null) {
                        Text("    ${entry.error}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "セットアップが必要",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "全部緑になるまでこのカードは消えません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            ChecklistRow(
                label = "通知の表示 (Foreground service 常駐通知)",
                granted = status.notifications,
                actionLabel = "許可する",
                onAction = onRequestNotifications,
            )
            ChecklistRow(
                label = "写真と動画 (Screenshots を読み取る)",
                granted = status.media,
                actionLabel = "許可する",
                onAction = onRequestMedia,
            )
            ChecklistRow(
                label = "バッテリー最適化を除外 (service が殺されない)",
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
        Text(
            text = if (granted) "✓" else "✗",
            color = if (granted) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
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
