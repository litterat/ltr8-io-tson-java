package io.ltr8.tson.schema;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.Top;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Where an extern ({@link Extern}) lands: the entry {@code type} names in the namespace of the linked schema {@code
 * foreign} ([TSON-SCHEMA] §5.13). Linking attaches it; the extern's value states only what was written.
 *
 * <p>Every judgement through the hop compares the {@link #landing()}: the entry at the end of the foreign alias hops
 * and of any further externs, in the namespace that holds it, so an extern, an import of the entry it names and an
 * extern of that extern are one type (§8.3).
 */
public record ExternTarget(TsonLinkedSchema foreign, String type) {

    /** The canonical identity of the schema the extern names. */
    public String schema() {
        return CanonicalIdentity.canonicalize(foreign.schema().id());
    }

    /**
     * The entry the hop finally names, or empty where a hop names nothing or the walk closes on itself -- a foreign
     * schema whose link refused such a hop is never attached, so this answers for a well-formed chain.
     */
    public Optional<Landing> landing() {
        TsonLinkedSchema in = foreign;
        String name = type;
        Set<String> seen = new HashSet<>();
        while (seen.add(in.originOf(name) + "#" + name)) {
            Top definition = in.schema().entries().get(name);
            if (definition instanceof Alias alias) {
                name = alias.target().name();
            } else if (definition instanceof Extern) {
                ExternTarget next = in.externs().get(name);
                if (next == null) {
                    return Optional.empty();
                }
                in = next.foreign();
                name = next.type();
            } else {
                return definition == null ? Optional.empty() : Optional.of(new Landing(in, name));
            }
        }
        return Optional.empty();
    }

    /**
     * An entry reached through externs: {@code name} in the namespace of {@code in}. Its identity is the schema that
     * declared it and its path there ([TSON-SCHEMA] §8.3).
     */
    public record Landing(TsonLinkedSchema in, String name) {

        public Top definition() {
            return in.schema().entries().get(name);
        }

        public String declaringSchema() {
            return in.originOf(name);
        }
    }
}
