package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.reader.ValueIdentity;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What each {@code record.extension} member obliges, checked over the linked closure ([TSON-SCHEMA] §5.2,
 * §5.7, §5.9; {@code SPEC-FEEDBACK.md} #10, #11). The peer of {@link ChoiceDisjointness}, and a checker rather
 * than a derivation: the fact is written by the author's mark and lowered by the resolver, and what is left is
 * whether the rest of the closure agrees with it.
 *
 * <p><b>Why here and not at resolution.</b> Every rule below needs a namespace the declaration does not have.
 * FINAL constrains whoever composes onto it, which may be another schema; the family rules range over {@code
 * subtypes}, which nothing populates until linking; and a discriminator field's type has to be followed to the
 * end of its reference chain. What <em>is</em> local -- two definition marks on one declaration, a mark
 * carrying a value, a mark on a non-record -- the resolver already refused while lowering.
 *
 * <p><b>The two marks are each other's condition, and the redundancy is for the author.</b> {@code
 * @discriminator} on a field requires {@code @sealed} on the declaration and {@code @abstract} forbids one, so
 * either rule alone would settle the other fact. Both are kept because a family is tag-dispatched or
 * member-dispatched, the two admit different documents, and the difference would otherwise be one word buried
 * in a field list: requiring the pair means removing a base's last discriminator fails at the schema that
 * changed rather than at every reader of an untagged document.
 */
final class RecordExtension {

    /** One problem, and the entry it is reported against -- which is always one this schema declares. */
    record Violation(String entry, String message) {
    }

    private RecordExtension() {
    }

    /**
     * Every violation the closure shows, in a stable order. {@code localNames} is what this schema may be
     * blamed for: an imported entry was judged when its own schema linked, and reporting it here would stamp
     * this schema's identity on another document's mistake.
     *
     * <p><b>A family is re-judged whenever any part of it is local</b>, base or subtype, which is not the same
     * as judging local entries. A schema importing a sealed family and adding one subtype has changed the
     * family: the new sibling can collide with an imported one, and only a closure holding both can see it.
     */
    static List<Violation> check(Map<String, TypeDefinition> merged, Set<String> localNames) {
        List<Violation> violations = new ArrayList<>();
        for (String name : localNames) {
            TypeDefinition def = merged.get(name);
            if (def != null && def.body() instanceof RecordBody record && def.parameters().isEmpty()) {
                checkDeclaration(name, def, record, merged, violations);
            }
        }
        for (Map.Entry<String, TypeDefinition> entry : merged.entrySet()) {
            TypeDefinition def = entry.getValue();
            if (def.body() instanceof RecordBody base && base.extension() == RecordExtensionType.SEALED
                    && def.parameters().isEmpty() && touchesLocally(entry.getKey(), def, localNames)) {
                checkFamily(entry.getKey(), def, base, merged, localNames, violations);
            }
        }
        return violations;
    }

    // ── The declaration's own obligations ────────────────────────────────

    private static void checkDeclaration(String name, TypeDefinition def, RecordBody record,
                                          Map<String, TypeDefinition> merged, List<Violation> violations) {
        checkNothingComposesOntoFinal(name, def, merged, violations);

        // §5.8 flattens an inherited field whole, the mark included, so a subtype's copy of its base's
        // selector is indistinguishable here from one the subtype declared. The rule is about the mark a
        // declaration *makes*: a field it inherits from a sealed supertype is that supertype's statement and
        // needs no mark of its own, where a genuinely new one starts a family and needs `@sealed` like any
        // other. Without the distinction every subtype of a sealed base would be refused for pinning it.
        List<RecordField> marked = record.fields().stream()
                .filter(RecordField::discriminator)
                .filter(field -> !inheritedSelector(def, field.name(), merged))
                .toList();
        if (record.extension() == RecordExtensionType.SEALED && marked.isEmpty()) {
            violations.add(new Violation(name, "'" + name + "' is @sealed but no field of it carries "
                    + "@discriminator -- a sealed family is dispatched on its own members, so there is nothing "
                    + "here to dispatch on. Mark the field subtypes pin, or write @abstract, whose subtypes are "
                    + "selected by the tag instead (§5.2)"));
        }
        if (!marked.isEmpty() && record.extension() != RecordExtensionType.SEALED) {
            violations.add(new Violation(name, "'" + name + "': field '" + marked.get(0).name() + "' carries "
                    + "@discriminator, so '" + name + "' must be @sealed"
                    + (record.extension() == RecordExtensionType.ABSTRACT
                            ? " -- @abstract is the tag-dispatched case and admits no discriminator, and the two"
                                    + " marks are each other's condition so that neither drifts from the other"
                            : ", which is the mark for a record dispatched on its own members")
                    + " (§5.2)"));
        }
        if (record.extension() != RecordExtensionType.SEALED) {
            return;
        }
        for (RecordField field : marked) {
            checkDiscriminatorField(name, field, record.groups(), merged, violations);
        }
    }

