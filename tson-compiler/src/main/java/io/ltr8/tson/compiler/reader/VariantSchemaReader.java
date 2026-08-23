package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonValue;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Record-subtype dispatcher bounded by the schema's own {@code TypeDefinition.subtypes()} (a plain
 * {@code Set<String>} of schema names) rather than any Java type -- unlike {@link VariantBindReader}'s
 * {@code DataClassUnion}-bounded version, there's no Java union to validate membership against here,
 * only the schema's own subtype list. Used wherever a real, reachable "own data" reader exists for
 * the declaration itself: unconditionally by {@link RecordTreeReader.Factory} (DOM mode has no "pure
 * marker interface, nothing to construct" case at all -- an empty record body, e.g. {@code top =>
 * top & {}}, reads to a perfectly ordinary, if empty, {@code Map<String, Object>}), and by {@link
 * RecordBindReader.Factory} specifically when the declaration's own bound Java class is a real
 * {@code DataClassRecord} rather than a marker {@code DataClassUnion} -- {@code text_type} is the
 * one real fixture case (composing with {@code uri_type}/{@code regex_type}/{@code email_type} on
 * top of it, but itself directly instantiable as a plain {@link io.ltr8.tson.schema.meta.TextType}).
 * A pure marker root with no data of its own (bound to a Java sealed interface, e.g. {@code
 * top}/{@code atom}) still goes through {@link VariantBindReader} instead, whose {@code ownParser} is
 * a stand-in that always throws.
 *
 * <p>Preserves {@code reader.VariantParser}'s own "own body is a valid reading too" rule -- a
 * value with no type-ref, or one naming this declaration itself, reads via {@code ownParser}
 * unconditionally; only a value naming something else dispatches by resolving that name against
 * {@code resolver}, the same compiled-schema path every other dispatch in this codebase uses (e.g.
 * an explicit {@code !uri_type {...}} value at a {@code text_type}-typed position dispatches straight
 * to {@code uri_type}'s own already-compiled reader, producing a real {@code UriType}, not a
 * {@code TextType}).
 *
 * <p><b>The type-ref driving this decision is read without being consumed</b> ({@link
 * EventSkip#typeRefAhead}), so whichever reader is chosen is handed the whole data-value -- its annotations,
 * its type-ref and its core-value -- exactly as it would be if nothing had dispatched to it. That is what
 * lets annotations written on a dispatched value survive: consuming the framing to reach the type-ref, which
 * is what this used to do, left the reader that builds the value unable to see them, and re-attaching
 * afterwards could only put them back on a {@code TsonValue}.
 */
final class VariantSchemaReader implements TsonTypeReader<Object> {

    private final String name;
    private final TsonTypeReader<?> ownParser;
    private final Set<String> subtypeNames;
    private final TsonTypeReaderResolver resolver;

    VariantSchemaReader(String name, TsonTypeReader<?> ownParser, Collection<String> subtypeNames,
                        TsonTypeReaderResolver resolver) {
        this.name = name;
        this.ownParser = ownParser;
        this.subtypeNames = Set.copyOf(subtypeNames);
        this.resolver = resolver;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> typeRef = EventSkip.typeRefAhead(ctx);
        if (typeRef.isEmpty() || typeRef.get().equals(name)) {
            return ownParser.read(ctx);
        }
        String ref = typeRef.get();
        if (!subtypeNames.contains(ref)) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, "'" + ref + "' is not a known subtype of '" + name
                            + "' -- expected one of " + subtypeNames,
                    "one of " + subtypeNames, ref);
            EventSkip.dataValue(ctx); // framing included: nothing consumed it, this value being unreadable
            return null;
        }
        return resolver.resolve(ref).read(ctx);
    }
}
