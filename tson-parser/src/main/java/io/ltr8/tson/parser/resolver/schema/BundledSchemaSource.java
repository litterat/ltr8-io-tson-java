package io.ltr8.tson.parser.resolver.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A {@link SchemaSource} serving this library's own three bundled schema documents -- meta-kernel,
 * meta.tn1, core.tn1 -- straight off the classpath, the same resources {@code
 * tson-parser/build.gradle.kts}'s own {@code processResources} task already copies in from {@code
 * spec/m/} for {@link MetaKernelParser} (see that class's own Javadoc for the identical
 * one-file-to-keep-in-sync reasoning).
 *
 * <p><b>Replaces the old, standalone {@code MetaTn1Parser}/{@code CoreTn1Parser} classes</b>
 * (deleted once this existed) -- each hand-rolled its own fetch-parse-resolve-register-compile
 * sequence for one schema specifically; the general version of that sequence is exactly what
 * {@link DefaultSchemaCoordinator#resolve(String)}'s own generic branch already does for *any*
 * URI, given a {@link SchemaSource} that knows how to fetch it. This class is that source for all
 * three well-known identities -- nothing more. A caller wanting meta.tn1's own compiled reader now
 * does:
 *
 * <pre>{@code
 * TsonSchema metaKernel = MetaKernelParser.getMetaKernelSchema();
 * SchemaRegistry schemaRegistry = new SchemaRegistry();
 * TsonSchema materializedMetaKernel = schemaRegistry.materializeBootstrap(metaKernel);
 * ParserFactoryRegistry factories = ParserFactoryRegistry.object(materializedMetaKernel, context);
 * TsonCompiledRegistry registry = new TsonCompiledRegistry(schemaRegistry, factories);
 * registry.register(materializedMetaKernel); // meta.tn1's own !!import needs this present first --
 *                                             // see DefaultSchemaCoordinator's own Javadoc on why
 *                                             // the bootstrap case alone doesn't satisfy
 *                                             // SchemaValidator's import merge. Registering the
 *                                             // already-materialized result, not the raw metaKernel
 *                                             // itself -- SchemaRegistry.register refuses an
 *                                             // unmaterialized bootstrap schema outright (see its
 *                                             // own Javadoc).
 * DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry, BundledSchemaSource.INSTANCE);
 * TsonSchemaParser meta = coordinator.resolve(BundledSchemaSource.META_TN1_ID);
 * TsonSchemaParser core = coordinator.resolve(BundledSchemaSource.CORE_TN1_ID); // needs meta.tn1 registered first, same reasoning
 * }</pre>
 *
 * <p><b>{@link #META_KERNEL_ID} is meta-kernel's own well-known identity -- the canonical
 * definition, moved here from {@code DefaultSchemaCoordinator} (2026-07-26, on the user's own
 * explicit direction)</b> so this class, not the coordinator, is the one place that owns "what URI
 * does each of this library's own bundled schemas live at." {@code DefaultSchemaCoordinator} no
 * longer declares its own copy -- it references this constant directly.
 *
 * <p><b>That entry in {@link #RESOURCES} is still never actually reached through {@link
 * DefaultSchemaCoordinator#resolve(String)}</b>, though -- that method special-cases it and
 * resolves it via {@link MetaKernelParser#getMetaKernelSchema()} directly, before this source's
 * own {@link #fetch} is ever consulted (see that method's own Javadoc for why: meta-kernel's
 * {@code !!meta} names itself, and falling through to the generic fetch-then-{@code
 * SchemaResolver(this)} path would recurse forever). It's included here anyway so this class is a
 * complete, uniform "fetch any of this library's own bundled schema documents" utility on its own
 * terms -- useful directly (e.g. {@code BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource
 * .META_KERNEL_ID)}, to get meta-kernel's raw source text the same way {@link #META_TN1_ID}/
 * {@link #CORE_TN1_ID} already can be -- {@link MetaKernelParser#getMetaKernelSchema()} does
 * exactly this internally), and safe for any *other* {@link SchemaCoordinator} implementation that
 * doesn't special-case meta-kernel the way {@link DefaultSchemaCoordinator} does.
 */
public final class BundledSchemaSource implements SchemaSource {

    /** meta-kernel's own real, published identity -- see {@code spec/m/meta-kernel.tn1}'s own {@code !!id}. */
    public static final String META_KERNEL_ID = "https://tson.io/2026/32/m/meta-kernel.tn1";

    /** meta.tn1's own real, published identity -- see {@code spec/m/meta.tn1}'s own {@code !!id}. */
    public static final String META_TN1_ID = "https://tson.io/2026/32/m/meta.tn1";

    /** core.tn1's own real, published identity -- see {@code spec/m/core.tn1}'s own {@code !!id}. */
    public static final String CORE_TN1_ID = "https://tson.io/2026/32/m/core.tn1";

    public static final BundledSchemaSource INSTANCE = new BundledSchemaSource();

    private static final Map<String, String> RESOURCES = Map.of(
            META_KERNEL_ID, "/meta-kernel.tn1",
            META_TN1_ID, "/meta.tn1",
            CORE_TN1_ID, "/core.tn1");

    private BundledSchemaSource() {
    }

    @Override
    public String fetch(String uri) {
        String resource = RESOURCES.get(uri);
        if (resource == null) {
            throw new IllegalStateException(
                    "'" + uri + "' is not one of this library's own bundled schemas "
                            + "(meta-kernel.tn1, meta.tn1, core.tn1)");
        }
        try (InputStream in = BundledSchemaSource.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
