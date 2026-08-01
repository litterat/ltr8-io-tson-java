package io.ltr8.tson;

import io.ltr8.tson.compiler.ContentHash;
import io.ltr8.tson.compiler.ContentHashMismatchException;
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
    void theHeldMetaKernelDigestMatchesTheShippedResource() {
        // The library-held digest must equal the content hash of the packaged file -- otherwise the
        // constant and the resource have drifted (this is the "check the held digest against the file").
        assertEquals(TsonBundledSchemas.META_KERNEL_SHA256, ContentHash.sha256(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aCorrectlyPinnedReferenceToTheBundledMetaKernelVerifies() {
        Tson tson = Tson.builder().build();
        assertDoesNotThrow(() -> tson.loader().load(
                TsonBundledSchemas.META_KERNEL_ID + "?sha256=" + TsonBundledSchemas.META_KERNEL_SHA256));
    }

    @Test
    void aWrongPinToTheBundledMetaKernelIsRejected() {
        Tson tson = Tson.builder().build();
        assertThrows(ContentHashMismatchException.class, () ->
                tson.loader().load(TsonBundledSchemas.META_KERNEL_ID + "?sha256=" + "a".repeat(64)));
    }
}
