package io.ltr8.net;

import java.util.Objects;
import java.util.Optional;

/**
 * A URI-reference (RFC 3986 §4.1) or IRI-reference (RFC 3987 §2.2), held as the text it was written as and split into
 * its components, each the substring the text holds. Built only by {@link #parse}, so every instance is a reference
 * the grammar it was read under admits.
 *
 * <p>Two values are equal exactly when their texts are: the components are a function of the text, and the
 * comparison RFC 3986 §6.2.1 calls simple string comparison is the one every rule built on this needs. A
 * component is absent when the text has no delimiter for it, and present but empty when it has the delimiter and
 * nothing after it -- {@code a:?} has an empty query and {@code a:} none, which RFC 3986 §5.3 says are different.
 *
 * @param text      the reference as written
 * @param scheme    the scheme, without its {@code :}
 * @param authority the authority, present exactly when the text has {@code //} where the hierarchical part begins
 * @param path      the path, which every reference has and which may be empty
 * @param query     the query, without its {@code ?}
 * @param fragment  the fragment, without its {@code #}
 */
public record Iri(String text, Optional<String> scheme, Optional<Authority> authority, String path,
                      Optional<String> query, Optional<String> fragment) {

    /** Which RFC's grammar a reference is read under. */
    public enum Grammar {
        /** RFC 3986's {@code URI-reference}: US-ASCII throughout. */
        URI,
        /**
         * RFC 3987's {@code IRI-reference}: {@code ucschar} wherever {@code unreserved} stands, {@code iprivate} in
         * the query.
         */
        IRI
    }

    public Iri {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(fragment, "fragment");
    }

    /**
     * {@code text} as a reference under {@code grammar}.
     *
     * @throws IriSyntaxException if the grammar does not admit it
     */
    public static Iri parse(String text, Grammar grammar) {
        return new IriGrammar(Objects.requireNonNull(text, "text"), grammar == Grammar.IRI).reference();
    }

    /** Whether this is a relative reference (RFC 3986 §4.2): one with no scheme. */
    public boolean isRelative() {
        return scheme.isEmpty();
    }

    /** The text as written. */
    @Override
    public String toString() {
        return text;
    }

    /** Equal exactly when the texts are, the components being a function of the text. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Iri iri && text.equals(iri.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    /**
     * RFC 3986 §3.2's authority, each part as written.
     *
     * @param userinfo the userinfo, without its {@code @}
     * @param host     the host, which may be an empty registered name
     * @param port     the port, without its {@code :}; present and empty for {@code http://a:/}, which RFC 3986
     *                 admits
     */
    public record Authority(Optional<String> userinfo, Host host, Optional<String> port) {

        public Authority {
            Objects.requireNonNull(userinfo, "userinfo");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(port, "port");
        }
    }

    /**
     * RFC 3986 §3.2.2's host.
     *
     * @param kind which of the four forms it is
     * @param text the host as written, an IP literal without its brackets
     */
    public record Host(Kind kind, String text) {

        /** The forms of §3.2.2, tried in its order: an IP literal, then IPv4, then a registered name. */
        public enum Kind {
            /** A registered name -- RFC 3987's {@code ireg-name} under the IRI grammar. */
            REG_NAME,
            /** A dotted-decimal IPv4 address. */
            IPV4,
            /** An IPv6 address in brackets. */
            IPV6,
            /** An IPvFuture literal in brackets. */
            IP_FUTURE
        }

        public Host {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
        }
    }
}
