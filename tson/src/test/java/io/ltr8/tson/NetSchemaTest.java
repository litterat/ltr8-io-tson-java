package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.net.Host;
import io.ltr8.net.HostName;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.HostType;
import io.ltr8.tson.schema.meta.HostnameType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code net.tn}, the network type library: the address and network families, {@code hostname} and {@code host},
 * in a library a schema imports when it wants them, so a schema importing core alone may declare those names
 * itself. Characters beyond ASCII are escapes.
 */
class NetSchemaTest {

    private static final String ID = "https://example.test/net-hosts.tn";

    /** {@code bücher.example}. */
    private static final String BUCHER = "bücher.example";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            !!import:"https://tson.io/2026/38/m/net.tn"
            {
              listener => { host: host  port: uint16 }
              site => { name: hostname }
              ascii_name => !hostname ^ { allow_idn: false }
              ascii_site => { name: ascii_name }
            }
            """.formatted(ID);

    private static Tson tson() {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        return tson;
    }

    private static List<Diagnostic> validate(String body) {
        return tson().validate("!!schema:\"" + ID + "\"\n" + body);
    }

    /** One string-class type, so none of the three spellings of a host needs a tag. */
    @Test
    void aHostIsAHostNameOrAnAddressUntagged() {
        assertEquals(List.of(), validate("!listener { host: localhost  port: 443 }"));
        assertEquals(List.of(), validate("!listener { host: \"127.0.0.1\"  port: 443 }"));
        assertEquals(List.of(), validate("!listener { host: \"::1\"  port: 443 }"));
        assertEquals(List.of(), validate("!listener { host: \"[::1]\"  port: 443 }"));
    }

    @Test
    void aHostReadsToItsMemberWrittenCanonically() {
        Tson tson = tson();
        assertEquals("::1", tson.treeReader()
                .read("!!schema:\"" + ID + "\"\n!listener { host: \"[0:0:0:0:0:0:0:1]\"  port: 443 }")
                .get("host").as(Host.class).orElseThrow().text());
    }

    @Test
    void aZoneIdentifierIsNotAHost() {
        List<Diagnostic> problems = validate("!listener { host: \"fe80::1%eth0\"  port: 443 }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problems.getFirst().code());
    }

    /** A dotted-quad's last label starts with a digit, so it is an address and never a host name. */
    @Test
    void aDottedQuadIsNotAHostName() {
        List<Diagnostic> problems = validate("!site { name: \"192.0.2.1\" }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problems.getFirst().code());
    }

    @Test
    void aHostNameIsOneValueInEitherLabelForm() {
        Tson tson = tson();
        HostName unicode = tson.treeReader().read("!!schema:\"" + ID + "\"\n!site { name: \"" + BUCHER + "\" }")
                .get("name").as(HostName.class).orElseThrow();
        HostName ascii = tson.treeReader().read("!!schema:\"" + ID + "\"\n!site { name: XN--BCHER-KVA.example }")
                .get("name").as(HostName.class).orElseThrow();
        assertEquals(unicode, ascii);
        assertEquals(BUCHER, ascii.unicode());
    }

    @Test
    void uppercaseBeyondAsciiIsRefusedWithItsFix() {
        List<Diagnostic> problems = validate("!site { name: \"BÜCHER.example\" }");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("write '" + BUCHER + "'"), problems.toString());
    }

    /** {@code allow_idn: false} refuses the name in either spelling: it is a constraint, not a grammar. */
    @Test
    void allowIdnFalseRefusesAnInternationalizedName() {
        assertEquals(List.of(), validate("!ascii_site { name: example.com }"));
        for (String name : List.of("\"" + BUCHER + "\"", "xn--bcher-kva.example")) {
            List<Diagnostic> problems = validate("!ascii_site { name: " + name + " }");
            assertEquals(1, problems.size(), problems.toString());
            assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
        }
    }

    @Test
    void allowIdnIsWithdrawnButNeverGrantedBack() {
        assertThrows(SchemaValidationException.class, () -> Tson.standard().resolve("""
                !!id:"https://example.test/regrant.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                !!import:"https://tson.io/2026/38/m/net.tn"
                {
                  ascii => !hostname ^ { allow_idn: false }
                  again => !ascii ^ { allow_idn: true }
                }
                """));
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
                  host => text
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
     * The schemaless {@code !hostname} and {@code !host} ([TSON-DATA] §5.5) read by net.tn's own bodies, or a
     * document's meaning would change as it moves under a schema importing net.tn.
     */
    @Test
    void theSchemalessNamesAreNetTnsOwn() {
        var net = Tson.standard().bindRegistry().core().resolveLinked(TsonBundledSchemas.NET_ID).schema().entries();
        assertEquals(HostnameType.UNCONSTRAINED, net.get("hostname").body());
        assertEquals(HostType.UNCONSTRAINED, net.get("host").body());
        assertTrue(BuiltinTypeVocabulary.lookup("hostname").isPresent());
        assertTrue(BuiltinTypeVocabulary.lookup("host").isPresent());
    }

    @Test
    void aSchemalessHostNameIsChecked() {
        assertEquals(List.of(), Tson.standard().validate("{ host: !hostname Api.Example.COM }"));
        assertEquals(List.of(), Tson.standard().validate("{ host: !hostname \"" + BUCHER + "\" }"));
        assertEquals(List.of(), Tson.standard().validate("{ host: !host \"[::1]\" }"));
        assertEquals(1, Tson.standard().validate("{ host: !hostname \"-bad-.example\" }").size());
    }
}
