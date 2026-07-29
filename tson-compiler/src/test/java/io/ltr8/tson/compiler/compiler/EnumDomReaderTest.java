package io.ltr8.tson.compiler.compiler;

import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.ast.Document;
import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.compiler.atom.AtomValidationException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves {@code boolean => !enum [true false]} reads correctly in DOM mode -- broken for generic
 * Java-object binding (its members collide with TSON's own boolean-literal shape, see {@code
 * EnumParser}'s own Javadoc), but fine here, since DOM mode never routes a token through {@code
 * BaseTypeResolver} identification at all. Uses {@code boolean}'s own *real*, {@code
 * MetaKernelBootstrapResolver}-resolved {@link io.ltr8.tson.schema.meta.EnumBody} -- not a
 * hand-built stand-in -- as the field type of a small local record, then reads real TSON data
 * source text through the real {@link TsonDataParser}. Object-binding mode's own contrasting
 * behavior (real {@code Boolean} values, via {@link BooleanReader}) is covered separately, in
 * {@link RecordBindReaderTest}/{@code DefinitionResolverTest}.
 */
class EnumDomReaderTest {

    private static TsonCompiledSchema compiled() {
        // The whole real meta-kernel closure, not a hand-picked subset -- "boolean"'s own source
        // names "enum", "enum" composes with "atom", and so on transitively; cherry-picking just
        // "boolean" drags in most of meta-kernel anyway via SchemaValidator's own reference checks,
        // so there's nothing simpler about trying to trim it down. One extra local entry on top.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>(MetaKernelBootstrapResolver.getMetaKernelSchema().entries());
        entries.put("flag_holder", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("flag", TypeRef.of("boolean"))))));
        // !!meta must be the real meta-kernel identity, not a placeholder -- this schema's own
        // locals are meta-kernel's real constructor vocabulary verbatim, plus one extra entry, so
        // TsonSchemaLinker's own "only a meta-kernel-governed schema may declare constructors" check
        // requires it (the placeholder "meta.tn1" this used to point at was never registered in
        // schemaRegistry below, so it was already functionally inert either way).
        TsonSchema schema = new TsonSchema("https://example.test/flag.tn1",
                TsonBundledSchemas.META_KERNEL_ID, List.of(), entries);

        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        TsonLinkedSchema registered = schemaRegistry.register(TsonSchemaLinker.link(schema, schemaRegistry));
        TsonCompiledSchema placeholder = new TsonCompiledSchema(registered, Map.of());
        TsonCompiledMetaSchema bootstrapMeta = new TsonCompiledMetaSchema(placeholder, ValueReaderFactoryRegistry.dom());
        return TsonSchemaCompiler.compile(registered, bootstrapMeta);
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
