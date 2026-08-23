package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Unbound;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonBindMismatchException;
import io.ltr8.tson.compiler.TsonMissingBindingException;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.atom.RawTokenParser;
import io.ltr8.tson.compiler.base.NumberNarrowing;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
     * Whether a schema field with nowhere to go is an error. Fixed when the reader is built, because that is
     * when the question is answerable and when the answer is cheap to act on -- see {@link
     * TsonBindMismatchException}. The lenient reading still reports what it drops (below); what it does not
     * do is refuse to start.
     */
    private final boolean strict;

    /**
     * The component receiving this value's own wire annotations (§3.1), if the bound class declares one.
     * Held apart from {@link #targetField}, which is keyed by <em>schema</em> field index and so has no slot
     * for a component the schema never mentions -- the carrier is filled from the framing, not from a field.
     */
    private final DataClassField annotationsCarrier;

    /**
     * What this record captures for <em>itself</em> -- discarding unless it declares a carrier, which drops
     * what it reads but still checks it (see {@link AnnotationTypes#discarding()}).
     * Distinct from {@link #annotationTypes}, which is the vocabulary in scope and is handed to nested
     * positions regardless: a record with no carrier of its own may still hold a field, or a map key, that
     * is boxed and does want them.
     */
    private final AnnotationTypes ownAnnotationTypes;
    private final AnnotationTypes annotationTypes;

    public RecordBindReader(String name, String displayName, RecordBody body, DataClassRecord descriptor,
                             TsonTypeReaderResolver resolver, SchemaLocation schemaLocation,
                             AnnotationTypes annotationTypes, boolean strict) {
        super(name, displayName, body, tokenAware(name, descriptor.fields(),
                descriptor.annotationsCarrier().orElse(null), resolver, schemaLocation), schemaLocation);
        this.descriptor = descriptor;
        this.annotationsCarrier = descriptor.annotationsCarrier().orElse(null);
        this.annotationTypes = annotationTypes;
        this.ownAnnotationTypes = annotationsCarrier == null ? annotationTypes.discarding() : annotationTypes;
        this.targetField = new DataClassField[fields.size()];
        this.strict = strict;
        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            CompiledField field = fields.get(i);
            DataClassField target = findTargetField(descriptor.fields(), field.schema().name());
            targetField[i] = target;
            if (target == null) {
                // The one exemption is FIXED (either state): the schema settles the value, so a component
                // would hold a constant the schema already knows. It is what lets this be strict at all --
                // 21 of the mismatches in this library's own bundled binding are FIXED fields
                // (access_pattern, size_type, an atom's spec), every one by design.
                //
                // OPTIONAL is deliberately NOT exempt, though it is the tempting case: nothing is lost until
                // a document writes one, so the mismatch could be left to the read that does. That is the
                // worse trade -- an optional field is exactly the one that works in development and fails
                // the first time a caller sends it, which is the bug this check exists to prevent. One rule
                // for every field beats two that differ on when the developer finds out.
                if (strict && !isFixed(field.schema().state())) {
                    mismatches.add("no component for field '" + field.schema().name() + "'");
                }
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
        if (strict) {
            Set<String> unbound = unboundComponents(descriptor.typeClass());
            for (DataClassField classField : descriptor.fields()) {
                if (classField != annotationsCarrier && !boundByAField(classField)
                        && !unbound.contains(classField.name())) {
                    mismatches.add("component '" + classField.name() + "' is filled by no field, so it reaches "
                            + "the constructor as null on every document -- annotate it @Unbound if the class "
                            + "means to own it");
                }
            }
            if (!mismatches.isEmpty()) {
                throw new TsonBindMismatchException("'" + displayName + "' and "
                        + descriptor.typeClass().getName() + " do not agree: " + String.join("; ", mismatches)
                        + ". Bind the class the schema describes, or read leniently "
                        + "(TsonConfig.lenientBinding) if dropping this is deliberate");
            }
        }
    }

    /** Whether any schema field of this type binds to {@code classField}. */
    private boolean boundByAField(DataClassField classField) {
        for (DataClassField bound : targetField) {
            if (bound == classField) {
                return true;
            }
        }
        return false;
    }

    /**
     * The components a class marked as its own ({@code @Unbound}), under the same names {@link
     * DataClassField#name()} uses -- so an {@code @Field}-renamed component is recognised by the name
     * binding actually matches on.
     *
     * <p>Read here by reflection rather than carried on {@link DataClassField}, because it answers a
     * <em>TSON</em> question -- "is a schema field expected to fill this?" -- that {@code tson-bind}'s
     * generic descriptor has no notion of: the descriptor knows components, not schemas. Both the record
     * component and its accessor are consulted, so the marker works wherever a class can put it.
     */
    private static Set<String> unboundComponents(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            return Set.of();
        }
        Set<String> unbound = new LinkedHashSet<>();
        for (RecordComponent component : components) {
            if (!component.isAnnotationPresent(Unbound.class)
                    && !component.getAccessor().isAnnotationPresent(Unbound.class)) {
                continue;
            }
            io.ltr8.annotation.Field renamed = component.getAnnotation(io.ltr8.annotation.Field.class);
            if (renamed == null) {
                renamed = component.getAccessor().getAnnotation(io.ltr8.annotation.Field.class);
            }
            unbound.add(renamed != null && !renamed.value().isEmpty() ? renamed.value() : component.getName());
        }
        return unbound;
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
        return findTargetField(classFields, annotationsCarrier, fieldName);
    }

    /** {@link #findTargetField(DataClassField[], String)} before an instance exists -- see {@link #tokenAware}. */
    private static DataClassField findTargetField(DataClassField[] classFields, DataClassField carrier,
                                                   String fieldName) {
        for (DataClassField classField : classFields) {
            if (classField != carrier && classField.name().equals(fieldName)) {
                return classField;
            }
        }
        return null;
    }

    /**
     * Field readers that give a slot whose bound component is a {@link io.ltr8.tson.schema.meta.Token} the
     * token itself rather than the value it denotes.
     *
     * <p><b>Why a slot can want the raw token.</b> The kernel types every such slot {@code value}, whose
     * reader decodes ([TSON-DATA] §4) -- {@code 3} to an integer, {@code "3"} to a string. A component
     * declared {@code Token} wants what was written: [TSON-SCHEMA] §5.10 calls a type argument's literal a
     * bare token rather than the value it denotes, and {@code record_field}'s own fixed and default values
     * are compared against what a document writes. A decoded host object cannot fill either.
     * {@code SPEC-FEEDBACK.md} #54 records what keying identity on the spelling costs and puts the
     * underlying disagreement -- bare token in the prose, {@code value} in the kernel -- to the spec.
     *
     * <p>The choice has to be made before the read, and it is made per field, so two slots of one schema
     * type that bind different components each get what they want.
     */
    private static FieldReaders tokenAware(String name, DataClassField[] classFields, DataClassField carrier,
                                            TsonTypeReaderResolver resolver, SchemaLocation location) {
        return field -> {
            DataClassField target = findTargetField(classFields, carrier, field.name());
            return target != null && target.type() == io.ltr8.tson.schema.meta.Token.class
                    ? AtomTypeReader.of(name, RawTokenParser.INSTANCE, location)
                    : resolver.resolve(field.type().name());
        };
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
        // The field's own name serves as both, here and only here: this rebuild deliberately drops the
        // schema type name (see above), and a field name is already the author's own word for the value --
        // there is nothing a derived display name would add over it.
        if (target.dataClass() instanceof DataClassArray targetArray && parser instanceof ArrayBindReader existing) {
            return new ArrayBindReader(field.schema().name(), field.schema().name(), existing.body, targetArray,
                    resolver, existing.schemaLocation, annotationTypes);
        }
        if (target.dataClass() instanceof DataClassMap targetMap && parser instanceof MapBindReader existing) {
            return new MapBindReader(field.schema().name(), field.schema().name(), existing.body, targetMap,
                    resolver, existing.schemaLocation, annotationTypes);
        }
        return parser;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.inRecord(schemaLocation);
        // Hoisted ahead of the shape check, like the tree readers: the base consumes the framing and
        // discards it, so capturing first leaves that call a no-op. Nothing to *keep* when the bound class
        // declares no carrier -- ownAnnotationTypes is then discarding, which still checks what it drops.
        Annotations annotations = AnnotationCapture.bound(ctx, ownAnnotationTypes);
        ShapeResult shapeResult = expectRecordShape(ctx);
        if (shapeResult.shape() == Shape.MISMATCH) {
            return null;
        }
        int mark = ConstructionGuard.mark(ctx);
        Object[] arguments = new Object[descriptor.fields().length];
        if (annotationsCarrier != null) {
            // Always written, even when nothing was annotated: `arguments` is sized by the Java class and
            // filled only through matched schema fields, so a component the schema never mentions would
            // otherwise reach the constructor as null.
            arguments[annotationsCarrier.index()] = annotations;
        }

        TsonReadContext fieldCtx = ctx;
        FieldSink sink = (schemaIndex, decoded) -> {
            DataClassField target = targetField[schemaIndex];
            if (target != null) {
                arguments[target.index()] = narrow(decoded, target.type());
                return;
            }
            // Unreachable under a strict reader: a field with no component fails when the reader is built,
            // so nothing gets here to drop. A lenient one asked for exactly this, and asked in the one place
            // where the intention is written down rather than inferred from silence.
        };
        boolean[] seen = switch (shapeResult.shape()) {
            case FIELDS -> readFields(ctx, sink);
            case EMPTY -> new boolean[fields.size()];
            case POSITIONAL -> readPositional(ctx, sink);
            case MISMATCH -> throw new IllegalStateException("unreachable");
        };

        TsonReadContext anchoredCtx = ctx.withPosition(shapeResult.anchor());
        for (int i = 0; i < fields.size(); i++) {
            if (seen[i]) {
                continue;
            }
            DataClassField target = targetField[i];
            Object defaulted = valueForAbsentField(i, anchoredCtx);
            if (target != null) {
                arguments[target.index()] = defaulted;
            }
        }
        validateGroups(anchoredCtx, seen);

        if (ConstructionGuard.abandoned(ctx, mark)) {
            return null; // see ConstructionGuard: bind mode never builds out of a document already reported
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

        /** Whether a schema field with nowhere to go fails the compile -- see {@link TsonBindMismatchException}. */
        private final boolean strict;

        public Factory(DataBindContext context, boolean strict) {
            this.context = context;
            this.strict = strict;
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
                Map<String, DataClassRecord> labelled = labelledChoice(body, dataClass);
                if (labelled != null) {
                    SchemaLocation location = context.locationOf(name, typeDefinition);
                    return new GroupUnionBindReader(name, EntryDisplayName.of(name, typeDefinition), body,
                            labelled, resolver, location);
                }
                return new RecordBindReader(name, EntryDisplayName.of(name, typeDefinition), body,
                        requireRecord(name, dataClass), resolver,
                        context.locationOf(name, typeDefinition), AnnotationTypes.of(context), strict);
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
                RecordBindReader ownParser = new RecordBindReader(name, EntryDisplayName.of(name, typeDefinition),
                        body, record, resolver,
                        context.locationOf(name, typeDefinition), AnnotationTypes.of(context), strict);
                return new VariantSchemaReader(name, ownParser, typeDefinition.subtypes(), resolver);
            }

            throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                    + ", which is neither record- nor union-shaped -- can't bind '" + name + "' as either");
        }

        /**
         * The union members of an <b>untagged labelled choice</b>, keyed by the schema field each one
         * carries, or {@code null} when this is not one -- see {@link GroupUnionBindReader}.
         *
         * <p>Three things must line up, and all three are checked rather than assumed: the Java target is a
         * sealed union, the schema body is one REQUIRED group covering every field it declares, and each
         * member carries exactly one component whose wire name is one of those fields. A near-miss falls
         * through to the ordinary record path, where {@link #requireRecord} reports it -- guessing at a
         * partial match would bind a member to a field it does not carry.
         */
        private Map<String, DataClassRecord> labelledChoice(RecordBody body, DataClass dataClass) {
            if (!(dataClass instanceof DataClassUnion union) || body.groups().size() != 1) {
                return null;
            }
            FieldGroup group = body.groups().get(0);
            if (group.state() != ElementState.REQUIRED
                    || group.members().size() != body.fields().size()
                    || union.memberTypes().length != body.fields().size()) {
                return null;
            }
            Map<String, DataClassRecord> byField = new LinkedHashMap<>();
            for (Class<?> memberType : union.memberTypes()) {
                DataClass member;
                try {
                    member = context.getDescriptor(memberType);
                } catch (DataBindException e) {
                    return null;
                }
                if (!(member instanceof DataClassRecord record)) {
                    return null;
                }
                String label = GroupUnionBindReader.labelOf(record);
                if (label == null || !group.members().contains(label)) {
                    return null;
                }
                byField.put(label, record);
            }
            return byField.size() == body.fields().size() ? byField : null;
        }

        /**
         * The class this schema type binds to.
         *
         * <p><b>A missing one is a misconfiguration, not a gap.</b> It used to raise an {@code
         * IllegalStateException}, which the compile turned into an {@code ErrorReader} and the first read of
         * that type into "no usable compiled reader" -- a library-gap shape, for a caller who simply never
         * mapped the type. That reading travels: a downstream service mapped it to a 501. It is the same
         * disagreement {@link TsonBindMismatchException} already covers from the other side (a class that
         * exists and does not fit), so it is reported the same way and at the same moment -- when the schema
         * is compiled in bind mode, naming the type nothing resolves.
         */
        private DataClass descriptorFor(String name) {
            try {
                return context.getDescriptor(name);
            } catch (DataBindException e) {
                throw new TsonMissingBindingException("no bound Java class for '" + name + "': nothing in this "
                        + "bind context resolves that schema type name. Map it (TsonConfig.bindings) or give "
                        + "the context a DataNameBinder that can find it -- " + e.getMessage());
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
