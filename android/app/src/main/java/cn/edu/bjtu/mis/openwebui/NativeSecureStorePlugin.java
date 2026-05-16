package cn.edu.bjtu.mis.openwebui;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;

@CapacitorPlugin(name = "NativeSecureStore")
public class NativeSecureStorePlugin extends Plugin {
    private static final String PREFS_NAME = "open_webui_secure_store";
    private static final String KEY_ALIAS = "open_webui_secure_store_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;
    private static final String SEPARATOR = ":";

    @PluginMethod
    public void set(PluginCall call) {
        String key = call.getString("key");
        String value = call.getString("value");
        if (key == null || key.isEmpty()) {
            call.reject("key is required");
            return;
        }
        if (value == null) {
            call.reject("value is required");
            return;
        }

        try {
            getPrefs().edit().putString(key, encrypt(value)).apply();
            JSObject response = new JSObject();
            response.put("key", key);
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Failed to store secure value", error);
        }
    }

    @PluginMethod
    public void get(PluginCall call) {
        String key = call.getString("key");
        if (key == null || key.isEmpty()) {
            call.reject("key is required");
            return;
        }

        try {
            String encrypted = getPrefs().getString(key, null);
            JSObject response = new JSObject();
            response.put("key", key);
            response.put("value", encrypted == null ? null : decrypt(encrypted));
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Failed to read secure value", error);
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String key = call.getString("key");
        if (key == null || key.isEmpty()) {
            call.reject("key is required");
            return;
        }

        getPrefs().edit().remove(key).apply();
        JSObject response = new JSObject();
        response.put("key", key);
        call.resolve(response);
    }

    @PluginMethod
    public void keys(PluginCall call) {
        JSONArray keys = new JSONArray();
        for (String key : getPrefs().getAll().keySet()) {
            keys.put(key);
        }
        JSObject response = new JSObject();
        response.put("keys", keys);
        call.resolve(response);
    }

    private SharedPreferences getPrefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split(SEPARATOR, 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid secure store payload");
        }

        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] decrypted = cipher.doFinal(encrypted);
        Arrays.fill(encrypted, (byte) 0);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }
}
