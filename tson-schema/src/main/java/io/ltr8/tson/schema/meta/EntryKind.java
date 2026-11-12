package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.function.Predicate;

/**
 * The kind of a resolved entry (Part 2 §4.1, §8.1) -- its value's type's kind ({@link #of}), derived and never
 * written.
 */
public enum EntryKind {
    ATOM, PRODUCT, SUM, ALIAS,

    /**
     * A value entry ({@link ValueEntry}): an instance of an ordinary type, {@code orders => !api { ... }}. It is
     * no type, and no kind holds it.
     */
    VALUE;

    /**
     * {@code value}'s kind. A body is the branch of {@link Top} it occupies -- an instance takes its constructor's
     * kind by being built as one (§5.5's transfer) -- except a record, open or closed, whose kind is the base
     * kind its chain reaches ({@link #ofChain}): {@code integer_type => atom & { ... }} is a record describing
     * atoms, and the kind of a constructor is what its instances are.
     *
     * @param kernel whether a chain's name denotes the kernel's own entry of that name -- a kind is judged by
     *               where a base kind is defined, never by its spelling, so a schema's own {@code atom => { ... }}
     *               composed into {@code payload => atom & { ... }} leaves {@code payload} a PRODUCT
     */
    public static EntryKind of(Top value, Predicate<String> kernel) {
        return switch (value) {
            case RecordBody record -> ofChain(record.supertypes(), kernel);

            case Atom ignored -> ATOM;
            case Product ignored -> PRODUCT;
            case Sum ignored -> SUM;
            // An application stands for a type that exists only once its open record is applied, so it has none.
            case ApplyBody application -> throw new IllegalStateException("the application '" + application.text()
                    + "' has no kind: it stands only inside an open record, written in place, and is no entry's value");
            case Alias ignored -> ALIAS;

            case ValueEntry ignored -> VALUE;
        };
    }

    /**
     * The kind a record's chain reaches: the first of the kernel's {@code atom}, {@code product} and {@code sum}
     * that appears in it as the kernel's own entry ({@code top} never counts),
     * and {@link #PRODUCT} where none does, a record being a product. Those entries themselves and not each
     * ancestor's own kind: the {@code atom} entry is itself a record of kind {@code PRODUCT}, so inheriting the
     * nearest ancestor's kind would answer wrongly for {@code atom}'s own subtypes. A chain reaching two is refused
     * where it is resolved.
     */
    public static EntryKind ofChain(List<String> chain, Predicate<String> kernel) {
        for (String supertype : chain) {
            if (!kernel.test(supertype)) {
                continue;
            }
            switch (supertype) {
                case "meta.atom" -> {
                    return ATOM;
                }
                case "meta.product" -> {
                    return PRODUCT;
                }
                case "meta.sum" -> {
                    return SUM;
                }
                default -> {
                }
            }
        }
        return PRODUCT;
    }

    /**
     * Whether {@code value} IS-A the kernel's {@code top} ([TSON-SCHEMA] §4.1): a constructor, a base kind,
     * {@code alias} or {@code template} -- what a construction may apply, and what a compiled
     * schema builds an instance reader for. Read off the chain, so a
     * template composing a base kind ({@code op => <T> product & { path: T }}) answers as its closed form would; and
     * judged by where {@code top} is defined, never by its spelling. The kernel's {@code top} answers for itself:
     * it is what every other answer IS-A, and as abstract as the base kinds beneath it, so its own reader is what
     * refuses a direct application of it. That is applicability, not kind membership: by the chain {@code top} is
     * a type, and a member of {@code meta.type_ref}.
     *
     * @param name the entry whose value {@code value} is
     */
    public static boolean isConstructor(String name, Top value, Predicate<String> kernel) {
        return (TypeChain.supertypesOf(value).contains("meta.top") || "meta.top".equals(name)) && kernel.test("meta.top");
    }
}
