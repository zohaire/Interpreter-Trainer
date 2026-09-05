package com.interpretertrainer.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.interpretertrainer.app.auth.AccountProfile
import com.interpretertrainer.app.auth.AccountGate
import com.interpretertrainer.app.auth.AccountViewModel
import com.interpretertrainer.app.ui.InterpreterTrainerApp
import com.interpretertrainer.app.ui.theme.InterpreterTrainerTheme
import com.interpretertrainer.app.ui.theme.ThemePreferences
import com.interpretertrainer.app.viewmodel.SessionViewModel

class MainActivity : ComponentActivity() {
    private val account: AccountViewModel by viewModels()
    private val facebookCallbacks = CallbackManager.Factory.create()
    private var facebookPending = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LoginManager.getInstance().registerCallback(facebookCallbacks, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) { facebookPending=false; account.facebook(result.accessToken.token) }
            override fun onCancel() { facebookPending=false; account.showMessage("Facebook sign-in cancelled.") }
            override fun onError(error: FacebookException) { facebookPending=false; account.showMessage("Facebook sign-in failed. Please retry.") }
        })
        val app = application as InterpreterTrainerApplication
        setContent {
            var themeMode by remember { mutableStateOf(ThemePreferences.get(this)) }
            InterpreterTrainerTheme(themeMode = themeMode) {
                AccountGate(onFacebook = {
                    if (getString(R.string.facebook_app_id) == "0") account.showMessage("Facebook login is not configured in this build.")
                    else if (!facebookPending) {
                        facebookPending=true
                        try { LoginManager.getInstance().logInWithReadPermissions(this, listOf("email", "public_profile")) }
                        catch (_: Exception) { facebookPending=false; account.showMessage("Facebook sign-in could not start.") }
                    }
                }) { uid, logout ->
                    val sessions: SessionViewModel = viewModel(key = "sessions_$uid", factory = SessionViewModel.Factory(app.repositoryFor(uid)))
                    var showAccount by remember(uid) { mutableStateOf(false) }
                    if (showAccount) AccountProfile(onDismiss={showAccount=false}, onLogout=logout)
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding(), horizontalArrangement=Arrangement.End) {
                            TextButton(onClick = {showAccount=true}) { Text("Account") }
                        }
                        Box(Modifier.weight(1f)) {
                            InterpreterTrainerApp(sessionViewModel=sessions, themeMode=themeMode,
                                onThemeModeChange={ mode -> ThemePreferences.set(this,mode); themeMode=mode })
                        }
                    }
                }
            }
        }
    }
    @Deprecated("Facebook SDK callback integration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        facebookCallbacks.onActivityResult(requestCode,resultCode,data)
    }
    override fun onDestroy() {
        LoginManager.getInstance().unregisterCallback(facebookCallbacks)
        super.onDestroy()
    }
}
