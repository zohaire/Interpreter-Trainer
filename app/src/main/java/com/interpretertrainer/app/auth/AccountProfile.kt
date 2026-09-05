package com.interpretertrainer.app.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun AccountProfile(onDismiss: () -> Unit, onLogout: () -> Unit) {
    val context=LocalContext.current
    val user=AccountSession.auth().currentUser ?: return
    val preferences=remember(user.uid) { context.getSharedPreferences("preferences_${user.uid}",android.content.Context.MODE_PRIVATE) }
    var languages by remember(user.uid) { mutableStateOf(preferences.getStringSet("languages",setOf("English","العربية الفصحى","Français"))!!.toSet()) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Your account")},text={
        Column {
            Text(user.displayName ?: "Interpreter Trainer")
            user.email?.let { Text(it) }
            Text("Preferred languages")
            listOf("English","العربية الفصحى","Français").forEach { language ->
                Row {
                    Checkbox(checked=language in languages,onCheckedChange={ checked ->
                        val next=if(checked)languages+language else languages-language
                        if(next.isNotEmpty()) {languages=next;preferences.edit().putStringSet("languages",next).apply()}
                    })
                    Text(language)
                }
            }
            Text("Practice and conversation history are stored separately for your account.")
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("Done")}},
        dismissButton={TextButton(onClick={onDismiss();onLogout()}){Text("Sign out")}})
}
