package io.ltr8.tson;

import io.ltr8.annotation.Tuple;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type written where it is used, end to end: a user schema writing container sugar at its fields resolves to the
 * construction held in place at each field, {@code { of: !meta.array { … } }}, with no entry added for it, and
 * reads data in tree and bind mode through readers built from each construction's body.
 */
class InlineTypeEndToEndTest {

    private static final String ID = "https://example.test/inline-types.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!import:"%s"
            !!import:"%s"
            !schema {
              order => {
                tags:   [text]
                prices: {text => [int32]}
                pair:   [text, int32]
                id:     ( int32 | text )
                labels: set<text>
                grid:   [[text]]
              }
              lines => {
                tags:   [text]
                prices: {text => [int32]}
                pair:   [text, int32]
                labels: set<text>
                grid:   [[text]]
              }
              nonempty_type => meta.array ^ { min_items?: = 1 }
              bag => set
              shapes => {
                ids:    nonempty_type<uuid>
                plain:  [uuid; 1..]
                bagged: bag<text>
                labels: set<text>
                owner:  meta.ref<order>
                nested: {text => nonempty_type<text>}
              }
            }""".formatted(ID, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static final String DATA = """
            !!import:"%s"
            !order {
              tags:   [a b]
              prices: { eur => [1 2]  usd => [] }
              pair:   [x 3]
              id:     7
              labels: [p q]
              grid:   [[a b] [c]]
            }""".formatted(ID);

    @Tuple
    public record Pair(String name, int count) {
    }

    public record Lines(List<String> tags, Map<String, List<Integer>> prices, Pair pair, Set<String> labels,
                        List<List<String>> grid) {
    }

    private static Tson tson() {
        SchemaSource source = uri -> SCHEMA;
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("lines", Lines.class)).orElse(SchemaModelNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
    }

    private static TypeInline inline(io.ltr8.tson.schema.meta.Type body) {
        return new TypeInline(body);
    }

    /** Each field holds the construction its sugar denotes, in place, and no entry is added for it. */
    @Test
    void everyFormResolvesToTheConstructionInPlace() {
        TsonSchema schema = tson().resolve(SCHEMA).schema();
        assertTrue(schema.entries().keySet().stream().noneMatch(name -> name.matches(".*_[0-9a-f]{8}")),
                "no entry is added for a form written in place");
        RecordBody order = assertInstanceOf(RecordBody.class, schema.entries().get("order"));

        assertEquals(inline(ArrayBody.of(TypeRef.of("text"))), order.fields().get("tags").type());
        assertEquals(inline(MapBody.of(TypeRef.of("text"), inline(ArrayBody.of(TypeRef.of("int32"))))),
                order.fields().get("prices").type());
        assertEquals(inline(new TupleBody(List.of(TupleElement.required(TypeRef.of("text")),
                TupleElement.required(TypeRef.of("int32"))))), order.fields().get("pair").type());
        ChoiceBody id = assertInstanceOf(ChoiceBody.class,
                assertInstanceOf(TypeInline.class, order.fields().get("id").type()).of());
        assertEquals(List.of(TypeRef.of("int32"), TypeRef.of("text")), id.variants());
        ArrayBody labels = assertInstanceOf(ArrayBody.class,
                assertInstanceOf(TypeInline.class, order.fields().get("labels").type()).of());
        assertTrue(labels.uniqueItems() && !labels.ordered(), "set<text> is the set constructor's array");
        assertEquals("meta.set_type", ((TypeInline) order.fields().get("labels").type()).constructor());
        assertEquals(inline(ArrayBody.of(inline(ArrayBody.of(TypeRef.of("text"))))),
                order.fields().get("grid").type());
    }

    /** A disjoint choice written in place is derived disjoint at its slot, so an untagged value is recovered. */
    @Test
    void anInlineChoiceIsDerivedDisjoint() {
        RecordBody order = (RecordBody) tson().resolve(SCHEMA).schema().entries().get("order");
        ChoiceBody id = (ChoiceBody) ((TypeInline) order.fields().get("id").type()).of();
        assertEquals(Optional.of(true), id.disjoint());
    }

    @Test
    void theDocumentReadsInTreeMode() {
        TsonValue order = tson().treeReader().read(DATA);
        assertEquals(Optional.of("b"), order.get("tags").get(1).asString());
        assertEquals(2, order.get("prices").get("eur").get(1).asInt().orElseThrow());
        assertEquals(3, order.get("pair").get(1).asInt().orElseThrow());
        assertEquals(7, order.get("id").asInt().orElseThrow());
        assertEquals(Optional.of("c"), order.get("grid").get(1).get(0).asString());
    }

    @Test
    void theDocumentReadsInBindMode() {
        String data = DATA.replace("!order", "!lines").replace("  id:     7\n", "");
        Lines lines = tson().objectReader().read(data, Lines.class);
        assertEquals(new Lines(List.of("a", "b"), Map.of("eur", List.of(1, 2), "usd", List.of()), new Pair("x", 3),
                Set.of("p", "q"), List.of(List.of("a", "b"), List.of("c"))), lines);
    }

    /**
     * Each type written in place records the constructor that built it, past its alias hops, and two constructions
     * of one body by two constructors are two types (§1.2).
     */
    @Test
    void eachTypeInPlaceRecordsItsConstructor() {
        RecordBody shapes = (RecordBody) tson().resolve(SCHEMA).schema().entries().get("shapes");
        assertEquals("nonempty_type", inlineAt(shapes, "ids").constructor());
        assertEquals("meta.array", inlineAt(shapes, "plain").constructor());
        assertEquals(inlineAt(shapes, "plain").of(), inlineAt(shapes, "ids").of());
        assertNotEquals(inlineAt(shapes, "plain"), inlineAt(shapes, "ids"));
        assertEquals("meta.set_type", inlineAt(shapes, "bagged").constructor());
        assertEquals(inlineAt(shapes, "labels"), inlineAt(shapes, "bagged"));
        assertEquals("meta.ref", inlineAt(shapes, "owner").constructor());
        MapBody nested = assertInstanceOf(MapBody.class, inlineAt(shapes, "nested").of());
        assertEquals("nonempty_type", assertInstanceOf(TypeInline.class, nested.valueType()).constructor());
        assertEquals("{text => nonempty_type<text>}", inlineAt(shapes, "nested").text());
    }

    /**
     * Resolved output writes each constructor as the tag on {@code of} -- a schema's own refined constructor, and
     * an aliased one past its alias -- and reading it back, in bind mode, keeps it: the record reads back equal.
     */
    @Test
    void theResolvedFormRoundTripsEachConstructorInBindMode() {
        Tson tson = tson();
        RecordBody shapes = (RecordBody) tson.resolve(SCHEMA).schema().entries().get("shapes");
        String written = tson.objectWriter().toTson(shapes);
        assertTrue(written.contains("\"ids\" => { type: { of: !nonempty_type {"), written);
        assertTrue(written.contains("\"plain\" => { type: { of: !meta.array {"), written);
        assertTrue(written.contains("\"bagged\" => { type: { of: !meta.set_type {"), written);
        assertTrue(written.contains("\"owner\" => { type: { of: !meta.ref {"), written);
        assertTrue(written.contains("value_type: { of: !nonempty_type {"), written);
        assertFalse(written.contains("!bag") || written.contains("!set ") || written.contains("meta.type_inline"),
                written);

        // Field by field: the record's own dump writes its empty `parameters` and `discriminators`, which a resolved
        // form omits and `meta.record` refuses.
        shapes.fields().forEach((field, declared) -> {
            String one = tson.objectWriter().toTson(declared);
            Object read = tson.objectReader().withImports(List.of(ID)).readAs(one, "meta.record_field", Object.class);
            assertEquals(declared, read, one);
            assertEquals(((TypeInline) declared.type()).constructor(),
                    ((TypeInline) ((io.ltr8.tson.schema.meta.RecordField) read).type()).constructor(), one);
        });
    }

    /** A constructor written through its alias is read as the entry the alias reaches, as resolution records it. */
    @Test
    void aConstructorTaggedThroughItsAliasReadsPastIt() {
        Object read = tson().objectReader().withImports(List.of(ID))
                .readAs("{ type: { of: !bag { element_type: text } } }", "meta.record_field", Object.class);
        TypeInline type = assertInstanceOf(TypeInline.class,
                assertInstanceOf(io.ltr8.tson.schema.meta.RecordField.class, read).type());
        assertEquals("meta.set_type", type.constructor());
    }

    /** Each field's resolved form reads in tree mode with no diagnostic, its {@code of} under its constructor's tag. */
    @Test
    void theResolvedFormReadsInTreeModeUnderEachConstructor() {
        Tson tson = tson();
        RecordBody shapes = (RecordBody) tson.resolve(SCHEMA).schema().entries().get("shapes");
        Map<String, String> constructors = Map.of("ids", "nonempty_type", "plain", "meta.array",
                "bagged", "meta.set_type", "owner", "meta.ref", "nested", "meta.map");
        constructors.forEach((field, constructor) -> {
            String written = tson.objectWriter().toTson(shapes.fields().get(field));
            List<Diagnostic> problems = new java.util.ArrayList<>();
            TsonValue read = tson.treeReader().withImports(List.of(ID)).withDiagnostics(problems::add)
                    .readAs(written, "meta.record_field");
            assertEquals(List.of(), problems, written);
            assertEquals(Optional.of(constructor), read.get("type").get("of").typeRef(), written);
        });
    }

    /** A refined constructor's construction in place refuses data by its own rule and is named by its application. */
    @Test
    void aRefinedConstructorInPlaceNamesItselfInARefusal() {
        String data = """
                !!import:"%s"
                !shapes { ids: []  plain: [%s]  bagged: []  labels: []  owner: o  nested: {} }"""
                .formatted(ID, "123e4567-e89b-12d3-a456-426614174000");
        List<Diagnostic> problems = tson().validate(data);
        assertTrue(problems.stream().anyMatch(d -> d.message().startsWith("'nonempty_type<uuid>'")),
                problems::toString);
    }

    /** The type written in place at a record field. */
    private static TypeInline inlineAt(RecordBody record, String field) {
        return assertInstanceOf(TypeInline.class, record.fields().get(field).type());
    }

    /** A value an inline type refuses is refused by the reader built from that type's body. */
    @Test
    void anInlineTypeRefusesWhatItsBodyRefuses() {
        ReadException refused = assertThrows(ReadException.class, () -> tson().treeReader().read(DATA
                .replace("grid:   [[a b] [c]]", "grid:   [a]")));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.diagnostic().code());
    }
}
