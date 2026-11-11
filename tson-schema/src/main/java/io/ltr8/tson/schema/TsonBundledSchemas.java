package io.ltr8.tson.schema;

import io.ltr8.tson.base.CanonicalIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The real, published identities of the six schema documents this library bundles and pre-loads -- meta (the kernel:
 * the one wait cycle a processor closes by pre-loading, every name it references and the concepts that belong with
 * them, [TSON-SCHEMA] §3.4), the three data-format libraries importing it alone, numeric (the exact and approximate
 * numbers), temporal (dates, times, durations and periods) and net (addresses, networks, host names, URIs and UUIDs),
 * core (importing the three, so one import has every bundled type, and declaring the author annotations) and policy
 * (the processor policy's vocabulary, [TSON-DATA] §8.2, §9.1) -- plus {@link #fetch}, their raw source text, straight
 * off this module's own classpath. Each document's root holds the vocabulary an author writes, and its machinery
 * sits under open nested maps: the kernel's under six ({@link KernelNames}), the libraries' constructors under
 * {@code numeric}, {@code temporal} and {@code net}, numeric's and net's being two of the kernel's.
 *
 * <p>Both the identities and their source text live here, in {@code tson-schema}: "what these documents
 * are" (identity) and "where their content lives" (fetch) sit in the one module every consumer of them
 * already depends on -- {@code tson-compiler}'s {@code TsonCompiledVocabularyRegistry},
 * {@code KernelBootstrapResolver} and {@code TsonSchema.namespaceSources} among them,
 * {@code tson-compiler} depending on this module and never the reverse.
 *
 * <p>{@link #fetch} is a static method on a class of constants, so there is no instance to implement
 * {@link io.ltr8.tson.base.source.SchemaSource} with. Its shape is that interface's single abstract method
 * exactly -- {@code String fetch(String uri)} -- so a caller needing a {@code SchemaSource} passes the
 * method reference {@code TsonBundledSchemas::fetch}, and no adapter class exists on either side.
 */
public final class TsonBundledSchemas {

    /**
     * The kernel's own real, published identity -- see {@code spec/m/meta.tn}'s own {@code !!id}. The one document
     * pre-loaded and bound to the software's revision, and the one this library's compiled-reader machinery is built
     * against.
     */
    public static final String META_ID = "https://tson.io/2026/40/m/meta.tn";

    /** numeric's own real, published identity -- see {@code spec/m/numeric.tn}'s own {@code !!id}. */
    public static final String NUMERIC_ID = "https://tson.io/2026/40/m/numeric.tn";

    /** temporal's own real, published identity -- see {@code spec/m/temporal.tn}'s own {@code !!id}. */
    public static final String TEMPORAL_ID = "https://tson.io/2026/40/m/temporal.tn";

    /** net's own real, published identity -- see {@code spec/m/net.tn}'s own {@code !!id}. */
    public static final String NET_ID = "https://tson.io/2026/40/m/net.tn";

    /** core's own real, published identity -- see {@code spec/m/core.tn}'s own {@code !!id}. */
    public static final String CORE_ID = "https://tson.io/2026/40/m/core.tn";

    /** policy's own real, published identity -- see {@code spec/m/policy.tn}'s own {@code !!id}. */
    public static final String POLICY_ID = "https://tson.io/2026/40/m/policy.tn";

    /**
     * The kernel's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/meta.tn}'s own {@code
     * !!id}. [TSON-SCHEMA] §10.2's "implementation-held digest": the library holds it so a hash-pinned reference to a
     * pre-loaded schema can be verified, and so the shipped resource can be checked against its own published digest
     * ({@link #declaredSha256}).
     */
    public static final String META_SHA256 = "42057db740595e473b6144db0b87565645b2fcbbd3fbe945310a60c1045a8fb8";

    /** numeric's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/numeric.tn}'s !!id. */
    public static final String NUMERIC_SHA256 = "8435fe35703cfba0ab24b2918e3996dc93ee2babf20f84e025ccd3ed357be9c8";

    /** temporal's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/temporal.tn}'s !!id. */
    public static final String TEMPORAL_SHA256 = "45147e9d8659edc3bf78dffb425512ee36eebb9e4de99e67c3b04140a0395589";

    /** net's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/net.tn}'s {@code !!id}. */
    public static final String NET_SHA256 = "9c088a3f68c5b52e627b9510089941440cb7f19b7a0d67f782b158cf7d7105da";

    /** core's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/core.tn}'s {@code !!id}. */
    public static final String CORE_SHA256 = "0faef39471884bada5b185308aa63a7dcc9f8ba30a2fd02dfc83213fa7b8cf48";

    /** policy's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/policy.tn}'s !!id. */
    public static final String POLICY_SHA256 = "7df7bcbe6cda95fbfc019f696e5dad282dd9c15d651ad7d436861f996fb94c89";

    /**
     * The six identities in dependency order: the kernel, the three libraries importing it alone, core importing the
     * libraries, and policy. An entry's document precedes every document importing it.
     */
    public static final List<String> IDS = List.of(META_ID, NUMERIC_ID, TEMPORAL_ID, NET_ID, CORE_ID, POLICY_ID);

    /**
     * The data-format libraries, each importing the kernel alone and declaring its constructors under a schema map of
     * its own name ({@code numeric}, {@code temporal}, {@code net}), in dependency order.
     */
    public static final List<String> LIBRARY_IDS = List.of(NUMERIC_ID, TEMPORAL_ID, NET_ID);

    private static final Map<String, String> RESOURCES = Map.of(
            CanonicalIdentity.canonicalize(META_ID), "/meta.tn",
            CanonicalIdentity.canonicalize(NUMERIC_ID), "/numeric.tn",
            CanonicalIdentity.canonicalize(TEMPORAL_ID), "/temporal.tn",
            CanonicalIdentity.canonicalize(NET_ID), "/net.tn",
            CanonicalIdentity.canonicalize(CORE_ID), "/core.tn",
            CanonicalIdentity.canonicalize(POLICY_ID), "/policy.tn");

    private static final Map<String, String> DIGESTS = Map.of(
            CanonicalIdentity.canonicalize(META_ID), META_SHA256,
            CanonicalIdentity.canonicalize(NUMERIC_ID), NUMERIC_SHA256,
            CanonicalIdentity.canonicalize(TEMPORAL_ID), TEMPORAL_SHA256,
            CanonicalIdentity.canonicalize(NET_ID), NET_SHA256,
            CanonicalIdentity.canonicalize(CORE_ID), CORE_SHA256,
            CanonicalIdentity.canonicalize(POLICY_ID), POLICY_SHA256);

    private static final String KERNEL_IDENTITY = CanonicalIdentity.canonicalize(META_ID);

    private TsonBundledSchemas() {
    }

    /**
     * Whether {@code identity} names the kernel, meta.tn, in any spelling of its identity: the one document
     * pre-loaded by special resolution ([TSON-SCHEMA] §3.4), and the test an entry's origin is put to for the entry to
     * be the kernel's own, which is what a kind and a constructor are judged by ([TSON-SCHEMA] §4.1). An entry's
     * identity is its declaring document and its path, so a member a user schema contributes to {@code meta}, or a
     * library to {@code numeric}, is no kernel entry. {@code false} for {@code null} and for a string that names no
     * identity.
     */
    public static boolean isKernel(String identity) {
        if (identity == null) {
            return false;
        }
        if (identity.equals(KERNEL_IDENTITY)) {
            return true; // already canonical, as a linked schema's origins are
        }
        try {
            return CanonicalIdentity.canonicalize(identity).equals(KERNEL_IDENTITY);
        } catch (io.ltr8.tson.base.SchemaValidationException notAnIdentity) {
            return false;
        }
    }

    /**
     * The published content-hash digest this library holds for {@code uri} if it names a pre-loaded
     * bundled schema, else empty ([TSON-SCHEMA] §10.2). Matched by canonical identity, so a plain or a
     * {@code ?sha256=}-pinned reference both find it.
     */
    public static Optional<String> declaredSha256(String uri) {
        return Optional.ofNullable(DIGESTS.get(CanonicalIdentity.canonicalize(uri)));
    }

    /**
     * Returns one of the six bundled schemas' own raw source text, straight off this module's own
     * classpath -- the resources {@code tson-schema/build.gradle.kts}'s {@code processResources} task
     * copies in from the repo's own {@code spec/m/}. Matched by canonical identity, as {@link #declaredSha256} is,
     * so an {@code !!import} that pins its target ({@code core.tn}'s of the libraries) is served as written.
     *
     * @throws IllegalStateException if {@code uri} names none of {@link #IDS}
     */
    public static String fetch(String uri) {
        String resource;
        try {
            resource = RESOURCES.get(CanonicalIdentity.canonicalize(uri));
        } catch (io.ltr8.tson.base.SchemaValidationException notAnIdentity) {
            resource = null;
        }
        if (resource == null) {
            throw new IllegalStateException(
                    "'" + uri + "' is not one of this library's own bundled schemas "
                            + "(meta, numeric, temporal, net, core, policy)");
        }
        try (InputStream in = TsonBundledSchemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
