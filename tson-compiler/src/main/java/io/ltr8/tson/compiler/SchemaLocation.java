package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * Where the rule a value is being read against lives in a schema -- the schema half of a {@link Diagnostic}'s
 * location, carried through a read as one value.
 *
 * <p><b>The pointer is the path taken, not the leaf reached.</b> {@code /person/age} is where an author looks:
 * read the schema document as written, {@code { person =&gt; { age: int32 } }}, and that is a literal RFC 6901
 * pointer into it. The leaf {@code int32} is <em>not</em> what belongs here -- naming it sends a reader to
 * core.tn, a file they did not write and cannot change, and never mentions the field they can. This is JSON
 * Schema 2020-12 §12.3's {@code keywordLocation}, which likewise follows the validation path rather than
 * naming the dereferenced target, and it crosses a declaration boundary the same way {@code keywordLocation}
 * crosses a {@code $ref}: a field of a record declared elsewhere still extends the path
 * ({@code /person/address/city}).
 *
 * <p><b>{@code schemaId} and {@code position} are always the same declaration's</b>, so the two can never
 * disagree about which file to open. Which declaration that is follows from how the read descended, in two
 * rules the readers apply through {@link TsonReadContext}:
 *
 * <ul>
 *   <li>A <b>record</b> ({@link TsonReadContext#inRecord}) re-anchors both to its own declaration, because it
 *   is what declares the field name the pointer now ends with -- and seeds the pointer with its own name only
 *   if nothing has yet, which is what makes the outermost record the root of the path.</li>
 *   <li>Everything else ({@link TsonReadContext#underDeclaration}) offers its own declaration only as a seed,
 *   taken when nothing encloses it. So a root-level {@code !int32} still locates itself in core.tn, while the
 *   same atom inside {@code person} leaves person.tn's anchor alone.</li>
 * </ul>
 *
 * <p>A declaration's own seed pairs {@link TsonLinkedSchema#originOf} with that declaration's position, not
 * the identity of the schema being read against, so the two stay matched wherever the declaration lives.
 *
 * <p>{@code position} is {@link Optional} and the other two are not, which is the honest asymmetry: a
 * desugar-injected entry has a name in a schema but no line in any source text, so it can be pointed at even
 * though it cannot be opened. Positions are per <em>declaration</em>, so {@code /person/age}'s position is
 * {@code person}'s own line rather than the field's; giving {@code RecordField} a position of its own is the
 * remaining granularity work ({@code BACKLOG.md}).
 */
public record SchemaLocation(String schemaId, String pointer, Optional<SourcePosition> position) {

    /** The location of one declaration, as a reader offers it: the pointer is that declaration's own name. */
    public static SchemaLocation of(String schemaId, String declaration, Optional<SourcePosition> position) {
        return new SchemaLocation(schemaId, "/" + declaration, position);
    }

    /** This location one field deeper -- {@code name} is RFC 6901-escaped, as {@link Diagnostic#path} is. */
    public SchemaLocation field(String name) {
        return new SchemaLocation(schemaId, pointer + "/" + name.replace("~", "~0").replace("/", "~1"), position);
    }

    /**
     * This location's pointer, re-anchored on {@code declaration}'s own schema and line.
     *
     * <p><b>A declaration with no line of its own contributes none</b>, leaving whatever the descent had
     * already established rather than replacing it with an absence. The entries without one are exactly
     * those no author wrote -- a sugar form lifted to an entry, a template application materialised into
     * one -- and for those the nearest useful line is the one that brought the type in: the field's own
     * record, or the alias the read entered through. Taking the absence instead would answer "which line
     * do I open" with nothing, for a document whose author has a perfectly good line to open.
     */
    public SchemaLocation anchoredOn(SchemaLocation declaration) {
        return new SchemaLocation(declaration.schemaId, pointer,
                declaration.position.isPresent() ? declaration.position : position);
    }
}
