package io.ltr8.tson.atom.parser;

import io.ltr8.net.Host;
import io.ltr8.net.HostSyntaxException;
import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.schema.meta.HostType;

import java.util.Optional;

/**
 * Parses against meta.tn's {@code host_type} (net.tn's {@code host}): a host name, an IPv4 address or an IPv6
 * address, read to a {@link Host}, whose rules are {@code io.ltr8.net}'s. A pure format check, with nothing to
 * configure. A {@code String} target receives the canonical text -- the member's -- and a {@link Host} target
 * the value.
 */
public record HostParser(HostType constraints) implements AtomTypeParser<Host> {

    /** net.tn's annotation name -- {@code !host}. */
    public static final String TYPENAME = "host";

    /** {@code host => !host_type {}}. */
    public static final HostParser UNCONSTRAINED = new HostParser(HostType.UNCONSTRAINED);

    @Override
    public Host read(String text) {
        try {
            return Host.parse(text);
        } catch (HostSyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a host: it " + e.reason(),
                    "a host name, an IPv4 address or an IPv6 address");
        }
    }

    @Override
    public String write(Host value) {
        return value.text();
    }

    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return AtomTypeParser.isTextTarget(target) ? asWrittenText() : natural(Host.class, target);
    }
}
