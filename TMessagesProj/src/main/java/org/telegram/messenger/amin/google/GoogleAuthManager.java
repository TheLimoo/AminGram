package org.telegram.messenger.amin.google;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.Scope;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Native Google account authorization for AminGram (v1.3.5): lets
 * JS plugins call Google APIs (Gemini) with the USER's own Google
 * account — an OAuth access token from Play Services, never an API
 * key, never anything leaving the device except to Google itself.
 *
 * Built on the Authorization API of play-services-auth 21.6.0
 * ({@code Identity.getAuthorizationClient} — verified against the
 * real AAR; the upstream-pinned 20.4.0 does not contain it, hence
 * patch 0016's version bump). Requested scopes:
 * {@code https://www.googleapis.com/auth/cloud-platform}
 * (Google Cloud Platform — broad, since v1.3.6: Google's console
 * refuses to register the narrower generative-language scope for
 * this app and the device flow dies with invalid_scope, while
 * the Gemini API accepts cloud-platform tokens) + {@code email}
 * (so the UI can show WHICH account is connected). Online access only — no server client id, no
 * refresh token exists in this API; tokens live about an hour.
 *
 * Token cache: a token fetched interactively (or silently) is
 * cached with a conservative 50-minute expiry and served while
 * more than 60 s remain; past that, {@link #getValidToken}
 * re-authorizes silently (no UI) and only fails when Google
 * demands an interactive consent — the caller can then run
 * {@link #startInteractiveAuth} (settings page) or the plugin's
 * amin.google.signIn.
 *
 * Storage: the whole cache blob {token, expiry, email} is
 * encrypted with an AndroidKeyStore AES-256/GCM key and kept in
 * the "amingram_google" prefs; nothing is stored in plaintext.
 * (EncryptedSharedPreferences is deprecated; this is the small
 * hand-rolled equivalent for one blob.)
 *
 * Honest bounds: Google Play Services is required; because
 * AminGram is not a Google-verified app, Google may ask the user
 * to re-consent periodically (roughly weekly in testing mode) —
 * that is Google's policy, not a bug. If the process dies while
 * the consent screen is up, the pending callback is lost; the
 * grant itself survives and the next silent fetch succeeds.
 * Every entry point is Throwable-isolated: failures surface as
 * null / false + a readable error string, never a crash.
 */
public final class GoogleAuthManager {

    public static final String SCOPE_CLOUD_PLATFORM =
            "https://www.googleapis.com/auth/cloud-platform";
    public static final String SCOPE_EMAIL = "email";

    private static final String PREFS = "amingram_google";
    private static final String KEY_BLOB = "auth_blob_v1";
    private static final String KEY_ALIAS = "amingram_google_auth";
    /** Access tokens from this API live ~1 h; cache for 50 min. */
    private static final long TOKEN_TTL_MS = 50L * 60L * 1000L;
    /** Serve the cache only while more than this remains. */
    private static final long MIN_REMAINING_MS = 60L * 1000L;

    public interface TokenCallback {
        void onToken(String tokenOrNull, String errorOrNull);
    }

    public interface AuthCallback {
        void onResult(boolean success, String errorOrNull);
    }

    // ------------------------------------------------------------------
    // Interactive flow registry (GoogleAuthActivity delivers here)
    // ------------------------------------------------------------------

    private static AuthCallback pendingCallback;
    private static boolean flowRunning;

    private GoogleAuthManager() {
    }

    /** Launches the transparent GoogleAuthActivity, which runs
     *  the authorize-with-resolution flow. One flow at a time: a
     *  second attempt fails fast, and a replaced callback is
     *  completed with an error so its owner never hangs. */
    public static synchronized void startInteractiveAuth(Context context, AuthCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        if (flowRunning) {
            safeAuth(callback, false, "A Google sign-in is already in progress.");
            return;
        }
        AuthCallback replaced = pendingCallback;
        pendingCallback = callback;
        flowRunning = true;
        if (replaced != null && replaced != callback) {
            safeAuth(replaced, false, "Superseded by a new sign-in attempt.");
        }
        try {
            Intent intent = new Intent(context, GoogleAuthActivity.class);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            deliverInteractiveResult(false, "Could not open Google sign-in: " + brief(t));
        }
    }

    /** Called by GoogleAuthActivity exactly once per flow. */
    static synchronized void deliverInteractiveResult(boolean success, String error) {
        AuthCallback callback = pendingCallback;
        pendingCallback = null;
        flowRunning = false;
        if (callback != null) {
            safeAuth(callback, success, error);
        }
    }

    private static void safeAuth(AuthCallback callback, boolean success, String error) {
        try {
            callback.onResult(success, error);
        } catch (Throwable ignored) {
        }
    }

    private static void safeToken(TokenCallback callback, String token, String error) {
        try {
            callback.onToken(token, error);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Public state + token access
    // ------------------------------------------------------------------

    /** True while a grant is cached (connected). Note the cached
     *  token itself may be past its TTL — the grant is what
     *  "signed in" means; getValidToken refreshes the token. */
    public static boolean isSignedIn(Context context) {
        return context != null && readCache(context) != null;
    }

    /** The connected account's email, or null when not connected
     *  (or when Google did not share it). */
    public static String connectedEmail(Context context) {
        Cache cache = context == null ? null : readCache(context);
        return cache == null ? null : cache.email;
    }

    /** Returns a valid access token: the cache while it has more
     *  than 60 s of (conservative) life left, otherwise a silent
     *  re-authorization. Fails (null + error) when not connected
     *  or when Google requires interactive consent. The callback
     *  may fire on the caller's thread (cache hit) or on the main
     *  thread (GMS completion) — callers that need a specific
     *  thread must re-post (the JS bridge does). */
    public static void getValidToken(Context context, final TokenCallback callback) {
        if (callback == null) {
            return;
        }
        if (context == null) {
            safeToken(callback, null, "No application context.");
            return;
        }
        final Context app = context.getApplicationContext();
        try {
            final Cache cache = readCache(app);
            if (cache == null || cache.token == null) {
                safeToken(callback, null, "Google account is not connected.");
                return;
            }
            if (cache.expiryMs - System.currentTimeMillis() > MIN_REMAINING_MS) {
                safeToken(callback, cache.token, null);
                return;
            }
            Identity.getAuthorizationClient(app).authorize(buildRequest(cache.email))
                    .addOnCompleteListener(task -> {
                        try {
                            if (!task.isSuccessful()) {
                                safeToken(callback, null, "Google sign-in check failed: "
                                        + brief(task.getException()));
                                return;
                            }
                            AuthorizationResult result = task.getResult();
                            if (result == null || result.hasResolution()
                                    || result.getAccessToken() == null) {
                                safeToken(callback, null,
                                        "Google needs an interactive sign-in first.");
                                return;
                            }
                            String email = emailOf(result);
                            saveAuthorization(app, result.getAccessToken(),
                                    email != null ? email : cache.email);
                            safeToken(callback, result.getAccessToken(), null);
                        } catch (Throwable t) {
                            safeToken(callback, null, "Google sign-in check failed: " + brief(t));
                        }
                    });
        } catch (Throwable t) {
            safeToken(callback, null, "Google sign-in check failed: " + brief(t));
        }
    }

    /** Removes the local cache and best-effort revokes the grant
     *  with Google (clearToken + revokeAccess, failures ignored —
     *  the local sign-out always succeeds). */
    public static void disconnect(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        Cache cache = readCache(app);
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_BLOB).apply();
        } catch (Throwable ignored) {
        }
        if (cache == null) {
            return;
        }
        try {
            AuthorizationClient client = Identity.getAuthorizationClient(app);
            if (cache.token != null) {
                try {
                    client.clearToken(ClearTokenRequest.builder()
                            .setToken(cache.token).build());
                } catch (Throwable ignored) {
                }
            }
            try {
                RevokeAccessRequest.Builder builder = RevokeAccessRequest.builder()
                        .setScopes(requestedScopes());
                if (cache.email != null) {
                    builder.setAccount(new Account(cache.email, "com.google"));
                }
                client.revokeAccess(builder.build());
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Authorization plumbing shared with GoogleAuthActivity
    // ------------------------------------------------------------------

    static List<Scope> requestedScopes() {
        List<Scope> scopes = new ArrayList<>();
        scopes.add(new Scope(SCOPE_CLOUD_PLATFORM));
        scopes.add(new Scope(SCOPE_EMAIL));
        return scopes;
    }

    static AuthorizationRequest buildRequest(String email) {
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(requestedScopes());
        if (email != null && !email.isEmpty()) {
            builder.setAccount(new Account(email, "com.google"));
        }
        return builder.build();
    }

    static String emailOf(AuthorizationResult result) {
        try {
            GoogleSignInAccount account = result == null ? null : result.toGoogleSignInAccount();
            return account == null ? null : account.getEmail();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Persists a fresh token (expiry = now + conservative TTL),
     *  keeping the previously stored email when the new result
     *  carries none. */
    static void saveAuthorization(Context context, String token, String email) {
        if (context == null || token == null) {
            return;
        }
        Cache cache = new Cache();
        cache.token = token;
        cache.expiryMs = System.currentTimeMillis() + TOKEN_TTL_MS;
        if (email == null || email.isEmpty()) {
            Cache previous = readCache(context);
            email = previous == null ? null : previous.email;
        }
        cache.email = email;
        writeCache(context, cache);
    }

    static String brief(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    // ------------------------------------------------------------------
    // Encrypted cache (AndroidKeyStore AES/GCM; one prefs blob)
    // ------------------------------------------------------------------

    private static final class Cache {
        String token;
        long expiryMs;
        String email;
    }

    private static Cache readCache(Context context) {
        try {
            String blob = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_BLOB, null);
            if (blob == null) {
                return null;
            }
            String plain = decrypt(blob);
            if (plain == null) {
                return null;
            }
            JSONObject json = new JSONObject(plain);
            Cache cache = new Cache();
            cache.token = json.optString("t", null);
            cache.expiryMs = json.optLong("e", 0L);
            String email = json.optString("m", null);
            cache.email = email == null || email.isEmpty() || "null".equals(email) ? null : email;
            if (cache.token == null) {
                return null;
            }
            return cache;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCache(Context context, Cache cache) {
        try {
            JSONObject json = new JSONObject();
            json.put("t", cache.token);
            json.put("e", cache.expiryMs);
            if (cache.email != null) {
                json.put("m", cache.email);
            }
            String blob = encrypt(json.toString());
            if (blob == null) {
                return;
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_BLOB, blob).apply();
        } catch (Throwable ignored) {
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + "."
                    + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decrypt(String blob) {
        try {
            int dot = blob.indexOf('.');
            if (dot < 0) {
                return null;
            }
            byte[] iv = Base64.decode(blob.substring(0, dot), Base64.NO_WRAP);
            byte[] data = Base64.decode(blob.substring(dot + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // The keystore key itself is the cipher key (it is
            // non-exportable by design — that is the point).
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
