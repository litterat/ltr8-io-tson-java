package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * An application of an open record in canonical form ([TSON-SCHEMA] §8.2): what decides whether two applications
 * are one, where an applied parent's edge looks up the declaration naming its application and §5.10.1's recursion
 * ties a nested application to the declaration closing it. The caller follows each argument's alias chain first.
 *
 * <p><b>Compared as structure</b>: the head and the arguments, each a name, as plain references carrying no
 * annotation, so two spellings of one application are one value ({@link ApplyBody#equals}).
 */
final class CanonicalApplication {

    private CanonicalApplication() {
    }

    /** {@code head} applied to {@code arguments}, each already past its alias chain, as one comparable value. */
    static ApplyBody of(String head, List<TypeRef> arguments) {
        return new ApplyBody(TypeRef.of(head), arguments);
    }
}
