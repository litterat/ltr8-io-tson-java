package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonTypeReader;

/**
 * The reader for a child position, named the way the author wrote that position.
 *
 * <p><b>Why a position needs a name of its own.</b> A field declared {@code c: pct} over {@code pct => small}
 * reads with {@code small}'s reader, that being the type at the end of the chain. A message from it against
 * {@code c} would otherwise read {@code 'small': '500' is greater than the maximum 100}, naming a declaration
 * the author did write but did not write <em>here</em>, and for a chain ending in an imported type
 * ({@code my_int => int32}) naming a file they never opened. So the {@code REFERENCE} entry's own compile
 * names the target's reader for the entry doing the referring ({@code TsonSchemaCompiler}), and a use site
 * naming {@code pct} gets a reader called {@code pct}.
 *
 * <p><b>A use site needs nothing of its own for that</b>, which it used to. Resolved output no longer
 * rewrites a type position past a reference, so a position reaches its child reader by resolving the name it
 * names and nothing more -- every container and record field calls the resolver directly. What is left here
 * is the {@link Renamed} seam a compiled reader offers a caller holding one, and the {@link #named} helper
 * the reference compile applies it through.
 *
 * <p>This is the naming twin of the rule {@code SchemaLocation} already follows for pointers: the pointer is
 * the path taken ({@code /person/age}), never the leaf it resolves to ({@code /int32} in core.tn), "because
 * the leaf names a file the author didn't write and never mentions the field they can edit". The name in the
 * message takes the same route.
 *
 * <p><b>It costs nothing at read time, which is the point of doing it here.</b> Every composite reader wires
 * its children once, when the schema is compiled; this runs there and nowhere else. A position with no alias
 * gets the shared reader back unchanged -- identity-equal, no allocation -- so a schema that names no type
 * twice compiles to exactly the readers it did before. A position with one gets a copy that shares the
 * parser and the location and differs only in its name. Nothing is added to {@code TsonReadContext}, and no
 * per-read allocation changes.
 *
 * <p><b>Not every position is wired at compile time</b>, and the ones that are not are deliberately left
 * alone: a choice dispatches to a variant by name inside {@code read} ({@code VariantSchemaReader},
 * {@code NamedDispatchReader}, {@code VariantBindReader}), so renaming there would allocate per read. A
 * choice therefore still names the variant that rejected the value, which is the informative name anyway.
 */
public final class UseSite {

    private UseSite() {
    }


    /**
     * {@code reader} named {@code displayName}, or {@code reader} itself where there is no name to give or
     * nothing to give it to.
     *
     * <p><b>A {@code REFERENCE} entry is the caller.</b> Its reader is its target's, so without this a
     * message at a {@code pct}-typed position would name {@code small}. The same applies to a materialised
     * instantiation, named for its own content ({@code integer_type_10_100_786fbcfb}) where the entry
     * referring to it records the application the author wrote ({@code b<10>}), which {@code
     * EntryDisplayName} renders -- so the name comes from the entry doing the referring.
     */
    public static TsonTypeReader<?> named(TsonTypeReader<?> reader, String displayName) {
        return displayName != null && reader instanceof Renamed renameable
                ? renameable.renamed(displayName) : reader;
    }

    /**
     * A reader that leads its messages with a type name, and can be handed a different one.
     *
     * <p>Implemented by the families whose diagnostics name the type they were reading. A family that does
     * not -- or one whose name is already the use site's, like a record template's single substituted entry
     * -- simply does not implement it, and {@link #named} hands its reader straight back.
     */
    public interface Renamed {

        /** A copy of this reader naming itself {@code displayName}, sharing everything else it holds. */
        TsonTypeReader<?> renamed(String displayName);
    }
}
