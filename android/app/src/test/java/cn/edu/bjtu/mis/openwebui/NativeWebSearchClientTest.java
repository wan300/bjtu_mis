package cn.edu.bjtu.mis.openwebui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class NativeWebSearchClientTest {
    private static final String BING_HTML =
            "<html><body><ol id=\"b_results\">" +
            "<li class=\"b_algo\">" +
            "<div class=\"b_algoheader\"><a href=\"https://example.com/a\"><h2>Example A</h2></a></div>" +
            "<div class=\"b_caption\"><p>Snippet A about the result.</p></div>" +
            "</li>" +
            "<li class=\"b_algo\">" +
            "<div class=\"b_algoheader\"><a href=\"https://example.com/a\"><h2>Duplicate A</h2></a></div>" +
            "</li>" +
            "<li class=\"b_algo\">" +
            "<h2><a href=\"https://www.bing.com/search?q=test\">Images</a></h2>" +
            "</li>" +
            "<li class=\"b_algo\">" +
            "<h2><a href=\"/ck/a?u=a1aHR0cHM6Ly9leGFtcGxlLmNvbS9i\">Example B</a></h2>" +
            "<div class=\"b_caption\"><p>Snippet B about the result.</p></div>" +
            "</li>" +
            "</ol></body></html>";

    private static final String BING_CN_HTML =
            "<html><body><ol id=\"b_results\">" +
            "<li class=\"b_algo\">" +
            "<div class=\"b_tpcn\"><a class=\"tilk\" href=\"https://example.com/a\">example.com › docs</a></div>" +
            "<a href=\"https://example.com/a\">Example CN A</a>" +
            "<div class=\"b_caption\"><p>Snippet CN A about the result.</p></div>" +
            "</li>" +
            "</ol></body></html>";

    @Test
    public void normalizeDuckDuckGoUrl_decodesRedirectLinks() throws Exception {
        String target = "https://example.com/news?q=openwebui";
        String encoded = URLEncoder.encode(target, StandardCharsets.UTF_8.name());

        assertEquals(
                target,
                NativeWebSearchClient.normalizeDuckDuckGoUrl("https://duckduckgo.com/l/?uddg=" + encoded)
        );
    }

    @Test
    public void parseDuckDuckGoLiteResults_extractsAndDeduplicatesResults() {
        String html =
                "<html><body><table>" +
                "<tr><td><a class=\"result-link\" href=\"/l/?uddg=https%3A%2F%2Fexample.com%2Fa\">Example A</a></td></tr>" +
                "<tr><td class=\"result-snippet\">Snippet A about the result.</td></tr>" +
                "<tr><td><a class=\"result-link\" href=\"/l/?uddg=https%3A%2F%2Fexample.com%2Fa\">Duplicate A</a></td></tr>" +
                "<tr><td><a class=\"result-link\" href=\"https://example.com/b\">Example B</a></td></tr>" +
                "<tr><td>Snippet B about the result.</td></tr>" +
                "<tr><td><a href=\"/lite/?q=test\">Next Page</a></td></tr>" +
                "</table></body></html>";

        List<NativeWebSearchClient.SearchResult> results =
                NativeWebSearchClient.parseDuckDuckGoLiteResults(html, 5);

        assertEquals(2, results.size());
        assertEquals("Example A", results.get(0).title);
        assertEquals("https://example.com/a", results.get(0).url);
        assertTrue(results.get(0).snippet.contains("Snippet A"));
        assertEquals("https://example.com/b", results.get(1).url);
        assertFalse(results.get(1).title.equals("Next Page"));
    }

    @Test
    public void normalizeDuckDuckGoUrl_rejectsInternalNavigationLinks() {
        assertEquals("", NativeWebSearchClient.normalizeDuckDuckGoUrl("https://duckduckgo.com/html/?q=test"));
    }

    @Test
    public void normalizeBingUrl_decodesRedirectLinks() {
        assertEquals(
                "https://example.com/b",
                NativeWebSearchClient.normalizeBingUrl("https://www.bing.com/ck/a?u=a1aHR0cHM6Ly9leGFtcGxlLmNvbS9i")
        );
    }

    @Test
    public void normalizeBingUrl_rejectsInternalNavigationLinks() {
        assertEquals("", NativeWebSearchClient.normalizeBingUrl("https://www.bing.com/search?q=test"));
    }

    @Test
    public void parseBingHtmlResults_extractsAndDeduplicatesResults() {
        List<NativeWebSearchClient.SearchResult> results =
                NativeWebSearchClient.parseBingHtmlResults(BING_HTML, 5);

        assertEquals(2, results.size());
        assertEquals("Example A", results.get(0).title);
        assertEquals("https://example.com/a", results.get(0).url);
        assertTrue(results.get(0).snippet.contains("Snippet A"));
        assertEquals("Example B", results.get(1).title);
        assertEquals("https://example.com/b", results.get(1).url);
    }

    @Test
    public void parseBingHtmlResults_extractsCnBingTitleLinks() {
        List<NativeWebSearchClient.SearchResult> results =
                NativeWebSearchClient.parseBingHtmlResults(BING_CN_HTML, 5);

        assertEquals(1, results.size());
        assertEquals("Example CN A", results.get(0).title);
        assertEquals("https://example.com/a", results.get(0).url);
        assertTrue(results.get(0).snippet.contains("Snippet CN A"));
    }

    @Test
    public void searchAuto_fallsBackToBingWhenDuckDuckGoReturnsNoResults() throws Exception {
        List<String> requestedUrls = new ArrayList<>();
        NativeWebSearchClient client = new NativeWebSearchClient((url, timeoutMs, connectionTracker) -> {
            requestedUrls.add(url);
            if (url.contains("duckduckgo.com")) {
                return "<html><body>No results</body></html>";
            }
            return BING_HTML;
        });

        List<NativeWebSearchClient.SearchResult> results = client.search(
                "auto",
                "open webui",
                5,
                0,
                12000,
                15000,
                connection -> {},
                () -> false
        );

        assertEquals(3, requestedUrls.size());
        assertTrue(requestedUrls.get(0).contains("lite.duckduckgo.com"));
        assertTrue(requestedUrls.get(1).contains("html.duckduckgo.com"));
        assertTrue(requestedUrls.get(2).contains("bing.com"));
        assertEquals("https://example.com/a", results.get(0).url);
    }

    @Test
    public void searchAuto_triesDuckDuckGoHtmlBeforeBingWhenLiteReturnsNoResults() throws Exception {
        List<String> requestedUrls = new ArrayList<>();
        NativeWebSearchClient client = new NativeWebSearchClient((url, timeoutMs, connectionTracker) -> {
            requestedUrls.add(url);
            if (url.contains("lite.duckduckgo.com")) {
                return "<html><body>No results</body></html>";
            }
            if (url.contains("html.duckduckgo.com")) {
                return "<html><body><a class=\"result__a\" href=\"/l/?uddg=https%3A%2F%2Fexample.com%2Fddg\">DDG HTML A</a></body></html>";
            }
            return BING_HTML;
        });

        List<NativeWebSearchClient.SearchResult> results = client.search(
                "auto",
                "open webui",
                5,
                0,
                12000,
                15000,
                connection -> {},
                () -> false
        );

        assertEquals(2, requestedUrls.size());
        assertTrue(requestedUrls.get(0).contains("lite.duckduckgo.com"));
        assertTrue(requestedUrls.get(1).contains("html.duckduckgo.com"));
        assertEquals("https://example.com/ddg", results.get(0).url);
    }

    @Test
    public void searchAuto_fallsBackToBingWhenDuckDuckGoFails() throws Exception {
        List<String> requestedUrls = new ArrayList<>();
        NativeWebSearchClient client = new NativeWebSearchClient((url, timeoutMs, connectionTracker) -> {
            requestedUrls.add(url);
            if (url.contains("lite.duckduckgo.com")) {
                throw new IOException("failed to connect to lite.duckduckgo.com");
            }
            return BING_HTML;
        });

        List<NativeWebSearchClient.SearchResult> results = client.search(
                "auto",
                "open webui",
                5,
                0,
                12000,
                15000,
                connection -> {},
                () -> false
        );

        assertEquals(2, requestedUrls.size());
        assertTrue(requestedUrls.get(0).contains("lite.duckduckgo.com"));
        assertTrue(requestedUrls.get(1).contains("bing.com"));
        assertEquals("https://example.com/a", results.get(0).url);
    }

    @Test
    public void searchDuckDuckGoLite_doesNotFallbackWhenDuckDuckGoReturnsNoResults() throws Exception {
        List<String> requestedUrls = new ArrayList<>();
        NativeWebSearchClient client = new NativeWebSearchClient((url, timeoutMs, connectionTracker) -> {
            requestedUrls.add(url);
            return "<html><body>No results</body></html>";
        });

        List<NativeWebSearchClient.SearchResult> results = client.search(
                "duckduckgo_lite",
                "open webui",
                5,
                0,
                12000,
                15000,
                connection -> {},
                () -> false
        );

        assertEquals(1, requestedUrls.size());
        assertTrue(requestedUrls.get(0).contains("lite.duckduckgo.com"));
        assertTrue(results.isEmpty());
    }

    @Test
    public void searchBingHtml_onlyUsesBing() throws Exception {
        List<String> requestedUrls = new ArrayList<>();
        NativeWebSearchClient client = new NativeWebSearchClient((url, timeoutMs, connectionTracker) -> {
            requestedUrls.add(url);
            return BING_HTML;
        });

        List<NativeWebSearchClient.SearchResult> results = client.search(
                "bing_html",
                "open webui",
                5,
                0,
                12000,
                15000,
                connection -> {},
                () -> false
        );

        assertEquals(1, requestedUrls.size());
        assertTrue(requestedUrls.get(0).contains("bing.com"));
        assertEquals("https://example.com/a", results.get(0).url);
    }
}
