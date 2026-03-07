package com.example.agenticassistant.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.calendar.CalendarScopes

class GoogleAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleAuthManager"
        val GMAIL_SCOPE = Scope(GmailScopes.GMAIL_READONLY)
        val CALENDAR_SCOPE = Scope(CalendarScopes.CALENDAR_EVENTS)
    }

    private val googleSignInClient: GoogleSignInClient by lazy {
        Log.d(TAG, "Building GoogleSignInOptions...")
        Log.d(TAG, "  Scopes: gmail.readonly, calendar.events")
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(GMAIL_SCOPE, CALENDAR_SCOPE)
            .build()
        Log.d(TAG, "GoogleSignInClient created successfully")
        GoogleSignIn.getClient(context, gso)
    }

    /**
     * Returns the sign-in intent to be launched via ActivityResultLauncher.
     */
    fun getSignInIntent(): Intent {
        Log.d(TAG, "getSignInIntent() called — launching Google Sign-In flow")
        return googleSignInClient.signInIntent
    }

    /**
     * Handles the result from the sign-in intent.
     * Returns the GoogleSignInAccount if successful, null otherwise.
     * Logs detailed error information on failure.
     */
    fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
        Log.d(TAG, "handleSignInResult() called")

        if (data == null) {
            Log.e(TAG, "Sign-in result Intent is NULL — user may have cancelled")
            return null
        }

        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            Log.d(TAG, "✅ Sign-in SUCCESSFUL")
            Log.d(TAG, "  Email: ${account.email}")
            Log.d(TAG, "  Display Name: ${account.displayName}")
            Log.d(TAG, "  Account Name: ${account.account?.name}")
            Log.d(TAG, "  Granted Scopes: ${account.grantedScopes}")
            Log.d(TAG, "  ID Token present: ${account.idToken != null}")
            Log.d(TAG, "  Server Auth Code present: ${account.serverAuthCode != null}")
            account
        } catch (e: ApiException) {
            val statusCode = e.statusCode
            val statusMessage = getStatusCodeMessage(statusCode)
            Log.e(TAG, "❌ Sign-in FAILED with ApiException")
            Log.e(TAG, "  Status Code: $statusCode")
            Log.e(TAG, "  Status Message: $statusMessage")
            Log.e(TAG, "  Exception: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sign-in FAILED with unexpected exception: ${e.message}", e)
            null
        }
    }

    /**
     * Returns the error status code from the last sign-in attempt.
     * Useful for showing specific error messages to the user.
     */
    fun getSignInError(data: Intent?): String {
        if (data == null) return "Sign-in was cancelled"
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            task.getResult(ApiException::class.java)
            "No error"
        } catch (e: ApiException) {
            "${getStatusCodeMessage(e.statusCode)} (code: ${e.statusCode})"
        } catch (e: Exception) {
            "Unexpected error: ${e.message}"
        }
    }

    /**
     * Returns the currently signed-in account, or null.
     */
    fun getLastSignedInAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        Log.d(TAG, "getLastSignedInAccount(): ${if (account != null) account.email else "null"}")
        return account
    }

    /**
     * Returns true if the user is already signed in with the required scopes.
     */
    fun isSignedIn(): Boolean {
        val account = getLastSignedInAccount() ?: return false
        val hasPermissions = GoogleSignIn.hasPermissions(account, GMAIL_SCOPE, CALENDAR_SCOPE)
        Log.d(TAG, "isSignedIn(): hasPermissions=$hasPermissions")
        return hasPermissions
    }

    /**
     * Builds a GoogleAccountCredential usable by Google API client libraries.
     * Token refresh is handled automatically by the credential object.
     */
    fun getCredential(): GoogleAccountCredential? {
        val account = getLastSignedInAccount() ?: run {
            Log.e(TAG, "getCredential(): No signed-in account found")
            return null
        }
        if (account.account == null) {
            Log.e(TAG, "getCredential(): GoogleSignInAccount.account is null — cannot build credential")
            return null
        }
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(GmailScopes.GMAIL_READONLY, CalendarScopes.CALENDAR_EVENTS)
        )
        credential.selectedAccount = account.account
        Log.d(TAG, "getCredential(): Built credential for ${account.account?.name}")
        return credential
    }

    /**
     * Signs the user out and clears cached sign-in state.
     */
    fun signOut(onComplete: () -> Unit = {}) {
        Log.d(TAG, "signOut() called")
        googleSignInClient.signOut().addOnCompleteListener {
            Log.d(TAG, "signOut() completed")
            onComplete()
        }
    }

    /**
     * Revokes access and signs the user out completely.
     * Use this when sign-in is in a broken state and you need a clean restart.
     */
    fun revokeAccess(onComplete: () -> Unit = {}) {
        Log.d(TAG, "revokeAccess() called")
        googleSignInClient.revokeAccess().addOnCompleteListener {
            Log.d(TAG, "revokeAccess() completed")
            onComplete()
        }
    }

    /**
     * Maps Google Sign-In status codes to human-readable messages.
     */
    private fun getStatusCodeMessage(statusCode: Int): String {
        return when (statusCode) {
            CommonStatusCodes.SUCCESS -> "Success"
            CommonStatusCodes.SIGN_IN_REQUIRED -> "Sign-in required"
            CommonStatusCodes.NETWORK_ERROR -> "Network error — check internet connection"
            CommonStatusCodes.INTERNAL_ERROR -> "Internal error in Google Play Services"
            CommonStatusCodes.DEVELOPER_ERROR ->
                "DEVELOPER_ERROR — OAuth client ID mismatch. Check: " +
                "1) SHA-1 fingerprint matches Google Cloud Console, " +
                "2) Package name 'com.example.agenticassistant' matches OAuth client, " +
                "3) OAuth consent screen is configured"
            CommonStatusCodes.CANCELED -> "User cancelled sign-in"
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Sign-in cancelled by user"
            GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> "Sign-in already in progress"
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> "Sign-in failed — possible causes: " +
                "1) Google Play Services outdated, " +
                "2) OAuth scopes not enabled in Cloud Console, " +
                "3) App not registered properly"
            else -> "Unknown error (status code: $statusCode)"
        }
    }
}
