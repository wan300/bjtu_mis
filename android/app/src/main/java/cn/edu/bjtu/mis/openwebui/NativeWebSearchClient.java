package cn.edu.bjtu.mis.openwebui;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class NativeWebSearchClient {
    private static final String DUCKDUCKGO_LITE_URL = "https://lite.duckduckgo.com/lite/?q=";
    private static final String DUCKDUCKGO_HTML_URL = "https://html.duckduckgo.com/html/?q=";
    private static final String BING_HTML_URL = "https://www.bing.com/search?q=";
    private static final String ENGINE_AUTO = "auto";
    private static final String ENGINE_DUCKDUCKGO_LITE = "duckduckgo_lite";
    private static final String ENGINE_BING_HTML = "bing_html";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Open WebUI) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";
    private static final int MAX_SEARCH_HTML_CHARS = 1_000_000;

    private final NativeSearchHtmlFetcher searchHtmlFetcher;

    public NativeWebSearchClient() {
        this(null);
    }

    NativeWebSearchClient(NativeSearchHtmlFetcher searchHtmlFetcher) {
        this.searchHtmlFetcher = searchHtmlFetcher != null ? searchHtmlFetcher : this::fetchSearchHtml;
    }

    public static class SearchResult {
        public String title;
        public String url;
        public String snippet;
        public String content;
        public boolean fetched;
        public String error;

        SearchResult(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
            this.content = "";
            this.fetched = false;
            this.error = "";
        }
    }

    public static class FetchedPage {
        public String url;
        public String title;
        public String content;
        public boolean fetched;
        public String error;

        FetchedPage(String url, String title, String content, boolean fetched, String error) {
            this.url = url;
            this.title = title;
            this.content = content;
            this.fetched = fetched;
            this.error = error;
        }
    }

    public List<SearchResult> searchDuckDuckGoLite(
            String query,
            int count,
            int fetchPageCount,
            int maxPageChars,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker,
            BooleanSupplier aborted
    ) throws IOException {
        return search(
                ENGINE_DUCKDUCKGO_LITE,
                query,
                count,
                fetchPageCount,
                maxPageChars,
                timeoutMs,
                connectionTracker,
                aborted
        );
    }

    public List<SearchResult> search(
            String engine,
            String query,
            int count,
            int fetchPageCount,
            int maxPageChars,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker,
            BooleanSupplier aborted
    ) throws IOException {
        String normalizedEngine = normalizeSearchEngine(engine);
        IOException firstError = null;

        if (!ENGINE_BING_HTML.equals(normalizedEngine)) {
            try {
                List<SearchResult> results = searchDuckDuckGoLiteResults(query, count, timeoutMs, connectionTracker);
                if (!results.isEmpty() || ENGINE_DUCKDUCKGO_LITE.equals(normalizedEngine)) {
                    return fetchResultPages(results, fetchPageCount, maxPageChars, timeoutMs, connectionTracker, aborted);
                }
                results = searchDuckDuckGoHtmlResults(query, count, timeoutMs, connectionTracker);
                if (!results.isEmpty()) {
                    return fetchResultPages(results, fetchPageCount, maxPageChars, timeoutMs, connectionTracker, aborted);
                }
            } catch (IOException error) {
                if (ENGINE_DUCKDUCKGO_LITE.equals(normalizedEngine)) {
                    throw error;
                }
                firstError = error;
            }
        }

        try {
            List<SearchResult> results = searchBingHtmlResults(query, count, timeoutMs, connectionTracker);
            return fetchResultPages(results, fetchPageCount, maxPageChars, timeoutMs, connectionTracker, aborted);
        } catch (IOException error) {
            if (firstError != null) {
                error.addSuppressed(firstError);
            }
            throw error;
        }
    }

    public FetchedPage fetchPage(
            String url,
            int maxChars,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker
    ) throws IOException {
        HttpURLConnection connection = openConnection(url, timeoutMs, connectionTracker);

        try {
            String html = readConnection(connection, Math.max(maxChars * 4, 200_000));
            Document document = Jsoup.parse(html, url);
            document.select("script, style, noscript, svg, canvas, nav, header, footer, aside, form").remove();

            String title = cleanText(document.title());
            String text = document.body() != null ? document.body().text() : document.text();
            return new FetchedPage(url, title, truncate(cleanText(text), maxChars), true, "");
        } finally {
            connection.disconnect();
        }
    }

    public static List<SearchResult> parseDuckDuckGoLiteResults(String html, int count) {
        Document document = Jsoup.parse(html, "https://lite.duckduckgo.com/");
        Elements anchors = document.select("a.result-link, a.result__a, a[href]");
        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        for (Element anchor : anchors) {
            if (results.size() >= count) {
                break;
            }

            String title = cleanText(anchor.text());
            if (title.isEmpty() || isNavigationLink(title)) {
                continue;
            }

            String normalizedUrl = normalizeDuckDuckGoUrl(anchor.attr("abs:href"));
            if (normalizedUrl.isEmpty() || !seenUrls.add(normalizedUrl)) {
                continue;
            }

            results.add(new SearchResult(title, normalizedUrl, findSnippet(anchor)));
        }

        return results;
    }

    public static List<SearchResult> parseBingHtmlResults(String html, int count) {
        Document document = Jsoup.parse(html, "https://www.bing.com/");
        Elements items = document.select("li.b_algo");
        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        for (Element item : items) {
            if (results.size() >= count) {
                break;
            }

            Element anchor = findBingResultAnchor(item);
            if (anchor == null) {
                continue;
            }

            String title = cleanText(anchor.text());
            if (title.isEmpty() || isNavigationLink(title)) {
                continue;
            }

            String normalizedUrl = normalizeBingUrl(anchor.attr("abs:href"));
            if (normalizedUrl.isEmpty() || !seenUrls.add(normalizedUrl)) {
                continue;
            }

            Element snippetElement = item.selectFirst(".b_caption p, .b_snippet, p");
            String snippet = snippetElement != null ? cleanText(snippetElement.text()) : "";
            results.add(new SearchResult(title, normalizedUrl, snippet));
        }

        return results;
    }

    public static String normalizeDuckDuckGoUrl(String href) {
        if (href == null || href.trim().isEmpty()) {
            return "";
        }

        String value = href.trim();
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (value.startsWith("/")) {
            value = "https://duckduckgo.com" + value;
        }

        try {
            URL url = new URL(value);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith("duckduckgo.com")) {
                String uddg = getQueryParameter(url.getQuery(), "uddg");
                if (uddg != null && !uddg.isEmpty()) {
                    return uddg;
                }
                return "";
            }

            return normalizeExternalHttpUrl(url.toString());
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String normalizeBingUrl(String href) {
        if (href == null || href.trim().isEmpty()) {
            return "";
        }

        String value = href.trim();
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (value.startsWith("/")) {
            value = "https://www.bing.com" + value;
        }

        try {
            URL url = new URL(value);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith("bing.com")) {
                return normalizeExternalHttpUrl(decodeBingRedirectUrl(getQueryParameter(url.getQuery(), "u")));
            }

            return normalizeExternalHttpUrl(url.toString());
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<SearchResult> searchDuckDuckGoLiteResults(
            String query,
            int count,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker
    ) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String html = searchHtmlFetcher.fetch(DUCKDUCKGO_LITE_URL + encodedQuery, timeoutMs, connectionTracker);
        return parseDuckDuckGoLiteResults(html, count);
    }

    private List<SearchResult> searchDuckDuckGoHtmlResults(
            String query,
            int count,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker
    ) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String html = searchHtmlFetcher.fetch(DUCKDUCKGO_HTML_URL + encodedQuery, timeoutMs, connectionTracker);
        return parseDuckDuckGoLiteResults(html, count);
    }

    private List<SearchResult> searchBingHtmlResults(
            String query,
            int count,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker
    ) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String html = searchHtmlFetcher.fetch(BING_HTML_URL + encodedQuery, timeoutMs, connectionTracker);
        return parseBingHtmlResults(html, count);
    }

    private List<SearchResult> fetchResultPages(
            List<SearchResult> results,
            int fetchPageCount,
            int maxPageChars,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker,
            BooleanSupplier aborted
    ) throws IOException {
        int pagesToFetch = Math.min(Math.max(fetchPageCount, 0), results.size());

        for (int index = 0; index < pagesToFetch; index++) {
            if (aborted.getAsBoolean()) {
                throw new IOException("Native web search was aborted.");
            }

            SearchResult result = results.get(index);
            try {
                FetchedPage page = fetchPage(result.url, maxPageChars, timeoutMs, connectionTracker);
                result.content = page.content;
                result.fetched = page.fetched;
                if (result.title == null || result.title.isEmpty()) {
                    result.title = page.title;
                }
                result.error = page.error;
            } catch (Exception error) {
                result.fetched = false;
                result.error = error.getMessage() != null ? error.getMessage() : "Failed to fetch page.";
            }
        }

        return results;
    }

    private String fetchSearchHtml(
            String url,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker
    ) throws IOException {
        HttpURLConnection connection = openConnection(url, timeoutMs, connectionTracker);

        try {
            return readConnection(connection, MAX_SEARCH_HTML_CHARS);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(
            String url,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker
    ) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connectionTracker.accept(connection);
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return connection;
    }

    private static String readConnection(HttpURLConnection connection, int maxChars) throws IOException {
        int status = connection.getResponseCode();
        InputStream inputStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = inputStream != null ? readStream(inputStream, maxChars) : "";

        if (status >= 400) {
            throw new IOException("HTTP " + status + (text.isEmpty() ? "" : ": " + truncate(cleanText(text), 240)));
        }

        return text;
    }

    private static String readStream(InputStream inputStream, int maxChars) throws IOException {
        StringBuilder builder = new StringBuilder(Math.min(maxChars, 8192));
        char[] buffer = new char[4096];

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            int read;
            while ((read = reader.read(buffer)) != -1 && builder.length() < maxChars) {
                int remaining = maxChars - builder.length();
                builder.append(buffer, 0, Math.min(read, remaining));
            }
        }

        return builder.toString();
    }

    private static Element findBingResultAnchor(Element item) {
        Element direct = item.selectFirst("div.b_algoheader a[href], h2 a[href], .b_title a[href]");
        if (isUsableBingResultAnchor(direct)) {
            return direct;
        }

        for (Element candidate : item.select("a[href]")) {
            if (candidate.hasClass("tilk") || candidate.hasClass("b_poleContent")) {
                continue;
            }
            if (isUsableBingResultAnchor(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private static boolean isUsableBingResultAnchor(Element anchor) {
        if (anchor == null) {
            return false;
        }

        String title = cleanText(anchor.text());
        if (title.isEmpty() || isNavigationLink(title)) {
            return false;
        }

        return !normalizeBingUrl(anchor.attr("abs:href")).isEmpty();
    }

    private static String findSnippet(Element anchor) {
        Element row = anchor.closest("tr");
        if (row != null) {
            Element sibling = row.nextElementSibling();
            int checked = 0;
            while (sibling != null && checked < 4) {
                String text = cleanText(sibling.text());
                if (!text.isEmpty() && !isNavigationLink(text)) {
                    return text;
                }
                sibling = sibling.nextElementSibling();
                checked++;
            }
        }

        Element parent = anchor.parent();
        if (parent != null) {
            String text = cleanText(parent.parent() != null ? parent.parent().text() : parent.text());
            String title = cleanText(anchor.text());
            if (text.startsWith(title)) {
                text = text.substring(title.length()).trim();
            }
            return text;
        }

        return "";
    }

    private static String getQueryParameter(String query, String name) {
        if (query == null || query.isEmpty()) {
            return null;
        }

        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            if (name.equals(key)) {
                String value = separator >= 0 ? pair.substring(separator + 1) : "";
                try {
                    return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                    return value;
                }
            }
        }

        return null;
    }

    private static String normalizeSearchEngine(String engine) {
        String normalized = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
        if (ENGINE_DUCKDUCKGO_LITE.equals(normalized) || ENGINE_BING_HTML.equals(normalized)) {
            return normalized;
        }
        return ENGINE_AUTO;
    }

    private static String normalizeExternalHttpUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }

        try {
            URL url = new URL(value.trim());
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                return "";
            }
            return url.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String decodeBingRedirectUrl(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }

        String encoded = value.startsWith("a1") ? value.substring(2) : value;
        return decodeBase64UrlSafe(encoded);
    }

    private static String decodeBase64UrlSafe(String encoded) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max((encoded.length() * 3) / 4, 0));
        int buffer = 0;
        int bits = 0;

        for (int index = 0; index < encoded.length(); index++) {
            char character = encoded.charAt(index);
            if (character == '=') {
                break;
            }
            if (Character.isWhitespace(character)) {
                continue;
            }

            int value = base64Value(character);
            if (value < 0) {
                return "";
            }

            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                output.write((buffer >> bits) & 0xff);
            }
        }

        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int base64Value(char character) {
        if (character >= 'A' && character <= 'Z') {
            return character - 'A';
        }
        if (character >= 'a' && character <= 'z') {
            return character - 'a' + 26;
        }
        if (character >= '0' && character <= '9') {
            return character - '0' + 52;
        }
        if (character == '-' || character == '+') {
            return 62;
        }
        if (character == '_' || character == '/') {
            return 63;
        }
        return -1;
    }

    private static boolean isNavigationLink(String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        return normalized.equals("next page") ||
                normalized.equals("previous") ||
                normalized.equals("images") ||
                normalized.equals("videos") ||
                normalized.equals("news") ||
                normalized.equals("maps") ||
                normalized.startsWith("more results");
    }

    private static String cleanText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars);
    }
}

interface NativeSearchHtmlFetcher {
    String fetch(
            String url,
            int timeoutMs,
            Consumer<HttpURLConnection> connectionTracker
    ) throws IOException;
}
