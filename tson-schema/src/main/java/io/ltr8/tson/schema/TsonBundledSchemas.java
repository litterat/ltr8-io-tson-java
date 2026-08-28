package io.ltr8.tson.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The real, published identities of the three schema documents this library bundles and pre-loads --
 * meta-kernel (the self-referencing bootstrap layer, spec §9's "meta layer"), meta (the canonical
 * meta-schema, the other half of the "meta layer"), and core (the core type library, governed by
 * meta but not itself part of the meta layer -- spec §9 is explicit that only two schemas, not
 * three, make up that layer) -- plus {@link #fetch}, their raw source text, straight off this
 * module's own classpath.
 *
 * <p>Both the identities and their source text live here, in `tson-schema`, not in a separate
 * `tson-compiler`-side class -- there's nothing left for a split class to do once both halves of
 * "what these documents are" (identity) and "where their content lives" (fetch) sit in the one
 * module that can be the single canonical source for `tson-compiler`-side consumers (e.g. {@code
 * io.ltr8.tson.compiler.TsonCompiledMetaRegistry}, {@code MetaKernelBootstrapResolver},
 * {@code TsonSchemaLinker}'s own meta-kernel-governed check), since `tson-schema`
 * has no dependency on `tson-compiler` (only the reverse). {@link #fetch} deliberately doesn't
 * implement {@code io.ltr8.tson.compiler.TsonSchemaSource} -- that interface lives in
 * `tson-compiler`, a module this one has no dependency on -- but its shape (a single {@code
 * String fetch(String uri)} method) already matches that interface's own single abstract method
 * exactly, so a `tson-compiler`-side caller needing a real {@code TsonSchemaSource} instance passes
 * the method reference {@code TsonBundledSchemas::fetch} directly; no adapter class needed on either
 * side.
 */
public final class TsonBundledSchemas {

    /**
     * meta-kernel's own real, published identity -- see {@code spec/m/meta-kernel.tn}'s own {@code
     * !!id}. The one meta-kernel this library's own compiled-reader machinery is built against -- see
     * {@code TsonSchemaLinker.isMetaKernelGoverned}'s own Javadoc for why that check needs this to be
     * a specific, fixed identity rather than a structural "is this schema self-referencing" test.
     */
    public static final String META_KERNEL_ID = "https://tson.io/2026/33/m/meta-kernel.tn";

    /** meta's own real, published identity -- see {@code spec/m/meta.tn}'s own {@code !!id}. */
    public static final String META_ID = "https://tson.io/2026/33/m/meta.tn";

    /** core's own real, published identity -- see {@code spec/m/core.tn}'s own {@code !!id}. */
    public static final String CORE_ID = "https://tson.io/2026/33/m/core.tn";

    /**
     * meta-kernel's own published content-hash digest -- the {@code ?sha256=} on {@code
     * spec/m/meta-kernel.tn}'s own {@code !!id}. [TSON-SCHEMA] §10.2's "implementation-held digest": the
     * library holds it so a hash-pinned reference to a pre-loaded schema can be verified, and so the
     * shipped resource can be checked against its own published digest ({@link #declaredSha256}).
     */
    public static final String META_KERNEL_SHA256 = "fe236a5855de399610a4e27cd3ae369fac644331566e2a2075634ab176cd5101";

    /** meta's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/meta.tn}'s {@code !!id}. See {@link #META_KERNEL_SHA256}. */
    public static final String META_SHA256 = "9d0477118e6bbfb7078173ab9393e50503bccb43605e57ccdccaed468478610e";

    /** core's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/core.tn}'s {@code !!id}. See {@link #META_KERNEL_SHA256}. */
    public static final String CORE_SHA256 = "2e150fce7f8b591b3b8054c9d54ae150cebdd972cf8be7f96eceafd165ec4dbf";

    private static final Map<String, String> RESOURCES = Map.of(
            META_KERNEL_ID, "/meta-kernel.tn",
            META_ID, "/meta.tn",
            CORE_ID, "/core.tn");

    private static final Map<String, String> DIGESTS = Map.of(
            TsonCanonicalIdentity.canonicalize(META_KERNEL_ID), META_KERNEL_SHA256,
            TsonCanonicalIdentity.canonicalize(META_ID), META_SHA256,
            TsonCanonicalIdentity.canonicalize(CORE_ID), CORE_SHA256);

    private TsonBundledSchemas() {
    }

    /**
     * The published content-hash digest this library holds for {@code uri} if it names a pre-loaded
     * bundled schema, else empty ([TSON-SCHEMA] §10.2). Matched by canonical identity, so a plain or a
     * {@code ?sha256=}-pinned reference both find it.
     */
    public static Optional<String> declaredSha256(String uri) {
        return Optional.ofNullable(DIGESTS.get(TsonCanonicalIdentity.canonicalize(uri)));
    }

    /**
     * Returns one of the three bundled schemas' own raw source text, straight off this module's own
     * classpath -- the resources {@code tson-schema/build.gradle.kts}'s {@code processResources} task
     * copies in from the repo's own {@code spec/m/}.
     *
     * @throws IllegalStateException if {@code uri} isn't one of {@link #META_KERNEL_ID}/{@link
     *                                #META_ID}/{@link #CORE_ID}
     */
    public static String fetch(String uri) {
        String resource = RESOURCES.get(uri);
        if (resource == null) {
            throw new IllegalStateException(
                    "'" + uri + "' is not one of this library's own bundled schemas "
                            + "(meta-kernel, meta, core)");
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
