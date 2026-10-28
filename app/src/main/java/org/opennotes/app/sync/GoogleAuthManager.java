package org.opennotes.app.sync;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

import org.json.JSONObject;
import org.opennotes.app.db.AppSettings;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Manages Google Sign-In and OAuth token retrieval for Google Drive cloud synchronization.
 * Supports both Google Play Services native 1-tap sign-in and direct Browser OAuth 2.0 PKCE flow.
 */
public final class GoogleAuthManager {
    private static final String TAG = "GoogleAuthManager";
    public static final Scope DRIVE_FILE_SCOPE = new Scope("https://www.googleapis.com/auth/drive.file");
    public static final String DRIVE_OAUTH_SCOPE_STRING = "oauth2:https://www.googleapis.com/auth/drive.file";

    public static final String DEFAULT_CLIENT_ID = "609741370963-lmum030ae8mfcot7gf9jli2t5gncfj7l.apps.googleusercontent.com";
    public static final String PROJECT_ID = "delta-sanctum-292606";
    public static final String DEBUG_SHA1_FINGERPRINT = "92:01:20:1A:57:14:6D:83:3C:CD:CC:20:30:C9:AC:F9:EF:CC:16:BC";

    public interface BrowserAuthCallback {
        void onSuccess(String email, String displayName);
        void onError(String errorMessage);
    }

    private static GoogleAuthManager sInstance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String cachedToken;

    private GoogleAuthManager() {}

    public static synchronized GoogleAuthManager getInstance() {
        if (sInstance == null) {
            sInstance = new GoogleAuthManager();
        }
        return sInstance;
    }

    /**
     * Builds standard Google Sign-In client options requesting email, profile, and drive.file scope.
     */
    public GoogleSignInOptions buildSignInOptions() {
        return new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestScopes(DRIVE_FILE_SCOPE)
                .build();
    }

    /**
     * Builds the Google Sign-In Intent to be launched by an Activity or ActivityResultLauncher.
     */
    public Intent getSignInIntent(@NonNull Activity activity) {
        GoogleSignInClient client = GoogleSignIn.getClient(activity, buildSignInOptions());
        return client.getSignInIntent();
    }

    /**
     * Returns true if user is currently signed in to Google and permissions are active.
     */
    public boolean isSignedIn(@NonNull Context context) {
        AppSettings settings = AppSettings.getInstance(context);
        return settings.isGoogleSignedIn();
    }

    @Nullable
    public GoogleSignInAccount getSignedInAccount(@NonNull Context context) {
        return GoogleSignIn.getLastSignedInAccount(context);
    }

    /**
     * Updates persistent user information when Google sign-in completes successfully.
     */
    public void onSignInSuccess(@NonNull Context context, @NonNull GoogleSignInAccount account) {
        String email = account.getEmail();
        String name = account.getDisplayName();
        String photoUrl = account.getPhotoUrl() != null ? account.getPhotoUrl().toString() : "";
        AppSettings.getInstance(context).setGoogleAccount(email, name, photoUrl);
        Log.i(TAG, "Google Sign-In successful for: " + email);
    }

