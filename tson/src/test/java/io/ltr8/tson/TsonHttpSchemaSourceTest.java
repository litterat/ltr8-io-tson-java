package io.ltr8.tson;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaFetchException.Reason;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driven against a real HTTP server on an ephemeral port rather than a stubbed client, because most of what is
 * being tested is behaviour of the transport -- a redirect, a slow response, an oversized body -- that a stub
 * would only assert about itself.
 */
class TsonHttpSchemaSourceTest {

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    /** The identity host schemas are named by -- never where they are fetched from. §2.2.1 forbids a port here. */
    private static final String HOST = "schemas.example.com";

    private HttpServer server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Serves {@code body} at {@code path} with {@code status}, counting every request that reaches it. */
    private void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/tson");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    /** The reference a document would write: identity, not location. */
    private static String reference(String path) {
        return "https://" + HOST + path;
    }

    private static String schemaAt(String path) {
        return SCHEMA.formatted(reference(path));
    }

    /** Names are on {@link #HOST}; the bytes come from the test server. That split is the point of mapHost. */
    private TsonHttpSchemaSource.Builder allowingThisServer() {
        return TsonHttpSchemaSource.builder().mapHost(HOST, base).timeout(Duration.ofSeconds(2));
    }

    @Test
    void fetchesAPermittedOrigin() {
        serve("/order-1.tn", 200, schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = allowingThisServer().build()) {
            assertTrue(source.fetch(reference("/order-1.tn")).contains("order =>"));
        }
    }

    /** Deny by default: a source with no allowed origin is exactly as inert as TsonSchemaSource.registeredOnly(). */
    @Test
    void fetchesNothingUntilAnOriginIsAllowed() {
        serve("/order-1.tn", 200, schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = TsonHttpSchemaSource.builder().build()) {
            assertEquals(Reason.NOT_PERMITTED, refusal(source, reference("/order-1.tn")).reason());
            assertEquals(0, requests.get(), "policy must refuse before opening a connection");
        }
    }

    /**
     * The control is an exact host, not a suffix. A suffix test written as ".example.com" also matches
     * "evil-example.com", which is the usual way this is defeated.
     */
    @Test
    void aHostIsMatchedExactlyNotBySuffix() {
        try (TsonHttpSchemaSource source = TsonHttpSchemaSource.builder().allowHost(HOST).build()) {
            assertEquals(Reason.NOT_PERMITTED, refusal(source, "https://evil-schemas.example.com/x.tn").reason());
            assertEquals(Reason.NOT_PERMITTED, refusal(source, "https://sub.schemas.example.com/x.tn").reason());
            assertEquals(Reason.NOT_PERMITTED, refusal(source, "https://example.com/x.tn").reason());
        }
    }

    /**
     * The scheme is a transport hint, not part of the name (§2.2.1), so a reference written http:// names the
     * same document as one written https:// -- and both are fetched from wherever policy says.
     */
    @Test
    void theSchemeIsNotPartOfTheIdentity() {
        serve("/order-1.tn", 200, schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = allowingThisServer().build()) {
            source.fetch("https://" + HOST + "/order-1.tn");
            source.fetch("http://" + HOST + "/order-1.tn");
            assertEquals(1, requests.get(), "one identity however the scheme is written");
        }
    }

    /** §2.2.1: an identifying URI carries no port, no userinfo and no fragment. Refused with a message that says so. */
    @Test
    void refusesAReferenceThatIsNotALegalIdentity() {
        try (TsonHttpSchemaSource source = TsonHttpSchemaSource.builder().allowHost(HOST).build()) {
            // The origin of https://allowed@evil/ is evil -- a reader, and some parsers, get this wrong.
            TsonSchemaFetchException userinfo = refusal(source, "https://" + HOST + "@evil.example.com/x.tn");
            assertEquals(Reason.NOT_PERMITTED, userinfo.reason());
            assertTrue(userinfo.getMessage().contains("userinfo"), userinfo.getMessage());

            TsonSchemaFetchException port = refusal(source, "https://" + HOST + ":8443/x.tn");
            assertEquals(Reason.NOT_PERMITTED, port.reason());
            assertTrue(port.getMessage().contains("port"), port.getMessage());

            TsonSchemaFetchException fragment = refusal(source, "https://" + HOST + "/x.tn#frag");
            assertEquals(Reason.NOT_PERMITTED, fragment.reason());
            assertTrue(fragment.getMessage().contains("fragment"), fragment.getMessage());
        }
    }

    /** A redirect is the allow-list's exit door: the check happened on the first URI, the content comes from the second. */
    @Test
    void refusesToFollowARedirect() {
        server.createContext("/moved.tn", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "https://evil.example.com/x.tn");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try (TsonHttpSchemaSource source = allowingThisServer().build()) {
            TsonSchemaFetchException refused = refusal(source, reference("/moved.tn"));
            assertEquals(Reason.TRANSPORT, refused.reason());
            assertTrue(refused.getMessage().contains("redirect"), refused.getMessage());
        }
    }

