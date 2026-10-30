package io.ltr8.tson;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.resolver.SchemaResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.SchemaPaths;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [TSON-SCHEMA] §3.4.1's fifth rule ({@code SPEC-FEEDBACK.md #4}): every order consistent with the waits gives one
 * verdict and one resolved output, and the order entries are written in is not consulted. Each bundled schema, and
 * schemas whose entries read values against each other, resolved with its declarations reversed and shuffled
 * comes out entry for entry as written: the same values, the same key annotations, the same facts.
 */
class ResolutionOrderTest {

    private static final String LOCAL_ID = "https://example.test/order.tn";

    /**
     * Schemas whose entries wait on each other through reads: a constructor and its instances, values, annotations,
     * and the same reaching members of schema maps nested as entries, whose paths the order permutes with the rest.
     */
    private static final List<String> SAME_DOCUMENT = List.of("""
            !!id:"https://example.test/order.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              counted => array ^ { ordered?: = false }
              counts  => !counted { element_type: int32  min_items: 1 }
              names   => !counted { element_type: label }
              label   => @kind_note:"a name" !text ^ { max_length: 32 }
              kind_note => @annotation text
              point   => { x: int32  y: int32  next?: point }
              line    => { from: point  to: point }
              origin  => !line { from: { x: 1  y: 2 }  to: { x: 3  y: 4  next: { x: 5  y: 6 } } }
              @kind_note:"steps" steps => !namespace { key_type: identifier  value_type: label }
              plan    => !steps { first => "a"  second => "b" }
              base    => { id: int32 }
              derived => base & { extra?: counts }
              step    => top & { takes: type_kind }
              s       => !step { takes: derived }
            }
            """, """
            !!id:"https://example.test/nested.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              geo => !schema {
                counted => array ^ { ordered?: = false }
                counts  => !geo.counted { element_type: int32  min_items: 1 }
                point   => { x: int32  y: int32 }
                origin  => !geo.point { x: 0  y: 0 }
                inner   => !schema {
                  label => !text ^ { max_length: 8 }
                  tag   => !geo.inner.label ^ { max_length: 4 }
                }
              }
              line  => { from: geo.point  to: geo.point  counts?: geo.counts  tags?: [geo.inner.tag] }
              start => !line { from: { x: 1  y: 2 }  to: { x: 3  y: 4 } }
            }
            """);

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaMetaNameBinder.defaultContext())
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of()))));
    }

    /**
     * Everything a resolution says of each entry, keyed and sorted by name so declaration order drops out. A nested
     * map's members are entries under their paths, compared there, so the map itself is its sorted member names.
     */
    private static Map<String, String> outcome(TsonSchema schema) {
        Map<String, String> outcome = new TreeMap<>();
        schema.entries().forEach((name, value) -> outcome.put(name, (SchemaPaths.isNestedMap(value)
                ? "!schema " + new TreeSet<>(((Namespace<?>) ((ValueEntry) value).value()).keySet()) : value)
                + " | key " + schema.entries().getAnnotations(name) + " | facts " + schema.factsOf(name)));
        return outcome;
    }

    private static Map<String, String> resolved(Tson tson, String source, List<String> order) {
        TsonSchemaParser parser = new TsonSchemaParser(source);
        SchemaDocument document = parser.parseSchemaDocument();
        Map<String, SchemaMap.Declaration> permuted = new LinkedHashMap<>();
        order.forEach(name -> permuted.put(name, document.body().declarations().get(name)));
        SchemaDocument reordered = new SchemaDocument(document.id(), document.imports(),
                new SchemaMap(document.body().annotations(), permuted));
        return outcome(new SchemaResolver(tson.loader()).resolveSchema(reordered, parser.schemaPositions()));
    }

    /** The source's own order, reversed, and three seeded shuffles. */
    private static List<List<String>> orders(String source) {
        List<String> written = List.copyOf(new TsonSchemaParser(source).parseSchemaDocument().body().declarations()
                .keySet());
        List<List<String>> orders = new ArrayList<>();
        orders.add(written);
        List<String> reversed = new ArrayList<>(written);
        Collections.reverse(reversed);
        orders.add(reversed);
        for (long seed : new long[] {1, 39, 2026}) {
            List<String> shuffled = new ArrayList<>(written);
            Collections.shuffle(shuffled, new Random(seed));
            orders.add(shuffled);
        }
        return orders;
    }

    private static void assertOrderIndependent(Tson tson, String label, String source) {
        List<List<String>> orders = orders(source);
        Map<String, String> expected = resolved(tson, source, orders.getFirst());
        for (List<String> order : orders.subList(1, orders.size())) {
            assertEquals(expected, resolved(tson, source, order), label + " resolved in the order " + order);
        }
    }

    @Test
    void eachBundledSchemaResolvesTheSameInAnyOrder() {
        Tson tson = tson();
        for (String id : List.of(TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID,
                TsonBundledSchemas.CORE_ID, TsonBundledSchemas.NET_ID, TsonBundledSchemas.POLICY_ID)) {
            assertOrderIndependent(tson, id, TsonBundledSchemas.fetch(id));
        }
    }

    @Test
    void aSchemaWhoseEntriesReadEachOtherResolvesTheSameInAnyOrder() {
        Tson tson = tson();
        for (String source : SAME_DOCUMENT) {
            assertEquals(List.of(), tson.validateSchema(source));
            assertOrderIndependent(tson, LOCAL_ID, source);
        }
    }
}
