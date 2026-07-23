package io.ltr8.tson.schema;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves declarations from a {@link SchemaMap} (the grammar-layer AST, {@code tson-parser}) into
 * {@link TypeDefinition}s (Part 2 §8's resolved schema-value shape, {@code io.ltr8.tson.schema.meta})
 * -- a first, deliberately narrow pass, not the full two-pass resolver of §3.4.1. It handles exactly
 * one construct so far: a fresh (no {@code ~}, no supertypes, no type parameters) record whose
 * fields are all plain, unmodified, REQUIRED, simple type-refs -- {@code integer_size}'s own shape
 * (`integer_size => { bits: integer  signed: boolean }`). Everything else -- elided field types,
 * modifiers, groups, composition, refinement, atom instances/refinements, generic type-refs,
 * templates -- is explicitly out of scope for now and reported via {@link
 * UnsupportedOperationException} rather than silently mis-resolved; each is a later, separate pass.
 *
 * <p>Deliberately does not consult a namespace: a field's type-ref (e.g. {@code integer}) is
 * carried through as a bare name, without checking that name actually resolves to a declared
 * entry anywhere -- namespace population and lookup (§3.3.2, §3.4.1's Pass 1/Pass 2) is later work.
 *
 * <p>Note the two {@code TypeRef}s in play: this class imports {@code tson-parser}'s grammar-layer
 * {@link TypeRef} (a source-text reference) for reading the AST, and refers to {@code
 * io.ltr8.tson.schema.meta.TypeRef} (the resolved reference it produces) by its fully-qualified
 * name -- the two share a name (matching the kernel's own single {@code type_ref} vocabulary type)
 * but live in different packages and are different concepts, so only one can be the unqualified
 * import here.
 */
public final class SchemaResolver {

    public TypeDefinition resolve(SchemaMap.Declaration declaration) {
        return resolveTypeDef(declaration.name(), declaration.typeDef());
    }

    /** Resolves every declaration in {@code schemaMap}, one entry at a time, in source order. */
    public TsonSchema resolveAll(SchemaMap schemaMap) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : schemaMap.declarations().values()) {
            entries.put(declaration.name(), resolve(declaration));
        }
        return new TsonSchema(entries);
    }

    private TypeDefinition resolveTypeDef(String name, TypeDef typeDef) {
        if (typeDef instanceof StructuralTypeDef structural
                && structural.body() instanceof RecordDef recordDef
                && !structural.constructor()
                && structural.typeParams().isEmpty()) {
            return TypeDefinition.product(resolveRecordConstruction(recordDef));
        }
        throw new UnsupportedOperationException(
                "'" + name + "': only fresh, non-parameterized record constructions are resolved so far, got "
                        + typeDef.getClass().getSimpleName());
    }

    private RecordBody resolveRecordConstruction(RecordDef recordDef) {
        List<RecordField> fields = new ArrayList<>();
        for (RecordEntry entry : recordDef.entries()) {
            fields.add(resolveField(entry));
        }
        return RecordBody.of(fields);
    }

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
