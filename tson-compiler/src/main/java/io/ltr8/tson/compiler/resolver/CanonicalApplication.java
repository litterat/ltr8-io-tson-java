package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * An application of an open record rendered canonically ([TSON-SCHEMA] §8.2): what decides whether two applications
 * are one, where an applied parent's edge looks up the declaration naming its application and §5.10.1's recursion
 * ties a nested application to the declaration closing it. The caller follows each argument's alias chain first.
 *
 * <p><b>The rendering is injective by construction</b>: the head, then each argument, each name written length-first
 * ({@code 4:text}) and each argument list bracketed, so no arrangement of delimiters inside a name can spell a
 * different application, and an application nested as an argument is its parsed form, never its spelling.
 */
final class CanonicalApplication {

    private CanonicalApplication() {
    }

    /** An application rendered structurally and injectively -- what decides whether two are the same application. */
    static String of(String head, List<TypeRef> arguments) {
        StringBuilder canonical = new StringBuilder();
        appendText(canonical.append('A'), head);
        canonical.append('(');
        for (TypeRef argument : arguments) {
            appendRef(canonical.append('r'), argument);
        }
        return canonical.append(')').toString();
    }

    private static void appendRef(StringBuilder out, TypeRef ref) {
        appendText(out.append('n'), ref.name());
        out.append('(');
        for (TypeRef argument : ref.arguments()) {
            appendRef(out.append('r'), argument);
        }
        out.append(')');
    }

    /** Length-first, so concatenation stays unambiguous whatever the text contains. */
    private static void appendText(StringBuilder out, String text) {
        out.append(text.length()).append(':').append(text);
    }
}
