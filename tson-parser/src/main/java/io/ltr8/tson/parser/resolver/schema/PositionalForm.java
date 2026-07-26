package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Normalizes an {@code Instance}'s value into the exact record shape its constructor's own
 * resolved {@link RecordBody} implies, so the result can be handed to ordinary generic record
 * binding with nothing further to know about the schema. Two, independent reasons a value might
 * need adjusting before binding, both requiring the *resolved schema*, not just the bound Java
 * class -- reflection alone can't recover either:
 *
 * <ul>
 *   <li><b>Positional form</b> (§5.6): "a record with exactly one REQUIRED field can be filled by
 *   a bare, non-braced value at any schema-backed data position" -- e.g. {@code !enum [true
 *   false]} standing in for {@code !enum { members: [true false] } }, since {@code enum}'s own
 *   resolved body ({@code enum => ~atom & { members: set<token> }}) has exactly one bare-{@link
 *   FieldState#REQUIRED} field, {@code members}. Finding *which* field is positionally fillable
 *   needs the schema's own {@link FieldState} (only bare {@code REQUIRED} counts, per §5.6's exact
 *   wording -- {@code REQUIRED_DEFAULT}/{@code REQUIRED_FIXED}/{@code OPTIONAL}/{@code
 *   OPTIONAL_FIXED} never are, even if it's the only field present); plain reflection on the bound
 *   class (e.g. {@code EnumBody}'s own shape) can't recover that distinction.</li>
 *   <li><b>Schema-composed defaults</b> (§5.2/§5.7's {@code ~}/{@code =} field modifiers): a
 *   {@code REQUIRED_DEFAULT} or {@code REQUIRED_FIXED} field the instance doesn't itself mention
 *   still needs a value before binding -- the schema already carries one (the modifier's own
 *   literal {@code Token}), it's just never written into any specific instance's own data. Found
 *   the hard way: {@code float32 => !float_type { format: BINARY32 } }'s own body never mentions
 *   {@code allow_nan}/{@code allow_infinity}/{@code allow_subnormal}/{@code allow_negative_zero}
 *   (all {@code boolean ~ true} in meta.tn1's own {@code float_type}), so plain generic binding
 *   against {@code FloatType}'s primitive {@code boolean} fields failed loudly ("missing required
 *   field 'allow_nan'") rather than silently guessing -- exactly the kind of gap this class exists
 *   to close generically, distinct from {@code uri_type}/{@code regex_type}'s own similar-looking
 *   but *not* fixed-by-this gap (see {@link #fillDefaultedFields}'s own note).</li>
 * </ul>
 *
 * <p><b>Deliberately a pure {@code tson-parser.ast} transform, not a {@code TsonMapperReader}
 * method or parameter.</b> Keeps the mapper itself free of any {@code schema.meta} dependency --
 * its own class Javadoc already scopes it to operating against a plain {@code DataBindContext}, not
 * "any bigger schema/type-registry layer." A caller (an {@code Instance}'s resolution, in {@code
 * DefinitionResolver}/{@code MetaKernelBootstrapResolver}) runs this normalization first; the result is handed to
 * an entirely unmodified {@code TsonMapperReader.toObject}, which binds the now-ordinary,
 * now-complete record shape exactly like any other. A field's own schema-level *type* (e.g.
 * {@code enum}'s {@code members: set<token>}) plays no further role past finding its name/default
 * here -- once normalized, ordinary generic reflection on the bound Java class already knows how to
 * bind each field's own value, the same way it would for any other record field.
 */
final class PositionalForm {

    private PositionalForm() {
    }

    /**
     * {@link #toRecordShape} (positional-form wrapping, if needed), then {@link
     * #fillDefaultedFields} (schema-composed defaulting) -- both against the same {@code body}, run
     * unconditionally in that order since neither implies the other: a positionally-wrapped value
     * can still be missing further defaulted fields (not exercised by any real fixture today, but
     * not excluded either), and a value that arrived already record-shaped still needs defaulting
     * exactly the same as a freshly-wrapped one (in fact every real fixture case that needs
     * defaulting -- {@code float32}/{@code float64} -- arrives already record-shaped and never
     * touches the wrapping step at all).
     */
    static DataValue normalizeToRecordForm(DataValue value, RecordBody body) {
        return fillDefaultedFields(toRecordShape(value, body), body);
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
    private static DataValue toRecordShape(DataValue value, RecordBody body) {
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

    /**
     * Adds a field for every {@code body} field whose state is {@link FieldState#REQUIRED_DEFAULT}
     * or {@link FieldState#REQUIRED_FIXED}, carries a literal {@code value} (a parameter-routed
     * default -- {@code value_param} -- has no literal to fill in with, and is left absent, same as
     * today; a template's own value parameter is settled at application time, not something this
     * resolver has anywhere to get from yet), and isn't already present in {@code value}'s own
     * fields -- an instance is always free to write an explicit value for a defaulted field (only
     * {@code REQUIRED_FIXED} disallows changing it, and validating that identity-diagonal invariant
     * isn't attempted here, matching {@code DefinitionResolver}'s own tightening logic, which doesn't
     * check it either), so an already-present field is left completely alone, whatever it says.
     * Returns {@code value} itself, unchanged, if there's nothing to add.
     *
     * <p><b>Does not fix {@code uri_type}/{@code regex_type}</b>, despite their own {@code spec}
     * field being exactly this shape ({@code REQUIRED_FIXED}, e.g. {@code uri_type => ~text_type &
     * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc3986" ... } }) -- {@code
     * UriType}/{@code RegexType}'s own Java shape keeps {@code spec} *nested* inside a {@code
     * specification: AtomSpecification} field, not flat, so a synthesized flat {@code spec} entry
     * doesn't match anything {@code tson-bind} is looking for at the top level and is silently
     * ignored, the same as before this method existed. Those two stay hand-picked constants in
     * {@code MetaKernelBootstrapResolver} for that reason, not this one.
     */
    private static DataValue fillDefaultedFields(DataValue value, RecordBody body) {
        List<RecordValue.Field> existing = value.coreValue() instanceof RecordValue recordValue
                ? recordValue.fields() : List.of();
        Set<String> present = new LinkedHashSet<>();
        for (RecordValue.Field field : existing) {
            present.add(field.name());
        }

        List<RecordValue.Field> defaulted = new ArrayList<>();
        for (RecordField field : body.fields()) {
            if (present.contains(field.name())
                    || (field.state() != FieldState.REQUIRED_DEFAULT && field.state() != FieldState.REQUIRED_FIXED)
                    || field.value().isEmpty()) {
                continue;
            }
            DataValue defaultValue = new DataValue(List.of(), Optional.empty(), toTokenValue(field.value().get()));
            defaulted.add(new RecordValue.Field(field.name(), new ScopedValue(Optional.empty(), defaultValue)));
        }
        if (defaulted.isEmpty()) {
            return value;
        }

        List<RecordValue.Field> allFields = new ArrayList<>(existing);
        allFields.addAll(defaulted);
        return new DataValue(value.annotations(), value.typeRef(), new RecordValue(allFields));
    }

    /** {@code schema.meta} has no dependency on {@code tson-parser}, so it can't reuse {@link TokenValue} directly (see {@link Token}'s own Javadoc) -- converts field by field instead. */
    private static TokenValue toTokenValue(Token token) {
        TokenForm form = switch (token.form()) {
            case UNQUOTED -> TokenForm.UNQUOTED;
            case SINGLE_LINE_QUOTED -> TokenForm.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> TokenForm.MULTI_LINE_QUOTED;
        };
        return new TokenValue(token.text(), form);
    }
}
