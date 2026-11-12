package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.compiler.resolver.KindMembers;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [TSON-SCHEMA] §5.2's rules for a dependent field, {@code v?: kind!}, checked at the declaration against the flattened
 * record: a field whose type is a {@code meta.dependent} in place, read per instance against the type its sibling's value
 * names. The linker asks {@link #check} of every closed record, so an open record's member is judged once its application
 * closes it.
 *
 * <ul>
 *   <li><b>The sibling is declared before the dependent field.</b> The fields are a telescope: a field depends only on
 *       the fields before it, in the order of {@code fields}, so no cycle can form and none is looked for. Flattening
 *       puts inherited fields first, so a base's sibling precedes a subtype's dependent field.</li>
 *   <li><b>The sibling is typed by a reference to types</b>: past its alias hops a reference ({@code meta.type_ref},
 *       {@code meta.ref<C>} over a constructor, or a refinement) whose {@code of} is a constructor or {@code top}, so
 *       every value it holds names a type. One that allows a sibling reference is admitted, as {@code
 *       record_field.type} is; a sibling reference as its value is refused where the dependent field is read.</li>
 *   <li><b>The sibling is there to read whenever the dependent field is</b>: required, defaulted, pinned, or a member
 *       of a field group with the dependent field optional, a group's own member being declared optional. A member is
 *       absent when another option is chosen, so a dependent field written without it is refused where it is read.
 *       A sibling that is optional with no default, or voidable, is refused, as is a member beside a required
 *       dependent field.</li>
 *   <li><b>A sibling's default or pin names a type a value can be read against</b>: a template, which no value is read
 *       against until it is applied, is refused, and so is a sibling reference. That it
 *       is a member of the sibling's reference is the field-value check's ({@code TsonSchemaLinker}).</li>
 *   <li><b>A dependent field is never voidable and carries no default or pin</b>: its type is not fixed at its
 *       declaration, so neither is the type a value of it would be read against.</li>
 * </ul>
 *
 * <p>A dependent field restated only as itself is {@link #restates}'s rule, which the restated-field check asks; a
 * sibling may be a discriminator, which the family rules admit as they do any required atom-typed field.
 */
final class DependentFields {

    private DependentFields() {
    }

    /** Refuses the first of {@code record}'s dependent fields, in declaration order, that breaks one of the rules. */
    static void check(String entryName, RecordBody record, Map<String, Top> namespace) {
        List<String> order = new ArrayList<>(record.fields().keySet());
        for (int at = 0; at < order.size(); at++) {
            String name = order.get(at);
            RecordField field = record.fields().get(name);
            if (field.sibling().isEmpty()) {
                continue;
            }
            String sibling = field.sibling().get();
            String dependent = "'" + entryName + "': field '" + name + "' is typed '" + field.type().text() + "'";
            if (field.voidable()) {
                throw new SchemaValidationException(dependent + ", a dependent field, and is voidable -- a dependent "
                        + "field may be optional but is never voidable: when present it has a value, read against the "
                        + "type its sibling names, and '_' is no value of that type (§5.2)");
            }
            if (field.value().isPresent()) {
                throw new SchemaValidationException(dependent + ", a dependent field, and carries "
                        + (field.role() == FieldRole.FIXED ? "a pin" : "a default") + " -- a dependent field's type is "
                        + "not fixed at its declaration, so neither is the type a value of it would be read against; "
                        + "pin the sibling '" + sibling + "' instead (§5.2)");
            }
            int siblingAt = order.indexOf(sibling);
            if (siblingAt < 0) {
                throw new SchemaValidationException(dependent + ", but '" + entryName + "' has no field '"
                        + sibling + "' -- a dependent type names a field of the same record, never a "
                        + "schema entry; it declares " + String.join(", ", order) + " (§5.2)");
            }
            if (siblingAt >= at) {
                throw new SchemaValidationException(dependent + ", but '" + sibling + "' is declared "
                        + (siblingAt == at ? "as the field itself" : "after it") + " -- a record's fields are a "
                        + "telescope, each depending only on the fields declared before it, so the sibling precedes "
                        + "its dependent field (§5.2)");
            }
            checkSibling(entryName, name, sibling, record, namespace);
        }
    }

    /** The rules a dependent field's sibling answers to: typed by a reference to types, and there to read. */
    private static void checkSibling(String entryName, String dependent, String sibling, RecordBody record,
                                     Map<String, Top> namespace) {
        RecordField field = record.fields().get(sibling);
        String about = "'" + entryName + "': field '" + dependent + "' takes its type from '" + sibling + "'";
        if (field.sibling().isPresent()) {
            throw new SchemaValidationException(about + ", which is itself a dependent field ('" + field.type().text()
                    + "') and names no types -- a sibling is typed by a reference whose values name types, "
                    + "meta.type_ref or a meta.ref<C> over a constructor (§5.2)");
        }
        if (!namesTypes(field.typeExpr(), namespace)) {
            throw new SchemaValidationException(about + ", which is typed '" + field.type().text() + "' -- a sibling "
                    + "is typed by a reference whose values name types, meta.type_ref, a meta.ref<C> over a "
                    + "constructor, or a refinement of either, or by what a type slot holds (meta.type_expr), "
                    + "so that every value it holds names a type to read '" + dependent
                    + "' against (§5.2)");
        }
        if (field.voidable()) {
            throw new SchemaValidationException(about + ", which is voidable -- '_' names no type, so a '" + dependent
                    + "' written beside it would have none to be read against; a sibling is there to read whenever "
                    + "its dependent field is (§5.2)");
        }
        boolean member = record.groups().stream().anyMatch(group -> group.hasMember(sibling));
        if (member && !record.fields().get(dependent).optional()) {
            throw new SchemaValidationException(about + ", which is a member of a field group, and '" + dependent
                    + "' is required -- a member is present only when its option is chosen and is never supplied, so "
                    + "'" + dependent + "' could have no type to be read against; a group member is the sibling of an "
                    + "optional dependent field alone (§5.2, §5.11)");
        }
        if (!member && field.optional() && field.value().isEmpty()) {
            throw new SchemaValidationException(about + ", which is optional with no default -- a '" + dependent
                    + "' written without it would have no type; a sibling is there to read whenever its dependent "
                    + "field is: required, defaulted, pinned, or a field group's member beside an optional dependent "
                    + "field (§5.2)");
        }
        field.value().ifPresent(value -> checkSiblingValue(about, field, value, namespace));
    }

    /**
     * A sibling's default or pin names a type a value can be read against. A template is refused: a template is no
     * type (§5.10), and reading never starts a materialisation.
     */
    private static void checkSiblingValue(String about, RecordField field, Token value, Map<String, Top> namespace) {
        String noun = field.role() == FieldRole.FIXED ? "pin" : "default";
        TypeRef type;
        try {
            type = ReferenceGrammar.parse(value.text());
        } catch (AtomParseException e) {
            return; // not a reference at all: the field-value check refuses it as a value of the wrong type
        }
        if (!type.arguments().isEmpty()) {
            return; // an application is judged a member, through the entry it denotes, by the field-value check
        }
        Top target = namespace.get(AliasChain.terminal(type.name(), namespace));
        if (target != null && OpenEntry.isOpen(target)) {
            throw new SchemaValidationException(about + ", whose " + noun + " '" + type.text() + "' is a template -- "
                    + "no value is read against a template until it is applied, so an instance omitting the sibling "
                    + "would have no type to read by; name a type, or an application this schema closes (§5.2)");
        }
    }

    /**
     * Whether a sibling typed {@code type} holds type names: a reference naming types, written by name or in place
     * ({@code meta.ref<C>}), or the kernel's {@code meta.type_expr}, whose names are
     * a reference naming types and whose other variant, a type written in place, names no entry -- a value is never
     * read against one, a value being admitted only on an atom or an enum.
     */
    private static boolean namesTypes(TypeExpr type, Map<String, Top> namespace) {
        Top target = switch (type) {
            // A closed record names no application bare; an open one is judged when it closes.
            case TypeRef reference when !reference.arguments().isEmpty() -> null;
            case TypeRef reference -> {
                String terminal = AliasChain.terminal(reference.name(), namespace);
                if (SLOT_CHOICES.contains(terminal)) {
                    yield namespace.get(KernelNames.of("type_ref"));
                }
                yield namespace.get(terminal);
            }
            case TypeInline inline -> inline.of();
        };
        return target instanceof ReferenceBody reference && KindMembers.namesTypes(reference.of().name(), namespace::get);
    }

    /** The kernel's choice a type slot is typed by: a type's name, or a type written in place. */
    private static final java.util.Set<String> SLOT_CHOICES = java.util.Set.of(KernelNames.of("type_expr"));

    /**
     * Why {@code restated} is no restatement of {@code inherited} where either is a dependent field, or empty where
     * neither is or the two agree: a dependent field is restated only as itself, its marks moving along §5.7's order as
     * any field's do. Its type stays the sibling reference on every member of the family, and pinning the sibling is
     * what fixes the type.
     */
    static Optional<String> restates(String fieldName, RecordField inherited, RecordField restated) {
        boolean wasDependent = inherited.sibling().isPresent();
        boolean isDependent = restated.sibling().isPresent();
        if (!wasDependent && !isDependent || inherited.type().equals(restated.type())) {
            return Optional.empty();
        }
        return Optional.of(wasDependent
                ? "field '" + fieldName + "' is the dependent field '" + inherited.type().text() + "' and is "
                        + "restated '" + restated.type().text() + "' -- a dependent field is restated only as itself; "
                        + "to fix its type, pin its sibling '" + inherited.sibling().get() + "' (§5.2)"
                : "field '" + fieldName + "' is typed '" + inherited.type().text() + "' and is restated as the "
                        + "dependent field '" + restated.type().text() + "', which is not '" + inherited.type().text()
                        + "' or IS-A it");
    }
}
