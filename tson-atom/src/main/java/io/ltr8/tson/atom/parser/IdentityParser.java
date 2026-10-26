package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.base.Identity;
import io.ltr8.tson.base.IdentitySyntaxException;
import io.ltr8.tson.schema.meta.IdentityType;

import java.util.Optional;

/**
 * Parses against meta.tn's {@code identity_type} (core.tn's {@code identity}): a canonical identity
 * ([TSON-DATA] §2.2.1), read to an {@link Identity} from its own text or from any reference naming it, whose rules
 * are {@code tson-base}'s -- the reading {@code CanonicalIdentity} gives every schema reference. A pure format
 * check, with nothing to configure. A {@code String} target receives the canonical identity, the string the
 * library keys its registries and {@code Diagnostic.schemaId} by, and an {@link Identity} target the value.
 */
public record IdentityParser(IdentityType constraints) implements AtomTypeParser<Identity> {

    /** core.tn's annotation name -- {@code !identity}. */
    public static final String TYPENAME = "identity";

    /** {@code identity => !identity_type {}}. */
    public static final IdentityParser UNCONSTRAINED = new IdentityParser(IdentityType.UNCONSTRAINED);

    @Override
    public Identity read(String text) {
        try {
            return Identity.parse(text);
        } catch (IdentitySyntaxException e) {
            throw new AtomParseException("'" + text + "' is not an identity: it " + e.reason(),
                    "a canonical identity (host and absolute path, or an absolute path) or a reference naming one");
        }
    }

    @Override
    public String write(Identity value) {
        return value.text();
    }

    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return AtomTypeParser.isTextTarget(target) ? asWrittenText() : natural(Identity.class, target);
    }
}
