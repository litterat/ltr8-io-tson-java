package io.ltr8.tson.tree;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A reference with arguments read with no schema ([TSON-DATA] §4, §7.4): {@code map<text list<int32>>} kept as the
 * structured value it is, its qualified name's {@link #segments} and its {@link #arguments}, resolving nothing. Each
 * argument is read by the ordinary schemaless rule, so {@code 201} is a number and {@code text} a string, and a
 * nested application is another {@code TsonReference}. A qualified name without arguments is a token, read as any
 * token is.
 *
 * <p><b>There is no referent here.</b> With no schema there is no closure to resolve the name in, so this node offers
 * no accessor for what it names; a read under a schema delivers a reference at a reference-typed position as the
 * schema model's reference object, which carries one.
 *
 * <p>{@link #text} recreates the canonical text: no whitespace, one space between arguments.
 */
public record TsonReference(List<String> segments, List<TsonValue> arguments, Optional<String> typeRef,
                            List<TsonAnnotation> annotations) implements TsonValue {

    public TsonReference {
        segments = List.copyOf(segments);
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(typeRef, "typeRef");
        annotations = List.copyOf(annotations);
        if (segments.isEmpty() || arguments.isEmpty()) {
            throw new IllegalArgumentException("a reference has a qualified name and at least one argument");
        }
    }

    /** The qualified name, its segments joined by {@code .}. */
    public String name() {
        return String.join(".", segments);
    }

    /**
     * The canonical text: the name, an adjacent {@code <}, each argument separated by one space, and {@code >}. A
     * string argument that is a qualified name is written bare, as the reference it was read from; any other string
     * quoted, and every other argument as its value's text.
     */
    public String text() {
        StringBuilder out = new StringBuilder(name()).append('<');
        for (int i = 0; i < arguments.size(); i++) {
            out.append(i == 0 ? "" : " ").append(argumentText(arguments.get(i)));
        }
        return out.append('>').toString();
    }

    private static String argumentText(TsonValue argument) {
        return switch (argument) {
            case TsonReference nested -> nested.text();
            case TsonAtom atom when atom.value() instanceof String text -> isQualifiedName(text) ? text : quoted(text);
            case TsonAtom atom -> String.valueOf(atom.value());
            default -> String.valueOf(argument);
        };
    }

    /** Segments joined by {@code .}, each a Unicode identifier start followed by identifier parts and {@code -}. */
    static boolean isQualifiedName(String text) {
        for (String segment : text.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isUnicodeIdentifierStart(segment.codePointAt(0))) {
                return false;
            }
            for (int i = Character.charCount(segment.codePointAt(0)); i < segment.length(); ) {
                int c = segment.codePointAt(i);
                if (c != '-' && !Character.isUnicodeIdentifierPart(c)) {
                    return false;
                }
                i += Character.charCount(c);
            }
        }
        return true;
    }

    private static String quoted(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    @Override
    public boolean isReference() {
        return true;
    }

    /** The canonical text -- what the reference reads as. */
    @Override
    public String toString() {
        return text();
    }
}
