package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.reader.EventSkip;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.Optional;

/**
 * The type-ref a self-describing document's root value carries, which is what selects the type a schema-aware
 * read validates against when the caller named none.
 *
 * <p><b>It is not necessarily the first event.</b> [TSON-DATA]'s data-value is {@code *annotation [type-ref]
 * core-value} and §3.3 puts the two in that order deliberately -- an annotation attaches to the value that
 * follows it, and a type-ref is part of that value, so {@code @doc:"..." !api { ... }} annotates and types one
 * value. Since TSON has no comment syntax (§2.4), an annotation is also the only way to put prose in a
 * document at all, and a root that could not carry one would leave configuration, fixtures and API
 * descriptions -- the documents most likely to want explaining -- unable to explain themselves.
 *
 * <p><b>Found by looking ahead and rewinding</b>, not by consuming: the annotations belong to the root value,
 * and every reader below builds them into what it returns (a {@code TsonValue}'s own annotation list, a bound
 * class's {@code Annotations} carrier). A lookahead that kept them would select the right reader and hand it
 * a value stripped of the prose this exists to allow.
 */
final class RootTypeRef {

    private RootTypeRef() {
    }

    /** The root value's own type-ref name, or empty if it carries none. Leaves the stream as it found it. */
    static Optional<String> find(TsonReadContext ctx) {
        return DefaultTsonReadContext.lookingAhead(ctx, lookahead -> {
            EventSkip.annotations(lookahead);
            return lookahead.peek() instanceof TypeRef typeRef ? Optional.of(typeRef.name()) : Optional.empty();
        });
    }
}
