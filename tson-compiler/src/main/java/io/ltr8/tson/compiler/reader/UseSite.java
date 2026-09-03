package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.atom.BytesParser;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * The reader for a child position, named the way the author wrote that position.
 *
 * <p><b>Why a position needs a name of its own.</b> [TSON-SCHEMA] §8.3 flattens a type position past a
 * {@code REFERENCE} entry, rewriting the reference to the end of its chain and recording the name the author
 * wrote as {@code @alias} on the type-ref. So a field declared {@code c: pct} over {@code pct => small}
 * arrives here as {@code type: @alias:pct small}, and the reader the resolver hands back is {@code small}'s
 * -- one reader, shared by every position that reaches that entry, and correctly named {@code small} for a
 * position that named it directly. A message from it against {@code c} then reads {@code 'small': '500' is
 * greater than the maximum 100}, naming a declaration the author did write but did not write <em>here</em>,
 * and for a chain ending in an imported type ({@code my_int => int32}) naming a file they never opened.
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
     * The already-compiled reader for {@code ref}'s target, renamed to the author's own name for this
     * position when §8.3 left one and the reader has a name to change.
     */
    static TsonTypeReader<?> reader(TypeRef ref, TsonTypeReaderResolver resolver) {
        TsonTypeReader<?> reader = respelled(resolver.resolve(ref.name()), ref);
        return named(reader, ref.annotations().value("alias", String.class).orElse(null));
    }

    /**
     * {@code reader} in the alphabet this position asks for, where it asks for one and is a {@code bytes}
     * reader -- otherwise {@code reader} itself.
     *
     * <p><b>Why a use site can carry a directive at all.</b> §8.3 flattens a position naming a {@code
     * REFERENCE} entry straight to the end of its chain, so a declaration-level {@code @bytes_encoding} on an
     * alias ({@code @bytes_encoding:HEX digest => bytes}) would be unreachable from every use of it -- while
     * the same intent written as a refinement keeps a {@code supertypes} edge and is found. The flattening
     * walk therefore carries a dropped hop's annotations onto the reference, and this is where they are read.
     *
     * <p><b>Here rather than per container.</b> Every position -- a record field, an array element, a map key
     * or value, a tuple element, a choice variant -- reaches its child reader through this method, so one
     * place serves all of them. A record field's own directive is handled a step earlier ({@code
     * BytesEncoding.fieldReader}), which has the field in hand and so can also state a directive on a field
     * whose type names no alias at all.
     */
    private static TsonTypeReader<?> respelled(TsonTypeReader<?> reader, TypeRef ref) {
        if (ref.annotations().isEmpty() || !(reader instanceof Respelled respellable)) {
            return reader;
        }
        return BytesEncoding.stated(ref.annotations())
                .<TsonTypeReader<?>>map(respellable::inEncoding)
                .orElse(reader);
    }

    /**
     * A reader that can restate itself in another RFC 4648 alphabet -- the {@code bytes} half of what a use
     * site may ask for, and the sibling of {@link Renamed}.
     *
     * <p>Two implementations for the same reason {@code Renamed} has two: a leaf reader, and the tree-mode
     * wrapper that boxes it. A reader is compiled once per entry and shared by every position that names it,
     * so a per-position alphabet cannot come from the entry -- what a position can do is ask the shared
     * reader for itself over the same constraints in a different spelling. A reader this does not apply to
     * hands itself back, exactly as {@code renamed} does.
     */
    interface Respelled {
        TsonTypeReader<?> inEncoding(BytesParser.Encoding encoding);
    }

    /**
     * {@code reader} named {@code displayName}, or {@code reader} itself where there is no name to give or
     * nothing to give it to.
     *
     * <p><b>A {@code REFERENCE} entry is the other caller</b>, and it needs this for a reason {@code @alias}
     * cannot cover. §8.3 flattens a use site past a reference and records the author's name there -- except
     * at a materialised instantiation, where the walk stops, so the use site names the instantiation and
     * carries no alias. That entry compiles to its target's reader ({@code TsonSchemaCompiler}), which is the
     * synthetic for the closed constructor form and is named for its own content ({@code
     * integer_type_10_100_786fbcfb}). The instantiation's own {@code source} is the application the author
     * wrote ({@code b<10>}), which {@code EntryDisplayName} renders -- so the name is taken from the entry
     * doing the referring rather than the entry referred to.
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
     * -- simply does not implement it, and {@link #reader} hands its reader straight back.
     */
    public interface Renamed {

        /** A copy of this reader naming itself {@code displayName}, sharing everything else it holds. */
        TsonTypeReader<?> renamed(String displayName);
    }
}
