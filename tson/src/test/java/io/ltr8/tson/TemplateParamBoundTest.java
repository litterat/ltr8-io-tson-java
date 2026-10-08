package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.meta.TemplateBody;
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
 * A declaration's written parameter types ({@code <T: text, N>}, [TSON-SCHEMA] §5.10): read by the kind the
 * positions give -- a bound on a type parameter, a narrower {@code type} on a value parameter -- checked against
 * what the positions derive at the declaration, inherited through another template's argument list, and enforced
 * at the application.
 */
class TemplateParamBoundTest {

    private static final String HEADER = """
            !!id:"https://example.test/bounds-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            """;

    private static final String SCHEMA = HEADER + """
            {
              non_empty_text       => !text ^ { min_length: 1 }
              non_negative_integer => !integer ^ { min: 0 }
              boxed      => <T: text> { a: T }
              vec        => <T: text, N> !array { element_type: T  min_items: N }
              rebox      => <T> boxed<T>
              narrower   => <T: non_empty_text> boxed<T>
              counted    => <N: non_negative_integer> !array { element_type: text  min_items: N }
              typed_by   => <T, N> { w?: T ~ N }
            }
            """;

    private static Map<String, List<TemplateParam>> parameters() {
        Map<String, List<TemplateParam>> parameters = new LinkedHashMap<>();
        Tson.standard().resolve(SCHEMA).schema().entries().forEach((name, definition) -> {
            if (definition.body() instanceof TemplateBody held) {
                parameters.put(name, held.parameters());
            }
        });
        return parameters;
    }

    private static TemplateParam bounded(String name, String bound) {
        return new TemplateParam(name, TemplateParam.TYPE_REF, Optional.of(TypeRef.of(bound)));
    }

    private static List<Diagnostic> problems(String declarations) {
        return Tson.standard().validateSchema(HEADER + "{\n" + declarations + "\n}\n");
    }

    private static void assertRefused(String declarations, String fragment) {
        List<Diagnostic> refused = problems(declarations);
        assertEquals(1, refused.size(), refused.toString());
        assertTrue(refused.getFirst().message().contains(fragment), refused.getFirst().message());
    }

    // ── Recorded ───────────────────────────────────────────────────────────

    @Test
    void aRecordTemplatesWrittenTypeIsATypeParametersBound() {
        assertEquals(List.of(bounded("T", "text")), parameters().get("boxed"));
    }

    @Test
    void anInstanceConstructionNarrowsOneParameterAndKeepsTheOthersDerivedType() {
        assertEquals(List.of(bounded("T", "text"), new TemplateParam("N", TypeRef.of("non_negative_integer"))),
                parameters().get("vec"));
    }

    @Test
    void anUnannotatedParameterInheritsTheBoundOfTheTemplateItIsPassedTo() {
        assertEquals(List.of(bounded("T", "text")), parameters().get("rebox"));
    }

    @Test
    void aWrittenBoundMayNarrowAnInheritedOne() {
        assertEquals(List.of(bounded("T", "non_empty_text")), parameters().get("narrower"));
    }

    /** Core's non_negative_integer copies the kernel's, which the slot is typed by: the same type, so it narrows. */
    @Test
    void aValueParametersWrittenTypeNarrowsItsType() {
        assertEquals(List.of(new TemplateParam("N", TypeRef.of("non_negative_integer"))), parameters().get("counted"));
    }

    // ── Refused at the declaration ─────────────────────────────────────────

    @Test
    void aWrittenBoundThatWidensAnInheritedOneIsRefused() {
        assertRefused("""
                  boxed => <T: text> { a: T }
                  wrong => <T: int32> boxed<T>""", "needs a type that IS-A text");
    }

    @Test
    void aWrittenValueTypeThePositionsDoNotReadAsIsRefused() {
        assertRefused("  bad => <N: text> !array { element_type: text  min_items: N }",
                "read it as non_negative_integer");
    }

    @Test
    void aWrittenTypeMustNameAType() {
        assertRefused("  bad => <T: [text]> { a: T }", "names a declared type");
    }

    /**
     * A bound names a type, in the type-name namespace. A name found only among the governing meta's constructors
     * is structure vocabulary (§3.3.1), which no argument could satisfy -- a type argument names a type.
     */
    @Test
    void aWrittenBoundNamesATypeInTheTypeNamespace() {
        assertRefused("  boxed => <T: text_type> { a: T }", "'text_type' is a constructor of the governing meta");
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
     * one at a field, though it closes before the template's parameters are stamped.
     */
    @Test
    void aBoundIsEnforcedOnAnApplicationClosedDuringResolution() {
        String boxed = "  boxed  => <T: text> { a: T }\n";
        assertRefused(boxed + "  held   => boxed<int32>", "binds 'T' to 'int32', which is not a type that IS-A text");
        assertRefused(boxed + "  held   => boxed<int32> & { b: text }", "binds 'T' to 'int32'");
        assertRefused(boxed + "  held   => boxed<boxed<int32>>", "binds 'T' to 'int32'");
        assertRefused(boxed + "  rebox  => <T> boxed<T>\n  held   => rebox<int32>", "'rebox<...>' binds 'T' to 'int32'");
        assertEquals(List.of(), problems(boxed + "  short  => !text ^ { max_length: 8 }\n  held   => boxed<short>"));
    }

    @Test
    void anInheritedBoundIsEnforcedAtTheOuterApplication() {
        assertRefused("""
                  boxed  => <T: text> { a: T }
                  rebox  => <T> boxed<T>
                  holder => { b: rebox<int32> }""", "'rebox<...>' binds 'T' to 'int32'");
    }

    @Test
    void aValueArgumentMustBeAValueOfTheParametersType() {
        assertRefused("""
                  vec    => <T, N> !array { element_type: T  min_items: N }
                  holder => { v: vec<text, "two"> }""", "binds 'N' to 'two', which is not a value of "
                + "non_negative_integer");
    }

    @Test
    void aValueTypedByAnEarlierParameterIsReadAsThatParametersArgument() {
        assertEquals(List.of(), problems("""
                  typed_by => <T, N> { w?: T ~ N }
                  holder   => { t: typed_by<int32, 5> }"""));
        assertRefused("""
                  typed_by => <T, N> { w?: T ~ N }
                  holder   => { t: typed_by<int32, "five"> }""", "not a value of int32");
    }
}
