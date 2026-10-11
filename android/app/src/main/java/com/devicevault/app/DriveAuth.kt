package com.devicevault.app

import android.app.Activity
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import org.json.JSONObject

/**
 * Google Drive access tokens through Google Play services' Authorization API. The web app's own
 * sign-in (Google Identity Services popup) cannot run inside a WebView, so the page asks the
 * shell for a token instead and keeps using the Drive REST API exactly as before.
 *
 * One-time setup for the owner: an OAuth client of type "Android" in the same Google Cloud
 * project, with this package name and the signing certificate's SHA-1 (shown in Settings).
 */
class DriveAuth(
    private val activity: MainActivity,
    private val resolutionLauncher: ActivityResultLauncher<IntentSenderRequest>
) {
    companion object {
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }

    private var pendingRequest = 0

    fun request(requestId: Int, interactive: Boolean) {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)
        if (availability != ConnectionResult.SUCCESS) {
            fail(requestId, "Google Play services is not available on this device, so Drive sync cannot be used here.")
            return
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    if (!interactive) {
                        fail(requestId, "Sign in to Google Drive from Settings first.")
                    } else {
                        pendingRequest = requestId
                        val sender = result.pendingIntent?.intentSender
                        if (sender == null) fail(requestId, "Google did not return a sign-in screen.")
                        else resolutionLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                } else {
                    deliver(requestId, result.accessToken)
                }
            }
            .addOnFailureListener { e -> fail(requestId, describe(e)) }
    }

    fun onResolutionResult(result: ActivityResult) {
        val id = pendingRequest
        pendingRequest = 0
        if (id == 0) return
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            fail(id, "Google sign-in was cancelled.")
            return
        }
        try {
            val auth = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            deliver(id, auth.accessToken)
        } catch (e: ApiException) {
            fail(id, describe(e))
        }
    }

    private fun describe(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            10 -> "Google rejected this app (DEVELOPER_ERROR). In Google Cloud Console add an OAuth client of type Android with package name ${activity.packageName} and the SHA-1 shown in Settings, then try again."
            16, 12501 -> "Google sign-in was cancelled."
            7 -> "Network error while contacting Google."
            else -> e.message ?: e.toString()
        }
    }

    private fun deliver(id: Int, token: String?) {
        if (token.isNullOrEmpty()) {
            fail(id, "Google returned no access token.")
            return
        }
        Log.i(MainActivity.TAG, "drive token ok (request $id)")
        activity.callJs("window.__onNativeDriveToken && __onNativeDriveToken($id, ${JSONObject.quote(token)}, null)")
    }

    private fun fail(id: Int, message: String) {
        Log.w(MainActivity.TAG, "drive token failed (request $id): $message")
        activity.callJs("window.__onNativeDriveToken && __onNativeDriveToken($id, null, ${JSONObject.quote(message)})")
    }
}
