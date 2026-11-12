package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.10's template is an open record: a {@code meta.record} carrying {@code parameters}, each standing
 * at a field's type or as an argument of an application there or in a parent. What is pinned here is the open
 * record's shape, where it must not be mistaken for a closed record, and the bodies that are refused for being no
 * record.
 */
class OpenRecordShapeTest {

    private static final String ID = "https://example.test/open-record-shape.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/open-record-shape.tn"
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

    private static RecordBody open(TsonCompiledSchema compiled, String name) {
        RecordBody record = assertInstanceOf(RecordBody.class, compiled.schema().entries().get(name));
        assertTrue(record.isOpen(), name + " is an open record");
        return record;
    }

    /** A record body with parameters is an open record, a {@code meta.record} carrying them. */
    @Test
    void aTemplateIsARecordCarryingParameters() {
        RecordBody box = open(compile("  box => <T> { v: T }"), "box");

        assertEquals(List.of("T"), box.parameterNames());
        assertTrue(OpenEntry.isOpen(box));
        assertEquals(ParamRef.of("T"), box.fields().get("v").type());
    }

    /**
     * A parameter is a reference value: whole at a field's type, or at a slot of a type written in place there, and no
     * label says which. A container over a parameter is its construction in place, {@code [T]} the array whose element
     * is the parameter; a literal on a closed type stays a literal.
     */
    @Test
    void aParameterIsAReferenceWholeOrAtASlotInPlace() {
        RecordBody pair = open(compile("  pair => <T> { a: T  b: [T]  c: {text => T}  d?: text ~ \"x\" }"), "pair");

        assertEquals(ParamRef.of("T"), pair.fields().get("a").type());
        assertEquals(new TypeInline(ArrayBody.of(ParamRef.of("T"))), pair.fields().get("b").type());
        assertEquals(new TypeInline(MapBody.of(TypeRef.of("text"), ParamRef.of("T"))),
                pair.fields().get("c").type());
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

        assertTrue(fresh.contains("parameter 'N' stands at the value of field 'attempts'"), fresh);
        assertTrue(explicit.contains("parameter 'N' stands at meta.record_field.value"), explicit);
        assertTrue(pinned.contains("parameter 'T' stands at the value of field 'element_type'"), pinned);
        assertTrue(composed.contains("parameter 'T' stands at the value of field 'status'"), composed);
    }

    /** A size bound is a count and never a parameter (§5.10), refused naming the slot. */
    @Test
    void aParameterAtASizeBoundIsRefused() {
        String bound = assertThrows(SchemaValidationException.class,
                () -> compile("  grid => <T, N> { cells: [text; N]  t: T }")).getMessage();

        assertTrue(bound.contains("parameter 'N' stands at a size bound (meta.array.min_items)"), bound);
    }

    /**
     * A template's body is a record body (§5.10): before a container constructor, a container sugar form, a choice, a
     * tuple, a value, or a bare reference, a parameter list is refused at the declaration with what to write instead.
     */
    @Test
    void aTemplateBodyThatIsNoRecordIsRefused() {
        String constructor = assertThrows(SchemaValidationException.class,
                () -> compile("  rows => <T> !meta.array { element_type: T  min_items: 1 }")).getMessage();
        String sugar = assertThrows(SchemaValidationException.class,
                () -> compile("  rows => <T> [T]")).getMessage();
        String choice = assertThrows(SchemaValidationException.class,
                () -> compile("  result => <T> ( T | text )")).getMessage();
        String tuple = assertThrows(SchemaValidationException.class,
                () -> compile("  pair => <A, B> [A, B]")).getMessage();
        String alias = assertThrows(SchemaValidationException.class, () -> compile("""
                  pair      => <A, B> { a: A  b: B }
                  uuid_pair => <B> pair<uuid, B>
                """)).getMessage();
        String value = assertThrows(SchemaValidationException.class, () -> compile("""
                  api    => { get: meta.type_ref }
                  orders => <T> !api { get: T }
                """)).getMessage();

        assertTrue(constructor.contains("a template's body is a record body")
                && constructor.contains("declare a refined constructor"), constructor);
        assertTrue(sugar.contains("'[T]' is an array") && sugar.contains("meta.array<T>"), sugar);
        assertTrue(choice.contains("is a choice") && choice.contains("field group"), choice);
        assertTrue(tuple.contains("is a tuple"), tuple);
        assertTrue(alias.contains("alias template"), alias);
        assertTrue(value.contains("is a value of the type 'api'") && value.contains("one value per instantiation"),
                value);
    }

