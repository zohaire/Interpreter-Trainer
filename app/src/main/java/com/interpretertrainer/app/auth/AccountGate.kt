package com.interpretertrainer.app.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AccountGate(onFacebook: () -> Unit, content: @Composable (String, () -> Unit) -> Unit) {
    val model: AccountViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val user = state.user
    if (state.checking) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val passwordAccount = user?.providerData?.any { it.providerId == "password" } == true
    if (user != null && (!passwordAccount || user.isEmailVerified) && !state.busy) {
        key(user.uid) { content(user.uid, model::logout) }
        return
    }
    var create by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    // Password stays in memory only, never saved to instance state or preferences.
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val validEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val enabled = !state.busy && AccountSession.configured
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Text("IT", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Text("Welcome to Interpreter Trainer", style = MaterialTheme.typography.headlineSmall)
            if (user != null && passwordAccount && !user.isEmailVerified) {
                Text("Verify your email to continue. Check the message sent to ${user.email.orEmpty()}.")
                Button(onClick = model::reload, enabled = enabled) { Text("I've verified my email") }
                TextButton(onClick = model::verify, enabled = enabled) { Text("Resend verification email") }
                TextButton(onClick = model::logout, enabled = enabled) { Text("Sign out") }
            } else {
                if (create) OutlinedTextField(name, {name=it.take(80)}, label={Text("Name")}, singleLine=true, enabled=enabled, modifier=Modifier.fillMaxWidth())
                OutlinedTextField(email, {email=it.take(254)}, label={Text("Email")}, singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email), enabled=enabled,
                    isError=email.isNotEmpty()&&!validEmail, modifier=Modifier.fillMaxWidth())
                OutlinedTextField(password, {password=it.take(128)}, label={Text("Password")}, singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password), enabled=enabled,
                    visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon={TextButton(onClick={visible=!visible}){Text(if(visible) "Hide" else "Show")}}, modifier=Modifier.fillMaxWidth())
                if(create) Text("Use at least 8 characters.", style=MaterialTheme.typography.bodySmall)
                Button(onClick={if(create) model.signUp(name,email,password) else model.signIn(email,password); password=""},
                    enabled=enabled&&validEmail&&password.isNotEmpty()&&(!create||(password.length>=8&&name.isNotBlank())),
                    modifier=Modifier.fillMaxWidth()) { Text(if(create) "Create Account" else "Sign In") }
                TextButton(onClick={model.reset(email)}, enabled=enabled&&validEmail) { Text("Forgot password?") }
                HorizontalDivider(); Text("OR")
                OutlinedButton(onClick=onFacebook, enabled=enabled, modifier=Modifier.fillMaxWidth()) { Text("Continue with Facebook") }
                TextButton(onClick={create=!create;password=""}, enabled=enabled) {
                    Text(if(create) "Already have an account? Sign In" else "Don't have an account? Create Account")
                }
            }
            if(state.busy) CircularProgressIndicator()
            state.message?.let { Text(it, color=MaterialTheme.colorScheme.primary) }
        }
    }
}
