package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declaration's written parameter bounds ({@code <T: text>}, [TSON-SCHEMA] §5.10): recorded on the parameter as
 * written and never inferred, required of a parameter passed on to a bounded parameter wherever it is an argument, and
 * enforced at the application.
 */
class TemplateParamBoundTest {

    private static final String HEADER = """
            !!id:"https://example.test/bounds-1.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema
            """;

    private static final String BOXED = "  boxed => <T: text> { a: T }\n";

    private static final String SCHEMA = HEADER + """
            {
              non_empty_text => !text ^ { min_length: 1 }
              boxed          => <T: text> { a: T }
              vec            => <T: text> !meta.array { element_type: T }
              rebox          => <T: text> { b: boxed<T> }
              narrower       => <T: non_empty_text> { b: boxed<T> }
              loose          => <T> { a: T }
            }
            """;

    private static Map<String, List<TemplateParam>> parameters() {
        Map<String, List<TemplateParam>> parameters = new LinkedHashMap<>();
        Tson.standard().resolve(SCHEMA).schema().entries().forEach((name, definition) -> {
            if (OpenEntry.isOpen(definition)) {
                parameters.put(name, OpenEntry.parameters(definition));
            }
        });
        return parameters;
    }

    private static TemplateParam bounded(String name, String bound) {
        return new TemplateParam(name, Optional.of(TypeRef.of(bound)));
    }

    private static List<Diagnostic> problems(String declarations) {
        return Tson.standard().validateSchema(HEADER + "{\n" + declarations + "\n}\n");
    }

    private static void assertRefused(String declarations, String fragment) {
        assertRefused(problems(declarations), fragment);
    }

    private static void assertRefused(List<Diagnostic> refused, String fragment) {
        assertEquals(1, refused.size(), refused.toString());
        assertTrue(refused.getFirst().message().contains(fragment), refused.getFirst().message());
    }

    // ── Recorded ───────────────────────────────────────────────────────────

    @Test
    void aRecordTemplatesWrittenTypeIsATypeParametersBound() {
        assertEquals(List.of(bounded("T", "text")), parameters().get("boxed"));
    }

    @Test
    void anInstanceConstructionsWrittenTypeIsABound() {
        assertEquals(List.of(bounded("T", "text")), parameters().get("vec"));
    }

    /** A bound is what the declaration writes: a parameter passed on records its own bound, a narrower one too. */
    @Test
    void aParameterPassedOnRecordsTheBoundItIsWrittenWith() {
        assertEquals(List.of(bounded("T", "text")), parameters().get("rebox"));
        assertEquals(List.of(bounded("T", "non_empty_text")), parameters().get("narrower"));
    }

    @Test
    void anUnwrittenBoundIsAbsent() {
        assertEquals(List.of(new TemplateParam("T")), parameters().get("loose"));
    }

    // ── Refused at the declaration ─────────────────────────────────────────

    /**
     * Nothing about a parameter is inferred: one passed to a bounded parameter with no bound of its own is refused at
     * the declaration that passes it, naming the callee's bound, wherever it is an argument.
     */
    @Test
    void aParameterPassedToABoundedParameterWithNoBoundIsRefused() {
        String needs = "is passed to 'boxed', which declares 'T: text', and is written with no bound";
        assertRefused(BOXED + "  rebox => <T> { b: boxed<T> }", needs);
        assertRefused(BOXED + "  rebox => <T> { b: [boxed<T>] }", needs);
        assertRefused(BOXED + "  pair  => <A, B> { a: A  b: B }\n  rebox => <T> { b: pair<boxed<T>, text> }", needs);
        assertRefused(BOXED + "  rebox => <T> boxed<T> & { c: text }", needs);
        assertRefused(BOXED + "  rebox => <T> boxed<T>", needs);
        assertRefused(BOXED + "  rebox => <T> !meta.array { element_type: boxed<T> }", needs);
    }

    /** The message names the declaration that wrote the parameter, not the template it is passed to. */
    @Test
    void theRefusalIsReportedAtTheDeclarationThatPassesTheParameter() {
        List<Diagnostic> refused = problems(BOXED + "  rebox => <T> { b: boxed<T> }");
        assertRefused(refused, "'rebox': parameter 'T'");
    }

    /** A parameter passed on through a template that does not bound it needs no bound, however deep the chain. */
    @Test
    void aParameterPassedToAnUnboundedParameterNeedsNoBound() {
        assertEquals(List.of(), problems("""
                  loose => <T> { a: T }
                  outer => <T> { b: loose<T> }"""));
    }

