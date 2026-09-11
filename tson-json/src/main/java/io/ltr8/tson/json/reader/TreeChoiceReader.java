package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.TsonSchema;
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
 * <p><b>A value may omit the tag if and only if one of two routes recovers the variant from the schema and
 * the JSON form alone</b>, and the predicate "MUST NOT be extended by implementation cleverness -- no
 * member-shape matching among record variants, no value-set separation, no trying variants in order". This
 * reader implements route 2 and no more: <em>the choice is {@code disjoint: true} and every variant is
 * class-stable</em>, and selection is then by the arriving value's kind, which names a discrimination class
 * (§4.2) and so names the one variant bearing it.
 *
 * <p>Route 1 -- a declared {@code @discriminator} (§8.4) -- is not built, so a choice carrying that
 * annotation currently falls through to "the tag is REQUIRED". That is the correct verdict for a reader
 * without the route, and not a silent approximation of it: a tagged value still reads, and an untagged one is
 * refused rather than guessed at.
 *
 * <p><b>The verdict is computed once, here, at schema load.</b> §8.3 asks for exactly that -- "an
 * implementation SHOULD compute the predicate's verdict per choice at schema load, in the manner of
 * [TSON-SCHEMA] §5.11's compiled group lookup -- the wire decision is then a table hit, not a per-value
 * derivation".
 */
