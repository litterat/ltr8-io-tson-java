package io.ltr8.tson.schema;

import io.ltr8.tson.base.CanonicalIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The real, published identities of the five schema documents this library bundles and pre-loads --
 * meta-kernel (the self-referencing bootstrap layer, spec §9's "meta layer"), meta (the canonical
 * meta-schema, the other half of the "meta layer"), core (the core type library, governed by meta but
 * not itself part of the meta layer -- spec §9 is explicit that only two schemas make up that layer), net
 * (the network type library: addresses, networks and host names, governed by meta like core), and policy
 * (the processor policy's vocabulary, [TSON-DATA] §8.2, §9.1) -- plus {@link #fetch}, their raw source
 * text, straight off this module's own classpath.
 *
 * <p>Both the identities and their source text live here, in {@code tson-schema}: "what these documents
 * are" (identity) and "where their content lives" (fetch) sit in the one module every consumer of them
 * already depends on -- {@code tson-compiler}'s {@code TsonCompiledMetaRegistry},
 * {@code MetaKernelBootstrapResolver} and {@code TsonSchemaLinker}'s meta-kernel-governed check among them,
 * {@code tson-compiler} depending on this module and never the reverse.
 *
 * <p>{@link #fetch} is a static method on a class of constants, so there is no instance to implement
 * {@link io.ltr8.tson.base.source.SchemaSource} with. Its shape is that interface's single abstract method
 * exactly -- {@code String fetch(String uri)} -- so a caller needing a {@code SchemaSource} passes the
 * method reference {@code TsonBundledSchemas::fetch}, and no adapter class exists on either side.
 */
public final class TsonBundledSchemas {

    /**
     * meta-kernel's own real, published identity -- see {@code spec/m/meta-kernel.tn}'s own {@code
     * !!id}. The one meta-kernel this library's own compiled-reader machinery is built against -- see
     * {@code TsonSchemaLinker.isMetaKernelGoverned}'s own Javadoc for why that check needs this to be
     * a specific, fixed identity rather than a structural "is this schema self-referencing" test.
     */
    public static final String META_KERNEL_ID = "https://tson.io/2026/38/m/meta-kernel.tn";

    /** meta's own real, published identity -- see {@code spec/m/meta.tn}'s own {@code !!id}. */
    public static final String META_ID = "https://tson.io/2026/38/m/meta.tn";

    /** core's own real, published identity -- see {@code spec/m/core.tn}'s own {@code !!id}. */
    public static final String CORE_ID = "https://tson.io/2026/38/m/core.tn";

    /** net's own real, published identity -- see {@code spec/m/net.tn}'s own {@code !!id}. */
    public static final String NET_ID = "https://tson.io/2026/38/m/net.tn";

    /** policy's own real, published identity -- see {@code spec/m/policy.tn}'s own {@code !!id}. */
    public static final String POLICY_ID = "https://tson.io/2026/38/m/policy.tn";

    /**
     * meta-kernel's own published content-hash digest -- the {@code ?sha256=} on {@code
     * spec/m/meta-kernel.tn}'s own {@code !!id}. [TSON-SCHEMA] §10.2's "implementation-held digest": the
     * library holds it so a hash-pinned reference to a pre-loaded schema can be verified, and so the
     * shipped resource can be checked against its own published digest ({@link #declaredSha256}).
     */
    public static final String META_KERNEL_SHA256 = "c50085d26e23c303eb3e43f3c1781f9114f5bd1deed189d71081e59124bcb874";

    /** meta's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/meta.tn}'s {@code !!id}. See {@link #META_KERNEL_SHA256}. */
    public static final String META_SHA256 = "b558429d49d2e1abb64708e13514b98ea1a5bdaf70c2c41af5a29d09a893e92c";

    /** core's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/core.tn}'s {@code !!id}. See {@link #META_KERNEL_SHA256}. */
    public static final String CORE_SHA256 = "666640c44263a5c188f39356fec8983d77fb5c215ce957d9db68095aef979866";

    /**
     * net's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/net.tn}'s {@code
     * !!id}. See {@link #META_KERNEL_SHA256}.
     */
    public static final String NET_SHA256 = "4461d718fbc77af8596bd0abc1a61ed893329ba5926f654eeb018993e14b247a";

    /**
     * policy's own published content-hash digest -- the {@code ?sha256=} on {@code spec/m/policy.tn}'s {@code
     * !!id}. See {@link #META_KERNEL_SHA256}.
     */
    public static final String POLICY_SHA256 = "abe0ddbc64d212a071a1548f94b4c3a617acf86daa333fbac4fec9be8bb9c690";

    private static final Map<String, String> RESOURCES = Map.of(
            META_KERNEL_ID, "/meta-kernel.tn",
            META_ID, "/meta.tn",
            CORE_ID, "/core.tn",
            NET_ID, "/net.tn",
            POLICY_ID, "/policy.tn");

    private static final Map<String, String> DIGESTS = Map.of(
            CanonicalIdentity.canonicalize(META_KERNEL_ID), META_KERNEL_SHA256,
            CanonicalIdentity.canonicalize(META_ID), META_SHA256,
            CanonicalIdentity.canonicalize(CORE_ID), CORE_SHA256,
            CanonicalIdentity.canonicalize(NET_ID), NET_SHA256,
            CanonicalIdentity.canonicalize(POLICY_ID), POLICY_SHA256);

    private TsonBundledSchemas() {
    }

    /**
     * Whether {@code identity} names the meta-kernel, in any spelling of its identity -- the test an entry's origin
     * is put to for the entry to be the kernel's own, which is what a kind and a constructor are judged by
     * ([TSON-SCHEMA] §4.1). {@code false} for {@code null} and for a string that names no identity.
     */
    public static boolean isMetaKernel(String identity) {
        if (identity == null) {
            return false;
        }
        if (identity.equals(META_KERNEL_IDENTITY)) {
            return true; // already canonical, as a linked schema's origins are
        }
        try {
            return CanonicalIdentity.canonicalize(identity).equals(META_KERNEL_IDENTITY);
        } catch (io.ltr8.tson.base.SchemaValidationException notAnIdentity) {
            return false;
        }
    }

    private static final String META_KERNEL_IDENTITY = CanonicalIdentity.canonicalize(META_KERNEL_ID);

    /**
     * The published content-hash digest this library holds for {@code uri} if it names a pre-loaded
     * bundled schema, else empty ([TSON-SCHEMA] §10.2). Matched by canonical identity, so a plain or a
     * {@code ?sha256=}-pinned reference both find it.
     */
    public static Optional<String> declaredSha256(String uri) {
        return Optional.ofNullable(DIGESTS.get(CanonicalIdentity.canonicalize(uri)));
    }

    /**
     * Returns one of the five bundled schemas' own raw source text, straight off this module's own
     * classpath -- the resources {@code tson-schema/build.gradle.kts}'s {@code processResources} task
     * copies in from the repo's own {@code spec/m/}.
     *
     * @throws IllegalStateException if {@code uri} isn't one of {@link #META_KERNEL_ID}/{@link
     *                                #META_ID}/{@link #CORE_ID}/{@link #NET_ID}/{@link
     *                                #POLICY_ID}
     */
    public static String fetch(String uri) {
        String resource = RESOURCES.get(uri);
        if (resource == null) {
            throw new IllegalStateException(
                    "'" + uri + "' is not one of this library's own bundled schemas "
                            + "(meta-kernel, meta, core, net, policy)");
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
