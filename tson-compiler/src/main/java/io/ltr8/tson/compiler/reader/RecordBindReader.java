package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.diagnostics.BindingDiagnostics;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics.Handed;
import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Unbound;
import io.ltr8.bind.*;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.atom.RawTokenParser;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.*;

import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * Object-binding mode's own {@code record} reader -- reads a record-shaped value into a real, bound
 * Java object via {@code descriptor}, a {@code ltr8-bind} {@link DataClassRecord} already resolved
 * for this record's own schema type name (resolving one is this class's caller's job, not this
 * class's).
 *
 * <p><b>{@code targetField}</b> (schema position -> bound {@code DataClassField}, or {@code null} if
 * the target class doesn't declare this schema field) is built once, right after {@link
 * RecordAbstractReader}'s own constructor returns, which is also where every inherited {@link
 * #precomputedValue} entry is decoded again through the field's now-bound parser -- {@link
 * RecordTreeReader} leaves those as that class computed them, a plain {@code Map} having no target to
 * decode toward.
 *
 * <p><b>No separate "already filled" tracker is needed at all.</b>
 * {@link RecordAbstractReader#readFields}'s forward, single-pass, overwrite-on-duplicate design (see its own
 * Javadoc) gets §2.5's "last value wins" for free, so this class's {@link FieldSink} assigns into
 * {@code arguments[target.index()]} unconditionally every time {@code sink} runs, and the {@code boolean[]}
 * {@link RecordAbstractReader#readFields} returns is reused directly as the "does this field still need its
 * own required-or-default handling" signal for the second pass -- covering an unbound field (no
 * {@code arguments} slot to write into at all) the same way it covers a bound one, with no separate array
 * for that case.
 *
 * <p>Four disagreements between the schema and the class are refused here
 * ({@code BindMismatchException}): a non-FIXED field with no component, a component no field fills, an
 * atom field whose component binds structurally, and an atom field whose family cannot produce the class
 * its component binds.
 *
 * <p>Everything shared with {@link RecordTreeReader} -- the compiled field list, the name lookup,
 * confirming a record-shaped value, precomputing default/fixed values -- lives on {@link
 * RecordAbstractReader}; this class holds only what's genuinely different about producing a real
 * bound object instead of a plain {@code Map}: the target-field lookup, binding each field's atom to what
 * its component holds, and constructor invocation.
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
     * What this record captures for <em>itself</em> -- discarding unless it declares a carrier, which drops
     * what it reads but still checks it (see {@link AnnotationTypes#discarding()}).
     * Distinct from {@link #annotationTypes}, which is the vocabulary in scope and is handed to nested
     * positions regardless: a record with no carrier of its own may still hold a field, or a map key, that
     * is boxed and does want them.
     */
    private final AnnotationTypes ownAnnotationTypes;
    private final AnnotationTypes annotationTypes;

    public RecordBindReader(String name, String displayName, RecordBody body, DataClassRecord descriptor,
                             TsonTypeReaderResolver resolver, ValueReaderContext context,
                             SchemaLocation schemaLocation,
                             AnnotationTypes annotationTypes) {
        super(name, displayName, body, tokenAware(name, descriptor.fields(),
                descriptor.annotationsCarrier().orElse(null), resolver, context, schemaLocation), schemaLocation,
                fieldNameRule(name, body, context));
        this.descriptor = descriptor;
        this.annotationsCarrier = descriptor.annotationsCarrier().orElse(null);
        this.annotationTypes = annotationTypes;
        this.ownAnnotationTypes = annotationsCarrier == null ? annotationTypes.discarding() : annotationTypes;
        this.targetField = new DataClassField[fields.size()];
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
                if (field.schema().role() != FieldRole.FIXED) {
                    mismatches.add("no component for field '" + field.schema().name() + "'");
                }
                continue;
            }
            String deliversNull = deliversNull(field.schema(), omitted(i));
            if (deliversNull != null && target.dataClass().typeClass().isPrimitive()) {
                mismatches.add("field '" + field.schema().name() + "' " + deliversNull + ", and component '"
                        + target.name() + "' binds " + target.dataClass().typeClass().getName()
                        + ", which has no null to hold it");
            }
            // The component is the target at every depth below the field too (BindTargets), and the bridge and box
            // are applied here because this reader builds its constructor arguments itself -- ltr8-bind's binder,
            // which collects a record's arguments through their bridges, is the step a schema-driven read replaces.
            TsonTypeReader<?> rebound = BindTargets.to(field.parser(), target.dataClass(),
                    "field '" + field.schema().name() + "'", "component '" + target.name() + "'",
                    new BindTargets.Site(field.schema().name(), resolver, this.annotationTypes, mismatches), true);
            if (rebound != field.parser()) {
                field = new CompiledField(field.schema(), rebound);
                fields.set(i, field);
            }
            if (field.schema().value().isPresent()) {
                precomputedValue[i] = readSchemaDefault(fields.get(i));
            }
        }
        {
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
                throw new BindMismatchException("'" + displayName + "' and "
                        + descriptor.typeClass().getName() + " do not agree: " + String.join("; ", mismatches)
                        + ". Bind the class the schema describes -- a class that means to read one version of a "
                        + "schema while another is current declares a @Profile constructor for it, which says "
                        + "which fields it takes rather than dropping whatever it does not name");
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
     * <em>TSON</em> question -- "is a schema field expected to fill this?" -- that {@code ltr8-bind}'s
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
     * are compared against what a document writes. A decoded host object cannot fill either, and §8.2 wants
     * the spelling kept: a value argument is "recorded as written" and compared as the value it denotes.
     *
     * <p>The choice has to be made before the read, and it is made per field, so two slots of one schema
     * type that bind different components each get what they want.
     */
    private static FieldReaders tokenAware(String name, DataClassField[] classFields, DataClassField carrier,
                                            TsonTypeReaderResolver resolver, ValueReaderContext context,
                                            SchemaLocation location) {
        return field -> {
            DataClassField target = findTargetField(classFields, carrier, field.name());
            if (target != null && target.type() == io.ltr8.tson.schema.meta.Token.class) {
                return AtomTypeReader.of(name, RawTokenParser.INSTANCE, location);
            }
            // Otherwise by the field's declared type, as tree mode does -- see FieldReaders.byType.
            return resolver.resolve(field.type().name());
        };
    }

    /**
     * {@code null}, which is all a bound object has: a Java component holds no third state between "set to
     * nothing" and "never set", so a field written {@code _} and one never written arrive alike. Tree mode
     * keeps [TSON-DATA] §2.9's distinction ({@code RecordTreeReader}); here it has nowhere to live, and
     * inventing somewhere would be a change to what a consumer's own class means.
     */
    @Override
    Object statedVoidValue() {
        return null;
    }

    /**
     * How {@code field} can deliver no value to its component, or {@code null} where it cannot: a voidable field
     * whose value may be void, and one that yields nothing when the document leaves it out -- optional with no
     * default, or a field group's member. Either reaches the constructor as {@code null}, which a primitive
     * component cannot take, and both are known before any document -- so the class is refused at compile rather
     * than at the read that writes one.
     */
    private static String deliversNull(RecordField field, RecordField.Omitted omitted) {
        if (field.voidable()) {
            return "is voidable";
        }
        return omitted == RecordField.Omitted.NOTHING ? "may be left out with nothing injected" : null;
    }

    /** The class this record builds, which a field holding it must be able to hold ({@link BindTargets}). */
    Class<?> boundClass() {
        return descriptor.typeClass();
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
                arguments[target.index()] = decoded;
                return;
            }
            // Only a FIXED field gets here: any other field with no component fails when the reader is built.
            // A stated FIXED value has already been checked against the schema's own, which settles it, so
            // there is nothing to keep.
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
            Object defaulted = valueForMissingField(i, anchoredCtx);
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
        } catch (Throwable t) {
            // The constructor is the class's own code: what it throws is its refusal (BindingDiagnostics).
            ctx.report(BindingDiagnostics.rejectedUnderSchema(descriptor.typeClass(), Handed.VALUE, t));
            return null;
        }
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
            DataClass dataClass = descriptorFor(name, typeDefinition, context);

            if (typeDefinition.subtypes().isEmpty()) {
                Map<String, DataClassRecord> labelled = labelledChoice(body, dataClass);
                if (labelled != null) {
                    SchemaLocation location = context.locationOf(name, typeDefinition);
                    return new GroupUnionBindReader(name, EntryDisplayName.of(name, typeDefinition), body,
                            labelled, resolver, location, fieldNameRule(name, body, context));
                }
                return new RecordBindReader(name, EntryDisplayName.of(name, typeDefinition), body,
                        requireRecord(name, dataClass), resolver, context,
                        context.locationOf(name, typeDefinition), AnnotationTypes.of(context));
            }

            if (dataClass instanceof DataClassUnion union) {
                TsonTypeReader<?> noOwnData = ctx -> {
                    ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                            "'" + name + "' has no data of its own to bind -- provide an explicit type annotation "
                                    + "(!typeName) naming one of its subtypes " + typeDefinition.subtypes(),
                            "an explicit type annotation naming one of " + typeDefinition.subtypes(), "(none)");
                    return null;
                };
                // Membership is decided through this context's own binder, which is the only thing that
                // knows a schema name is not always its class's own (set -> ArrayBody, and the rest of §5's
                // array family with it). Unresolvable is not an error here -- it just is not a member.
                return new VariantBindReader(name, noOwnData, union, resolver,
                        member -> boundClassOrNull(member, context));
            }

            if (dataClass instanceof DataClassRecord record) {
                RecordBindReader ownParser = new RecordBindReader(name, EntryDisplayName.of(name, typeDefinition),
                        body, record, resolver, context,
                        context.locationOf(name, typeDefinition), AnnotationTypes.of(context));
                return Subsumption.dispatching(name, typeDefinition, ownParser, context.namesMeaning(), resolver);
            }

            throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                    + ", which is neither record- nor union-shaped -- can't bind '" + name + "' as either");
        }

        /**
         * The union members of an <b>untagged labelled choice</b>, keyed by the schema field each one
         * carries, or {@code null} when this is not one -- see {@link GroupUnionBindReader}.
         *
         * <p>Three things must line up, and all three are checked rather than assumed: the Java target is a
         * sealed union, the schema body is one non-optional group of one-field options covering every field it
         * declares -- exclusive alternatives, so neither the {@code +} group nor an option of several fields --
         * and each member carries exactly one component whose wire name is one of those fields. A near-miss falls
         * through to the ordinary record path, where {@link #requireRecord} reports it -- guessing at a
         * partial match would bind a member to a field it does not carry.
         */
        private Map<String, DataClassRecord> labelledChoice(RecordBody body, DataClass dataClass) {
            if (!(dataClass instanceof DataClassUnion union) || body.groups().size() != 1) {
                return null;
            }
            FieldGroup group = body.groups().getFirst();
            if (group.optional()
                    || group.members().stream().anyMatch(option -> option.size() != 1)
                    || group.memberNames().size() != body.fields().size()
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
                if (label == null || !group.hasMember(label)) {
                    return null;
                }
                byField.put(label, record);
            }
            return byField.size() == body.fields().size() ? byField : null;
        }

        /**
         * The Java class {@code schemaTypeName} binds to, or {@code null} where nothing does.
         *
         * <p>{@link #descriptorFor}'s answer without its verdict: a name that binds nowhere is a
         * misconfiguration where a *schema* asked for it, and merely a non-member where a *document* did.
         * Goes through this context's own {@code DataNameBinder}, which is the only thing that knows a
         * schema name is not always its class's own -- {@code set} and {@code array} both reach {@code
         * ArrayBody}, §5's array family resolving to one body shape.
         */
        private Class<?> boundClassOrNull(String schemaTypeName, ValueReaderContext vctx) {
            for (String candidate : vctx.bindingNamesFor(schemaTypeName)) {
                try {
                    return context.getDescriptor(candidate).typeClass();
                } catch (DataBindException | RuntimeException e) {
                    // Not a member under this name; the next candidate is the entry's own.
                }
            }
            return null;
        }

        /**
         * The class this schema type binds to.
         *
         * <p><b>A missing one is a misconfiguration, not a gap.</b> It is the same disagreement {@link
         * BindMismatchException} covers from the other side -- a class that exists and does not fit -- so
         * it is raised the same way and at the same moment, when the schema is compiled in bind mode, naming
         * the type nothing resolves. Surfacing it as a library gap instead would tell a caller who simply
         * never mapped the type that this library cannot do the job, and that reading travels: a downstream
         * service turns it into a 501.
         */
        private DataClass descriptorFor(String name, TypeDefinition definition, ValueReaderContext vctx) {
            DataBindException first = null;
            StringBuilder tried = new StringBuilder();
            for (String candidate : vctx.bindingNamesFor(name, definition)) {
                try {
                    return context.getDescriptor(candidate);
                } catch (UnboundNameException e) {
                    // Not bound under this name; the next candidate is tried, and the first is the one reported,
                    // being the name the author wrote.
                    first = first == null ? e : first;
                    tried.append(tried.isEmpty() ? "" : "' or '").append(candidate);
                } catch (DataBindException e) {
                    // Bound, to a class this context cannot analyse: deferred like a type nobody bound (a meta
                    // layer maps its marker kinds to interfaces nothing builds), and named for what it is.
                    throw new MissingBindingException(e.getMessage(), e);
                }
            }
            throw new MissingBindingException("no bound Java class for '" + tried + "': nothing in this "
                    + "bind context resolves that schema type name. Map it (ProcessorConfig.bindings) or give "
                    + "the context a DataNameBinder that can find it -- "
                    + (first == null ? "" : first.getMessage()), first);
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
