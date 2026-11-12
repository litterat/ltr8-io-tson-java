package io.ltr8.tson;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Param;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application is a construction, {@code meta.apply}, written in place ([TSON-SCHEMA] §5.10): a reference holds a
 * name only. In resolved output one stands only inside an open record, over one of its parameters, applying an open
 * record; the explicit spelling closes as the sugar does; the {@code meta.application} annotation is the same record.
 */
class ApplyConstructionTest {

    private static String schema(String declarations) {
        return """
                !!id:"https://example.test/apply.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                  box    => <T> { v: T }
                  pair   => <A, B> { a: A  b: B }
                  result => abstract <T> { ok: boolean =?  value?: T }
                  tree   => <T> { value: T  kids?: [tree<T>] }
                %s
                }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations);
    }

    private static Map<String, Top> entries(String declarations) {
        Map<String, Top> entries = new LinkedHashMap<>();
        var resolved = Tson.standard().resolve(schema(declarations)).schema();
        resolved.entries().keySet().forEach(name -> entries.put(name, resolved.entries().get(name)));
        return entries;
    }

    private static List<Diagnostic> problems(String declarations) {
        return Tson.standard().validateSchema(schema(declarations));
    }

    private static void refused(String declarations, String fragment) {
        List<Diagnostic> found = problems(declarations);
        assertEquals(1, found.size(), found::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, found.getFirst().code(), found::toString);
        assertTrue(found.getFirst().message().contains(fragment), found.getFirst().message());
    }

    private static TypeExprAt field(Map<String, Top> entries, String entry, String field) {
        return new TypeExprAt(((RecordBody) entries.get(entry)).fields().get(field).type());
    }

    private record TypeExprAt(io.ltr8.tson.schema.meta.TypeExpr type) {
    }

    /** Inside an open record an application over a parameter is the application written in place, in a parent too. */
    @Test
    void anApplicationOverAParameterIsAnApplyInPlace() {
        Map<String, Top> entries = entries("  page => <T> result<T> & { cursor: box<T>  more?: [pair<T text>] }");
        RecordBody page = (RecordBody) entries.get("page");
        ParamRef t = ParamRef.of("T");
        assertEquals(new ApplyBody(TypeRef.of("box"), List.of(t)).inPlace(), page.fields().get("cursor").type());
        ArrayBody more = assertInstanceOf(ArrayBody.class, ((TypeInline) page.fields().get("more").type()).of());
        assertEquals(new ApplyBody(TypeRef.of("pair"), List.of(t, TypeRef.of("text"))).inPlace(), more.elementType());
        assertEquals(List.of(new ApplyBody(TypeRef.of("result"), List.of(t)).inPlace()), page.parents());
    }

    /** The explicit spelling means what the sugar means, at a slot and inside an open record. */
    @Test
    void theExplicitSpellingClosesAsTheSugarDoes() {
        Map<String, Top> entries = entries("""
                  sugar    => { f: box<text>  g: [pair<text int32>] }
                  explicit => !meta.record { fields: {
                    f => !meta.record_field { type: { of: !meta.apply { head: box  arguments: [text] } } }
                    g => !meta.record_field { type: { of: !meta.array { element_type: { of: !meta.apply {
                      head: pair  arguments: [text int32] } } } } }
                  } }
                  open_sugar    => <T> { f: box<T> }
                  open_explicit => <T> !meta.record { fields: {
                    f => !meta.record_field { type: { of: !meta.apply { head: box  arguments: [T] } } }
                  } }""");
        assertEquals(field(entries, "sugar", "f"), field(entries, "explicit", "f"));
        assertEquals(field(entries, "sugar", "g"), field(entries, "explicit", "g"));
        assertInstanceOf(RecordBody.class, ((TypeInline) field(entries, "explicit", "f").type()).of());
        assertEquals(field(entries, "open_sugar", "f"), field(entries, "open_explicit", "f"));
    }

    /** A declaration naming an application records it on its key as the record {@code meta.apply}, each argument a name. */
    @Test
    void theAnnotationIsTheApplicationAsARecord() {
        TsonSchema resolved = Tson.standard().resolve(schema("  text_pair => pair<text int32>")).schema();
        assertEquals(Optional.of(ApplyBody.of("pair", "text", "int32")), resolved.applicationOf("text_pair"));
    }

    // ── Refusals ────────────────────────────────────────────────────────────────────────────────

    /**
     * A schema value's payload is data, where an argument list is no grammar ([TSON-DATA] §7.2.5): it is a syntax
     * error, under the base-syntax code and never the resolver's.
     */
    @Test
    void anArgumentListInASchemaValuesPayloadDoesNotParse() {
        List<Diagnostic> found = problems("  r => !meta.record { fields: { f => !meta.record_field { type: box<text> } } }");
        assertEquals(1, found.size(), found::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, found.getFirst().code(), found::toString);
    }

    /** {@code @meta.application} is derived, never written. */
    @Test
    void anAuthorWrittenApplicationAnnotationIsRefused() {
        assertTrue(!problems("  @meta.application:{ head: box  arguments: [text] }\n  x => box<text>").isEmpty());
    }

    /** An open record is named only as an application's head. */
    @Test
    void anOpenRecordIsNamedOnlyAsAHead() {
        refused("  r => { f: box }", "a template is not a type");
        refused("  r => { f: meta.ref<box> }", "box");
    }

    private static TsonSchema closedHolding(ApplyBody application, boolean open) {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("text", io.ltr8.tson.schema.unicode.TextType.UNCONSTRAINED);
        entries.put("box", new RecordBody(null, List.of(new Param("T")), RecordBody.fieldMap(Map.entry("v",
                RecordField.required(TypeRef.of("T")))), null, RecordExtensionType.OPEN, null, Optional.empty()));
        entries.put("r", new RecordBody(null, open ? List.of(new Param("U")) : List.of(), RecordBody.fieldMap(
                Map.entry("f", RecordField.required(application.inPlace())),
                Map.entry("u", RecordField.required(TypeRef.of(open ? "U" : "text")))), null,
                RecordExtensionType.OPEN, null, Optional.empty()));
        return new TsonSchema("https://example.test/held.tn", List.of(), AnnotatedMap.of(entries), false, Map.of(),
                Map.of());
    }

    /**
     * A resolved form is held to where an application may stand: outside an open record, or inside one over names
     * alone, it would have closed, and is refused.
     */
    @Test
    void anApplyWhereItWouldHaveClosedIsRefused() {
        SchemaValidationException closed = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(closedHolding(ApplyBody.of("box", "text"), false), null));
        assertTrue(closed.getMessage().contains("outside an open record"), closed.getMessage());
        SchemaValidationException overNames = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(closedHolding(ApplyBody.of("box", "text"), true), null));
        assertTrue(overNames.getMessage().contains("over names alone it closes"), overNames.getMessage());
        TsonSchemaLinker.link(closedHolding(new ApplyBody(TypeRef.of("box"), List.of(ParamRef.of("U"))), true),
                null);
    }

    // ── Final kernel constructors ───────────────────────────────────────────────────────────────

    /** {@code meta.apply} is final: nothing composes or refines it, and it still constructs. */
    @Test
    void metaApplyIsFinal() {
        refused("  x => meta.apply & { extra?: text }", "is final");
        refused("  x => meta.apply ^ {}", "is final");
        assertEquals(List.of(), problems("  r => !meta.record { fields: { f => !meta.record_field { type: { of: "
                + "!meta.apply { head: box  arguments: [text] } } } } }"));
    }

    /** {@code meta.dependent} is final too, and a dependent field reads as before, in sugar and written in place. */
    @Test
    void metaDependentIsFinal() {
        refused("  x => meta.dependent & { extra?: text }", "is final");
        refused("  x => meta.dependent ^ {}", "is final");
        assertEquals(List.of(), problems("  a => { kind: meta.type_ref  v?: kind! }"));
        assertEquals(List.of(), problems("  a => !meta.record { fields: { kind => !meta.record_field { type: "
                + "meta.type_ref }  v => !meta.record_field { type: { of: !meta.dependent { on: kind } }  "
                + "optional: true } } }"));
    }
}
