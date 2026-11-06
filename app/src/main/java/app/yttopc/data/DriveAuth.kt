package app.yttopc.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NeedsSignIn : Exception("Sign in to Google Drive.")

object DriveAuth {
    private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE)))
        .build()

    suspend fun revoke(context: Context) {
        val client = Identity.getAuthorizationClient(context)
        val result = client.authorize(request()).await()
        val account = result.toGoogleSignInAccount()?.account ?: return
        val revoke = RevokeAccessRequest.builder()
            .setAccount(account)
            .setScopes(listOf(Scope(DRIVE_FILE)))
            .build()
        client.revokeAccess(revoke).await()
    }

    /** Fresh access token, or null when the user still has to approve the Google screen. */
    suspend fun authorize(context: Context): String? {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (result.hasResolution()) return null
        return result.accessToken
    }

    fun needsCloudSetup(error: Exception): Boolean = statusCode(error) == 10

    fun explain(context: Context, error: Exception): String {
        val status = statusCode(error)
        if (status == 10) {
            return "Google Cloud does not know this app yet. Enable the Drive API, then add an Android OAuth client for app.yttopc with SHA-1 ${certificateSha1(context)}."
        }
        if (status == 7) return "Could not reach Google. Check the network and try again."
        return error.message ?: "Google sign-in failed."
    }

    private fun statusCode(error: Exception): Int? =
        (error as? ApiException)?.statusCode ?: (error.cause as? ApiException)?.statusCode

    fun certificateSha1(context: Context): String {
        val signatures = signatures(context)
        val digest = MessageDigest.getInstance("SHA-1").digest(signatures.first().toByteArray())
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun signatures(context: Context): Array<out android.content.pm.Signature> {
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val info = packageInfo.signingInfo
            val current = info?.apkContentsSigners
            if (current != null && current.isNotEmpty()) return current
        }
        @Suppress("DEPRECATION")
        return packageInfo.signatures ?: error("This install has no signing certificate.")
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
