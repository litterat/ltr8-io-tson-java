package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.FieldState;

import java.util.List;
import java.util.Optional;

/**
 * [TSON-SCHEMA] §5.2's field-state table: a field's presence marker and value modifier decide its {@link
 * FieldState} and what value, if any, rides with it. The table is closed and consults nothing but the two
 * marks the author wrote, which is why it can be answered before a field's type is known.
 *
 * <p><b>Two phases ask it.</b> {@link SchemaDesugarer} asks when it rewrites a record template's body into
 * the {@code !record { fields: [ ... ] }} §5.2 says it denotes, and writes the answer as wire fields;
 * {@code DefinitionResolver} asks when it resolves a closed record body, and writes the answer into a
 * {@code RecordField}. One table, so the six spellings and the errors around them cannot drift apart
 * between a template and the closed record beside it.
 */
final class FieldModifiers {

    private FieldModifiers() {
    }

    /**
     * What §5.2 makes of one field's marks. {@code value} is absent for the two states that carry none --
     * plain {@code REQUIRED}/{@code OPTIONAL}, and {@code OPTIONAL_FIXED}'s {@code = _} spelling, whose
     * output encoding is a {@code record_field} without a {@code value} member (§8.1).
     *
     * <p><b>A token naming a type parameter rides {@code value} like any other</b> (§5.7's "Open modifiers"),
     * and nothing here labels it as one: §8.1's shadowing rule -- a token is a parameter exactly when its
     * text resolves into the enclosing entry's own {@code parameters} -- is what tells the two apart wherever
     * the question is asked. What a parametric modifier does decide is the {@code state} beside it, and
     * {@link #of} decides it there.
     */
    record Resolved(FieldState state, Optional<TokenValue> value) {
    }

    /**
     * §5.2's table for one field. {@code optional} is the presence axis -- the entry's own {@code ?}, or
     * (for a tightening entry that restates only a modifier) the state it inherits. {@code parameters} is
     * the enclosing declaration's type-parameter list, empty outside a template.
     *
     * @throws TsonSchemaValidationException for the three spellings §5.2 rules out: {@code ~ _} on any
     *     field, {@code = _} on a required one, and a default on an optional one.
     */
    static Resolved of(String fieldName, boolean optional, Optional<FieldDef.Modifier> modifier,
            List<String> parameters) {
        if (modifier.isEmpty()) {
            return new Resolved(optional ? FieldState.OPTIONAL : FieldState.REQUIRED, Optional.empty());
        }
        boolean fixed = modifier.get().kind() == FieldDef.Modifier.Kind.FIXED;

        if (modifier.get().value() instanceof FieldDef.Modifier.Value.Absent) {
            // §5.2's sixth spelling, `field: type? = _`: OPTIONAL_FIXED carrying no value at all, so the
            // field MUST be omitted or written as `_`.
            if (!fixed) {
                throw new TsonSchemaValidationException("field '" + fieldName + "' uses '~ _' -- a required "
                        + "field cannot fall back to not-being-filled, so an absent default is a resolver "
                        + "error on any field (§5.2). Write 'type?' for a field that may be absent");
            }
            if (!optional) {
                throw new TsonSchemaValidationException("field '" + fieldName + "' fixes a required field to "
                        + "absent ('= _') -- a field cannot be both required and forbidden from being present "
                        + "(§5.2). Make it optional ('" + fieldName + ": type? = _') to forbid its value "
                        + "while keeping it in the contract");
            }
            return new Resolved(FieldState.OPTIONAL_FIXED, Optional.empty());
        }

        TokenValue token = ((FieldDef.Modifier.Value.Literal) modifier.get().value()).token();
        if (optional && !fixed) {
            throw new TsonSchemaValidationException("field '" + fieldName + "' gives an optional field a "
                    + "default ('type? ~ value') -- a default implies the field is always present, which "
                    + "contradicts optional (§5.2). Use 'type ~ value' for a fallback, 'type?' for absence, "
                    + "or 'type? = value' for present-implies-value");
        }
        // §5.7's "Open modifiers": a parametric modifier lands in a REQUIRED-family state whatever the
        // presence axis says, because nothing is fixed at declaration -- the value arrives at application,
        // and every application MUST bind every parameter.
        if (parameters.contains(token.text())) {
            return new Resolved(fixed ? FieldState.REQUIRED : FieldState.REQUIRED_DEFAULT, Optional.of(token));
        }
        FieldState state = optional ? FieldState.OPTIONAL_FIXED
                : (fixed ? FieldState.REQUIRED_FIXED : FieldState.REQUIRED_DEFAULT);
        return new Resolved(state, Optional.of(token));
    }
}
