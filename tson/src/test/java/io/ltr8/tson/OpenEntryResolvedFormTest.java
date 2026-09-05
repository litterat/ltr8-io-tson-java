package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.Product;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Sum;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
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
 * What [TSON-SCHEMA] §8.1 claims about an <b>open</b> entry's resolved form, asked of real documents.
 *
 * <p>§8.1 says an open entry "is a {@code type_definition} like any other ... typed by the kernel's {@code
 * schema} without a second value shape, since a parameter reference is an {@code identifier} where a type
 * name is". That premise holds only where every parameter stands in a {@code type_ref} slot, and §5.10 does
 * not confine them there. The three tests below pin what actually happens, in the order the register
 * (`SPEC-FEEDBACK.md` #5) states it: the published fixtures validate, a value parameter does not validate at
 * all, and a parameter that happens to typecheck is read as something else entirely.
 *
 * <p><b>Two of these assert the defect rather than the fix.</b> They are written as characterisation tests
 * so this stack stays green while the change lands across several commits, and each says at its own head
 * what it becomes when #5 does: {@link #anOpenEntryWithAValueParameterDoesNotValidate} inverts to a clean
 * validation, and {@link #anOpenEntryWhoseParametersTypecheckIsReadAsSomethingElse} loses its second half
 * once nothing reads a held body as values. Deleting either instead of inverting it would leave the fix
 * unguarded.
 */
class OpenEntryResolvedFormTest {

    private static Tson tson() {
        return Tson.builder().build();
    }

    /**
     * The same front door with the internal {@code schema.meta} vocabulary bound, for the one test that reads
     * a resolved document <em>back</em> rather than validating it -- the binding is what makes the misreading
     * visible as a value rather than as a clean verdict.
     */
    private static Tson metaBoundTson() {
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build();
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
     * type_ref} slot ({@code set<T>}, {@code extern_of<S>}, {@code extern_type<S, T>}), which is exactly the
     * case §8.1's premise covers. It is a regression guard, not evidence that the premise is sound; the next
     * test is the counterexample.
     */
    @Test
    void everyPublishedResolvedFixtureValidatesAgainstItsGoverningSchema() throws Exception {
        Tson tson = tson();
        for (String fixture : List.of("meta-kernel-resolved.tn", "meta-resolved.tn", "core-resolved.tn")) {
            List<Diagnostic> diagnostics = tson.validate(Files.readString(specDirectory().resolve(fixture)));
            assertEquals(List.of(), diagnostics, () -> fixture + " does not validate against the schema it names");
        }
    }

    // ── What §5.10's own example does, now that a held body is text ──────

    /**
     * <b>§5.10's {@code vector} example validates in §8.1's own prescribed form.</b> The declaration is
     *
     * <pre>{@code vector => <T, N> !array { element_type: T  min_items: N  max_items: N }}</pre>
     *
     * and §5.10 introduces value parameters with precisely this shape -- "a parameter in a value slot
     * ({@code min_items: N}) ... is a token like any other". Its resolved body is a {@code !template} whose
     * {@code template} field is the application as written, so there is no value slot for {@code N} to fail
     * against and the entry is a {@code type_definition} like any other.
     *
     * <p>This is the assertion {@code SPEC-FEEDBACK.md} #5 exists for. Before the {@code template}
     * constructor it failed with two {@code ATOM_CONSTRAINT_VIOLATION}s -- {@code N} is not a
     * {@code non_negative_integer} -- against §8.1's claim that an open entry is "typed by the kernel's
     * {@code schema} without a second value shape".
     *
     * <p>The document is hand-authored in §8.1's form rather than produced by this library's writer,
     * deliberately: the claim under test is about the <em>spec's</em> prescribed serialization, and routing
     * it through a writer would put this implementation's own canonicalisation between the claim and the
     * verdict.
     */
    @Test
    void anOpenEntryWithAValueParameterValidates() {
        assertEquals(List.of(), tson().validate("""
                !!schema:"https://tson.io/2026/35/m/meta-kernel.tn"
                !schema {
                  vector => !type_definition {
                    kind: PRODUCT
                    source: array
                    body: !template {
                      parameters: [T N]
                      template: "!array { element_type: T  min_items: N  max_items: N }"
                    }
                  }
                }
                """), "a value parameter has no slot to fail against once the body is held as text");
    }

    /**
     * <b>And a held body is read back as held, not as the constructor it names.</b> Before the
     * {@code template} constructor these two validated clean and bound as something else -- {@code S} as a
     * schema identity literally named {@code S}, {@code M} as a third enum member spelled {@code M} -- which
     * is the half of {@code SPEC-FEEDBACK.md} #5 that produced no diagnostic anywhere.
     *
     * <p>{@code extern_of} is core.tn's own declaration. What comes back now is the application as text,
     * with nothing having tried to read it.
     */
    @Test
    void aHeldBodyReadsBackAsTheApplicationItHolds() {
        Tson tson = metaBoundTson();
        String resolved = """
                !!schema:"https://tson.io/2026/35/m/meta.tn"
                !schema {
                  extern_of => !type_definition {
                    kind: SUM
                    source: scoped
                    body: !template {
                      parameters: [S]
                      template: "!scoped { scope: [EXTERN]  schemas: { S => _ } }"
                    }
                  }
                  e => !type_definition {
                    kind: ATOM
                    source: enum
                    body: !template { parameters: [M]  template: "!enum { members: [a b M] }" }
                  }
                }
                """;
        assertEquals(List.of(), tson.validate(resolved), "both open entries are valid data");

        Map<String, TypeDefinition> read = ResolvedForm.readResolved(tson, resolved);

        TemplateBody externOf = assertInstanceOf(TemplateBody.class, read.get("extern_of").body());
        assertEquals(List.of("S"), externOf.parameters());
        assertEquals("!scoped { scope: [EXTERN]  schemas: { S => _ } }", externOf.template(),
                "the application as written -- no URI named 'S' anywhere");

        TemplateBody e = assertInstanceOf(TemplateBody.class, read.get("e").body());
        assertEquals("!enum { members: [a b M] }", e.template(),
                "the application as written -- no third enum member spelled 'M'");
    }

    // ── The biconditional `type_definition.parameters` rests on ─────────

    /**
     * <b>An entry declares parameters exactly when its body is held, and the two lists agree.</b>
     * [TSON-SCHEMA] §5.10's "Closed entries are parameter-free" is structural now rather than a MUST anyone
     * can violate: {@code TypeDefinition.parameters()} reads the held body's own list, so a closed entry has
     * nowhere to put one. This asks it of every entry of every schema -- the three bundled ones and a schema
     * exercising each template shape -- which is the population the rule is about, and it is what would catch
     * the derivation silently answering {@code []} for an entry that is genuinely open.
     *
     * <p><b>It is a property of a published entry, not of every {@code TypeDefinition} ever built.</b>
     * Resolution legitimately constructs an un-held intermediate and converts it: {@code holdIfOpen} takes a
     * resolved record body and replaces it with the held form, and is handed the parameter list separately
     * for exactly that reason. So the invariant cannot be an assertion in the constructor -- it would fire on
     * the value about to be converted -- and belongs here, over entries a schema actually holds.
     */
    @Test
    void anEntryDeclaresParametersExactlyWhenItsBodyIsHeld() {
        Tson tson = tson();
        tson.resolve("""
                !!id:"https://example.com/shapes.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
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
            for (Map.Entry<String, TypeDefinition> entry
                    : tson.bindRegistry().core().resolveLinked(id).schema().entries().entrySet()) {
                TypeDefinition definition = entry.getValue();
                boolean held = definition.body() instanceof TemplateBody;
                if (held) {
                    open++;
                }
                if (definition.parameters().isEmpty() == held) {
                    broken.add(id + "#" + entry.getKey() + ": parameters " + definition.parameters()
                            + " with a " + definition.body().getClass().getSimpleName() + " body");
                } else if (definition.body() instanceof TemplateBody body
                        && !body.parameters().equals(definition.parameters())) {
                    broken.add(id + "#" + entry.getKey() + ": entry states " + definition.parameters()
                            + " and its held body states " + body.parameters());
                }
            }
        }

        int openEntries = open;
        assertEquals(List.of(), broken, "§5.10: only template entries are open, and they agree with their body");
        assertTrue(openEntries >= 8, () -> "not enough open entries to mean anything: " + openEntries);
    }

    // ── SPEC-FEEDBACK #6: kind restates what the entry already says ──────

    /**
     * <b>{@code type_definition.kind} agrees with what the entry's own {@code supertypes} and {@code body}
     * determine</b>, over every entry of the three bundled schemas plus a schema exercising each declaration
     * form. §8.1 derives the field at resolution and never asks anything to verify it -- it appears nowhere
     * in the Ingest paragraph's list of what must be discarded, recomputed or verified -- so nothing but this
     * test stands between a forged {@code kind} and a schema that reads by it (`SPEC-FEEDBACK.md` #6).
     *
     * <p><b>The rule has four branches and two of them are special cases</b>, which is the argument for
     * keeping the field rather than deleting it as redundant: branch 4 is a namespace lookup, and it reaches
     * the <em>governing meta</em> rather than the entry's own schema for most of core.tn -- {@code integer}
     * is {@code !integer_type {}} and {@code integer_type} is meta.tn's. A consumer holding one schema's
     * resolved output cannot derive what this field states.
     *
     * <p><b>When #5 and #6 land, branch 1 becomes {@code TypeKind.TEMPLATE}</b> rather than a skip, and
     * {@link #derive} is the rule §8.1's Ingest paragraph should carry.
     */
    @Test
    void kindAgreesWithWhatTheEntryAlreadyStates() {
        Tson tson = tson();
        tson.resolve("""
                !!id:"https://example.com/kinds.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
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
        List<String> ids = List.of(TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID,
                TsonBundledSchemas.CORE_ID, "https://example.com/kinds.tn");

        Map<String, TypeDefinition> universe = new LinkedHashMap<>();
        Map<String, Map<String, TypeDefinition>> perSchema = new LinkedHashMap<>();
        for (String id : ids) {
            Map<String, TypeDefinition> entries = tson.bindRegistry().core().resolveLinked(id).schema().entries();
            perSchema.put(id, entries);
            entries.forEach(universe::putIfAbsent);
        }

        List<String> mismatches = new ArrayList<>();
        int closed = 0;
        int neededTheGoverningMeta = 0;
        for (String id : ids) {
            for (Map.Entry<String, TypeDefinition> entry : perSchema.get(id).entrySet()) {
                TypeDefinition definition = entry.getValue();
                if (definition.body() instanceof TemplateBody) {
                    // Branch 1: an open entry's kind is the one case the rule cannot answer from the entry
                    // alone -- it is inherited from the constructor the held body applies. #5's `kind:
                    // TEMPLATE` is what closes it.
                    continue;
                }
                closed++;
                if (!definition.supertypes().contains("top") && !(definition.body() instanceof Reference)
                        && !perSchema.get(id).containsKey(headOf(definition.body()))) {
                    neededTheGoverningMeta++;
                }
                TypeKind derived = derive(definition, universe);
                if (derived != definition.kind()) {
                    mismatches.add(id + "#" + entry.getKey() + ": states " + definition.kind()
                            + " but derives " + derived);
                }
            }
        }

        int closedEntries = closed;
        int outsideOwnSchema = neededTheGoverningMeta;
        assertEquals(List.of(), mismatches, "kind restates what supertypes and body already determine");
        assertTrue(closedEntries > 200, () -> "not enough entries for this to mean anything: " + closedEntries);
        assertTrue(outsideOwnSchema > 50, () -> "the lookup should reach outside the entry's own schema for "
                + "most of core.tn -- that is why the field stays: " + outsideOwnSchema + " of " + closedEntries);
    }

    /** §4.1's rule, read off what the entry already states. Four branches; see the test's own note. */
    private static TypeKind derive(TypeDefinition definition, Map<String, TypeDefinition> universe) {
        if (definition.body() instanceof Reference) {
            // §4.1 gives an alias REFERENCE, a type_kind and not a base kind -- and the kernel's `reference`
            // constructor is itself PRODUCT, so branch 4 would give the wrong answer here.
            return TypeKind.REFERENCE;
        }
        if (definition.supertypes().contains("top")) {
            // A constructor: its kind states what its *instances* will be, not what its own body is --
            // `integer_type => atom & { ... }` has a !record body and kind ATOM.
            for (String supertype : definition.supertypes()) {
                switch (supertype) {
                    case "atom" -> { return TypeKind.ATOM; }
                    case "sum" -> { return TypeKind.SUM; }
                    case "data" -> { return TypeKind.DATA; }
                    default -> { }
                }
            }
            return TypeKind.PRODUCT;
        }
        TypeDefinition constructor = universe.get(headOf(definition.body()));
        assertNotNull(constructor, "no entry for the body's constructor head " + headOf(definition.body()));
        return constructor.kind();
    }

    /** The constructor name a body identifies as -- the {@code !head} a resolved document writes on it. */
    private static String headOf(Top body) {
        return switch (body) {
            case Atom atom -> atom.getClass().getAnnotation(io.ltr8.annotation.Typename.class).name();
            case Product product -> product.getClass().getAnnotation(io.ltr8.annotation.Typename.class).name();
            case Sum sum -> sum.getClass().getAnnotation(io.ltr8.annotation.Typename.class).name();
            case Reference reference -> "reference";
            case Data data -> data.getClass().getAnnotation(io.ltr8.annotation.Typename.class).name();
            case TemplateBody held -> "template";
        };
    }
}
