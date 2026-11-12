package io.ltr8.tson.tree;

/**
 * A reference read with no schema ([TSON-DATA] §4, §7.4), kept as the structured value it is and resolving nothing:
 * an application ({@link TsonApplicationReference}).
 *
 * <p><b>A tree reference is one the grammar alone shows to be a reference</b>: an application by its {@code <…>}. A
 * bare name is a {@link TsonAtom} holding text, since a schemaless read
 * cannot tell it from any other token.
 *
 * <p><b>There is no referent here.</b> With no schema there is no closure to resolve a name in, so this node offers no
 * accessor for what it names; a read under a schema delivers a reference at a reference-typed position as the schema model's
 * reference object.
 */
public sealed interface TsonReference extends TsonValue permits TsonApplicationReference {

    /** The canonical text, what the reference reads as: {@code box<text>}. */
    String text();

    @Override
    default boolean isReference() {
        return true;
    }
}
