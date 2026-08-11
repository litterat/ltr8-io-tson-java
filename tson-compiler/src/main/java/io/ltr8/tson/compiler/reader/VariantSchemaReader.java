package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonNode;

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
 * <p>The type-ref driving this decision is consumed here, via {@link EventSkip#annotationsAndTypeRef}
 * -- {@code ownParser} still calls it again on delegation (every reader does, as its own first step),
 * which is a safe no-op once nothing's left to consume.
 */
final class VariantSchemaReader implements TsonValueReader<Object> {

    private final String name;
    private final TsonValueReader<?> ownParser;
    private final Set<String> subtypeNames;
    private final TsonValueReaderResolver resolver;
    private final AnnotationTypes annotationTypes;

    VariantSchemaReader(String name, TsonValueReader<?> ownParser, Collection<String> subtypeNames,
                         TsonValueReaderResolver resolver, AnnotationTypes annotationTypes) {
        this.name = name;
        this.ownParser = ownParser;
        this.subtypeNames = Set.copyOf(subtypeNames);
        this.resolver = resolver;
        this.annotationTypes = annotationTypes;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        Optional<String> typeRef = EventSkip.typeRef(ctx);
        if (typeRef.isEmpty() || typeRef.get().equals(name)) {
            return reattach(ownParser.read(ctx), annotations);
        }
        String ref = typeRef.get();
        if (!subtypeNames.contains(ref)) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, "'" + ref + "' is not a known subtype of '" + name
                            + "' -- expected one of " + subtypeNames,
                    "one of " + subtypeNames, ref);
            EventSkip.coreValue(ctx);
            return null;
        }
        return reattach(resolver.resolve(ref).read(ctx), annotations);
    }

    /**
     * The annotations written on the dispatched value, re-attached to whatever the chosen reader built.
     * Dispatch has to consume them to reach the type-ref it dispatches on ({@code data-value = *annotation
     * [type-ref] core-value}), so the reader that actually builds the node never sees them; a non-node result
     * (object-binding mode) has nowhere to carry them and is returned untouched.
     */
    private static Object reattach(Object value, List<TsonAnnotation> annotations) {
        return value instanceof TsonNode node ? node.withAnnotations(annotations) : value;
    }
}
