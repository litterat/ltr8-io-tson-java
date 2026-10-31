package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.resolver.SchemaResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same proof {@link MetaKernelEndToEndTest} gives for the bootstrap's output, for the kernel as it is
 * registered: meta.tn resolved the ordinary way through {@code SchemaResolver} against the pre-loaded stand-in,
 * linked, registered and compiled whole, then read with real TSON data text against one of its record types.
 */
class MetaTn1CompiledEndToEndTest {

    /**
     * The kernel resolved through {@code resolveSchema}/{@code bindAtomInstance}, whose instance declarations
     * ({@code meta.bytes_encoding => !meta.enum [...]}) read through the loader's object-binding-mode stand-in,
     * then registered; the outer compile this test exercises stays tree mode.
     */
    private static TsonLinkedSchema registerMeta() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledSchemaLoader loader = new TsonCompiledMetaRegistry(context);

        SchemaDocument kernelDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID))
                .parseSchemaDocument();
        TsonSchema kernel = new SchemaResolver(loader).resolveSchema(kernelDocument);
        return registry.register(TsonSchemaLinker.link(kernel, registry));
    }

    /**
     * The raw {@link TsonCompiledSchema} underneath a throwaway bootstrap {@link
     * TsonCompiledMetaSchema} -- this test's own outer compile is plain DOM mode against the global
     * registry directly, not against any real governing meta (mirrors {@code
     * MetaKernelEndToEndTest#rawCompile}).
     */
    private static TsonCompiledSchema rawCompile(TsonLinkedSchema linked) {
        return TsonSchemaCompiler.compile(linked, ValueReaderFactoryRegistry.tree());
    }

    /**
     * The registered kernel's entries -- {@code meta}, its 96 members and the 6 synthetics its sugar lifts -- each
     * compile cleanly with the same factory set {@link MetaKernelEndToEndTest} proves against the bootstrap's output.
     */
    @Test
    void everyRealMetaEntryCompilesCleanly() {
        TsonLinkedSchema meta = registerMeta();
        TsonCompiledSchema compiled = rawCompile(meta);

        for (String name : meta.schema().entries().keySet()) {
            compiled.get(name);
        }
        assertEquals(103, meta.schema().entries().size());
    }

    @Test
    void readsBytesEncodingEnumMembersAgainstRealData() {
        TsonLinkedSchema meta = registerMeta();
        TsonCompiledSchema compiled = rawCompile(meta);

        Object result = Dom.of((TsonValue) compiled.get("meta.bytes_encoding")
                .read(TestDocuments.document("BASE64")));

        assertEquals("BASE64", result);
    }
}
