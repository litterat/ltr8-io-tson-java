package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.Product;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Scoped;
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

    // ── What does not, and is the reason SPEC-FEEDBACK #5 is open ────────

    /**
     * <b>§5.10's own {@code vector} example does not validate in §8.1's own prescribed form.</b> The
     * declaration is
     *
     * <pre>{@code vector => <T, N> !array { element_type: T  min_items: N  max_items: N }}</pre>
     *
     * and §5.10 introduces value parameters with precisely this shape -- "a parameter in a value slot
     * ({@code min_items: N}) ... is a token like any other". §8.1 then says the resolved entry is typed by
     * the kernel's {@code schema}. It is not: {@code min_items} is {@code non_negative_integer?} and
     * {@code N} is an identifier, so the body fails to read as the {@code top} the kernel declares.
     *
     * <p>The document below is hand-authored in §8.1's form rather than produced by this library's writer,
     * deliberately: the claim under test is about the <em>spec's</em> prescribed serialization, and routing
     * it through a writer would put this implementation's own canonicalisation between the claim and the
     * verdict.
     *
     * <p><b>When #5 lands this inverts</b> -- the same declaration resolves to a {@code !template} body whose
     * {@code template} field is text, the document validates clean, and this asserts {@code List.of()}.
     */
    @Test
    void anOpenEntryWithAValueParameterDoesNotValidate() {
        List<Diagnostic> diagnostics = tson().validate("""
                !!schema:"https://tson.io/2026/35/m/meta-kernel.tn"
                !schema {
                  vector => !type_definition {
                    kind: PRODUCT
                    source: array
                    parameters: [T N]
                    body: !array { element_type: T  min_items: N  max_items: N }
                  }
                }
                """);

        List<String> pointers = diagnostics.stream().map(d -> d.path().orElse("?")).toList();
        assertEquals(List.of("/vector/body/min_items", "/vector/body/max_items"), pointers,
                "§8.1 says this reads as a type_definition; it does not, and these are the two slots that fail");
        assertTrue(diagnostics.stream().allMatch(d -> d.code() == Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                () -> "each failure is the value slot refusing an identifier: " + diagnostics);
    }

    /**
     * <b>And where the premise happens to hold, it holds by accident and the entry is read as something
     * else.</b> This is the worse half of #5: the failing case at least fails.
     *
     * <p>{@code extern_of} is core.tn's own declaration, and its resolved form validates clean -- because
     * {@code S} in a map-key position is a well-formed relative URI, so the parameter binds as a
     * <em>schema identity literally named {@code S}</em>. Likewise {@code M} in an enum's member list binds
     * as a third member spelled {@code M}. A conforming ingest following §8.1 accepts both and builds a
     * schema that means something the author did not write, with no diagnostic anywhere.
     *
     * <p><b>When #5 lands the second half of this goes</b>: with the body held as text there is nothing left
     * to misread, and what remains is the first half -- that the document is valid data.
     */
    @Test
    void anOpenEntryWhoseParametersTypecheckIsReadAsSomethingElse() {
        Tson tson = metaBoundTson();
        String resolved = """
                !!schema:"https://tson.io/2026/35/m/meta.tn"
                !schema {
                  extern_of => !type_definition {
                    kind: SUM
                    source: scoped
                    parameters: [S]
                    body: !scoped { scope: [EXTERN]  schemas: { S => _ } }
                  }
                  e => !type_definition {
                    kind: ATOM
                    source: enum
                    parameters: [M]
                    body: !enum { members: [a b M] }
                  }
                }
                """;
        assertEquals(List.of(), tson.validate(resolved), "both open entries are accepted as ordinary data");

        Map<String, TypeDefinition> read = ResolvedForm.readResolved(tson, resolved);

        Top externOf = read.get("extern_of").body();
        assertTrue(externOf instanceof Scoped, () -> "read back as a scoped body, not as a held one: " + externOf);
        assertEquals(List.of("S"), ((Scoped) externOf).schemas().orElseThrow().keySet().stream()
                        .map(java.net.URI::toString).toList(),
                "the parameter bound as a schema identity -- a URI literally named 'S'");

        Top enumBody = read.get("e").body();
        assertTrue(enumBody instanceof EnumBody, () -> "read back as an enum body: " + enumBody);
        assertEquals(List.of("a", "b", "M"), ((EnumBody) enumBody).members(),
                "the parameter bound as a third enum member spelled 'M'");
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
