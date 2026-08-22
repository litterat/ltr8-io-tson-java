package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonContentHashMismatchException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code !!import} contributes an imported schema's <b>whole namespace</b>, its own imports included, and a
 * collision is decided by entry <b>identity</b> rather than by name occurrence -- a deliberate divergence
 * from [TSON-SCHEMA] §2.2.3's "imports are shallow", argued in {@code SPEC-FEEDBACK.md} #55.
 *
 * <p>The two halves are one rule. Because the namespace is flat and transitive, the diamond every practical
 * schema forms -- two imports that both import core.tn -- must unify rather than collide; because it is flat,
 * a name may denote exactly one type, so nothing may shadow or redefine a name the closure already binds, and
 * two genuinely different schemas claiming one name is a hard error.
 */
class TransitiveImportTest {

    private static final String CORE = "https://tson.io/2026/32/m/core.tn";
    private static final String META = "https://tson.io/2026/32/m/meta.tn";
    private static final String CORE_SHA256 = TsonBundledSchemas.CORE_SHA256;

    /** A leaf schema: imports core.tn (as every practical schema does) and declares one record of its own. */
    private static String leaf(String id, String typeName) {
        return """
                !!id:"%s"
                !!meta:"%s"
                !!import:"%s"
                {
                  %s => { name: text }
                }
                """.formatted(id, META, CORE, typeName);
    }

    /** A schema importing {@code importId} and re-exposing one declaration of its own. */
    private static String derived(String id, String importId, String typeName) {
        return """
                !!id:"%s"
                !!meta:"%s"
                !!import:"%s"
                {
                  %s => { inner: widget }
                }
                """.formatted(id, META, importId, typeName);
    }

    /**
     * The diamond. {@code a} and {@code b} each import core.tn and declare one type; {@code c} imports both.
     * core's entries arrive by two routes, but they are one set of entries from one schema, so they unify.
     */
    @Test
    void aSchemaMayImportTwoSchemasThatBothImportCore() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/a-1.tn", "alpha"));
        tson.resolve(leaf("https://example.test/b-1.tn", "beta"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/c-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://example.test/a-1.tn"
                !!import:"https://example.test/b-1.tn"
                {
                  pair => { left: alpha  right: beta }
                }
                """);

        assertTrue(problems.isEmpty(), () -> "core.tn reached through both imports is one schema, so it "
                + "unifies rather than colliding, but got: " + problems);
    }

    /** The same diamond with core.tn named explicitly too -- a third route to the same entries, still one set. */
    @Test
    void aSchemaMayImportCoreAlongsideTwoSchemasThatAlsoImportIt() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/a-2.tn", "alpha"));
        tson.resolve(leaf("https://example.test/b-2.tn", "beta"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/c-2.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                !!import:"https://example.test/a-2.tn"
                !!import:"https://example.test/b-2.tn"
                {
                  pair => { left: alpha  right: beta  label: text }
                }
                """);

        assertTrue(problems.isEmpty(), () -> problems.toString());
    }

