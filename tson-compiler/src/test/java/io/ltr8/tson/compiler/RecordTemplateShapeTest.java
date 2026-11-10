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
 * [TSON-SCHEMA] §5.10's two kinds of template: a record template is a {@code record_template}, the family base its
 * declared applications are IS-A, whose fields label a parameter standing for a whole type or value, and every other
 * template is a held body. What is pinned here is where the template must not be mistaken for a closed record.
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
        assertTrue(OpenEntry.parameterIn(RecordField.typeRef(b.type().orElseThrow()), pair.parameterNames()).isPresent(),
                "the parameter rides an argument of the type: " + b.type());
        assertEquals(Optional.of("N"), pair.fields().get("c").valueParam());
        assertEquals(Optional.of("x"), pair.fields().get("d").value().map(Token::text));
        RecordTemplateField e = pair.fields().get("e");
        assertEquals(Optional.of("T"), e.typeParam());
        assertEquals(Optional.of("M"), e.valueParam());
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
        assertEquals(Optional.empty(), fam.fields().get("kind").valueParam());
        assertEquals(Optional.of("T"), fam.fields().get("v").typeParam());
    }

    /**
     * An application still open where a value parameter stands -- an operand absorbing {@code pin<box<T>>} -- is
     * refused at the declaration that wrote it (§5.10): a value parameter's argument is a token.
     */
    @Test
    void anApplicationArgumentForAValueParameterIsRefused() {
        String refused = assertThrows(SchemaValidationException.class, () -> compile("""
                  box  => <T> { v: T }
                  pin  => <N> { n?: text = N }
                  bad  => <T> pin<box<T>> & { w: T }
                """)).getMessage();

        assertTrue(refused.contains("binds the value parameter 'N' to 'box<T>', an application"), refused);
    }

    /**
     * A literal argument is refused where its parameter's type is still open, wherever the application stands --
     * an operand or a field's type -- naming the argument and the open type (§5.10). Applied with a closed type the
     * same literal is the member's pin, and an out-of-range one is refused against the parameter's type.
     */
    @Test
    void aLiteralArgumentWhoseTypeIsStillOpenIsRefusedAtTheApplication() {
        String pin = "  pin2 => <T, P> { a?: T = P }\n";
        String operand = assertThrows(SchemaValidationException.class,
                () -> compile(pin + "  w => <X> pin2<X, 3> & { z: int32 }")).getMessage();
        String field = assertThrows(SchemaValidationException.class,
                () -> compile(pin + "  w => <X> { f: pin2<X 3>  z: int32 }")).getMessage();
        for (String refused : List.of(operand, field)) {
            assertTrue(refused.contains("binds 'P' to the literal '3', whose type is 'X', still open"), refused);
        }

        TsonCompiledSchema closed = compile(pin + "  ok => pin2<int8, 3>");
        assertEquals(List.of(), read(closed, "ok", "{ a: 3 }"));
        assertEquals(List.of(Diagnostic.Code.FIELD_FIXED),
                read(closed, "ok", "{ a: 4 }").stream().map(Diagnostic::code).toList());
        assertThrows(SchemaValidationException.class, () -> compile(pin + "  big => pin2<int8, 300>"));
    }

    /**
     * An operand's arguments land where the operand's labels stand: reordered, the absorbing template's own parameters
     * land as its labels in the operand's order; a closed argument lands as a type or a literal; and a literal for a
     * value parameter whose field type is closed is that field's value (§5.10).
     */
    @Test
    void anOperandsArgumentsLandAtItsLabels() {
        TsonCompiledSchema compiled = compile("""
                  pair  => <A, B> { first: A  second: B }
                  swap  => <A, B> pair<B, A> & { tag: text }
                  pv    => <A, N> { first: A  n?: int32 = N }
                  mixed => <T> pv<T, 7> & { z: text }
                  wrap  => <T> pair<T, text> & { w: T }
                  swapped => swap<int32, text>
                """);

        RecordTemplateBody swap = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("swap"));
        assertEquals(Optional.of("B"), swap.fields().get("first").typeParam());
        assertEquals(Optional.of("A"), swap.fields().get("second").typeParam());
        RecordTemplateBody mixed = assertInstanceOf(RecordTemplateBody.class, compiled.schema().entries().get("mixed"));
        assertEquals(Optional.of("T"), mixed.fields().get("first").typeParam());
        assertEquals(Optional.of("7"), mixed.fields().get("n").value().map(Token::text));
        assertEquals(Optional.empty(), mixed.fields().get("n").valueParam());
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
     * A record template's parameter spelled like a token an absorbed field's type writes among an application's
     * arguments is refused at the template (§5.10): {@code base}'s {@code f: pick<T>} applies {@code pick} to the
     * member {@code T}, and inside {@code t2} a parameter spelled {@code T} would be read first.
     */
    @Test
    void aParameterSpelledLikeAnAbsorbedArgumentIsRefused() {
        String refused = assertThrows(SchemaValidationException.class, () -> compile("""
                  mode => !enum [T U]
                  pick => <M> { m?: mode ~ M }
                  base => { f: pick<T> }
                  t2   => <T> base & { x: T }
                """)).getMessage();

        assertTrue(refused.contains("'t2': parameter 'T' is spelled like 'T' in the field 'f: pick<T>' it absorbs "
                + "from 'base'"), refused);
        assertTrue(refused.contains("Rename the parameter"), refused);
    }

    /**
     * The same of an open operand's field, its own parameters aside: {@code pair}'s {@code V} is replaced by what
     * {@code t3} writes, and its {@code T} is the member. {@code pair} itself is valid, and so is an absorber whose
     * parameter is spelled otherwise (§5.10).
     */
    @Test
    void aParameterSpelledLikeAnOperandsArgumentIsRefused() {
        String operands = """
                  mode => !enum [T U]
                  two  => <A, M> { a: A  m?: mode ~ M }
                  pair => <V> { z: two<V, T> }
                """;
        String refused = assertThrows(SchemaValidationException.class,
                () -> compile(operands + "  t3 => <T> pair<T> & { x: T }")).getMessage();

        assertTrue(refused.contains("'t3': parameter 'T' is spelled like 'T' in the field 'z: two<V, T>' it absorbs "
                + "from 'pair'"), refused);
        TsonCompiledSchema compiled = compile(operands + "  t4 => <X> pair<X> & { x: X }\n  u4 => t4<text>");
        RecordBody u4 = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("u4"));
        assertEquals(TypeRef.of("text"), u4.fields().get("x").type());
        assertEquals(List.of(), read(compiled, "u4", "{ z: { a: \"a\"  m: T }  x: \"b\" }"));
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

        String valueArgument = assertThrows(SchemaValidationException.class, () -> compile("""
                  bounded => <M> !numeric.integer_type { max: M }
                  capped  => <M> { v?: bounded<M> ~ 5 }
                """)).getMessage();

        assertTrue(whole.contains("typed by the parameter 'T' and carries the literal value '5'"), whole);
        assertTrue(valueArgument.contains("typed by the parameter 'M' and carries the literal value '5'"),
                valueArgument);
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
