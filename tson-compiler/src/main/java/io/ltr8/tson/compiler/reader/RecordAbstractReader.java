package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
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
import java.util.Optional;

/**
 * Everything {@link RecordDomReader} and {@link RecordBindReader} share verbatim: the compiled
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
 * target type; {@link RecordDomReader} leaves them exactly as this class computed them.
 *
 * <p><b>Forward, single-pass, with overwrite on a duplicate field name</b> (a deliberate behavior
 * change from this class's own pre-streaming design, which scanned backward specifically so a
 * shadowed duplicate's own value was never read at all): {@link #readFields} consumes {@code
 * FieldName} events strictly in stream order, decoding (and thus validating) every occurrence of a
 * recognized field name, with a later occurrence's own decoded value simply replacing an earlier
 * one's in whatever {@link FieldSink} the caller supplies -- necessary for a record to be read in one
 * forward pass over the stream at all, since there's no way to know in advance whether a field name
 * will recur later without buffering the whole record first. See {@code SPEC-FEEDBACK.md} for the
 * observable consequence: a shadowed duplicate's own malformed value now surfaces as a diagnostic
 * (fail-fast: an exception; collecting: a reported problem) even though its own decoded value is
 * ultimately discarded, where it previously went entirely unvalidated.
 *
 * <p>A field name with no match in the compiled field list, and a field the schema itself marks
 * {@code REQUIRED_FIXED}/{@code OPTIONAL_FIXED} (whose value can never come from data -- {@link
 * #precomputedValue} already won, immutably, before {@link #readFields} ever runs), are both
 * discarded unread via {@link EventSkip}, keeping the stream correctly positioned without validating
 * or ever handing either to {@code sink}.
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
abstract class RecordAbstractReader<T> implements TsonValueReader<T> {

    record CompiledField(RecordField schema, TsonValueReader<?> parser) {
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
    final List<CompiledField> fields;
    final Map<String, Integer> fieldIndex;
    final List<FieldGroup> groups;
    final Object[] precomputedValue;
    final int[] fixedFieldIndices;
    final int positionalFieldIndex;
    final Optional<SourcePosition> schemaPosition;

    RecordAbstractReader(String name, RecordBody body, TsonValueReaderResolver resolver,
                          Optional<SourcePosition> schemaPosition) {
        this.name = name;
        this.schemaPosition = schemaPosition;
        this.fields = buildFields(body, resolver);
        this.groups = body.groups();
        this.fieldIndex = new HashMap<>();
        this.precomputedValue = new Object[fields.size()];
        List<Integer> fixedIndices = new ArrayList<>();
        int solePositionalField = -1;
        int bareRequiredCount = 0;
        for (int i = 0; i < fields.size(); i++) {
            CompiledField field = fields.get(i);
            fieldIndex.put(field.schema().name(), i);
            FieldState state = field.schema().state();
            if (state == FieldState.REQUIRED_DEFAULT || state == FieldState.REQUIRED_FIXED
                    || state == FieldState.OPTIONAL_FIXED) {
                precomputedValue[i] = readSchemaDefault(field);
            }
            if (state == FieldState.REQUIRED_FIXED || state == FieldState.OPTIONAL_FIXED) {
                fixedIndices.add(i);
            }
            if (state == FieldState.REQUIRED) {
                bareRequiredCount++;
                solePositionalField = i;
            }
        }
        this.fixedFieldIndices = fixedIndices.stream().mapToInt(Integer::intValue).toArray();
        this.positionalFieldIndex = bareRequiredCount == 1 ? solePositionalField : -1;
    }

    private static List<CompiledField> buildFields(RecordBody body, TsonValueReaderResolver resolver) {
        List<CompiledField> fields = new ArrayList<>(body.fields().size());
        for (RecordField field : body.fields()) {
            fields.add(new CompiledField(field, resolver.resolve(field.type().name())));
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
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a record for '" + name + "', found " + e,
                "a record", String.valueOf(e));
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
     */
    final boolean[] readFields(TsonReadContext ctx, FieldSink sink) {
        boolean[] seen = new boolean[fields.size()];
        while (!(ctx.peek() instanceof RecordEnd)) {
            FieldName fieldName = (FieldName) ctx.next();
            Integer schemaIndex = fieldIndex.get(fieldName.name());
            if (schemaIndex == null || isFixed(fields.get(schemaIndex).schema().state())) {
                EventSkip.scopedValue(ctx);
                continue;
            }
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            Object decoded;
            if (ctx.peek() instanceof AbsentEvent) {
                ctx.next();
                decoded = defaultOrRequireNonFixed(schemaIndex, ctx);
            } else {
                decoded = fields.get(schemaIndex).parser().read(ctx.field(fieldName.name()));
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
        Object decoded = fields.get(index).parser().read(ctx.field(fields.get(index).schema().name()));
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
                        "at most one of (" + members + ") may be present for '" + name + "', found " + present,
                        "at most one of (" + members + ")", present + " present");
            } else if (group.state() == ElementState.REQUIRED && present == 0) {
                ctx.report(Diagnostic.Code.FIELD_REQUIRED,
                        "exactly one of (" + members + ") must be present for '" + name + "'",
                        "one of (" + members + ")", "none present");
            }
        }
    }

    /**
     * {@code REQUIRED_FIXED}/{@code OPTIONAL_FIXED} fields are pre-seeded from {@link
     * #fixedFieldIndices} before this can ever be reached for them. {@code ctx} is expected to still
     * be scoped to the *enclosing* record (not yet descended into the missing field) -- this itself
     * descends one level via {@link TsonReadContext#field}, so the reported {@link Diagnostic#path()}
     * still names the missing field while its own {@link Diagnostic#dataPosition()} reflects {@code
     * ctx}'s own {@link TsonReadContext#position()} -- both {@link #readFields}'s own callers pass a
     * plain {@code ctx} for a field noticed absent inline (the live cursor is already sitting right
     * at it) and an anchored one (see {@link ShapeResult#anchor()}, via {@link
     * TsonReadContext#withPosition}) for the second "never mentioned at all" pass, where the live
     * cursor has already moved past the whole record.
     */
    final Object defaultOrRequireNonFixed(int schemaIndex, TsonReadContext ctx) {
        RecordField schema = fields.get(schemaIndex).schema();
        return switch (schema.state()) {
            case REQUIRED -> {
                ctx.field(schema.name()).report(Diagnostic.Code.FIELD_REQUIRED,
                        "missing required field '" + schema.name() + "' for '" + name + "'",
                        "a value for '" + schema.name() + "'", "(absent)");
                yield null;
            }
            case OPTIONAL -> null;
            case REQUIRED_DEFAULT -> precomputedValue[schemaIndex];
            case REQUIRED_FIXED, OPTIONAL_FIXED -> throw new IllegalStateException("unreachable: '" + schema.name()
                    + "' is fixed and is always pre-seeded before this fallback runs");
        };
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
        if (schema.valueParam().isPresent()) {
            throw new UnsupportedOperationException("'" + schema.name() + "' on '" + name + "' defaults via a "
                    + "type parameter ('= " + schema.valueParam().get() + "') -- parameter substitution isn't "
                    + "implemented anywhere in this codebase yet, so this can't resolve to a concrete value");
        }
        Token token = schema.value().orElseThrow(() -> new IllegalStateException("'" + schema.name() + "' on '"
                + name + "' is " + schema.state() + " but the schema carries neither a literal value nor a "
                + "value parameter for it -- DefinitionResolver should never produce this"));
        TokenEvent event = new TokenEvent(token.text(), TokenForm.valueOf(token.form().name()), new Position(0, 0, 0));
        TsonReadContext syntheticCtx = TsonReadContext.throwing(new ListEventSource(List.of(event)));
        return field.parser().read(syntheticCtx);
    }
}