    /**
     * A constructor's slot judges the argument that arrives when the record is applied, so a parameter passed to one
     * needs no bound, the slot narrow or not.
     */
    @Test
    void aParameterPassedToAConstructorsSlotNeedsNoBound() {
        assertEquals(List.of(), problems("  keyed => <K> { m: meta.namespace<K text> }"));
        assertEquals(List.of(), problems("  listed => <T> { m: meta.array<T> }"));
    }

    @Test
    void aWrittenBoundThatIsNotTheCalleesIsRefused() {
        assertRefused(BOXED + "  wrong => <T: int32> { b: boxed<T> }",
                "is declared 'T: int32' and passed to 'boxed', which declares 'T: text', and int32 is not a type "
                        + "that IS-A text");
        assertRefused(BOXED + "  wrong => <T: int32> boxed<T>", "int32 is not a type that IS-A text");
    }

    /** The bound an imported template records is what its author wrote, and a parameter passed to it is judged by it. */
    @Test
    void anImportedTemplatesBoundIsRequiredOfAParameterPassedToIt() {
        String imported = """
                !!id:"https://example.test/boxes.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema { boxed => <T: text> { a: T } }
                """;
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(
                SchemaSource.ofMap(Map.of("https://example.test/boxes.tn", imported)))));
        String importing = """
                !!id:"https://example.test/rebox.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !!import:"https://example.test/boxes.tn"
                !schema { rebox => <T> { b: boxed<T> } }
                """;
        assertRefused(tson.validateSchema(importing), "is passed to 'boxed', which declares 'T: text'");
        assertEquals(List.of(), tson.validateSchema(importing.replace("rebox => <T>", "rebox => <T: text>")));
    }

    @Test
    void aWrittenTypeMustNameAType() {
        assertRefused("  bad => <T: [text]> { a: T }", "names a declared type");
    }

    /**
     * A bound names a type in the schema's namespace. A constructor is no type (§4.1), so a bound naming one is
     * refused: no argument could satisfy it -- a type argument names a type.
     */
    @Test
    void aWrittenBoundNamesAType() {
        assertRefused("  boxed => <T: unicode.text_type> { a: T }", "'unicode.text_type' is a constructor, not a type");
        assertRefused("  boxed => <T: no_such_type> { a: T }", "'no_such_type' names no type");
        assertRefused("  boxed => <T: no_such<text>> { a: T }", "'no_such' names no type");
    }

    // ── Enforced at the application ────────────────────────────────────────

    @Test
    void aTypeArgumentMustIsATheBound() {
        assertEquals(List.of(), problems("""
                  boxed  => <T: text> { a: T }
                  short  => !text ^ { max_length: 8 }
                  holder => { b: boxed<short> }"""));
        assertRefused("""
                  boxed  => <T: text> { a: T }
                  holder => { b: boxed<int32> }""", "binds 'T' to 'int32', which is not a type that IS-A text");
    }

    /**
     * An application closed while the schema is still resolving -- a declaration naming it, a composition
     * operand, a refinement source, an argument nested in one of those -- is checked against the same bound as
     * one at a field, once every argument is ready.
     */
    @Test
    void aBoundIsEnforcedOnAnApplicationClosedDuringResolution() {
        assertRefused(BOXED + "  held   => boxed<int32>", "binds 'T' to 'int32', which is not a type that IS-A text");
        assertRefused(BOXED + "  held   => boxed<int32> & { b: text }", "binds 'T' to 'int32'");
        assertRefused(BOXED + "  held   => boxed<boxed<int32>>", "binds 'T' to 'int32'");
        assertRefused(BOXED + "  rebox  => <T: text> boxed<T>\n  held   => rebox<int32>",
                "'rebox<...>' binds 'T' to 'int32'");
        assertEquals(List.of(), problems(BOXED + "  short  => !text ^ { max_length: 8 }\n  held   => boxed<short>"));
    }

    /** A passed-on parameter's written bound is enforced where the outer template is applied. */
    @Test
    void aWrittenBoundIsEnforcedAtTheOuterApplication() {
        assertRefused(BOXED + "  rebox  => <T: text> { b: boxed<T> }\n  holder => { b: rebox<int32> }",
                "'rebox<...>' binds 'T' to 'int32'");
    }

    /**
     * An argument orders nothing (§3.4.1), so judging one never makes the declaration wait on it: two declarations
     * naming applications of each other resolve, their bounds checked once both are ready.
     */
    @Test
    void aBoundCheckDuringResolutionWaitsOnNoArgument() {
        assertEquals(List.of(), problems("""
                  node  => abstract { id: text =? }
                  boxed => <T: node> node & { v?: T }
                  a     => boxed<b> & { id?: = "a" }
                  b     => boxed<a> & { id?: = "b" }"""));
    }
}
