package io.ltr8.tson.parser.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.base.NumberNarrowing;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Object-binding mode's own {@code record} reader -- reads a record-shaped value into a real, bound
 * Java object via {@code descriptor}, a {@code tson-bind} {@link DataClassRecord} already resolved
 * for this record's own schema type name (resolving one is this class's caller's job, not this
 * class's).
 *
 * <p><b>{@link #read} walks the incoming data once, not the schema's own field list per field, and
 * needs no separate "already filled" {@code boolean[]} tracker for the common case.</b> {@code
 * targetField} (position -> bound {@code DataClassField}, or {@code null} if the target class
 * doesn't declare this schema field) is built once, right after {@link RecordAbstractReader}'s own
 * constructor returns, which is also where every inherited {@link #precomputedValue} entry gets
 * narrowed in place to its bound field's target type -- {@link RecordDomReader} leaves those
 * unnarrowed, since a plain {@code Map} has no target type to narrow toward. Every {@code
 * REQUIRED_FIXED}/{@code OPTIONAL_FIXED} field's own precomputed value is written into {@code
 * arguments} up front, before the data is ever consulted -- a fixed field's value is immutable, so
 * data can never override it -- and this is what lets the ordinary data pass skip a bound fixed
 * field with the exact same check it uses for an already-filled duplicate, {@code
 * arguments[target.index()] != null}, with no separate "is this field fixed" branch in the hot path.
 * The one data pass runs *backward*, so the first occurrence found for a still-empty slot is
 * genuinely its last in source order (§2.5's "last value wins"). A schema field neither fixed nor
 * mentioned by the data still needs its own required-or-default handling, but that second pass over
 * the schema's own field list is skipped whenever every non-fixed field was already filled by data --
 * tracked with a single {@code int} counter, not an array.
 *
 * <p><b>A schema field the target class doesn't bind at all (no {@code arguments} slot to hold an
 * "already filled" signal) is the one case this still needs a real {@code boolean[]} for</b> --
 * {@code unboundFilled}, allocated only when {@code hasUnboundField} says this compiled type
 * actually has one (every real {@code schema.meta} class this codebase binds against declares every
 * field the schema does, so this array is {@code null}, and the check that would consult it never
 * runs, for every real caller today). Without it, a required-but-unbound field genuinely present in
 * the data would have no way to record that it was seen, and the schema-field pass would report it
 * missing.
 *
 * <p>Everything shared with {@link RecordDomReader} -- the compiled field list, the name lookup,
 * unwrapping a record-shaped {@link DataValue}, precomputing default/fixed values -- lives on
 * {@link RecordAbstractReader}; this class holds only what's genuinely different about producing a
 * real bound object instead of a plain {@code Map}: the target-field lookup, narrowing, and
 * constructor invocation.
 */
public final class RecordBindReader extends RecordAbstractReader<Object> {

    private final DataClassRecord descriptor;
    private final DataClassField[] targetField;
    private final int nonFixedFieldCount;
    private final boolean hasUnboundField;

    public RecordBindReader(String name, RecordBody body, DataClassRecord descriptor, ValueReaderResolver resolver) {
        super(name, body, resolver);
        this.descriptor = descriptor;
        this.targetField = new DataClassField[fields.size()];
        boolean anyUnbound = false;
        for (int i = 0; i < fields.size(); i++) {
            CompiledField field = fields.get(i);
            DataClassField target = findTargetField(descriptor.fields(), field.schema().name());
            targetField[i] = target;
            if (target == null) {
                anyUnbound = true;
                continue;
            }
            TsonValueReader<?> rebound = rebindContainerIfNeeded(field, target, resolver);
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
        this.hasUnboundField = anyUnbound;
        this.nonFixedFieldCount = fields.size() - fixedFieldIndices.length;
    }

    private static DataClassField findTargetField(DataClassField[] classFields, String fieldName) {
        for (DataClassField classField : classFields) {
            if (classField.name().equals(fieldName)) {
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
    private static TsonValueReader<?> rebindContainerIfNeeded(CompiledField field, DataClassField target,
            ValueReaderResolver resolver) {
        TsonValueReader<?> parser = field.parser();
        if (target.dataClass() instanceof DataClassArray targetArray && parser instanceof ArrayBindReader existing) {
            return new ArrayBindReader(field.schema().name(), existing.body, targetArray, resolver);
        }
        if (target.dataClass() instanceof DataClassMap targetMap && parser instanceof MapBindReader existing) {
            return new MapBindReader(field.schema().name(), existing.body, targetMap, resolver);
        }
        return parser;
    }

    @Override
    public Object read(DataValue value) {
        List<RecordValue.Field> dataFields = dataFields(value);
        Object[] arguments = new Object[descriptor.fields().length];
        boolean[] unboundFilled = hasUnboundField ? new boolean[fields.size()] : null;

        for (int schemaIndex : fixedFieldIndices) {
            DataClassField target = targetField[schemaIndex];
            if (target != null) {
                arguments[target.index()] = precomputedValue[schemaIndex];
            } else {
                unboundFilled[schemaIndex] = true;
            }
        }

        int filledCount = 0;
        for (int i = dataFields.size() - 1; i >= 0; i--) {
            RecordValue.Field dataField = dataFields.get(i);
            Integer schemaIndex = fieldIndex.get(dataField.name());
            if (schemaIndex == null) {
                continue;
            }
            CompiledField field = fields.get(schemaIndex);
            DataClassField target = targetField[schemaIndex];
            if (target != null) {
                if (arguments[target.index()] != null) {
                    continue;
                }
                DataValue fieldValue = dataField.value().value();
                arguments[target.index()] = isAbsent(fieldValue)
                        ? defaultOrRequireNonFixed(schemaIndex)
                        : narrow(field.parser().read(fieldValue), target.type());
                filledCount++;
            } else {
                if (isFixed(field.schema().state()) || unboundFilled[schemaIndex]) {
                    continue;
                }
                DataValue fieldValue = dataField.value().value();
                if (isAbsent(fieldValue)) {
                    defaultOrRequireNonFixed(schemaIndex);
                } else {
                    field.parser().read(fieldValue);
                }
                unboundFilled[schemaIndex] = true;
                filledCount++;
            }
        }

        if (hasUnboundField || filledCount < nonFixedFieldCount) {
            for (int i = 0; i < fields.size(); i++) {
                CompiledField field = fields.get(i);
                if (isFixed(field.schema().state())) {
                    continue;
                }
                DataClassField target = targetField[i];
                if (target != null) {
                    if (arguments[target.index()] == null) {
                        arguments[target.index()] = defaultOrRequireNonFixed(i);
                    }
                } else if (!unboundFilled[i]) {
                    defaultOrRequireNonFixed(i);
                }
            }
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
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof RecordBody body)) {
                throw new IllegalArgumentException(
                        "'" + name + "' is not record-shaped: " + typeDefinition.body());
            }
            DataClass dataClass = descriptorFor(name);

            if (typeDefinition.subtypes().isEmpty()) {
                return new RecordBindReader(name, body, requireRecord(name, dataClass), resolver);
            }

            if (dataClass instanceof DataClassUnion union) {
                TsonValueReader<?> noOwnData = value -> {
                    throw new IllegalArgumentException("'" + name + "' has no data of its own to bind -- provide "
                            + "an explicit type annotation (!typeName) naming one of its subtypes "
                            + typeDefinition.subtypes());
                };
                return new VariantBindReader(name, noOwnData, union, resolver);
            }

            if (dataClass instanceof DataClassRecord record) {
                RecordBindReader ownParser = new RecordBindReader(name, body, record, resolver);
                return new VariantSchemaReader(name, ownParser, typeDefinition.subtypes(), resolver);
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
