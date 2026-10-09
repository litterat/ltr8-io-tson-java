package io.ltr8.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.net.Iri.Grammar;
import io.ltr8.net.Iri.Host.Kind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IriTest {

    private static Iri uri(String text) {
        return Iri.parse(text, Grammar.URI);
    }

    private static Iri iri(String text) {
        return Iri.parse(text, Grammar.IRI);
    }

    /** RFC 3986 §1.1.2's examples, each split as the RFC's own §3 diagram splits {@code foo://…}. */
    @Test
    void rfc3986sExamplesAreReferences() {
        for (String example : List.of("ftp://ftp.is.co.za/rfc/rfc1808.txt", "http://www.ietf.org/rfc/rfc2396.txt",
                "ldap://[2001:db8::7]/c=GB?objectClass?one", "mailto:John.Doe@example.com",
                "news:comp.infosystems.www.servers.unix", "tel:+1-816-555-1212", "telnet://192.0.2.16:80/",
                "urn:oasis:names:specification:docbook:dtd:xml:4.1.2",
                "foo://example.com:8042/over/there?name=ferret#nose")) {
            assertEquals(example, uri(example).toString());
        }
        Iri foo = uri("foo://example.com:8042/over/there?name=ferret#nose");
        assertEquals(Optional.of("foo"), foo.scheme());
        assertEquals("example.com", foo.authority().orElseThrow().host().text());
        assertEquals(Optional.of("8042"), foo.authority().orElseThrow().port());
        assertEquals("/over/there", foo.path());
        assertEquals(Optional.of("name=ferret"), foo.query());
        assertEquals(Optional.of("nose"), foo.fragment());
    }

    /** What {@code java.net.URI}'s RFC 2396 grammar refuses and RFC 3986 admits. */
    @Test
    void referencesRfc2396RefusesAreReferences() {
        assertEquals("", uri("https://").authority().orElseThrow().host().text());
        assertEquals(Optional.of("q=1"), uri("https://?q=1").query());
        assertEquals("", uri("a:").path());
        Iri future = uri("http://[v7.abc]/");
        assertEquals(Kind.IP_FUTURE, future.authority().orElseThrow().host().kind());
        assertEquals("v7.abc", future.authority().orElseThrow().host().text());
        assertEquals(Kind.IP_FUTURE, uri("http://[V1.x:y]/").authority().orElseThrow().host().kind());
        assertEquals(Optional.of(""), uri("http://a:/").authority().orElseThrow().port());
    }

    /** And what it admits and RFC 3986 refuses: a port is digits only. */
    @Test
    void aPortIsDigitsOnly() {
        IriSyntaxException e = assertThrows(IriSyntaxException.class, () -> uri("http://a:b/"));
        assertTrue(e.reason().contains("port"), e.getMessage());
    }

    @Test
    void componentsAbsentAndEmptyAreTwoThings() {
        Iri bare = uri("a:");
        assertEquals(Optional.empty(), bare.query());
        assertEquals(Optional.empty(), bare.fragment());
        Iri empty = uri("a:?#");
        assertEquals(Optional.of(""), empty.query());
        assertEquals(Optional.of(""), empty.fragment());
    }

    @Test
    void relativeReferences() {
        assertTrue(uri("/local/orders.tn").isRelative());
        assertEquals("/local/orders.tn", uri("/local/orders.tn").path());
        assertEquals("example.com", uri("//example.com/x").authority().orElseThrow().host().text());
        assertEquals(Optional.of("frag"), uri("#frag").fragment());
        assertEquals("", uri("").path());
        assertFalse(uri("https://example.com/").isRelative());
    }

    /** {@code path-noscheme}: a relative reference's first segment holds no {@code :}, or it would be a scheme. */
    @Test
    void aRelativeReferencesFirstSegmentHoldsNoColon() {
        assertThrows(IriSyntaxException.class, () -> uri("1a:b"));
        assertEquals("./a:b", uri("./a:b").path());
    }

    @Test
    void userinfoPrecedesTheFirstAt() {
        Iri iri = uri("ftp://user:pass@host/");
        assertEquals(Optional.of("user:pass"), iri.authority().orElseThrow().userinfo());
        assertEquals("host", iri.authority().orElseThrow().host().text());
    }

    /** RFC 3986 §3.2.2's nine IPv6 shapes, and IPv4 tried before a registered name. */
    @Test
    void hosts() {
        for (String v6 : List.of("1:2:3:4:5:6:7:8", "::", "::1", "1::", "1:2::7:8", "::ffff:192.0.2.1",
                "1:2:3:4:5:6:192.0.2.1", "1::192.0.2.1", "2001:db8::7")) {
            assertEquals(Kind.IPV6, uri("http://[" + v6 + "]/").authority().orElseThrow().host().kind(), v6);
        }
        for (String bad : List.of("1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1::2::3", ":1::", "1::2:", "12345::",
                "1:2:3:4:5:6:7:192.0.2.1", "::1.2.3", "g::")) {
            assertThrows(IriSyntaxException.class, () -> uri("http://[" + bad + "]/"), bad);
        }
        assertEquals(Kind.IPV4, uri("http://192.0.2.16/").authority().orElseThrow().host().kind());
        assertEquals(Kind.REG_NAME, uri("http://192.0.2.256/").authority().orElseThrow().host().kind());
        assertEquals(Kind.REG_NAME, uri("http://01.2.3.4/").authority().orElseThrow().host().kind());
        assertThrows(IriSyntaxException.class, () -> uri("http://[::1/"));
        assertThrows(IriSyntaxException.class, () -> uri("http://[::1]x/"));
    }

    @Test
    void percentEncodingIsATriplet() {
        assertEquals("/%41%2F", uri("http://h/%41%2F").path());
        for (String bad : List.of("http://h/%4", "http://h/%zz", "http://h/%", "http://h/%4?x")) {
            assertThrows(IriSyntaxException.class, () -> uri(bad), bad);
        }
    }

    @Test
    void charactersOutsideTheGrammarAreRefused() {
        for (String bad : List.of("http://h/a b", "http://h/<x>", "http://h/a#b#c", "http://h/a\\b", "http://h^/")) {
            assertThrows(IriSyntaxException.class, () -> uri(bad), bad);
        }
    }

    /** A URI is US-ASCII (RFC 3986 §2); an IRI admits {@code ucschar} wherever {@code unreserved} stands. */
    @Test
    void anIriAdmitsUcscharAndAUriDoesNot() {
        String wide = "https://\u4F8B\u3048.test/\u6CE8\u6587.tn?q=\u00E9#\u00E9";
        IriSyntaxException e = assertThrows(IriSyntaxException.class, () -> uri(wide));
        assertTrue(e.reason().contains("US-ASCII"), e.getMessage());
        assertEquals(8, e.index());

        Iri iri = iri(wide);
        assertEquals("\u4F8B\u3048.test", iri.authority().orElseThrow().host().text());
        assertEquals("/\u6CE8\u6587.tn", iri.path());
        assertEquals(Kind.REG_NAME, iri.authority().orElseThrow().host().kind());
    }

    /** {@code iprivate} only in the query; a character outside both {@code ucschar} and it, nowhere. */
    @Test
    void privateUseOnlyInTheQuery() {
        assertEquals(Optional.of("\uE000"), iri("http://h/?\uE000").query());
        assertThrows(IriSyntaxException.class, () -> iri("http://h/\uE000"));
        assertThrows(IriSyntaxException.class, () -> iri("http://h/#\uE000"));
        assertThrows(IriSyntaxException.class, () -> iri("http://h/\uFFFE"));
        assertEquals("/\uD83D\uDE00", iri("http://h/\uD83D\uDE00").path());
    }

    /** An IP literal stays US-ASCII under RFC 3987 too. */
    @Test
    void anIpLiteralStaysAscii() {
        assertThrows(IriSyntaxException.class, () -> iri("http://[v1.\u00E9]/"));
    }

    @Test
    void equalityIsTheText() {
        assertEquals(uri("http://h/a"), iri("http://h/a"));
        assertFalse(uri("http://h/a").equals(uri("HTTP://h/a")));
        assertEquals(uri("http://h/a").hashCode(), "http://h/a".hashCode());
    }
}
