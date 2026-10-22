package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.unicode.Normalization;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.reader.ValueIdentity;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What each {@code record.extension} member obliges, checked over the linked closure ([TSON-SCHEMA] §5.2, §5.7,
 * §5.9). The peer of {@link ChoiceDisjointness}, and a checker rather than a derivation: the fact is written by
 * the author's mark and lowered by the resolver, and what is left is whether the rest of the closure agrees with
 * it.
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
 *
 * <p><b>Extending a family is a declaration.</b> A family's members are open across schemas (§3.3.4), so a
 * member changes what every position typed by its base does, in every schema that imports it; and a member is
 * something a read reports, a tag names (§6.1.5) and a binding maps, all by name. An application at a use site
 * has only a minted name, which nothing may write (§8.2), so a member it produced could be selected and never
 * named. It is refused, and the author declares it: {@code dogs => dog_of<text>}. A minted entry that joins no
 * family -- an array, a set, a choice, an application of a family's base -- is untouched, and two schemas
 * minting one such form hold two entries that are the same type in every way a value can tell.
 */
final class RecordExtension {

    /**
     * One problem, and the entry it is reported against: one this schema declares or minted -- the linker
     * reports a minted one against the declaration that wrote it -- or {@code ""}, the schema itself, for what
     * its imports brought together.
     */
    record Violation(String entry, String message) {
    }

    private RecordExtension() {
    }

    /**
     * Every violation the closure shows, in a stable order. {@code localNames} is what this schema may be
     * blamed for: an imported entry was judged when its own schema linked, and reporting it here would stamp
     * this schema's identity on another document's mistake.
     *
     * <p><b>Every family in the closure is judged</b>, not only one with a part declared here. Two schemas
     * that each link cleanly can each add a member to one imported family, and a schema importing both holds a
     * family neither did: its merge is what put the two members together, so a collision between them is this
     * schema's, reported against the schema itself ({@code ""}) and naming where each member came from. A
     * collision inside one import cannot reach here, since that import was refused when it linked.
     */
    static List<Violation> check(Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                                 Map<String, TypeRef> applications, Set<String> localNames,
                                 Map<String, String> origins, Map<String, Normalization> enumForms) {
        List<Violation> violations = new ArrayList<>();
        Predicate<String> kernel = name -> TsonBundledSchemas.isMetaKernel(origins.get(name));
        for (String name : localNames) {
            Top def = merged.get(name);
            if (def != null && isUndeclaredMember(name, def, facts, applications, kernel)) {
                String base = nearest(TypeChain.supertypesOf(def), merged);
                String shownName = shown(name, merged, facts, applications);
                violations.add(new Violation(name, "'" + shownName + "' composes onto '"
                        + shown(base, merged, facts, applications) + "', so it is a member of that family, and a "
                        + "member is declared: a read reports the member it selects, a tag names it and a binding maps "
                        + "it, all by a name an application at a use site does not have (§3.3.4, §8.2). Declare it -- "
                        + "'my_name => " + shownName + "' -- and use that name in place of the application"));
                continue;
            }
            RecordBody record = def == null ? null : familyBodyOf(def);
            if (record != null) {
                checkDeclaration(name, def, record, merged, facts, violations);
            }
        }
        for (Map.Entry<String, Top> entry : merged.entrySet()) {
            Top def = entry.getValue();
            RecordBody base = familyBodyOf(def);
            if (base != null && FamilySelectors.dispatchesOnMembers(def)) {
                checkFamily(entry.getKey(), def, merged, facts, applications, localNames, origins, enumForms, violations);
            }
        }
        return violations;
    }

    /**
     * A record a use-site template application minted that composes onto another: a family member with no
     * declared name. Refused at minting for an expansion ({@code TemplateMaterialiser}), where the application is
     * in hand; what reaches here is the instantiation entry an expansion naming itself keeps, which has no source
     * position and records its application. A constructor (IS-A the kernel's {@code top}) is no member of anything.
     */
    private static boolean isUndeclaredMember(String name, Top def, Map<String, TsonSchema.EntryFacts> facts,
                                              Map<String, TypeRef> applications, Predicate<String> kernel) {
        TsonSchema.EntryFacts known = facts.getOrDefault(name, TsonSchema.EntryFacts.NONE);
        TypeRef application = applications.get(name);
        return known.position().isEmpty() && def instanceof RecordBody record
                && application != null && !application.arguments().isEmpty()
                && !record.supertypes().isEmpty() && !TypeKind.isConstructor(name, record, kernel);
    }

