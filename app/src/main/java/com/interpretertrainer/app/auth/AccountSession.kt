package com.interpretertrainer.app.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.interpretertrainer.app.BuildConfig

/** Firebase owns token storage and refresh. Tokens never enter JavaScript or app preferences. */
object AccountSession {
    val configured: Boolean get() = listOf(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_PROJECT_ID).all { it.isNotBlank() }
    fun initialize(context: Context) {
        if (configured && FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY).setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).build())
        }
    }
    fun auth(): FirebaseAuth = FirebaseAuth.getInstance()
    fun uid(): String? = if (configured) auth().currentUser?.uid else null
}
