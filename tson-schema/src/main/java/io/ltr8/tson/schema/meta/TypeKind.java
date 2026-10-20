package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.function.Predicate;

/**
 * The kind of a resolved entry (Part 2 §4.1, §8.1) -- its value's type's kind ({@link #of}), derived and never
 * written, since the meta-kernel has no {@code type_kind}.
 */
public enum TypeKind {
    ATOM, PRODUCT, SUM, REFERENCE,

    /**
     * An entry that describes something other than a data value -- meta-schema vocabulary riding along in a
     * schema map. Its body is a {@link Data}, or the record describing one; nothing may be typed by it.
     */
    DATA,

    /**
     * An entry that declares type parameters -- [TSON-SCHEMA] §5.10's <b>open</b> entry, whose body is a
     * {@link TemplateBody}. A template is not a type: it cannot validate data, and naming one where a type is
     * expected is a resolver error. Like {@link #REFERENCE} it is a {@code type_kind} and not a base kind
     * (§4.1), and it says nothing about what an application of the template will produce -- that is the kind
     * of the entry materialisation mints, derived from the constructor its closed body applies.
     */
    TEMPLATE;

    /**
     * {@code value}'s kind. A body is the branch of {@link Top} it occupies -- an instance takes its constructor's
     * kind by being built as one (§5.5's transfer) -- except a record, whose kind is the base kind its chain
     * reaches ({@link #ofChain}): {@code integer_type => atom & { ... }} is a record describing atoms, and the kind
     * of a constructor is what its instances are.
     *
     * @param kernel whether a chain's name denotes the meta-kernel's own entry of that name -- a kind is judged by
     *               where a base kind is defined, never by its spelling, so a schema's own {@code data => { ... }}
     *               composed into {@code payload => data & { ... }} leaves {@code payload} a PRODUCT
     */
    public static TypeKind of(Top value, Predicate<String> kernel) {
        if (OpenEntry.isOpen(value)) {
            return TEMPLATE;
        }
        return switch (value) {
            case RecordBody record -> ofChain(record.supertypes(), kernel);
            case Atom ignored -> ATOM;
            case Product ignored -> PRODUCT;
            case Sum ignored -> SUM;
            case Reference ignored -> REFERENCE;
            case Data ignored -> DATA;
            case TemplateBody ignored -> TEMPLATE; // open, answered above
        };
    }

    /**
     * The base kind a record's chain reaches: the first of the kernel's four base kinds -- {@code atom}, {@code
     * product}, {@code sum}, {@code data}; {@code top} never counts -- that appears in it as the kernel's own
     * entry, and {@link #PRODUCT} where none does, a record being a product. The base kinds themselves and not
     * each ancestor's own kind: the {@code atom} entry is itself a record of kind {@code PRODUCT}, so inheriting
     * the nearest ancestor's kind would answer wrongly for {@code atom}'s own subtypes. A chain reaching two is
     * refused where it is resolved.
     */
    public static TypeKind ofChain(List<String> chain, Predicate<String> kernel) {
        for (String supertype : chain) {
            if (!kernel.test(supertype)) {
                continue;
            }
            switch (supertype) {
                case "atom" -> {
                    return ATOM;
                }
                case "product" -> {
                    return PRODUCT;
                }
                case "sum" -> {
                    return SUM;
                }
                case "data" -> {
                    return DATA;
                }
                default -> {
                }
            }
        }
        return PRODUCT;
    }

    /**
     * Whether {@code value} IS-A the meta-kernel's {@code top} ([TSON-SCHEMA] §4.1): a constructor, a base kind,
     * {@code reference} or {@code template} -- what a construction may apply, what only a meta-kernel-governed
     * schema may declare, and what a meta layer builds a reader for. Read off the chain, so a template composing a
     * base kind ({@code op => <T> data & { path: T }}) answers as its closed form would; and judged by where
     * {@code top} is defined, never by its spelling. The kernel's {@code top} answers for itself: it is what
     * every other answer IS-A, and as abstract as the base kinds beneath it, so its own reader is what refuses a
     * direct application of it.
     *
     * @param name the entry whose value {@code value} is
     */
    public static boolean isConstructor(String name, Top value, Predicate<String> kernel) {
        return (Chained.supertypesOf(value).contains("top") || "top".equals(name)) && kernel.test("top");
    }
}
