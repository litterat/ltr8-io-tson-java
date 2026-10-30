package io.ltr8.tson;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Product;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Sum;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.EntryKind;
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
 * <p>An open entry's body is an instance of the kernel's {@code template} constructor -- the parameter
 * names, and the application as <b>text</b> -- because a body carrying a parameter in a value slot
 * ({@code min_items: N}) is not a value of any constructor's record shape and cannot be typed as one
 * (§8.1's "Open entries"). The tests below hold that against real documents: the published fixtures
 * validate, §5.10's own {@code vector} example validates, and a held body reads back as the application it
 * holds rather than as the constructor it names.
 *
 * <p><b>Each pins a failure the text form exists to prevent</b>, which is what makes them a guard rather than a
 * description: read as the constructor it names, the value-parameter case fails with two
 * {@code ATOM_CONSTRAINT_VIOLATION}s, and the held case binds {@code S} as a relative URI -- the two failures
 * §8.1 cites as the reason a held body is text.
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
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaMetaNameBinder.defaultContext()));
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

    // ── What holds today, and must go on holding ─────────────────────────

    /**
     * <b>The published resolver-output fixtures are valid data under the schema each names.</b> §1.3 makes
     * producing a resolved schema value a MUST and §8 fixes its serialization, so this is the baseline claim
     * of §8's whole output form -- and the fixtures are the spec's own statement of it, not this
     * implementation's.
     *
     * <p>It passes because the only templates the bundled schemas declare put every parameter in a {@code
     * type_ref} slot ({@code set<T>}, {@code kind<T>}, {@code tuple1<T>}), which is exactly the
     * case §8.1's premise covers. It is a regression guard, not evidence that the premise is sound; the next
     * test is the counterexample.
     */
    @Test
    void everyPublishedResolvedFixtureValidatesAgainstItsGoverningSchema() throws Exception {
        Tson tson = tson();
        for (String fixture : List.of("meta-kernel-resolved.tn", "meta-resolved.tn", "core-resolved.tn")) {
            List<Diagnostic> diagnostics =
                    ResolvedForm.validateResolved(tson, Files.readString(specDirectory().resolve(fixture)));
            assertEquals(List.of(), diagnostics, () -> fixture + " does not validate against the schema it names");
        }
    }

    // ── What §5.10's own example does, with a held body as text ──────

    /**
     * <b>§5.10's {@code vector} example validates in §8.1's own prescribed form.</b> The declaration is
     *
     * <pre>{@code vector => <T, N> !array { element_type: T  min_items: N  max_items: N }}</pre>
     *
     * and §5.10 introduces value parameters with precisely this shape -- "a parameter in a value slot
     * ({@code min_items: N}) ... is a token like any other". Its resolved body is a {@code !template} whose
     * {@code template} field is the application as written, so there is no value slot for {@code N} to fail
     * against and the entry's value is a {@code top} like any other.
     *
     * <p>Read as the {@code !array} it names, the body would fail with two {@code ATOM_CONSTRAINT_VIOLATION}s
     * -- {@code N} is not a {@code non_negative_integer} -- which is the case §8.1 names when it says a body
     * carrying a parameter in a value slot "is not a value of any constructor's record shape".
     *
     * <p>The document is hand-authored in §8.1's form rather than produced by this library's writer,
     * deliberately: the claim under test is about the <em>spec's</em> prescribed serialization, and routing
     * it through a writer would put this implementation's own canonicalisation between the claim and the
     * verdict.
     */
    @Test
    void anOpenEntryWithAValueParameterValidates() {
        assertEquals(List.of(), ResolvedForm.validateResolved(tson(), """
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !schema {
                  vector => !template {
                    parameters: [ { name: T  type: type_ref } { name: N  type: non_negative_integer } ]
                    template: "!array { element_type: T  min_items: N  max_items: N }"
                  }
                }
                """), "a value parameter has no slot to fail against once the body is held as text");
    }

    /**
     * <b>And a held body is read back as held, not as the constructor it names.</b> Read as the constructor
     * it names, this would validate clean and bind as something else -- {@code M} as a third enum member
     * spelled {@code M} -- accepted, and wrong, with no diagnostic anywhere. What comes back is the
     * application as text, with nothing having tried to read it.
     */
    @Test
    void aHeldBodyReadsBackAsTheApplicationItHolds() {
        Tson tson = metaBoundTson();
        String resolved = """
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  e => !template {
                    parameters: [ { name: M  type: text } ]
                    template: "!enum { members: [a b M] }"
                  }
                }
                """;
        assertEquals(List.of(), ResolvedForm.validateResolved(tson, resolved), "the open entry is valid data");

        Map<String, Object> read = ResolvedForm.readResolved(tson, resolved);

        TemplateBody e = assertInstanceOf(TemplateBody.class, read.get("e"));
        assertEquals("!enum { members: [a b M] }", e.template(),
                "the application as written -- no third enum member spelled 'M'");
        assertEquals(List.of(new TemplateParam("M", TypeRef.of("text"))), e.parameters());
    }

    // ── The biconditional an entry's parameters rest on ─────────────────

    /**
     * <b>A record template is a record with parameters, and every other open entry is held.</b> [TSON-SCHEMA]
     * §5.10's two kinds of template, asked of every entry of every schema -- the three bundled ones and a schema
     * exercising each template shape: an entry with parameters is either a {@code RecordBody} or a {@code
     * TemplateBody}, a held body never applies {@code record}, and every {@code TemplateBody} declares
     * parameters.
     */
    @Test
    void aRecordTemplateIsARecordAndEveryOtherTemplateIsHeld() {
        Tson tson = tson();
        tson.resolve("""
                !!id:"https://example.com/shapes.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  base           => { id: text }
                  pair           => <A, B> { first: A  second: B }
                  uuid_pair      => <B> pair<text, B>
                  boxes          => <T> [T]
                  text_keyed_map => <V> {text => V}
                  vec            => <T, N> !array { element_type: T  min_items: N  max_items: N }
                  composed       => <T> base & { value: T }
                  holder         => { p: pair<text, int32>  q: vec<int32, 3> }
                }
                """);
        List<String> ids = List.of(TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID,
                TsonBundledSchemas.CORE_ID, "https://example.com/shapes.tn");

        List<String> broken = new ArrayList<>();
        int open = 0;
        for (String id : ids) {
            for (Map.Entry<String, Top> entry
                    : tson.bindRegistry().core().resolveLinked(id).schema().entries().entrySet()) {
                Top definition = entry.getValue();
                boolean declares = !OpenEntry.parameterNames(definition).isEmpty();
                if (declares) {
                    open++;
                }
                boolean shape = definition instanceof TemplateBody held
                        ? !held.template().startsWith("!record") && declares
                        : !declares || definition instanceof RecordBody;
                if (!shape) {
                    broken.add(id + "#" + entry.getKey() + ": parameters " + OpenEntry.parameterNames(definition)
                            + " with a " + definition.getClass().getSimpleName() + " body");
                }
            }
        }

        int openEntries = open;
        assertEquals(List.of(), broken, "§5.10: a record template is a record, every other template is held");
        assertTrue(openEntries >= 8, () -> "not enough open entries to mean anything: " + openEntries);
    }

    // ── §8.1: a kind is derived, never recorded ─────────────────────────

    /**
     * <b>{@code EntryKind.of} agrees with what the entry's own value determines</b>, over every entry of the
     * three bundled schemas plus a schema exercising each declaration form. An entry's kind is its value's
     * type's kind ([TSON-SCHEMA] §4.1), derived and never written, so nothing but this test stands between
     * the derivation and a model that quietly stops agreeing with the rule it states.
     *
     * <p><b>The rule has four branches and two of them are special cases</b>: branch 4 is a namespace lookup,
     * and it reaches the <em>governing meta</em> rather than the entry's own schema for most of core.tn --
     * {@code integer} is {@code !integer_type {}} and {@code integer_type} is meta.tn's -- which the value's
     * Java shape answers on its own, a body's class being the branch of {@code Top} its constructor builds.
     *
     * <p><b>The rule is total.</b> An open entry is {@code TEMPLATE}, answered from the entry alone rather than
     * inherited from the constructor its held body applies -- that would be a fact about an application of it,
     * not about the template. {@link #derive} is
     * therefore the whole rule, and the one §8.1 states under "Kind is derived".
     */
    @Test
    void kindAgreesWithWhatTheEntryAlreadyStates() {
        Tson tson = tson();
        tson.resolve("""
                !!id:"https://example.com/kinds.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  port   => !integer_type { min: 0  max: 65535 }
                  age    => !int32 ^ { min: 0  max: 150 }
                  alias  => port
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
        // A meta layer whose templates compose onto a base kind: each application closes to a constructor,
        // whose kind is its chain's base kind and not its !record body's PRODUCT.
        tson.resolve("""
                !!id:"https://example.com/kind-constructors.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  listed      => <T> atom & { members: [T] }
                  listed_text => listed<text>
                  payloads     => <T> !namespace { key_type: identifier  value_type: T }
                  iri_payloads => payloads<iri>
                }
                """);
        List<String> ids = List.of(TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID,
                TsonBundledSchemas.CORE_ID, "https://example.com/kinds.tn",
                "https://example.com/kind-constructors.tn");

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
                if (OpenEntry.isOpen(definition)) {
                    open++;
                }
                closed++;
                if (!TypeChain.supertypesOf(definition).contains("top") && !(definition instanceof Reference)
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
        assertTrue(closedEntries > 150, () -> "not enough entries for this to mean anything: " + closedEntries);
        assertTrue(openEntries > 0, () -> "no open entry exercised the TEMPLATE branch: " + openEntries);
        assertTrue(outsideOwnSchema > 40, () -> "the lookup should reach outside the entry's own schema for "
                + "most of core.tn -- that is why the field stays: " + outsideOwnSchema + " of " + closedEntries);
    }

    /** §4.1's rule, read off what the entry already states. Four branches; see the test's own note. */
    private static EntryKind derive(Top definition, Map<String, Top> universe) {
        if (definition instanceof TemplateBody) {
            // A held template is not a type (§5.10), so its kind says that and nothing about what applying it
            // produces -- that is the kind of the entry materialisation mints, from its own closed body. A record
            // template is a type, the family base, and falls to the record rules below.
            return EntryKind.TEMPLATE;
        }
        if (definition instanceof Reference) {
            // §4.1 gives an alias REFERENCE, a type_kind and not a base kind -- and the kernel's `reference`
            // constructor is itself PRODUCT, so branch 4 would give the wrong answer here.
            return EntryKind.REFERENCE;
        }
        if (TypeChain.supertypesOf(definition).contains("top")) {
            // A constructor: its kind states what its *instances* will be, not what its own body is --
            // `integer_type => atom & { ... }` has a !record body and kind ATOM.
            for (String supertype : TypeChain.supertypesOf(definition)) {
                switch (supertype) {
                    case "atom" -> { return EntryKind.ATOM; }
                    case "sum" -> { return EntryKind.SUM; }
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
            case Reference reference -> "reference";
            case TemplateBody held -> "template";
            case io.ltr8.tson.schema.meta.ValueEntry value -> value.type();
        };
    }
}
