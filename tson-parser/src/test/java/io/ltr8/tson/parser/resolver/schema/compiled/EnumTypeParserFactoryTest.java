package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.resolver.schema.MetaKernelParser;
import io.ltr8.tson.parser.resolver.vocab.AtomValidationException;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the exact case this pair of classes was built for: {@code boolean => !enum [true false]}
 * -- broken for generic Java-object binding (its members collide with TSON's own boolean-literal
 * shape, see {@code EnumParser}'s own Javadoc), but read correctly here, since nothing in this
 * package ever routes a token through {@code BaseTypeResolver} identification at all. Uses {@code
 * boolean}'s own *real*, {@code MetaKernelParser}-resolved {@link EnumBody} -- not a hand-built
 * stand-in -- as the field type of a small local record, then reads real TSON data source text
 * through the real {@link TsonDataParser}.
 */
class EnumTypeParserFactoryTest {

    private static TsonCompiledSchema compiled() {
        // The whole real meta-kernel closure, not a hand-picked subset -- "boolean"'s own source
        // names "enum", "enum" composes with "atom", and so on transitively; cherry-picking just
        // "boolean" drags in most of meta-kernel anyway via SchemaValidator's own reference checks,
        // so there's nothing simpler about trying to trim it down. One extra local entry on top.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>(MetaKernelParser.getMetaKernelSchema().entries());
        entries.put("flag_holder", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("flag", TypeRef.of("boolean"))))));
        TsonSchema schema = new TsonSchema("https://example.test/flag.tn1",
                "https://example.test/meta.tn1", List.of(), entries);

        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        TsonSchema registered = schemaRegistry.register(TsonSchemaLinker.link(schema, schemaRegistry)).schema();
        TsonParserFactoryRegistry registry = TsonParserFactoryRegistry.builder()
                .register("record", RecordParser.FACTORY)
                .register("enum", AtomTypeParser.ENUM)
                .build();
        return TsonSchemaCompiler.compile(registered, registry);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(TsonCompiledSchema compiled, String source) {
        Document document = new TsonDataParser(source).parseDocument();
        return (Map<String, Object>) compiled.get("flag_holder").read(document.root());
    }

    @Test
    void realBooleanEnumMembersReadCorrectlyAsDataThroughThisLayer() {
        TsonCompiledSchema compiled = compiled();

        assertEquals("true", read(compiled, "{ flag: true }").get("flag"));
        assertEquals("false", read(compiled, "{ flag: false }").get("flag"));
    }

    @Test
    void aNonMemberValueFailsValidation() {
        TsonCompiledSchema compiled = compiled();

        assertThrows(AtomValidationException.class, () -> read(compiled, "{ flag: maybe }"));
    }
}
