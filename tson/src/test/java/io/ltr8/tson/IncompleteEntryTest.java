package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.IncompleteEntry;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonArray;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incomplete entries ([TSON-SCHEMA] §5.5): a construction of a constructor of types left open, refined by fields it
 * has not written, completed into the plain construction, refused wherever a type is named and left out of the
 * resolved form; and the refinement of an array or a map type by its facets' kinds (§5.7).
 */
class IncompleteEntryTest {

    private static final String LIB = "https://example.test/open.tn";
    private static final String USE = "https://example.test/use.tn";

    private static final Map<String, String> DOCUMENTS = Map.of(LIB, """
            !!id:"https://example.test/open.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              nonempty => !meta.array ^ { min_items: 1 }
              keyed    => !meta.map ^ { key_type: text }
            }
            """, USE, """
            !!id:"https://example.test/use.tn"
            !!import:"https://example.test/open.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              few    => !nonempty ^ { max_items: 2 }
              tags   => few<text>
              counts => keyed<int32>
              holder => { names: nonempty<text> }
            }
            """);

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(DOCUMENTS))));
    }

    private static String schema(String body) {
        return """
                !!id:"https://example.test/s.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                """ + body + "\n}\n";
    }

    private static TsonLinkedSchema resolved(String body) {
        return tson().resolve(schema(body));
    }

    /** The one problem {@code body} has, whose message holds {@code expected}. */
    private static void refused(String body, String expected) {
        List<Diagnostic> problems = tson().validateSchema(schema(body));
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems.toString());
        assertTrue(problems.getFirst().message().contains(expected), problems.getFirst().message());
    }

    // ── The forms ────────────────────────────────────────────────────────────

    @Test
    void aConstructionLeftOpenIsAnIncompleteEntryAndNoType() {
        TsonLinkedSchema linked = resolved("nonempty => !meta.array ^ { min_items: 1 }");

        IncompleteEntry nonempty = assertInstanceOf(IncompleteEntry.class, linked.schema().entries().get("nonempty"));
        assertEquals("meta.array", nonempty.constructor());
        assertEquals(List.of("element_type"), nonempty.absent());
    }

    @Test
    void aCompletionIsThePlainConstructionAndCarriesNoChain() {
        TsonLinkedSchema linked = resolved("""
                nonempty => !meta.array ^ { min_items: 1 }
                tags     => nonempty<text>
                braced   => !nonempty { element_type: text }
                written  => !meta.array { element_type: text  min_items: 1 }
                """);
        Top written = linked.schema().entries().get("written");

        assertEquals(written, linked.schema().entries().get("tags"));
        assertEquals(written, linked.schema().entries().get("braced"));
        assertEquals(TypeChain.EMPTY, ((ArrayBody) linked.schema().entries().get("tags")).chain());
        assertEquals(Optional.of("meta.array"), linked.schema().constructorOf("tags"));
    }

    @Test
    void aRefinementOfAnIncompleteEntryAccumulatesWhatItWrites() {
        TsonLinkedSchema linked = resolved("""
                keyed  => !meta.map ^ { key_type: text }
                capped => !keyed ^ { max_items: 9 }
                counts => capped<int32>
                """);

        IncompleteEntry capped = assertInstanceOf(IncompleteEntry.class, linked.schema().entries().get("capped"));
        assertEquals(List.of("value_type"), capped.absent());
        MapBody counts = assertInstanceOf(MapBody.class, linked.schema().entries().get("counts"));
        assertEquals("text", counts.keyType().name());
        assertEquals("int32", counts.valueType().text());
        assertEquals(Optional.of(9L), counts.maxItems());
        assertEquals(TypeChain.EMPTY, counts.chain());
    }

    @Test
    void aRefinementMayWriteARequiredFieldWhileAnotherStaysAbsent() {
        TsonLinkedSchema linked = resolved("""
                open  => !meta.map ^ { voidable: true }
                keyed => !open ^ { key_type: text }
                names => keyed<uuid>
                """);

        assertEquals(List.of("value_type"),
                assertInstanceOf(IncompleteEntry.class, linked.schema().entries().get("keyed")).absent());
        assertInstanceOf(MapBody.class, linked.schema().entries().get("names"));
    }

    @Test
    void theDesignExampleLoadsAndIsRefusedWhereItIsClosed() {
        assertEquals(List.of(), tson().validateSchema(schema("""
                array_min  => !meta.array ^ { min_items: 10 }
                max_things => !array_min ^ { max_items: 5 }
                """)));
        refused("""
                array_min  => !meta.array ^ { min_items: 10 }
                max_things => !array_min ^ { max_items: 5 }
                things     => max_things<text>
                """, "min_items 10 is above max_items 5");
    }

    @Test
    void aBracketedCompletionFillsTheOneAbsentField() {
        TsonLinkedSchema linked = resolved("""
                names  => !meta.enum_type ^ { name_type: meta.identifier }
                status => !names [OPEN DONE]
                """);

        assertEquals(Optional.of("meta.enum_type"), linked.schema().constructorOf("status"));
        refused("""
                open  => !meta.map ^ { voidable: true }
                pairs => !open [text int32]
                """, "completes only an entry leaving one required field absent");
    }

    @Test
    void aPositionalCompletionIsTheValueOfTheOneAbsentField() {
        TsonLinkedSchema linked = resolved("""
                nonempty => !meta.array ^ { min_items: 1 }
                spelled  => !nonempty text
                applied  => nonempty<text>
                braced   => !nonempty { element_type: text }
                """);

        assertEquals(linked.schema().entries().get("braced"), linked.schema().entries().get("spelled"));
        assertEquals(linked.schema().entries().get("braced"), linked.schema().entries().get("applied"));
        refused("""
                open => !meta.map ^ { voidable: true }
                one  => !open text
                """, "the positional form '!open value' completes only an entry leaving one required field absent");
    }

    @Test
    void anAliasOfAnIncompleteEntryIsAnotherNameForIt() {
        TsonLinkedSchema linked = resolved("""
                nonempty => !meta.array ^ { min_items: 1 }
                filled   => nonempty
                tags     => filled<text>
                """);

        assertEquals(Optional.of(1L), ((ArrayBody) linked.schema().entries().get("tags")).minItems());
    }

    @Test
    void aCompletionInPlaceEqualsTheConstructionWrittenThere() {
        RecordBody holder = (RecordBody) resolved("""
                nonempty => !meta.array ^ { min_items: 1 }
                holder   => { a: nonempty<text>  b: [text; 1..] }
                """).schema().entries().get("holder");

        assertEquals(holder.fields().get("b").type(), holder.fields().get("a").type());
    }

    @Test
    void aCompletionWrittenOutInAPayloadEqualsTheConstructionWrittenThere() {
        TsonLinkedSchema linked = resolved("""
                nonempty => !meta.array ^ { min_items: 1 }
                spelled  => !meta.map { key_type: text  value_type: { of: !nonempty { element_type: int32 } } }
                sugar    => {text => [int32; 1..]}
                """);

        assertEquals(linked.schema().entries().get("sugar"), linked.schema().entries().get("spelled"));
    }

    @Test
    void anImportedIncompleteEntryIsRefinedAndCompleted() {
        Tson tson = tson();
        TsonLinkedSchema linked = tson.bindRegistry().core().resolveLinked(USE);

        ArrayBody tags = assertInstanceOf(ArrayBody.class, linked.schema().entries().get("tags"));
        assertEquals(Optional.of(1L), tags.minItems());
        assertEquals(Optional.of(2L), tags.maxItems());
        assertInstanceOf(MapBody.class, linked.schema().entries().get("counts"));
        assertInstanceOf(IncompleteEntry.class, linked.schema().entries().get("nonempty"));
    }

    // ── The resolved form ─────────────────────────────────────────────────────

    @Test
    void theResolvedFormLeavesOutAnIncompleteEntryAndAnAliasOfOne() {
        Tson tson = tson();
        TsonLinkedSchema linked = tson.resolve(schema("""
                nonempty => !meta.array ^ { min_items: 1 }
                filled   => nonempty
                tags     => filled<text>
                """));

        String written = tson.objectWriter().toTson(linked.schema().entries());
        assertFalse(written.contains("nonempty =>"), written);
        assertFalse(written.contains("filled =>"), written);
        assertTrue(written.contains("tags => !meta.array"), written);
        assertEquals(List.of("tags"), List.copyOf(ResolvedForm.ownEntries(tson, "https://example.test/s.tn")
                .keySet()));
    }

    @Test
    void theResolvedFormLeavesOutANestedMapsAliasOfAnIncompleteEntry() {
        Tson tson = tson();
        TsonLinkedSchema linked = tson.resolve(schema("""
                geo => !schema { ne => !meta.array ^ { min_items: 1 }  ali => geo.ne  tags => geo.ali<text> }
                """));

        String written = tson.objectWriter().toTson(linked.schema().entries());
        String geoMap = written.substring(written.indexOf("geo => !meta.schema"));
        geoMap = geoMap.substring(0, geoMap.indexOf('}', geoMap.indexOf("tags =>")));
        assertEquals(List.of("tags"), java.util.regex.Pattern.compile("(\\w+) =>").matcher(geoMap).results()
                .map(match -> match.group(1)).filter(key -> !key.equals("geo")).toList(), geoMap);
        Top geo = ResolvedForm.ownEntries(tson, "https://example.test/s.tn").get("geo");
        assertEquals("!meta.schema [tags]", ResolvedForm.rendered(geo));
    }

    // ── Refusals of the forms ─────────────────────────────────────────────────

    @Test
    void aConstructionLeftOpenWritingEveryRequiredFieldIsRefused() {
        refused("done => !meta.array ^ { element_type: text }", "nothing is left open");
    }

    @Test
    void aFieldOfAnIncompleteEntryIsWrittenOnceWhateverTheValue() {
        refused("""
                array_min => !meta.array ^ { min_items: 10 }
                narrower  => !array_min ^ { min_items: 12 }
                """, "written once");
        refused("""
                array_min => !meta.array ^ { min_items: 10 }
                same      => !array_min ^ { min_items: 10 }
                """, "written once");
        refused("""
                array_min => !meta.array ^ { min_items: 10 }
                done      => !array_min { element_type: text  min_items: 12 }
                """, "written once");
    }

    @Test
    void aRefinementWritingTheLastRequiredFieldIsRefusedNamingTheCompletion() {
        refused("""
                nonempty => !meta.array ^ { min_items: 1 }
                filled   => !nonempty ^ { element_type: text }
                """, "complete it with '!nonempty { ... }'");
    }

    @Test
    void aCompletionLeavingARequiredFieldAbsentIsRefused() {
        refused("""
                open => !meta.map ^ { voidable: true }
                half => !open { key_type: text }
                """, "[value_type] is not written");
    }

    @Test
    void anApplicationTakesOneArgumentForEachAbsentTypeSlot() {
        refused("""
                open  => !meta.map ^ { voidable: true }
                texts => open<text>
                """, "takes 2 arguments");
    }

    @Test
    void eachFieldIsReadAgainstItsOwnTypeWhereItIsWritten() {
        refused("counted => !meta.array ^ { min_items: \"ten\" }", "is no value of 'numeric.count'");
        refused("counted => !meta.array ^ { min_itemz: 1 }", "is no field of the constructor");
        refused("indexed => !meta.array ^ { access_pattern: INDEX }", "is fixed by 'meta.array'");
    }

    // ── An incomplete entry is no type ────────────────────────────────────────

    static Stream<Arguments> positionsNamingAType() {
        return Stream.of(
                Arguments.of("a field's type", "holder => { names: nonempty }"),
                Arguments.of("a variant", "either => ( nonempty | text )"),
                Arguments.of("an element type", "rows => [nonempty]"),
                Arguments.of("an argument", "box => <T> { v: T }\n  boxed => box<nonempty>"),
                Arguments.of("a bound", "box => <T: nonempty> { v: T }"),
                Arguments.of("an operand of &", "tagged => nonempty & { note?: text }"),
                Arguments.of("a reference's of", "arrays => !meta.ref { of: nonempty }"),
                Arguments.of("an alias at a field", "filled => nonempty\n  holder => { names: filled }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positionsNamingAType")
    void anIncompleteEntryIsRefusedWhereATypeIsNamed(String position, String body) {
        refused("nonempty => !meta.array ^ { min_items: 1 }\n  " + body,
                "an incomplete 'meta.array' with [element_type] absent, which is no type");
    }

    @Test
    void theNamesAnIncompleteEntryHoldsAreJudgedWhenItsSchemaLinks() {
        refused("m => !meta.map ^ { key_type: no_such_type }", "unresolved reference 'no_such_type'");
        refused("""
                n => !meta.array ^ { min_items: 1 }
                m => !meta.map ^ { value_type: n }
                """, "'m' value_type names 'n', an incomplete 'meta.array'");
        refused("m => !meta.map ^ { value_type: { of: !meta.array { element_type: no_such } } }",
                "unresolved reference 'no_such'");
        refused("e => !meta.enum_type ^ { name_type: text }", "which 'meta.identifier_ref' does not admit");
        assertEquals(List.of(), tson().validateSchema(schema("e => !meta.enum_type ^ { name_type: meta.identifier }")));
    }

    @Test
    void anImportedIncompleteEntryIsJudgedByItsOwnSchemasLink() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(
                "https://example.test/bad.tn", """
                        !!id:"https://example.test/bad.tn"
                        !!import:"https://tson.io/2026/40/m/core.tn"
                        !schema {
                          m => !meta.map ^ { key_type: no_such_type }
                        }
                        """)))));

        assertTrue(tson.validateSchema(DOCUMENTS.get(LIB)).isEmpty());
        assertEquals(List.of(), tson().validateSchema(DOCUMENTS.get(USE)));
        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/uses-bad.tn"
                !!import:"https://example.test/bad.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  counts => m<int32>
                }
                """);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().allMatch(problem -> problem.message().contains("no_such_type")), problems.toString());
    }

    @Test
    void aDataTagNamingAnIncompleteEntryIsRefused() {
        Tson tson = tson();
        tson.bindRegistry().core().resolveLinked(LIB);

        List<Diagnostic> problems = tson.validate("!!import:\"https://example.test/open.tn\"\n!nonempty [a]");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("an incomplete 'meta.array'"), problems.toString());
    }

    // ── Refinement of a collection type (§5.7) ────────────────────────────────

    @Test
    void aRefinementOfAnArrayTypeNarrowsByFacetAndKeepsIsA() {
        TsonLinkedSchema linked = resolved("""
                tags => [text; 1..]
                more => !tags ^ { min_items: 2  max_items: 8  unique_items: true  voidable: false }
                set2 => set<text>
                few  => !set2 ^ { max_items: 3 }
                """);

        ArrayBody more = assertInstanceOf(ArrayBody.class, linked.schema().entries().get("more"));
        assertEquals(List.of("tags"), more.supertypes());
        assertEquals(Optional.of(2L), more.minItems());
        assertTrue(more.uniqueItems());
        assertEquals(List.of("more"), ((ArrayBody) linked.schema().entries().get("tags")).subtypes());
        ArrayBody few = assertInstanceOf(ArrayBody.class, linked.schema().entries().get("few"));
        assertEquals(List.of("set2"), few.supertypes());
        assertFalse(few.ordered());
    }

    @Test
    void aRefinementOfAMapTypeKeepsIsA() {
        MapBody small = assertInstanceOf(MapBody.class, resolved("""
                index => {text => int32}
                small => !index ^ { max_items: 3  key_type: text }
                """).schema().entries().get("small"));

        assertEquals(List.of("index"), small.supertypes());
        assertEquals(Optional.of(3L), small.maxItems());
    }

    /** A type slot is one by the entry its name reaches, and the refined body holds the source's spelling (§5.7). */
    @Test
    void aTypeSlotRestatedThroughAnAliasKeepsTheSourcesSpelling() {
        Namespace<Top> entries = resolved("""
                label  => text
                names  => [text]
                viaal  => !names ^ { element_type: label  max_items: 3 }
                labels => [label]
                back   => !labels ^ { element_type: text  max_items: 3 }
                index  => {text => int32}
                count  => int32
                keyed  => !index ^ { key_type: label  value_type: count }
                """).schema().entries();

        ArrayBody viaal = assertInstanceOf(ArrayBody.class, entries.get("viaal"));
        assertEquals(TypeRef.of("text"), viaal.elementType());
        assertEquals(Optional.of(3L), viaal.maxItems());
        assertEquals(TypeRef.of("label"), assertInstanceOf(ArrayBody.class, entries.get("back")).elementType());
        MapBody keyed = assertInstanceOf(MapBody.class, entries.get("keyed"));
        assertEquals(TypeRef.of("text"), keyed.keyType());
        assertEquals(TypeRef.of("int32"), keyed.valueType());
    }

    @Test
    void aTwoHopAliasDeclaredAfterTheRefinementRestatesTheSlot() {
        ArrayBody refined = assertInstanceOf(ArrayBody.class, resolved("""
                refined => !names ^ { element_type: heading  unique_items: true }
                names   => [text]
                heading => label
                label   => text
                """).schema().entries().get("refined"));

        assertEquals(TypeRef.of("text"), refined.elementType());
        assertTrue(refined.uniqueItems());
    }

    @Test
    void anImportedAliasRestatesTheSlot() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(
                "https://example.test/labels.tn", """
                        !!id:"https://example.test/labels.tn"
                        !!import:"https://tson.io/2026/40/m/core.tn"
                        !schema {
                          label => text
                        }
                        """)))));

        Top refined = tson.resolve("""
                !!id:"https://example.test/s.tn"
                !!import:"https://example.test/labels.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  names => [text]
                  r     => !names ^ { element_type: label  max_items: 3 }
                }
                """).schema().entries().get("r");
        assertEquals(TypeRef.of("text"), assertInstanceOf(ArrayBody.class, refined).elementType());
    }

    @Test
    void anAliasOfAnotherTypeStillReplacesTheSlot() {
        refused("""
                  id    => uuid
                  names => [text]
                  r     => !names ^ { element_type: id }""", "element_type id replaces the source's own text");
        refused("""
                  r     => !names ^ { element_type: r }
                  names => [text]""", "element_type r replaces the source's own text");
        refused("""
                  index => {text => int32}
                  r     => !index ^ { value_type: no_such }""", "value_type no_such replaces the source's own int32");
    }

    static Stream<Arguments> collectionFacetsMovedOutward() {
        return Stream.of(
                Arguments.of("a floor lowered", "t => [text; 2..]", "!t ^ { min_items: 1 }", "min_items 1 is below"),
                Arguments.of("a ceiling raised", "t => [text; ..5]", "!t ^ { max_items: 6 }", "max_items 6 is above"),
                Arguments.of("order changed", "t => [text]", "!t ^ { ordered: false }", "only restates ordered"),
                Arguments.of("an element replaced", "t => [text]", "!t ^ { element_type: uuid }",
                        "only restates element_type"),
                Arguments.of("voids allowed", "t => [text]", "!t ^ { voidable: true }", "voidable re-enables"),
                Arguments.of("duplicates allowed", "u => [text]\n  t => !u ^ { unique_items: true }",
                        "!t ^ { unique_items: false }", "duplicate items"),
                Arguments.of("a key replaced", "t => {text => int32}", "!t ^ { key_type: uuid }",
                        "only restates key_type"),
                Arguments.of("an element in place restated by a name", "inner => [text]\n  t => [[text]]",
                        "!t ^ { element_type: inner }", "only restates element_type"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collectionFacetsMovedOutward")
    void aRefinementOfACollectionTypeMovesEachFacetOnlyByItsKind(String facet, String source, String refinement,
                                                                 String expected) {
        refused(source + "\n  r => " + refinement, expected);
    }

    // ── Data against completed types ──────────────────────────────────────────

    @Test
    void dataIsReadAgainstACompletionInTreeAndBindMode() {
        Tson tson = tson();
        tson.bindRegistry().core().resolveLinked(USE);
        String header = "!!import:\"https://example.test/use.tn\"\n";

        TsonValue tree = tson.treeReader().read(header + "!tags [a b]");
        assertEquals(2, assertInstanceOf(TsonArray.class, tree).elements().size());
        assertEquals(List.of("a", "b"), tson.objectReader().read(header + "!tags [a b]", Object.class));
        assertEquals(List.of(), tson.validate(header + "!holder { names: [x] }"));

        assertThrows(ReadException.class, () -> tson.treeReader().read(header + "!tags []"));
        assertThrows(ReadException.class, () -> tson.objectReader().read(header + "!tags [a b c]", Object.class));
        List<Diagnostic> problems = tson.validate(header + "!holder { names: [] }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Optional.of("/names"), problems.getFirst().path());
    }

    /**
     * A tuple, a choice and a namespace value are finished, and the refusal says so rather than that the source has no
     * atom constraints, an array or a map type being refinable too (§5.5).
     */
    @Test
    void aFinishedTypeRefusesRefinementAndSaysSo() {
        refused("""
                  pair => [text, int32]
                  bad  => !pair ^ { voidable: false }""",
                "is a '!meta.tuple' value, which is finished and takes no refinement");
        refused("""
                  either => ( int32 | text )
                  bad    => !either ^ { disjoint: true }""", "which is finished and takes no refinement");
    }
}
