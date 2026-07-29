package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.ast.DataValue;

import java.util.Collection;
import java.util.Set;

/**
 * Record-subtype dispatcher bounded by the schema's own {@code TypeDefinition.subtypes()} (a plain
 * {@code Set<String>} of schema names) rather than any Java type -- unlike {@link VariantBindReader}'s
 * {@code DataClassUnion}-bounded version, there's no Java union to validate membership against here,
 * only the schema's own subtype list. Used wherever a real, reachable "own data" reader exists for
 * the declaration itself: unconditionally by {@link RecordDomReader.Factory} (DOM mode has no "pure
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
 */
final class VariantSchemaReader implements TsonValueReader<Object> {

    private final String name;
    private final TsonValueReader<?> ownParser;
    private final Set<String> subtypeNames;
    private final TsonValueReaderResolver resolver;

    VariantSchemaReader(String name, TsonValueReader<?> ownParser, Collection<String> subtypeNames,
                         TsonValueReaderResolver resolver) {
        this.name = name;
        this.ownParser = ownParser;
        this.subtypeNames = Set.copyOf(subtypeNames);
        this.resolver = resolver;
    }

    @Override
    public Object read(DataValue value) {
        if (value == null || value.typeRef().isEmpty() || value.typeRef().get().equals(name)) {
            return ownParser.read(value);
        }
        String typeRef = value.typeRef().get();
        if (!subtypeNames.contains(typeRef)) {
            throw new IllegalArgumentException("'" + typeRef + "' is not a known subtype of '" + name
                    + "' -- expected one of " + subtypeNames);
        }
        return resolver.resolve(typeRef).read(value);
    }
}