    /**
     * Inside an open record a form over a parameter is its construction in place, whatever else it sets (§5.10): a
     * size, a {@code ?} element, a choice and a tuple each load and close where the record is applied.
     */
    @Test
    void aFormOverAParameterIsItsConstructionWhateverElseItSets() {
        TsonCompiledSchema compiled = compile("""
                  page => <T> { items: [T; 1..]  maybe: [T?]  body: ( T | int32 )  at: [T, int32] }
                  text_page => page<text>""");

        assertEquals(List.of(), read(compiled, "text_page", "{ items: [a]  maybe: [_]  body: b  at: [c 1] }"));
        assertEquals(1, read(compiled, "text_page", "{ items: []  maybe: []  body: 1  at: [c 1] }").size());
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
     * A field taken from a closed source keeps its literal {@code T}, the base's value, though the template absorbing
     * it declares a parameter spelled {@code T}: a field's value is never a parameter.
     */
    @Test
    void anInheritedLiteralSpelledLikeAParameterStaysALiteral() {
        TsonCompiledSchema compiled = compile("""
                  mode => !enum [T U]
                  base => { kind?: mode = T }
                  fam  => <T> base & { v: T }
                """);

        RecordBody fam = open(compiled, "fam");
        assertEquals(Optional.of("T"), fam.fields().get("kind").value().map(Token::text));
        assertEquals(ParamRef.of("T"), fam.fields().get("v").type());
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
     * An operand's arguments land where its parameters stand: reordered, the absorbing template's own parameters land
     * as its parameters in the operand's order, and a closed argument lands as a type (§5.10).
     */
    @Test
    void anOperandsArgumentsLandWhereItsParametersStand() {
        TsonCompiledSchema compiled = compile("""
                  pair  => <A, B> { first: A  second: B }
                  swap  => <A, B> pair<B, A> & { tag: text }
                  wrap  => <T> pair<T, text> & { w: T }
                  swapped => swap<int32, text>
                """);

        RecordBody swap = open(compiled, "swap");
        assertEquals(ParamRef.of("B"), swap.fields().get("first").type());
        assertEquals(ParamRef.of("A"), swap.fields().get("second").type());
        assertEquals(TypeRef.of("text"), open(compiled, "wrap").fields().get("second").type());

        RecordBody swapped = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("swapped"));
        assertEquals(TypeRef.of("text"), swapped.fields().get("first").type());
        assertEquals(TypeRef.of("int32"), swapped.fields().get("second").type());
    }

    /** A member keeps an inherited literal spelled like its template's parameter: substitution reaches references. */
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

        assertEquals(TypeRef.of("two", List.of(ParamRef.of("X"), TypeRef.of("text"))),
                open(compiled, "t4").fields().get("z").type());
        RecordBody u4 = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("u4"));
        assertEquals(TypeRef.of("int32"), u4.fields().get("x").type());
        assertEquals(List.of(), read(compiled, "u4", "{ z: { a: 1  b: \"t\" }  x: 2 }"));
    }

    /**
     * A parameter spelled like an entry the schema's namespace holds -- a type or a value entry, its own or an
     * import's -- is refused at the declaration (§5.10's shadowing rule).
     */
    @Test
    void aParameterShadowingASchemaEntryIsRefused() {
        String record = assertThrows(SchemaValidationException.class, () -> compile("""
                  T    => { q: int32 }
                  base => { f: T }
                  t2   => <T> base & { x: T }
                """)).getMessage();
        String imported = assertThrows(SchemaValidationException.class,
                () -> compile("  boxed => <text> { v: text }")).getMessage();

        assertTrue(record.contains("'t2': parameter 'T' shadows the entry 'T' this schema's namespace holds"), record);
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
     * The explicit spelling is read against {@code record}'s own vocabulary as the sugar is: {@code item => T}, {@code
     * item => { type: T }} and {@code item: T} are one open record, and an application closes {@code item} to its
     * argument.
     */
    @Test
    void theExplicitSpellingIsTheSameOpenRecord() {
        TsonCompiledSchema compiled = compile("""
                  box   => <T> !meta.record { fields: { item => T } }
                  spelt => <T> !meta.record { fields: { item => { type: T } } }
                  sugar => <T> { item: T }
                  held  => box<int32>
                """);

        RecordBody box = open(compiled, "box");
        assertEquals(ParamRef.of("T"), box.fields().get("item").type());
        assertEquals(box.fields(), open(compiled, "spelt").fields());
        assertEquals(box.fields(), open(compiled, "sugar").fields());
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

    /** Parameters are written before the body, never as a binding of a construction of {@code record} (§5.10). */
    @Test
    void writtenParametersAreRefused() {
        String refused = assertThrows(SchemaValidationException.class, () -> compile("""
                  sneaky => !meta.record {
                    fields: { v => { type: T } }
                    parameters: [{ name: T }]
                  }
                """)).getMessage();

        assertTrue(refused.contains("states 'parameters', which a construction never writes"), refused);
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
     * An open record that composes a member-dispatched base may pin the selector itself, and is no member: a template
     * is no type. Its declared application is, reaching the base through the template's written parent, so a value
     * pinned {@code "dog"} reads as {@code dogs}.
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

    /**
     * A container over a parameter, held as the application it spells, closes when the record is applied: the field
     * of {@code page<int32>} holds the array written in place, the type a direct {@code [int32]} is, and reads as one.
     */
    @Test
    void aHeldContainerClosesToTheConstructionItsSugarDenotes() {
        TsonCompiledSchema compiled = compile("""
                  page  => <T> { items: [T] }
                  pages => page<int32>
                  plain => { items: [int32] }
                """);

        RecordBody pages = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("pages"));
        RecordBody plain = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("plain"));
        assertEquals(plain.fields().get("items").type(), pages.fields().get("items").type());
        assertEquals(List.of(), read(compiled, "pages", "{ items: [1 2 3] }"));
    }
}
