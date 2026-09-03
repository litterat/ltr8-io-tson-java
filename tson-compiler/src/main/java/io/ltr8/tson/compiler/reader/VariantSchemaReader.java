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
final class VariantSchemaReader implements TsonTypeReader<Object>, UseSite.Renamed, UseSite.Respelled {

    private final String name;
    private final TsonTypeReader<?> ownParser;
    private final Set<String> selfNames;
    private final Set<String> subtypeNames;
    private final TsonTypeReaderResolver resolver;

    VariantSchemaReader(String name, TsonTypeReader<?> ownParser, Collection<String> subtypeNames,
                        TsonTypeReaderResolver resolver) {
        this(name, Set.of(name), ownParser, subtypeNames, resolver);
    }

    /**
     * {@code selfNames} are the written names that mean <em>this</em> type and so read through
     * {@code ownParser} rather than dispatching: the entry's own name, plus any alias that flattens to it.
     * §7.2 compares "after reference flattening of both", so an alias is not a different type to be
     * dispatched to -- resolving it would arrive back here and recurse.
     */
    VariantSchemaReader(String name, Collection<String> selfNames, TsonTypeReader<?> ownParser,
                        Collection<String> subtypeNames, TsonTypeReaderResolver resolver) {
        this.name = name;
        this.selfNames = Set.copyOf(selfNames);
        this.ownParser = ownParser;
        this.subtypeNames = Set.copyOf(subtypeNames);
        this.resolver = resolver;
    }

    /**
     * The reader this guards, and a rebuilt guard around a replacement for it. Object-binding rebinds a
     * container field's reader to the component's own Java type ({@code RecordBindReader}'s rebind step),
     * which tests the reader's concrete class -- so that step has to see through this wrapper and put it
     * back, or a guarded field silently loses its rebinding.
     */
    TsonTypeReader<?> wrapped() {
        return ownParser;
    }

    VariantSchemaReader rewrap(TsonTypeReader<?> replacement) {
        return new VariantSchemaReader(name, selfNames, replacement, subtypeNames, resolver);
    }

    /**
     * {@inheritDoc} <p>Respells the <em>wrapped</em> reader and keeps this one's dispatch untouched, exactly
     * as {@link #renamed} does: the alphabet is the leaf's business, and which subtype a tagged value
     * dispatches to is this wrapper's.
     */
    @Override
    public TsonTypeReader<?> inEncoding(io.ltr8.tson.compiler.atom.BytesParser.Encoding encoding) {
        if (!(wrapped() instanceof UseSite.Respelled respellable)) {
            return this;
        }
        TsonTypeReader<?> respelled = respellable.inEncoding(encoding);
        return respelled == wrapped() ? this : rewrap(respelled);
    }

    /**
     * {@inheritDoc} <p>Renames the <em>wrapped</em> reader and keeps this one's own {@code name} for
     * dispatch: the display name is what the position wrote (§8.3's {@code @alias}), while dispatch compares
     * against the entry's real name and its aliases. Without this the wrapper would swallow the rename and a
     * diagnostic would report the entry a use site resolved to rather than the name the author typed --
     * which is the rule {@link UseSite} exists to keep.
     */
    @Override
    public TsonTypeReader<?> renamed(String displayName) {
        if (!(ownParser instanceof UseSite.Renamed renameable)) {
            return this;
        }
        return new VariantSchemaReader(name, selfNames, renameable.renamed(displayName), subtypeNames, resolver);
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> typeRef = EventSkip.typeRefAhead(ctx);
        if (typeRef.isEmpty() || selfNames.contains(typeRef.get())) {
            return ownParser.read(ctx);
        }
        String ref = typeRef.get();
        if (!subtypeNames.contains(ref)) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, subtypeNames.isEmpty()
                            ? "'" + ref + "' is not valid at a '" + name + "' position -- a type annotation "
                                    + "must name the position's own type, which has no subtypes (§7.2)"
                            : "'" + ref + "' is not a known subtype of '" + name + "' -- expected one of "
                                    + subtypeNames,
                    subtypeNames.isEmpty() ? "'" + name + "'" : "one of " + subtypeNames, ref);
            EventSkip.dataValue(ctx); // framing included: nothing consumed it, this value being unreadable
            return null;
        }
        return resolver.resolve(ref).read(ctx);
    }
}