    @Test
    void reportsAMissingSchemaAsNotFound() {
        serve("/gone.tn", 404, "nope");
        try (TsonHttpSchemaSource source = allowingThisServer().build()) {
            assertEquals(Reason.NOT_FOUND, refusal(source, reference("/gone.tn")).reason());
        }
    }

    @Test
    void reportsAFailingOriginAsTransport() {
        serve("/broken.tn", 500, "boom");
        try (TsonHttpSchemaSource source = allowingThisServer().build()) {
            assertEquals(Reason.TRANSPORT, refusal(source, reference("/broken.tn")).reason());
        }
    }

    /** The cap is on bytes delivered, never on Content-Length, which the origin also controls. */
    @Test
    void refusesADocumentLargerThanTheCap() {
        serve("/big.tn", 200, "x".repeat(4096));
        try (TsonHttpSchemaSource source = allowingThisServer().maxDocumentBytes(1024).build()) {
            assertEquals(Reason.TOO_LARGE, refusal(source, reference("/big.tn")).reason());
        }
    }

    @Test
    void givesUpOnASlowOrigin() {
        server.createContext("/slow.tn", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(Duration.ofSeconds(3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try (TsonHttpSchemaSource source = allowingThisServer().timeout(Duration.ofMillis(300)).build()) {
            assertEquals(Reason.TIMEOUT, refusal(source, reference("/slow.tn")).reason());
        }
    }

    /** Only content the operator has already hashed is accepted -- the loader then verifies the pin it required. */
    @Test
    void canRequireAContentHashPin() {
        serve("/order-1.tn", 200, schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = allowingThisServer().requireContentHashPin(true).build()) {
            TsonSchemaFetchException refused = refusal(source, reference("/order-1.tn"));
            assertEquals(Reason.NOT_PERMITTED, refused.reason());
            assertTrue(refused.getMessage().contains("sha256"), refused.getMessage());
            assertEquals(0, requests.get(), "policy must refuse before opening a connection");
        }
    }

    /**
     * Cached by canonical identity, so a varied query string cannot force repeated outbound fetches -- and a
     * pinned and an unpinned reference to one identity share the entry, which stays sound because the loader
     * verifies each reference's own pin against whatever is returned.
     */
    @Test
    void cachesByIdentitySoAQueryStringCannotForceRefetches() {
        serve("/order-1.tn", 200, schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = allowingThisServer().build()) {
            source.fetch(reference("/order-1.tn"));
            source.fetch(reference("/order-1.tn"));
            source.fetch(reference("/order-1.tn") + "?sha256=abc123");
            assertEquals(1, requests.get(), "one identity, one fetch");
            assertTrue(source.isCached(reference("/order-1.tn")));
        }
    }

    @Test
    void aFullCacheStopsCachingRatherThanFailing() {
        serve("/order-1.tn", 200, schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = allowingThisServer().maxCachedSchemas(0).build()) {
            source.fetch(reference("/order-1.tn"));
            source.fetch(reference("/order-1.tn"));
            assertEquals(2, requests.get());
            assertFalse(source.isCached(reference("/order-1.tn")));
        }
    }

    /** preload is the intended startup path: fail on a misconfigured deployment rather than on its first request. */
    @Test
    void preloadFetchesEagerlyAndFailsLoudly() {
        serve("/order-1.tn", 200, schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = allowingThisServer().build()) {
            source.preload(reference("/order-1.tn"));
            assertTrue(source.isCached(reference("/order-1.tn")));
            assertThrows(TsonSchemaFetchException.class, () -> source.preload(reference("/missing.tn")));
        }
    }

    /**
     * The whole point, end to end: a document naming a schema over HTTP resolves, validates, and reads --
     * with resolution done at startup, on this thread, which is what the threading note requires.
     */
    @Test
    void aDocumentNamingAnHttpSchemaResolvesAndValidates() {
        String schemaUri = reference("/order-1.tn");
        serve("/order-1.tn", 200, schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = allowingThisServer().build()) {
            Tson tson = Tson.builder().schemaSource(source).build();
            tson.resolve(source.fetch(schemaUri));

            TsonValue order = tson.treeReader().read("""
                    !!schema:"%s"
                    !order { sku: "ABC-1"  quantity: 3 }""".formatted(schemaUri));
            assertEquals("ABC-1", order.get("sku").asString().orElseThrow());

            assertEquals(2, tson.validate("""
                    !!schema:"%s"
                    !order { }""".formatted(schemaUri)).size(), "both required fields are missing");
        }
    }

    private static TsonSchemaFetchException refusal(TsonHttpSchemaSource source, String uri) {
        return assertThrows(TsonSchemaFetchException.class, () -> source.fetch(uri));
    }

    private static InputStream body(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }
}