    /**
     * Whether a marked field came from a sealed supertype rather than from this declaration. Read off {@code
     * TypeDefinition.supertypes}, so a subtraction -- which empties the contract index (§5.9) -- leaves the
     * field this record's own to justify, which is right: the type is no longer in the family.
     */
    private static boolean inheritedSelector(TypeDefinition def, String field,
                                              Map<String, TypeDefinition> merged) {
        return def.supertypes().stream()
                .map(supertype -> merged.get(ReferenceChain.terminal(supertype, merged)))
                .anyMatch(parent -> parent != null && parent.body() instanceof RecordBody record
                        && record.extension() == RecordExtensionType.SEALED
                        && record.fields().stream()
                                .anyMatch(f -> f.name().equals(field) && f.discriminator()));
    }

    /**
     * §5.9's subtraction is what makes this read {@code TypeDefinition.supertypes} rather than the body's own
     * list: subtraction empties the contract index and leaves the body's authorial lineage in place, minting
     * no IS-A edge, which is the only thing FINAL constrains. Reading {@code RecordBody.supertypes} instead
     * would refuse the one operation §5.9 admits against a final type.
     */
    private static void checkNothingComposesOntoFinal(String name, TypeDefinition def,
            Map<String, TypeDefinition> merged, List<Violation> violations) {
        for (String supertype : def.supertypes()) {
            TypeDefinition target = merged.get(ReferenceChain.terminal(supertype, merged));
            if (target != null && target.body() instanceof RecordBody parent
                    && parent.extension() == RecordExtensionType.FINAL) {
                violations.add(new Violation(name, "'" + name + "' names '" + supertype + "' as a supertype, "
                        + "but '" + supertype + "' is @final and admits none -- composition and refinement are "
                        + "both refused, in the declaring schema and in any that imports it. Subtraction is "
                        + "not: §5.9 empties the contract index and mints no IS-A edge, which is the only "
                        + "thing @final constrains (§5.2, §5.9)"));
            }
        }
    }

    /**
     * §5.2's own rule for a field carrying a value, applied to a field that carries none. The base declares
     * the discriminator unpinned -- §5.7's identity diagonal forbids it pinning what its subtypes each pin
     * differently -- so nothing else asks whether its type could hold a pin at all, and a family whose
     * selector is a record would be discovered only by the subtypes failing one at a time.
     */
    private static void checkDiscriminatorField(String name, RecordField field, List<FieldGroup> groups,
            Map<String, TypeDefinition> merged, List<Violation> violations) {
        // Group membership is checked first and alone: §5.11 forces a member OPTIONAL, so the state rule
        // would fire too and report the symptom beside the cause. One mistake, one verdict.
        if (groups.stream().anyMatch(group -> group.members().contains(field.name()))) {
            violations.add(new Violation(name, "'" + name + "': discriminator field '" + field.name()
                    + "' is a member of a field group, whose members are mutually exclusive and uniformly "
                    + "optional (§5.11) -- so a conforming value may leave it out, and a selector that may be "
                    + "absent selects nothing. Declare it beside the group instead"));
        } else if (field.state() != FieldState.REQUIRED) {
            violations.add(new Violation(name, "'" + name + "': discriminator field '" + field.name()
                    + "' is " + field.state() + ", and must be REQUIRED -- an optional selector leaves a value "
                    + "with nothing to dispatch on, and a pinned or defaulted one fixes in the base what each "
                    + "subtype has to state differently (§5.2, §5.7)"));
        }
        Optional<AtomType<?>> parser = parserFor(field, merged);
        if (parser.isEmpty()) {
            violations.add(new Violation(name, "'" + name + "': discriminator field '" + field.name()
                    + "' is declared '" + field.type().name() + "', which is not an atom or an enum -- a "
                    + "subtype pins it with a bare token (§12.1), so a type no token denotes has no pin to be "
                    + "told apart by (§5.2)"));
        }
    }

    // ── The family's obligations, over the closure ───────────────────────

