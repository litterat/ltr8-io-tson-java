package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Unbound;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
 * <p><b>{@code constructor} is the tag on {@code of}</b>, the entry the construction applies, past its alias hops
 * ([TSON-SCHEMA] §8.3): {@code set<T>} is {@code { of: !meta.set_type { element_type: T } }}, {@code meta.ref<T>} is
 * {@code { of: !meta.ref { of: T } }}, and a schema's own {@code nonempty_type<uuid>} is {@code { of: !nonempty_type {
 * element_type: uuid } }}. It is no field of the wrapper -- the kernel's {@code type_inline} declares {@code of} alone
 * -- so it is {@code @Unbound}, held beside the body as a declared entry's tag is held beside its value ({@code
 * TsonSchema.EntryFacts}): the body class says which kernel constructor built it, never which refinement of it. Where
 * none is given it is the one the body class is an instance of.
 *
 * <p><b>An inline body carries no chain.</b> It is a member of nothing and composes nothing, so its chain is empty,
 * and an empty chain is omitted from resolved output. Identity is the constructor's and {@code of}'s: two
 * constructions are one type when their constructor and fields are equal, recursively, names as the leaves,
 * annotations taking no part ([TSON-SCHEMA] §1.2). {@code nonempty_type<uuid>} and {@code [uuid; 1..]} are two.
 */
@Typename(name = "meta.type_inline")
public record TypeInline(@Unbound String constructor, Type of) implements TypeExpr, FieldTypeExpr {

    @Record
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
        constructor = constructor == null ? constructorOf(of) : constructor;
    }

    /** {@code of} applying the constructor its class is an instance of: {@code meta.array} for an array, and so on. */
    public TypeInline(Type of) {
        this(null, of);
    }

    /** The same constructor applied to {@code of} -- a rewrite of the slots inside the body. */
    public TypeInline withOf(Type of) {
        return new TypeInline(constructor, of);
    }

    /** {@code of} under {@code constructor}, the entry that built it. */
    public TypeInline withConstructor(String constructor) {
        return new TypeInline(constructor, of);
    }

    /** Whether {@code type} may be written in place: a container, a tuple, a choice or a reference. */
    public static boolean inlinable(Type type) {
        return type instanceof ArrayBody || type instanceof MapBody || type instanceof NamespaceBody
                || type instanceof TupleBody || type instanceof ChoiceBody || type instanceof ReferenceBody;
    }

    /** The kernel constructor {@code type}'s class is an instance of, the one its {@link Typename} names. */
    private static String constructorOf(Type type) {
        Typename tag = type.getClass().getAnnotation(Typename.class);
        if (tag == null) {
            throw new IllegalStateException("no constructor names " + type.getClass().getSimpleName());
        }
        return tag.name();
    }

    /**
     * The form as an author writes it, for a message: {@code [text]}, <code>{text =&gt; money}</code>, {@code [a, b]},
     * {@code (a | b)} for the four constructors with sugar, sizes and {@code ?} included; {@code set<text>} for a
     * set; and every other constructor, a refinement included, as its application, {@code meta.ref<order>} or {@code
     * pair_of<int32>}, its arguments the body's type slots.
     */
    @Override
    public String text() {
        return text(TypeExpr::text);
    }

    /** {@link #text()} with each type slot inside the body rendered by {@code slot}. */
    public String text(Function<TypeExpr, String> slot) {
        return switch (of) {
            case ArrayBody array when constructor.equals("meta.array") -> "[" + slot.apply(array.elementType())
                    + (array.voidable() ? "?" : "") + size(array.minItems(), array.maxItems()) + "]";
            case MapBody map when constructor.equals("meta.map") -> "{" + slot.apply(map.keyType()) + " => "
                    + slot.apply(map.valueType()) + (map.voidable() ? "?" : "")
                    + size(map.minItems(), map.maxItems()) + "}";
            case TupleBody tuple when constructor.equals("meta.tuple") -> "[" + String.join(", ",
                    tuple.elements().stream().map(element -> slot.apply(element.elementType())
                            + (element.voidable() ? "?" : "")).toList()) + "]";
            case ChoiceBody choice when constructor.equals("meta.choice") -> "(" + String.join(" | ",
                    choice.variants().stream().map(slot).toList()) + ")";
            default -> (constructor.equals("meta.set_type") ? "set" : constructor) + "<" + arguments(slot) + ">";
        };
    }

    /** The body's type slots, rendered, as an application's argument list writes them. */
    private String arguments(Function<TypeExpr, String> slot) {
        return switch (of) {
            case ArrayBody array -> slot.apply(array.elementType());
            case MapBody map -> slot.apply(map.keyType()) + ", " + slot.apply(map.valueType());
            case NamespaceBody namespace -> slot.apply(namespace.keyType()) + ", " + slot.apply(namespace.valueType());
            case TupleBody tuple -> String.join(", ",
                    tuple.elements().stream().map(element -> slot.apply(element.elementType())).toList());
            case ChoiceBody choice -> String.join(", ", choice.variants().stream().map(slot).toList());
            case ReferenceBody reference -> slot.apply(reference.of());
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
