package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.FieldRole;

import java.util.List;
import java.util.Optional;

/**
 * [TSON-SCHEMA] §5.2's field-state table: a field's presence marker and value modifier decide the facts a
 * {@code record_field} stores -- {@code optional}, {@code voidable}, {@code role} -- and what value, if any,
 * rides with them. The table is closed and consults nothing but the two marks the author wrote, which is why
 * it can be answered before a field's type is known.
 *
 * <p><b>Two phases ask it.</b> {@link SchemaDesugarer} asks when it rewrites a record template's body into
 * the {@code !record { fields: [ ... ] }} §5.2 says it denotes, and writes the answer as wire fields;
 * {@code DefinitionResolver} asks when it resolves a closed record body, and writes the answer into a
 * {@code RecordField}. One table, so the spellings and the errors around them cannot drift apart between a
 * template and the closed record beside it.
 */
final class FieldModifiers {

    private FieldModifiers() {
    }

    /**
     * What §5.2 makes of one field's marks. {@code value} is absent for the fields that carry none -- a
     * {@link FieldRole#FREE} field, and {@code = _}, which is FIXED with no value: pinned to {@code _}, whose
     * output encoding is a {@code record_field} without a {@code value} member (§8.1).
     *
     * <p><b>A token naming a type parameter rides {@code value} like any other</b> (§5.7's "Open modifiers"),
     * and nothing here labels it as one: §8.1's shadowing rule -- a token is a parameter exactly when its
     * text resolves into the enclosing entry's own {@code parameters} -- is what tells the two apart wherever
     * the question is asked. What a parametric modifier does decide is the facts beside it, and {@link #of}
     * decides them there.
     */
    record Resolved(boolean optional, boolean voidable, FieldRole role, Optional<TokenValue> value) {

        static final Resolved REQUIRED = new Resolved(false, false, FieldRole.FREE, Optional.empty());
    }

    /**
     * Whether this field is written {@code =?} -- a discriminator, whose name the enclosing record collects
     * into {@code record.discriminators}. A group member cannot be one: §5.11 makes a value modifier a parse
     * error on a member, so the spelling does not reach one, and a member reached by refinement is the
     * linker's to refuse.
     */
    static boolean discriminates(FieldDef field) {
        return field.modifier().filter(m -> m.value() instanceof FieldDef.Modifier.Value.Deferred).isPresent();
    }

    /**
     * §5.2's table for one field. {@code optional} is the presence marker -- the entry's own {@code ?}, or
     * (for a tightening entry that restates only a modifier) the one it inherits. {@code parameters} is the
     * enclosing declaration's type-parameter list, empty outside a template.
     *
     * @throws SchemaValidationException for the five spellings §5.2 rules out: {@code ~ _} on any field,
     *     {@code = _} on a required one, a default on an optional one, a pin to a value on an optional one,
     *     and {@code =?} on an optional one.
     */
    static Resolved of(String fieldName, boolean optional, Optional<FieldDef.Modifier> modifier,
            List<String> parameters) {
        if (modifier.isEmpty()) {
            return optional ? new Resolved(true, true, FieldRole.FREE, Optional.empty()) : Resolved.REQUIRED;
        }
        boolean fixed = modifier.get().kind() == FieldDef.Modifier.Kind.FIXED;

        if (modifier.get().value() instanceof FieldDef.Modifier.Value.Deferred) {
            // `=?`: a discriminator. The field is required and unpinned here -- §5.7's identity diagonal
            // forbids the base pinning what each member pins differently -- and the name is collected into
            // the enclosing `record.discriminators` by the phase that holds the record.
            if (optional) {
                throw new SchemaValidationException("field '" + fieldName + "' is a discriminator ('=?') and "
                        + "optional -- a selector that may be absent selects nothing, so a discriminator is "
                        + "REQUIRED (§5.2). Drop the '?'");
            }
            return Resolved.REQUIRED;
        }

        if (modifier.get().value() instanceof FieldDef.Modifier.Value.Absent) {
            // `field: type? = _`: pinned to `_`, so the field is omitted or written as `_`, never a value.
            if (!fixed) {
                throw new SchemaValidationException("field '" + fieldName + "' uses '~ _' -- a required "
                        + "field cannot fall back to not-being-filled, so an absent default is a resolver "
                        + "error on any field (§5.2). Write 'type?' for a field that may be absent");
            }
            if (!optional) {
                throw new SchemaValidationException("field '" + fieldName + "' fixes a required field to "
                        + "absent ('= _') -- a field cannot be both required and forbidden from being present "
                        + "(§5.2). Make it optional ('" + fieldName + ": type? = _') to forbid its value "
                        + "while keeping it in the contract");
            }
            return new Resolved(true, true, FieldRole.FIXED, Optional.empty());
        }

        TokenValue token = ((FieldDef.Modifier.Value.Literal) modifier.get().value()).token();
        if (optional && !fixed) {
            throw new SchemaValidationException("field '" + fieldName + "' gives an optional field a "
                    + "default ('type? ~ value') -- a default implies the field is always present, which "
                    + "contradicts optional (§5.2). Use 'type ~ value' for a fallback, or 'type?' for absence");
        }
        // §5.7's "Open modifiers": a parametric modifier is fixed at no declaration -- the value arrives at
        // application, and every application MUST bind every parameter -- so `= P` is a required FREE field
        // carrying the parameter until materialisation closes it, whatever the presence marker says, and
        // `~ P` is an ordinary default.
        if (fixed && parameters.contains(token.text())) {
            return new Resolved(false, false, FieldRole.FREE, Optional.of(token));
        }
        if (optional) {
            // A pin names the only value the field admits, and on a voidable field `_` would be admitted
            // beside it -- the one combination the stored facts refuse, since a written `_` is decided before
            // the pin is consulted.
            throw new SchemaValidationException("field '" + fieldName + "' pins an optional field to a value "
                    + "('type? = value') -- a pin names the only value the field admits, and `_` is not it. "
                    + "Use 'type = value' for a pin that omission supplies, or 'type?' for a free field");
        }
        return new Resolved(true, false, fixed ? FieldRole.FIXED : FieldRole.DEFAULT, Optional.of(token));
    }
}
