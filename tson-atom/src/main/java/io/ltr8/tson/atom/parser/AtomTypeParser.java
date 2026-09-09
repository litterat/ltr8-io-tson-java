package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;

import java.util.Optional;
import java.util.function.Function;

public interface AtomTypeParser<T> extends AtomType<T> {

    /** Whether {@code target} is one of the two classes a string-valued reading may land in. */
    static boolean isTextTarget(Class<?> target) {
        return target == String.class || target == CharSequence.class;
    }

    /**
     * A {@link #boundTo} answer for a family whose value reaches {@code target} unchanged -- the natural
     * host type, and the primitive of a boxed one. The common shape, so that stating it is a line.
     */
    default Optional<AtomType<?>> natural(Class<?> naturalType, Class<?> target) {
        return AtomType.wrap(target).isAssignableFrom(AtomType.wrap(naturalType)) ? Optional.of(this) : Optional.empty();
    }

    /**
     * A {@link #boundTo} answer stated as the two directions themselves, for a family whose reading into a
     * target is not its own value converted -- the numeric families, which narrow from the token's text
     * rather than from the host value they would otherwise produce.
     */
    default <R> Optional<AtomType<?>> bound(Function<String, R> read, Function<R, String> write) {
        return Optional.of(new BoundAtom<>(read, write));
    }

    default <R> Optional<AtomType<?>> converting(Function<T, R> render, Function<R, String> parse) {
        AtomType<T> self = this;
        return bound(text -> render.apply(self.read(text)), parse);
    }

    /**
     * {@link #converting} to the text this family read, for a string target. Every family whose wire form is
     * text can hand back the spelling it validated: the value is checked first and only then rendered, so a
     * text target chooses the representation and never the rules.
     */
    default Optional<AtomType<?>> asWrittenText() {
        return converting(value -> write(value), text -> text);
    }



}
