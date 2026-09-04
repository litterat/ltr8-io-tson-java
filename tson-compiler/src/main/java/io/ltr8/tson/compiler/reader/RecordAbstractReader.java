package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonUnicodePolicy;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Everything {@link RecordTreeReader} and {@link RecordBindReader} share verbatim: the compiled
 * per-field list, the name -> position lookup, confirming a record-shaped value's own event
 * sequence, and precomputing every {@code REQUIRED_DEFAULT}/{@code REQUIRED_FIXED}/{@code
 * OPTIONAL_FIXED} field's own literal schema value once at construction rather than per read. Each
 * subclass's own {@code read()} still differs in shape (a {@code Map} vs. a real bound object's own
 * constructor arguments) and stays there, along with anything specific to only one of them (object
 * mode's own target-type narrowing, for instance).
 *
 * <p>{@link #precomputedValue} is stored raw here -- the natural host value {@code readSchemaDefault}
 * produces, with no narrowing applied. {@link RecordBindReader} overwrites its own entries in place,
 * once, right after calling this class's own constructor, narrowing each one to its bound field's
 * target type; {@link RecordTreeReader} leaves them exactly as this class computed them. A field's
 * {@code FixedCheck} keeps the pre-rebind parser for exactly that reason: a written token has to be
 * decoded the same way the schema's own value was, or comparing them would compare across the narrowing.
 *
 * <p><b>Forward, single-pass, and a repeated field name is a validation error</b> ({@code
 * DUPLICATE_FIELD}): {@link #readFields} consumes {@code FieldName} events strictly in stream order,
 * decoding (and thus validating) every occurrence of a recognized field name, reporting the second and
 * later ones at their own positions. [TSON-DATA] §2.5 makes a duplicate field name MUST NOT, with the
 * diagnostic at the repeated occurrence, which leaves no shadowed-occurrence question -- the repeat
 * <em>is</em> the error, so whether its value was going to be used decides nothing. Last-value-wins
 * survives here only as the recovery underneath: a later occurrence's decoded
 * value replaces an earlier one's in whatever {@link FieldSink} the caller supplies, which is what lets a
 * record be read in one forward pass over the stream at all (there is no way to know in advance whether a
 * name will recur without buffering the whole record first).
 *
 * <p><b>Records are closed under their type</b> ([TSON-SCHEMA] §7.2): a field name with no match in the
 * compiled field list is an {@code UNRECOGNIZED_FIELD} violation, reported and then discarded unread via
 * {@link EventSkip}, which keeps the stream correctly positioned. Closure is a MUST wherever a schema is in
 * scope and is not configurable; §7.2 exempts only schemaless records, and those are read by
 * {@code SchemalessObjectReader}/{@code SchemalessTreeReader}, which never reach this class. The rule
 * reaches the <em>schema</em> path too, through the same code: a constructor body ({@code !integer ^ { min:
 * 1 }}) is bound by replaying it through the governing meta's own compiled reader, so a hallucinated facet
 * (§5.5's vocabulary does not include JSON Schema's {@code minimum}) is caught here rather than silently
 * constraining nothing. A FIXED field the document <em>does</em> state is a different
 * case: its value still comes from {@link #precomputedValue} and never from the data, but the stated token
 * is decoded and checked against the fixed one, because §5.2 makes a contradicting value a validation
 * error. Skipping it unread would let a document say one thing and decode to another in silence.
 *
 * <p><b>Positional form (§5.6):</b> a record whose fields include exactly one bare {@code REQUIRED}
 * one (never {@code REQUIRED_DEFAULT}/{@code REQUIRED_FIXED}/{@code OPTIONAL}/{@code
 * OPTIONAL_FIXED}, even if it's the only field present) can be filled by a bare, non-braced
 * value standing in for that one field -- {@code !enum [true false]}'s own {@code [true false]}
 * filling {@code enum}'s sole required field, {@code members}, without ever writing {@code {
 * members: [true false] } }. {@link #positionalFieldIndex} (that field's own schema position, or
 * {@code -1} if the record doesn't qualify) is computed once, in the constructor, by counting bare
 * {@code REQUIRED} fields in the same pass that already visits every field for {@link
 * #precomputedValue} -- no separate pass, and nothing paid per read: {@link #expectRecordShape} only
 * consults it on the one path that isn't already record-shaped, and {@link #readPositional} then
 * reads whatever's already sitting at the cursor directly as that single field's own value, with no
 * synthetic wrapping needed at all.
 */
abstract class RecordAbstractReader<T> implements TsonTypeReader<T> {

    record CompiledField(RecordField schema, TsonTypeReader<?> parser) {
    }

    /**
     * What a written value at a FIXED field is checked against (§5.2: "A contradicting value is a validation
     * error"). Holds the field's <em>schema-level</em> parser, captured before any subclass rebinds it, so
     * the token in the document is decoded the same way {@link #precomputedValue} was and the two are
     * comparable in either read mode. {@code value} is likewise the <em>raw</em> parsed value, not the
     * {@link #precomputedValue} entry that {@link RecordBindReader} narrows in place -- comparing a
     * raw-parsed token against a narrowed one would report a contradiction between two spellings of the same
     * number. {@code mustBeAbsent} is §5.2's {@code type? = _}: no value exists to compare against, and only
     * omission or {@code _} conforms.
     */
    private record FixedCheck(boolean mustBeAbsent, Object value, TsonTypeReader<?> parser) {
    }

    /** Called once per recognized, non-fixed field {@link #readFields}/{@link #readPositional} decode -- may be called more than once for the same {@code schemaIndex} on a duplicate field name; the last call wins. */
    interface FieldSink {
        void accept(int schemaIndex, Object decodedValue);
    }

    enum Shape { FIELDS, EMPTY, POSITIONAL, MISMATCH }

    /**
     * {@code anchor} is this record's own opening position -- the first event belonging to its own
     * value, captured before any field is processed -- so a caller's own second "fill in missing/
     * default" pass can report a never-mentioned field's own {@code FIELD_REQUIRED} against *this*
     * record's own position (via {@link TsonReadContext#withPosition}), not wherever the shared
     * cursor has drifted to by the time that pass runs (past {@code RecordEnd}, for {@link
     * Shape#FIELDS}/{@link Shape#EMPTY}).
     */
    record ShapeResult(Shape shape, Optional<SourcePosition> anchor) {
    }

    final String name;

    /** What to call this entry in a message -- see {@link ArrayAbstractReader#displayName}. */
    final String displayName;

    final List<CompiledField> fields;
    final Map<String, Integer> fieldIndex;
    final List<FieldGroup> groups;
    final Object[] precomputedValue;
    private final FixedCheck[] fixedCheck;
    final int positionalFieldIndex;
    final SchemaLocation schemaLocation;
    /**
     * This type's declared field names in <em>schema</em> order, rendered once for the closure diagnostic
     * ({@link #readFields}) -- both its message and its machine-readable {@code expected}. Schema order, not
     * {@link #fieldIndex}'s hash order, so the same schema always produces the same diagnostic; built here
     * rather than per report because a document full of stray names would otherwise rebuild it per name.
     */
    private final String declaredFields;

    RecordAbstractReader(String name, String displayName, RecordBody body, FieldReaders readers,
                          SchemaLocation schemaLocation) {
        this.name = name;
        this.displayName = displayName;
        this.schemaLocation = schemaLocation;
        this.fields = buildFields(body, readers);
        this.groups = body.groups();
        this.fieldIndex = new HashMap<>();
        this.precomputedValue = new Object[fields.size()];
        FixedCheck[] fixedChecks = new FixedCheck[fields.size()];
        int solePositionalField = -1;
        int bareRequiredCount = 0;
        for (int i = 0; i < fields.size(); i++) {
            CompiledField field = fields.get(i);
            fieldIndex.put(field.schema().name(), i);
            FieldState state = field.schema().state();
            if (state == FieldState.REQUIRED_DEFAULT || state == FieldState.REQUIRED_FIXED
                    || (state == FieldState.OPTIONAL_FIXED && field.schema().value().isPresent())) {
                precomputedValue[i] = readSchemaDefault(field);
            }
            if (isFixed(state)) {
                // §5.2's sixth spelling (`type? = _`) is OPTIONAL_FIXED with no value at all: nothing to
                // parse, and the only conforming document is one that omits the field or writes `_`.
                fixedChecks[i] = new FixedCheck(field.schema().value().isEmpty(), precomputedValue[i],
                        field.parser());
            }
            if (state == FieldState.REQUIRED) {
                bareRequiredCount++;
                solePositionalField = i;
            }
        }
        this.fixedCheck = fixedChecks;
        this.positionalFieldIndex = bareRequiredCount == 1 ? solePositionalField : -1;
        this.declaredFields = fields.stream().map(field -> field.schema().name()).collect(Collectors.joining(" | "));
    }

    /**
     * How one record's field readers are chosen.
     *
     * <p><b>Per field, not per schema type name.</b> {@link #byType} is what a reader with no binding target
     * in view can do, and is what tree mode uses: the field's declared type names its reader and nothing
     * else is known. Object-binding mode knows more -- which Java component the field will fill -- and two
     * fields of the same schema type can want different readers because their components differ. Handing
     * the choice the {@link RecordField} rather than its type name is what lets that be said; a resolver
     * keyed by type name cannot express it, and a reader built from one has to give up where two slots of a
     * type disagree.
     */
    @FunctionalInterface
    interface FieldReaders {

        TsonTypeReader<?> forField(RecordField field);

        /**
         * By the field's declared schema type, named as the author wrote it here ({@link UseSite}).
         *
         * <p>With one exception, and it is the reason this takes a context at all: a field carrying
         * {@code @bytes_encoding} needs a reader of its own. Every other field of a given type shares one
         * compiled reader, which is what makes compilation cheap; the directive is per-position by design
         * (`@bytes_encoding:HEX  digest: sha256`), so a field that states one gets its own parser over the
         * same constraints rather than the shared one.
         */
        static FieldReaders byType(TsonTypeReaderResolver resolver, ValueReaderContext context,
                java.util.function.BiFunction<TsonTypeReader<?>, String, TsonTypeReader<?>> leaf) {
            return field -> BytesEncoding.fieldReader(field, context, leaf)
                    .orElseGet(() -> resolver.resolve(field.type().name()));
        }
    }

    private static List<CompiledField> buildFields(RecordBody body, FieldReaders readers) {
        List<CompiledField> fields = new ArrayList<>(body.fields().size());
        for (RecordField field : body.fields()) {
            fields.add(new CompiledField(field, readers.forField(field)));
        }
        return fields;
    }

    /**
     * Consumes leading annotations/type-ref, then decides the record's own shape: {@code
     * RecordStart} ({@link Shape#FIELDS}, consumed -- {@link #readFields} follows), {@code
     * EmptyBraceEvent} ({@link Shape#EMPTY}, consumed -- every non-fixed field falls straight to
     * its own default/required handling), a value eligible for {@link #positionalFieldIndex}
     * ({@link Shape#POSITIONAL}, nothing consumed -- {@link #readPositional} reads the value
     * directly), or neither ({@link Shape#MISMATCH}: {@code TYPE_MISMATCH} reported, whatever was
     * actually there discarded). A caller seeing {@link Shape#MISMATCH} must stop processing this
     * record entirely rather than also reporting every one of its own fields as separately missing.
     */
    final ShapeResult expectRecordShape(TsonReadContext ctx) {
        EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        Optional<SourcePosition> anchor = Optional.of(e.position());
        if (e instanceof RecordStart) {
            ctx.next();
            return new ShapeResult(Shape.FIELDS, anchor);
        }
        if (e instanceof EmptyBraceEvent) {
            ctx.next();
            return new ShapeResult(Shape.EMPTY, anchor);
        }
        if (positionalFieldIndex >= 0) {
            return new ShapeResult(Shape.POSITIONAL, anchor);
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "expected a record for '" + displayName + "', found " + TypeRefCheck.describe(e),
                "a record", TypeRefCheck.describe(e));
        EventSkip.coreValue(ctx);
        return new ShapeResult(Shape.MISMATCH, anchor);
    }

    /**
     * Loops {@code FieldName} events forward until {@code RecordEnd} (the cursor assumed already
     * positioned right after {@code RecordStart} -- see {@link #expectRecordShape}), decoding each
     * recognized, non-fixed field's own value and handing it to {@code sink} -- see this class's own
     * Javadoc for the forward-overwrite behavior this implements. Returns which schema fields
     * {@code sink} was invoked for at least once, so a caller's own second "fill in missing/default"
     * pass (over every {@code false} entry) knows exactly which fields still need it.
     *
     * <p>A name this type does not declare is reported ({@code UNRECOGNIZED_FIELD}) and then discarded
     * unread, per the continuation policy -- so one collecting pass finds every stray name in a record
     * rather than only the first, and the record still yields whatever its declared fields hold.
     */
    final boolean[] readFields(TsonReadContext ctx, FieldSink sink) {
        boolean[] seen = new boolean[fields.size()];
        while (!(ctx.peek() instanceof RecordEnd)) {
            FieldName fieldName = (FieldName) ctx.next();
            Integer schemaIndex = fieldIndex.get(fieldName.name());
            if (schemaIndex == null) {
                ctx.field(fieldName.name()).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                        "unknown field '" + fieldName.name() + "' on '" + displayName + "' -- a record is closed "
                                + "under its type (§7.2), whose fields are (" + declaredFields + ")",
                        declaredFields, fieldName.name());
                EventSkip.scopedValue(ctx);
                continue;
            }
            if (seen[schemaIndex]) {
                ctx.schemaField(fieldName.name(), fields.get(schemaIndex).schema().position())
                        .report(Diagnostic.Code.DUPLICATE_FIELD,
                        "duplicate field '" + fieldName.name() + "' on '" + displayName + "' -- a record states each "
                                + "field at most once (§2.5), and the repeat states a value for nothing",
                        "each field stated once", "'" + fieldName.name() + "' stated again");
            }
            if (fixedCheck[schemaIndex] != null) {
                // A FIXED field's value comes from the schema, never the data -- but the data may still
                // *state* it, and §5.2 makes a contradicting statement a validation error. Skipping it
                // unread (what this did before) meant a document could say one thing and decode to another
                // with no diagnostic at all. `seen` is set either way: the field appeared, which is what
                // §5.11's group count is counting.
                verifyFixed(ctx, schemaIndex, sink, fieldName.name());
                seen[schemaIndex] = true;
                continue;
            }
            SchemaRef push = ScopePush.notAdmitted(ctx, fields.get(schemaIndex).parser());
            if (push != null) {
                ScopePush.refuse(ctx.schemaField(fieldName.name(), fields.get(schemaIndex).schema().position()),
                        fields.get(schemaIndex).schema().type().name(), push);
            }
            Object decoded;
            if (ctx.peek() instanceof AbsentEvent) {
                ctx.next();
                decoded = valueForStatedAbsentField(schemaIndex, ctx);
            } else {
                decoded = fields.get(schemaIndex).parser()
                        .read(ctx.schemaField(fieldName.name(), fields.get(schemaIndex).schema().position()));
            }
            sink.accept(schemaIndex, decoded);
            seen[schemaIndex] = true;
        }
        ctx.next(); // RecordEnd
        return seen;
    }

    /** {@link Shape#POSITIONAL} counterpart to {@link #readFields} -- reads whatever's at the cursor directly as {@link #positionalFieldIndex}'s own value, with no other field ever seen. */
    final boolean[] readPositional(TsonReadContext ctx, FieldSink sink) {
        boolean[] seen = new boolean[fields.size()];
        int index = positionalFieldIndex;
        Object decoded = fields.get(index).parser()
                .read(ctx.schemaField(fields.get(index).schema().name(), fields.get(index).schema().position()));
        sink.accept(index, decoded);
        seen[index] = true;
        return seen;
    }

    static boolean isFixed(FieldState state) {
        return state == FieldState.REQUIRED_FIXED || state == FieldState.OPTIONAL_FIXED;
    }

    /**
     * Field-group presence check (§5.11): a bare (REQUIRED) group must have exactly one member
     * present, a {@code ?} (OPTIONAL) group at most one -- the group's members flatten into ordinary
     * OPTIONAL fields (§5.11's own resolution), so this is the only place the group's own
     * "at most/exactly one" multiplicity is actually enforced at read time. "Present" means the
     * member's field name appeared in the data ({@code seen}); a member written as the absent
     * sentinel {@code _} still counts as appearing, an edge this doesn't distinguish. Reported
     * through {@code ctx} like any other problem, so both readers gain it by calling this once after
     * their own field pass, and collecting mode surfaces a group violation alongside sibling ones.
     */
    final void validateGroups(TsonReadContext ctx, boolean[] seen) {
        for (FieldGroup group : groups) {
            int present = 0;
            for (String member : group.members()) {
                Integer idx = fieldIndex.get(member);
                if (idx != null && seen[idx]) {
                    present++;
                }
            }
            String members = String.join(" | ", group.members());
            if (present > 1) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "at most one of (" + members + ") may be present for '" + displayName + "', found " + present,
                        "at most one of (" + members + ")", present + " present");
            } else if (group.state() == ElementState.REQUIRED && present == 0) {
                ctx.report(Diagnostic.Code.FIELD_REQUIRED,
                        "exactly one of (" + members + ") must be present for '" + displayName + "'",
                        "one of (" + members + ")", "none present");
            }
        }
    }

    /**
     * The value a field takes when the document wrote {@code _} at it, which differs from never mentioning
     * it at all ({@link #valueForAbsentField}) in exactly one state. §5.2 makes an explicit {@code _} at any
     * REQUIRED-family field a validation error, omission remaining the injection route; this reports it and
     * injects anyway. Injecting silently would substitute a value the
     * document explicitly disclaimed, and for the retry loop the format targets, {@code _} at a defaulted
     * field means the emitter misread the schema -- exactly the signal injection papers over. It also
     * completes §7.6's table, whose REQUIRED_DEFAULT cell was the lone warn among states that already
     * error at REQUIRED and REQUIRED_FIXED. Plain omission still injects silently, which is the whole
     * point of the state.
     *
     * <p>The default is still what the field decodes to, so the value comes back whole and only the
     * verdict changes -- the same split {@link #verifyFixed} makes for a contradicted FIXED value. Neither
     * FIXED state reaches here at all: {@link #readFields} routes both through {@link #verifyFixed}, which
     * answers {@code _} for itself.
     */
    final Object valueForStatedAbsentField(int schemaIndex, TsonReadContext ctx) {
        RecordField schema = fields.get(schemaIndex).schema();
        if (schema.state() == FieldState.OPTIONAL) {
            // [TSON-DATA] §2.9: "A field or entry set to `_` is present with an absent value -- distinct from
            // not appearing at all." Answering `valueForAbsentField`'s `null` here would collapse the two,
            // and the state is the one where both are legal, so it is the only one where the distinction has
            // anything to carry.
            return statedAbsentValue();
        }
        if (schema.state() == FieldState.REQUIRED_DEFAULT) {
            ctx.schemaField(schema.name(), schema.position()).report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                    "'" + schema.name() + "' on '" + displayName + "' is always filled from the schema and cannot be "
                            + "written '_' -- omit the field to take its default (§5.2)",
                    "the field omitted, or a value for '" + schema.name() + "'", "_");
            return precomputedValue[schemaIndex];
        }
        return valueForAbsentField(schemaIndex, ctx);
    }

    /**
     * The no-value form this reader's own output mode uses for a field the document wrote {@code _} at.
     *
     * <p>Per subclass because the two modes can represent different things. A tree has a node for it
     * ({@code TsonAbsent}), so it keeps [TSON-DATA] §2.9's distinction between a field written {@code _} and
     * one never written -- which an array element and a tuple slot already keep, leaving the record the one
     * container of the four that dropped it. A bound object has no third state between {@code null} and a
     * component that was never set, so bind mode answers {@code null} and the two collapse there; that is a
     * limit of the target, not a reading of §2.9, and it is why this is a subclass's answer rather than one
     * shared here.
     */
    abstract Object statedAbsentValue();

    /**
     * The value a field takes when the document never stated it -- §5.2's five states answered in one place,
     * which is what lets both subclasses run a single "everything not seen" pass instead of pre-seeding some
     * states and defaulting the rest. {@code ctx} is expected to still be scoped to the *enclosing* record
     * (not yet descended into the missing field) -- this itself
     * descends one level via {@link TsonReadContext#field}, so the reported {@link Diagnostic#path()}
     * still names the missing field while its own {@link Diagnostic#dataPosition()} reflects {@code
     * ctx}'s own {@link TsonReadContext#position()} -- both {@link #readFields}'s own callers pass a
     * plain {@code ctx} for a field noticed absent inline (the live cursor is already sitting right
     * at it) and an anchored one (see {@link ShapeResult#anchor()}, via {@link
     * TsonReadContext#withPosition}) for the second "never mentioned at all" pass, where the live
     * cursor has already moved past the whole record.
     */
    final Object valueForAbsentField(int schemaIndex, TsonReadContext ctx) {
        RecordField schema = fields.get(schemaIndex).schema();
        return switch (schema.state()) {
            case REQUIRED -> {
                ctx.schemaField(schema.name(), schema.position()).report(Diagnostic.Code.FIELD_REQUIRED,
                        "missing required field '" + schema.name() + "' for '" + displayName + "'",
                        "a value for '" + schema.name() + "'", "(absent)");
                yield null;
            }
            case OPTIONAL -> null;
            // §5.2's Default injection: "when a field has state REQUIRED_DEFAULT (or REQUIRED_FIXED) and the
            // data does not provide a value, the decoder injects the default (or fixed) value".
            case REQUIRED_DEFAULT, REQUIRED_FIXED -> precomputedValue[schemaIndex];
            // OPTIONAL_FIXED is *not* on that list, and that omission is the whole difference between it and
            // REQUIRED_FIXED: an omitted OPTIONAL_FIXED field stays absent rather than materialising a value
            // the document never wrote. §5.2 says so outright: OPTIONAL and OPTIONAL_FIXED are never injected.
            case OPTIONAL_FIXED -> null;
        };
    }

    /**
     * Checks a FIXED field the document actually stated, and re-emits the <em>schema's</em> value for it
     * (§5.2: a REQUIRED_FIXED field "may be provided with a value matching the fixed value, or omitted").
     * The document's token decides only whether the document is valid; it never becomes the field's value.
     *
     * <p>Three outcomes are wrong and each is reported: a value contradicting the fixed one, any value at a
     * {@code = _} field (§5.2: "the field MUST either be omitted or be the absent sentinel"), and {@code _}
     * at a REQUIRED_FIXED field ("at a plain REQUIRED or a REQUIRED_FIXED field, `_` is a validation
     * error"). {@code _} at an OPTIONAL_FIXED field is fine -- the field may be absent, which is what it
     * asserts.
     *
     * <p><b>One wrong token yields one diagnostic.</b> The stated token is decoded through the field's own
     * parser, which reports for its own reasons (an out-of-range integer, an enum non-member) and then hands
     * back whatever a failed parse returns. Comparing that to the fixed value would report a contradiction
     * inferred from a parse that already failed, so the {@code ctx.reported()} checkpoint idiom this reader
     * stack uses everywhere else applies here too: if decoding reported, the contradiction check is skipped.
     */
    private void verifyFixed(TsonReadContext ctx, int schemaIndex, FieldSink sink, String fieldName) {
        SchemaRef push = ScopePush.notAdmitted(ctx, fields.get(schemaIndex).parser());
        FixedCheck check = fixedCheck[schemaIndex];
        RecordField schema = fields.get(schemaIndex).schema();
        TsonReadContext fieldCtx = ctx.schemaField(fieldName, schema.position());
        if (push != null) {
            // A FIXED field's type is an atom or an enum (§5.2), so it is never scoped and the directive is
            // always refused here -- but it is refused rather than ignored, which is the point.
            ScopePush.refuse(fieldCtx, schema.type().name(), push);
        }
        if (ctx.peek() instanceof AbsentEvent) {
            ctx.next();
            if (schema.state() == FieldState.REQUIRED_FIXED) {
                fieldCtx.report(Diagnostic.Code.FIELD_FIXED,
                        "'" + fieldName + "' is fixed on '" + displayName + "' and cannot be absent",
                        String.valueOf(check.value()), "_");
                return;
            }
            return; // OPTIONAL_FIXED, valued or `= _`: absence is exactly what it permits
        }
        if (check.mustBeAbsent()) {
            EventSkip.scopedValue(ctx);
            fieldCtx.report(Diagnostic.Code.FIELD_FIXED,
                    "'" + fieldName + "' is fixed to absent on '" + displayName + "' and may only be omitted or "
                            + "written as '_'", "_", "a value");
            return;
        }
        int before = ctx.reported();
        Object written = check.parser().read(fieldCtx);
        if (ctx.reported() > before) {
            // The token isn't a value of the field's own type at all, and that has just been reported against
            // this same path. Whatever `read` handed back is what a failed parse returned, not what the
            // document says, so comparing it to the fixed value would report a contradiction derived from a
            // parse that already failed -- two diagnostics for one wrong token, the second of them evidence
            // of nothing.
            return;
        }
        if (!Objects.equals(ValueIdentity.of(written), ValueIdentity.of(check.value()))) {
            fieldCtx.report(Diagnostic.Code.FIELD_FIXED,
                    "'" + fieldName + "' is fixed on '" + displayName + "' and cannot be given another value -- the "
                            + "schema declares it with '=' (fixed); for a default the data may override, "
                            + "use '~'",
                    Rendered.value(check.value()), Rendered.value(written));
            return;
        }
        // The raw value, not the narrowed precomputed one -- every other field reaches the sink raw and is
        // narrowed there, and this one must not be narrowed twice.
        sink.accept(schemaIndex, check.value());
    }

    /**
     * Builds a one-event {@link ListEventSource} wrapping the schema's own literal {@link Token} and
     * reads it through a fail-fast-only {@link TsonReadContext} -- there's no real stream to pull a
     * schema-composed default from (this runs once, at construction, before any actual document is
     * being read), and no real source position for it either (a {@link Position} placeholder is
     * used; a schema-authoring bug here always throws immediately regardless). Only a bare-token
     * default resolves via this today, matching {@link #positionalFieldIndex}'s own scope note: no
     * composite (record/array/map-shaped) schema default resolves anywhere in this codebase yet.
     */
    private Object readSchemaDefault(CompiledField field) {
        RecordField schema = field.schema();
        Token token = schema.value().orElseThrow(() -> new IllegalStateException("'" + schema.name() + "' on '"
                + displayName + "' is " + schema.state() + " but the schema carries no value for it -- "
                + "DefinitionResolver should never produce this"));
        TokenEvent event = new TokenEvent(token.text(), TokenForm.valueOf(token.form().name()), new Position(0, 0, 0));
        // Unrestricted deliberately: this replays a token the real stream already delivered, so it has been
        // judged once. Checking it again here would report one author token twice.
        TsonReadContext syntheticCtx = TsonReadContext.throwing(new ListEventSource(List.of(event)),
                TsonUnicodePolicy.unrestricted());
        return field.parser().read(syntheticCtx);
    }
}
