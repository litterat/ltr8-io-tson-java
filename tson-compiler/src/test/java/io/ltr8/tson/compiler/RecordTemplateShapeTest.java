package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordTemplateBody;
import io.ltr8.tson.schema.meta.RecordTemplateField;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Token;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.10's two kinds of template: a record template is a {@code record_template}, the family base its
 * declared applications are IS-A, whose fields label a parameter standing for a whole type or value, and every other
 * template is a held body. What is pinned here is where the template must not be mistaken for a closed record.
 */
class RecordTemplateShapeTest {

    private static final String ID = "https://example.test/record-template-shape.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/record-template-shape.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static List<Diagnostic> read(TsonCompiledSchema compiled, String rootType, String document) {
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        compiled.get(rootType).read(TestDocuments.document(document, problems));
        return List.copyOf(problems.diagnostics());
    }

    /** A record body with parameters is a record template; a container body is held text. */
    @Test
    void aRecordTemplateIsItsOwnShapeAndAContainerTemplateIsHeld() {
        TsonCompiledSchema compiled = compile("""
                  box   => <T> { v: T }
                  boxes => <T> [T]
                """);

        RecordTemplateBody box = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("box"));
        assertEquals(List.of("T"), box.parameterNames());
        assertTrue(OpenEntry.isFamilyBase(box));
        assertInstanceOf(TemplateBody.class, compiled.schema().entries().get("boxes"));
    }

    /**
     * A parameter standing for a whole type or value is labelled; one standing as an argument stays in {@code type},
     * recognised by §5.10's shadowing rule; a literal on a closed type stays a literal.
     */
    @Test
    void aWholeParameterIsLabelledAndAnArgumentIsNot() {
        TsonCompiledSchema compiled = compile("""
                  pair => <T, N, M> { a: T  b: [T]  c?: int32 = N  d?: text ~ "x"  e?: T ~ M }
                """);

        RecordTemplateBody pair = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("pair"));
        RecordTemplateField a = pair.fields().get("a");
        assertEquals(Optional.of("T"), a.typeParam());
        assertEquals(Optional.empty(), a.type());
        RecordTemplateField b = pair.fields().get("b");
        assertEquals(Optional.empty(), b.typeParam());
        assertTrue(OpenEntry.parameterIn(b.type().orElseThrow(), pair.parameterNames()).isPresent(),
                "the parameter rides an argument of the type: " + b.type());
        assertEquals(Optional.of("N"), pair.fields().get("c").valueParam());
        assertEquals(Optional.of("x"), pair.fields().get("d").value().map(Token::text));
        RecordTemplateField e = pair.fields().get("e");
        assertEquals(Optional.of("T"), e.typeParam());
        assertEquals(Optional.of("M"), e.valueParam());
    }

    /** A literal needs a closed type: a field typed by a parameter, whole or as an argument, takes none (§5.10). */
    @Test
    void aLiteralOnAParameterTypedFieldIsRefused() {
        String whole = assertThrows(SchemaValidationException.class, () -> compile("""
                  box => <T> { v?: T ~ 5 }
                """)).getMessage();
        String argument = assertThrows(SchemaValidationException.class, () -> compile("""
                  box  => <T> { v: T }
                  boxy => <T> { w?: box<T> = x }
                """)).getMessage();

        String valueArgument = assertThrows(SchemaValidationException.class, () -> compile("""
                  bounded => <M> !numeric.integer_type { max: M }
                  capped  => <M> { v?: bounded<M> ~ 5 }
                """)).getMessage();

        assertTrue(whole.contains("typed by the parameter 'T' and carries the literal value '5'"), whole);
        assertTrue(valueArgument.contains("typed by the parameter 'M' and carries the literal value '5'"),
                valueArgument);
        assertTrue(argument.contains("typed by the parameter 'T' and carries the literal value 'x'"), argument);
    }

    /** {@code record_template} is resolver vocabulary, as {@code template} is: no source declaration applies it (§8.1). */
    @Test
    void aRecordTemplateConstructionIsRefused() {
        String refused = assertThrows(SchemaValidationException.class, () -> compile("""
                  sneaky => !meta.record_template {
                    fields: { v => !meta.record_template_field { type_param: T } }
                    parameters: [{ name: T  type: meta.type_ref }]
                  }
                """)).getMessage();

        assertTrue(refused.contains("is resolver vocabulary -- the body of a record template"), refused);
    }

    /** An operand is flattened into the declaration, so a template there needs its arguments. */
    @Test
    void aTemplateNamedWithoutArgumentsIsNoOperand() {
        String composed = assertThrows(SchemaValidationException.class, () -> compile("""
                  box => <T> { v: T }
                  bad => box & { a: text }
                """)).getMessage();
        String refined = assertThrows(SchemaValidationException.class, () -> compile("""
                  box => <T> { v: T }
                  bad => box ^ { }
                """)).getMessage();

        assertTrue(composed.contains("supertype 'box' is a template and is applied to no arguments"), composed);
        assertTrue(refined.contains("refinement source 'box' is a template and is applied to no arguments"),
                refined);
    }

    /**
     * A record template that composes a member-dispatched base is IS-A it, and may pin the selector itself; it is
     * still no member. Only its declared application is, so a value pinned {@code "dog"} reads as {@code dogs}.
     */
    @Test
    void anOpenSubtypeIsNoMemberOfTheFamilyItComposes() {
        TsonCompiledSchema compiled = compile("""
                  pet    => abstract { type: text =?  name: text }
                  dog_of => <T> pet & { type: text = "dog"  tricks: T }
                  dogs   => dog_of<text>
                  owner  => { pet: pet }
                """);

        assertEquals(List.of(), read(compiled, "owner",
                "{ pet: { type: \"dog\"  name: \"rex\"  tricks: \"sit\" } }"));
    }
}
