package io.ltr8.tson.schema.meta;

import io.ltr8.tson.schema.TsonSchema;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What to call a schema entry in a diagnostic -- the author's own name for it wherever there is one, and
 * otherwise the form that produced it.
 *
 * <p><b>Every entry is declared</b> ([TSON-SCHEMA] §8.2): nothing is lifted or minted, so a name is an author's, and
 * an entry with a source position ({@link TsonSchema.EntryFacts#position}) is named by it. An entry with none -- one
 * the kernel's bootstrap builds by hand, or one read from a resolved form -- is shown as the form its body is where
 * there is one: {@code [order; 1..]}, <code>{text =&gt; order}</code>, {@code [text, int32]}, {@code (text | int32)},
 * and {@code paged<order>} for a declared application, which its key records. A type written in place is no entry,
 * and is named by its own form ({@link TypeInline#text()}).
 */
public final class EntryDisplayName {

    private EntryDisplayName() {
    }

    /**
     * {@code name} if the author wrote it, else the form that produced this entry -- {@code application} being the
     * one its key carries ({@link TsonSchema#applicationOf(String)}).
     */
    public static String of(String name, Top value, TsonSchema.EntryFacts facts, Optional<TypeRef> application) {
        return of(name, value, facts, application, ignored -> null, ignored -> TsonSchema.EntryFacts.NONE,
                ignored -> Optional.empty());
    }

    /**
     * The same, resolving the names <em>inside</em> a form through {@code schema} as well, so a form built over
     * another entry with no position renders whole.
     */
    public static String of(String name, TsonSchema schema) {
        Top value = schema.entries().get(name);
        return value == null ? name : of(name, value, schema.factsOf(name), schema.applicationOf(name),
                schema.entries()::get, schema::factsOf, schema::applicationOf);
    }

    /**
     * The same over a namespace, its facts and its applications held apart -- the linker's merged view, which is
     * no one schema. A caller with no namespace to hand gets the one-level rendering ({@link #of(String, Top,
     * TsonSchema.EntryFacts, Optional)}), which is what a reader already has and enough for the value it is
     * reporting on.
     */
    public static String of(String name, Top value, TsonSchema.EntryFacts facts, Optional<TypeRef> application,
                            Function<String, ? extends Top> namespace,
                            Function<String, TsonSchema.EntryFacts> factsOf,
                            Function<String, Optional<TypeRef>> applicationOf) {
        Lookup lookup = new Lookup(namespace, factsOf, applicationOf);
        lookup.rendering().add(name);
        return authored(name, value, facts, application, lookup).orElse(name);
    }

    /**
     * What the author wrote that names entry {@code name}: its own name, the sugar form, or the application it
     * records. One level deep, as {@link #of(String, Top, TsonSchema.EntryFacts, Optional)} is.
     */
    public static Optional<String> authored(String name, Top value, TsonSchema.EntryFacts facts,
                                            Optional<TypeRef> application) {
        return authored(name, value, facts, application,
                new Lookup(ignored -> null, ignored -> TsonSchema.EntryFacts.NONE, ignored -> Optional.empty()));
    }

    private static Optional<String> authored(String name, Top value, TsonSchema.EntryFacts facts,
                                             Optional<TypeRef> application, Lookup lookup) {
        if (facts.position().isPresent()) {
            return Optional.of(name);
        }
        return applied(application).or(() -> Optional.ofNullable(switch (value) {
            // `[T]` is an ordered array that admits repeats; a set is the same body with neither, and has no sugar.
            case ArrayBody array when array.ordered() && !array.uniqueItems() -> "["
                    + shown(array.elementType(), lookup) + size(array.minItems(), array.maxItems()) + "]";
            case MapBody map -> "{" + shown(map.keyType(), lookup) + " => "
                    + shown(map.valueType(), lookup) + size(map.minItems(), map.maxItems()) + "}";
            case TupleBody tuple -> tuple.elements().stream().map(TupleElement::elementType)
                    .map(ref -> shown(ref, lookup)).collect(Collectors.joining(", ", "[", "]"));
            case ChoiceBody choice -> choice.variants().stream().map(ref -> shown(ref, lookup))
                    .collect(Collectors.joining(" | ", "(", ")"));
            default -> name;
        }));
    }

    /**
     * The three views a rendering resolves an inner name through, and the names being rendered: a recursive
     * form ({@code { v: text  next: <itself>? }}) names itself inside its own rendering, and is shown there by
     * its entry name rather than unrolled.
     */
    private record Lookup(Function<String, ? extends Top> namespace, Function<String, TsonSchema.EntryFacts> factsOf,
                          Function<String, Optional<TypeRef>> applicationOf, Set<String> rendering) {

        Lookup(Function<String, ? extends Top> namespace, Function<String, TsonSchema.EntryFacts> factsOf,
               Function<String, Optional<TypeRef>> applicationOf) {
            this(namespace, factsOf, applicationOf, new HashSet<>());
        }
    }

    /**
     * One slot inside a form: a name display-rendered as below, and a type written in place as the form it is,
     * each name inside it rendered the same way.
     */
    private static String shown(TypeExpr expr, Lookup lookup) {
        return switch (expr) {
            case TypeRef ref -> shown(ref, lookup);
            case TypeInline inline -> inline.text(slot -> shown(slot, lookup));
        };
    }

    /** One name inside a form, itself display-rendered when the namespace knows it and it has no position. */
    private static String shown(TypeRef ref, Lookup lookup) {
        Top referenced = lookup.namespace().apply(ref.name());
        if (referenced == null || !lookup.rendering().add(ref.name())) {
            return ref.name();
        }
        try {
            return authored(ref.name(), referenced, lookup.factsOf().apply(ref.name()),
                    lookup.applicationOf().apply(ref.name()), lookup).orElse(ref.name());
        } finally {
            lookup.rendering().remove(ref.name());
        }
    }

    /**
     * {@code head<arg, arg>} for a declared application, which its key carries (§8.2).
     */
    private static Optional<String> applied(Optional<TypeRef> application) {
        return application
                .filter(applied -> !applied.arguments().isEmpty())
                .map(applied -> applied.name() + applied.arguments().stream()
                        .map(TypeRef::name).collect(Collectors.joining(", ", "<", ">")));
    }

    /** §5.3's size specifier, in the shortest form that says the same thing -- and empty when unbounded. */
    private static String size(Optional<Long> min, Optional<Long> max) {
        if (min.isEmpty() && max.isEmpty()) {
            return "";
        }
        if (min.isPresent() && min.equals(max)) {
            return "; " + min.get();
        }
        return "; " + min.map(String::valueOf).orElse("") + ".." + max.map(String::valueOf).orElse("");
    }
}
