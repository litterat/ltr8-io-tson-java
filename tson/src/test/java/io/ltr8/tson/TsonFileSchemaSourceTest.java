package io.ltr8.tson;

import io.ltr8.tson.TsonSchemaFetchException.Reason;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonFileSchemaSource} -- the local half of the pair, whose policy question is path traversal where
 * its remote sibling's is SSRF. [TSON-DATA] §2.2.1 is what makes serving an {@code https://} identity from a
 * directory legitimate: identity names a document, location is separate, and a consumer "MAY fetch by
 * whichever scheme its policy allows".
 */
class TsonFileSchemaSourceTest {

    private static final String HOST = "schemas.example.test";

    private static String schemaAt(String path) {
        return """
                !!id:"https://%s%s"
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
                {
                  order => { sku: text  quantity: int32 }
                }
                """.formatted(HOST, path);
    }

    private static TsonFileSchemaSource serving(Path directory) {
        return TsonFileSchemaSource.builder().mapHost(HOST, directory).build();
    }

    private static String reference(String path) {
        return "https://" + HOST + path;
    }

    private static TsonSchemaFetchException refusal(TsonFileSchemaSource source, String uri) {
        return assertThrows(TsonSchemaFetchException.class, () -> source.fetch(uri));
    }

    @Test
    void readsAMappedHostFromItsDirectory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));

        assertEquals(schemaAt("/order-1.tn"), serving(dir).fetch(reference("/order-1.tn")));
    }

    /** Deny by default: a source with no mapping serves nothing, and says so rather than reading anything. */
    @Test
    void readsNothingUntilAHostIsMapped() {
        TsonFileSchemaSource source = TsonFileSchemaSource.builder().build();

        assertEquals(Reason.NOT_PERMITTED, refusal(source, reference("/order-1.tn")).reason());
    }

    /** Exact match, for the reason its sibling gives: a suffix test also matches {@code evil-example.test}. */
    @Test
    void aHostIsMatchedExactlyNotBySuffix(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));
        TsonFileSchemaSource source = serving(dir);

        assertEquals(Reason.NOT_PERMITTED, refusal(source, "https://evil-" + HOST + "/order-1.tn").reason());
        assertEquals(Reason.NOT_PERMITTED, refusal(source, "https://sub." + HOST + "/order-1.tn").reason());
    }

    /**
     * <b>The control this class exists for.</b> A {@code ..} that climbs out of the mapped directory is
     * refused, and refused on the <em>real</em> path -- so the check does not depend on the reference
     * looking suspicious.
     */
    @Test
    void aPathMayNotEscapeItsDirectory(@TempDir Path dir) throws IOException {
        Path served = Files.createDirectory(dir.resolve("served"));
        Files.writeString(served.resolve("order-1.tn"), schemaAt("/order-1.tn"));
        Files.writeString(dir.resolve("secret.tn"), "!!id:\"https://elsewhere.test/secret.tn\"\n{}\n");

        TsonSchemaFetchException refused = refusal(serving(served), reference("/../secret.tn"));
        assertEquals(Reason.NOT_PERMITTED, refused.reason());
        assertTrue(refused.getMessage().contains("outside"), refused.getMessage());
    }

    /**
     * <b>And the reason containment is checked after {@link Path#toRealPath}.</b> A symlink inside the
     * directory pointing out of it looks entirely ordinary until the path is made real -- checking the
     * unresolved path is the usual way this control is defeated.
     */
    @Test
    void aSymlinkMayNotEscapeItsDirectoryEither(@TempDir Path dir) throws IOException {
        Path served = Files.createDirectory(dir.resolve("served"));
        Path secret = Files.writeString(dir.resolve("secret.tn"), "!!id:\"https://elsewhere.test/s.tn\"\n{}\n");
        try {
            Files.createSymbolicLink(served.resolve("order-1.tn"), secret);
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            return; // a filesystem without symlinks has nothing to test here
        }

        TsonSchemaFetchException refused = refusal(serving(served), reference("/order-1.tn"));
        assertEquals(Reason.NOT_PERMITTED, refused.reason());
        assertTrue(refused.getMessage().contains("outside"), refused.getMessage());
    }

    /** A directory is not a document, and is refused rather than opened. */
    @Test
    void onlyARegularFileIsRead(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("order-1.tn"));

        assertEquals(Reason.NOT_PERMITTED, refusal(serving(dir), reference("/order-1.tn")).reason());
    }

    @Test
    void reportsAMissingFileAsNotFound(@TempDir Path dir) {
        assertEquals(Reason.NOT_FOUND, refusal(serving(dir), reference("/nothing-here.tn")).reason());
    }

    /** §2.2.1's rules on an identifying URI, shared with the HTTP source and refused before anything is opened. */
    @Test
    void refusesAReferenceThatIsNotALegalIdentity(@TempDir Path dir) {
        TsonFileSchemaSource source = serving(dir);

        for (String illegal : new String[] {
                "https://" + HOST + ":8443/order-1.tn",       // a port
                "https://user@" + HOST + "/order-1.tn",       // userinfo
                "https://" + HOST + "/order-1.tn#frag",       // a fragment
                "/order-1.tn"}) {                             // not absolute
            assertEquals(Reason.NOT_PERMITTED, refusal(source, illegal).reason(), illegal);
        }
    }

    /** The scheme is a transport hint, not part of the name (§2.2.1) -- so a directory may serve an https identity. */
    @Test
    void theSchemeIsNotPartOfTheIdentity(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));

        assertEquals(schemaAt("/order-1.tn"), serving(dir).fetch("http://" + HOST + "/order-1.tn"));
    }

    @Test
    void refusesADocumentLargerThanTheCap(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));
        TsonFileSchemaSource source =
                TsonFileSchemaSource.builder().mapHost(HOST, dir).maxDocumentBytes(16).build();

        assertEquals(Reason.TOO_LARGE, refusal(source, reference("/order-1.tn")).reason());
    }

    @Test
    void canRequireAContentHashPin(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));
        TsonFileSchemaSource source =
                TsonFileSchemaSource.builder().mapHost(HOST, dir).requireContentHashPin(true).build();

        assertEquals(Reason.NOT_PERMITTED, refusal(source, reference("/order-1.tn")).reason());
        assertEquals(schemaAt("/order-1.tn"), source.fetch(reference("/order-1.tn") + "?sha256=abc"));
    }

    /** Cached by identity, so the pin -- verification metadata, not part of the name -- cannot force a re-read. */
    @Test
    void cachesByIdentitySoAQueryStringCannotForceRereads(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));
        TsonFileSchemaSource source = serving(dir);

        source.fetch(reference("/order-1.tn"));
        Files.delete(file);

        assertTrue(source.isCached(reference("/order-1.tn")));
        assertEquals(schemaAt("/order-1.tn"), source.fetch(reference("/order-1.tn") + "?sha256=abc"),
                "one identity, one entry, whatever the query says");
    }

    /** A question about the cache is not a request to read: nothing this source would refuse is ever cached. */
    @Test
    void isCachedAnswersFalseForAnythingItWouldRefuse(@TempDir Path dir) {
        assertFalse(serving(dir).isCached("https://elsewhere.test/order-1.tn"));
        assertFalse(serving(dir).isCached("not a uri at all"));
    }

    @Test
    void preloadReadsEagerlyAndFailsLoudly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));
        TsonFileSchemaSource source = serving(dir);

        source.preload(reference("/order-1.tn"));
        assertTrue(source.isCached(reference("/order-1.tn")));
        assertThrows(TsonSchemaFetchException.class, () -> source.preload(reference("/missing.tn")));
    }

    /** A mapping that cannot be satisfied is a startup mistake, and says so at build time. */
    @Test
    void refusesAMappingThatCannotBeSatisfied(@TempDir Path dir) {
        TsonFileSchemaSource.Builder builder = TsonFileSchemaSource.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.mapHost(HOST, dir.resolve("nope")));
        assertThrows(IllegalArgumentException.class, () -> builder.mapHost("has/slash", dir));
    }

    /** The whole arc: a document naming a schema by its https identity, served from disk, resolves and reads. */
    @Test
    void aDocumentNamingAFileBackedSchemaResolvesAndValidates(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));
        TsonFileSchemaSource source = serving(dir);
        Tson tson = Tson.builder().schemaSource(source).build();
        tson.resolve(source.fetch(reference("/order-1.tn")));

        TsonValue order = tson.treeReader().read("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(reference("/order-1.tn")));

        assertEquals("ABC-1", order.get("sku").asString().orElseThrow());
        assertEquals(2, tson.validate("""
                !!schema:"%s"
                !order { }""".formatted(reference("/order-1.tn"))).size());
    }
}