    /**
     * Signs out the user and clears stored local credentials.
     */
    public void signOut(@NonNull Context context, @Nullable Runnable onComplete) {
        cachedToken = null;
        AppSettings.getInstance(context).clearGoogleAccount();

        try {
            GoogleSignInClient client = GoogleSignIn.getClient(context, buildSignInOptions());
            client.signOut().addOnCompleteListener(task -> {
                Log.i(TAG, "Google Sign-Out completed");
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Play Services sign out warning: " + t.getMessage());
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    /**
     * Retrieves an OAuth 2.0 Bearer access token for Google Drive API.
     * Checks browser OAuth tokens first, then falls back to Google Play Services.
     * MUST be called from a background worker thread.
     */
    @NonNull
    public synchronized String getAccessToken(@NonNull Context context) throws IOException, GoogleAuthException {
        AppSettings settings = AppSettings.getInstance(context);

        // 1. Check Browser OAuth refresh token flow
        String refreshToken = settings.getGoogleOAuthRefreshToken();
        if (refreshToken != null && !refreshToken.isEmpty()) {
            long expiresAt = settings.getGoogleOAuthExpiresAt();
            String accessToken = settings.getGoogleOAuthAccessToken();
            if (accessToken != null && !accessToken.isEmpty() && System.currentTimeMillis() < (expiresAt - 60000L)) {
                return accessToken;
            }
            // Refresh token
            try {
                return refreshOAuthAccessToken(context, refreshToken);
            } catch (Exception e) {
                Log.e(TAG, "Failed to refresh OAuth token", e);
                throw new IOException("Failed to refresh Google OAuth token: " + e.getMessage(), e);
            }
        }

        // 2. Play Services cached token
        if (cachedToken != null) {
            return cachedToken;
        }

        // 3. Play Services AuthUtil token
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        if (account == null) {
            throw new IOException("Not signed in to Google.");
        }

        Account androidAccount = account.getAccount();
        if (androidAccount == null) {
            androidAccount = new Account(account.getEmail() != null ? account.getEmail() : "", "com.google");
        }

        try {
            cachedToken = GoogleAuthUtil.getToken(context.getApplicationContext(), androidAccount, DRIVE_OAUTH_SCOPE_STRING);
            return cachedToken;
        } catch (com.google.android.gms.auth.UserRecoverableAuthException e) {
            Log.w(TAG, "Google Play Services requires user authorization consent: " + e.getMessage());
            throw new IOException("Google Drive permissions require authorization. Please use 'Sign in via Browser' to connect.", e);
        } catch (GoogleAuthException | IOException e) {
            Log.e(TAG, "Failed to obtain Google OAuth access token", e);
            throw e;
        }
    }

    /**
     * Invalidates the cached token so the next call refreshes with Google.
     */
    public synchronized void invalidateToken(@NonNull Context context) {
        AppSettings settings = AppSettings.getInstance(context);
        if (!settings.getGoogleOAuthRefreshToken().isEmpty()) {
            settings.setGoogleOAuthTokens("", settings.getGoogleOAuthRefreshToken(), 0);
        }
        if (cachedToken != null) {
            try {
                GoogleAuthUtil.clearToken(context.getApplicationContext(), cachedToken);
            } catch (Throwable t) {
                Log.w(TAG, "Could not clear token: " + t.getMessage());
            }
            cachedToken = null;
        }
    }

    /**
     * Refreshes the OAuth access token using the stored refresh token.
     */
    private String refreshOAuthAccessToken(Context context, String refreshToken) throws Exception {
        URL url = new URL("https://oauth2.googleapis.com/token");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        String body = "grant_type=refresh_token"
                + "&client_id=" + URLEncoder.encode(DEFAULT_CLIENT_ID, "UTF-8")
                + "&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readStream(conn.getErrorStream());
            throw new IOException("Token refresh failed (HTTP " + code + "): " + err);
        }

        String response = readStream(conn.getInputStream());
        JSONObject json = new JSONObject(response);
        String newAccessToken = json.getString("access_token");
        String newRefreshToken = json.optString("refresh_token", refreshToken);
        long expiresIn = json.optLong("expires_in", 3600L);

        AppSettings.getInstance(context).setGoogleOAuthTokens(newAccessToken, newRefreshToken, expiresIn);
        return newAccessToken;
    }

    /**
     * Performs standard OAuth 2.0 PKCE flow in the browser using the Installed Client ID.
     * Uses a temporary loopback HTTP server on 127.0.0.1 (RFC 8252 compliant).
     */
    public void startBrowserOAuth(@NonNull Activity activity, @NonNull BrowserAuthCallback callback) {
        new Thread(() -> {
            ServerSocket serverSocket = null;
            try {
                // 1. Start local loopback server on an available port
                serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                int port = serverSocket.getLocalPort();
                serverSocket.setSoTimeout(180000); // 3 minutes timeout

                String redirectUri = "http://127.0.0.1:" + port + "/oauth2callback";

                // 2. Generate PKCE code verifier and challenge
                SecureRandom sr = new SecureRandom();
                byte[] codeVerifierBytes = new byte[32];
                sr.nextBytes(codeVerifierBytes);
                String codeVerifier = Base64.encodeToString(codeVerifierBytes,
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
                String codeChallenge = Base64.encodeToString(digest,
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

                // 3. Build authorization URL
                String authUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                        + "?client_id=" + URLEncoder.encode(DEFAULT_CLIENT_ID, "UTF-8")
                        + "&response_type=code"
                        + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
                        + "&scope=" + URLEncoder.encode("https://www.googleapis.com/auth/drive.file email profile", "UTF-8")
                        + "&code_challenge=" + URLEncoder.encode(codeChallenge, "UTF-8")
                        + "&code_challenge_method=S256"
                        + "&access_type=offline"
                        + "&prompt=consent";

                // 4. Open Browser
                activity.runOnUiThread(() -> {
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
                        activity.startActivity(browserIntent);
                    } catch (Exception e) {
                        callback.onError("Could not launch browser: " + e.getMessage());
                    }
                });

                // 5. Wait for loopback redirect request
                Socket socket = serverSocket.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = reader.readLine();

                String authCode = null;
                if (requestLine != null && requestLine.contains("GET /oauth2callback")) {
                    int codeIndex = requestLine.indexOf("code=");
                    if (codeIndex != -1) {
                        int endIndex = requestLine.indexOf('&', codeIndex);
                        if (endIndex == -1) endIndex = requestLine.indexOf(' ', codeIndex);
                        authCode = requestLine.substring(codeIndex + 5, endIndex);
                    }
                }

                // 6. Send friendly browser response
                String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>OpenNotes Connected</title>"
                        + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                        + "<style>body{background:#121422;color:#fff;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;text-align:center;padding:60px 20px;}h1{color:#4ADE80;margin-bottom:12px;}p{color:#A0AEC0;font-size:16px;}</style></head>"
                        + "<body><h1>✅ Connected to Google Drive!</h1><p>You can now close this browser tab and return to OpenNotes.</p></body></html>";

                byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=UTF-8\r\n"
                        + "Content-Length: " + htmlBytes.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(htmlBytes);
                out.flush();
                socket.close();

                if (authCode == null) {
                    mainHandler.post(() -> callback.onError("Authorization code not received from Google."));
                    return;
                }

                // 7. Exchange code for access & refresh tokens
                URL tokenUrl = new URL("https://oauth2.googleapis.com/token");
                HttpURLConnection tokenConn = (HttpURLConnection) tokenUrl.openConnection();
                tokenConn.setRequestMethod("POST");
                tokenConn.setConnectTimeout(20000);
                tokenConn.setReadTimeout(20000);
                tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                tokenConn.setDoOutput(true);

                String tokenRequestBody = "code=" + URLEncoder.encode(authCode, "UTF-8")
                        + "&client_id=" + URLEncoder.encode(DEFAULT_CLIENT_ID, "UTF-8")
                        + "&code_verifier=" + URLEncoder.encode(codeVerifier, "UTF-8")
                        + "&grant_type=authorization_code"
                        + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8");

                try (OutputStream os = tokenConn.getOutputStream()) {
                    os.write(tokenRequestBody.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int tokenHttpCode = tokenConn.getResponseCode();
                if (tokenHttpCode != 200) {
                    String err = readStream(tokenConn.getErrorStream());
                    mainHandler.post(() -> callback.onError("Token exchange failed: " + err));
                    return;
                }

                String tokenResponse = readStream(tokenConn.getInputStream());
                JSONObject tokenJson = new JSONObject(tokenResponse);
                String accessToken = tokenJson.getString("access_token");
                String refreshToken = tokenJson.optString("refresh_token", "");
                long expiresIn = tokenJson.optLong("expires_in", 3600L);

                // 8. Fetch user profile information
                URL userInfoUrl = new URL("https://www.googleapis.com/oauth2/v2/userinfo");
                HttpURLConnection userConn = (HttpURLConnection) userInfoUrl.openConnection();
                userConn.setRequestMethod("GET");
                userConn.setRequestProperty("Authorization", "Bearer " + accessToken);

                String email = "Google User";
                String name = "Google User";
                String picture = "";
                if (userConn.getResponseCode() == 200) {
                    String userJsonStr = readStream(userConn.getInputStream());
                    JSONObject userJson = new JSONObject(userJsonStr);
                    email = userJson.optString("email", email);
                    name = userJson.optString("name", email);
                    picture = userJson.optString("picture", "");
                }

                // 9. Persist credentials
                AppSettings settings = AppSettings.getInstance(activity);
                settings.setGoogleAccount(email, name, picture);
                settings.setGoogleOAuthTokens(accessToken, refreshToken, expiresIn);

                final String finalEmail = email;
                final String finalName = name;
                mainHandler.post(() -> callback.onSuccess(finalEmail, finalName));

            } catch (Exception e) {
                Log.e(TAG, "Browser OAuth error", e);
                mainHandler.post(() -> callback.onError("Sign-in error: " + e.getMessage()));
            } finally {
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (Throwable ignored) {}
                }
            }
        }).start();
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }
}