final class TreeChoiceReader implements JsonTypeReader<JsonValue> {

    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        ChoiceBody body = (ChoiceBody) definition.body();
        return new TreeChoiceReader(name, body, context, context.locationOf(name, definition));
    };

    private final String name;
    private final List<String> variants;
    private final JsonSchemaLocation schemaLocation;
    private final TypeReaderResolver readerFor;

    /** §8.2's route 2, decided once: {@code disjoint} and class-stable, so the kind alone selects. */
    private final Map<DiscriminationClass, String> byClass;

    /**
     * Every subtype of every variant, to the variant it reaches -- §8.4's "subtypes of variants" note, which
     * holds for a tagged value at any choice: "a record whose {@code $type} names a proper subtype S of a
     * variant validates as S". Resolved here so the read is a lookup.
     */
    private final Map<String, String> bySubtype;

    private TreeChoiceReader(String name, ChoiceBody body, ValueReaderContext context,
                             JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.variants = body.variants().stream().map(TypeRef::name).toList();
        this.schemaLocation = schemaLocation;
        this.readerFor = context.readers();
        this.byClass = routeTwo(context.schema(), body);
        Map<String, String> subtypes = new LinkedHashMap<>();
        for (TypeRef variant : body.variants()) {
            Types.terminal(context.schema(), variant.name())
                    .ifPresent(resolved -> resolved.definition().subtypes()
                            .forEach(subtype -> subtypes.putIfAbsent(subtype, variant.name())));
        }
        this.bySubtype = Map.copyOf(subtypes);
    }

    /**
     * The class → variant table when route 2 holds, and an empty table otherwise -- emptiness being exactly
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
    private static Map<DiscriminationClass, String> routeTwo(TsonSchema schema, ChoiceBody body) {
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
    public JsonValue read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        JsonEvent first = ctx.peek();

        // §8.2's decode order, step 1, and §8.3.1's escape rule: at a choice position an object carrying any
        // reserved member is the tagged form -- always, before any other reading. That is what lets a bare
        // map stand as a variant while its keys remain data: the one ambiguity is settled by the tag, and a
        // map whose keys would collide rides in a wrapper.
        if (first instanceof JsonEvent.ObjectStart) {
            ReservedMembers.Tag tag = ReservedMembers.scan(ctx);
            if (tag.present() || tag.unknown() != null) {
                return tagged(ctx, tag);
            }
        }

        // Step 2 would be a declared discriminator (§8.4), which is not built. Step 3: route 2.
        Optional<DiscriminationClass> arriving = DiscriminationClass.ofKind(first);
        String variant = arriving.map(byClass::get).orElse(null);
        if (variant != null) {
            return (JsonValue) readerFor.resolve(variant).read(ctx);
        }
        return untagged(ctx, first, arriving);
    }

    /**
     * §8.1's tagged form: {@code $type} names the variant, and "a decoder MUST accept a {@code $type}-tagged
     * value at any choice position, including positions where the tag could have been omitted". So this runs
     * whether or not route 2 holds, and a redundant tag is never wrong.
     */
    private JsonValue tagged(JsonReadContext ctx, ReservedMembers.Tag tag) {
        if (tag.unknown() != null) {
            ReservedMembers.refuseUnknown(ctx, tag.unknown());
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (tag.schema()) {
            // §8.5 admits `$schema` at a scoped position -- the open sum -- and a choice is the closed one.
            ctx.field(ReservedMembers.SCHEMA).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                    "'$schema' opens a schema scope, which [TSON-SCHEMA] §7.8 admits only at a scoped position "
                            + "-- '" + name + "' is a choice, whose variants its own schema declares",
                    "no $schema at this position", ReservedMembers.SCHEMA);
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (tag.type() == null) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF,
                    "this object carries this encoding's reserved members but no '$type' naming a variant of '"
                            + name + "' (§3.3)", "a '$type' member holding a variant name", "no $type");
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        String selected = variants.contains(tag.type()) ? tag.type() : variantAdmitting(tag.type());
        if (selected == null) {
            if (!NameHygiene.refuses(ctx, tag.type())) {
                ctx.field(ReservedMembers.TYPE).report(Diagnostic.Code.UNKNOWN_TYPE_REF,
                        "'$type' names '%s', which is not a variant of '%s'".formatted(tag.type(), name),
                        String.join(" | ", variants), tag.type());
            }
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        // A subtype of a variant is admissible (§8.4's own note) and reaches the variant's own reader, which
        // applies [TSON-SCHEMA] §7.2 to whatever the tag named. Naming the variant is all this position decides.
        JsonTypeReader<?> reader = readerFor.resolve(selected);
        return tag.wrapper()
                ? ReservedMembers.readWrapped(ctx, reader)
                : (JsonValue) reader.read(ctx);
    }

    /** The variant {@code type} is a subtype of, or null -- §7.2's admission, resolved once at construction. */
    private String variantAdmitting(String type) {
        return bySubtype.get(type);
    }

    /**
     * No route recovered the variant, so §8.2's last clause applies: the tag is REQUIRED and a value without
     * one is a validation error at the position.
     *
     * <p><b>A missing tag is {@code UNKNOWN_TYPE_REF}</b>, which is the code the TSON reader gives the same
     * document and therefore the one §9.4's single vocabulary requires here. It is a poor fit read literally
     * -- nothing unknown was written, the tag being absent rather than unresolvable -- and the closed
     * {@code Code} enum has no member for "a required tag is missing", which `BACKLOG.md` records. What the
     * two encodings must not do is disagree, and the incumbent's choice is the one that settles it.
     *
     * <p>The message says <em>why</em> the tag is required, because the two reasons want different fixes: a
     * choice that is not disjoint needs a tag on every value (or a discriminator), where one that is disjoint
     * but received an unexpected kind has a document problem. A null gets its own answer -- §7 spends it as
     * the absent sentinel before any class question arises, so it is not an unrecognised kind but an absence
     * at a position admitting none.
     */
    private JsonValue untagged(JsonReadContext ctx, JsonEvent first, Optional<DiscriminationClass> arriving) {
        String found = JsonAtoms.describe(first);
        if (first instanceof JsonEvent.NullValue) {
            ctx.report(Diagnostic.Code.FIELD_REQUIRED,
                    "'%s' admits no absence, and JSON null is this encoding's spelling of the absent sentinel (§7)"
                            .formatted(name), "a value of one of (" + String.join(" | ", variants) + ")", "null");
        } else if (byClass.isEmpty()) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF,
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
        return JsonNull.INSTANCE;
    }
}
