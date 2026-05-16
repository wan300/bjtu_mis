package cn.edu.bjtu.mis.openwebui;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "NativeSse")
public class NativeSsePlugin extends Plugin {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, HttpURLConnection> connections = new ConcurrentHashMap<>();

    @PluginMethod
    public void request(PluginCall call) {
        String requestId = call.getString("requestId");
        String url = call.getString("url");
        String method = call.getString("method", "POST");
        String body = call.getString("body", "");
        JSObject headers = call.getObject("headers", new JSObject());

        if (requestId == null || requestId.isEmpty()) {
            call.reject("requestId is required");
            return;
        }
        if (url == null || url.isEmpty()) {
            call.reject("url is required");
            return;
        }

        executor.execute(() -> executeRequest(call, requestId, url, method, headers, body));
    }

    @PluginMethod
    public void abort(PluginCall call) {
        String requestId = call.getString("requestId");
        HttpURLConnection connection = connections.remove(requestId);

        if (connection != null) {
            connection.disconnect();
        }

        call.resolve();
    }

    private void executeRequest(
        PluginCall call,
        String requestId,
        String url,
        String method,
        JSObject headers,
        String body
    ) {
        HttpURLConnection connection = null;
        boolean resolved = false;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connections.put(requestId, connection);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(60000);
            connection.setReadTimeout(0);
            connection.setDoInput(true);

            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = headers.opt(key);
                if (value != null) {
                    connection.setRequestProperty(key, String.valueOf(value));
                }
            }

            if (body != null && !body.isEmpty()) {
                connection.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            JSObject response = new JSObject();
            response.put("requestId", requestId);
            response.put("status", status);
            response.put("headers", getResponseHeaders(connection));
            call.resolve(response);
            resolved = true;

            InputStream inputStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (inputStream != null) {
                streamBody(requestId, inputStream);
            }

            notifyDone(requestId);
        } catch (Exception error) {
            notifyError(requestId, error.getMessage() != null ? error.getMessage() : "Native SSE request failed");
            if (!resolved) {
                call.reject(error.getMessage(), error);
            }
        } finally {
            connections.remove(requestId);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSObject getResponseHeaders(HttpURLConnection connection) {
        JSObject headers = new JSObject();

        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key != null && values != null) {
                headers.put(key, join(values));
            }
        }

        return headers;
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private void streamBody(String requestId, InputStream inputStream) throws Exception {
        char[] buffer = new char[4096];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            int count;
            while ((count = reader.read(buffer)) != -1) {
                JSObject event = new JSObject();
                event.put("requestId", requestId);
                event.put("data", new String(buffer, 0, count));
                notifyListeners("nativeSseChunk", event);
            }
        }
    }

    private void notifyDone(String requestId) {
        JSObject event = new JSObject();
        event.put("requestId", requestId);
        notifyListeners("nativeSseDone", event);
    }

    private void notifyError(String requestId, String message) {
        JSObject event = new JSObject();
        event.put("requestId", requestId);
        event.put("message", message);
        notifyListeners("nativeSseError", event);
    }
}
