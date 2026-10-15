package io.ltr8.tson.schema.meta;

import io.ltr8.tson.schema.TsonSchema;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What to call a schema entry in a diagnostic -- the author's own name for it wherever there is one, and
 * otherwise the form they wrote that produced it.
 *
 * <p><b>Why an entry can have a name nobody wrote.</b> Every §5.3 sugar form lifts to an entry of its own
 * ({@code [order; 1..]} becomes {@code array_order_1_e9777a39}) and every §5.10 template application
 * materialises one ({@code paged<order>} becomes {@code paged_order_e0260dd4}), both named content-derived
 * so that two spellings of one type land on one entry (§8.2). Those names are internal and non-normative,
 * and a reader that puts one in a message names a thing that appears nowhere in the author's file -- against
 * this project's own rule that a diagnostic never names what the author did not write, and now doubly so
 * where diagnostics travel to a reader who cannot open the schema at all.
 *
 * <p><b>A missing source position is what tells them apart</b> ({@link TsonSchema.EntryFacts#position}), and it is exact
 * rather than a heuristic on
 * the name's shape: a declaration parsed from a document carries the position of its own name token, and an
 * entry the resolver mints has nothing to carry. So {@code tag_list => [text; 1..2]} keeps {@code tag_list},
 * which is what its author would look for, while the anonymous form inside it renders as the form.
 *
 * <p>The rendering is the sugar the author would have written, not a description of it: {@code
 * [order; 1..]}, <code>{text =&gt; order}</code>, {@code [text, int32]}, {@code (text | int32)},
 * {@code paged&lt;order&gt;}. Anything with no sugar spelling falls back to the entry name -- honest rather
 * than invented, and unreachable in practice, since a form with no spelling is a form nobody wrote.
 */
public final class EntryDisplayName {

    private EntryDisplayName() {
    }

    /** {@code name} if the author wrote it, else the form that produced this entry. */
    public static String of(String name, Top value, TsonSchema.EntryFacts facts) {
        return of(name, value, facts, Map.of(), ignored -> TsonSchema.EntryFacts.NONE);
    }

    /**
     * The same, resolving the names <em>inside</em> a form through {@code schema} as well, so a form built over
     * another derived entry renders whole -- {@code [tree<text>; 1..]} rather than {@code [tree_text_a7f070f6;
     * 1..]}.
     */
    public static String of(String name, TsonSchema schema) {
        Top value = schema.entries().get(name);
        return value == null ? name : of(name, value, schema.factsOf(name), schema.entries(), schema::factsOf);
    }

    /**
     * The same over a namespace and its facts held apart -- the linker's merged view, which is no one schema. A
     * caller with no namespace to hand gets the one-level rendering ({@link #of(String, Top,
     * TsonSchema.EntryFacts)}), which is what a reader already has and enough for the value it is reporting on.
     */
    public static String of(String name, Top value, TsonSchema.EntryFacts facts, Map<String, ? extends Top> namespace,
                            Function<String, TsonSchema.EntryFacts> factsOf) {
        if (facts.position().isPresent()) {
            return name;
        }
        return application(facts).orElseGet(() -> switch (value) {
            case ArrayBody array -> "[" + shown(array.elementType(), namespace, factsOf)
                    + size(array.minItems(), array.maxItems()) + "]";
            case MapBody map -> "{" + shown(map.keyType(), namespace, factsOf) + " => "
                    + shown(map.valueType(), namespace, factsOf) + size(map.minItems(), map.maxItems()) + "}";
            case TupleBody tuple -> tuple.elements().stream().map(TupleElement::elementType)
                    .map(ref -> shown(ref, namespace, factsOf)).collect(Collectors.joining(", ", "[", "]"));
            case ChoiceBody choice -> choice.variants().stream().map(ref -> shown(ref, namespace, factsOf))
                    .collect(Collectors.joining(" | ", "(", ")"));
            default -> name;
        });
    }

    /** One name inside a form, itself display-rendered when the namespace knows it and it is derived. */
    private static String shown(TypeRef ref, Map<String, ? extends Top> namespace,
                                Function<String, TsonSchema.EntryFacts> factsOf) {
        Top referenced = namespace.get(ref.name());
        return referenced == null ? ref.name()
                : of(ref.name(), referenced, factsOf.apply(ref.name()), namespace, factsOf);
    }

    /**
     * {@code head<arg, arg>} for an entry a template application materialised: an instantiation entry keys its
     * identity on the application it closes (§8.2), and its facts keep that application as the author wrote it
     * at the site.
     */
    private static Optional<String> application(TsonSchema.EntryFacts facts) {
        return facts.application()
                .filter(application -> !application.arguments().isEmpty())
                .map(application -> application.name() + application.arguments().stream()
                        .map(EntryDisplayName::argument).collect(Collectors.joining(", ", "<", ">")));
    }

    private static String argument(TypeArgument argument) {
        return switch (argument) {
            case TypeArgument.Ref ref -> ref.ref().name();
            case TypeArgument.Value value -> value.value().text();
        };
    }

    /** §5.3's size specifier, in the shortest form that says the same thing -- and empty when unbounded. */
    private static String size(Optional<BigInteger> min, Optional<BigInteger> max) {
        if (min.isEmpty() && max.isEmpty()) {
            return "";
        }
        if (min.isPresent() && min.equals(max)) {
            return "; " + min.get();
        }
        return "; " + min.map(BigInteger::toString).orElse("") + ".." + max.map(BigInteger::toString).orElse("");
    }
}
