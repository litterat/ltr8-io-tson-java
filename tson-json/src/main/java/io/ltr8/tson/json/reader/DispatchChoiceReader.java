package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.ChoiceSelection;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A choice-typed position: [TSON-JSON] §8.2's discrimination predicate, which is the rule [TSON-SCHEMA] §5.4
 * requires each encoding to state over the resolver-derived {@code disjoint} fact.
 *
 * <p><b>A value may omit the tag if and only if one condition recovers the variant from the schema and the
 * JSON form alone</b>, and the predicate "MUST NOT be extended by implementation cleverness -- no
 * member-shape matching among record variants, no value-set separation, no trying variants in order". The
 * condition is that <em>the choice is {@code disjoint: true}</em>, and selection is then by the arriving
 * value's kind, which names a discrimination class (§4.2) and so names the one variant bearing it. Every
 * classed type is class-stable (§8.3), so the kind never lands a value in another variant's class.
 *
 * <p>There is no member dispatch at a choice position (§8.4): a discriminator is a property of a record
 * family and lives with the records (§6.1.5), so where the condition does not hold the tag is REQUIRED -- a
 * tagged value reads, and an untagged one is refused rather than guessed at.
 *
 * <p><b>The verdict is computed once, here, at schema load.</b> §8.3 asks for exactly that -- "an
 * implementation SHOULD compute the predicate's verdict per choice at schema load, in the manner of
 * [TSON-SCHEMA] §5.11's compiled group lookup -- the wire decision is then a table hit, not a per-value
 * derivation".
 *
 * <p><b>Every reader it can select was wired then too</b> ({@link Route}): a variant by its kind, and a
 * variant, an alias of one or a subtype of one by its tag. It builds nothing itself, so one class serves
 * every mode.
 */
