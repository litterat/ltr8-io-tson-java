package io.ltr8.tson.atom.parser;

import io.ltr8.net.Iri;
import io.ltr8.tson.atom.AtomType;
import java.net.URI;
import java.util.Optional;
import java.util.function.Function;

/**
 * A URI or IRI family read into {@code java.net.URI}: the family judges the text, and the value it reads to is then
 * held as a {@code URI}, or refused as a binding failure where RFC 2396 cannot hold it. A host type's entry in its
 * own right ({@code HostAtoms}), so it answers {@link #boundTo} as a family does: {@code URI} itself, the {@link
 * Iri} the family reads, and the text as written.
 *
 * @param family the family that judges the text
 * @param toUri  the family's own mapping of a value onto a {@code URI}, throwing {@link IllegalArgumentException}
 *               where none holds it
 */
record JavaUriAtom(AtomTypeParser<Iri> family, Function<Iri, URI> toUri) implements AtomType<URI> {

    @Override
    public URI read(String text) {
        return toUri.apply(family.read(text));
    }

    @Override
    public String write(URI value) {
        return value.toString();
    }

    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return target == URI.class ? Optional.of(this) : family.boundTo(target);
    }
}
