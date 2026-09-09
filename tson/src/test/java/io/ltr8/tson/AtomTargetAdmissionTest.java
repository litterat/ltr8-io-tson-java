package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.TsonConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field's atom reconciles against the wire class its bound component wants.
 *
 * <p>An atom position under a schema used to be read with no target in hand: the family produced its own
 * natural host value and the constructor discovered any disagreement by failing its cast, unclassified and
 * past a collecting receiver. The family is asked now -- {@code AtomType.read(String, Class)} at the read,
 * and {@code AtomType.admits} where the schema and the class are compared -- so which targets a family
 * reaches is stated by the family that knows, once, for both questions.
 *
 * <p><b>The wire class, never the declared one.</b> A bridged component is reached by whatever its bridge
 * takes, so that is what the family has to produce; asking about the declared class would refuse every
 * bridged component, Java enums included.
 *
 * <p>{@code admits} is an over-approximation and the direction is load-bearing: {@code false} refuses at
 * compile, {@code true} means only that the family does not rule the target out, leaving the read to judge.
 * That is what lets the default admit everything, so a family that has not stated its targets -- every
 * built-in but {@code uri} so far, and any a consumer implements -- behaves exactly as before.
 */
class AtomTargetAdmissionTest {

    private static final String ID = "https://example.test/a-1.tn";

    private static Tson tson(String decls, Class<?> bound) {
        String schema = """
                !!id:"https://example.test/a-1.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                %s
                }
                """.formatted(decls);
        DataNameBinder binder = name -> "t".equals(name) ? bound : SchemaMetaNameBinder.INSTANCE.resolve(name);
        SchemaSource source = uri -> schema;
        return Tson.of(TsonConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(DataBindContext.builder().nameBinder(binder)
                        .registerAtoms(AtomContext.hostTypes()).build()));
    }

    private static String doc(String body) {
        return "!!schema:\"https://example.test/a-1.tn\"\n" + body;
    }

    // ── uri, the first family to state its targets ───────────────────────

    public record UriAsUri(URI u) {
    }

    public record UriAsText(String u) {
    }

    @Test
    void uriReadsToItsOwnHostType() {
        UriAsUri t = tson("  t => { u: uri }", UriAsUri.class).objectReader()
                .read(doc("!t { u: \"https://x.test/p\" }"), UriAsUri.class);
        assertEquals(URI.create("https://x.test/p"), t.u());
    }

    /**
     * A {@code uri}'s wire form is text and {@code URI.toString} hands back what it was built from, so a
     * component keeping the validated spelling loses nothing. It is still validated: the family reads the
     * URI first and only then renders it.
     */
    @Test
    void uriAlsoReadsToTheTextItWasWrittenAs() {
        UriAsText t = tson("  t => { u: uri }", UriAsText.class).objectReader()
                .read(doc("!t { u: \"https://x.test/p\" }"), UriAsText.class);
        assertEquals("https://x.test/p", t.u());
    }

    @Test
    void aTextTargetDoesNotSkipTheFamilysOwnValidation() {
        ReadException e = assertThrows(ReadException.class, () -> tson("  t => { u: uri }", UriAsText.class)
                .objectReader().read(doc("!t { u: \": : :\" }"), UriAsText.class));
        assertTrue(e.getMessage().contains("not a valid URI"), e.getMessage());
    }

    /** A facet is measured on the text either way -- the target chooses the representation, not the rules. */
    @Test
    void aFacetStillAppliesAtATextTarget() {
        assertThrows(ReadException.class, () -> tson("  short_uri => !uri ^ { max_length: 8 }\n"
                + "  t => { u: short_uri }", UriAsText.class)
                .objectReader().read(doc("!t { u: \"https://x.test/p\" }"), UriAsText.class));
    }

    public record UriAsNumber(Integer u) {
    }

    /**
     * A target the family rules out is refused where the schema meets the class, not by the first document.
     * {@code Integer} is the case that reaches this: it binds perfectly well, so nothing before {@code
     * admits} has an opinion about it.
     */
    @Test
    void uriRefusesATargetItCannotProduceAtCompile() {
        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> tson("  t => { u: uri }", UriAsNumber.class).bindRegistry().get(ID));
        assertTrue(thrown.getMessage().contains("cannot produce java.lang.Integer"), thrown.getMessage());
    }

    /**
     * {@code java.net.URL} never reaches {@code admits}: {@code tson-bind} finds no conversion for it and
     * refuses the class outright, a layer earlier. Worth pinning because the reason it is not admitted is a
     * different and older one than the reason it should not be -- {@code URI.toURL} is partial over this
     * family's value space, a {@code urn:}, a relative reference and a bare fragment all being valid
     * {@code uri}s and none a URL, so which documents bound would depend on the protocol handlers a JVM
     * happens to carry. {@code UriParser.admits} states that; this states that nothing exercises it yet --
     * and that the refusal names the enclosing type rather than the component that caused it, the class
     * having failed to resolve as a whole.
     */
    public record UriAsUrl(java.net.URL u) {
    }

    @Test
    void aUrlComponentIsRefusedBeforeAdmitsIsAsked() {
        MissingBindingException e = assertThrows(MissingBindingException.class,
                () -> tson("  t => { u: uri }", UriAsUrl.class).objectReader()
                        .read(doc("!t { u: \"https://x.test/p\" }"), UriAsUrl.class));
        assertTrue(e.getMessage().contains("no bound Java class for 't'"), e.getMessage());
    }

    // ── what a family that states nothing still does ─────────────────────

    public record IntAsInt(int n) {
    }

    public record IntAsBig(BigInteger n) {
    }

    public record IntAsString(String n) {
    }

    @Test
    void anIntegerFamilyStillNarrowsToItsTargetsPrimitive() {
        assertEquals(5, tson("  t => { n: int32 }", IntAsInt.class).objectReader()
                .read(doc("!t { n: 5 }"), IntAsInt.class).n());
    }

    /** And widens, which the constructor used to refuse after the fact. */
    @Test
    void anIntegerFamilyAlsoReachesAWiderTarget() {
        assertEquals(BigInteger.valueOf(5), tson("  t => { n: int32 }", IntAsBig.class).objectReader()
                .read(doc("!t { n: 5 }"), IntAsBig.class).n());
    }

    /**
     * A target no numeric family reaches is refused by the family rather than by the constructor -- but
     * <b>still not as a diagnostic</b>: {@code NumberNarrowing} throws {@link IllegalArgumentException},
     * which is no {@code AtomTypeException}, so it escapes the classification every other refusal here gets.
     * The numeric families have not stated their targets, so {@code admits} defaults to admitting this one
     * and the compile stays silent. Both halves are one piece of work; {@code BACKLOG.md} carries it.
     */
    @Test
    void anIntegerFamilyRefusesATextTargetButNotYetAsADiagnostic() {
        Tson tson = tson("  t => { n: int32 }", IntAsString.class);
        assertNotNull(tson.bindRegistry().get(ID), "int32 has not stated its targets, so nothing is refused here");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> tson.objectReader()
                .read(doc("!t { n: 5 }"), IntAsString.class));
        assertTrue(e.getMessage().contains("cannot represent an integer value"), e.getMessage());
    }
}
