package io.ltr8.tson.schema;

import io.ltr8.tson.parser.ast.schema.ConstructionDef;
import io.ltr8.tson.parser.ast.schema.FieldDef;
import io.ltr8.tson.parser.ast.schema.RecordDef;
import io.ltr8.tson.parser.ast.schema.RecordEntry;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.parser.ast.schema.SimpleRef;
import io.ltr8.tson.parser.ast.schema.StructuralTypeDef;
import io.ltr8.tson.parser.ast.schema.TypeDef;
import io.ltr8.tson.parser.ast.schema.TypeRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves declarations from a {@link SchemaMap} (the grammar-layer AST, {@code tson-parser}) into
 * {@link TypeDefinition}s (Part 2 §8's resolved schema-value shape, {@code io.ltr8.tson.schema.meta})
 * -- an incremental, deliberately narrow resolver, not the full two-pass resolver of §3.4.1. It
 * handles two constructs so far:
 *
 * <ul>
 *   <li>A fresh (no {@code ~}, no supertypes, no type parameters) record whose fields are all
 *   plain, unmodified, REQUIRED, simple type-refs -- {@code integer_size}'s own shape.</li>
 *   <li>Non-constructor composition ({@code A & B & { ... }}, §5.8) over supertypes that are
 *   themselves already resolved, simple (non-generic) references, whose own body is a {@link
 *   RecordBody} -- {@code atom => top & {}}, {@code product => top & { access_pattern: ...
 *   size_type: ... }}, {@code sum => top & {}}, and {@code reference => top & { target: type_name
 *   } }'s own shape.</li>
 * </ul>
 *
 * Everything else -- elided field types, modifiers, groups, refinement, subtraction, atom
 * instances/refinements/constructors (any {@code ~}-marked composition), generic type-refs,
 * templates, tightening an inherited field in a composition body -- is explicitly out of scope for
 * now and reported via {@link UnsupportedOperationException} rather than silently mis-resolved;
 * each is a later, separate pass.
 *
 * <p><b>No namespace, only an accumulating "resolved so far" map.</b> A composition's supertypes
 * must already be present in the {@code resolved} map passed to {@link #resolve(SchemaMap.Declaration,
 * Map)} -- {@link #resolveAll} builds this map incrementally, in source order, so a supertype must
 * be declared earlier in the same schema map than anything composing with it (true for every
 * built-in base kind: {@code top} before {@code atom}/{@code product}/{@code sum}/{@code
 * reference}). Real forward references and cross-schema imports need the full namespace population
 * of §3.3.2/§3.4.1's Pass 1, not implemented yet.
 *
 * <p><b>Kind determination</b> (§4.1) checks the transitive supertype chain for the literal,
 * kernel-fixed names {@code atom}/{@code product}/{@code sum} -- not a general "inherit the nearest
 * ancestor's own kind" rule (that would be wrong: {@code atom} the type-definition entry is itself
 * {@code kind: PRODUCT}, since {@code atom}'s own supertype chain is just {@code [top]}, which
 * contains none of the three). Zero found -&gt; {@code PRODUCT} (structural default); exactly one
 * -&gt; that kind; two or more -&gt; a resolver error (reported here as {@link
 * UnsupportedOperationException}, not yet a proper diagnostic).
 *
 * <p><b>{@code subtypes} is never populated</b> -- computing it requires a reverse index over the
 * *whole* resolved schema (who lists me as a supertype, transitively), a global pass over every
 * entry, not a per-declaration concern; deliberately deferred, not forgotten.
 *
 * <p>Note the two {@code TypeRef}s in play: this class imports {@code tson-parser}'s grammar-layer
 * {@link TypeRef} (a source-text reference) for reading the AST, and refers to {@code
 * io.ltr8.tson.schema.meta.TypeRef} (the resolved reference it produces) by its fully-qualified
 * name -- the two share a name (matching the kernel's own single {@code type_ref} vocabulary type)
 * but live in different packages and are different concepts, so only one can be the unqualified
 * import here.
 */
public final class SchemaResolver {

    /** Resolves a single declaration with no supertypes visible -- fine for a fresh record like {@code integer_size}, not for a composition. */
    public TypeDefinition resolve(SchemaMap.Declaration declaration) {
        return resolve(declaration, Map.of());
    }

    /** Resolves a single declaration against {@code resolved}, the entries already resolved so far (for composition's supertype lookups). */
    public TypeDefinition resolve(SchemaMap.Declaration declaration, Map<String, TypeDefinition> resolved) {
        return resolveTypeDef(declaration.name(), declaration.typeDef(), resolved);
    }

    /** Resolves every declaration in {@code schemaMap}, one entry at a time, in source order, each seeing every entry resolved before it. */
    public TsonSchema resolveAll(SchemaMap schemaMap) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : schemaMap.declarations().values()) {
            entries.put(declaration.name(), resolve(declaration, entries));
        }
        return new TsonSchema(entries);
    }

    private TypeDefinition resolveTypeDef(String name, TypeDef typeDef, Map<String, TypeDefinition> resolved) {
        if (typeDef instanceof StructuralTypeDef structural && !structural.constructor() && structural.typeParams().isEmpty()) {
            if (structural.body() instanceof RecordDef recordDef) {
                return TypeDefinition.product(resolveRecordConstruction(recordDef));
            }
            if (structural.body() instanceof ConstructionDef construction) {
                return resolveComposition(name, construction, resolved);
            }
        }
        throw new UnsupportedOperationException(
                "'" + name + "': only fresh record constructions and non-constructor composition are resolved so far, got "
                        + typeDef.getClass().getSimpleName());
    }

    // ── Fresh record construction ─────────────────────────────────────────

    private RecordBody resolveRecordConstruction(RecordDef recordDef) {
        List<RecordField> fields = new ArrayList<>();
        for (RecordEntry entry : recordDef.entries()) {
            fields.add(resolveField(entry));
        }
        return RecordBody.of(fields);
    }

    // ── Composition (§5.8) ────────────────────────────────────────────────

    /**
     * {@code A & B & { ... }}: each supertype's fields are copied into the result, left to right
     * (§5.8's field-ordering rule), checked for name overlap across supertypes; the trailing body's
     * fields are appended after, but only when genuinely new -- a body field naming an inherited
     * field would be a tightening entry (§5.7), not supported here yet. {@code
     * type_definition.supertypes} accumulates by induction: each supertype's own {@code
     * supertypes()} is already its full transitive chain (by the same induction, computed when
     * *that* entry was resolved), so {@code direct + parent.supertypes()} for every direct
     * supertype, deduplicated, is the complete transitive chain -- no separate graph walk needed.
     */
    private TypeDefinition resolveComposition(String name, ConstructionDef construction, Map<String, TypeDefinition> resolved) {
        if (construction.removal().isPresent()) {
            throw new UnsupportedOperationException("'" + name + "': subtraction is not resolved yet");
        }

        List<String> directSupertypes = new ArrayList<>();
        List<String> transitiveSupertypes = new ArrayList<>();
        Set<String> seenTransitive = new HashSet<>();
        List<RecordField> fields = new ArrayList<>();
        Set<String> seenFieldNames = new HashSet<>();

        for (TypeRef supertypeRef : construction.supertypes()) {
            if (!(supertypeRef instanceof SimpleRef simple)) {
                throw new UnsupportedOperationException(
                        "'" + name + "': only simple supertype references are resolved so far, got " + supertypeRef);
            }
            String supertypeName = simple.name();
            TypeDefinition supertypeDef = resolved.get(supertypeName);
            if (supertypeDef == null) {
                throw new UnsupportedOperationException("'" + name + "': supertype '" + supertypeName
                        + "' is not resolved yet (only supertypes declared earlier in the same schema map are visible so far)");
            }
            if (!(supertypeDef.body() instanceof RecordBody supertypeBody)) {
                throw new UnsupportedOperationException("'" + name + "': supertype '" + supertypeName
                        + "' does not have a record body -- composing with a non-record supertype is not resolved yet");
            }

            directSupertypes.add(supertypeName);
            addIfAbsent(transitiveSupertypes, seenTransitive, supertypeName);
            for (String ancestor : supertypeDef.supertypes()) {
                addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
            }

            for (RecordField field : supertypeBody.fields()) {
                if (!seenFieldNames.add(field.name())) {
                    throw new UnsupportedOperationException("'" + name + "': supertypes contribute overlapping field '"
                            + field.name() + "' (§5.8 resolver error, not yet reported precisely)");
                }
                fields.add(field);
            }
        }

        if (construction.body().isPresent()) {
            for (RecordEntry entry : construction.body().get().entries()) {
                if (entry instanceof FieldDef fieldDef && seenFieldNames.contains(fieldDef.name())) {
                    throw new UnsupportedOperationException("'" + name + "': tightening an inherited field ('"
                            + fieldDef.name() + "') is not resolved yet");
                }
                RecordField field = resolveField(entry);
                seenFieldNames.add(field.name());
                fields.add(field);
            }
        }

        TypeKind kind = determineKind(name, transitiveSupertypes);
        RecordBody body = new RecordBody(directSupertypes, fields, List.of());
        return new TypeDefinition(Optional.empty(), kind, List.of(), false, transitiveSupertypes, List.of(),
                Optional.empty(), body);
    }

    private static void addIfAbsent(List<String> list, Set<String> seen, String name) {
        if (seen.add(name)) {
            list.add(name);
        }
    }

    /**
     * §4.1: a type's kind is settled by which of the kernel's three fixed base-kind names --
     * {@code atom}/{@code product}/{@code sum}, {@code top} never counts -- appear in its
     * transitive supertype chain. This checks those exact literal names, not each ancestor's own
     * resolved {@code kind} field: {@code atom} the entry is itself {@code kind: PRODUCT} (its own
     * chain is just {@code [top]}), so "inherit the nearest ancestor's kind" would give the wrong
     * answer even for {@code atom}'s own resolution.
     */
    private static TypeKind determineKind(String name, List<String> transitiveSupertypes) {
        List<String> baseKindsFound = new ArrayList<>();
        for (String supertype : transitiveSupertypes) {
            if (supertype.equals("atom") || supertype.equals("product") || supertype.equals("sum")) {
                baseKindsFound.add(supertype);
            }
        }
        if (baseKindsFound.isEmpty()) {
            return TypeKind.PRODUCT;
        }
        if (baseKindsFound.size() > 1) {
            throw new UnsupportedOperationException(
                    "'" + name + "': multiple base kinds in transitive supertype chain: " + baseKindsFound);
        }
        return switch (baseKindsFound.get(0)) {
            case "atom" -> TypeKind.ATOM;
            case "product" -> TypeKind.PRODUCT;
            case "sum" -> TypeKind.SUM;
            default -> throw new IllegalStateException(baseKindsFound.get(0));
        };
    }

    // ── Fields (shared by fresh records and composition) ──────────────────

    private RecordField resolveField(RecordEntry entry) {
        if (!(entry instanceof FieldDef field)) {
            throw new UnsupportedOperationException("field groups are not resolved yet: " + entry);
        }
        if (field.type().isEmpty()) {
            throw new UnsupportedOperationException("an elided field type is not resolved yet: " + field);
        }
        if (field.modifier().isPresent()) {
            throw new UnsupportedOperationException("field modifiers (~/=) are not resolved yet: " + field);
        }
        if (field.type().get().optional()) {
            throw new UnsupportedOperationException("optional fields are not resolved yet: " + field);
        }
        TypeRef ref = field.type().get().typeRef();
        if (!(ref instanceof SimpleRef simple)) {
            throw new UnsupportedOperationException("only simple (non-generic) type-refs are resolved so far: " + ref);
        }
        return RecordField.required(field.name(), io.ltr8.tson.schema.meta.TypeRef.of(simple.name()));
    }
}
