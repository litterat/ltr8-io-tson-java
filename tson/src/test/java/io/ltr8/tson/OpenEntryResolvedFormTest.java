package io.ltr8.tson;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Product;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Param;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Sum;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What [TSON-SCHEMA] §8.1 says about an <b>open</b> entry's resolved form, asked of real documents.
 *
 * <p>An open entry is an open record: a {@code !meta.record} carrying {@code parameters}, each a type written where it
 * stands as its name, a reference value, so the record is read against {@code record}'s own vocabulary as any record
 * is. The tests below hold that against real documents: the published fixtures validate, an open record validates
 * and reads back with its parameters, every entry with parameters is one, and an entry's kind is derived.
 */
class OpenEntryResolvedFormTest {

    private static Tson tson() {
        return Tson.standard();
    }

    /**
     * The same front door with the internal {@code schema.meta} vocabulary bound, for the one test that reads
     * a resolved document <em>back</em> rather than validating it -- the binding is what makes the misreading
     * visible as a value rather than as a clean verdict.
     */
    private static Tson metaBoundTson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaModelNameBinder.defaultContext()));
    }

    /** {@code spec/fixtures}, found by walking up rather than assumed relative to a working directory. */
    private static Path specDirectory() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("spec/fixtures"))) {
            directory = directory.getParent();
        }
        assertNotNull(directory, "no spec/fixtures above " + Path.of("").toAbsolutePath());
        return directory.resolve("spec/fixtures");
    }

    // ── What holds today, and must go on holding ─────────────────────────

    /**
     * <b>The published resolver-output fixtures are valid data under the schema each names.</b> §1.3 makes
     * producing a resolved schema value a MUST and §8 fixes its serialization, so this is the baseline claim
     * of §8's whole output form -- and the fixtures are the spec's own statement of it, not this
     * implementation's.
     *
     * <p>The kernel declares no open record, so this is a regression guard over closed entries.
     */
    @Test
    void everyPublishedResolvedFixtureValidatesAgainstItsGoverningSchema() throws Exception {
        Tson tson = tson();
        // A library's form holds instances of constructors its source declares, and a resolved form is read under its
        // source's imports, not over its own entries as [TSON-SCHEMA] §8.1 reads one: each is read beside its source.
        Map<String, String[]> fixtures = new LinkedHashMap<>();
        fixtures.put("meta-resolved.tn", new String[0]);
        fixtures.put("numeric-resolved.tn", new String[] {TsonBundledSchemas.NUMERIC_ID});
        fixtures.put("temporal-resolved.tn", new String[] {TsonBundledSchemas.TEMPORAL_ID});
        fixtures.put("net-resolved.tn", new String[] {TsonBundledSchemas.NET_ID});
        fixtures.put("core-resolved.tn", new String[] {TsonBundledSchemas.CORE_ID});
        for (Map.Entry<String, String[]> fixture : fixtures.entrySet()) {
            List<Diagnostic> diagnostics = ResolvedForm.validateResolved(tson,
                    Files.readString(specDirectory().resolve(fixture.getKey())), fixture.getValue());
            assertEquals(List.of(), diagnostics,
                    () -> fixture.getKey() + " does not validate against the schema it names");
        }
    }

    // ── An open record's resolved form ───────────────────────────────────

    /**
     * <b>An open record validates in §8.1's form</b>: a {@code !meta.record} carrying {@code parameters}, each standing
     * where it is written as its name, a reference value -- at a field's type, and among an application's arguments.
     * Nothing about it needs a vocabulary of its own: it is read against {@code record}'s, as any record is.
     *
     * <p>The document is hand-authored rather than produced by this library's writer, deliberately: the claim under
     * test is about the <em>spec's</em> prescribed serialization.
     */
    @Test
    void anOpenRecordValidatesAsARecord() {
        assertEquals(List.of(), ResolvedForm.validateResolved(tson(), """
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  page => !meta.record {
                    parameters: [ !meta.param { name: T } ]
                    fields: {
                      item  => !meta.record_field { type: T }
                      items => !meta.record_field { type: meta.array<T> }
                    }
                  }
                }
                """), "a parameter is a reference value, admitted by the slot it stands at");
    }

    /**
     * <b>And it reads back as the open record it is</b>: the record's parameters are looked up before the namespace,
     * so its field types are parameters ({@link ParamRef}), whole and as an argument, and no reference names an
     * entry spelled {@code T}.
     */
    @Test
    void anOpenRecordReadsBackWithItsParameters() {
        Map<String, Object> read = ResolvedForm.readResolved(metaBoundTson(), """
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  page => !meta.record {
                    parameters: [ !meta.param { name: T } ]
                    fields: {
                      item  => !meta.record_field { type: T }
                      items => !meta.record_field { type: meta.array<T> }
                    }
                  }
                }
                """);

        RecordBody page = assertInstanceOf(RecordBody.class, read.get("page"));
        assertEquals(List.of(new Param("T")), page.parameters());
        assertEquals(ParamRef.of("T"), page.fields().get("item").type());
        assertEquals(TypeRef.of("meta.array", List.of(ParamRef.of("T"))), page.fields().get("items").type());
    }

    // ── The biconditional an entry's parameters rest on ─────────────────

    /**
     * <b>An entry declares parameters exactly when it is an open record</b>, asked of every entry of every bundled
     * schema and of a schema exercising each template shape: a fresh record body, a composition and a refinement. The
     * kernel declares none.
     */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void anEntryWithParametersIsAnOpenRecord() {
        Tson tson = tson();
        tson.resolve("""
                !!id:"https://example.com/shapes.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  base     => { id: text }
                  pair     => <A, B> { first: A  second: B }
                  composed => <T> base & { value: T }
                  refined  => <T> pair<T, text> ^ { second: = hi }
                  composed_int => composed<int32>
                  holder   => { p: pair<text, int32>  q: composed_int }
                }
                """);
        List<String> ids = List.of(TsonBundledSchemas.META_ID, TsonBundledSchemas.NUMERIC_ID,
                TsonBundledSchemas.TEMPORAL_ID, TsonBundledSchemas.NET_ID, TsonBundledSchemas.CORE_ID,
                "https://example.com/shapes.tn");

        List<String> broken = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (String id : ids) {
            for (Map.Entry<String, Top> entry
                    : tson.bindRegistry().core().resolveLinked(id).schema().entries().entrySet()) {
                Top definition = entry.getValue();
                boolean declares = !OpenEntry.parameterNames(definition).isEmpty();
                if (declares) {
                    open.add(entry.getKey());
                }
                if (declares != (definition instanceof RecordBody record && record.isOpen())) {
                    broken.add(id + "#" + entry.getKey() + ": parameters " + OpenEntry.parameterNames(definition)
                            + " with a " + definition.getClass().getSimpleName() + " body");
                }
            }
        }

        assertEquals(List.of(), broken, "§5.10: a template is an open record");
        assertEquals(List.of("pair", "composed", "refined"), open, "the kernel declares no open record");
    }
    // ── §8.1: a kind is derived, never recorded ─────────────────────────

    /**
     * <b>{@code EntryKind.of} agrees with what the entry's own value determines</b>, over every entry of the
     * three bundled schemas plus a schema exercising each declaration form. An entry's kind is its value's
     * type's kind ([TSON-SCHEMA] §4.1), derived and never written, so nothing but this test stands between
     * the derivation and a model that quietly stops agreeing with the rule it states.
     *
     * <p><b>The rule has four branches and two of them are special cases</b>: branch 4 is a namespace lookup,
     * and it reaches an <em>imported</em> schema rather than the entry's own for most of core.tn --
     * {@code integer} is {@code !integer_type {}} and {@code integer_type} is meta.tn's -- which the value's
     * Java shape answers on its own, a body's class being the branch of {@code Top} its constructor builds.
     *
     * <p><b>The rule is total.</b> An open record takes the kind its chain reaches, as a closed record does, answered
     * from the entry alone. {@link #derive} is therefore the whole rule, and the one §8.1 states under "Kind is
     * derived".
     */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void kindAgreesWithWhatTheEntryAlreadyStates() {
        Tson tson = tson();
        tson.resolve("""
                !!id:"https://example.com/kinds.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  port   => !numeric.integer_type { min: 0  max: 65535 }
                  age    => !int32 ^ { min: 0  max: 150 }
                  renamed => port
                  colour => !enum [RED GREEN BLUE]
                  point  => { x: int32  y: int32 }
                  named  => point & { label: text }
                  boxes  => [point]
                  lookup => { text => point }
                  either => ( point | colour )
                  trip   => [int32, text]
                  pair   => <A, B> { first: A  second: B }
                  holder => { p: pair<text, int32> }
                }
                """);
        // A schema whose templates compose onto a base kind: each application closes to a constructor,
        // whose kind is its chain's base kind and not its !record body's PRODUCT.
        tson.resolve("""
                !!id:"https://example.com/kind-constructors.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  listed      => <T> meta.atom & { members: [T] }
                  listed_text => listed<text>
                }
                """);
        List<String> ids = List.of(TsonBundledSchemas.META_ID, TsonBundledSchemas.NUMERIC_ID,
                TsonBundledSchemas.TEMPORAL_ID, TsonBundledSchemas.NET_ID, TsonBundledSchemas.CORE_ID,
                "https://example.com/kinds.tn", "https://example.com/kind-constructors.tn");

        Map<String, Top> universe = new LinkedHashMap<>();
        Map<String, Map<String, Top>> perSchema = new LinkedHashMap<>();
        for (String id : ids) {
            TsonLinkedSchema linked = tson.bindRegistry().core().resolveLinked(id);
            String own = CanonicalIdentity.canonicalize(id);
            // The entries the schema declares itself: its linked namespace holds its whole closure too.
            Map<String, Top> entries = new LinkedHashMap<>();
            linked.schema().entries().entries().forEach((name, definition) -> {
                if (linked.originOf(name).equals(own)) {
                    entries.put(name, definition);
                }
            });
            perSchema.put(id, entries);
            entries.forEach(universe::putIfAbsent);
        }

        List<String> mismatches = new ArrayList<>();
        int closed = 0;
        int open = 0;
        int neededTheGoverningMeta = 0;
        for (String id : ids) {
            for (Map.Entry<String, Top> entry : perSchema.get(id).entrySet()) {
                Top definition = entry.getValue();
                if (io.ltr8.tson.schema.SchemaPaths.isNestedMap(definition)) {
                    continue; // the kernel's `meta`: a value of meta.schema, whose members are checked on their own
                }
                if (OpenEntry.isOpen(definition)) {
                    open++;
                }
                closed++;
                if (!TypeChain.supertypesOf(definition).contains("meta.top") && !(definition instanceof Alias)
                        && !OpenEntry.isOpen(definition)
                        && !perSchema.get(id).containsKey(headOf(definition))) {
                    neededTheGoverningMeta++;
                }
                EntryKind derived = derive(definition, universe);
                if (derived != EntryKind.of(definition, n -> true)) {
                    mismatches.add(id + "#" + entry.getKey() + ": states " + EntryKind.of(definition, n -> true)
                            + " but derives " + derived);
                }
            }
        }

        int closedEntries = closed;
        int openEntries = open;
        int outsideOwnSchema = neededTheGoverningMeta;
        assertEquals(List.of(), mismatches, "kind restates what supertypes and body already determine");
        assertTrue(closedEntries > 140, () -> "not enough entries for this to mean anything: " + closedEntries);
        assertTrue(openEntries > 0, () -> "no open record exercised the rule: " + openEntries);
        assertTrue(outsideOwnSchema > 25, () -> "the lookup should reach outside the entry's own schema for "
                + "most of core.tn's instances -- that is why the field stays: " + outsideOwnSchema + " of "
                + closedEntries);
    }

    /** §4.1's rule, read off what the entry already states. Four branches; see the test's own note. */
    private static EntryKind derive(Top definition, Map<String, Top> universe) {
        if (definition instanceof Alias) {
            // §4.1 gives an alias the kind ALIAS, a meta.type_ref and not a base kind -- and the kernel's `alias`
            // constructor is itself PRODUCT, so branch 4 would give the wrong answer here.
            return EntryKind.ALIAS;
        }
        if (TypeChain.supertypesOf(definition).contains("meta.top")) {
            // A constructor: its kind states what its *instances* will be, not what its own body is --
            // `integer_type => atom & { ... }` has a !record body and kind ATOM.
            for (String supertype : TypeChain.supertypesOf(definition)) {
                switch (supertype) {
                    case "meta.atom" -> { return EntryKind.ATOM; }
                    case "meta.sum" -> { return EntryKind.SUM; }
                    default -> { }
                }
            }
            return EntryKind.PRODUCT;
        }
        Top constructor = universe.get(headOf(definition));
        assertNotNull(constructor, "no entry for the body's constructor head " + headOf(definition));
        return EntryKind.of(constructor, n -> true);
    }

    /** The constructor name a body identifies as -- the {@code !head} a resolved document writes on it. */
    private static String headOf(Top body) {
        return switch (body) {
            case Atom atom -> atom.getClass().getAnnotation(io.ltr8.annotation.Typename.class).name();
            case Product product -> product.getClass().getAnnotation(io.ltr8.annotation.Typename.class).name();
            case Sum sum -> sum.getClass().getAnnotation(io.ltr8.annotation.Typename.class).name();
            case Alias alias -> "alias";
            case io.ltr8.tson.schema.meta.ValueEntry value -> value.type();
        };
    }
}
