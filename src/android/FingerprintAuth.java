package com.cordova.plugin.android.fingerprintauth;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Drop-in replacement for cordova-plugin-android-fingerprint-auth 1.5.0 built on
 * androidx.biometric BiometricPrompt.
 *
 * Compatible with data written by 1.5.0:
 *  - AES/CBC/PKCS7 key in AndroidKeyStore under alias = clientId
 *  - IV in SharedPreferences file (clientId + username), key "aes_iv"
 *  - token = Base64(AES(clientId + username + "|:|" + password))
 *
 * Every action always ends in exactly one success or error callback.
 */
public class FingerprintAuth extends CordovaPlugin {
    private static final String TAG = "FingerprintAuth";

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String FINGERPRINT_PREF_IV = "aes_iv";
    private static final String CREDENTIAL_DELIMITER = "|:|";
    private static final String TRANSFORMATION = KeyProperties.KEY_ALGORITHM_AES + "/"
            + KeyProperties.BLOCK_MODE_CBC + "/" + KeyProperties.ENCRYPTION_PADDING_PKCS7;
    // Crypto-bound keys can only be unlocked by Class 3 (strong) biometrics.
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    public enum PluginError {
        BAD_PADDING_EXCEPTION,
        CERTIFICATE_EXCEPTION,
        FINGERPRINT_CANCELLED,
        FINGERPRINT_DATA_NOT_DELETED,
        FINGERPRINT_ERROR,
        FINGERPRINT_NOT_AVAILABLE,
        FINGERPRINT_PERMISSION_DENIED,
        FINGERPRINT_PERMISSION_DENIED_SHOW_REQUEST,
        ILLEGAL_BLOCK_SIZE_EXCEPTION,
        INIT_CIPHER_FAILED,
        INVALID_ALGORITHM_PARAMETER_EXCEPTION,
        IO_EXCEPTION,
        JSON_EXCEPTION,
        MINIMUM_SDK,
        MISSING_ACTION_PARAMETERS,
        MISSING_PARAMETERS,
        NO_SUCH_ALGORITHM_EXCEPTION,
        SECURITY_EXCEPTION,
        FRAGMENT_NOT_EXIST
    }

    private BiometricPrompt mPrompt;
    private CallbackContext mPendingCallback;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        Log.v(TAG, "FingerprintAuth action: " + action);
        try {
            switch (action) {
                case "availability":
                    sendAvailabilityResult(callbackContext);
                    return true;
                case "encrypt":
                    startCrypt(true, args.getJSONObject(0), callbackContext);
                    return true;
                case "decrypt":
                    startCrypt(false, args.getJSONObject(0), callbackContext);
                    return true;
                case "delete":
                    delete(args.getJSONObject(0), callbackContext);
                    return true;
                case "dismiss":
                    dismiss(callbackContext);
                    return true;
                default:
                    return false;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Bad arguments for " + action, e);
            callbackContext.error(PluginError.JSON_EXCEPTION.name());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in " + action, t);
            callbackContext.error(PluginError.FINGERPRINT_ERROR.name());
            return true;
        }
    }

    /* ---------------------------------------------------------------- availability */

    private int canAuthenticate() {
        return BiometricManager.from(cordova.getActivity()).canAuthenticate(AUTHENTICATORS);
    }

    private void sendAvailabilityResult(CallbackContext cb) throws JSONException {
        int status;
        try {
            status = canAuthenticate();
        } catch (Throwable t) {
            Log.e(TAG, "canAuthenticate failed", t);
            status = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
        }
        boolean hardware = status != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
                && status != BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED;
        boolean enrolled = status == BiometricManager.BIOMETRIC_SUCCESS;

        JSONObject result = new JSONObject();
        result.put("isAvailable", enrolled);
        result.put("isHardwareDetected", hardware);
        result.put("hasEnrolledFingerprints", enrolled);
        result.put("biometricStatus", status);
        Log.v(TAG, "availability: " + result);
        cb.success(result);
    }

    /* ---------------------------------------------------------------- encrypt / decrypt */

