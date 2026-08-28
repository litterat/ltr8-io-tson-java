package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where the things a schema document declares sit in its source text -- what {@link TsonSchemaParser}
 * records as it parses and what the resolver stamps onto the values it produces, so a diagnostic can name a
 * line the author can open.
 *
 * <p><b>Identity-keyed, and that is the whole design.</b> A grammar-layer node compares structurally --
 * {@code SchemaDesugarerTest} asserts on rebuilt trees, and two declarations of the same shape are equal --
 * so a node cannot carry its own position without breaking that. Keeping positions beside the tree instead,
 * in {@link IdentityHashMap}s, lets a resolver ask "where was <em>this</em> node" using the exact object it
 * already holds. {@code TsonDataParser.positions} keys {@code CoreValue} the same way and for the same
 * reason.
 *
 * <p><b>One carrier rather than a parameter per kind.</b> The resolver chain threads this from the parser
 * down to {@code DefinitionResolver}, and there are more kinds to come -- a supertype and a choice variant
 * are bare names in a list today, with the same gap and no position between them. A kind added here reaches
 * every phase without five signatures churning, which is {@code ValueReaderContext}'s own argument for the
 * same shape one layer down.
 *
 * <p><b>The maps are mutable on purpose.</b> Desugaring rewrites any declaration containing a sugar form and
 * any field whose type is one, and a rebuilt node is a different identity -- so the phase carries each
 * original's position onto what replaced it ({@code SchemaDesugarer}). Without that, a record with a single
 * {@code [T]} field would lose the line for every diagnostic against it.
 *
 * @param declarations each {@code name =>} declaration, at its own name token
 * @param fields       each record field, at its own name token -- one level finer than the declaration it
 *                     sits in, which is what lets {@code /person/age} be positioned at {@code age}
 */
public record SchemaPositions(Map<SchemaMap.Declaration, SourcePosition> declarations,
                               Map<FieldDef, SourcePosition> fields) {

    /** Empty tables -- a caller that parsed nothing, or resolves a document whose source it never saw. */
    public static SchemaPositions none() {
        return new SchemaPositions(new IdentityHashMap<>(), new IdentityHashMap<>());
    }

    /** A mutable, identity-keyed copy -- what a phase that rewrites nodes takes before carrying positions over. */
    public SchemaPositions copy() {
        return new SchemaPositions(new IdentityHashMap<>(declarations), new IdentityHashMap<>(fields));
    }

    /** Where {@code declaration} was written, if this document's source is known. */
    public Optional<SourcePosition> of(SchemaMap.Declaration declaration) {
        return Optional.ofNullable(declarations.get(declaration));
    }

    /** Where {@code field} was written, if this document's source is known. */
    public Optional<SourcePosition> of(FieldDef field) {
        return Optional.ofNullable(fields.get(field));
    }

    /**
     * Carries {@code original}'s position onto {@code rewritten}, if there was one -- what a phase calls at
     * the one place it replaces a node with a rebuilt one.
     */
    public void carry(FieldDef original, FieldDef rewritten) {
        SourcePosition position = fields.get(original);
        if (position != null) {
            fields.put(rewritten, position);
        }
    }
}