    /**
     * The transitive half: {@code d} imports only {@code a-3.tn} and names {@code text}, which {@code a-3.tn}
     * reaches through its own import of core.tn. The flat namespace makes it available -- the behaviour
     * §2.2.3 forbids and #55 argues for, and the same rule §3.3.1 already states for {@code !!meta}.
     */
    @Test
    void aNameReachedThroughAnImportsOwnImportIsInScope() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/a-3.tn", "alpha"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/d-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://example.test/a-3.tn"
                {
                  tagged => alpha & { label: text }
                }
                """);

        assertTrue(problems.isEmpty(), () -> "'text' arrives through a-3.tn's own import of core.tn and the "
                + "namespace is flat, so it is in scope, but got: " + problems);
    }

    /** Listing one schema twice is redundant, not a collision with itself. */
    @Test
    void oneSchemaNamedTwiceIsRedundantNotACollision() {
        Tson tson = Tson.builder().build();

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/dup-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  thing => { label: text }
                }
                """);

        assertTrue(problems.isEmpty(), () -> "one schema named twice is one namespace, not a collision "
                + "with itself, but got: " + problems);
    }

    /**
     * <b>Revision skew is a hard error.</b> {@code p} and {@code q} reach two different schemas that each
     * declare {@code widget} -- the shape a spec-revision bump produces, core.tn's identity carrying
     * {@code /2026/32/} in its path. The two {@code widget}s are distinct entries, so a schema importing both
     * routes cannot build a flat namespace, and says so at namespace-construction time rather than failing
     * later on a field conflict between two identically-spelled types.
     */
    @Test
    void twoDifferentSchemasDeclaringOneNameCollideEvenWhenReachedTransitively() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/shared-32.tn", "widget"));
        tson.resolve(leaf("https://example.test/shared-33.tn", "widget"));
        tson.resolve(derived("https://example.test/p-1.tn", "https://example.test/shared-32.tn", "panel"));
        tson.resolve(derived("https://example.test/q-1.tn", "https://example.test/shared-33.tn", "quilt"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/r-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://example.test/p-1.tn"
                !!import:"https://example.test/q-1.tn"
                {
                  both => { one: panel  two: quilt }
                }
                """);

        assertFalse(problems.isEmpty(), "two different schemas each declaring 'widget' cannot both be in "
                + "one flat namespace -- this must be rejected");
        assertTrue(problems.getFirst().message().contains("widget"), problems::toString);
        assertTrue(problems.getFirst().message().contains("shared-32.tn")
                && problems.getFirst().message().contains("shared-33.tn"),
                () -> "the diagnostic should name both declaring schemas: " + problems);
    }

    /**
     * <b>No hiding, no redefinition.</b> A flat namespace means one name denotes one type, so a local
     * declaration may not reuse a name the import closure already binds -- including one reached
     * transitively, which is exactly the case §2.2.3's own worked example permits ("even where X locally
     * declares an unrelated {@code uuid}") and #55 argues must be refused.
     */
    @Test
    void aLocalDeclarationMayNotShadowANameReachedThroughAnImportsOwnImport() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/a-4.tn", "alpha"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/d-2.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://example.test/a-4.tn"
                {
                  uuid => { mine: text }
                }
                """);

        assertFalse(problems.isEmpty(), "'uuid' is core.tn's, reached transitively through a-4.tn -- "
                + "redefining it locally must be refused, not silently shadowed");
        assertTrue(problems.getFirst().message().contains("uuid"), problems::toString);
    }

    /** The importer's own new material still resolves normally -- the control. */
    @Test
    void anImportsOwnDeclarationsAreInScope() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/a-5.tn", "alpha"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/d-3.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://example.test/a-5.tn"
                {
                  wrapper => { inner: alpha }
                }
                """);

        assertTrue(problems.isEmpty(), () -> problems.toString());
    }

    /** The fail-fast {@link Tson#resolve} path is governed by the same rule, not only the collecting one. */
    @Test
    void theFailFastResolvePathAcceptsTheDiamondToo() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/a-6.tn", "alpha"));
        tson.resolve(leaf("https://example.test/b-6.tn", "beta"));

        try {
            tson.resolve("""
                    !!id:"https://example.test/c-6.tn"
                    !!meta:"https://tson.io/2026/32/m/meta.tn"
                    !!import:"https://example.test/a-6.tn"
                    !!import:"https://example.test/b-6.tn"
                    {
                      pair => { left: alpha  right: beta }
                    }
                    """);
        } catch (TsonSchemaValidationException e) {
            throw new AssertionError("the diamond must resolve: " + e.getMessage(), e);
        }
    }

    // ── Hash pins (§2.2.1: the pin is verification metadata, not identity) ──

    /** {@link #leaf} with a {@code ?sha256=} pin on its core.tn import. */
    private static String leafPinningCore(String id, String typeName) {
        return """
                !!id:"%s"
                !!meta:"%s"
                !!import:"%s?sha256=%s"
                {
                  %s => { name: text }
                }
                """.formatted(id, META, CORE, CORE_SHA256, typeName);
    }

    /**
     * <b>A pinned and an unpinned route to one schema unify, and the importer needs neither.</b> {@code a}
     * pins core.tn, {@code b} does not, and {@code c} imports the two peers without naming core.tn at all --
     * yet writes {@code text}, which reaches it transitively. Canonicalization strips the query (§2.2.1), so
     * the pin never enters identity and the two routes are one schema.
     */
    @Test
    void aPinnedAndAnUnpinnedRouteToOneSchemaUnifyAndTheImporterNeedsNeither() {
        Tson tson = Tson.builder().build();
        tson.resolve(leafPinningCore("https://example.test/a-pin.tn", "alpha"));
        tson.resolve(leaf("https://example.test/b-nopin.tn", "beta"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/c-pin-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://example.test/a-pin.tn"
                !!import:"https://example.test/b-nopin.tn"
                {
                  pair => { left: alpha  right: beta  label: text }
                }
                """);

        assertTrue(problems.isEmpty(), () -> "a pin is verification metadata, not identity, so the two "
                + "routes to core.tn are one schema: " + problems);
    }

    /** Importing core.tn unpinned is fine where a peer pins it -- the importer chooses its own spelling. */
    @Test
    void theImporterMayNameCoreUnpinnedWhereItsPeerPinsIt() {
        Tson tson = Tson.builder().build();
        tson.resolve(leafPinningCore("https://example.test/a-pin-2.tn", "alpha"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/c-pin-2.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                !!import:"https://example.test/a-pin-2.tn"
                {
                  thing => { left: alpha  label: text }
                }
                """);

        assertTrue(problems.isEmpty(), () -> problems.toString());
    }

    /** And the reverse: the importer may pin where its peer does not. */
    @Test
    void theImporterMayPinCoreWhereItsPeerDoesNot() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/b-nopin-2.tn", "beta"));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://tson.io/2026/32/ltr8/example/c-pin-3.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn?sha256=%s"
                !!import:"https://example.test/b-nopin-2.tn"
                {
                  thing => { right: beta  label: text }
                }
                """.formatted(CORE_SHA256));

        assertTrue(problems.isEmpty(), () -> problems.toString());
    }

    /**
     * The pin is still verified (§2.2.1's MUST), and unification does not launder a wrong one: reaching
     * core.tn through an unpinned peer as well does not excuse a pin that disagrees with the content.
     */
    @Test
    void aPinThatDisagreesWithTheContentIsStillRejected() {
        Tson tson = Tson.builder().build();
        tson.resolve(leaf("https://example.test/b-nopin-3.tn", "beta"));

        String wrongPin = "0".repeat(64);
        assertThrows(TsonContentHashMismatchException.class, () -> tson.resolve("""
                !!id:"https://example.test/c-pin-4.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn?sha256=%s"
                !!import:"https://example.test/b-nopin-3.tn"
                {
                  thing => { right: beta  label: text }
                }
                """.formatted(wrongPin)));
    }
}
