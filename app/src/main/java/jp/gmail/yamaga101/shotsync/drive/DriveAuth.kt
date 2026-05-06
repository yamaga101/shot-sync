package jp.gmail.yamaga101.shotsync.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes

object DriveAuth {

    /** drive.file: 自分が作成 / open したファイルだけ。最小権限。 */
    private val SCOPES = listOf(DriveScopes.DRIVE_FILE)

    fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .apply { SCOPES.forEach { requestScopes(Scope(it)) } }
            .build()

    fun signInClient(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions())

    fun lastSignedInAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)?.takeIf { account ->
            // scope が drive.file 含むかチェック (revoke 対策)
            GoogleSignIn.hasPermissions(account, *SCOPES.map { Scope(it) }.toTypedArray())
        }

    fun driveClient(context: Context, account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(context, SCOPES).apply {
            selectedAccount = account.account
        }
        return Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential,
        )
            .setApplicationName("shot-sync/0.1.0")
            .build()
    }

    fun signInIntent(context: Context): Intent = signInClient(context).signInIntent
}
