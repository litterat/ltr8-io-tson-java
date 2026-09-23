package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.reader.ValueIdentity;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TemplateBody;
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
 * <p><b>A selector implies {@code abstract}, and states the dispatch by itself.</b> A field written
 * {@code =?} says its members pin it, so the record is the base they are selected from and has no values of
 * its own -- which the declaration must say, since instantiability is not derivable from a field. Whether the
 * family is tag-dispatched or member-dispatched is *not* a second mark: it is whether any field carries the
 * spelling, read off the body the way {@code choice.disjoint} is.
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
            RecordBody record = def == null ? null : familyBodyOf(def, merged);
            if (record != null) {
                checkDeclaration(name, def, record, merged, violations);
            }
        }
        for (Map.Entry<String, TypeDefinition> entry : merged.entrySet()) {
            TypeDefinition def = entry.getValue();
            RecordBody base = familyBodyOf(def, merged);
            if (base != null && FamilySelectors.dispatchesOnMembers(def)
                    && touchesLocally(entry.getKey(), def, localNames)) {
                checkFamily(entry.getKey(), def, merged, localNames, violations);
            }
        }
        return violations;
    }

    // ── The declaration's own obligations ────────────────────────────────

    private static void checkDeclaration(String name, TypeDefinition def, RecordBody record,
                                          Map<String, TypeDefinition> merged, List<Violation> violations) {
        checkNothingComposesOntoFinal(name, def, merged, violations);

        // The *names* this declaration states, never fields resolved from its members: which fields a family
        // dispatches on is the base's own statement (§5.2), and a template nobody has applied yet has no
        // members to resolve against -- asking for fields here refused a correct marked template for having
        // no instantiations. It needs no inherited-selector exemption either: a member states no discriminators
        // of its own, where the per-field mark had to be cleared at each one to say the same thing.
        List<String> declared = FamilySelectors.namesOf(def);
        List<RecordField> marked = declared.stream()
                .map(selector -> record.fields().stream().filter(f -> f.name().equals(selector)).findFirst())
                .flatMap(Optional::stream)
                .toList();
        if (declared.isEmpty() || record.extension() != RecordExtensionType.ABSTRACT) {
            return;
        }
        for (RecordField field : marked) {
            checkDiscriminatorField(name, field, record.groups(), merged, violations);
        }
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
                        + "but '" + supertype + "' is final and admits none -- composition and refinement are "
                        + "both refused, in the declaring schema and in any that imports it. Subtraction is "
                        + "not: §5.9 empties the contract index and mints no IS-A edge, which is the only "
                        + "thing final constrains (§5.2, §5.9)"));
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
        // Group membership is checked first and alone: §5.11 forces a member optional, so the state rule
        // would fire too and report the symptom beside the cause. One mistake, one verdict.
        if (groups.stream().anyMatch(group -> group.members().contains(field.name()))) {
            violations.add(new Violation(name, "'" + name + "': discriminator field '" + field.name()
                    + "' is a member of a field group, whose members are mutually exclusive and uniformly "
                    + "optional (§5.11) -- so a conforming value may leave it out, and a selector that may be "
                    + "absent selects nothing. Declare it beside the group instead"));
        } else if (field.optional() || field.voidable() || field.role() != FieldRole.FREE) {
            violations.add(new Violation(name, "'" + name + "': discriminator field '" + field.name()
                    + "' is " + field.describe() + ", and must be required -- an optional selector leaves a value "
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

    private static void checkFamily(String base, TypeDefinition def,
            Map<String, TypeDefinition> merged, Set<String> localNames, List<Violation> violations) {
        // The same derivation the dispatchers read, never a second filter over the fields: a check that
        // decided "which fields select" differently from the read could pass a family no reader can place.
        List<RecordField> selectors = FamilySelectors.of(def, merged);
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
                    violations.add(new Violation(blamed, "'" + shown(subtype, merged) + "' and '"
                            + shown(collision, merged)
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
     * A member named the way the author wrote it: a minted entry as the application that produced it, which
     * is the same rule a reader's own messages follow. A family whose members are template applications
     * collides under names the resolver chose ({@code pet_of_cat_int32_1c52dc45}, §8.2), and those name
     * nothing an author can open.
     */
    private static String shown(String name, Map<String, TypeDefinition> merged) {
        TypeDefinition definition = merged.get(name);
        return definition == null ? name : EntryDisplayName.of(name, definition, merged);
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
            if (pinned == null || pinned.role() != FieldRole.FIXED || pinned.value().isEmpty()) {
                if (localNames.contains(subtype)) {
                    violations.add(new Violation(subtype, "'" + subtype + "' is a subtype of the sealed '"
                            + base + "' but does not pin its discriminator '" + selector.name() + "' -- every "
                            + "member of a sealed family states its own value for each selector, with `= `, "
                            + "and it is those values a position typed '" + base + "' dispatches on. "
                            + (pinned == null ? "The field is not restated here."
                                    : "It is " + pinned.describe() + " here, and a default is omissible so it "
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

    /**
     * The record body whose family rules this entry is subject to, or {@code null} where it has none.
     *
     * <p><b>A marked template is a family base and is judged as one</b> ({@code SPEC-FEEDBACK.md} #13). Its
     * body is held text, so the body checked here is assembled from the two facts the entry states
     * structurally: the derived {@code extension}, and the {@code discriminators} it names. Skipping it
     * instead -- a guard on {@code parameters().isEmpty()} alone -- would accept a marked template with no
     * family check at all: no rule that every member pins every selector, and no
     * rule that the pins are pairwise distinct. An unchecked family is worse than a refused one.
     *
     * <p><b>The selector derivation is the reader's own</b> ({@code FamilySelectors}), so the check and the
     * dispatch cannot disagree about which fields a family is selected by -- a check that passed a family
     * the read could not place is the defect one derivation per consumer produces.
     *
     * <p>An <em>unmarked</em> template still has none: it is no type until applied (§5.10), nothing can
     * stand at it, and its instantiations are judged as the closed records they are.
     */
    private static RecordBody familyBodyOf(TypeDefinition def, Map<String, TypeDefinition> merged) {
        if (def.body() instanceof RecordBody record) {
            return def.parameters().isEmpty() ? record : null;
        }
        if (!(def.body() instanceof TemplateBody held) || held.extension().isEmpty()) {
            return null;
        }
        // The selectors come from `FamilySelectors`, the one derivation both encodings read -- never from
        // parsing the held text, which `tson-json` cannot do and §1.3 says no consumer should have to.
        return new RecordBody(List.of(), FamilySelectors.of(def, merged), List.of(),
                held.extension().get(), held.discriminators());
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