    private void startCrypt(final boolean encrypt, final JSONObject cfg, final CallbackContext cb)
            throws JSONException {
        if (!cfg.has("clientId")) {
            cb.error(PluginError.MISSING_PARAMETERS.name());
            return;
        }
        final String clientId = cfg.getString("clientId");
        final String username = cfg.optString("username", "");
        final String secret;
        if (encrypt) {
            secret = clientId + username + CREDENTIAL_DELIMITER + cfg.optString("password", "");
        } else if (cfg.has("token")) {
            secret = cfg.getString("token");
        } else {
            cb.error(PluginError.MISSING_ACTION_PARAMETERS.name());
            return;
        }
        boolean userAuthRequired = cfg.optBoolean("userAuthRequired", false);

        Cipher cipher;
        try {
            cipher = initCipher(encrypt, clientId, username, userAuthRequired);
        } catch (KeyPermanentlyInvalidatedException e) {
            // Biometrics changed since the key was created (new fingerprint, lock screen removed).
            Log.w(TAG, "Key permanently invalidated, deleting it", e);
            deleteKeyAndIv(clientId, username);
            if (!encrypt) {
                cb.error(PluginError.INIT_CIPHER_FAILED.name());
                return;
            }
            try {
                cipher = initCipher(true, clientId, username, userAuthRequired);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to init cipher after key reset", e2);
                cb.error(PluginError.INIT_CIPHER_FAILED.name());
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init cipher", e);
            cb.error(PluginError.INIT_CIPHER_FAILED.name());
            return;
        }

        if (encrypt && cfg.optBoolean("encryptNoAuth", false)) {
            finishCrypt(true, cipher, secret, clientId, username, false, cb);
            return;
        }

        int status = canAuthenticate();
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.e(TAG, "Biometric authentication not available, status " + status);
            cb.error(PluginError.FINGERPRINT_NOT_AVAILABLE.name());
            return;
        }

        final Cipher promptCipher = cipher;
        cordova.getActivity().runOnUiThread(() -> {
            try {
                showPrompt(encrypt, cfg, promptCipher, secret, clientId, username, cb);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to show BiometricPrompt", t);
                if (mPendingCallback == cb) {
                    mPendingCallback = null;
                }
                cb.error(PluginError.FINGERPRINT_ERROR.name());
            }
        });
    }

    private void showPrompt(final boolean encrypt, JSONObject cfg, Cipher cipher, final String secret,
                            final String clientId, final String username, final CallbackContext cb) {
        cancelPending();

        Activity activity = cordova.getActivity();
        Context strings = localizedContext(activity, cfg.optString("locale", ""));
        String title = cfg.optString("dialogTitle", "");
        if (title.isEmpty()) {
            title = getString(strings, "fpauth_title", "Biometric authentication");
        }
        BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(getString(strings, "fpauth_cancel", "Cancel"))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false);
        String message = cfg.optString("dialogMessage", "");
        if (!message.isEmpty()) {
            info.setDescription(message);
        }

