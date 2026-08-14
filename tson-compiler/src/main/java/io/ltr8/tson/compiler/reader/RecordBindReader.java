package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.base.NumberNarrowing;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Object-binding mode's own {@code record} reader -- reads a record-shaped value into a real, bound
 * Java object via {@code descriptor}, a {@code tson-bind} {@link DataClassRecord} already resolved
 * for this record's own schema type name (resolving one is this class's caller's job, not this
 * class's).
 *
 * <p><b>{@code targetField}</b> (schema position -> bound {@code DataClassField}, or {@code null} if
 * the target class doesn't declare this schema field) is built once, right after {@link
 * RecordAbstractReader}'s own constructor returns, which is also where every inherited {@link
 * #precomputedValue} entry gets narrowed in place to its bound field's target type -- {@link
 * RecordTreeReader} leaves those unnarrowed, since a plain {@code Map} has no target type to narrow
 * toward.
 *
 * <p><b>No separate "already filled" tracker is needed at all</b> -- unlike this class's own
 * pre-streaming design, which relied on backward iteration plus an {@code arguments[...] != null}/
 * {@code boolean[] unboundFilled} check to implement "first occurrence found is genuinely the last
 * in source order." {@link RecordAbstractReader#readFields}'s own forward, single-pass, overwrite-
 * on-duplicate design (see its own Javadoc) already gets §2.5's "last value wins" for free -- this
 * class's own {@link FieldSink} just assigns into {@code arguments[target.index()]} unconditionally
 * every time {@code sink} runs, and the {@code boolean[]} {@link RecordAbstractReader#readFields}
 * itself returns is reused directly as the "does this field still need its own required-or-default
 * handling" signal for the second pass -- covering an unbound field (no {@code arguments} slot to
 * write into at all) the same way it covers a bound one, with no separate array for that case
 * anymore either.
 *
 * <p>Everything shared with {@link RecordTreeReader} -- the compiled field list, the name lookup,
 * confirming a record-shaped value, precomputing default/fixed values -- lives on {@link
 * RecordAbstractReader}; this class holds only what's genuinely different about producing a real
 * bound object instead of a plain {@code Map}: the target-field lookup, narrowing, and constructor
 * invocation.
 */
final class RecordBindReader extends RecordAbstractReader<Object> {

    private final DataClassRecord descriptor;
    private final DataClassField[] targetField;

    /**
     * The component receiving this value's own wire annotations (§3.1), if the bound class declares one.
     * Held apart from {@link #targetField}, which is keyed by <em>schema</em> field index and so has no slot
     * for a component the schema never mentions -- the carrier is filled from the framing, not from a field.
     */
    private final DataClassField annotationsCarrier;

    /**
     * What this record captures for <em>itself</em> -- {@code DISCARDED} unless it declares a carrier.
     * Distinct from {@link #annotationTypes}, which is the vocabulary in scope and is handed to nested
     * positions regardless: a record with no carrier of its own may still hold a field, or a map key, that
     * is boxed and does want them.
     */
    private final AnnotationTypes ownAnnotationTypes;
    private final AnnotationTypes annotationTypes;

    public RecordBindReader(String name, RecordBody body, DataClassRecord descriptor, TsonTypeReaderResolver resolver,
                             Optional<SourcePosition> schemaPosition, AnnotationTypes annotationTypes) {
        super(name, body, resolver, schemaPosition);
        this.descriptor = descriptor;
        this.annotationsCarrier = descriptor.annotationsCarrier().orElse(null);
        this.annotationTypes = annotationTypes;
        this.ownAnnotationTypes = annotationsCarrier == null ? AnnotationTypes.DISCARDED : annotationTypes;
        this.targetField = new DataClassField[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            CompiledField field = fields.get(i);
            DataClassField target = findTargetField(descriptor.fields(), field.schema().name());
            targetField[i] = target;
            if (target == null) {
                continue;
            }
            TsonTypeReader<?> rebound = rebindContainerIfNeeded(field, target, resolver, this.annotationTypes);
            if (target.dataClass() instanceof DataClassAnnotated boxed) {
                rebound = boxing(rebound, boxed, annotationTypes);
            }
            if (rebound != field.parser()) {
                field = new CompiledField(field.schema(), rebound);
                fields.set(i, field);
            }
            FieldState state = field.schema().state();
            if (state == FieldState.REQUIRED_DEFAULT || state == FieldState.REQUIRED_FIXED
                    || state == FieldState.OPTIONAL_FIXED) {
                precomputedValue[i] = narrow(precomputedValue[i], target.type());
            }
        }
    }

