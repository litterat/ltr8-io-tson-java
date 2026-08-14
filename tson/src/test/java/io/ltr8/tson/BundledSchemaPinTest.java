package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonContentHash;
import io.ltr8.tson.compiler.TsonContentHashMismatchException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The library holds each pre-loaded bundled schema's own published digest ([TSON-SCHEMA] §10.2), so a
 * hash-pinned reference to one is verifiable and the shipped resource is checked against it.
 */
class BundledSchemaPinTest {

    @Test
    void everyHeldDigestMatchesItsShippedResource() {
        // The held constant must equal the packaged file's content hash -- else the two have drifted
        // (this is the "check the held digest against what's in the file").
        assertDigestMatches(TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_KERNEL_SHA256);
        assertDigestMatches(TsonBundledSchemas.META_ID, TsonBundledSchemas.META_SHA256);
        assertDigestMatches(TsonBundledSchemas.CORE_ID, TsonBundledSchemas.CORE_SHA256);
    }

    @Test
    void aCorrectlyPinnedReferenceToEachBundledSchemaVerifies() {
        Tson tson = Tson.builder().build();
        assertDoesNotThrow(() -> {
            // meta-kernel and meta.tn are governing metas (loadMeta); core.tn is a non-meta import (resolveLinked).
            tson.loader().loadMeta(TsonBundledSchemas.META_KERNEL_ID + "?sha256=" + TsonBundledSchemas.META_KERNEL_SHA256);
            tson.loader().loadMeta(TsonBundledSchemas.META_ID + "?sha256=" + TsonBundledSchemas.META_SHA256);
            tson.loader().resolveLinked(TsonBundledSchemas.CORE_ID + "?sha256=" + TsonBundledSchemas.CORE_SHA256);
        });
    }

    @Test
    void aWrongPinToABundledSchemaIsRejected() {
        Tson tson = Tson.builder().build();
        assertThrows(TsonContentHashMismatchException.class, () ->
                tson.loader().resolveLinked(TsonBundledSchemas.CORE_ID + "?sha256=" + "a".repeat(64)));
    }

    private static void assertDigestMatches(String id, String heldDigest) {
        assertEquals(heldDigest,
                TsonContentHash.sha256(TsonBundledSchemas.fetch(id).getBytes(StandardCharsets.UTF_8)), id);
    }
}
