package io.ltr8.tson.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
 * io.ltr8.tson.compiler.resolver.DefaultTsonCompiledSchemaLoader}, {@code MetaKernelBootstrapResolver})
 * and `tson-schema`'s own {@link TsonSchemaLinker#isMetaKernelGoverned} alike, since `tson-schema`
 * has no dependency on `tson-compiler` (only the reverse). {@link #fetch} deliberately doesn't
 * implement {@code io.ltr8.tson.compiler.resolver.TsonSchemaSource} -- that interface lives in
 * `tson-compiler`, a module this one has no dependency on -- but its shape (a single {@code
 * String fetch(String uri)} method) already matches that interface's own single abstract method
 * exactly, so a `tson-compiler`-side caller needing a real {@code TsonSchemaSource} instance passes
 * the method reference {@code TsonBundledSchemas::fetch} directly; no adapter class needed on either
 * side.
 *
 * <p><b>Replaces the old, standalone {@code BundledSchemaSource} class</b> (`tson-compiler`, deleted
 * once this existed) -- once this class already held the one canonical copy of all three identities
 * (2026-07-29), keeping the fetch capability split out in a different module, in front of a
 * fixed table keyed by those same three identities, bought nothing further; consolidating both here
 * removed the split entirely, not just moved it.
 */
public final class TsonBundledSchemas {

    /**
     * meta-kernel's own real, published identity -- see {@code spec/m/meta-kernel.tn}'s own {@code
     * !!id}. The one meta-kernel this library's own compiled-reader machinery is built against -- see
     * {@link TsonSchemaLinker#isMetaKernelGoverned}'s own Javadoc for why that check needs this to be
     * a specific, fixed identity rather than a structural "is this schema self-referencing" test.
     */
    public static final String META_KERNEL_ID = "https://tson.io/2026/32/m/meta-kernel.tn";

    /** meta's own real, published identity -- see {@code spec/m/meta.tn}'s own {@code !!id}. */
    public static final String META_ID = "https://tson.io/2026/32/m/meta.tn";

    /** core's own real, published identity -- see {@code spec/m/core.tn}'s own {@code !!id}. */
    public static final String CORE_ID = "https://tson.io/2026/32/m/core.tn";

    private static final Map<String, String> RESOURCES = Map.of(
            META_KERNEL_ID, "/meta-kernel.tn",
            META_ID, "/meta.tn",
            CORE_ID, "/core.tn");

    private TsonBundledSchemas() {
    }

    /**
     * Returns one of the three bundled schemas' own raw source text, straight off this module's own
     * classpath (the same resources {@code tson-schema/build.gradle.kts}'s own {@code
     * processResources} task copies in from the repo's own {@code spec/m/}, mirroring {@code
     * MetaKernelBootstrapResolver}'s identical one-file-to-keep-in-sync reasoning).
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
