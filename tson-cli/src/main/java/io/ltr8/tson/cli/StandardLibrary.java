package io.ltr8.tson.cli;

import io.ltr8.tson.parser.TsonSchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.compiler.SchemaMetaNameBinder;
import io.ltr8.tson.parser.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.parser.compiler.TsonCompiledRegistry;
import io.ltr8.tson.parser.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.parser.compiler.ValueReaderFactoryResolver;
import io.ltr8.tson.parser.resolver.BundledSchemaSource;
import io.ltr8.tson.parser.resolver.DefaultTsonCompiledSchemaLoader;
import io.ltr8.tson.parser.resolver.TsonSchemaResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;

/**
 * Bootstraps meta-kernel/meta.tn1/core.tn1 into a fresh registry, and resolves+links a
 * caller-supplied schema document governed by one of them -- the sequence every real
 * schema-compiling test in {@code tson-parser} (e.g. {@code TinySchemaImportsCoreTn1Test}) already
 * hand-rolls, given a real front door for it doesn't exist yet (tracked in {@code BACKLOG.md}'s
 * "Front door / ergonomics").
 *
 * <p><b>Resolution always runs in object-binding mode, regardless of what mode a caller ultimately
 * wants to read data in.</b> Resolving an {@code Instance}/{@code AtomRefinement} declaration
 * (meta.tn1's/core.tn1's own {@code binary_encoding => !enum [...]}, {@code int32 => !integer ^
 * {...}}, and so on -- there are many) calls {@code DefinitionResolver.bindAtomInstance}, which
 * casts its governing meta-schema's own reader output straight to {@code schema.meta.Top} -- a DOM
 * reader's plain {@code Map}/{@code List} output fails that cast outright. Only *compilation* of an
 * already-resolved schema is free to pick a different mode (see {@link #compile}), since it just
 * dispatches an already-built {@code Top} body tree to a factory by constructor name -- it never
 * re-runs resolution.
 *
 * <p>Only supports a user schema governed by (and importing only from) meta-kernel/meta.tn1/core.tn1
 * -- a real, disk/HTTP-backed {@link io.ltr8.tson.parser.resolver.TsonSchemaSource} for arbitrary
 * other governing chains is its own, separately tracked backlog item.
 */
final class StandardLibrary {

    private StandardLibrary() {
    }

    record Bootstrapped(TsonSchemaRegistry schemaRegistry, TsonCompiledRegistry compiledRegistry,
                         DefaultTsonCompiledSchemaLoader loader) {
    }

    static Bootstrapped bootstrap() {
        ValueReaderFactoryResolver resolver = ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext());
        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        TsonCompiledRegistry registry = new TsonCompiledRegistry(schemaRegistry, resolver);
        DefaultTsonCompiledSchemaLoader loader =
                new DefaultTsonCompiledSchemaLoader(registry, BundledSchemaSource.INSTANCE);

        // Meta-kernel's own bootstrap case, registered explicitly -- see BundledSchemaSource's own
        // class Javadoc for why this step can't just be another loader.load(...) call.
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(resolvedMetaKernel, loader.load(BundledSchemaSource.META_KERNEL_ID));

        loader.load(BundledSchemaSource.META_TN1_ID);
        loader.load(BundledSchemaSource.CORE_TN1_ID);

        return new Bootstrapped(schemaRegistry, registry, loader);
    }

    /**
     * Resolves, links, and registers {@code schemaText} into {@code stdlib}'s own registry --
     * its own {@code !!meta} must already be registered there (meta-kernel, meta.tn1, or core.tn1),
     * and any {@code !!import} it carries must resolve the same way. Deliberately stops short of
     * compiling -- see {@link #compile} for why that's a separate step with its own mode.
     */
    static TsonLinkedSchema resolveUserSchema(Bootstrapped stdlib, String schemaText) {
        SchemaDocument document = new TsonSchemaParser(schemaText).parseSchemaDocument();
        TsonSchema resolved = new TsonSchemaResolver(stdlib.loader()).resolveSchema(document);
        return stdlib.schemaRegistry().register(TsonSchemaLinker.link(resolved, stdlib.schemaRegistry()));
    }

    /**
     * Compiles an already-resolved, already-linked schema fresh, in {@code mode} -- independent of
     * the object-binding mode {@link #resolveUserSchema} always used internally. Safe because
     * compiling only ever dispatches an already-built {@code Top} body tree to a factory by
     * constructor name; it never re-resolves an {@code Instance}/{@code AtomRefinement} declaration,
     * so nothing here needs a real governing-meta reader the way resolution did.
     */
    static TsonCompiledMetaSchema compile(TsonLinkedSchema linked, ValueReaderFactoryResolver mode) {
        return TsonCompiledMetaSchema.bootstrap(linked, mode);
    }
}
