package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record written in place, end to end: an application of an open record at a type slot closes to the substituted
 * record with no name ([TSON-SCHEMA] §5.10), and a value there is read, written and bound by a reader built from that
 * record's body, its field states those of the open record, with no tag admitted (§7.2), named in a message by the
 * application as written, and bound in bind mode to the record class the component holding it declares.
 */
class RecordInPlaceEndToEndTest {

    private static final String ID = "https://example.test/records-in-place.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!import:"%s"
            !!import:"%s"
            !schema {
              order      => { id: text }
              page       => <T> { items: [T]  size?: int32 ~ 10  kind?: text = "page"  ( first: T | last: T )? }
              order_page => page<order>
              json_name  => !unicode.identifier_type { start_add: "@$" }
              node       => <T> !meta.record { name_type: json_name  fields: { "@id" => { type: text }  v => { type: T } } }
              setting    => <T> { kind: meta.type_ref  value: kind!  note: T }
              holder => {
                p:       page<order>
                ps?:     [page<order>]
                byName?: {text => page<order>}
              }
              linked  => { n: node<int32> }
              settled => { s: setting<text> }
            }""".formatted(ID, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    public record Order(String id) {
    }

    public record Page(List<Order> items, int size, Order first, Order last) {
    }

    public record Holder(Page p, List<Page> ps, Map<String, Page> byName) {
    }

    /** A component holding a record in place with a class that is no record. */
    public record Flat(String p, List<String> ps, Map<String, String> byName) {
    }

    private static Tson tson(Map<String, Class<?>> bound) {
        SchemaSource source = uri -> SCHEMA;
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(bound).orElse(SchemaModelNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
    }

    private static Tson tson() {
        return tson(Map.of("order", Order.class, "holder", Holder.class));
    }

    private static String document(String root, String body) {
        return "!!import:\"" + ID + "\"\n!" + root + " " + body;
    }

    private static List<Diagnostic> validate(String root, String body) {
        return tson().validate(document(root, body));
    }

    /** The field holds the record in place, closed and nameless, and the schema holds no entry for it. */
    @Test
    void theApplicationIsTheRecordInPlace() {
        RecordBody holder = (RecordBody) tson().resolve(SCHEMA).schema().entries().get("holder");
        TypeInline p = assertInstanceOf(TypeInline.class, holder.fields().get("p").type());
        assertEquals("page<order>", p.text());
        assertEquals(tson().resolve(SCHEMA).schema().entries().get("order_page"), p.of(),
                "the shape order_page declares, under no name");
    }

    @Test
    void aValueReadsInTreeModeWithTheRecordsDefaultsAndPins() {
        TsonValue holder = tson().treeReader().read(document("holder", "{ p: { items: [ { id: a } ]  first: { id: a } } }"));
        TsonValue p = holder.get("p");
        assertEquals(Optional.of("a"), p.get("items").get(0).get("id").asString());
        assertEquals(10, p.get("size").asInt().orElseThrow(), "the default supplied");
        assertEquals(Optional.of("page"), p.get("kind").asString(), "the pin supplied");
        assertEquals(Optional.empty(), p.typeRef(), "a value at a record in place carries no tag");
    }

    @Test
    void aMissingRequiredFieldIsLocatedByItsPathAndNamedByTheApplication() {
        List<Diagnostic> problems = validate("holder", "{ p: { size: 3 } }");
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of("/p/items"), problems.getFirst().path());
        assertTrue(problems.getFirst().message().contains("page<order>"), problems.getFirst().message());
    }

    @Test
    void aWrongFieldAnUnknownFieldAndAPinAreJudgedAsInADeclaredRecord() {
        assertEquals(Optional.of("/p/size"), only(validate("holder", "{ p: { items: []  size: 4294967296 } }")).path());
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD,
                only(validate("holder", "{ p: { items: []  colour: red } }")).code());
        assertEquals(Optional.of("/p/kind"), only(validate("holder", "{ p: { items: []  kind: book } }")).path());
    }

    @Test
    void aFieldGroupOfTheOpenRecordIsEachRecordInPlaces() {
        assertEquals(List.of(), validate("holder", "{ p: { items: []  last: { id: z } } }"));
        assertEquals(1, validate("holder", "{ p: { items: []  first: { id: a }  last: { id: z } } }").size());
    }

    /** A tag at a position whose type is in place is refused, a declared record of the same shape included (§7.2). */
    @Test
    void aTagAtARecordInPlaceIsRefused() {
        Diagnostic refused = only(validate("holder", "{ p: !order_page { items: [] } }"));
        assertEquals(Optional.of("/p"), refused.path());
    }

    @Test
    void aRecordInPlaceInAListAndAtAMapValueReads() {
        assertEquals(List.of(), validate("holder",
                "{ p: { items: [] }  ps: [ { items: [] } { items: [ { id: x } ] } ]  byName: { a => { items: [] } } }"));
        assertEquals(Optional.of("/ps/1/items/0/colour"), only(validate("holder",
                "{ p: { items: [] }  ps: [ { items: [] } { items: [ { id: x  colour: red } ] } ] }")).path());
    }

    /** The open record's name type is the record in place's: a name it admits reads, and one under the default. */
    @Test
    void aRecordInPlaceJudgesItsNamesByItsOpenRecordsNameType() {
        assertEquals(List.of(), validate("linked", "{ n: { \"@id\": a  v: 1 } }"));
    }

    @Test
    void aDependentFieldInARecordInPlaceIsReadAgainstItsSibling() {
        assertEquals(List.of(), validate("settled", "{ s: { kind: int32  value: 7  note: x } }"));
        assertEquals(Optional.of("/s/value"), only(validate("settled", "{ s: { kind: int32  value: seven  note: x } }"))
                .path());
    }

    /**
     * In bind mode a record in place binds to the record class the component holding it declares -- at a field, an
     * element and a map's value -- each field bound to that class's component as a declared record's is.
     */
    @Test
    void aValueBindsToTheComponentsRecordClass() {
        Holder holder = tson().objectReader().read(document("holder",
                "{ p: { items: [ { id: a } ] }  ps: [ { items: []  size: 2 } ]  byName: { k => { items: [] } } }"),
                Holder.class);
        assertEquals(new Holder(new Page(List.of(new Order("a")), 10, null, null),
                List.of(new Page(List.of(), 2, null, null)), Map.of("k", new Page(List.of(), 10, null, null))), holder);
    }

    /** A bound value a refusal reached builds nothing, as any bound record does. */
    @Test
    void aRefusedValueBindsNothing() {
        List<Diagnostic> problems = new java.util.ArrayList<>();
        Holder holder = tson().objectReader().withDiagnostics(problems::add)
                .read(document("holder", "{ p: { size: 3 } }"), Holder.class);
        assertNull(holder);
        assertEquals(Optional.of("/p/items"), only(problems).path());
    }

    /**
     * A component whose class is no record cannot hold a record in place: a wiring mistake found where the reader is
     * built, before any value is read.
     */
    @Test
    void aComponentThatIsNoRecordClassIsAMismatch() {
        RuntimeException mismatch = assertThrows(RuntimeException.class,
                () -> tson(Map.of("order", Order.class, "holder", Flat.class)).objectReader()
                        .read(document("holder", "{ p: { items: [] } }"), Flat.class));
        assertTrue(mismatch.getMessage().contains("is a record written in place"), mismatch.getMessage());
    }

    /** A bound value writes untagged at the record in place and reads back equal. */
    @Test
    void aBoundValueWritesAndReadsBack() {
        Tson tson = tson();
        Holder holder = new Holder(new Page(List.of(new Order("a")), 3, new Order("a"), null),
                List.of(new Page(List.of(), 10, null, null)), Map.of("k", new Page(List.of(), 1, null, new Order("z"))));
        String written = tson.objectWriter().toTson(holder);
        assertEquals(holder, tson.objectReader().read(document("holder", written), Holder.class), written);
    }

    // ── A record body written at a type slot (§5.2) ─────────────────────────────────────────────

    private static final String WRITTEN_ID = "https://example.test/records-written-in-place.tn";

    private static final String WRITTEN = """
            !!id:"%s"
            !!import:"%s"
            !!import:"%s"
            !schema {
              note => text
              xy   => <T> { x: T  y: T }
              shipment => {
                at:      { x: int32  y: int32 }
                again?:  { x: int32  y: int32 }
                closed?: xy<int32>
                lines:   [{ @note:"a line" sku: text  qty: int32 }]
                byName?: {text => { n: int32 }}
                by:      ( text | { name: text  email: text } )
                pair?:   [{ a: int32 }, text]
                empty?:  {}
                deep?:   { a: { b: { c: int32 } } }
                picked?: { ( p: int32 | q: text )  w?: text ~ "w" }
                cell?:   { kind: meta.type_ref  v?: kind! }
              }
              placed => {
                at:      { x: int32  y: int32 }
                closed?: xy<int32>
                lines:   [{ sku: text  qty: int32 }]
              }
            }""".formatted(WRITTEN_ID, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    public record Point(int x, int y) {
    }

    public record Line(String sku, int qty) {
    }

    public record Placed(Point at, Point closed, List<Line> lines) {
    }

    private static Tson written() {
        SchemaSource source = uri -> WRITTEN;
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("placed", Placed.class))
                        .orElse(SchemaModelNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
    }

    private static final String SHIPPED = "at: { x: 1  y: 2 }  lines: [ { sku: a  qty: 1 } ]  by: b";

    private static List<Diagnostic> shipped(String more) {
        return written().validate("!!import:\"" + WRITTEN_ID + "\"\n!shipment { " + SHIPPED + "  " + more + " }");
    }

    private static TypeInline writtenAt(String field) {
        RecordBody shipment = (RecordBody) written().resolve(WRITTEN).schema().entries().get("shipment");
        return assertInstanceOf(TypeInline.class, shipment.fields().get(field).type());
    }

    /** The body at a slot is the record in place, named by its form, and the schema holds no entry for it. */
    @Test
    void aRecordBodyAtASlotIsTheRecordInPlace() {
        TypeInline at = writtenAt("at");
        assertEquals(new TypeInline("meta.record", RecordBody.of(RecordBody.fieldMap(
                Map.entry("x", RecordField.required(TypeRef.of("int32"))),
                Map.entry("y", RecordField.required(TypeRef.of("int32")))))), at);
        assertEquals("{ x: int32  y: int32 }", at.text());
    }

    /** Two equal bodies are one type, and one equal to what an application closes to is that application's type. */
    @Test
    void equalBodiesAreOneTypeAndOneWithAnApplicationsRecord() {
        assertEquals(writtenAt("at"), writtenAt("again"));
        assertEquals(writtenAt("at"), writtenAt("closed"));
    }

    /** A field's annotations resolve in the schema's namespace and land on the field, as a declared field's do. */
    @Test
    void aFieldsAnnotationsLandOnTheField() {
        ArrayBody lines = assertInstanceOf(ArrayBody.class, writtenAt("lines").of());
        RecordBody line = (RecordBody) ((TypeInline) lines.elementType()).of();
        assertEquals(Optional.of("a line"), line.fields().get("sku").annotations().value("note", String.class));
    }

    @Test
    void aValueAtEachSlotReadsInTreeMode() {
        assertEquals(List.of(), shipped("byName: { k => { n: 1 } }  pair: [ { a: 1 } t ]  empty: {}  "
                + "deep: { a: { b: { c: 3 } } }  picked: { p: 1 }  cell: { kind: int32  v: 7 }"));
        assertEquals(List.of(), written().validate("!!import:\"" + WRITTEN_ID + "\"\n!shipment { at: { x: 1  y: 2 }  "
                + "lines: []  by: { name: n  email: e } }"));
        TsonValue read = written().treeReader().read("!!import:\"" + WRITTEN_ID + "\"\n!shipment { " + SHIPPED
                + "  picked: { q: z } }");
        assertEquals(Optional.of("w"), read.get("picked").get("w").asString(), "the default supplied");
    }

    /** A refusal inside a record in place is located by its path, at every slot, as in a declared record. */
    @Test
    void aRefusalInsideARecordInPlaceIsLocatedByItsPath() {
        assertEquals(Optional.of("/at/x"), only(written().validate("!!import:\"" + WRITTEN_ID + "\"\n!shipment { "
                + "at: { x: one  y: 2 }  lines: []  by: b }")).path());
        assertEquals(Optional.of("/lines/0/qty"), only(written().validate("!!import:\"" + WRITTEN_ID
                + "\"\n!shipment { at: { x: 1  y: 2 }  lines: [ { sku: a } ]  by: b }")).path());
        assertEquals(Optional.of("/byName/k/n"), only(shipped("byName: { k => { n: z } }")).path());
        assertEquals(Optional.of("/pair/0/a"), only(shipped("pair: [ { a: z } t ]")).path());
        assertEquals(Optional.of("/deep/a/b/c"), only(shipped("deep: { a: { b: { c: z } } }")).path());
        assertEquals(Optional.of("/cell/v"), only(shipped("cell: { kind: int32  v: seven }")).path());
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, only(shipped("empty: { a: 1 }")).code());
        assertEquals(1, shipped("picked: { p: 1  q: z }").size());
    }

    /** A diagnostic inside a record in place carries the line its field was written on, inside a container too. */
    @Test
    void aRefusalInsideARecordInPlaceIsPositionedAtItsField() {
        Diagnostic deep = only(shipped("deep: { a: { b: { c: z } } }"));
        assertEquals(16, deep.schemaPosition().orElseThrow().line(), deep::toString);
        assertEquals(26, deep.schemaPosition().orElseThrow().column(), deep::toString);
        Diagnostic line = only(written().validate("!!import:\"" + WRITTEN_ID + "\"\n!shipment { at: { x: 1  y: 2 }  "
                + "lines: [ { sku: a  qty: z } ]  by: b }"));
        assertEquals(11, line.schemaPosition().orElseThrow().line(), line::toString);
    }

    /** In bind mode a record in place binds to the component's record class, at a field and an element. */
    @Test
    void aRecordInPlaceBindsByTheComponentHoldingIt() {
        Placed placed = written().objectReader().read("!!import:\"" + WRITTEN_ID + "\"\n!placed { at: { x: 1  y: 2 }  "
                + "closed: { x: 3  y: 4 }  lines: [ { sku: a  qty: 1 } ] }", Placed.class);
        assertEquals(new Placed(new Point(1, 2), new Point(3, 4), List.of(new Line("a", 1))), placed);
    }

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }
}