    private static void checkFamily(String base, TypeDefinition def, RecordBody body,
            Map<String, TypeDefinition> merged, Set<String> localNames, List<Violation> violations) {
        List<RecordField> selectors = body.fields().stream().filter(RecordField::discriminator).toList();
        if (selectors.isEmpty() || selectors.stream().anyMatch(f -> parserFor(f, merged).isEmpty())) {
            return; // already reported against the base, and a pin has nothing to be read at
        }
        Map<List<Object>, String> seen = new LinkedHashMap<>();
        for (String subtype : new LinkedHashSet<>(def.subtypes())) {
            TypeDefinition sub = merged.get(subtype);
            if (sub == null || !(sub.body() instanceof RecordBody record) || !sub.parameters().isEmpty()) {
                continue;
            }
            List<Object> pins = pinsOf(base, subtype, selectors, record, merged, localNames, violations);
            if (pins == null) {
                continue;
            }
            String collision = seen.putIfAbsent(pins, subtype);
            if (collision != null) {
                String blamed = localNames.contains(subtype) ? subtype
                        : localNames.contains(collision) ? collision : null;
                if (blamed != null) {
                    violations.add(new Violation(blamed, "'" + subtype + "' and '" + collision
                            + "' pin the discriminator" + (selectors.size() > 1 ? "s" : "") + " of '" + base
                            + "' to the same "
                            + describe(selectors, pins) + " -- two subtypes a value cannot tell apart, so the "
                            + "family's mapping is not a function. Pins are compared as values and not as "
                            + "tokens, so `= 255` and `= 0xFF` are one pin (§4.3) and `= 1` and `= 1.0` are one "
                            + "(§5.5)"));
                }
            }
        }
    }

    /**
     * One subtype's pins in the base's declaration order, or {@code null} where the subtype does not pin them
     * all -- reported, and left out of the distinctness comparison, a partial tuple being no key.
     *
     * <p><b>Read at the base's declared type</b>, which [TSON-JSON] §6.1.5 makes the dispatch rule: the base's
     * types are the one set known before a subtype is selected, so distinctness has to be judged in the same
     * terms a decoder will compare in. A subtype narrowing the field narrows a subset of that type.
     */
    private static List<Object> pinsOf(String base, String subtype, List<RecordField> selectors,
            RecordBody record, Map<String, TypeDefinition> merged, Set<String> localNames,
            List<Violation> violations) {
        List<Object> pins = new ArrayList<>(selectors.size());
        for (RecordField selector : selectors) {
            RecordField pinned = record.fields().stream()
                    .filter(f -> f.name().equals(selector.name())).findFirst().orElse(null);
            if (pinned == null || pinned.state() != FieldState.REQUIRED_FIXED || pinned.value().isEmpty()) {
                if (localNames.contains(subtype)) {
                    violations.add(new Violation(subtype, "'" + subtype + "' is a subtype of the sealed '"
                            + base + "' but does not pin its discriminator '" + selector.name() + "' -- every "
                            + "member of a sealed family states its own value for each selector, with `= `, "
                            + "and it is those values a position typed '" + base + "' dispatches on. "
                            + (pinned == null ? "The field is not restated here."
                                    : "It is " + pinned.state() + " here, and a default is omissible so it "
                                            + "cannot dispatch.") + " (§5.2, §5.7)"));
                }
                return null;
            }
            Object value = decode(selector, pinned.value().get(), merged);
            if (value == null) {
                return null; // the pin is not a value of the declared type -- checkFieldValue's verdict
            }
            pins.add(ValueIdentity.of(value));
        }
        return pins;
    }

    private static Object decode(RecordField selector, Token pin, Map<String, TypeDefinition> merged) {
        Optional<AtomType<?>> parser = parserFor(selector, merged);
        try {
            return parser.get() instanceof io.ltr8.tson.compiler.atom.TokenAtomType<?> formSensitive
                    ? formSensitive.read(new TokenValue(pin.text(), TokenForm.valueOf(pin.form().name())))
                    : parser.get().read(pin.text());
        } catch (AtomTypeException notThisType) {
            return null;
        }
    }

    /** The parser for a field's type at the end of its reference chain, empty where nothing reads a token. */
    private static Optional<AtomType<?>> parserFor(RecordField field, Map<String, TypeDefinition> merged) {
        if (!field.type().arguments().isEmpty()) {
            return Optional.empty();
        }
        String terminal = ReferenceChain.terminal(field.type().name(), merged);
        TypeDefinition target = merged.get(terminal);
        if (target == null || !target.parameters().isEmpty() || target.body() instanceof Reference) {
            return Optional.empty();
        }
        return AtomParsers.forType(terminal, target.body());
    }

    /** Whether this schema may be blamed for the family at all -- the base or any subtype declared here. */
    private static boolean touchesLocally(String base, TypeDefinition def, Set<String> localNames) {
        return localNames.contains(base) || def.subtypes().stream().anyMatch(localNames::contains);
    }

    /** The colliding pin, named the way an author reads it: one value, or the tuple the selectors form. */
    private static String describe(List<RecordField> selectors, List<Object> pins) {
        if (selectors.size() == 1) {
            return "value " + pins.get(0);
        }
        StringBuilder rendered = new StringBuilder("combination (");
        for (int i = 0; i < selectors.size(); i++) {
            rendered.append(i == 0 ? "" : ", ").append(selectors.get(i).name()).append(": ").append(pins.get(i));
        }
        return rendered.append(")").toString();
    }
}
