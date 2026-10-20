package org.telegram.messenger.amin.google;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

/**
 * Transparent trampoline Activity for the interactive Google
 * authorization flow (v1.3.5). It exists because the Play
 * Services Authorization API delivers its consent UI as a
 * PendingIntent that must be launched from a real Activity
 * (startIntentSenderForResult) and completed from
 * onActivityResult — something neither the settings page (a
 * Telegram fragment) nor a JS plugin can do directly.
 *
 * Declared (patch 0016) with exported=false and a transparent
 * theme; it draws nothing of its own — Google's account picker /
 * consent screens appear over whatever was on screen. Serves
 * both entry points: the hub's Google Account page and a plugin's
 * amin.google.signIn.
 *
 * Flow: authorize() → token directly (already consented) OR
 * hasResolution → launch the PendingIntent → result intent →
 * getAuthorizationResultFromIntent → token. The result is saved
 * through GoogleAuthManager and delivered to the static pending
 * callback there; this Activity then finishes. Any abandonment
 * (destroy without a result) completes the pending callback as a
 * cancellation so nobody waits forever.
 */
public class GoogleAuthActivity extends Activity {

    private static final int RC_RESOLUTION = 4713;

    private AuthorizationClient client;
    private boolean delivered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            client = Identity.getAuthorizationClient(this);
            if (savedInstanceState == null) {
                // A recreated instance (system kill during the
                // consent screen) only waits for onActivityResult;
                // it must not start a second authorize.
                client.authorize(GoogleAuthManager.buildRequest(
                                GoogleAuthManager.connectedEmail(this)))
                        .addOnCompleteListener(this::onAuthorizeDone);
            }
        } catch (Throwable t) {
            fail("Google sign-in is unavailable: " + GoogleAuthManager.brief(t));
        }
    }

    private void onAuthorizeDone(Task<AuthorizationResult> task) {
        if (delivered || isFinishing()) {
            return;
        }
        try {
            if (!task.isSuccessful()) {
                fail("Google sign-in failed: " + GoogleAuthManager.brief(task.getException()));
                return;
            }
            AuthorizationResult result = task.getResult();
            if (result == null) {
                fail("Google returned no sign-in result.");
                return;
            }
            if (result.hasResolution()) {
                PendingIntent pendingIntent = result.getPendingIntent();
                if (pendingIntent == null) {
                    fail("Google returned no consent screen.");
                    return;
                }
                try {
                    startIntentSenderForResult(pendingIntent.getIntentSender(),
                            RC_RESOLUTION, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    fail("Could not open the Google consent screen.");
                }
                return;
            }
            succeed(result);
        } catch (Throwable t) {
            fail("Google sign-in failed: " + GoogleAuthManager.brief(t));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_RESOLUTION || delivered) {
            return;
        }
        try {
            if (resultCode != RESULT_OK || data == null) {
                fail("Sign-in was cancelled.");
                return;
            }
            succeed(client.getAuthorizationResultFromIntent(data));
        } catch (ApiException e) {
            fail("Google sign-in failed (status " + e.getStatusCode() + ").");
        } catch (Throwable t) {
            fail("Google sign-in failed: " + GoogleAuthManager.brief(t));
        }
    }

    private void succeed(AuthorizationResult result) {
        String token = result == null ? null : result.getAccessToken();
        if (token == null) {
            fail("Google did not return an access token.");
            return;
        }
        delivered = true;
        GoogleAuthManager.saveAuthorization(this, token,
                GoogleAuthManager.emailOf(result));
        GoogleAuthManager.deliverInteractiveResult(true, null);
        finish();
    }

    private void fail(String message) {
        if (delivered) {
            return;
        }
        delivered = true;
        GoogleAuthManager.deliverInteractiveResult(false, message);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!delivered) {
            // Backed out / swiped away / killed mid-flow: complete
            // the pending callback as a cancellation. (After a
            // normal deliver this is a no-op — delivered is true,
            // and the manager's registry is already empty.)
            delivered = true;
            GoogleAuthManager.deliverInteractiveResult(false, "Sign-in was cancelled.");
        }
    }
}
