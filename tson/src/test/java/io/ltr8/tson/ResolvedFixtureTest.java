package io.ltr8.tson;

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
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build();
    }

    // ── The comparison ───────────────────────────────────────────────────

    private record Comparison(String label, Map<String, TypeDefinition> fixture,
                               Map<String, TypeDefinition> ours) {

    }

    private static Comparison compare(String label, String fixtureFile, String id) throws Exception {
        return new Comparison(label, fixtureEntries(fixtureFile), ourEntries(id));
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
     * subtypes}, {@code disjoint}, {@code body} and the annotations each carries.
     *
     * <p>It was not always so, and what closed the gap is worth knowing before changing any of it: the
     * container forms were carried as applications of a parameterized {@code array}/{@code map}, which the
     * structure-templates CR removed (D3, "array, set, and map lose their parameter lists"), and every sugar
     * form now lifts to a synthetic entry instead (D5). The fixtures were written against the older shape.
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
     * <b>And the same entries are synthetic on both sides.</b> [TSON-SCHEMA] §8.2 puts the derived
     * {@code @synthetic} marker on the schema-map key of every entry the resolver materialised from a sugar
     * form, and on no other -- an instantiation entry deliberately carries none. The fixtures mark nine keys
     * in meta-kernel and one in meta.tn; core.tn writes no inline form and has none, which is as much a
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
        assertEquals(1, fixtureSynthetics("meta-resolved.tn").size(), "meta.tn marks one");

        assertEquals(fixtureSynthetics("meta-kernel-resolved.tn"),
                ResolvedForm.ourSynthetics(tson(), TsonBundledSchemas.META_KERNEL_ID), "meta-kernel.tn");
        assertEquals(fixtureSynthetics("meta-resolved.tn"),
                ResolvedForm.ourSynthetics(tson(), TsonBundledSchemas.META_ID), "meta.tn");
        assertEquals(fixtureSynthetics("core-resolved.tn"),
                ResolvedForm.ourSynthetics(tson(), TsonBundledSchemas.CORE_ID), "core.tn");
    }
}
