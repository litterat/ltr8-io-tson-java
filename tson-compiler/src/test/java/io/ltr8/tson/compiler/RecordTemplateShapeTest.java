package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.RecordTemplateBody;
import io.ltr8.tson.schema.meta.RecordTemplateField;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.ValueEntry;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.10's two kinds of template: a record template is a {@code record_template}, whose fields label a
 * parameter standing for a whole type, and every other template is a held body; neither is a type. What is pinned
 * here is where the template must not be mistaken for a closed record.
 */
class RecordTemplateShapeTest {

    private static final String ID = "https://example.test/record-template-shape.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/record-template-shape.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
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
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
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
        assertTrue(OpenEntry.isOpen(box));
        assertInstanceOf(TemplateBody.class, compiled.schema().entries().get("boxes"));
    }

    /**
     * A parameter standing for a whole type is labelled; one standing as an argument stays in {@code type},
     * recognised by §5.10's shadowing rule; a literal on a closed type stays a literal.
     */
    @Test
    void aWholeParameterIsLabelledAndAnArgumentIsNot() {
        TsonCompiledSchema compiled = compile("""
                  pair => <T> { a: T  b: [T]  d?: text ~ "x" }
                """);

        RecordTemplateBody pair = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("pair"));
        RecordTemplateField a = pair.fields().get("a");
        assertEquals(Optional.of("T"), a.typeParam());
        assertEquals(Optional.empty(), a.type());
        RecordTemplateField b = pair.fields().get("b");
        assertEquals(Optional.empty(), b.typeParam());
        assertTrue(OpenEntry.parameterIn(RecordField.typeRef(b.type().orElseThrow()), pair.parameterNames()).isPresent(),
                "the parameter rides an argument of the type: " + b.type());
        assertEquals(Optional.of("x"), pair.fields().get("d").value().map(Token::text));
    }

    /**
     * A field's value is never a parameter, whatever the field's type (§5.10): a default or a pin naming one is
     * refused at the declaration, naming the slot -- in a fresh record body, in the explicit spelling, and in a
     * refinement template pinning a slot typed {@code meta.type_ref}.
     */
    @Test
    void aParameterAtAFieldsValueIsRefused() {
        String fresh = assertThrows(SchemaValidationException.class,
                () -> compile("  retry => <T, N> { t: T  attempts?: int32 ~ N }")).getMessage();
        String explicit = assertThrows(SchemaValidationException.class, () -> compile(
                "  retry => <T, N> !meta.record { fields: { t => T  attempts => { type: int32  optional: true  "
                        + "value: N } } }")).getMessage();
        String pinned = assertThrows(SchemaValidationException.class,
                () -> compile("  my_set => <T> meta.array ^ { element_type?: = T }")).getMessage();
        String composed = assertThrows(SchemaValidationException.class, () -> compile("""
                  base => { status: int32  body: text }
                  bad  => <T> base & { status?: = T  extra: T }
                """)).getMessage();

        assertTrue(fresh.contains("parameter 'N' stands at meta.record_field.value"), fresh);
        assertTrue(explicit.contains("parameter 'N' stands at meta.record_field.value"), explicit);
        assertTrue(pinned.contains("parameter 'T' stands at the value of field 'element_type'"), pinned);
        assertTrue(composed.contains("parameter 'T' stands at the value of field 'status'"), composed);
    }

    /**
     * A parameter is a type, so one standing at a facet, a size bound or an enum member is refused at the declaration,
     * naming the slot (§5.10).
     */
    @Test
    void aParameterAtAScalarSlotIsRefused() {
        String facet = assertThrows(SchemaValidationException.class,
                () -> compile("  vec => <N> !meta.array { element_type: text  min_items: N }")).getMessage();
        String bound = assertThrows(SchemaValidationException.class,
                () -> compile("  grid => <T, N> { cells: [T; N] }")).getMessage();
        String member = assertThrows(SchemaValidationException.class,
                () -> compile("  names => <M> !enum [a b M]")).getMessage();

        assertTrue(facet.contains("parameter 'N' stands at meta.array.min_items"), facet);
        assertTrue(bound.contains("parameter 'N' stands at a size bound (meta.array.min_items)"), bound);
        assertTrue(member.contains("parameter 'M' stands at enum.members"), member);
    }

    /** An argument is a type, written as a reference: a literal token is no argument (§5.10). */
    @Test
    void aLiteralTokenIsNoArgument() {
        String number = assertThrows(RuntimeException.class, () -> compile("""
                  box => <T> { v: T }
                  ten => box<10>
                """)).getMessage();
        String quoted = assertThrows(RuntimeException.class, () -> compile("""
                  box => <T> { v: T }
                  dog => box<"dog">
                """)).getMessage();

        assertTrue(number.contains("'10' is no type argument"), number);
        assertTrue(quoted.contains("'dog' is no type argument"), quoted);
    }

    /**
     * A field taken from a closed source is never relabelled: its literal {@code T} is the base's value, though the
     * template absorbing it declares a parameter spelled {@code T}. Only what the template itself writes is
     * classified by the shadowing rule (§5.10).
     */
    @Test
    void anInheritedLiteralSpelledLikeAParameterStaysALiteral() {
        TsonCompiledSchema compiled = compile("""
                  mode => !enum [T U]
                  base => { kind?: mode = T }
                  fam  => <T> base & { v: T }
                """);

        RecordTemplateBody fam = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("fam"));
        assertEquals(Optional.of("T"), fam.fields().get("kind").value().map(Token::text));
        assertEquals(Optional.of("T"), fam.fields().get("v").typeParam());
    }

    /**
     * A value that differs per application is written where the application is declared (§5.10): the member
     * composes the application and pins the field, and a value other than the pin is refused at the read.
     */
    @Test
    void aMemberPinsAFieldWhereTheApplicationIsDeclared() {
        TsonCompiledSchema compiled = compile("""
                  response => <T> { status: int32  body: T }
                  created  => response<text> & { status?: = 201 }
                """);

        assertEquals(List.of(), read(compiled, "created", "{ body: \"ok\" }"));
        assertEquals(List.of(Diagnostic.Code.FIELD_FIXED),
                read(compiled, "created", "{ status: 200  body: \"ok\" }").stream().map(Diagnostic::code).toList());
    }

    /**
     * An operand's arguments land where the operand's labels stand: reordered, the absorbing template's own parameters
     * land as its labels in the operand's order, and a closed argument lands as a type (§5.10).
     */
    @Test
    void anOperandsArgumentsLandAtItsLabels() {
        TsonCompiledSchema compiled = compile("""
                  pair  => <A, B> { first: A  second: B }
                  swap  => <A, B> pair<B, A> & { tag: text }
                  wrap  => <T> pair<T, text> & { w: T }
                  swapped => swap<int32, text>
                """);

        RecordTemplateBody swap = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("swap"));
        assertEquals(Optional.of("B"), swap.fields().get("first").typeParam());
        assertEquals(Optional.of("A"), swap.fields().get("second").typeParam());
        RecordTemplateBody wrap = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("wrap"));
        assertEquals(Optional.of(TypeRef.of("text")), wrap.fields().get("second").type());

        RecordBody swapped = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("swapped"));
        assertEquals(TypeRef.of("text"), swapped.fields().get("first").type());
        assertEquals(TypeRef.of("int32"), swapped.fields().get("second").type());
    }

    /** A member keeps an inherited literal spelled like its template's parameter: substitution reaches labels only. */
    @Test
    void aClosedMemberKeepsAnInheritedLiteralSpelledLikeAParameter() {
        TsonCompiledSchema compiled = compile("""
                  base => { kind?: text = T }
                  t1   => <T> base & { v: T }
                  u    => t1<int32>
                """);

        RecordBody u = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("u"));
        assertEquals(Optional.of("T"), u.fields().get("kind").value().map(Token::text));
        assertEquals(TypeRef.of("int32"), u.fields().get("v").type());
    }

    /**
     * An open operand's own parameters are replaced by what the absorbing template writes, and every other name its
     * fields write stays: {@code pair}'s {@code V} becomes {@code t4}'s {@code X}, and {@code text} stays {@code
     * text} (§5.10).
     */
    @Test
    void anOpenOperandsParametersAreReplacedByTheAbsorbersArguments() {
        TsonCompiledSchema compiled = compile("""
                  two  => <A, B> { a: A  b: B }
                  pair => <V> { z: two<V, text> }
                  t4   => <X> pair<X> & { x: X }
                  u4   => t4<int32>
                """);

        RecordBody u4 = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("u4"));
        assertEquals(TypeRef.of("int32"), u4.fields().get("x").type());
        assertEquals(List.of(), read(compiled, "u4", "{ z: { a: 1  b: \"t\" }  x: 2 }"));
    }

    /**
     * A parameter spelled like an entry the schema's namespace holds -- a type or a value entry, its own or an
     * import's -- is refused at the declaration (§5.10's shadowing rule), a record template and a held template alike.
     */
    @Test
    void aParameterShadowingASchemaEntryIsRefused() {
        String record = assertThrows(SchemaValidationException.class, () -> compile("""
                  T    => { q: int32 }
                  base => { f: T }
                  t2   => <T> base & { x: T }
                """)).getMessage();
        String held = assertThrows(SchemaValidationException.class, () -> compile("""
                  T  => { q: int32 }
                  ts => <T> [T]
                """)).getMessage();
        String imported = assertThrows(SchemaValidationException.class,
                () -> compile("  boxed => <text> { v: text }")).getMessage();

        assertTrue(record.contains("'t2': parameter 'T' shadows the entry 'T' this schema's namespace holds"), record);
        assertTrue(held.contains("'ts': parameter 'T' shadows the entry 'T'"), held);
        assertTrue(imported.contains("'boxed': parameter 'text' shadows the entry 'text'"), imported);

        String point = """
                  point  => { x: int32  y: int32 }
                  origin => !point { x: 0  y: 0 }
                """;
        assertInstanceOf(ValueEntry.class, compile(point).schema().entries().get("origin"));
        String value = assertThrows(SchemaValidationException.class,
                () -> compile(point + "  boxed => <origin> { v: origin }")).getMessage();
        assertTrue(value.contains("'boxed': parameter 'origin' shadows the entry 'origin'"), value);
    }

    /**
     * A positional field written with a parameter is labelled as its {@code type} would be: {@code item => T}, {@code
     * item => { type: T }} and the sugar {@code item: T} are one template, and an application closes {@code item} to
     * its argument.
     */
    @Test
    void aPositionalFieldWrittenWithAParameterIsItsTypeParameter() {
        TsonCompiledSchema compiled = compile("""
                  box   => <T> !meta.record { fields: { item => T } }
                  spelt => <T> !meta.record { fields: { item => { type: T } } }
                  sugar => <T> { item: T }
                  held  => box<int32>
                """);

        RecordTemplateBody box = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("box"));
        assertEquals(Optional.of("T"), box.fields().get("item").typeParam());
        assertEquals(box.fields(), ((RecordTemplateBody) compiled.schema().entries().get("spelt")).fields());
        assertEquals(box.fields(), ((RecordTemplateBody) compiled.schema().entries().get("sugar")).fields());
        RecordBody held = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("held"));
        assertEquals(TypeRef.of("int32"), held.fields().get("item").type());
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

        assertTrue(whole.contains("typed by the parameter 'T' and carries the literal value '5'"), whole);
        assertTrue(argument.contains("typed by the parameter 'T' and carries the literal value 'x'"), argument);
    }

    /** A record template's body that is no valid record is refused in the author's terms -- a record, as written. */
    @Test
    void anInvalidTemplateBodyIsRefusedAsTheRecordWritten() {
        String refused = assertThrows(SchemaValidationException.class,
                () -> compile("  box => <T> { @doc:{ a: 1 } v: T }")).getMessage();

        assertTrue(refused.contains("'box': the body is not valid data for 'meta.record'"), refused);
        assertFalse(refused.contains("record_template"), refused);
    }

    /** {@code record_template} is resolver vocabulary, as {@code template} is: no source declaration applies it (§8.1). */
    @Test
    void aRecordTemplateConstructionIsRefused() {
        String refused = assertThrows(SchemaValidationException.class, () -> compile("""
                  sneaky => !meta.record_template {
                    fields: { v => !meta.record_template_field { type_param: T } }
                    parameters: [{ name: T }]
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
     * A record template that composes a member-dispatched base may pin the selector itself, and is no member: a
     * template is no type. Its declared application is, reaching the base through the template's written parent, so
     * a value pinned {@code "dog"} reads as {@code dogs}.
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
