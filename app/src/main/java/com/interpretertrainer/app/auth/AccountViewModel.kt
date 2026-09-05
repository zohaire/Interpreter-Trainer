package com.interpretertrainer.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facebook.login.LoginManager
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

data class AccountState(val checking: Boolean = true, val user: FirebaseUser? = null,
    val busy: Boolean = false, val message: String? = null)

class AccountViewModel : ViewModel() {
    private val mutable = MutableStateFlow(AccountState())
    val state = mutable.asStateFlow()
    private val auth = if (AccountSession.configured) AccountSession.auth() else null
    private val listener = FirebaseAuth.AuthStateListener { service ->
        mutable.value = mutable.value.copy(checking = false, user = service.currentUser)
    }
    init {
        if (auth == null) mutable.value = AccountState(checking = false,
            message = "Account service is not configured in this build.")
        else auth.addAuthStateListener(listener)
    }
    private fun operation(block: suspend () -> String?) {
        if (mutable.value.busy || auth == null) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { val message = withTimeout(30000) { block() }
                mutable.value = mutable.value.copy(user = auth.currentUser, message = message)
            } catch (_: TimeoutCancellationException) { showMessage("Connection timed out. Please retry.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { showMessage(when (e) {
                is FirebaseNetworkException -> "No internet connection. Please retry when connected."
                is FirebaseTooManyRequestsException -> "Too many attempts. Please try again later."
                is FirebaseAuthException -> when (e.errorCode) {
                    "ERROR_EMAIL_ALREADY_IN_USE" -> "This email is already registered. Sign in or reset your password."
                    "ERROR_WEAK_PASSWORD" -> "Choose a stronger password with at least 8 characters."
                    "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> "Sign in using your existing account method."
                    else -> "Sign-in failed. Check your details and try again."
                }
                else -> "The account request failed. Please retry."
            })
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun showMessage(message: String) { mutable.value = mutable.value.copy(message = message) }
    fun signIn(email: String, password: String) = operation {
        auth!!.signInWithEmailAndPassword(email.trim(), password).await(); null
    }
    fun signUp(name: String, email: String, password: String) = operation {
        val user = auth!!.createUserWithEmailAndPassword(email.trim(), password).await().user!!
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
        user.sendEmailVerification().await(); "Verification email sent. Check your inbox."
    }
    fun reset(email: String) = operation {
        auth!!.sendPasswordResetEmail(email.trim()).await()
        "If an account exists, password reset instructions will arrive by email."
    }
    fun verify() = operation { auth!!.currentUser?.sendEmailVerification()?.await(); "Verification email sent." }
    fun reload() = operation {
        auth!!.currentUser?.reload()?.await(); auth.currentUser?.getIdToken(true)?.await()
        if (auth.currentUser?.isEmailVerified == true) null else "Your email is not verified yet."
    }
    fun facebook(token: String) = operation {
        auth!!.signInWithCredential(FacebookAuthProvider.getCredential(token)).await(); null
    }
    fun logout() {
        if (mutable.value.busy) return
        auth?.signOut(); LoginManager.getInstance().logOut()
        mutable.value = AccountState(checking = false)
    }
    override fun onCleared() { auth?.removeAuthStateListener(listener) }
}
