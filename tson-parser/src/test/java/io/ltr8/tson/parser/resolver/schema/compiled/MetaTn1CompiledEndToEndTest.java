package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.resolver.schema.MetaKernelParser;
import io.ltr8.tson.parser.resolver.schema.MetaTn1Parser;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same proof {@link MetaKernelEndToEndTest} gives for meta-kernel.tn1, one rung up the schema
 * ladder: compiles the ENTIRE real, registered {@code meta.tn1} (meta-kernel + meta.tn1, chained
 * the way {@link io.ltr8.tson.parser.resolver.schema.MetaSchemaImportTest} registers them) and
 * reads real TSON data text against one of meta.tn1's own genuinely useful record types.
 */
class MetaTn1CompiledEndToEndTest {

    private static TsonSchema registerMeta() {
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);
        return registry.register(MetaTn1Parser.parse(metaKernel));
    }

    /**
     * {@code meta.tn1} declares 31 entries of its own, but the *registered* schema this compiles --
     * the one a real reader actually needs, since it's what {@link TsonSchemaParser#compile} accepts
     * -- also carries meta-kernel's own entries (merged in via meta.tn1's real {@code !!import}) plus
     * whatever array-sugar materialization synthesized, matching {@code MetaSchemaImportTest}'s own
     * counts. Every one of them still compiles cleanly with the same registry this whole atom-family
     * + composite factory set already proves against meta-kernel.tn1 in {@link
     * MetaKernelEndToEndTest}.
     */
    @Test
    void everyRealMetaEntryCompilesCleanly() {
        TsonSchema meta = registerMeta();
        TsonSchemaParser compiled = TsonSchemaParser.compile(meta, ParserFactoryRegistry.dom());

        for (String name : meta.entries().keySet()) {
            compiled.get(name);
        }
        assertEquals(90, meta.entries().size());
    }

    @Test
    void readsBinaryEncodingEnumMembersAgainstRealData() {
        TsonSchema meta = registerMeta();
        TsonSchemaParser compiled = TsonSchemaParser.compile(meta, ParserFactoryRegistry.dom());
        Document document = new Parser("BASE64").parseDocument();

        Object result = compiled.get("binary_encoding").read(document.root());

        assertEquals("BASE64", result);
    }
}