    /**
     * The nearest of {@code chain}'s members -- the first that no other member IS-A, which is a parent the
     * declaration wrote or reached through one hop -- for a message that names what an entry composes onto.
     */
    private static String nearest(List<String> chain, Map<String, Top> merged) {
        return chain.stream()
                .filter(member -> chain.stream().noneMatch(other -> !other.equals(member)
                        && TypeChain.supertypesOf(merged.get(other)).contains(member)))
                .findFirst().orElse(chain.getFirst());
    }



    // ── The declaration's own obligations ────────────────────────────────

    private static void checkDeclaration(String name, Top def, RecordBody record,
                                          Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                                                  List<Violation> violations) {
        checkNothingComposesOntoFinal(name, def, merged, facts, violations);

        // The *names* this declaration states, never fields resolved from its members: which fields a family
        // dispatches on is the base's own statement (§5.2), and a template nobody has applied yet has no
        // members to resolve against -- asking for fields here refused a correct marked template for having
        // no instantiations. It needs no inherited-selector exemption either: a member states no discriminators
        // of its own, where the per-field mark had to be cleared at each one to say the same thing.
        List<String> declared = FamilySelectors.namesOf(def);
        if (declared.isEmpty() || record.extension() != RecordExtensionType.ABSTRACT) {
            return;
        }
        // Each is checked as the base reads it: a template's selector is FIXED to its parameter where it is
        // written, and the base dispatches on it with the pin dropped.
        SequencedMap<String, RecordField> selectors = FamilySelectors.of(def);
        for (String selector : declared) {
            RecordField field = selectors.get(selector);
            if (field != null) {
                checkDiscriminatorField(name, selector, field, record.groups(), merged, facts, violations);
            }
        }
    }

