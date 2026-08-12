package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic application ({@code head<args>}) in an ordinary user schema -- one governed by {@code meta.tn} and
 * importing {@code core.tn} -- driven through the real bundled chain. The end-to-end peer of {@code
 * SchemaDesugarerTest}, which pins the same rewrite against a hand-built governing meta.
 *
 * <p>[TSON-SCHEMA] §3.3.1 lists "generic-application heads -- the name before {@code <} when the name is not
 * otherwise in scope" among the <b>constructor roles</b> at which the structure namespace is consulted, and
 * gives {@code map<text, text>} as its own example. A user schema's {@code !!meta} is {@code meta.tn}, which
 * imports the meta-kernel, so every container constructor is in that schema's structure namespace.
 *
 * <p>What makes these work uniformly is that {@code SchemaDesugarer} rewrites the application into a real
 * {@code !C value} declaration before resolution, so {@code map} takes the same path as {@code array} rather
 * than a per-shape assembler that only some constructors have.
 */
class GenericApplicationHeadTest {

    private static final String ID = "https://example.test/generic-head.tn";

    /** Resolves, links and compiles a user schema whose body is {@code declarations}; throws whatever the pipeline throws. */
    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/generic-head.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        TsonSchemaSource source = uri -> {
            if (TsonSchemaRegistry.canonicalIdentity(uri).equals(TsonSchemaRegistry.canonicalIdentity(ID))) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** The entry a field's type names, which every fixture here reaches the injected declaration through. */
    private static TypeDefinition fieldTypeEntry(TsonCompiledSchema compiled, String record, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(record).body();
        String name = body.fields().stream().filter(f -> f.name().equals(field)).findFirst().orElseThrow()
                .type().name();
        return compiled.schema().entries().get(name);
    }

    /** The whole arc in one assertion: a {@code map}-typed field resolves, links, compiles, and reads real data. */
    @Test
    void aMapTypedFieldReadsRealData() {
        TsonCompiledSchema compiled = compile("  holder => { entries: map<text, text> }");

        MapBody body = assertInstanceOf(MapBody.class, fieldTypeEntry(compiled, "holder", "entries").body());
        assertEquals(TypeRef.of("text"), body.keyType());
        assertEquals(TypeRef.of("text"), body.valueType());

        TsonNode value = (TsonNode) compiled.get("holder").read("{ entries: { \"a\" => \"one\"  \"b\" => \"two\" } }");
        assertNotNull(value);
    }

    @Test
    void theSameApplicationLinksAsATopLevelDeclaration() {
        // §5.6: a declaration whose body is a fully-bound constructor application is a construction. The
        // desugar phase makes the field position above take this exact same path.
        assertNotNull(compile("""
                  entries => map<text, text>
                  holder => { xs: entries }"""));
    }

    @Test
    void arraySugarAndExplicitArrayOrSetApplicationsAllLink() {
        assertNotNull(compile("  holder => { xs: [text] }"));
        assertNotNull(compile("  holder => { xs: array<text> }"));
        assertNotNull(compile("  holder => { xs: set<text> }"));
    }

    /**
     * {@code set} and {@code array} share a body shape, so the only thing distinguishing them is the defaults
     * {@code set}'s own vocabulary tightens (§5.7). Binding the injected {@code !set { element_type: text }}
     * through the compiled reader applies those schema-composed defaults, so this needs no {@code
     * set}-specific handling anywhere -- worth pinning precisely because nothing names {@code set}.
     */
    @Test
    void aSetApplicationCarriesItsOwnTightenedDefaultsNotArrays() {
        ArrayBody asSet = assertInstanceOf(ArrayBody.class,
                fieldTypeEntry(compile("  holder => { xs: set<text> }"), "holder", "xs").body());
        assertTrue(asSet.unordered());
        assertTrue(asSet.uniqueItems());

        ArrayBody asArray = assertInstanceOf(ArrayBody.class,
                fieldTypeEntry(compile("  holder => { xs: array<text> }"), "holder", "xs").body());
        assertFalse(asArray.unordered());
        assertFalse(asArray.uniqueItems());
    }

    /**
     * A non-constructor generic head -- a locally declared template. §5.10 parameter substitution is a
     * separate, unimplemented feature, so the desugar phase cannot rewrite the application; it rejects it
     * where it is written rather than passing it through. Left alone, this schema linked and compiled and
     * then failed on the first read that reached the field, with {@code 'T' is referenced but not present in
     * the schema} -- an error a caller might never provoke.
     */
    @Test
    void applyingALocallyDeclaredTemplateIsRejectedWhereItIsWritten() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> compile("""
                          box => <T> { v: T }
                          holder => { b: box<text> }"""));

        assertTrue(thrown.getMessage().contains("'box' is a parameterized template"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not implemented"), thrown.getMessage());
    }
}
