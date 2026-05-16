package cn.edu.bjtu.mis.openwebui;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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

@CapacitorPlugin(name = "NativeHttp")
public class NativeHttpPlugin extends Plugin {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, HttpURLConnection> connections = new ConcurrentHashMap<>();

    @PluginMethod
    public void request(PluginCall call) {
        String requestId = call.getString("requestId");
        String url = call.getString("url");
        String method = call.getString("method", "GET");
        String body = call.getString("body", "");
        int timeoutMs = call.getInt("timeoutMs", 60000);
        JSObject headers = call.getObject("headers", new JSObject());

        if (requestId == null || requestId.isEmpty()) {
            call.reject("requestId is required");
            return;
        }
        if (url == null || url.isEmpty()) {
            call.reject("url is required");
            return;
        }

        executor.execute(() -> executeRequest(call, requestId, url, method, headers, body, timeoutMs));
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
        String body,
        int timeoutMs
    ) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connections.put(requestId, connection);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(Math.max(1000, timeoutMs));
            connection.setReadTimeout(Math.max(1000, timeoutMs));
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
            InputStream inputStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();

            JSObject response = new JSObject();
            response.put("requestId", requestId);
            response.put("status", status);
            response.put("headers", getResponseHeaders(connection));
            response.put("body", inputStream != null ? readString(inputStream) : "");
            call.resolve(response);
        } catch (Exception error) {
            call.reject(error.getMessage() != null ? error.getMessage() : "Native HTTP request failed", error);
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

    private String readString(InputStream inputStream) throws Exception {
        try (InputStream stream = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
