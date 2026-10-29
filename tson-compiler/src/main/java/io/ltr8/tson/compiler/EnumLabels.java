package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.unicode.Normalization;
import io.ltr8.tson.compiler.reader.ValueIdentity;
import io.ltr8.tson.compiler.resolver.NameType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What an enum's {@code name_type} obliges ([TSON-SCHEMA] §7.4): that it names an identifier family -- a member of
 * {@code identifier_kind}, the kind the slot is typed by ({@link NameType#admits}) -- that each member is a value of
 * it, and that no two members are one value under its equality. {@link FieldNames} holds a
 * record's field names to the same three; the peer of {@link RecordExtension} in shape: a checker over the linked
 * closure that hands back violations and leaves reporting to the linker.
 *
 * <p><b>Why the linker and not the body.</b> {@link EnumBody} holds {@code name_type} as a name, and what the name
 * denotes -- which family, which parser, which equality -- lives in a namespace the body cannot see: the
 * schema's one namespace, where an author-written {@code name_type} resolves and where the schema declaring the
 * constructor that pinned one ({@code enum}'s {@code name_type: identifier}) sits in the closure. The linked closure
 * is the first place it is whole.
 *
 * <p><b>An identifier family is a type built with {@code identifier_type}</b>, which is what membership of the
 * kernel's {@code identifier_kind} walks: an instance, a refinement of one, an instantiation of a template over the
 * constructor, and an alias of any of them. An enum's members are names under a naming vocabulary's equality, and
 * carry §8.2's per-name rules under its profile; a closed set of values on any other family, {@code text} included,
 * is that family's own {@code members} facet.
 */
final class EnumLabels {

    /** One problem, and the entry it is reported against -- always one this schema declares. */
    record Violation(String entry, String message) {
    }

    private EnumLabels() {
    }

    /**
     * The form {@code enumeration} matches its members in: its name type's {@code normalization}
     * ([TSON-SCHEMA] §5.5), followed to the closure entry the enum's constructor pinned.
     * {@code NONE} for a body that is not an enum, a type that resolves to nothing, or one that is not a text
     * family -- each refused or reported on its own.
     */
    static Normalization labelForm(Top enumeration, Optional<String> constructor, Map<String, Top> merged,
                                   Function<String, Top> structure) {
        return NameType.form(enumeration, constructor, merged::get, structure);
    }

    /** Every violation among {@code localNames}, in their order. */
    static List<Violation> check(Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                                 Set<String> localNames, Function<String, Top> structure) {
        List<Violation> violations = new ArrayList<>();
        for (String name : localNames) {
            if (merged.get(name) instanceof EnumBody body) {
                check(name, body, TsonSchemaLinker.constructorOf(name, body, facts), merged, structure)
                        .ifPresent(violations::add);
            }
        }
        return List.copyOf(violations);
    }

    private static Optional<Violation> check(String name, EnumBody body, Optional<String> constructor,
                                             Map<String, Top> merged,
                                             Function<String, Top> structure) {
        Optional<NameType.Resolved> resolved = NameType.of(body, constructor, merged::get, structure);
        if (resolved.isEmpty()) {
            return Optional.of(new Violation(name, "'" + name + "': its name_type '" + body.nameType()
                    + "' names nothing in scope (§7.4)"));
        }
        NameType.Resolved label = resolved.get();
        if (!NameType.admits(body, constructor, label, merged::get, structure)) {
            return Optional.of(new Violation(name, "'" + name + "': its name_type '" + body.nameType() + "' is not "
                    + "an identifier family -- an enum's members are names drawn from an atom-family instance whose "
                    + "constructor IS-A identifier_type (§7.4). A closed set of values on another family is that "
                    + "family's own 'members' facet: '!text ^ { members: [...] }' or '!integer ^ { members: [80 443] }',"
                    + " not an enum"));
        }
        if (label.definition() instanceof IdentifierType identifiers) {
            Optional<String> form = NameType.formRefusal(identifiers);
            if (form.isPresent()) {
                return Optional.of(new Violation(name, "'" + name + "': its name_type '" + body.nameType() + "' "
                        + "cannot judge names -- " + form.get()));
            }
        }
        Optional<AtomType<?>> parser = AtomParsers.forType(label.definition());
        if (parser.isEmpty()) {
            return Optional.empty(); // a text family this library has no parser for: nothing to judge members by
        }
        // Under the kernel's identifier, the default, a refusal speaks of identifiers as any name's would; only an
        // author's own name type is named, since its rules are the ones the author has to look up.
        boolean identifier = NameType.isIdentifier(label, structure);
        String ofType = identifier ? "an identifier" : "a value of its name_type '" + body.nameType() + "'";
        Map<Object, String> seen = new HashMap<>();
        for (String member : body.members()) {
            Object value;
            try {
                value = parser.get().read(member);
            } catch (AtomTypeException refused) {
                return Optional.of(new Violation(name, "'" + name + "': member '" + member + "' is not " + ofType
                        + ": " + refused.getMessage()
                        + (identifier ? " -- a closed set of text that is no name is '!text ^ { members: [...] }'" : "")));
            }
            String earlier = seen.putIfAbsent(ValueIdentity.of(value), member);
            if (earlier != null) {
                return Optional.of(new Violation(name, "'" + name + "': members '" + earlier + "' and '" + member
                        + "' are one " + (identifier ? "identifier" : "value of its name_type '" + body.nameType() + "'")
                        + " (§7.4)"));
            }
        }
        return Optional.empty();
    }
}
