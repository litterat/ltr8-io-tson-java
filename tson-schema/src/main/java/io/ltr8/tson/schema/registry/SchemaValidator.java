package io.ltr8.tson.schema.registry;

import io.ltr8.tson.schema.SchemaLoader;
import io.ltr8.tson.schema.SchemaValidationException;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UriType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The private "pass 2" {@code SchemaRegistry#register} runs before a schema is admitted: flattens
 * every {@code type_ref} with arguments into a real, named entry, then checks that every reference
 * anywhere in the schema actually resolves. Not part of the public API -- see this package's own
 * Javadoc note (on {@link CanonicalIdentity}) for why this class is nonetheless {@code public}.
 *
 * <p><b>Materialization is uniform</b> (a deliberate simplification confirmed with the user, not
 * Part 2 §8.2's literal text): *every* {@code type_ref} with a non-empty {@code arguments} list
 * gets a synthesized entry, regardless of whether the applied name is itself a constructor (like
 * {@code set}) or a genuine non-constructor template -- §8.2 says only the latter should
 * materialise. Bottom-up: a nested argument that's itself argument-bearing is materialized first,
 * so an outer entry's own synthesized name is built from an already-flattened application, and two
 * structurally-identical applications anywhere in the schema dedup to the same entry (record
 * equality on {@link TypeRef} is exactly the "flattened applications are structurally equal" test
 * §8.2 calls for). The synthesized entry's own shape is exactly {@link
 * TypeDefinition#reference(TypeRef)}'s existing one -- that method's own Javadoc already flagged
 * this gap ("this resolver doesn't materialise instantiation entries yet, so target is reused as
 * both source and (as a placeholder) body.target until that exists").
 *
 * <p><b>{@code TypeDefinition.source} is never itself materialized</b>, even when it carries
 * arguments (e.g. {@code set}'s own {@code source: array<T>}): it's provenance -- how this entry
 * was itself derived -- not a field consuming another type, so it's validated (a name must still
 * resolve) but never rewritten into a separate synthetic entry.
 *
 * <p><b>Type-parameter exception:</b> a bare name is valid if it resolves in the schema's own
 * namespace, or if it's one of the checked entry's own declared {@code parameters} -- load-bearing
 * for every parameterized declaration (`array`, `set`, `map`, `array_min`, `array_max`,
 * `array_ranged`), whose own {@code source}/body positions reference their own type parameter by
 * bare name (`array<T>`), not a real other entry.
 *
 * <p><b>{@code !!import} is not yet handled</b> -- a schema declaring one is rejected outright
 * rather than mishandled, matching this project's established convention for every other
 * not-yet-built construct. The {@code loader} parameter exists so this signature doesn't need to
 * change once import merging is built; it isn't consulted yet.
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    public static TsonSchema validate(TsonSchema schema, SchemaLoader loader) {
        if (!schema.imports().isEmpty()) {
            throw new SchemaValidationException(
                    "import merging not yet implemented (schema declares " + schema.imports().size() + " !!import)");
        }

        Map<TypeRef, String> materializedNames = new LinkedHashMap<>();
        Map<String, TypeDefinition> synthesized = new LinkedHashMap<>();
        Map<String, TypeDefinition> rewritten = new LinkedHashMap<>();

        for (Map.Entry<String, TypeDefinition> entry : schema.entries().entrySet()) {
            TypeDefinition def = entry.getValue();
            TypeBody rewrittenBody = rewriteBody(def.body(), materializedNames, synthesized);
            rewritten.put(entry.getKey(), new TypeDefinition(def.source(), def.kind(), def.parameters(),
                    def.constructor(), def.supertypes(), def.subtypes(), def.disjoint(), rewrittenBody));
        }
        rewritten.putAll(synthesized);

        for (Map.Entry<String, TypeDefinition> entry : rewritten.entrySet()) {
            validateEntry(entry.getKey(), entry.getValue(), rewritten);
        }

        return new TsonSchema(schema.id(), schema.meta(), schema.imports(), rewritten);
    }

    // ── Materialization ──────────────────────────────────────────────────

    private static TypeBody rewriteBody(TypeBody body, Map<TypeRef, String> materializedNames,
                                         Map<String, TypeDefinition> synthesized) {
        return switch (body) {
            case RecordBody r -> {
                List<RecordField> fields = new ArrayList<>(r.fields().size());
                for (RecordField field : r.fields()) {
                    fields.add(new RecordField(field.name(), materialize(field.type(), materializedNames, synthesized),
                            field.state(), field.value(), field.valueParam()));
                }
                yield new RecordBody(r.supertypes(), fields, r.groups());
            }
            case Reference ref -> new Reference(materialize(ref.target(), materializedNames, synthesized));
            case MapBody m -> new MapBody(
                    materialize(m.keyType(), materializedNames, synthesized),
                    materialize(m.valueType(), materializedNames, synthesized),
                    m.minItems(), m.maxItems());
            case ArrayBody a -> new ArrayBody(
                    materialize(a.elementType(), materializedNames, synthesized),
                    a.state(), a.unordered(), a.uniqueItems(), a.minItems(), a.maxItems());
            case TupleBody t -> {
                List<TupleElement> elements = new ArrayList<>(t.elements().size());
                for (TupleElement element : t.elements()) {
                    elements.add(new TupleElement(materialize(element.elementType(), materializedNames, synthesized),
                            element.state()));
                }
                yield new TupleBody(elements);
            }
            case ChoiceBody c -> {
                List<TypeRef> variants = new ArrayList<>(c.variants().size());
                for (TypeRef variant : c.variants()) {
                    variants.add(materialize(variant, materializedNames, synthesized));
                }
                yield new ChoiceBody(variants);
            }
            case Unit u -> u;
            case EnumBody e -> e;
            case IntegerType i -> i;
            case TextType t -> t;
            case UriType u -> u;
            case RegexType r -> r;
        };
    }

    /** Bottom-up: rewrites {@code ref}'s own arguments first, then materializes {@code ref} itself if it still has any. */
    private static TypeRef materialize(TypeRef ref, Map<TypeRef, String> materializedNames,
                                        Map<String, TypeDefinition> synthesized) {
        List<TypeArgument> rewrittenArgs = new ArrayList<>(ref.arguments().size());
        for (TypeArgument arg : ref.arguments()) {
            if (arg instanceof TypeArgument.Ref nested) {
                rewrittenArgs.add(new TypeArgument.Ref(materialize(nested.ref(), materializedNames, synthesized)));
            } else {
                rewrittenArgs.add(arg);
            }
        }
        TypeRef flattened = new TypeRef(ref.name(), rewrittenArgs);
        if (flattened.arguments().isEmpty()) {
            return flattened;
        }

        String existingName = materializedNames.get(flattened);
        if (existingName != null) {
            return TypeRef.of(existingName);
        }

        String syntheticName = syntheticName(flattened);
        materializedNames.put(flattened, syntheticName);
        synthesized.put(syntheticName, TypeDefinition.reference(flattened));
        return TypeRef.of(syntheticName);
    }

    /**
     * §8.2's own non-normative guidance: "a readable head plus a structural hash." Not
     * conformance-relevant -- free to refine if a real schema's names ever collide or read badly.
     */
    private static String syntheticName(TypeRef flattened) {
        StringBuilder head = new StringBuilder(flattened.name());
        for (TypeArgument arg : flattened.arguments()) {
            head.append('_');
            switch (arg) {
                case TypeArgument.Ref r -> head.append(r.ref().name());
                case TypeArgument.Value v -> head.append(v.value().text());
            }
        }
        return head + "_" + String.format("%08x", flattened.toString().hashCode());
    }

    // ── Validation ───────────────────────────────────────────────────────

    private static void validateEntry(String name, TypeDefinition def, Map<String, TypeDefinition> namespace) {
        if (def.source().isPresent()) {
            validateTypeRef(def.source().get(), namespace, def.parameters(), "'" + name + "' source");
        }
        for (String supertype : def.supertypes()) {
            if (!namespace.containsKey(supertype)) {
                throw new SchemaValidationException("'" + name + "' has an unresolved supertype '" + supertype + "'");
            }
        }
        for (String subtype : def.subtypes()) {
            if (!namespace.containsKey(subtype)) {
                throw new SchemaValidationException("'" + name + "' has an unresolved subtype '" + subtype + "'");
            }
        }
        validateBody(name, def.body(), namespace, def.parameters());
    }

    private static void validateBody(String entryName, TypeBody body, Map<String, TypeDefinition> namespace,
                                      List<String> ownParameters) {
        switch (body) {
            case RecordBody r -> {
                for (String supertype : r.supertypes()) {
                    if (!namespace.containsKey(supertype)) {
                        throw new SchemaValidationException(
                                "'" + entryName + "' has an unresolved supertype '" + supertype + "'");
                    }
                }
                for (RecordField field : r.fields()) {
                    validateTypeRef(field.type(), namespace, ownParameters,
                            "'" + entryName + "' field '" + field.name() + "'");
                }
                for (FieldGroup group : r.groups()) {
                    for (String member : group.members()) {
                        if (r.fields().stream().noneMatch(f -> f.name().equals(member))) {
                            throw new SchemaValidationException(
                                    "'" + entryName + "' has a field group referencing unknown field '" + member + "'");
                        }
                    }
                }
            }
            case Reference ref -> validateTypeRef(ref.target(), namespace, ownParameters, "'" + entryName + "'");
            case MapBody m -> {
                validateTypeRef(m.keyType(), namespace, ownParameters, "'" + entryName + "' key_type");
                validateTypeRef(m.valueType(), namespace, ownParameters, "'" + entryName + "' value_type");
            }
            case ArrayBody a -> validateTypeRef(a.elementType(), namespace, ownParameters,
                    "'" + entryName + "' element_type");
            case TupleBody t -> {
                int index = 0;
                for (TupleElement element : t.elements()) {
                    validateTypeRef(element.elementType(), namespace, ownParameters,
                            "'" + entryName + "' element[" + index + "]");
                    index++;
                }
            }
            case ChoiceBody c -> {
                int index = 0;
                for (TypeRef variant : c.variants()) {
                    validateTypeRef(variant, namespace, ownParameters, "'" + entryName + "' variant[" + index + "]");
                    index++;
                }
            }
            case Unit ignored -> {
            }
            case EnumBody ignored -> {
            }
            case IntegerType ignored -> {
            }
            case TextType ignored -> {
            }
            case UriType ignored -> {
            }
            case RegexType ignored -> {
            }
        }
    }

    private static void validateTypeRef(TypeRef ref, Map<String, TypeDefinition> namespace, List<String> ownParameters,
                                         String context) {
        if (!namespace.containsKey(ref.name()) && !ownParameters.contains(ref.name())) {
            throw new SchemaValidationException(context + " has an unresolved reference '" + ref.name() + "'");
        }
        for (TypeArgument arg : ref.arguments()) {
            if (arg instanceof TypeArgument.Ref nested) {
                validateTypeRef(nested.ref(), namespace, ownParameters, context);
            }
            // TypeArgument.Value is a literal token, not a type reference -- nothing to validate.
        }
    }
}
