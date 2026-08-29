package io.ltr8.tson.compiler;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §8.2's derived {@code @synthetic} marker: every entry the resolver materialised from a sugar
 * form carries it at its schema-map key, and nothing else does.
 *
 * <p><b>Why the marker, when the names are already distinctive.</b> A synthetic entry is named by derivation
 * from its own content, but §8.2 makes that spelling non-normative -- an implementation picks its own -- so
 * pattern-matching the name is not a way to recognise one. The marker is, and it is what lets a consumer of
 * resolver output fold these entries back into the nested form the author actually wrote.
 *
 * <p><b>Two families, one marked.</b> §8.2: "The instantiation entry carries no {@code @synthetic} marker:
 * the two families are distinguishable (an instantiation's {@code source} is an application; a synthetic's is
 * a bare constructor), and only synthetics are the fold-back-into-display case the marker serves." Both
 * halves are asserted here, because marking too much is as wrong as marking too little.
 *
 * <p>Key position, per §6: the marker is metadata <em>about the declaration</em>, so it lands on the schema
 * map's key ({@link AnnotatedMap#getAnnotations}) and never on the {@link TypeDefinition} value.
 */
class SyntheticEntryMarkerTest {

    private static final String ID = "https://example.test/marker.tn";

    /** A second schema, which reaches the first one's entries -- synthetics included -- through !!import. */
    private static final String IMPORTER_ID = "https://example.test/importer.tn";

    /**
     * One of each: a plain record, a template and its application, a declaration whose <em>own</em> body is a
     * sugar form (which never lifts, §5.3), and a record holding two inline forms (which do).
     */
    private static final String SCHEMA = """
            !!id:"https://example.test/marker.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              @doc:"An order." order => { id: text }
              paged => <T> { items: [T; 1..] }
              order_response => paged<order>
              tag_list => [text; 1..2]
              holder => {
                tags:   tag_list
                inline: [order; 2..]
                pick:   (text | int32)
              }
            }
            """;

    /** The names the author wrote, which is what "declared" means for the assertions below. */
    private static final Set<String> DECLARED =
            Set.of("order", "paged", "order_response", "tag_list", "holder");

    private static final String IMPORTER = """
            !!id:"https://example.test/importer.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://example.test/marker.tn"
            {
              shipment => { of: order }
            }
            """;

    private static TsonCompiledMetaRegistry registry() {
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            if (TsonCanonicalIdentity.sameIdentity(uri, IMPORTER_ID)) {
                return IMPORTER;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        });
    }

    private static AnnotatedMap<String, TypeDefinition> entriesOf(String id) {
        return registry().resolveLinked(id).schema().entries();
    }

    /** This schema's own entries, the merged {@code !!import} closure left out. */
    private static Map<String, TypeDefinition> localEntries() {
        var linked = registry().resolveLinked(ID);
        String canonical = TsonCanonicalIdentity.canonicalize(ID);
        Map<String, TypeDefinition> local = new LinkedHashMap<>();
        linked.schema().entries().forEach((name, definition) -> {
            if (linked.originOf(name).equals(canonical)) {
                local.put(name, definition);
            }
        });
        return local;
    }

    private static boolean marked(AnnotatedMap<String, TypeDefinition> entries, String name) {
        return entries.getAnnotations(name).has("synthetic");
    }

    /** An inline sugar form lifts to an entry of its own (§5.3), and that entry is a synthetic one. */
    @Test
    void everyEntryLiftedFromAnInlineFormIsMarked() {
        AnnotatedMap<String, TypeDefinition> entries = entriesOf(ID);

        Set<String> lifted = new TreeSet<>();
        localEntries().forEach((name, definition) -> {
            if (!DECLARED.contains(name) && marked(entries, name)) {
                lifted.add(name);
            }
        });
        // `[T; 1..]` open in the template, `[T; 1..]` closed as `[order; 1..]` by materialising
        // `paged<order>`, and `holder`'s own `[order; 2..]` and `(text | int32)`.
        assertEquals(4, lifted.size(), "expected four synthetic entries, got " + lifted);
    }

    /** A name the author wrote is never one, however sugary its body. */
    @Test
    void noDeclaredEntryIsMarked() {
        AnnotatedMap<String, TypeDefinition> entries = entriesOf(ID);

        for (String name : DECLARED) {
            assertFalse(marked(entries, name), name + " is declared, not materialised");
        }
    }

    /**
     * §5.3's exception, which is the one a marker could plausibly get wrong: a declaration's own body never
     * lifts -- {@code tag_list => [text; 1..2]} <em>is</em> the construction, not a reference to one -- so
     * {@code tag_list} is an ordinary declared entry that happens to have a constructor {@code source}.
     */
    @Test
    void aDeclarationWhoseOwnBodyIsASugarFormIsNotSynthetic() {
        AnnotatedMap<String, TypeDefinition> entries = entriesOf(ID);

        assertEquals("array", entries.get("tag_list").source().orElseThrow().name());
        assertFalse(marked(entries, "tag_list"), "the declaration is the construction, not a lift of one");
    }

    /**
     * §8.2's own dividing line, both ways: an instantiation entry -- {@code source} an application -- carries
     * no marker, and every entry that does carry one has a bare constructor as its {@code source}.
     */
    @Test
    void anInstantiationEntryIsNotMarkedAndEverySyntheticSourceIsAConstructor() {
        AnnotatedMap<String, TypeDefinition> entries = entriesOf(ID);

        Set<String> instantiations = new TreeSet<>();
        localEntries().forEach((name, definition) -> {
            boolean application = definition.source()
                    .map(source -> !source.arguments().isEmpty()).orElse(false);
            if (application) {
                instantiations.add(name);
                assertFalse(marked(entries, name), name + " is an instantiation entry, which §8.2 leaves bare");
            } else if (marked(entries, name)) {
                assertTrue(definition.source().isPresent(),
                        name + " is marked synthetic but records no constructor as its source");
            }
        });
        assertEquals(1, instantiations.size(), "expected paged<order> to materialise one entry, got "
                + instantiations);
    }

    /** The marker is the key's, not the definition's -- §6 forbids hoisting between the two positions. */
    @Test
    void theDefinitionValueNeverCarriesTheMarker() {
        localEntries().forEach((name, definition) -> assertFalse(definition.annotations().has("synthetic"),
                name + " carries @synthetic on its definition, where §6 puts declaration metadata on the key"));
    }

    /**
     * And it survives the linker, which rebuilds its entry map several times over, and the {@code !!import}
     * merge: §2.2.3 contributes an imported schema's whole namespace, synthetic entries included, and each
     * arrives marked by the schema that materialised it rather than by whoever imported it.
     */
    @Test
    void anImportedSyntheticKeepsItsMarker() {
        AnnotatedMap<String, TypeDefinition> declaring = entriesOf(ID);
        AnnotatedMap<String, TypeDefinition> importing = entriesOf(IMPORTER_ID);

        Set<String> synthetics = new TreeSet<>();
        declaring.keySet().forEach(name -> {
            if (marked(declaring, name)) {
                synthetics.add(name);
            }
        });
        assertEquals(4, synthetics.size(), "expected four synthetic entries, got " + synthetics);
        for (String name : synthetics) {
            assertTrue(marked(importing, name), name + " lost its marker on the way through !!import");
        }
    }
}