    /**
     * A field whose bound position is {@code Annotated<T>}: capture the annotations written at that position,
     * read {@code T} with the reader the schema already gave the field, and hand back both.
     *
     * <p>Wrapping the field's own reader is what keeps this out of the shared field loop -- the base reads a
     * field by calling whatever reader it holds, so replacing that reader is enough and no signature carries
     * annotations. The capture is the same hoist used everywhere else: it runs before the delegate, whose own
     * framing consumption then finds nothing left.
     */
    private static TsonTypeReader<?> boxing(TsonTypeReader<?> value, DataClassAnnotated boxed,
                                            AnnotationTypes annotationTypes) {
        Class<?> valueType = boxed.valueClass().typeClass();
        TsonTypeReader<?> narrowing = ctx -> narrow(value.read(ctx), valueType);
        return AnnotationBoxing.wrap(narrowing, boxed, annotationTypes);
    }

    /**
     * The component a schema field binds to, by name. <b>The carrier is excluded</b>: it takes no part in
     * field matching, so a schema field that happens to share its name binds nowhere rather than overwriting
     * the annotations with an authored value.
     */
    private DataClassField findTargetField(DataClassField[] classFields, String fieldName) {
        for (DataClassField classField : classFields) {
            if (classField != annotationsCarrier && classField.name().equals(fieldName)) {
                return classField;
            }
        }
        return null;
    }