        mPendingCallback = cb;
        mPrompt = new BiometricPrompt((FragmentActivity) activity,
                ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        if (mPendingCallback != cb) return;
                        mPendingCallback = null;
                        BiometricPrompt.CryptoObject crypto = result.getCryptoObject();
                        if (crypto == null || crypto.getCipher() == null) {
                            cb.error(PluginError.INIT_CIPHER_FAILED.name());
                            return;
                        }
                        finishCrypt(encrypt, crypto.getCipher(), secret, clientId, username, true, cb);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        if (mPendingCallback != cb) return;
                        mPendingCallback = null;
                        Log.e(TAG, "Authentication error " + errorCode + ": " + errString);
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || errorCode == BiometricPrompt.ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.ERROR_CANCELED) {
                            cb.error(PluginError.FINGERPRINT_CANCELLED.name());
                        } else {
                            cb.error(PluginError.FINGERPRINT_ERROR.name());
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Not recognised; the prompt stays open and lets the user retry.
                        Log.v(TAG, "Authentication failed (not recognised)");
                    }
                });
        mPrompt.authenticate(info.build(), new BiometricPrompt.CryptoObject(cipher));
    }

    private void finishCrypt(boolean encrypt, Cipher cipher, String secret, String clientId,
                             String username, boolean withFingerprint, CallbackContext cb) {
        try {
            JSONObject result = new JSONObject();
            result.put(withFingerprint ? "withFingerprint" : "withBackup", true);
            if (encrypt) {
                byte[] bytes = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
                // Store the IV only once the new token exists, so a cancelled re-enrolment
                // does not break the token the app already holds.
                setIv(clientId + username, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
                result.put("token", Base64.encodeToString(bytes, Base64.NO_WRAP));
            } else {
                byte[] bytes = cipher.doFinal(Base64.decode(secret, Base64.NO_WRAP));
                String credentials = new String(bytes, StandardCharsets.UTF_8);
                String expectedUser = clientId + username;
                int i = credentials.indexOf(CREDENTIAL_DELIMITER);
                if (i >= 0) {
                    if (credentials.substring(0, i).equalsIgnoreCase(expectedUser)) {
                        result.put("password", credentials.substring(i + CREDENTIAL_DELIMITER.length()));
                    }
                } else {
                    String[] parts = credentials.split(":");
                    if (parts.length == 2 && parts[0].equalsIgnoreCase(expectedUser)) {
                        result.put("password", parts[1]);
                    }
                }
            }
            cb.success(result);
        } catch (BadPaddingException e) {
            Log.e(TAG, "doFinal failed", e);
            cb.error(PluginError.BAD_PADDING_EXCEPTION.name());
        } catch (IllegalBlockSizeException e) {
            Log.e(TAG, "doFinal failed", e);
            cb.error(PluginError.ILLEGAL_BLOCK_SIZE_EXCEPTION.name());
        } catch (JSONException e) {
            cb.error(PluginError.JSON_EXCEPTION.name());
        } catch (Throwable t) {
            Log.e(TAG, "doFinal failed", t);
            cb.error(PluginError.FINGERPRINT_ERROR.name());
        }
    }

    /* ---------------------------------------------------------------- keys */

    private Cipher initCipher(boolean encrypt, String clientId, String username,
                              boolean userAuthRequired) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        SecretKey key = (SecretKey) keyStore.getKey(clientId, null);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        if (encrypt) {
            if (key == null) {
                key = createKey(clientId, userAuthRequired);
            }
            cipher.init(Cipher.ENCRYPT_MODE, key);
        } else {
            if (key == null) {
                throw new GeneralSecurityException("No key for " + clientId);
            }
            String iv = getPrefs(clientId + username).getString(FINGERPRINT_PREF_IV, null);
            if (iv == null) {
                throw new GeneralSecurityException("No IV stored");
            }
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(Base64.decode(iv, Base64.NO_WRAP)));
        }
        return cipher;
    }

    private static SecretKey createKey(String alias, boolean userAuthRequired) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(userAuthRequired)
                .build());
        return generator.generateKey();
    }

    private boolean deleteKeyAndIv(String clientId, String username) {
        boolean keyDeleted = false;
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            keyStore.deleteEntry(clientId);
            keyDeleted = true;
        } catch (Exception e) {
            Log.e(TAG, "Error while deleting SecretKey.", e);
        }
        boolean ivDeleted = getPrefs(clientId + username).edit().remove(FINGERPRINT_PREF_IV).commit();
        return keyDeleted && ivDeleted;
    }

    private void delete(JSONObject cfg, CallbackContext cb) throws JSONException {
        if (!cfg.has("clientId")) {
            cb.error(PluginError.MISSING_PARAMETERS.name());
            return;
        }
        if (deleteKeyAndIv(cfg.getString("clientId"), cfg.optString("username", ""))) {
            cb.success();
        } else {
            cb.error(PluginError.FINGERPRINT_DATA_NOT_DELETED.name());
        }
    }

    /* ---------------------------------------------------------------- dismiss / lifecycle */

    private void dismiss(final CallbackContext cb) {
        if (mPendingCallback == null || mPrompt == null) {
            cb.error(PluginError.FRAGMENT_NOT_EXIST.name());
            return;
        }
        cordova.getActivity().runOnUiThread(() -> {
            cancelPending();
            cb.success("Fragment dismissed");
        });
    }

    /** Cancels an open prompt and fails its callback. Must run on the UI thread. */
    private void cancelPending() {
        CallbackContext pending = mPendingCallback;
        mPendingCallback = null;
        if (mPrompt != null) {
            try {
                mPrompt.cancelAuthentication();
            } catch (Throwable t) {
                Log.w(TAG, "cancelAuthentication failed", t);
            }
        }
        if (pending != null) {
            pending.error(PluginError.FINGERPRINT_CANCELLED.name());
        }
    }

    @Override
    public void onReset() {
        cordova.getActivity().runOnUiThread(this::cancelPending);
    }

    /* ---------------------------------------------------------------- helpers */

    private void setIv(String prefsName, String iv) {
        getPrefs(prefsName).edit().putString(FINGERPRINT_PREF_IV, iv).commit();
    }

    private SharedPreferences getPrefs(String name) {
        return cordova.getActivity().getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static Context localizedContext(Context base, String code) {
        if (code == null || code.isEmpty()) {
            return base;
        }
        String[] parts = code.split("[_-]");
        Locale locale = parts.length > 1
                ? new Locale(parts[0].toLowerCase(Locale.ROOT), parts[1].toUpperCase(Locale.ROOT))
                : new Locale(parts[0].toLowerCase(Locale.ROOT));
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(locale);
        return base.createConfigurationContext(conf);
    }

    private static String getString(Context context, String name, String fallback) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? fallback : context.getString(id);
    }
}
