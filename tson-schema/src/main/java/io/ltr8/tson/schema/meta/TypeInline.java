package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.Objects;
import java.util.Optional;

/**
 * A type written where it is used, the kernel's {@code meta.type_inline}: the construction a sugar form or an applied
 * constructor denotes, held at the slot that wrote it, {@code { of: !meta.array { element_type: text } }}. Nothing is
 * lifted to an entry and nothing is named by its content.
 *
 * <p><b>What may be inline</b> is a container, a tuple, a choice or a reference: {@code of} is an {@link ArrayBody}
 * ({@code meta.array}, and {@code meta.set_type} refining it), a {@link MapBody}, a {@link NamespaceBody}, a {@link
 * TupleBody}, a {@link ChoiceBody} or a {@link ReferenceBody} ({@code meta.reference_type}, and {@code meta.ref}).
 * A record, an enum and every other atom are declared and named ([TSON-SCHEMA] §5.2). This constructor keeps that
 * line, the one place it is kept: the kernel types {@code of} by the abstract {@code meta.type}, which admits more.
 *
 * <p><b>An inline body carries no chain.</b> It is a member of nothing and composes nothing, so its chain is empty,
 * and an empty chain is omitted from resolved output. Identity is {@code of}'s: two constructions are one type when
 * their constructor and fields are equal, recursively, names as the leaves, annotations taking no part.
 */
@Typename(name = "meta.type_inline")
public record TypeInline(Type of) implements TypeExpr, FieldTypeExpr {

    public TypeInline {
        Objects.requireNonNull(of, "of");
        if (!inlinable(of)) {
            throw new IllegalArgumentException("a type written in place is a container, a tuple, a choice or a "
                    + "reference, and a " + of.getClass().getSimpleName() + " is none: a record, an enum and an "
                    + "atom are declared and named ([TSON-SCHEMA] §5.2)");
        }
        if (!of.chain().equals(TypeChain.EMPTY)) {
            throw new IllegalArgumentException("a type written in place carries no chain: " + of.chain());
        }
    }

    /** Whether {@code type} may be written in place: a container, a tuple, a choice or a reference. */
    public static boolean inlinable(Type type) {
        return type instanceof ArrayBody || type instanceof MapBody || type instanceof NamespaceBody
                || type instanceof TupleBody || type instanceof ChoiceBody || type instanceof ReferenceBody;
    }

    /**
     * The form as an author writes it, for a message: {@code [text]}, <code>{text =&gt; money}</code>, {@code [a, b]},
     * {@code ( a | b )} or {@code meta.ref<order>}, sizes and {@code ?} included. A set is written {@code set<T>}, and
     * a namespace type, which has no sugar, as its constructor's tag and its two slots.
     */
    @Override
    public String text() {
        return switch (of) {
            case ArrayBody array when array.uniqueItems() && !array.ordered() ->
                    "set<" + array.elementType().text() + ">";
            case ArrayBody array -> "[" + array.elementType().text() + (array.voidable() ? "?" : "")
                    + size(array.minItems(), array.maxItems()) + "]";
            case MapBody map -> "{" + map.keyType().text() + " => " + map.valueType().text()
                    + (map.voidable() ? "?" : "") + size(map.minItems(), map.maxItems()) + "}";
            case NamespaceBody namespace -> "!meta.namespace { key_type: " + namespace.keyType().text()
                    + "  value_type: " + namespace.valueType().text() + " }";
            case TupleBody tuple -> "[" + String.join(", ", tuple.elements().stream()
                    .map(element -> element.elementType().text() + (element.voidable() ? "?" : "")).toList()) + "]";
            case ChoiceBody choice -> "( " + String.join(" | ",
                    choice.variants().stream().map(TypeExpr::text).toList()) + " )";
            case ReferenceBody reference -> "meta.ref<" + reference.of().text() + ">";
            default -> throw new IllegalStateException("not inlinable: " + of);
        };
    }

    private static String size(Optional<Long> min, Optional<Long> max) {
        if (min.isEmpty() && max.isEmpty()) {
            return "";
        }
        if (min.equals(max)) {
            return "; " + min.get();
        }
        return "; " + min.map(String::valueOf).orElse("") + ".." + max.map(String::valueOf).orElse("");
    }
}