    /**
     * The schema-driven child reader {@link RecordAbstractReader}'s own constructor already built
     * (via {@code resolver.resolve(field.type().name())}) has no visibility into what Java
     * collection shape the *consuming* field actually wants -- for a synthesized, materialized
     * array/map type (e.g. {@code enum}'s own {@code members: set<token>}), there's no real Java
     * class registered under that synthetic schema name at all, so {@link ArrayBindReader.Factory}/
     * {@link MapBindReader.Factory} have nothing reliable to resolve one from on their own.
     *
     * <p>{@code target.dataClass()} is already the right answer, independent of any of that --
     * reflection on the record's own real field (e.g. {@code List<String> members}) already
     * resolved a genuine {@link DataClassArray}/{@link DataClassMap} when {@code descriptor} itself
     * was built, with no dependency on the schema's own (possibly synthetic) type name. This rebuilds
     * the child reader against that target directly, reusing the already-resolved {@code body} the
     * schema-driven build produced (element/key/value readers, size constraints -- everything
     * *structural* stays schema-derived; only the target Java container type changes). Untouched for
     * every field whose target type isn't itself a collection {@link DataClass}, which is every
     * ordinary case today.
     */
    private static TsonTypeReader<?> rebindContainerIfNeeded(CompiledField field, DataClassField target,
                                                             TsonTypeReaderResolver resolver, AnnotationTypes annotationTypes) {
        TsonTypeReader<?> parser = field.parser();
        if (target.dataClass() instanceof DataClassArray targetArray && parser instanceof ArrayBindReader existing) {
            return new ArrayBindReader(field.schema().name(), existing.body, targetArray, resolver,
                    existing.schemaPosition, annotationTypes);
        }
        if (target.dataClass() instanceof DataClassMap targetMap && parser instanceof MapBindReader existing) {
            return new MapBindReader(field.schema().name(), existing.body, targetMap, resolver,
                    existing.schemaPosition, annotationTypes);
        }
        return parser;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        // Hoisted ahead of the shape check, like the tree readers: the base consumes the framing and
        // discards it, so capturing first leaves that call a no-op. Nothing to capture when the bound class
        // declares no carrier, in which case annotationTypes is DISCARDED and this consumes and drops.
        Annotations annotations = AnnotationCapture.bound(ctx, ownAnnotationTypes);
        ShapeResult shapeResult = expectRecordShape(ctx);
        if (shapeResult.shape() == Shape.MISMATCH) {
            return null;
        }
        int diagnosticsBefore = ctx.reported();
        Object[] arguments = new Object[descriptor.fields().length];
        if (annotationsCarrier != null) {
            // Always written, even when nothing was annotated: `arguments` is sized by the Java class and
            // filled only through matched schema fields, so a component the schema never mentions would
            // otherwise reach the constructor as null.
            arguments[annotationsCarrier.index()] = annotations;
        }

        for (int schemaIndex : fixedFieldIndices) {
            DataClassField target = targetField[schemaIndex];
            if (target != null) {
                arguments[target.index()] = precomputedValue[schemaIndex];
            }
        }

        FieldSink sink = (schemaIndex, decoded) -> {
            DataClassField target = targetField[schemaIndex];
            if (target != null) {
                arguments[target.index()] = narrow(decoded, target.type());
            }
        };
        boolean[] seen = switch (shapeResult.shape()) {
            case FIELDS -> readFields(ctx, sink);
            case EMPTY -> new boolean[fields.size()];
            case POSITIONAL -> readPositional(ctx, sink);
            case MISMATCH -> throw new IllegalStateException("unreachable");
        };

        TsonReadContext anchoredCtx = ctx.withPosition(shapeResult.anchor());
        for (int i = 0; i < fields.size(); i++) {
            if (isFixed(fields.get(i).schema().state()) || seen[i]) {
                continue;
            }
            DataClassField target = targetField[i];
            Object defaulted = defaultOrRequireNonFixed(i, anchoredCtx);
            if (target != null) {
                arguments[target.index()] = defaulted;
            }
        }
        validateGroups(anchoredCtx, seen);

        if (ctx.reported() > diagnosticsBefore) {
            // Collecting mode, and at least one of this record's own fields already failed -- a bound
            // Java constructor (unlike a DOM Map) can't tolerate a null argument for a primitive-typed
            // parameter, so building it now would risk a confusing secondary NPE instead of the one
            // diagnostic already reported. Nothing to construct; the caller already has what it needs.
            return null;
        }
        try {
            return descriptor.constructor().invoke(arguments);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to construct " + descriptor.typeClass() + " from '" + name
                    + "'s own compiled field values", t);
        }
    }

    /**
     * The schema-driven child-reader recursion has no knowledge of the target Java field's own
     * declared width (e.g. an unconstrained schema {@code integer} atom's natural host type is
     * {@link BigInteger}, but a bound field might be {@code Optional<Integer>}) -- reuses {@link
     * NumberNarrowing}, the same utility this codebase's atom-family readers and untyped-number
     * binding already share for exactly this. Also narrows a schema {@code enum}-typed field's raw
     * member text to the matching Java {@code enum} constant by exact name, and a schema {@code
     * uri}-typed field's real {@link java.net.URI} down to {@link String} where the target field
     * keeps it flat.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object narrow(Object raw, Class<?> target) {
        if (raw instanceof BigInteger bi && target != BigInteger.class) {
            return NumberNarrowing.narrowIntegral(bi, target);
        }
        if (raw instanceof BigDecimal bd && target != BigDecimal.class) {
            return NumberNarrowing.narrowDecimal(bd, target);
        }
        if (raw instanceof String s && target.isEnum()) {
            return Enum.valueOf((Class<Enum>) target, s);
        }
        if (raw instanceof java.net.URI uri && target == String.class) {
            return uri.toString();
        }
        return raw;
    }

    /**
     * Validates what {@link #RecordBindReader} needs before ever constructing one, and owns the
     * decision (and the {@link DataBindContext} that decision needs) about whether a record-shaped
     * declaration also needs subtype dispatch -- keeping that concern local to this factory rather
     * than a generic, orchestrator-level step is what keeps {@link DataBindContext} itself out of
     * the orchestrator entirely; the orchestrator only ever calls {@link #create}, whose signature
     * carries no {@link DataBindContext} at all. {@code context} is fixed once, at this factory's
     * own construction, not threaded through {@link #create} -- one {@link DataBindContext} governs
     * every entry a given compiled schema binds, the same way a {@link Factory} instance gets
     * created once and reused across a whole compile, not once per entry.
     *
     * <p><b>Three real shapes, not two.</b> When {@code typeDefinition.subtypes()} is empty, {@code
     * name} must resolve to a {@link DataClassRecord} -- the ordinary case, no dispatch wrapper at
     * all. When it's non-empty, two further shapes exist, told apart by what {@code name} itself
     * resolves to: a {@link DataClassUnion} is the real fixture's own shape for a pure marker root
     * ({@code top}/{@code atom}/{@code product}/{@code sum}: an empty record body with a huge subtype
     * list, bound to a Java sealed interface with nothing instantiable of its own) -- {@code
     * ownParser} there is a stand-in that unconditionally throws if ever reached, since there's no
     * real Java object "just {@code top}" could construct, so every genuine value at such a position
     * must carry an explicit subtype type-ref. A {@link DataClassRecord} instead -- {@code text_type}
     * is the one real fixture case, directly instantiable as a plain {@link
     * io.ltr8.tson.schema.meta.TextType} *and* composed on top of by {@code uri_type}/{@code
     * regex_type}/{@code email_type} -- means {@code ownParser} is a real, reachable {@link
     * RecordBindReader} for the declaration's own body; dispatch to a named subtype is bounded by the
     * schema's own {@code subtypes()} list, not by any Java type, via {@link VariantSchemaReader} (see
     * that class's own Javadoc for why the same dispatcher DOM mode always uses fits this case too).
     */
    public static final class Factory implements ValueReaderFactory {

        private final DataBindContext context;

        public Factory(DataBindContext context) {
            this.context = context;
        }

        @Override
        public TsonTypeReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderContext context) {
            TsonTypeReaderResolver resolver = context.readers();
            if (!(typeDefinition.body() instanceof RecordBody body)) {
                throw new IllegalArgumentException(
                        "'" + name + "' is not record-shaped: " + typeDefinition.body());
            }
            DataClass dataClass = descriptorFor(name);

            if (typeDefinition.subtypes().isEmpty()) {
                return new RecordBindReader(name, body, requireRecord(name, dataClass), resolver,
                        typeDefinition.position(), AnnotationTypes.of(context));
            }

            if (dataClass instanceof DataClassUnion union) {
                TsonTypeReader<?> noOwnData = ctx -> {
                    ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF,
                            "'" + name + "' has no data of its own to bind -- provide an explicit type annotation "
                                    + "(!typeName) naming one of its subtypes " + typeDefinition.subtypes(),
                            "an explicit type annotation naming one of " + typeDefinition.subtypes(), "(none)");
                    return null;
                };
                return new VariantBindReader(name, noOwnData, union, resolver);
            }

            if (dataClass instanceof DataClassRecord record) {
                RecordBindReader ownParser = new RecordBindReader(name, body, record, resolver,
                        typeDefinition.position(), AnnotationTypes.of(context));
                return new VariantSchemaReader(name, ownParser, typeDefinition.subtypes(), resolver,
                        AnnotationTypes.DISCARDED);
            }

            throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                    + ", which is neither record- nor union-shaped -- can't bind '" + name + "' as either");
        }

        private DataClass descriptorFor(String name) {
            try {
                return context.getDescriptor(name);
            } catch (DataBindException e) {
                throw new IllegalStateException("no bound Java class for '" + name + "'", e);
            }
        }

        private static DataClassRecord requireRecord(String name, DataClass dataClass) {
            if (!(dataClass instanceof DataClassRecord descriptor)) {
                throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                        + ", which isn't record-shaped -- can't bind '" + name + "' as one");
            }
            return descriptor;
        }
    }
}
