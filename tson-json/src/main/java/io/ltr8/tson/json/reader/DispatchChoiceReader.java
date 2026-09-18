package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A choice-typed position: [TSON-JSON] §8.2's discrimination predicate, which is the rule [TSON-SCHEMA] §5.4
 * requires each encoding to state over the resolver-derived {@code disjoint} fact.
 *
 * <p><b>A value may omit the tag if and only if one condition recovers the variant from the schema and the
 * JSON form alone</b>, and the predicate "MUST NOT be extended by implementation cleverness -- no
 * member-shape matching among record variants, no value-set separation, no trying variants in order". The
 * condition is that <em>the choice is {@code disjoint: true} and every variant is class-stable</em>, and
 * selection is then by the arriving value's kind, which names a discrimination class (§4.2) and so names the
 * one variant bearing it.
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
        ChoiceBody body = (ChoiceBody) definition.body();
        return new DispatchChoiceReader(EntryDisplayName.of(name, definition, context.schema().entries()), body,
                context, context.locationOf(name, definition));
    };

    private final String name;
    private final List<String> variants;
    private final JsonSchemaLocation schemaLocation;
    /** §8.2's condition, decided once: {@code disjoint} and class-stable, so the kind alone selects. */
    private final Map<DiscriminationClass, String> byClass;

    /** {@link #byClass}'s variants, as their readers. */
    private final Map<DiscriminationClass, JsonTypeReader<?>> readerByClass;

    /**
     * Every name a written {@code $type} may spell here, to where the value goes: each variant, every alias
     * whose chain ends at one, and every subtype of one -- §8.4's "subtypes of variants" note, which holds for
     * a tagged value at any choice: "a record whose {@code $type} names a proper subtype S of a variant
     * validates as S". {@link #variants} stays the declared list, which is what a diagnostic names.
     */
    private final Map<String, Route> routes;

    private DispatchChoiceReader(String name, ChoiceBody body, ValueReaderContext context,
                                 JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.variants = body.variants().stream().map(TypeRef::name).toList();
        this.schemaLocation = schemaLocation;
        this.byClass = variantsByClass(context.schema(), body);
        Map<DiscriminationClass, JsonTypeReader<?>> kinds = new LinkedHashMap<>();
        byClass.forEach((kind, variant) -> kinds.put(kind, context.readers().resolve(variant)));
        this.readerByClass = Map.copyOf(kinds);
        Map<String, Route> table = new LinkedHashMap<>();
        for (String written : context.admitting(variants)) {
            table.put(written, Route.to(context.readers().resolve(written)));
        }
        for (TypeRef variant : body.variants()) {
            JsonTypeReader<?> reader = context.readers().resolve(variant.name());
            ReferenceChain.terminal(context.schema(), variant.name())
                    .ifPresent(resolved -> context.admitting(resolved.definition().subtypes())
                            .forEach(subtype -> table.putIfAbsent(subtype, routeBelow(reader, subtype))));
        }
        this.routes = Map.copyOf(table);
    }

    /**
     * The route to {@code subtype} through the variant whose compiled reader is {@code variant}. A variant
     * placed by {@code $type} already holds that route, flattened; any other -- a sealed family, whose
     * selectors this position cannot bypass, or a reader still closing a cycle -- receives the value and
     * places it itself.
     */
    private static Route routeBelow(JsonTypeReader<?> variant, String subtype) {
        Route route = variant instanceof DispatchTagReader dispatch ? dispatch.route(subtype) : null;
        return route != null ? route : new Route(variant, variant);
    }

    /**
     * The class → variant table when §8.2's condition holds, and an empty table otherwise -- emptiness being exactly
     * "the tag is REQUIRED at this position".
     *
     * <p>Both halves are required and neither implies the other. The <b>{@code disjoint} fact</b> guarantees
     * at most one variant per class, and the resolver derives it ([TSON-SCHEMA] §5.4) -- a processor does not
     * re-derive it and MUST NOT second-guess it. <b>Class stability</b> guarantees the arriving value's kind
     * actually lands in its variant's class (§8.3); without it a `float64` variant admitting {@code .nan}
     * would receive a JSON string and dispatch as though a string variant had been chosen.
     *
     * <p>A variant with no class at all leaves the table empty rather than being skipped: §5.4 makes a choice
     * containing one non-disjoint, so this is unreachable in a linked schema -- and treating it as "the tag is
     * required" is the safe reading if it ever is reached.
     */
    private static Map<DiscriminationClass, String> variantsByClass(TsonSchema schema, ChoiceBody body) {
        if (!body.disjoint().orElse(false)) {
            return Map.of();
        }
        Map<DiscriminationClass, String> table = new LinkedHashMap<>();
        for (TypeRef variant : body.variants()) {
            Optional<DiscriminationClass> variantClass = DiscriminationClass.of(schema, variant.name());
            if (variantClass.isEmpty() || !DiscriminationClass.stable(schema, variant.name())
                    || table.put(variantClass.get(), variant.name()) != null) {
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
        // member is reserved is the tagged form -- always, before any other reading, and from that member
        // alone. That is what lets a bare map stand as a variant while its keys remain data: a reserved name
        // among its later keys is a key, and a map holding one rides in a wrapper when it is written.
        if (first instanceof JsonEvent.ObjectStart) {
            ReservedMembers.Lead lead = ReservedMembers.lead(ctx);
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
    private Object tagged(JsonReadContext ctx, ReservedMembers.Lead tag) {
        // §8.5 admits `$schema` at a scoped position -- the open sum -- and a choice is the closed one.
        if (Tags.refusesScope(ctx, tag, name, Tags.CHOICE)) {
            return null;
        }
        if (tag.type() == null) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                    "this object leads with this encoding's reserved members but no '$type' naming a variant of '"
                            + name + "' (§3.3)", "a '$type' member holding a variant name", "no $type");
            EventSkip.nextValue(ctx);
            return null;
        }
        Route route = routes.get(tag.type());
        if (route == null) {
            if (!NameHygiene.refuses(ctx, tag.type())) {
                ctx.field(ReservedMembers.TYPE).report(Diagnostic.Code.TYPE_MISMATCH,
                        "'$type' names '%s', which is not a variant of '%s'".formatted(tag.type(), name),
                        String.join(" | ", variants), tag.type());
            }
            EventSkip.nextValue(ctx);
            return null;
        }
        return route.read(ctx, tag);
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
     * choice that cannot be discriminated needs a tag on every value, where one that is disjoint and
     * class-stable but received an unexpected kind has a document problem. A null gets its own answer -- §7
     * spends it as the absent sentinel before any class question arises, so it is not an unrecognised kind but
     * an absence at a position admitting none.
     */
    private Object untagged(JsonReadContext ctx, JsonEvent first, Optional<DiscriminationClass> arriving) {
        String found = JsonAtoms.describe(first);
        if (first instanceof JsonEvent.NullValue) {
            ctx.report(Diagnostic.Code.FIELD_REQUIRED,
                    "'%s' admits no absence, and JSON null is this encoding's spelling of the absent sentinel (§7)"
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
