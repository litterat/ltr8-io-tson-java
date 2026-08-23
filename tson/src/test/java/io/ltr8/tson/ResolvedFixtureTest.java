package io.ltr8.tson;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p><b>What the assertions are for.</b> Every fixture entry must read back and must have a counterpart
 * here; those are absolute. What may still <em>differ</em> is pinned per schema, so a new divergence fails
 * and a closed one fails too, asking for the count to come down. The differences that remain are all one
 * deliberate thing -- see {@link #everyRemainingDifferenceIsAContainerThisResolverLifted} -- and that shape
 * is asserted rather than a list of names, so the test survives a change of synthetic naming and still
 * catches a divergence of a different kind.
 */
class ResolvedFixtureTest {

    /**
     * Where this schema and the fixture disagree, per schema. Every one is [TSON-SCHEMA] §8.2's
     * carried-structurally rule against this resolver's container lifting ({@code SPEC-FEEDBACK.md}
     * #49/#50/#51, deliberately left open as a revision discussion point). Bring these down; a rise is a
     * regression.
     */
    private static final int CORE_DIFFERENCES = 0;
    private static final int META_DIFFERENCES = 5;
    private static final int META_KERNEL_DIFFERENCES = 7;

    private static Tson tson() {
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build();
    }

    // ── The comparison ───────────────────────────────────────────────────

    private record Comparison(String label, Map<String, TypeDefinition> fixture,
                               Map<String, TypeDefinition> ours) {

        /** Entry names present on both sides whose canonicalised definitions are not equal. */
        List<String> differing() {
            List<String> differing = new ArrayList<>();
            fixture.forEach((name, definition) -> {
                TypeDefinition mine = ours.get(name);
                if (mine != null && !canonical(mine).equals(canonical(definition))) {
                    differing.add(name);
                }
            });
            return differing;
        }

        /** Entry names this resolver produces that the fixture has no counterpart for. */
        List<String> onlyOurs() {
            var extra = new TreeSet<>(ours.keySet());
            extra.removeAll(fixture.keySet());
            return List.copyOf(extra);
        }
    }

    /**
     * {@code supertypes}/{@code subtypes} sorted -- the "canonicalise" the fixtures' own instruction asks
     * for before comparing. [TSON-SCHEMA] §8.2 calls both <b>name-level indexes, resolver-managed</b>: they
     * are sets the representation happens to write as lists, so the fixture's alphabetical order and this
     * resolver's resolution order say the same thing. Nothing else is normalised; a difference anywhere
     * else is a real one.
     */
    private static TypeDefinition canonical(TypeDefinition definition) {
        return new TypeDefinition(definition.source(), definition.kind(), definition.parameters(),
                definition.constructor(), definition.supertypes().stream().sorted().toList(),
                definition.subtypes().stream().sorted().toList(), definition.disjoint(), definition.body(),
                definition.position(), definition.annotations());
    }

    private static Comparison compare(String label, String fixtureFile, String id) throws Exception {
        return new Comparison(label, fixtureEntries(fixtureFile), ourEntries(id));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TypeDefinition> fixtureEntries(String file) throws Exception {
        Map<String, TypeDefinition> bound = (Map<String, TypeDefinition>) tson().objectReader()
                .withSchema(TsonBundledSchemas.META_ID)
                .readAs(Files.readString(specDirectory().resolve(file)), "schema", Object.class);
        assertNotNull(bound, file + " did not read back at all");
        return bound;
    }

    /** This schema's OWN entries -- the fixture is resolver output, from before an import merged anything in. */
    private static Map<String, TypeDefinition> ourEntries(String id) {
        var linked = tson().bindRegistry().core().resolveLinked(id);
        Map<String, TypeDefinition> own = new LinkedHashMap<>();
        String canonical = TsonCanonicalIdentity.canonicalize(id);
        linked.schema().entries().forEach((name, definition) -> {
            if (linked.originOf(name).equals(canonical)) {
                own.put(name, definition);
            }
        });
        return own;
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

    // ── What may still differ ────────────────────────────────────────────

    /**
     * The count of disagreeing entries, pinned per schema. A rise is a regression; a fall means something
     * was closed and the constant should follow it down.
     */
    @Test
    void nothingDiffersBeyondWhatIsAlreadyKnownTo() throws Exception {
        List<Comparison> all = all();
        assertEquals(CORE_DIFFERENCES, all.get(0).differing().size(), () -> detail(all.get(0)));
        assertEquals(META_DIFFERENCES, all.get(1).differing().size(), () -> detail(all.get(1)));
        assertEquals(META_KERNEL_DIFFERENCES, all.get(2).differing().size(), () -> detail(all.get(2)));
    }

    /**
     * <b>Every remaining difference is one deliberate thing.</b> §8.2 carries a container at a type position
     * structurally -- {@code type: array<token>} -- where this resolver lifts it to an injected declaration
     * and leaves a bare reference behind ({@code type: array_field_name_f1a73e72}). {@code SPEC-FEEDBACK.md}
     * #49/#50/#51 hold the argument and are open revision questions, not defects.
     *
     * <p>Asserted as a <em>shape</em> rather than a list of entry names: a synthetic's name is derived from
     * its content, so a list would break on any change to that derivation while saying nothing, where this
     * still fails the moment a difference of some other kind appears. The lifted names are gathered across
     * all three schemas, since a schema may reference a container lifted by one it imports.
     */
    @Test
    void everyRemainingDifferenceIsAContainerThisResolverLifted() throws Exception {
        List<Comparison> all = all();
        // Across all three: meta.tn's own `extern` names a container lifted in meta-kernel, so a check
        // scoped to one schema's own extras would miss it.
        var lifted = new TreeSet<String>();
        all.forEach(comparison -> lifted.addAll(comparison.onlyOurs()));
        for (Comparison comparison : all) {
            for (String name : comparison.differing()) {
                assertTrue(mentionsALiftedContainer(comparison, name, lifted),
                        comparison.label() + ": " + name + " differs for some reason other than container "
                                + "lifting, which is the only difference this comparison knows about."
                                + firstDifference(canonical(comparison.fixture().get(name)),
                                        canonical(comparison.ours().get(name))));
            }
        }
    }

    /**
     * The extra entries this resolver produces are exactly the lifted containers -- an injected declaration
     * has no counterpart in a fixture that carries the container structurally, so it can only appear here.
     */
    @Test
    void everyExtraEntryIsALiftedContainer() throws Exception {
        for (Comparison comparison : all()) {
            for (String name : comparison.onlyOurs()) {
                TypeDefinition extra = comparison.ours().get(name);
                assertTrue(extra.body() instanceof ArrayBody || extra.body() instanceof MapBody,
                        comparison.label() + ": " + name + " is an extra entry that is not a lifted "
                                + "container -- " + extra.body());
                assertTrue(extra.position().isEmpty(),
                        comparison.label() + ": " + name + " is extra but has a source position, so someone "
                                + "declared it -- an injected declaration has none");
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Whether {@code name}'s disagreement is the lifting one: this side names an entry the fixture has no
     * counterpart for, and that entry is a container. Compared through the rendered forms because the
     * difference can sit at any depth of a body.
     */
    private static boolean mentionsALiftedContainer(Comparison comparison, String name, Set<String> lifted) {
        String ours = String.valueOf(comparison.ours().get(name));
        return lifted.stream().anyMatch(ours::contains);
    }

    /** Where two renderings first diverge, with a window either side -- a whole body is unreadable. */
    private static String firstDifference(TypeDefinition theirs, TypeDefinition mine) {
        String a = String.valueOf(theirs);
        String b = String.valueOf(mine);
        int i = 0;
        while (i < Math.min(a.length(), b.length()) && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return "\n  fixture: …" + window(a, i) + "\n  ours   : …" + window(b, i);
    }

    private static String window(String s, int at) {
        return s.substring(Math.max(0, at - 60), Math.min(s.length(), at + 90));
    }

    private static String detail(Comparison comparison) {
        return comparison.label() + " differs on " + comparison.differing()
                + " -- if that is smaller than the pinned count, bring the constant down with it";
    }
}
