package cn.edu.bjtu.mis.openwebui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.activity.result.ActivityResult;
import cn.edu.bjtu.mis.R;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;

@CapacitorPlugin(
    name = "NativeAndroidTools",
    permissions = {
        @Permission(alias = "calendar", strings = { Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR }),
        @Permission(alias = "audio", strings = { Manifest.permission.RECORD_AUDIO }),
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION })
    }
)
public class NativeAndroidToolsPlugin extends Plugin {
    private static final String NOTIFICATION_CHANNEL_ID = "open_webui_local";
    private final ConcurrentHashMap<String, RecordingSession> recordings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Uri> pendingPhotoUris = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pendingTextDocuments = new ConcurrentHashMap<>();

    @PluginMethod
    public void getDeviceContext(PluginCall call) {
        JSObject response = new JSObject();
        response.put("platform", "android");
        response.put("sdk_int", Build.VERSION.SDK_INT);
        response.put("manufacturer", Build.MANUFACTURER);
        response.put("model", Build.MODEL);
        response.put("locale", Locale.getDefault().toLanguageTag());
        response.put("timezone", TimeZone.getDefault().getID());
        response.put("battery", getBatteryInfo());
        response.put("network", getNetworkInfo());
        call.resolve(response);
    }

    @PluginMethod
    public void getAppInfo(PluginCall call) {
        try {
            PackageManager packageManager = getContext().getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(getContext().getPackageName(), 0);

            JSObject response = new JSObject();
            response.put("packageName", getContext().getPackageName());
            response.put("versionName", packageInfo.versionName);
            response.put(
                "versionCode",
                Build.VERSION.SDK_INT >= 28 ? packageInfo.getLongVersionCode() : packageInfo.versionCode
            );
            response.put("sdkInt", Build.VERSION.SDK_INT);
            response.put("manufacturer", Build.MANUFACTURER);
            response.put("model", Build.MODEL);
            response.put("locale", Locale.getDefault().toLanguageTag());
            response.put("timezone", TimeZone.getDefault().getID());
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Failed to read Android app info", error);
        }
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notificationPermissionCallback");
            return;
        }
        resolvePermission(call, true);
    }

    @PluginMethod
    public void showNotification(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notificationPermissionCallback");
            return;
        }
        doShowNotification(call);
    }

    @PluginMethod
    public void vibrate(PluginCall call) {
        long durationMs = Math.max(1, Math.min(1000, call.getLong("durationMs", 10L)));
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager != null ? manager.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                call.reject("vibrator_unavailable");
                return;
            }

            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(durationMs);
            }

            JSObject response = new JSObject();
            response.put("vibrated", true);
            response.put("durationMs", durationMs);
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Failed to vibrate", error);
        }
    }

    @PluginMethod
    public void getCurrentLocation(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "locationPermissionCallback");
            return;
        }
        doGetCurrentLocation(call);
    }

    @PermissionCallback
    private void locationPermissionCallback(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            call.reject("user_permission_required");
            return;
        }
        doGetCurrentLocation(call);
    }

    @PluginMethod
    public void writeClipboard(PluginCall call) {
        String text = call.getString("text", "");
        String html = call.getString("html", null);
        ClipboardManager manager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            call.reject("clipboard_unavailable");
            return;
        }

        ClipData clip = html != null && !html.isEmpty()
            ? ClipData.newHtmlText("Open WebUI", text, html)
            : ClipData.newPlainText("Open WebUI", text);
        manager.setPrimaryClip(clip);

        JSObject response = new JSObject();
        response.put("written", true);
        call.resolve(response);
    }

    @PluginMethod
    public void readClipboardText(PluginCall call) {
        ClipboardManager manager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip() || manager.getPrimaryClip() == null) {
            JSObject response = new JSObject();
            response.put("text", "");
            call.resolve(response);
            return;
        }

        ClipData.Item item = manager.getPrimaryClip().getItemAt(0);
        CharSequence text = item.coerceToText(getContext());
        JSObject response = new JSObject();
        response.put("text", text != null ? text.toString() : "");
        call.resolve(response);
    }

    @PluginMethod
    public void openTextDocument(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        JSONArray mimeTypes = call.getArray("mimeTypes", null);
        if (mimeTypes != null && mimeTypes.length() > 0) {
            String[] values = new String[mimeTypes.length()];
            for (int i = 0; i < mimeTypes.length(); i++) {
                values[i] = mimeTypes.optString(i, "*/*");
            }
            intent.putExtra(Intent.EXTRA_MIME_TYPES, values);
        } else {
            intent.setType(call.getString("mimeType", "text/*"));
        }

        startActivityForResult(call, intent, "openTextDocumentResult");
    }

    @ActivityCallback
    private void openTextDocumentResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("user_denied");
            return;
        }

        Uri uri = result.getData().getData();
        try {
            JSObject response = describeUri(uri);
            response.put("uri", uri.toString());
            response.put("content", readTextFromUri(uri));
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Failed to read selected document", error);
        }
    }

    @PluginMethod
    public void saveTextDocument(PluginCall call) {
        String fileName = call.getString("fileName", "open-webui-export.json");
        String mimeType = call.getString("mimeType", "application/json");
        String content = call.getString("content", "");
        pendingTextDocuments.put(call.getCallbackId(), content);

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(call, intent, "saveTextDocumentResult");
    }

    @ActivityCallback
    private void saveTextDocumentResult(PluginCall call, ActivityResult result) {
        String content = pendingTextDocuments.remove(call.getCallbackId());
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("user_denied");
            return;
        }

        Uri uri = result.getData().getData();
        try (OutputStream outputStream = getContext().getContentResolver().openOutputStream(uri, "wt")) {
            if (outputStream == null) {
                call.reject("Failed to open output document");
                return;
            }
            byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
            outputStream.write(bytes);

            JSObject response = new JSObject();
            response.put("uri", uri.toString());
            response.put("size", bytes.length);
            response.put("saved", true);
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Failed to save document", error);
        }
    }

    @PluginMethod
    public void pickMedia(PluginCall call) {
        String mimeType = call.getString("mimeType", "*/*");
        boolean multiple = Boolean.TRUE.equals(call.getBoolean("multiple", false));
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType == null || mimeType.isEmpty() ? "*/*" : mimeType);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        startActivityForResult(call, intent, "pickMediaResult");
    }

    @ActivityCallback
    private void pickMediaResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK) {
            call.reject("user_denied");
            return;
        }

        Intent data = result.getData();
        JSONArray items = new JSONArray();
        if (data != null && data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                items.put(describeUri(data.getClipData().getItemAt(i).getUri()));
            }
        } else if (data != null && data.getData() != null) {
            items.put(describeUri(data.getData()));
        }

        JSObject response = new JSObject();
        response.put("items", items);
        call.resolve(response);
    }

    @PluginMethod
    public void capturePhoto(PluginCall call) {
        try {
            File dir = new File(getContext().getCacheDir(), "captures");
            if (!dir.exists() && !dir.mkdirs()) {
                call.reject("Failed to create capture directory");
                return;
            }

            File file = new File(dir, "open-webui-" + System.currentTimeMillis() + ".jpg");
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
            pendingPhotoUris.put(call.getCallbackId(), uri);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(call, intent, "capturePhotoResult");
        } catch (Exception error) {
            call.reject("Failed to start camera capture", error);
        }
    }

    @ActivityCallback
    private void capturePhotoResult(PluginCall call, ActivityResult result) {
        Uri uri = pendingPhotoUris.remove(call.getCallbackId());
        if (result.getResultCode() != Activity.RESULT_OK || uri == null) {
            call.reject("user_denied");
            return;
        }

        JSObject response = new JSObject();
        response.put("uri", uri.toString());
        response.put("mimeType", "image/jpeg");
        call.resolve(response);
    }

    @PluginMethod
    public void startAudioRecording(PluginCall call) {
        if (getPermissionState("audio") != PermissionState.GRANTED) {
            requestPermissionForAlias("audio", call, "audioPermissionCallback");
            return;
        }
        doStartAudioRecording(call);
    }

    @PermissionCallback
    private void audioPermissionCallback(PluginCall call) {
        if (getPermissionState("audio") != PermissionState.GRANTED) {
            call.reject("user_permission_required");
            return;
        }
        doStartAudioRecording(call);
    }

    private void doStartAudioRecording(PluginCall call) {
        String requestId = call.getString("requestId");
        if (requestId == null || requestId.isEmpty()) {
            call.reject("requestId is required");
            return;
        }
        if (recordings.containsKey(requestId)) {
            call.reject("Recording already exists for requestId");
            return;
        }

        try {
            File dir = new File(getContext().getCacheDir(), "recordings");
            if (!dir.exists() && !dir.mkdirs()) {
                call.reject("Failed to create recording directory");
                return;
            }
            File file = new File(dir, "open-webui-" + requestId + ".m4a");
            MediaRecorder recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(getContext()) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recordings.put(requestId, new RecordingSession(recorder, file));

            JSObject response = new JSObject();
            response.put("requestId", requestId);
            response.put("started", true);
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Failed to start audio recording", error);
        }
    }

    @PluginMethod
    public void stopAudioRecording(PluginCall call) {
        String requestId = call.getString("requestId");
        if (requestId == null || requestId.isEmpty()) {
            call.reject("requestId is required");
            return;
        }

        RecordingSession session = recordings.remove(requestId);
        if (session == null) {
            call.reject("Recording not found");
            return;
        }

        try {
            session.recorder.stop();
        } catch (Exception ignored) {
            // stop can throw when no audio frames were captured; still release below.
        } finally {
            session.recorder.release();
        }

        Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", session.file);
        JSObject response = new JSObject();
        response.put("requestId", requestId);
        response.put("uri", uri.toString());
        response.put("mimeType", "audio/mp4");
        response.put("size", session.file.length());
        call.resolve(response);
    }

    @PluginMethod
    public void listCalendarEvents(PluginCall call) {
        if (getPermissionState("calendar") != PermissionState.GRANTED) {
            requestPermissionForAlias("calendar", call, "calendarPermissionCallback");
            return;
        }
        doListCalendarEvents(call);
    }

    @PluginMethod
    public void createCalendarEvent(PluginCall call) {
        if (getPermissionState("calendar") != PermissionState.GRANTED) {
            requestPermissionForAlias("calendar", call, "calendarPermissionCallback");
            return;
        }
        doCreateCalendarEvent(call);
    }

    @PluginMethod
    public void updateCalendarEvent(PluginCall call) {
        if (getPermissionState("calendar") != PermissionState.GRANTED) {
            requestPermissionForAlias("calendar", call, "calendarPermissionCallback");
            return;
        }
        doUpdateCalendarEvent(call);
    }

    @PluginMethod
    public void deleteCalendarEvent(PluginCall call) {
        if (getPermissionState("calendar") != PermissionState.GRANTED) {
            requestPermissionForAlias("calendar", call, "calendarPermissionCallback");
            return;
        }
        doDeleteCalendarEvent(call);
    }

    @PermissionCallback
    private void calendarPermissionCallback(PluginCall call) {
        if (getPermissionState("calendar") != PermissionState.GRANTED) {
            call.reject("user_permission_required");
            return;
        }

        String method = call.getMethodName();
        if ("listCalendarEvents".equals(method)) doListCalendarEvents(call);
        else if ("createCalendarEvent".equals(method)) doCreateCalendarEvent(call);
        else if ("updateCalendarEvent".equals(method)) doUpdateCalendarEvent(call);
        else if ("deleteCalendarEvent".equals(method)) doDeleteCalendarEvent(call);
        else call.reject("Unknown calendar permission continuation");
    }

    @PluginMethod
    public void scheduleNotification(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notificationPermissionCallback");
            return;
        }
        doScheduleNotification(call);
    }

    @PermissionCallback
    private void notificationPermissionCallback(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            call.reject("user_permission_required");
            return;
        }

        String method = call.getMethodName();
        if ("scheduleNotification".equals(method)) doScheduleNotification(call);
        else if ("requestNotificationPermission".equals(method)) resolvePermission(call, true);
        else if ("showNotification".equals(method)) doShowNotification(call);
        else call.reject("Unknown notification permission continuation");
    }

    @PluginMethod
    public void cancelNotification(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isEmpty()) {
            call.reject("id is required");
            return;
        }

        WorkManager.getInstance(getContext()).cancelUniqueWork(notificationWorkName(id));
        NotificationManager manager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(id.hashCode());
        JSObject response = new JSObject();
        response.put("id", id);
        response.put("cancelled", true);
        call.resolve(response);
    }

    private void resolvePermission(PluginCall call, boolean granted) {
        JSObject response = new JSObject();
        response.put("granted", granted);
        call.resolve(response);
    }

    private void doShowNotification(PluginCall call) {
        String title = call.getString("title", "Open WebUI");
        String body = call.getString("body", "");
        String id = call.getString("id", "immediate-" + System.currentTimeMillis());
        boolean sound = Boolean.TRUE.equals(call.getBoolean("sound", true));

        NotificationManager manager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            call.reject("notification_manager_unavailable");
            return;
        }
        ensureNotificationChannel(manager);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_24)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setSilent(!sound);

        manager.notify(id.hashCode(), builder.build());

        JSObject response = new JSObject();
        response.put("id", id);
        response.put("shown", true);
        call.resolve(response);
    }

    private void ensureNotificationChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationChannel existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID);
        if (existing != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Open WebUI",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Open WebUI local notifications");
        manager.createNotificationChannel(channel);
    }

    @SuppressLint("MissingPermission")
    private void doGetCurrentLocation(PluginCall call) {
        LocationManager manager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            call.reject("location_manager_unavailable");
            return;
        }

        boolean highAccuracy = Boolean.TRUE.equals(call.getBoolean("highAccuracy", false));
        long timeoutMs = Math.max(1000, Math.min(60000, call.getLong("timeoutMs", 15000L)));
        Location lastKnown = getBestLastKnownLocation(manager, highAccuracy);
        if (lastKnown != null) {
            call.resolve(locationToResponse(lastKnown));
            return;
        }

        String provider = chooseLocationProvider(manager, highAccuracy);
        if (provider == null) {
            call.reject("location_provider_unavailable");
            return;
        }

        AtomicBoolean completed = new AtomicBoolean(false);
        Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] timeout = new Runnable[1];

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (completed.compareAndSet(false, true)) {
                    manager.removeUpdates(this);
                    if (timeout[0] != null) {
                        handler.removeCallbacks(timeout[0]);
                    }
                    call.resolve(locationToResponse(location));
                }
            }
        };

        timeout[0] = () -> {
            if (completed.compareAndSet(false, true)) {
                manager.removeUpdates(listener);
                call.reject("location_timeout");
            }
        };

        handler.postDelayed(timeout[0], timeoutMs);
        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
    }

    @SuppressLint("MissingPermission")
    private Location getBestLastKnownLocation(LocationManager manager, boolean highAccuracy) {
        Location gps = null;
        Location network = null;

        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception ignored) {}

        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception ignored) {}

        if (highAccuracy && gps != null) return gps;
        if (gps == null) return network;
        if (network == null) return gps;
        return gps.getTime() >= network.getTime() ? gps : network;
    }

    private String chooseLocationProvider(LocationManager manager, boolean highAccuracy) {
        if (highAccuracy && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER;
        }
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER;
        }
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER;
        }
        return null;
    }

    private JSObject locationToResponse(Location location) {
        JSObject response = new JSObject();
        response.put("latitude", location.getLatitude());
        response.put("longitude", location.getLongitude());
        response.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : null);
        response.put("provider", location.getProvider());
        response.put("time", location.getTime());
        response.put(
            "formatted",
            String.format(Locale.US, "%.3f, %.3f (lat, long)", location.getLatitude(), location.getLongitude())
        );
        return response;
    }

    private String readTextFromUri(Uri uri) throws Exception {
        try (InputStream inputStream = getContext().getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to open document");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private JSObject getBatteryInfo() {
        BatteryManager batteryManager = (BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
        JSObject battery = new JSObject();
        battery.put("level", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        return battery;
    }

    private JSObject getNetworkInfo() {
        JSObject network = new JSObject();
        ConnectivityManager manager = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || Build.VERSION.SDK_INT < 23) {
            network.put("connected", false);
            return network;
        }

        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        network.put("connected", capabilities != null);
        network.put("wifi", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        network.put("cellular", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        network.put("vpn", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        return network;
    }

    private JSObject describeUri(Uri uri) {
        JSObject item = new JSObject();
        item.put("uri", uri.toString());
        item.put("mimeType", getContext().getContentResolver().getType(uri));

        try (Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) item.put("name", cursor.getString(nameIdx));
                if (sizeIdx >= 0) item.put("size", cursor.getLong(sizeIdx));
            }
        }

        return item;
    }

    private void doListCalendarEvents(PluginCall call) {
        long now = System.currentTimeMillis();
        long startMs = call.getLong("startMs", now - TimeUnit.DAYS.toMillis(7));
        long endMs = call.getLong("endMs", now + TimeUnit.DAYS.toMillis(30));
        int limit = Math.max(1, Math.min(200, call.getInt("limit", 50)));
        JSONArray events = new JSONArray();

        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, startMs);
        ContentUris.appendId(builder, endMs);
        String[] projection = {
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        };

        try (Cursor cursor = getContext()
            .getContentResolver()
            .query(builder.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            int count = 0;
            while (cursor != null && cursor.moveToNext() && count < limit) {
                JSObject event = new JSObject();
                event.put("id", cursor.getString(0));
                event.put("title", cursor.getString(1));
                event.put("description", cursor.getString(2));
                event.put("location", cursor.getString(3));
                event.put("startMs", cursor.getLong(4));
                event.put("endMs", cursor.getLong(5));
                event.put("allDay", cursor.getInt(6) == 1);
                events.put(event);
                count++;
            }
        }

        JSObject response = new JSObject();
        response.put("events", events);
        call.resolve(response);
    }

    private void doCreateCalendarEvent(PluginCall call) {
        Long calendarId = findWritableCalendarId();
        if (calendarId == null) {
            call.reject("No writable calendar was found");
            return;
        }

        String title = call.getString("title");
        Long startMs = call.getLong("startMs");
        Long endMs = call.getLong("endMs");
        if (title == null || title.isEmpty() || startMs == null || endMs == null) {
            call.reject("title, startMs, and endMs are required");
            return;
        }

        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, title);
        values.put(CalendarContract.Events.DESCRIPTION, call.getString("description", ""));
        values.put(CalendarContract.Events.EVENT_LOCATION, call.getString("location", ""));
        values.put(CalendarContract.Events.DTSTART, startMs);
        values.put(CalendarContract.Events.DTEND, endMs);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        values.put(CalendarContract.Events.ALL_DAY, Boolean.TRUE.equals(call.getBoolean("allDay", false)) ? 1 : 0);

        Uri uri = getContext().getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
        if (uri == null) {
            call.reject("Failed to create calendar event");
            return;
        }

        JSObject response = new JSObject();
        response.put("id", uri.getLastPathSegment());
        response.put("uri", uri.toString());
        call.resolve(response);
    }

    private void doUpdateCalendarEvent(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isEmpty()) {
            call.reject("id is required");
            return;
        }

        ContentValues values = new ContentValues();
        putIfPresent(values, CalendarContract.Events.TITLE, call.getString("title"));
        putIfPresent(values, CalendarContract.Events.DESCRIPTION, call.getString("description"));
        putIfPresent(values, CalendarContract.Events.EVENT_LOCATION, call.getString("location"));
        putIfPresent(values, CalendarContract.Events.DTSTART, call.getLong("startMs"));
        putIfPresent(values, CalendarContract.Events.DTEND, call.getLong("endMs"));
        if (call.hasOption("allDay")) {
            values.put(CalendarContract.Events.ALL_DAY, Boolean.TRUE.equals(call.getBoolean("allDay", false)) ? 1 : 0);
        }

        int updated = getContext()
            .getContentResolver()
            .update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, Long.parseLong(id)), values, null, null);
        JSObject response = new JSObject();
        response.put("id", id);
        response.put("updated", updated);
        call.resolve(response);
    }

    private void doDeleteCalendarEvent(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isEmpty()) {
            call.reject("id is required");
            return;
        }

        int deleted = getContext()
            .getContentResolver()
            .delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, Long.parseLong(id)), null, null);
        JSObject response = new JSObject();
        response.put("id", id);
        response.put("deleted", deleted);
        call.resolve(response);
    }

    private Long findWritableCalendarId() {
        String[] projection = { CalendarContract.Calendars._ID };
        String selection =
            CalendarContract.Calendars.VISIBLE +
            " = 1 AND " +
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL +
            " >= ?";
        String[] args = { String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) };

        try (Cursor cursor = getContext()
            .getContentResolver()
            .query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return null;
    }

    private void doScheduleNotification(PluginCall call) {
        String id = call.getString("id");
        String title = call.getString("title");
        String body = call.getString("body");
        long triggerAtMs = call.getLong("triggerAtMs", System.currentTimeMillis());
        if (id == null || id.isEmpty() || title == null || title.isEmpty() || body == null) {
            call.reject("id, title, and body are required");
            return;
        }

        long delayMs = Math.max(0, triggerAtMs - System.currentTimeMillis());
        Data data = new Data.Builder().putString("id", id).putString("title", title).putString("body", body).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(LocalNotificationWorker.class)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build();
        WorkManager.getInstance(getContext()).enqueueUniqueWork(notificationWorkName(id), ExistingWorkPolicy.REPLACE, request);

        JSObject response = new JSObject();
        response.put("id", id);
        response.put("scheduled", true);
        response.put("triggerAtMs", triggerAtMs);
        call.resolve(response);
    }

    private String notificationWorkName(String id) {
        return "local-notification-" + id;
    }

    private void putIfPresent(ContentValues values, String key, String value) {
        if (value != null) values.put(key, value);
    }

    private void putIfPresent(ContentValues values, String key, Long value) {
        if (value != null) values.put(key, value);
    }

    private static class RecordingSession {
        final MediaRecorder recorder;
        final File file;

        RecordingSession(MediaRecorder recorder, File file) {
            this.recorder = recorder;
            this.file = file;
        }
    }
}
