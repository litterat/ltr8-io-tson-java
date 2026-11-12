package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Unbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A type written where it is used, the kernel's {@code meta.type_inline}: the construction a sugar form or an applied
 * constructor denotes, or the record an application of an open record closes to, held at the slot that wrote it,
 * {@code { of: !meta.array { element_type: text } }}. It is no entry: it has no name, and nothing looks it up.
 *
 * <p><b>What may be inline</b> ([TSON-SCHEMA] §4.2, §5.2), and {@link #refusal} is the one place the line is kept --
 * the kernel types {@code of} by the abstract {@code meta.type}, which admits more, so the constructor admits what the
 * kernel does and whatever makes one from what an author wrote asks {@link #refusal} before it is used:
 * <ul>
 *   <li>a construction of a container, a tuple, a choice or a reference ({@link #constructible}): an {@link
 *       ArrayBody} ({@code meta.array}, and {@code meta.set_type} refining it), a {@link MapBody}, a {@link
 *       TupleBody}, a {@link ChoiceBody} or a {@link RefBody} ({@code meta.ref});</li>
 *   <li>a closed {@link RecordBody}, under {@code meta.record}: the record an application of an open record closes to
 *       at a type slot ([TSON-SCHEMA] §5.10), or one a resolved form states.</li>
 *   <li>an {@link ApplyBody}, {@code meta.apply}, an application written in place, which in resolved output stands
 *       only inside an open record, over one of its parameters;</li>
 *   <li>a {@link DependentBody}, {@code meta.dependent}, which is written in place only, and only directly at a record
 *       field's type ({@link #dependentOn}): a variable bound by that record, so equal text in two records is two
 *       types.</li>
 * </ul>
 * An enum and every other atom and a namespace type ({@link NamespaceBody}), whose values carry its name as their
 * tag, are declared and named.
 *
 * <p><b>{@code constructor} is the tag on {@code of}</b>, the entry the construction applies, past its alias hops
 * ([TSON-SCHEMA] §8.3): {@code set<T>} is {@code { of: !meta.set_type { element_type: T } }}, {@code meta.ref<T>} is
 * {@code { of: !meta.ref { of: T } }}, and a schema's own {@code nonempty_type<uuid>} is {@code { of: !nonempty_type {
 * element_type: uuid } }}. It is no field of the wrapper -- the kernel's {@code type_inline} declares {@code of} alone
 * -- so it is {@code @Unbound}, held beside the body as a declared entry's tag is held beside its value ({@code
 * TsonSchema.EntryFacts}): the body class says which kernel constructor built it, never which refinement of it. Where
 * none is given it is the one the body class is an instance of.
 *
 * <p><b>{@code application} is the application a record in place closes, as written</b> ({@code page<order>}), held
 * for a message and nothing else ({@link #text}): it is no field of the wrapper, takes no part in {@link #equals},
 * {@link #hashCode} or {@link #toString}, and is absent wherever it is unknown -- on every other construction, and on
 * a record read from a resolved form.
 *
 * <p><b>An inline body carries no chain.</b> It is a member of nothing and composes nothing, so its chain is empty,
 * and an empty chain is omitted from resolved output. Identity is the constructor's and {@code of}'s ([TSON-SCHEMA]
 * §8.2): two types written in place are one type when their constructor and fields are equal, recursively, names as
 * the leaves, annotations taking no part. {@code nonempty_type<uuid>} and {@code [uuid; 1..]} are two; {@code
 * box<int32>} and {@code crate<int32>} closing to one field list are one.
 */
@Typename(name = "meta.type_inline")
public record TypeInline(@Unbound String constructor, Type of, @Unbound ApplyBody application)
        implements TypeExpr {

    private static final String RECORD = "meta.record";

    @Record
    public TypeInline {
        Objects.requireNonNull(of, "of");
        constructor = constructor == null ? constructorOf(of) : constructor;
        if (application != null && !(of instanceof RecordBody)) {
            throw new IllegalArgumentException("only a record in place closes an application: " + application.text());
        }
    }

    /** {@code of} under {@code constructor}, no application recorded. */
    public TypeInline(String constructor, Type of) {
        this(constructor, of, null);
    }

    /** {@code of} applying the constructor its class is an instance of: {@code meta.array} for an array, and so on. */
    public TypeInline(Type of) {
        this(null, of, null);
    }

    /** The record {@code application}, written as it was, closes to in place ([TSON-SCHEMA] §5.10). */
    public static TypeInline closing(ApplyBody application, RecordBody record) {
        return new TypeInline(RECORD, record, Objects.requireNonNull(application, "application"));
    }

    /** The same constructor applied to {@code of} -- a rewrite of the slots inside the body. */
    public TypeInline withOf(Type of) {
        return new TypeInline(constructor, of, application);
    }

    /** {@code of} under {@code constructor}, the entry that built it. */
    public TypeInline withConstructor(String constructor) {
        return new TypeInline(constructor, of, application);
    }

    /**
     * Whether {@code type} is a construction that may be written in place: a container, a tuple, a choice or a
     * reference. A namespace type is no such container: its values are tagged by its name. A record in place is no
     * construction.
     */
    public static boolean constructible(Type type) {
        return type instanceof ArrayBody || type instanceof MapBody || type instanceof TupleBody
                || type instanceof ChoiceBody || type instanceof RefBody;
    }

    /**
     * Why this type may not be written in place ([TSON-SCHEMA] §4.2, §5.2), naming it by its form; empty where it may.
     * A type in place has no name: nothing composes it or applies it.
     */
    public Optional<String> refusal() {
        if (!constructible(of) && !(of instanceof DependentBody) && !(of instanceof RecordBody)
                && !(of instanceof ApplyBody)) {
            return Optional.of("'!" + constructor + " { ... }' is written in place, and only a container, a tuple, a "
                    + "choice, a reference, a dependent type, a record or an application is: an enum, an atom and a "
                    + "namespace type are declared and named");
        }
        String form = (of instanceof RecordBody ? "the record written in place '" : "the type written in place '")
                + text() + "'";
        if (!of.chain().isEmpty()) {
            return Optional.of(form + " states a chain, and a type written in place carries none: it has no name, "
                    + "so it is a member of nothing and nothing composes it");
        }
        if (of instanceof RecordBody record && record.isOpen()) {
            return Optional.of(form + " takes parameters, and an open record is applied by its name, which a record "
                    + "written in place has none of");
        }
        return Optional.empty();
    }

    /** The field a dependent type in place takes its type from, or empty where {@code of} is no {@link DependentBody}. */
    public Optional<String> dependentOn() {
        return of instanceof DependentBody dependent ? Optional.of(dependent.on()) : Optional.empty();
    }

    /** The application this record in place closes, as written, where it is known. */
    public Optional<ApplyBody> closes() {
        return Optional.ofNullable(application);
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
     * Structural ([TSON-SCHEMA] §8.2): the constructor and the body, and for a record its fields without the
     * annotations written on their keys. {@code application} takes no part.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof TypeInline other && constructor.equals(other.constructor)
                && identity(of).equals(identity(other.of));
    }

    @Override
    public int hashCode() {
        return Objects.hash(constructor, identity(of));
    }

    /** The two parts identity reads, as the canonical record's own rendering states them. */
    @Override
    public String toString() {
        return "TypeInline[constructor=" + constructor + ", of=" + of + "]";
    }

    /** {@code of} as identity compares it: a record's key annotations dropped, annotations taking no part (§8.2). */
    private static Type identity(Type of) {
        if (!(of instanceof RecordBody record) || record.fields().annotatedKeys().isEmpty()) {
            return of;
        }
        AnnotatedMap<String, RecordField> fields = new AnnotatedMap<>();
        record.fields().forEach(fields::put);
        return record.withFields(AnnotatedMap.copyOf(fields));
    }

    /**
     * The form as an author writes it, for a message: {@code [text]}, <code>{text =&gt; money}</code>, {@code [a, b]},
     * {@code (a | b)} for the four constructors with sugar, sizes and {@code ?} included; {@code set<text>} for a
     * set; a record in place as the application it closes ({@code page<order>}), or where that is unknown as its
     * fields (<code>{ v: int32 }</code>); and every other constructor, a refinement included, as its application,
     * {@code meta.ref<order>} or {@code pair_of<int32>}, its arguments the body's type slots.
     */
    @Override
    public String text() {
        return text(TypeExpr::text);
    }

    /** {@link #text()} with each type slot inside the body rendered by {@code slot}. */
    public String text(Function<TypeExpr, String> slot) {
        return switch (of) {
            case RecordBody record -> application != null ? application.text() : fields(record, slot);
            case DependentBody dependent -> dependent.text();
            case ApplyBody apply -> apply.text();
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

    /** A record in place with no application known, as its fields: <code>{ v: int32  w?: text? }</code>. */
    private static String fields(RecordBody record, Function<TypeExpr, String> slot) {
        List<String> written = new ArrayList<>();
        record.fields().forEach((name, field) -> written.add(name + (field.optional() ? "?" : "") + ": "
                + slot.apply(field.type())
                + (field.voidable() ? "?" : "")));
        return written.isEmpty() ? "{}" : "{ " + String.join("  ", written) + " }";
    }

    /** The body's type slots, rendered, as an application's argument list writes them. */
    private String arguments(Function<TypeExpr, String> slot) {
        return switch (of) {
            case ArrayBody array -> slot.apply(array.elementType());
            case MapBody map -> slot.apply(map.keyType()) + ", " + slot.apply(map.valueType());
            case TupleBody tuple -> String.join(", ",
                    tuple.elements().stream().map(element -> slot.apply(element.elementType())).toList());
            case ChoiceBody choice -> String.join(", ", choice.variants().stream().map(slot).toList());
            case RefBody reference -> slot.apply(reference.of());
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
