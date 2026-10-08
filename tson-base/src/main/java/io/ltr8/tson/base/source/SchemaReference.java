package io.ltr8.tson.base.source;


import io.ltr8.tson.base.SchemaFetchException;

import io.ltr8.net.Iri;
import io.ltr8.net.IriSyntaxException;
import java.util.Locale;

/**
 * One schema reference checked against [TSON-DATA] §2.2.1's rules on what an identifying URI may be, shared by
 * every fetching {@code SchemaSource}.
 *
 * <p><b>Identity is not location</b>, and this type is the split. §2.2.1 makes a reference's canonical identity
 * its lowercase host plus path: the scheme is "a transport hint, not part of the name", the {@code ?sha256=}
 * pin is verification metadata, and an identifying URI carries <b>no port, default or otherwise</b>, no
 * userinfo and no fragment. So what a reference <em>names</em> is settled here, once, and where a given source
 * goes to get it is that source's own business -- an HTTPS origin, a directory on disk.
 *
 * <p><b>Shared because it is a security check.</b> The reference reaching a source came out of a document,
 * which in a server came out of a request body; two sources enforcing §2.2.1 separately is two places for one
 * of them to drift lenient. The rules refused here are refused before any location is opened, and with a
 * message naming the rule rather than a stack trace from further in.
 *
 * <p>What this deliberately does <b>not</b> do is verify the {@code ?sha256=} pin or check the fetched
 * document's embedded {@code !!id}. The loader does both, after a source returns: it verifies the pin (§2.2.1's
 * MUST-verify rule) and cross-checks the identity. Repeating either here would be a second implementation to
 * drift from the real one. Requiring that a pin be <em>present</em> is the one thing the loader cannot express,
 * since it only verifies one that is there -- so that is here.
 */
record SchemaReference(String canonical, String host, String path) {

    /**
     * {@code reference} as a legal identity, or a {@link SchemaFetchException.Reason#NOT_PERMITTED}
     * failure.
     *
     * @param requireContentHashPin refuse a reference carrying no {@code ?sha256=} pin
     */
    static SchemaReference of(String reference, boolean requireContentHashPin) {
        Iri iri;
        try {
            iri = Iri.parse(reference, Iri.Grammar.IRI);
        } catch (IriSyntaxException e) {
            throw notPermitted(reference, "not an IRI-reference (RFC 3987): it " + e.reason(), e);
        }
        Iri.Authority authority = iri.authority().orElse(null);
        if (iri.isRelative() || authority == null || authority.host().text().isEmpty()) {
            throw notPermitted(reference, "not an absolute URI with a host");
        }
        if (authority.userinfo().isPresent()) {
            throw notPermitted(reference, "carries userinfo, which an identifying URI may not (§2.2.1) and "
                    + "whose host is easy to misread");
        }
        if (authority.port().isPresent()) {
            throw notPermitted(reference, "carries a port, which an identifying URI may not (§2.2.1); map the "
                    + "host to another location instead");
        }
        if (iri.fragment().isPresent()) {
            throw notPermitted(reference, "carries a fragment, which an identifying URI may not (§2.2.1)");
        }
        if (requireContentHashPin && !iri.query().orElse("").contains("sha256=")) {
            throw notPermitted(reference, "carries no ?sha256= content-hash pin, and this source requires one");
        }
        String host = authority.host().text().toLowerCase(Locale.ROOT);
        return new SchemaReference(host + iri.path(), host, iri.path());
    }

    static SchemaFetchException notPermitted(String reference, String message) {
        return notPermitted(reference, message, null);
    }

    static SchemaFetchException notPermitted(String reference, String message, Throwable cause) {
        return new SchemaFetchException(reference, SchemaFetchException.Reason.NOT_PERMITTED, message,
                cause);
    }
}
