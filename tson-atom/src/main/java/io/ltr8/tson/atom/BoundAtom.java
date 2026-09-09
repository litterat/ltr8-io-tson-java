package io.ltr8.tson.atom;

import java.util.Optional;
import java.util.function.Function;

/**
 * A family already bound to one target: what {@link AtomType#boundTo} hands back where the reading has to be
 * converted to reach the class a component holds.
 *
 * <p>It binds no further. Binding happens once, where a field's reader is wired, and the atom asked there is
 * always the family the schema named -- so a second call cannot arise from the reader stack and an empty
 * answer is the honest one: anything that did ask would be asking a question already settled.
 */
record BoundAtom<R>(Function<String, R> reader, Function<R, String> writer) implements AtomType<R> {

    @Override
    public R read(String text) {
        return reader.apply(text);
    }

    @Override
    public String write(R value) {
        return writer.apply(value);
    }

    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return Optional.empty();
    }
}
