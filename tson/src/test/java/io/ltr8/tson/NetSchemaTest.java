package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code net.tn}, the network type library: the address and network families and {@code hostname}, in a library
 * a schema imports when it wants them, so a schema importing core alone may declare those names itself.
 */
class NetSchemaTest {

    private static final String ID = "https://example.test/net-hosts.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            !!import:"https://tson.io/2026/38/m/net.tn"
            {
              listener => { host: ( hostname | ipv4 )  port: uint16 }
            }
            """.formatted(ID);

    private static List<Diagnostic> listener(String host) {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        return tson.validate("!!schema:\"" + ID + "\"\n!listener { host: " + host + "  port: 443 }");
    }

    @Test
    void aHostIsAHostNameOrAnAddress() {
        assertEquals(List.of(), listener("!hostname Api.Example.COM"));
        assertEquals(List.of(), listener("!ipv4 \"192.0.2.1\""));
    }

    /** A dotted-quad's last label starts with a digit, so it is an {@code ipv4} and never a {@code hostname}. */
    @Test
    void aDottedQuadIsNotAHostName() {
        List<Diagnostic> problems = listener("!hostname \"192.0.2.1\"");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
    }

    @Test
    void theHostNameIsReadFolded() {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        assertEquals("api.example.com", tson.treeReader()
                .read("!!schema:\"" + ID + "\"\n!listener { host: !hostname Api.Example.COM  port: 443 }")
                .get("host").asString().orElseThrow());
    }

    /** Core claims none of the network names, so a schema importing it alone may declare them. */
    @Test
    void aSchemaImportingCoreAloneMayDeclareTheNetworkNames() {
        Tson tson = Tson.standard();
        tson.resolve("""
                !!id:"https://example.test/own-mac.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  mac => { vendor: text  model: text }
                  hostname => text
                }
                """);
        assertEquals(List.of(), tson.validate("""
                !!schema:"https://example.test/own-mac.tn"
                !mac { vendor: acme  model: m1 }"""));
    }

    @Test
    void aSchemaImportingCoreAloneDoesNotSeeTheNetworkTypes() {
        assertThrows(SchemaValidationException.class, () -> Tson.standard().resolve("""
                !!id:"https://example.test/no-net.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                { server => { address: ipv4 } }
                """));
    }

    /**
     * The schemaless {@code !hostname} ([TSON-DATA] §5.5) is the hand-written {@link BuiltinTypeVocabulary#HOSTNAME},
     * and it must be net.tn's own body facet for facet, or a document's meaning would change as it moves under a
     * schema importing net.tn.
     */
    @Test
    void theSchemalessHostNameIsNetTnsOwn() {
        assertEquals(BuiltinTypeVocabulary.HOSTNAME, Tson.standard().bindRegistry().core()
                .resolveLinked(TsonBundledSchemas.NET_ID).schema().entries().get("hostname").body());
    }

    @Test
    void aSchemalessHostNameIsChecked() {
        assertEquals(List.of(), Tson.standard().validate("{ host: !hostname Api.Example.COM }"));
        assertEquals(1, Tson.standard().validate("{ host: !hostname \"-bad-.example\" }").size());
    }
}
