package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.compiler.reader.ValueIdentity;
import io.ltr8.tson.compiler.resolver.HeldBody;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What an enum's {@code type} obliges ([TSON-SCHEMA] §7.4): that it names a text family, that each member is a
 * value of it, and that no two members are one value under its equality. The peer of {@link RecordExtension} in
 * shape: a checker over the linked closure that hands back violations and leaves reporting to the linker.
 *
 * <p><b>Why the linker and not the body.</b> {@link EnumBody} holds {@code type} as a name, and what the name
 * denotes -- which family, which parser, which equality -- lives in a namespace the body cannot see: the
 * schema's own, where an author-written {@code type} resolves, or the governing meta's, where a value the
 * constructor pinned ({@code enum}'s {@code type: identifier}) was written. The linked closure is the first
 * place both are present.
 *
 * <p><b>A text family is an atom-family instance whose constructor IS-A {@code text_type}</b> -- one hop through
 * the instance's {@code source}, since construction transfers no supertypes and a family instance is IS-A
 * nothing. An enum's members are texts under a naming vocabulary's equality; a value set on any other family is
 * that family's own {@code members} facet.
 */
final class EnumLabels {

    private static final String TEXT_TYPE = "text_type";
    private static final String IDENTIFIER_TYPE = "identifier_type";
    private static final String TYPE = "type";

    /** One problem, and the entry it is reported against -- always one this schema declares. */
    record Violation(String entry, String message) {
    }

    private EnumLabels() {
    }

    /**
     * Whether {@code enumeration}'s members are names: its {@code type} is an identifier family. A type that
     * resolves to nothing is taken to have names, the stricter reading -- the unresolved name is reported on its
     * own -- so every per-name rule still applies.
     */
    static boolean membersAreNames(TypeDefinition enumeration, Map<String, TypeDefinition> merged,
                                   Function<String, TypeDefinition> structure) {
        if (!(enumeration.body() instanceof EnumBody body)) {
            return false;
        }
        return labelType(enumeration, body, merged, structure)
                .map(label -> isFamily(label.definition(), IDENTIFIER_TYPE, label.lookup()))
                .orElse(true);
    }

    /** Every violation among {@code localNames}, in their order. */
    static List<Violation> check(Map<String, TypeDefinition> merged, Set<String> localNames,
                                 Function<String, TypeDefinition> structure) {
        List<Violation> violations = new ArrayList<>();
        for (String name : localNames) {
            TypeDefinition definition = merged.get(name);
            if (definition != null && definition.body() instanceof EnumBody body) {
                check(name, definition, body, merged, structure).ifPresent(violations::add);
            }
        }
        return List.copyOf(violations);
    }

    private static Optional<Violation> check(String name, TypeDefinition definition, EnumBody body,
                                             Map<String, TypeDefinition> merged,
                                             Function<String, TypeDefinition> structure) {
        Optional<Label> resolved = labelType(definition, body, merged, structure);
        if (resolved.isEmpty()) {
            return Optional.of(new Violation(name, "'" + name + "': its type '" + body.type()
                    + "' names nothing in scope (§7.4)"));
        }
        Label label = resolved.get();
        if (!isFamily(label.definition(), TEXT_TYPE, label.lookup())) {
            return Optional.of(new Violation(name, "'" + name + "': its type '" + body.type() + "' is not a text "
                    + "family -- an enum's labels are drawn from an atom-family instance whose constructor IS-A "
                    + "text_type (§7.4). A value set on another family is that family's own 'members' facet: "
                    + "'!integer ^ { members: [80 443] }', not an enum"));
        }
        Optional<AtomType<?>> parser = AtomParsers.forType(label.definition().body());
        if (parser.isEmpty()) {
            return Optional.empty(); // a text family this library has no parser for: nothing to judge members by
        }
        boolean names = isFamily(label.definition(), IDENTIFIER_TYPE, label.lookup());
        Map<Object, String> seen = new HashMap<>();
        for (String member : body.members()) {
            Object value;
            try {
                value = parser.get().read(member);
            } catch (AtomTypeException refused) {
                return Optional.of(new Violation(name, "'" + name + "': member '" + member + "' is not a value of "
                        + "its type '" + body.type() + "': " + refused.getMessage()
                        + (names ? " -- members that are any text are a '!text_enum [...]'" : "")));
            }
            String earlier = seen.putIfAbsent(ValueIdentity.of(value), member);
            if (earlier != null) {
                return Optional.of(new Violation(name, "'" + name + "': members '" + earlier + "' and '" + member
                        + "' are one value of its type '" + body.type() + "' (§7.4)"));
            }
        }
        return Optional.empty();
    }

    /** A resolved label type, and the namespace it resolved in -- where its own constructor is looked up. */
    private record Label(TypeDefinition definition, Function<String, TypeDefinition> lookup) {
    }

    /**
     * {@code type} resolved: for the value the enum's constructor pinned, in the governing meta's namespace, where
     * that value was written -- so {@code !enum [A B]} names the kernel's {@code identifier} whatever the schema
     * declares or imports; otherwise in the schema's own namespace, where an author-written one resolves.
     */
    private static Optional<Label> labelType(TypeDefinition enumeration, EnumBody body,
                                             Map<String, TypeDefinition> merged,
                                             Function<String, TypeDefinition> structure) {
        Function<String, TypeDefinition> local = merged::get;
        if (pinned(enumeration, body, fallingBackTo(local, structure)) && structure.apply(body.type()) != null) {
            return Optional.of(new Label(structure.apply(ReferenceChain.terminal(body.type(), structure)), structure));
        }
        if (merged.containsKey(body.type())) {
            return Optional.of(new Label(merged.get(ReferenceChain.terminal(body.type(), local)),
                    fallingBackTo(local, structure)));
        }
        return Optional.empty();
    }

    /**
     * Whether {@code type} is the value {@code enumeration}'s constructor pins -- found through its {@code source},
     * and through a template's held body to the constructor it applies, since an entry instantiating
     * {@code names => <M> !enum [a b M]} records the template as its source and not {@code enum}.
     */
    private static boolean pinned(TypeDefinition enumeration, EnumBody body, Function<String, TypeDefinition> lookup) {
        Optional<String> head = enumeration.source().map(source -> source.name());
        Set<String> seen = new HashSet<>();
        while (head.isPresent() && seen.add(head.get())) {
            TypeDefinition constructor = lookup.apply(head.get());
            if (constructor != null && constructor.body() instanceof TemplateBody held) {
                head = HeldBody.of(held).application().typeRef();
                continue;
            }
            return constructor != null && constructor.body() instanceof RecordBody record && record.fields().stream()
                    .filter(field -> field.name().equals(TYPE) && field.role() == FieldRole.FIXED)
                    .flatMap(field -> field.value().stream())
                    .anyMatch(value -> value.text().equals(body.type()));
        }
        return false;
    }

    private static Function<String, TypeDefinition> fallingBackTo(Function<String, TypeDefinition> primary,
                                                                  Function<String, TypeDefinition> fallback) {
        return name -> {
            TypeDefinition found = primary.apply(name);
            return found != null ? found : fallback.apply(name);
        };
    }

    /**
     * Whether {@code instance} is an atom-family instance built by {@code family} or by a constructor IS-A it --
     * a refinement records its instance's constructor as its own {@code source}, so this answers for
     * {@code !identifier ^ { ... }} as for {@code identifier}.
     */
    private static boolean isFamily(TypeDefinition instance, String family, Function<String, TypeDefinition> lookup) {
        if (instance == null || instance.kind() != TypeKind.ATOM || instance.supertypes().contains("top")
                || instance.source().isEmpty()) {
            return false;
        }
        String constructor = instance.source().get().name();
        if (constructor.equals(family)) {
            return true;
        }
        TypeDefinition built = lookup.apply(constructor);
        return built != null && built.supertypes().contains(family);
    }
}
