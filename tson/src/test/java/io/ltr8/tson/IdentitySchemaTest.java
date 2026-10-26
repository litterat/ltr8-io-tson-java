package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.Identity;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * core.tn's {@code identity}: [TSON-DATA] §2.2.1's canonical identity under a schema, read untagged, bound to a
 * {@code String} as the canonical identity and to an {@link Identity} as the value, and written canonically.
 */
class IdentitySchemaTest {

    private static final String ID = "https://example.test/identities.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              entry => { name: identity  home: identity  seen?: set<identity> }
            }
            """.formatted(ID);

    /** The canonical identity as a {@code String}, and as an {@link Identity}. */
    public record Entry(String name, Identity home, Set<Identity> seen) {
    }

    private static Tson tson() {
        DataNameBinder binder = name -> "entry".equals(name) ? Entry.class : SchemaMetaNameBinder.INSTANCE.resolve(name);
        Tson tson = Tson.of(ProcessorConfig.defaults().withDataBindContext(
                DataBindContext.builder().nameBinder(binder).registerAtoms(AtomContext.hostTypes()).build()));
        tson.resolve(SCHEMA);
        return tson;
    }

    private static String document(String body) {
        return "!!schema:\"" + ID + "\"\n" + body;
    }

    @Test
    void anIdentityNeedsNoTagInAnySpelling() {
        assertEquals(List.of(), tson().validate(document("""
                !entry { name: "tson.io/2026/38/m/core.tn"  home: "/local/orders.tn" }""")));
        assertEquals(List.of(), tson().validate(document("""
                !entry { name: "http://Example.COM/people.tn?sha256=%s"  home: "file:///local/orders.tn" }"""
                .formatted("ab".repeat(32)))));
    }

    @Test
    void aStringBindsTheCanonicalIdentityAndAnIdentityTheValue() {
        Entry entry = tson().objectReader().read(document("""
                !entry {
                  name: "HTTP://Example.COM/people.tn"
                  home: "https://xn--bcher-kva.example/sch%c3%a9mas/x.tn"
                  seen: [ "/a.tn" "example.com/b.tn" ]
                }"""), Entry.class);
        assertEquals("example.com/people.tn", entry.name());
        assertEquals(Identity.parse("bücher.example/schémas/x.tn"), entry.home());
        assertEquals(Set.of(Identity.parse("/a.tn"), Identity.parse("https://example.com/b.tn")), entry.seen());
    }

    @Test
    void aWriterEmitsTheCanonicalIdentity() {
        Tson tson = tson();
        Entry entry = new Entry("example.com/people.tn", Identity.parse("https://Example.COM/x.tn?sha256="
                + "ab".repeat(32)), null);
        StringBuilder out = new StringBuilder();
        tson.objectWriter().write(entry, out);
        assertTrue(out.toString().contains("example.com/x.tn"), out.toString());
        assertTrue(!out.toString().contains("sha256"), out.toString());
    }

    @Test
    void twoSpellingsOfOneIdentityAreOneSetElement() {
        List<Diagnostic> problems = tson().validate(document("""
                !entry { name: "/a.tn"  home: "/a.tn"
                         seen: [ "https://tson.io/2026/38/m/core.tn" "tson.io/2026/38/m/core.tn" ] }"""));
        assertEquals(1, problems.size(), problems.toString());
        assertEquals("/seen/1", problems.getFirst().path().orElseThrow());
    }

    @Test
    void aReferenceWithAPortIsNoIdentity() {
        List<Diagnostic> problems = tson().validate(document("""
                !entry { name: "https://example.com:8443/x.tn"  home: "/a.tn" }"""));
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("is not an identity: it carries a port"),
                problems.toString());
    }
}