    /**
     * §5.9's subtraction leaves the chain empty and mints no IS-A edge, which is the only thing FINAL
     * constrains, so a subtracted record is not refused here: the one operation §5.9 admits against a final type
     * names no parent at all.
     */
    private static void checkNothingComposesOntoFinal(String name, Top def,
            Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts, List<Violation> violations) {
        for (String supertype : TypeChain.supertypesOf(def)) {
            Top target = merged.get(ReferenceChain.terminal(supertype, merged));
            if (target instanceof RecordBody parent
                    && parent.extension() == RecordExtensionType.FINAL) {
                violations.add(new Violation(name, "'" + name + "' names '" + supertype + "' as a supertype, "
                        + "but '" + supertype + "' is final and admits none -- composition and refinement are "
                        + "both refused, in the declaring schema and in any that imports it. Subtraction is "
                        + "not: §5.9 empties the chain and mints no IS-A edge, which is the only "
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
    private static void checkDiscriminatorField(String name, String fieldName, RecordField field,
            List<FieldGroup> groups, Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                    List<Violation> violations) {
        // Group membership is checked first and alone: §5.11 forces a member optional, so the state rule
        // would fire too and report the symptom beside the cause. One mistake, one verdict.
        if (groups.stream().anyMatch(group -> group.hasMember(fieldName))) {
            violations.add(new Violation(name, "'" + name + "': discriminator field '" + fieldName
                    + "' is a member of a field group, whose members are mutually exclusive and uniformly "
                    + "optional (§5.11) -- so a conforming value may leave it out, and a selector that may be "
                    + "absent selects nothing. Declare it beside the group instead"));
        } else if (field.optional() || field.voidable() || field.role() != FieldRole.FREE) {
            violations.add(new Violation(name, "'" + name + "': discriminator field '" + fieldName
                    + "' is " + field.describe() + ", and must be required -- an optional selector leaves a value "
                    + "with nothing to dispatch on, and a pinned or defaulted one fixes in the base what each "
                    + "subtype has to state differently (§5.2, §5.7)"));
        }
        Optional<AtomType<?>> parser = parserFor(field, merged, facts);
        if (parser.isEmpty()) {
            violations.add(new Violation(name, "'" + name + "': discriminator field '" + fieldName
                    + "' is declared '" + field.type().name() + "', which is not an atom or an enum -- a "
                    + "subtype pins it with a bare token (§12.1), so a type no token denotes has no pin to be "
                    + "told apart by (§5.2)"));
        }
    }

    // ── The family's obligations, over the closure ───────────────────────

    private static void checkFamily(String base, Top def, Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
            Map<String, TypeRef> applications, Set<String> localNames, Map<String, String> origins,
            Map<String, Normalization> enumForms, List<Violation> violations) {
        // The same derivation the dispatchers read, never a second filter over the fields: a check that
        // decided "which fields select" differently from the read could pass a family no reader can place.
        // Each is checked as the base reads it: a template's selector is FIXED to its parameter where it is
        // written, and the base dispatches on it with the pin dropped.
        SequencedMap<String, RecordField> selectors = FamilySelectors.of(def);
        if (selectors.isEmpty() || selectors.values().stream().anyMatch(f -> parserFor(f, merged, facts).isEmpty())) {
            return; // already reported against the base, and a pin has nothing to be read at
        }
        Map<List<Object>, String> seen = new LinkedHashMap<>();
        for (String subtype : new LinkedHashSet<>(TypeChain.subtypesOf(def))) {
            Top sub = merged.get(subtype);
            // An undeclared member is refused on its own, and judging its pins too would report one mistake twice.
            // A record template IS-A the base is no member: its applications are, each pinned once closed.
            if (!(sub instanceof RecordBody record) || OpenEntry.isOpen(sub)
                    || isUndeclaredMember(subtype, sub, facts, applications,
                    name -> TsonBundledSchemas.isMetaKernel(origins.get(name)))) {
                continue;
            }
            List<Object> pins = pinsOf(base, subtype, selectors, record, merged, facts, localNames, enumForms, violations);
            if (pins == null) {
                continue;
            }
            String collision = seen.putIfAbsent(pins, subtype);
            if (collision != null) {
                boolean merge = !localNames.contains(subtype) && !localNames.contains(collision);
                String blamed = localNames.contains(subtype) ? subtype : localNames.contains(collision) ? collision : "";
                violations.add(new Violation(blamed, "'" + shown(subtype, merged, facts, applications) + "' and '"
                        + shown(collision, merged, facts, applications)
                        + "' pin the discriminator" + (selectors.size() > 1 ? "s" : "") + " of '" + base
                        + "' to the same "
                        + describe(selectors, pins) + " -- two subtypes a value cannot tell apart, so the "
                        + "family's mapping is not a function. "
                        + (merge ? "Each is declared by a schema this one imports ('" + origins.get(subtype)
                                + "' and '" + origins.get(collision) + "'), and importing both is what puts them in "
                                + "one family. " : "")
                        + "Pins are compared as values and not as tokens, so `= 255` and `= 0xFF` are one pin "
                        + "(§4.3) and `= 1` and `= 1.0` are one (§5.5)"));
            }
        }
    }

    /**
     * A member named the way the author wrote it: a minted entry as the application that produced it, which
     * is the same rule a reader's own messages follow. A family whose members are template applications
     * collides under names the resolver chose ({@code pet_of_cat_int32_1c52dc45}, §8.2), and those name
     * nothing an author can open.
     */
    private static String shown(String name, Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                                Map<String, TypeRef> applications) {
        Top definition = merged.get(name);
        return definition == null ? name : EntryDisplayName.of(name, definition,
                facts.getOrDefault(name, TsonSchema.EntryFacts.NONE), Optional.ofNullable(applications.get(name)),
                merged, other -> facts.getOrDefault(other, TsonSchema.EntryFacts.NONE),
                other -> Optional.ofNullable(applications.get(other)));
    }

    /**
     * One subtype's pins in the base's declaration order, or {@code null} where the subtype does not pin them
     * all -- reported, and left out of the distinctness comparison, a partial tuple being no key.
     *
     * <p><b>Read at the base's declared type</b>, which [TSON-JSON] §6.1.5 makes the dispatch rule: the base's
     * types are the one set known before a subtype is selected, so distinctness has to be judged in the same
     * terms a decoder will compare in. A subtype narrowing the field narrows a subset of that type.
     */
    private static List<Object> pinsOf(String base, String subtype, SequencedMap<String, RecordField> selectors,
            RecordBody record, Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts, Set<String> localNames,
            Map<String, Normalization> enumForms,
            List<Violation> violations) {
        List<Object> pins = new ArrayList<>(selectors.size());
        for (Map.Entry<String, RecordField> selector : selectors.entrySet()) {
            RecordField pinned = record.fields().get(selector.getKey());
            if (pinned == null || pinned.role() != FieldRole.FIXED || pinned.value().isEmpty()) {
                if (localNames.contains(subtype)) {
                    violations.add(new Violation(subtype, "'" + subtype + "' is a subtype of the sealed '"
                            + base + "' but does not pin its discriminator '" + selector.getKey() + "' -- every "
                            + "member of a sealed family states its own value for each selector, with `= `, "
                            + "and it is those values a position typed '" + base + "' dispatches on. "
                            + (pinned == null ? "The field is not restated here."
                                    : "It is " + pinned.describe() + " here, and a default is omissible so it "
                                            + "cannot dispatch.") + " (§5.2, §5.7)"));
                }
                return null;
            }
            Object value = decode(selector.getValue(), pinned.value().get(), merged, facts, enumForms);
            if (value == null) {
                return null; // the pin is not a value of the declared type -- checkFieldValue's verdict
            }
            pins.add(ValueIdentity.of(value));
        }
        return pins;
    }

    private static Object decode(RecordField selector, Token pin, Map<String, Top> merged, Map<String,
            TsonSchema.EntryFacts> facts,
                                 Map<String, Normalization> enumForms) {
        Optional<AtomType<?>> parser = parserFor(selector, merged, facts, enumForms);
        try {
            return parser.get() instanceof io.ltr8.tson.compiler.atom.TokenAtomType<?> formSensitive
                    ? formSensitive.read(new TokenValue(pin.text(), TokenForm.valueOf(pin.form().name())))
                    : parser.get().read(pin.text());
        } catch (AtomTypeException notThisType) {
            return null;
        }
    }

    /** The parser for a field's type at the end of its reference chain, empty where nothing reads a token. */
    private static Optional<AtomType<?>> parserFor(RecordField field, Map<String, Top> merged, Map<String,
            TsonSchema.EntryFacts> facts) {
        return parserFor(field, merged, facts, Map.of());
    }

    /** {@link #parserFor(RecordField, Map)}, an enum selector matching in its name type's form. */
    private static Optional<AtomType<?>> parserFor(RecordField field, Map<String, Top> merged, Map<String,
            TsonSchema.EntryFacts> facts,
                                                   Map<String, Normalization> enumForms) {
        if (!field.type().arguments().isEmpty()) {
            return Optional.empty();
        }
        String terminal = ReferenceChain.terminal(field.type().name(), merged);
        Top target = merged.get(terminal);
        if (target == null || OpenEntry.isOpen(target) || target instanceof Reference) {
            return Optional.empty();
        }
        return AtomParsers.forType(target, enumForms.getOrDefault(terminal, Normalization.NONE));
    }

    /**
     * The record body whose family rules this entry is subject to, or {@code null} where it has none.
     *
     * <p><b>A record template is a family base and is judged as one</b> (§5.10). Its own fields may still
     * name its parameters, so the body checked here is assembled from the facts it states as a base: ABSTRACT,
     * the {@code discriminators} it names, and each selector at the type the base declares it. Skipping it
     * instead -- a guard on {@code parameters().isEmpty()} alone -- would accept a marked template with no
     * family check at all: no rule that every member pins every selector, and no
     * rule that the pins are pairwise distinct. An unchecked family is worse than a refused one.
     *
     * <p><b>The selector derivation is the reader's own</b> ({@code FamilySelectors}), so the check and the
     * dispatch cannot disagree about which fields a family is selected by -- a check that passed a family
     * the read could not place is the defect one derivation per consumer produces.
     *
     * <p>A held template has none: it is no type until applied (§5.10), nothing can stand at it, and its
     * instantiations are judged as the closed records they are.
     */
    private static RecordBody familyBodyOf(Top def) {
        if (!OpenEntry.isFamilyBase(def)) {
            return def instanceof RecordBody record && !OpenEntry.isOpen(def) ? record : null;
        }
        // The selectors come from `FamilySelectors`, the one derivation both encodings read.
        return new RecordBody(FamilySelectors.of(def), List.of(),
                FamilySelectors.extensionOf(def).orElseThrow(), FamilySelectors.namesOf(def),
                java.util.Optional.empty());
    }

    /** The colliding pin, named the way an author reads it: one value, or the tuple the selectors form. */
    private static String describe(SequencedMap<String, RecordField> selectors, List<Object> pins) {
        if (selectors.size() == 1) {
            return "value " + pins.get(0);
        }
        StringBuilder rendered = new StringBuilder("combination (");
        int i = 0;
        for (String selector : selectors.keySet()) {
            rendered.append(i == 0 ? "" : ", ").append(selector).append(": ").append(pins.get(i));
            i++;
        }
        return rendered.append(")").toString();
    }
}
