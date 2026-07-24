package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;

import java.util.List;
import java.util.Optional;

/**
 * §5.6's positional form: "a record with exactly one REQUIRED field can be filled by a bare,
 * non-braced value at any schema-backed data position" -- e.g. {@code !enum [true false]} standing
 * in for {@code !enum { members: [true false] } }, since {@code enum}'s own resolved body ({@code
 * enum => ~atom & { members: set<token> }}) has exactly one bare-{@code REQUIRED} field, {@code
 * members}. {@link #normalizeToRecordForm} is the one operation this collapses to: given a target
 * constructor's own *resolved* {@link RecordBody} and a {@link DataValue} that may or may not
 * already be record-shaped, produce an equivalent record-shaped {@link DataValue} either way.
 *
 * <p><b>Needs the resolved {@code RecordBody}, not just the bound Java class.</b> Finding which
 * field is positionally fillable requires the schema's own {@link FieldState} (only a bare {@link
 * FieldState#REQUIRED} field counts, per §5.6's exact wording -- {@code REQUIRED_DEFAULT}/{@code
 * REQUIRED_FIXED}/{@code OPTIONAL}/{@code OPTIONAL_FIXED} never are, even if it's the only field
 * present) -- plain Java reflection on the bound class (e.g. {@code EnumBody}'s {@code
 * Optional}-wrapping) can't recover that distinction; only the resolved schema can.
 *
 * <p><b>Deliberately a pure {@code tson-parser.ast} transform, not a {@code TsonMapperReader}
 * method or parameter.</b> Keeps the mapper itself free of any {@code schema.meta} dependency --
 * its own class Javadoc already scopes it to operating against a plain {@code DataBindContext}, not
 * "any bigger schema/type-registry layer." A caller (an {@code Instance}'s resolution, in {@code
 * SchemaResolver}/{@code MetaKernelParser}) runs this normalization first; the result is handed to
 * an entirely unmodified {@code TsonMapperReader.toObject}, which binds the now-ordinary record
 * shape exactly like any other. The target field's own schema-level type (e.g. {@code enum}'s
 * {@code members: set<token>}) plays no further role past finding its *name* -- once wrapped,
 * ordinary generic reflection on the bound Java class (e.g. {@code EnumBody.members: List<String>})
 * already knows how to bind the array, the same way it would for any other record field.
 */
final class PositionalForm {

    private PositionalForm() {
    }

    /**
     * {@link EmptyBrace}/{@link RecordValue} pass through unchanged (already record-shaped, §5.6's
     * positional form doesn't apply). Anything else -- a bare token or array -- is wrapped into a
     * synthetic one-field {@link RecordValue} under {@code body}'s sole bare-{@code REQUIRED} field
     * name. The wrapped value's own {@code annotations}/{@code typeRef} stay on the outer,
     * record-level {@link DataValue} (they describe the whole instance, e.g. {@code !enum}'s own
     * type-ref); the synthesized field value carries neither -- it's a fresh {@link DataValue}
     * wrapping only the original core-value.
     */
    static DataValue normalizeToRecordForm(DataValue value, RecordBody body) {
        if (value.coreValue() instanceof RecordValue || value.coreValue() instanceof EmptyBrace) {
            return value;
        }
        String fieldName = solePositionalField(body);
        DataValue fieldValue = new DataValue(List.of(), Optional.empty(), value.coreValue());
        RecordValue wrapped = new RecordValue(
                List.of(new RecordValue.Field(fieldName, new ScopedValue(Optional.empty(), fieldValue))));
        return new DataValue(value.annotations(), value.typeRef(), wrapped);
    }

    private static String solePositionalField(RecordBody body) {
        List<RecordField> required = body.fields().stream()
                .filter(field -> field.state() == FieldState.REQUIRED)
                .toList();
        if (required.size() != 1) {
            throw new UnsupportedOperationException("positional form requires exactly one bare REQUIRED "
                    + "field (§5.6), found " + required.size() + " among " + body.fields());
        }
        return required.get(0).name();
    }
}
