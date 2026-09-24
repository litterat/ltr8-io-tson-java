package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.FieldRole;

import java.util.List;
import java.util.Optional;

/**
 * [TSON-SCHEMA] §5.2's field spellings: one slot per question a field answers, and this table turns the three
 * marks into the facts a {@code record_field} stores. The name's {@code ?} makes the field {@code optional}
 * (the key may be omitted); the type's {@code ?} makes it {@code voidable} (a written {@code _} is admitted);
 * the modifier gives the {@code role} and its value. No mark answers two questions, so the facts follow the
 * marks one to one, and what this table adds is the spellings it refuses. It consults nothing but the marks
 * the author wrote, which is why it can be answered before a field's type is known.
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
     * What §5.2 makes of one field's marks. {@code value} is absent exactly where the role is {@link
     * FieldRole#FREE}, with one exception: a held template body's parametric {@code = P}, which is FREE and
     * carries the parameter until materialisation closes it to FIXED.
     *
     * <p><b>A token naming a type parameter rides {@code value} like any other</b> (§5.7's "Open modifiers"),
     * and nothing here labels it as one: §8.1's shadowing rule -- a token is a parameter exactly when its
     * text resolves into the enclosing entry's own {@code parameters} -- is what tells the two apart wherever
     * the question is asked. What a parametric modifier does decide is the role beside it, and {@link #of}
     * decides it there.
     */
    record Resolved(boolean optional, boolean voidable, FieldRole role, Optional<TokenValue> value) {
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
     * §5.2's table for one field. {@code omittable} is the name's {@code ?}; {@code voidable} is the type's, or
     * (for a tightening entry that restates only a modifier, whose type slot is elided) the one the field it
     * tightens carries. {@code parameters} is the enclosing declaration's type-parameter list, empty outside a
     * template.
     *
     * @throws SchemaValidationException for the spellings the marks can form and §5.2 refuses: a default on a
     *     key that is always written, a pin on a voidable type, a pin or default to {@code _}, and a
     *     discriminator that may be omitted or written {@code _}.
     */
    static Resolved of(String fieldName, boolean omittable, boolean voidable, Optional<FieldDef.Modifier> modifier,
            List<String> parameters) {
        if (modifier.isEmpty()) {
            return new Resolved(omittable, voidable, FieldRole.FREE, Optional.empty());
        }
        boolean fixed = modifier.get().kind() == FieldDef.Modifier.Kind.FIXED;

        if (modifier.get().value() instanceof FieldDef.Modifier.Value.Deferred) {
            // `=?`: a discriminator. The field is required and unpinned here -- §5.7's identity diagonal
            // forbids the base pinning what each member pins differently -- and the name is collected into
            // the enclosing `record.discriminators` by the phase that holds the record. Its omission, `_` and
            // value answers are each member's pin, so the base admits exactly the one shape a pin fills.
            if (omittable || voidable) {
                throw new SchemaValidationException("field '" + fieldName + "' is a discriminator ('=?') that "
                        + "may be " + (omittable ? "omitted" : "written as '_'") + " -- a selector that is "
                        + "absent selects nothing, so a discriminator is written on an unmarked name and a type "
                        + "without '?' (§5.2)");
            }
            return new Resolved(false, false, FieldRole.FREE, Optional.empty());
        }

        if (modifier.get().value() instanceof FieldDef.Modifier.Value.Absent) {
            throw new SchemaValidationException("field '" + fieldName + "' " + (fixed ? "pins" : "defaults")
                    + " itself to '_' -- a " + (fixed ? "pin" : "default") + " names a value of the field's "
                    + "type, and '_' is none of them (§5.2). Write '" + fieldName + "?: void?' for a field "
                    + "that may be omitted or written as '_' and nothing else");
        }

        TokenValue token = ((FieldDef.Modifier.Value.Literal) modifier.get().value()).token();
        if (!fixed && !omittable) {
            throw new SchemaValidationException("field '" + fieldName + "' gives a default to a key that is "
                    + "always written -- a default is what omission yields, and an unmarked name says the key "
                    + "is never omitted (§5.2). Write '" + fieldName + "?: type ~ value'");
        }
        if (fixed && voidable) {
            // A pin names the only value the field admits, and on a voidable field `_` would be admitted
            // beside it -- the one combination the stored facts refuse, since a written `_` is decided before
            // the pin is consulted.
            throw new SchemaValidationException("field '" + fieldName + "' pins a voidable type ('type? = "
                    + "value') -- a pin names the only value the field admits, and '_' is not it (§5.2). Drop "
                    + "the type's '?'");
        }
        // §5.7's "Open modifiers": a parametric modifier is fixed at no declaration -- the value arrives at
        // application, and every application MUST bind every parameter -- so `= P` is a FREE field carrying
        // the parameter until materialisation closes it to FIXED, and `~ P` is an ordinary default.
        if (fixed && parameters.contains(token.text())) {
            return new Resolved(omittable, false, FieldRole.FREE, Optional.of(token));
        }
        return new Resolved(omittable, voidable, fixed ? FieldRole.FIXED : FieldRole.DEFAULT, Optional.of(token));
    }
}
