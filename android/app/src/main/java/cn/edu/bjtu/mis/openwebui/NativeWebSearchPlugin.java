package cn.edu.bjtu.mis.openwebui;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;

@CapacitorPlugin(name = "NativeWebSearch")
public class NativeWebSearchPlugin extends Plugin {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final NativeWebSearchClient client = new NativeWebSearchClient();
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<HttpURLConnection>> connections = new ConcurrentHashMap<>();

    @PluginMethod
    public void search(PluginCall call) {
        String requestId = call.getString("requestId");
        String query = call.getString("query");
        String engine = call.getString("engine");

        if (requestId == null || requestId.isEmpty()) {
            call.reject("requestId is required");
            return;
        }
        if (query == null || query.trim().isEmpty()) {
            call.reject("query is required");
            return;
        }

        int count = clamp(call.getInt("count", 5), 1, 10);
        int fetchPageCount = clamp(call.getInt("fetchPageCount", 3), 0, 5);
        int maxPageChars = clamp(call.getInt("maxPageChars", 12000), 1000, 50000);
        int timeoutMs = clamp(call.getInt("timeoutMs", 15000), 1000, 60000);
        String resolvedEngine = engine == null || engine.trim().isEmpty() ? "auto" : engine.trim();

        Future<?> future = executor.submit(() -> {
            try {
                List<NativeWebSearchClient.SearchResult> results = client.search(
                        resolvedEngine,
                        query.trim(),
                        count,
                        fetchPageCount,
                        maxPageChars,
                        timeoutMs,
                        connection -> trackConnection(requestId, connection),
                        () -> Thread.currentThread().isInterrupted()
                );

                JSObject response = new JSObject();
                response.put("requestId", requestId);
                response.put("results", searchResultsToJson(results));
                call.resolve(response);
            } catch (Exception error) {
                call.reject(error.getMessage() != null ? error.getMessage() : "Native web search failed", error);
            } finally {
                cleanup(requestId);
            }
        });
        futures.put(requestId, future);
    }

    @PluginMethod
    public void fetchUrl(PluginCall call) {
        String requestId = call.getString("requestId");
        String url = call.getString("url");

        if (requestId == null || requestId.isEmpty()) {
            call.reject("requestId is required");
            return;
        }
        if (url == null || url.trim().isEmpty()) {
            call.reject("url is required");
            return;
        }

        int maxPageChars = clamp(call.getInt("maxPageChars", 12000), 1000, 50000);
        int timeoutMs = clamp(call.getInt("timeoutMs", 15000), 1000, 60000);

        Future<?> future = executor.submit(() -> {
            try {
                NativeWebSearchClient.FetchedPage page = client.fetchPage(
                        url.trim(),
                        maxPageChars,
                        timeoutMs,
                        connection -> trackConnection(requestId, connection)
                );

                JSObject response = new JSObject();
                response.put("requestId", requestId);
                response.put("page", fetchedPageToJson(page));
                call.resolve(response);
            } catch (Exception error) {
                call.reject(error.getMessage() != null ? error.getMessage() : "Native web fetch failed", error);
            } finally {
                cleanup(requestId);
            }
        });
        futures.put(requestId, future);
    }

    @PluginMethod
    public void abort(PluginCall call) {
        String requestId = call.getString("requestId");

        if (requestId != null) {
            Future<?> future = futures.remove(requestId);
            if (future != null) {
                future.cancel(true);
            }

            Set<HttpURLConnection> activeConnections = connections.remove(requestId);
            if (activeConnections != null) {
                for (HttpURLConnection connection : activeConnections) {
                    connection.disconnect();
                }
            }
        }

        call.resolve();
    }

    private void trackConnection(String requestId, HttpURLConnection connection) {
        connections
                .computeIfAbsent(requestId, key -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(connection);
    }

    private void cleanup(String requestId) {
        futures.remove(requestId);
        Set<HttpURLConnection> activeConnections = connections.remove(requestId);
        if (activeConnections != null) {
            activeConnections.clear();
        }
    }

    private JSONArray searchResultsToJson(List<NativeWebSearchClient.SearchResult> results) {
        JSONArray array = new JSONArray();
        for (NativeWebSearchClient.SearchResult result : results) {
            JSObject item = new JSObject();
            item.put("title", result.title);
            item.put("url", result.url);
            item.put("snippet", result.snippet);
            item.put("content", result.content);
            item.put("fetched", result.fetched);
            if (result.error != null && !result.error.isEmpty()) {
                item.put("error", result.error);
            }
            array.put(item);
        }
        return array;
    }

    private JSObject fetchedPageToJson(NativeWebSearchClient.FetchedPage page) {
        JSObject item = new JSObject();
        item.put("url", page.url);
        item.put("title", page.title);
        item.put("content", page.content);
        item.put("fetched", page.fetched);
        if (page.error != null && !page.error.isEmpty()) {
            item.put("error", page.error);
        }
        return item;
    }

    private int clamp(Integer value, int min, int max) {
        int resolved = value != null ? value : min;
        return Math.max(min, Math.min(max, resolved));
    }
}
