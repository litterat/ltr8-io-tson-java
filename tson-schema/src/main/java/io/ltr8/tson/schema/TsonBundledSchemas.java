package io.ltr8.tson.schema;

import io.ltr8.tson.base.CanonicalIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The real, published identities of the four schema documents this library bundles and pre-loads -- meta (the
 * kernel: every kind, constructor, base instance and annotation type of the series under one entry, {@code meta},
 * pre-loaded by special resolution, [TSON-SCHEMA] §3.4), core (the core type library, importing meta), net (the
 * network type library: addresses, networks and host names, importing meta) and policy (the processor policy's
 * vocabulary, [TSON-DATA] §8.2, §9.1) -- plus {@link #fetch}, their raw source text, straight off this module's own
 * classpath.
 *
 * <p>Both the identities and their source text live here, in {@code tson-schema}: "what these documents
 * are" (identity) and "where their content lives" (fetch) sit in the one module every consumer of them
 * already depends on -- {@code tson-compiler}'s {@code TsonCompiledMetaRegistry},
 * {@code MetaKernelBootstrapResolver} and {@code TsonSchema.namespaceSources} among them,
 * {@code tson-compiler} depending on this module and never the reverse.
 *
 * <p>{@link #fetch} is a static method on a class of constants, so there is no instance to implement
 * {@link io.ltr8.tson.base.source.SchemaSource} with. Its shape is that interface's single abstract method
 * exactly -- {@code String fetch(String uri)} -- so a caller needing a {@code SchemaSource} passes the
 * method reference {@code TsonBundledSchemas::fetch}, and no adapter class exists on either side.
 */
public final class TsonBundledSchemas {

    /**
     * The kernel's own real, published identity -- see {@code spec/m/meta.tn}'s own {@code !!id}. The one kernel this
     * library's compiled-reader machinery is built against, pre-loaded and bound to the software's revision.
     */
    public static final String META_ID = "https://tson.io/2026/39/m/meta.tn";

    /** core's own real, published identity -- see {@code spec/m/core.tn}'s own {@code !!id}. */
    public static final String CORE_ID = "https://tson.io/2026/39/m/core.tn";

    /** net's own real, published identity -- see {@code spec/m/net.tn}'s own {@code !!id}. */
    public static final String NET_ID = "https://tson.io/2026/39/m/net.tn";

    /** policy's own real, published identity -- see {@code spec/m/policy.tn}'s own {@code !!id}. */
    public static final String POLICY_ID = "https://tson.io/2026/39/m/policy.tn";

    /**
     * The kernel's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/meta.tn}'s own {@code
     * !!id}. [TSON-SCHEMA] §10.2's "implementation-held digest": the library holds it so a hash-pinned reference to a
     * pre-loaded schema can be verified, and so the shipped resource can be checked against its own published digest
     * ({@link #declaredSha256}).
     */
    public static final String META_SHA256 = "0335e318a8ea9b3a9e108ac6b0ef6cf930def8353ce3cafbb241d4c037cacd54";

    /** core's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/core.tn}'s {@code !!id}. */
    public static final String CORE_SHA256 = "6d57f04f65608aab13769156208c59087b7a3ab3fab695f9b10c0e7a3659f8e0";

    /**
     * net's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/net.tn}'s {@code
     * !!id}.
     */
    public static final String NET_SHA256 = "b78221075ed3dbb9411d3d90a39d09f759d98a08dba15f5252dc333cb70d0322";

    /**
     * policy's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/policy.tn}'s {@code
     * !!id}.
     */
    public static final String POLICY_SHA256 = "36c6d94a3423c62d0d963c84020a794567d3d471b344ea6b4652c959e695db6b";

    private static final Map<String, String> RESOURCES = Map.of(
            META_ID, "/meta.tn",
            CORE_ID, "/core.tn",
            NET_ID, "/net.tn",
            POLICY_ID, "/policy.tn");

    private static final Map<String, String> DIGESTS = Map.of(
            CanonicalIdentity.canonicalize(META_ID), META_SHA256,
            CanonicalIdentity.canonicalize(CORE_ID), CORE_SHA256,
            CanonicalIdentity.canonicalize(NET_ID), NET_SHA256,
            CanonicalIdentity.canonicalize(POLICY_ID), POLICY_SHA256);

    private TsonBundledSchemas() {
    }

    /**
     * Whether {@code identity} names the kernel, meta.tn, in any spelling of its identity -- the test an entry's origin
     * is put to for the entry to be the kernel's own, which is what a kind and a constructor are judged by
     * ([TSON-SCHEMA] §4.1). {@code false} for {@code null} and for a string that names no identity.
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

    private static final String KERNEL_IDENTITY = CanonicalIdentity.canonicalize(META_ID);

    /**
     * The published content-hash digest this library holds for {@code uri} if it names a pre-loaded
     * bundled schema, else empty ([TSON-SCHEMA] §10.2). Matched by canonical identity, so a plain or a
     * {@code ?sha256=}-pinned reference both find it.
     */
    public static Optional<String> declaredSha256(String uri) {
        return Optional.ofNullable(DIGESTS.get(CanonicalIdentity.canonicalize(uri)));
    }

    /**
     * Returns one of the four bundled schemas' own raw source text, straight off this module's own
     * classpath -- the resources {@code tson-schema/build.gradle.kts}'s {@code processResources} task
     * copies in from the repo's own {@code spec/m/}.
     *
     * @throws IllegalStateException if {@code uri} isn't one of {@link #META_ID}/{@link #CORE_ID}/{@link
     *                                #NET_ID}/{@link #POLICY_ID}
     */
    public static String fetch(String uri) {
        String resource = RESOURCES.get(uri);
        if (resource == null) {
            throw new IllegalStateException(
                    "'" + uri + "' is not one of this library's own bundled schemas "
                            + "(meta, core, net, policy)");
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
