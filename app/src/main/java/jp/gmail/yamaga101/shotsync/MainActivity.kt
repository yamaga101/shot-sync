package jp.gmail.yamaga101.shotsync

import android.Manifest
import android.os.Build
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    // Activity Result API の launcher は Composable 内で remember 経由で取得しないと
    // Activity が STARTED 以降に登録できず crash する (registerForActivityResult を
    // 直接呼ぶと "LifecycleOwners must call register before they are STARTED" 例外)
    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* result handled implicitly by toggling sync */ }
    val mediaPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* same */ }
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vm.onSignInResult(context, result.data)
    }

    LaunchedEffect(Unit) {
        // Ask runtime permissions on launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            mediaPermLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
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
    // remember(key) を使って state.driveFolderId が DataStore から後から到着しても
    // 入力欄に反映されるように。user 編集中は state は変わらないので reset されない。
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
                    onCheckedChange = { vm.toggleAutoSync(context, it) }
                )
            }
            Text(
                "ON で /Pictures/Screenshots/ を watch → 新規ファイルを Drive に instant 送信。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    // --- Manual scan/upload (P1 verification) ---
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("手動 upload テスト", style = MaterialTheme.typography.titleMedium)
            Text(
                "/Pictures/Screenshots/ の最新 1 枚を即 Drive に送る (P1 動作確認用)。",
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
