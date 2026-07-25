package io.ltr8.tson.parser.resolver.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A {@link SchemaSource} serving exactly this library's own bundled schema documents -- today, just
 * meta.tn1 -- straight off the classpath, the same resource {@code tson-parser/build.gradle.kts}'s
 * own {@code processResources} task already copies in from {@code spec/m/} for {@link
 * MetaKernelParser} (see that class's own Javadoc for the identical one-file-to-keep-in-sync
 * reasoning).
 *
 * <p><b>Replaces the old, standalone {@code MetaTn1Parser} class</b> (deleted once this existed) --
 * that class hand-rolled its own fetch-parse-resolve-register-compile sequence for meta.tn1
 * specifically; the general version of that sequence is exactly what {@link DefaultSchemaCoordinator
 * #resolve(String)}'s own generic branch already does for *any* URI, given a {@link SchemaSource}
 * that knows how to fetch it. This class is that source for meta.tn1's own well-known identity --
 * nothing more. A caller wanting meta.tn1's own compiled reader now does:
 *
 * <pre>{@code
 * MetaSchema metaKernel = MetaKernelParser.parse();
 * TsonSchema materializedMetaKernel = new SchemaRegistry().register(metaKernel);
 * ParserFactoryRegistry factories = ParserFactoryRegistry.object(materializedMetaKernel, context);
 * TsonCompiledRegistry registry = new TsonCompiledRegistry(factories);
 * registry.register(metaKernel); // meta.tn1's own !!import needs this present first -- see
 *                                 // DefaultSchemaCoordinator's own Javadoc on why the bootstrap
 *                                 // case alone doesn't satisfy SchemaValidator's import merge.
 * DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry, BundledSchemaSource.INSTANCE);
 * TsonSchemaParser meta = coordinator.resolve(BundledSchemaSource.META_TN1_ID);
 * }</pre>
 *
 * <p><b>core.tn1 is deliberately not included yet</b> -- its own production loader ({@code
 * CoreTn1Parser}) was deleted in the same round of consolidation as {@code MetaTn1Parser}, but no
 * replacement has been built for it yet; that's a separate, still-open gap, not an oversight here
 * (a caller needing core.tn1 today still reads it directly off the classpath and resolves it by
 * hand -- see {@code CoreTn1ParserTest}'s own former approach, or any of this module's own tests
 * that still do this locally, e.g. {@code SchemaResolverCompiledMetaSchemaTest}'s {@code
 * readBundledCoreSource}).
 */
public final class BundledSchemaSource implements SchemaSource {

    /** meta.tn1's own real, published identity -- see {@code spec/m/meta.tn1}'s own {@code !!id}. */
    public static final String META_TN1_ID = "https://tson.io/2026/32/m/meta.tn1";

    public static final BundledSchemaSource INSTANCE = new BundledSchemaSource();

    private static final Map<String, String> RESOURCES = Map.of(META_TN1_ID, "/meta.tn1");

    private BundledSchemaSource() {
    }

    @Override
    public String fetch(String uri) {
        String resource = RESOURCES.get(uri);
        if (resource == null) {
            throw new IllegalStateException(
                    "'" + uri + "' is not one of this library's own bundled schemas (only meta.tn1, today)");
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
