package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.atom.BytesParser;
import io.ltr8.tson.schema.meta.TypeDefinition;

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
 * is the two seams a compiled reader offers a caller holding one, {@link Renamed} and {@link Respelled}, and
 * the two helpers the reference compile applies them through.
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
     * {@code target} respelled by the {@code @bytes_encoding} that {@code name}'s own declaration states, at
     * either of §6's declaration positions -- the schema-map key first, then the {@code TypeDefinition}.
     *
     * <p>Called where a {@code REFERENCE} entry is compiled, which is where a chain is collapsed: a directive
     * on an alias ({@code @bytes_encoding:HEX digest => bytes}) then directs every use of that alias without
     * the alias having to be a refinement to be heard. <b>This entry only, never its chain</b> -- a chain
     * compiles innermost-first, so each hop respells what the hop below produced and the outermost, the one
     * nearest the use site, is left standing. Nearest-first with no walk and no ordering rule of its own.
     */
    public static TsonTypeReader<?> respelledByDeclaration(TsonTypeReader<?> target, String name,
            TypeDefinition definition, ValueReaderContext context) {
        if (!(target instanceof Respelled respellable)) {
            return target;
        }
        return BytesEncoding.stated(context.schema().entries().getAnnotations(name))
                .or(() -> BytesEncoding.stated(definition.annotations()))
                .<TsonTypeReader<?>>map(respellable::inEncoding)
                .orElse(target);
    }

    /**
     * A reader that can restate itself in another RFC 4648 alphabet -- the {@code bytes} half of what a use
     * site may ask for, and the sibling of {@link Renamed}.
     *
     * <p>Three implementations, for the same reason {@code Renamed} has three: the leaf reader, tree mode's
     * boxing wrapper, and the subtype dispatcher, each delegating through to the one below. An alias entry
     * that states a directive asks its target's reader for itself over the same constraints in a different
     * spelling ({@code TsonSchemaCompiler}'s reference branch), and a reader this does not apply to hands
     * itself back, exactly as {@code renamed} does. <b>Nearest-first falls out of the recursion</b>: a chain
     * compiles innermost-first, so each hop respells what the hop below it produced and the outermost
     * directive -- the one nearest the use site -- is the one left standing.
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
     * -- simply does not implement it, and {@link #named} hands its reader straight back.
     */
    public interface Renamed {

        /** A copy of this reader naming itself {@code displayName}, sharing everything else it holds. */
        TsonTypeReader<?> renamed(String displayName);
    }
}
