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
     * A synthetic entry's name ends in a content hash of its resolved binding record, and <b>that hash is
     * not normative</b> -- the CR's D6 keys a closed synthetic on structural equality, "one entry per
     * distinct concrete form schema-wide", leaving the spelling to the implementation. The fixtures write
     * {@code xxhash} where a real one goes, so both sides are reduced to that before comparing and this
     * stays a test of structure rather than of a hash function.
     */
    private static final java.util.regex.Pattern SYNTHETIC_HASH =
            java.util.regex.Pattern.compile("_[0-9a-f]{8}$");

    /** The same, unanchored -- a synthetic's name appears inside a body as well as being an entry's own. */
    private static final java.util.regex.Pattern SYNTHETIC_HASH_ANYWHERE =
            java.util.regex.Pattern.compile("_[0-9a-f]{8}\\b");

    private static final java.util.regex.Pattern SOURCE_POSITION =
            java.util.regex.Pattern.compile("position=Optional\\[Position\\[[^\\]]*\\]\\]");

    private static String withoutHash(String name) {
        return SYNTHETIC_HASH.matcher(name).replaceFirst("_xxhash");
    }

    private static Tson tson() {
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build();
    }

    // ── The comparison ───────────────────────────────────────────────────

    private record Comparison(String label, Map<String, TypeDefinition> fixture,
                               Map<String, TypeDefinition> ours) {

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

    /**
     * A definition reduced to what the comparison is about: canonicalised, then with every synthetic's
     * content hash replaced by the {@code xxhash} the fixtures write. Compared as text rather than through
     * {@code equals} because a synthetic's name appears at any depth of a body -- as the type of a field,
     * inside an argument list -- and normalising it in place would mean rebuilding every body shape here.
     */
    private static String rendered(TypeDefinition definition) {
        String text = SYNTHETIC_HASH_ANYWHERE.matcher(String.valueOf(canonical(definition)))
                .replaceAll("_xxhash");
        // `position` is this model's own: an @Unbound component recording where a declaration was written,
        // which §8's resolved form has no field for and no fixture carries. Normalised away rather than
        // compared, exactly as TypeDefinition's own equals leaves it out.
        return SOURCE_POSITION.matcher(text).replaceAll("position=Optional.empty");
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
                own.put(withoutHash(name), definition);
            }
        });
        return own;
    }

    /**
     * The names each fixture marks {@code @synthetic} at its schema-map key, read from the fixture's own
     * <em>text</em>.
     *
     * <p>Text, and not the bound document every other comparison here uses, because a key-position annotation
     * is dropped when a resolved-form document is read back -- the second half of {@code BACKLOG.md}'s
     * synthetic-entry item. Both sides of a bound comparison would therefore render no annotations at all and
     * agree for the wrong reason. Scanning the source is what makes the marker checkable against the spec's
     * own output before that channel exists; when it does, this can read the keys like anything else.
     */
    private static Set<String> fixtureSynthetics(String file) throws Exception {
        Set<String> marked = new TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@synthetic\\s+([A-Za-z0-9_]+)\\s*=>")
                .matcher(Files.readString(specDirectory().resolve(file)));
        while (matcher.find()) {
            marked.add(matcher.group(1));
        }
        return marked;
    }

    /** The same, from this resolver: the local entries whose keys carry the marker, hashes normalised. */
    private static Set<String> ourSynthetics(String id) {
        var linked = tson().bindRegistry().core().resolveLinked(id);
        var entries = linked.schema().entries();
        String canonical = TsonCanonicalIdentity.canonicalize(id);
        Set<String> marked = new TreeSet<>();
        entries.forEach((name, definition) -> {
            if (linked.originOf(name).equals(canonical) && entries.getAnnotations(name).has("synthetic")) {
                marked.add(withoutHash(name));
            }
        });
        return marked;
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
                    rendered(fixtureDefinition), rendered(comparison.ours().get(name)),
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
     * #fixtureSynthetics}.
     */
    @Test
    void theSameEntriesAreMarkedSyntheticOnBothSides() throws Exception {
        // Non-vacuous: the fixtures really do mark keys, so an empty-equals-empty pass is not available to a
        // scan that stopped matching or a resolver that stopped marking.
        assertEquals(9, fixtureSynthetics("meta-kernel-resolved.tn").size(), "meta-kernel.tn marks nine keys");
        assertEquals(1, fixtureSynthetics("meta-resolved.tn").size(), "meta.tn marks one");

        assertEquals(fixtureSynthetics("meta-kernel-resolved.tn"),
                ourSynthetics(TsonBundledSchemas.META_KERNEL_ID), "meta-kernel.tn");
        assertEquals(fixtureSynthetics("meta-resolved.tn"), ourSynthetics(TsonBundledSchemas.META_ID), "meta.tn");
        assertEquals(fixtureSynthetics("core-resolved.tn"), ourSynthetics(TsonBundledSchemas.CORE_ID), "core.tn");
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

}
