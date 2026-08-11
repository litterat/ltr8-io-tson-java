package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic application ({@code head<args>}) in an ordinary user schema -- one governed by {@code meta.tn} and
 * importing {@code core.tn}. The end-to-end peer of {@code TsonSchemaLinkerTest}'s isolated materialisation
 * tests: this proves the user-facing symptom through the real meta.tn/core.tn chain, that one pins the cause
 * with a hand-built fixture.
 *
 * <p>[TSON-SCHEMA] §3.3.1 lists "generic-application heads -- the name before {@code <} when the name is not
 * otherwise in scope" among the <b>constructor roles</b> at which the structure namespace is consulted, and
 * gives {@code map<text, text>} as its own example. A user schema's {@code !!meta} is {@code meta.tn}, which
 * imports the meta-kernel, so {@code map} is in that schema's structure namespace and this must resolve.
 *
 * <p>It does not, and the cause is <em>not</em> a namespace problem: {@code TsonSchemaLinker.instantiateBody}
 * has per-shape assemblers only for {@code array}/{@code set}, so every other constructor falls back to a
 * placeholder reference whose target still carries its arguments and therefore never substitutes them. The
 * {@code array}/{@code set} cases below reach their constructor through the identical structure-namespace
 * path and work, which is what rules the namespace out.
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

    /**
     * <b>Currently fails.</b> §3.3.1 requires this to resolve. It is the user-facing shape of the defect and
     * the thing the desugar arc exists to fix; when it passes, the arc has landed.
     */
    @Test
    void aMapTypedFieldLinks() {
        assertNotNull(compile("  holder => { entries: map<text, text> }"));
    }

    @Test
    void theSameApplicationLinksAsATopLevelDeclaration() {
        // §5.6: a declaration whose body is a fully-bound constructor application resolves as a construction,
        // which DefinitionResolver handles directly and materialises as a real map body. So the identical
        // application is legal one line up from where it fails -- the clearest measure of how narrow the
        // defect is, and the asymmetry the desugar phase removes.
        assertNotNull(compile("""
                  entries => map<text, text>
                  holder => { xs: entries }"""));
    }

    @Test
    void arraySugarAndExplicitArrayOrSetApplicationsAllLink() {
        // The control that rules out the namespace: array and set are reached through the same
        // structure-namespace lookup as map, and work, because instantiateBody has assemblers for them.
        assertNotNull(compile("  holder => { xs: [text] }"));
        assertNotNull(compile("  holder => { xs: array<text> }"));
        assertNotNull(compile("  holder => { xs: set<text> }"));
    }

    /**
     * A non-constructor generic head -- a locally declared template -- takes a <em>different</em> fallback in
     * {@code instantiate} ({@code !constructor.constructor()}), and produces a schema that links and compiles
     * but cannot read: the placeholder resolves to the unsubstituted template, whose field type is the bare
     * parameter {@code T}.
     *
     * <p>Recorded because it is the same broken placeholder shape surfacing at a different moment, and
     * because it is deliberately <b>out of scope</b> for the desugar arc: real §5.10 parameter substitution is
     * a separate feature. This asserts the read-time failure rather than the compile succeeding, so it stops
     * reading as though template application works.
     */
    @Test
    void aLocallyDeclaredTemplateCompilesButCannotRead() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<text> }""");

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> compiled.get("holder").read("{ b: { v: \"x\" } }"));
        assertTrue(thrown.getMessage().contains("no usable compiled reader"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'T' is referenced but not present"), thrown.getMessage());
    }
}
