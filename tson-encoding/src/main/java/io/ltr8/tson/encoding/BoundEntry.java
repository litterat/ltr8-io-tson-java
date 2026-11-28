package io.ltr8.tson.encoding;

import io.ltr8.bind.DataClass;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.schema.EntryKey;
import io.ltr8.tson.schema.meta.Top;

import java.util.Objects;
import java.util.Optional;

/**
 * What a written type name binds to: the entry it denotes past its alias hops and its extern ({@code entry}, in the
 * schema declaring it), that entry's {@code definition}, the class it binds to ({@code descriptor}), and, for an atom,
 * the type a bind read parses its text with ({@code atom}).
 */
public record BoundEntry(EntryKey entry, Top definition, Descriptor descriptor, Optional<AtomType<?>> atom) {

    public BoundEntry {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(atom, "atom");
    }

    /** The class the entry binds to; empty where it is {@link Descriptor.Unbound} or {@link Descriptor.Unanalysable}. */
    public Optional<DataClass> dataClass() {
        return descriptor instanceof Descriptor.Bound bound ? Optional.of(bound.dataClass()) : Optional.empty();
    }
}
