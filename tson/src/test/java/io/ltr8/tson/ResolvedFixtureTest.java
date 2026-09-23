package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.TypeDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This resolver's output against the spec's own published resolver output.
 *
 * <p><b>The fixtures ask for exactly this.</b> {@code spec/m/*-resolved.tn} carry the instruction in their
 * own {@code @doc}: <i>"Parse the source schema, run the resolver, canonicalise, compare."</i> They are
 * non-normative, but they are the only external statement of what a conforming resolver should produce, and
 * comparing against them is the one check in this repo that is not this implementation marking its own
 * homework.
 *
 * <p><b>Read whole, not entry by entry.</b> An earlier shape of this comparison tree-read the document,
 * wrote each entry back out with {@code toTson} and bound that -- which loses a token's form on the way (a
 * tree holds the decoded {@code String}, so an unquoted {@code REQUIRED} came back quoted) and reported the
 * loss as a difference against this implementation. Binding the document directly re-spells nothing.
 *
 * <p><b>What the assertions are for.</b> Every fixture entry must read back, must have a counterpart here,
 * and must resolve to the same thing: no category of difference is expected or tolerated. What is
 * normalised before comparing -- index order, a synthetic's content hash, this model's own source position
 * -- is {@link ResolvedForm}'s to state, and it states it once for this and for the Class 2 conformance
 * runner, which asks the same question of a corpus vector's expected side.
 */
class ResolvedFixtureTest {

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaMetaNameBinder.defaultContext()));
    }

    // ── The comparison ───────────────────────────────────────────────────

    private record Comparison(String label, Map<String, TypeDefinition> fixture,
                               Map<String, TypeDefinition> ours, String text) {

    }

    private static Comparison compare(String label, String fixtureFile, String id) throws Exception {
        return new Comparison(label, fixtureEntries(fixtureFile), ourEntries(id),
                Files.readString(specDirectory().resolve(fixtureFile)));
    }

    private static Map<String, TypeDefinition> fixtureEntries(String file) throws Exception {
        Map<String, TypeDefinition> bound =
                ResolvedForm.readResolved(tson(), Files.readString(specDirectory().resolve(file)));
        assertNotNull(bound, file + " did not read back at all");
        return bound;
    }

    /** This schema's OWN entries -- the fixture is resolver output, from before an import merged anything in. */
    private static Map<String, TypeDefinition> ourEntries(String id) {
        return ResolvedForm.ownEntries(tson(), id);
    }

    /** The keys a fixture marks {@code @synthetic}, hashes normalised. */
    private static Set<String> fixtureSynthetics(String file) throws Exception {
        return ResolvedForm.markedSynthetics(Files.readString(specDirectory().resolve(file)));
    }

    /** {@code spec/m}, found by walking up rather than assumed relative to a working directory. */
    private static Path specDirectory() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("spec/m"))) {
            directory = directory.getParent();
        }
        assertNotNull(directory, "no spec/m above " + Path.of("").toAbsolutePath());
        return directory.resolve("spec/m");
    }

    private static List<Comparison> all() throws Exception {
        return List.of(
                compare("core.tn", "core-resolved.tn", TsonBundledSchemas.CORE_ID),
                compare("meta.tn", "meta-resolved.tn", TsonBundledSchemas.META_ID),
                compare("meta-kernel.tn", "meta-kernel-resolved.tn", TsonBundledSchemas.META_KERNEL_ID));
    }

    // ── The absolutes ────────────────────────────────────────────────────

    /**
     * Every entry of every fixture binds into this model. Absolute, and the property that was missing for
     * most of this project's life: nothing consumed a {@code type_definition} document, so the value model
     * had drifted from the schema it mirrors in four separate ways without anything noticing.
     */
    @Test
    void everyFixtureEntryReadsBackIntoTheValueModel() throws Exception {
        for (Comparison comparison : all()) {
            assertTrue(comparison.fixture().size() > 0, comparison.label() + " bound no entries at all");
            comparison.fixture().forEach((name, definition) ->
                    assertNotNull(definition, comparison.label() + ": " + name + " bound to null"));
        }
    }

    /** And every one of them names something this resolver also produced. */
    @Test
    void everyFixtureEntryHasACounterpartHere() throws Exception {
        for (Comparison comparison : all()) {
            var missing = new TreeSet<>(comparison.fixture().keySet());
            missing.removeAll(comparison.ours().keySet());
            assertEquals(List.of(), List.copyOf(missing),
                    comparison.label() + ": the fixture declares entries this resolver does not produce");
        }
    }

    // ── The substance ────────────────────────────────────────────────────

    /** Both directions: neither side declares an entry the other does not. */
    @Test
    void theEntrySetsAreIdentical() throws Exception {
        for (Comparison comparison : all()) {
            assertEquals(new TreeSet<>(comparison.fixture().keySet()), new TreeSet<>(comparison.ours().keySet()),
                    comparison.label() + ": the two do not declare the same entries");
        }
    }

    /**
     * <b>And every entry resolves to the same thing.</b> No category of difference is expected or tolerated:
     * this resolver's output and the spec's own published resolver output agree, entry for entry, over
     * {@code kind}, {@code source}, {@code parameters}, {@code constructor}, {@code supertypes}, {@code
     * subtypes}, {@code disjoint} and {@code body}. An entry's annotations sit on its schema-map key, which
     * binding drops, so they are not compared here: {@link #everyKeyCarriesTheSameAnnotations} compares them.
     *
     * <p>What the agreement rests on is worth knowing before changing any of it: {@code array}, {@code set}
     * and {@code map} carry no parameter lists ([TSON-SCHEMA] §4.2), so a container form is never an
     * application of one, and every sugar form lifts to a synthetic entry instead (§5.3).
     */
    @Test
    void everyEntryResolvesIdentically() throws Exception {
        for (Comparison comparison : all()) {
            comparison.fixture().forEach((name, fixtureDefinition) -> assertEquals(
                    ResolvedForm.rendered(fixtureDefinition), ResolvedForm.rendered(comparison.ours().get(name)),
                    comparison.label() + ": " + name + " does not resolve to what the fixture records"));
        }
    }

    /**
     * <b>And every entry's key carries the same annotations</b> -- {@code @ordered}, {@code @bounded}, {@code
     * @exact}, {@code @numeric} and the rest, {@code @doc} aside. They sit on the schema-map key, where binding
     * the fixture drops them, so this is the one assertion that reaches them; through the bound document both
     * sides would carry none and agree for the wrong reason, which is how a fixture drifts unnoticed.
     */
    @Test
    void everyKeyCarriesTheSameAnnotations() throws Exception {
        Map<String, String> fixtures = Map.of(
                "meta-kernel-resolved.tn", TsonBundledSchemas.META_KERNEL_ID,
                "meta-resolved.tn", TsonBundledSchemas.META_ID,
                "core-resolved.tn", TsonBundledSchemas.CORE_ID);
        for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
            Map<String, List<String>> written = ResolvedForm.fixtureKeyAnnotations(
                    Files.readString(specDirectory().resolve(fixture.getKey())));
            Map<String, List<String>> ours = ResolvedForm.ourKeyAnnotations(tson(), fixture.getValue());
            assertEquals(written, ours, fixture.getKey() + ": a key's annotations differ from this resolver's");
        }
        // Non-vacuous: core marks its atoms, so an empty-equals-empty pass is not available here either.
        assertTrue(ResolvedForm.fixtureKeyAnnotations(Files.readString(specDirectory().resolve("core-resolved.tn")))
                .get("int32").contains("@ordered:TOTAL"), "core-resolved.tn marks int32 @ordered:TOTAL");
    }

    /**
     * <b>And the same entries are synthetic on both sides.</b> [TSON-SCHEMA] §8.2 puts the derived
     * {@code @synthetic} marker on the schema-map key of every entry the resolver materialised from a sugar
     * form, and on no other -- an instantiation entry deliberately carries none. The fixtures mark eight keys
     * in meta-kernel and five in meta.tn; core.tn writes no inline form and has none, which is as much a
     * statement as the other two.
     *
     * <p>This is the one assertion here that does not go through the bound document -- see {@link
     * ResolvedForm#markedSynthetics}.
     */
    @Test
    void theSameEntriesAreMarkedSyntheticOnBothSides() throws Exception {
        // Non-vacuous: the fixtures really do mark keys, so an empty-equals-empty pass is not available to a
        // scan that stopped matching or a resolver that stopped marking.
        assertEquals(8, fixtureSynthetics("meta-kernel-resolved.tn").size(), "meta-kernel.tn marks eight keys");
        assertEquals(5, fixtureSynthetics("meta-resolved.tn").size(), "meta.tn marks five keys");

        assertEquals(fixtureSynthetics("meta-kernel-resolved.tn"),
                ResolvedForm.ourSynthetics(tson(), TsonBundledSchemas.META_KERNEL_ID), "meta-kernel.tn");
        assertEquals(fixtureSynthetics("meta-resolved.tn"),
                ResolvedForm.ourSynthetics(tson(), TsonBundledSchemas.META_ID), "meta.tn");
        assertEquals(fixtureSynthetics("core-resolved.tn"),
                ResolvedForm.ourSynthetics(tson(), TsonBundledSchemas.CORE_ID), "core.tn");
    }
}