final class DispatchChoiceReader implements JsonTypeReader<Object> {

    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        ChoiceBody body = (ChoiceBody) definition;
        return new DispatchChoiceReader(EntryDisplayName.of(name, context.schema()), body,
                context, context.locationOf(name));
    };

    private final String name;
    private final List<String> variants;
    private final JsonSchemaLocation schemaLocation;
    /** §8.2's condition, decided once: the choice is {@code disjoint}, so the kind alone selects. */
    private final Map<DiscriminationClass, TypeExpr> byClass;

    /** {@link #byClass}'s variants, as their readers. */
    private final Map<DiscriminationClass, JsonTypeReader<?>> readerByClass;

    /**
     * Every name a written {@code $type} may spell here, to where the value goes ([TSON-SCHEMA] §5.4, [TSON-JSON]
     * §8.1): each variant and every alias meaning the same type, then every type IS-A exactly one variant, which
     * validates as itself. {@link #variants} stays the declared list, which is what a diagnostic names.
     */
    private final Map<String, Route> routes;

    /** Every name IS-A more than one variant and meaning none, to those variants: it selects none. */
    private final Map<String, List<String>> selectsNone;

    /** Whether a name is one of the scope holding the choice, where its {@code $type} is read ([TSON-SCHEMA] §7.9). */
    private final Predicate<String> inScope;

    private DispatchChoiceReader(String name, ChoiceBody body, ValueReaderContext context,
                                 JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.variants = body.variants().stream().map(TypeExpr::text).toList();
        this.schemaLocation = schemaLocation;
        this.byClass = variantsByClass(context.linked(), body);
        Map<DiscriminationClass, JsonTypeReader<?>> kinds = new LinkedHashMap<>();
        byClass.forEach((kind, variant) -> kinds.put(kind, context.readers().resolve(variant)));
        this.readerByClass = Map.copyOf(kinds);
        // A variant written in place has no name a $type can spell: it is reached by its class alone.
        List<TypeRef> named = body.variants().stream().flatMap(variant -> TypeExpr.named(variant).stream()).toList();
        Map<TypeRef, AliasChain.Resolved> terminals = new LinkedHashMap<>();
        for (TypeRef variant : named) {
            AliasChain.terminal(context.schema(), variant.name()).ifPresent(resolved -> terminals.put(variant, resolved));
        }
        ChoiceSelection selection = ChoiceSelection.of(context.linked(), named,
                variant -> terminals.containsKey(variant)
                        ? context.admitting(List.of(terminals.get(variant).name())) : List.of(),
                variant -> terminals.containsKey(variant)
                        ? context.admitting(TypeChain.subtypesOf(terminals.get(variant).definition())) : List.of());
        Map<TypeRef, Route> byVariant = new LinkedHashMap<>();
        named.forEach(variant -> byVariant.put(variant, Route.to(context.readers().resolve(variant.name()))));
        Map<String, Route> table = new LinkedHashMap<>();
        selection.selects().forEach((written, variant) -> {
            Route route = byVariant.get(variant);
            // An extern variant's boundary takes the whole tagged object and admits the subtype's name itself (§7.9).
            boolean through = selection.named().contains(written) || context.linked().landing(variant.name())
                    .isPresent();
            table.put(written, through ? route
                    : routeBelow(context, terminals.get(variant).definition(), route.entry(), written));
        });
        this.routes = Map.copyOf(table);
        this.selectsNone = selection.selectsNone();
        this.inScope = context.schema().entries()::containsKey;
    }

    /**
     * The route to {@code subtype} through the variant whose definition is {@code variant} and whose compiled reader
     * is {@code reader}. A record variant placed by {@code $type} already holds that route, flattened; any other
     * record variant -- a sealed family, whose selectors this position cannot bypass, or a reader still closing a
     * cycle -- receives the value and places it itself. Below a variant that is no record the subtype's own reader
     * reads the value, whose facets the variant's reader would not check.
     */
    private static Route routeBelow(ValueReaderContext context, Top variant, JsonTypeReader<?> reader,
                                    String subtype) {
        if (!(variant instanceof RecordBody)) {
            return Route.to(context.readers().resolve(subtype));
        }
        Route route = reader instanceof DispatchTagReader dispatch ? dispatch.route(subtype) : null;
        return route != null ? route : new Route(reader, reader);
    }

    /**
     * The class → variant table when §8.2's condition holds, and an empty table otherwise -- emptiness being exactly
     * "the tag is REQUIRED at this position".
     *
     * <p>The <b>{@code disjoint} fact</b> guarantees at most one variant per class, and the resolver derives it
     * ([TSON-SCHEMA] §5.4) -- a processor does not re-derive it and MUST NOT second-guess it. It carries class
     * stability too (§8.3): §5.4 gives no class to an approximate atom admitting {@code .nan} or an infinity, or
     * to a map in pairs form, so a {@code float64} variant as core declares it makes its choice non-disjoint and
     * never receives a JSON string through this table.
     *
     * <p>A variant with no class at all leaves the table empty rather than being skipped: §5.4 makes a choice
     * containing one non-disjoint, so this is unreachable in a linked schema -- and treating it as "the tag is
     * required" is the safe reading if it ever is reached.
     */
    private static Map<DiscriminationClass, TypeExpr> variantsByClass(TsonLinkedSchema linked, ChoiceBody body) {
        if (!body.disjoint().orElse(false)) {
            return Map.of();
        }
        Map<DiscriminationClass, TypeExpr> table = new LinkedHashMap<>();
        for (TypeExpr variant : body.variants()) {
            Optional<DiscriminationClass> variantClass = DiscriminationClass.of(linked, variant);
            if (variantClass.isEmpty() || table.put(variantClass.get(), variant) != null) {
                return Map.of();
            }
        }
        return Map.copyOf(table);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        JsonEvent first = ctx.peek();

        // §8.2's decode order, step 1, and §8.3.1's escape rule: at a choice position an object whose first
        // member is one of the three is the tagged form -- always, before any other reading, and from that member
        // alone. That is what lets a bare map stand as a variant while its keys remain data: one of the three
        // among its later keys is a key, and a map holding one rides in a wrapper when it is written.
        if (first instanceof JsonEvent.ObjectStart) {
            TagMembers.Lead lead = TagMembers.lead(ctx);
            if (lead.present()) {
                return tagged(ctx, lead);
            }
        }

        // Step 2: the untagged route -- where §8.2's condition holds, dispatch on the value kind.
        Optional<DiscriminationClass> arriving = DiscriminationClass.ofKind(first);
        JsonTypeReader<?> variant = arriving.map(readerByClass::get).orElse(null);
        if (variant != null) {
            return variant.read(ctx);
        }
        return untagged(ctx, first, arriving);
    }

    /**
     * §8.1's tagged form: {@code $type} names the variant, and "a decoder MUST accept a {@code $type}-tagged
     * value at any choice position, including positions where the tag could have been omitted". So this runs
     * whether or not §8.2's condition holds, and a redundant tag is never wrong.
     */
    private Object tagged(JsonReadContext ctx, TagMembers.Lead tag) {
        if (Tags.refusesScope(ctx, tag, name, Tags.CHOICE)) {
            return null;
        }
        if (tag.type() == null) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                    "this object leads with this encoding's annotation members but no '$type' naming a variant of '"
                            + name + "' (§3.3)", "a '$type' member holding a variant name", "no $type");
            EventSkip.nextValue(ctx);
            return null;
        }
        Route route = routes.get(tag.type());
        if (route != null) {
            return route.read(ctx, tag);
        }
        List<String> among = selectsNone.get(tag.type());
        if (among != null) {
            ctx.field(TagMembers.TYPE).report(Diagnostic.Code.TYPE_MISMATCH,
                    "'$type' names '%s', a subtype of more than one variant of '%s' -- (%s) -- and of none by name, "
                            .formatted(tag.type(), name, String.join(" | ", among)) + "so it selects none (§8.1)",
                    String.join(" | ", among), tag.type());
        } else if (!NameHygiene.refuses(ctx, tag.type())) {
            boolean resolves = inScope.test(tag.type());
            ctx.field(TagMembers.TYPE).report(resolves ? Diagnostic.Code.TYPE_MISMATCH : Diagnostic.Code.UNKNOWN_TYPE_REF,
                    resolves ? "'$type' names '%s', which is neither a variant of '%s' nor a subtype of exactly one"
                            .formatted(tag.type(), name)
                            : "'$type' names '%s', which names no type of the scope holding '%s', where its tag is "
                            .formatted(tag.type(), name) + "read -- expected a variant or a type that IS-A exactly "
                            + "one ([TSON-SCHEMA] §5.4, §7.9)",
                    String.join(" | ", variants), tag.type());
        }
        EventSkip.nextValue(ctx);
        return null;
    }

    /**
     * The untagged route did not recover the variant, so §8.2's last clause applies: the tag is REQUIRED and
     * a value without one is a validation error at the position.
     *
     * <p><b>A missing tag is {@code TYPE_MISMATCH}</b>: no type was established for the value, which is what
     * that code says, and nothing unknown was written for {@code UNKNOWN_TYPE_REF} to name. It is the code
     * the TSON reader gives the same document, as §9.4's single vocabulary requires.
     *
     * <p>The message says <em>why</em> the tag is required, because the two reasons want different fixes: a
     * choice that cannot be discriminated needs a tag on every value, where a disjoint one that received an
     * unexpected kind has a document problem. A null gets its own answer -- §7
     * spends it as the void sentinel before any class question arises, so it is not an unrecognised kind but
     * a void value at a position admitting none.
     */
    private Object untagged(JsonReadContext ctx, JsonEvent first, Optional<DiscriminationClass> arriving) {
        String found = JsonAtoms.describe(first);
        if (first instanceof JsonEvent.NullValue) {
            ctx.report(Diagnostic.Code.FIELD_REQUIRED,
                    "'%s' is not voidable, and JSON null is this encoding's spelling of the void sentinel (§7)"
                            .formatted(name), "a value of one of (" + String.join(" | ", variants) + ")", "null");
        } else if (byClass.isEmpty()) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                    "'%s' cannot be discriminated from the JSON form alone, so a value here carries a '$type' "
                            .formatted(name) + "naming its variant (§8.2) -- its variants are ("
                            + String.join(" | ", variants) + ")",
                    "a '$type'-tagged value", found);
        } else {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                    "no variant of '%s' takes %s -- this choice discriminates on the JSON value kind (§8.2), and "
                            .formatted(name, found) + "the kinds its variants take are "
                            + byClass.keySet().stream().map(DiscriminationClass::name).toList(),
                    String.join(" | ", variants), found);
        }
        EventSkip.nextValue(ctx);
        return null;
    }
}
