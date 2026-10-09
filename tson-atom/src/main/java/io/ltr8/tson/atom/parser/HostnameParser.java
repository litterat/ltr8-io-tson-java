package io.ltr8.tson.atom.parser;

import io.ltr8.net.HostName;
import io.ltr8.net.HostSyntaxException;
import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.schema.meta.HostnameType;

import java.util.Optional;

/**
 * Parses and validates against meta.tn's {@code hostname_type} (net.tn's {@code hostname}): a domain name in either
 * label form, read to an {@link HostName}, whose rules -- IDNA2008, the ASCII fold, the boundaries -- are
 * {@code io.ltr8.net}'s. A token that is not a host name is outside the family's grammar and so a parse failure;
 * {@code allow_idn} false refusing an internationalized name is a constraint violation, the name being one.
 *
 * <p>A {@code String} target receives the canonical text -- the name's U-labels -- so a list of names declared
 * {@code List<String>} compares by name in either spelling.
 */
public record HostnameParser(HostnameType constraints) implements AtomTypeParser<HostName> {

    /** net.tn's annotation name -- {@code !hostname}. */
    public static final String TYPENAME = "hostname";

    /** {@code hostname => !hostname_type {}}. */
    public static final HostnameParser UNCONSTRAINED = new HostnameParser(HostnameType.UNCONSTRAINED);

    @Override
    public HostName read(String text) {
        HostName name;
        try {
            name = HostName.parse(text);
        } catch (HostSyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a host name: it " + e.reason(), "a host name");
        }
        if (!constraints.allowIdn() && name.isIdn()) {
            throw new AtomValidationException("'" + text + "' is an internationalized host name, and this type "
                    + "admits only names of ASCII labels (allow_idn: false)",
                    "a host name with no internationalized label");
        }
        return name;
    }

    @Override
    public String write(HostName value) {
        return value.unicode();
    }

    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return AtomTypeParser.isTextTarget(target) ? asWrittenText() : natural(HostName.class, target);
    }
}
