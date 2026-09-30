package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The label type of an enum ([TSON-SCHEMA] §7.4): the {@code T} of the {@code enum_of<T>} application its
 * constructor is, read from that constructor's {@code source}. An enum's members are labels drawn from a naming
 * vocabulary, and whether the vocabulary is an identifier family decides which of [TSON-DATA] §8.2's rules reach
 * them.
 *
 * <p><b>The bound on {@code T} is checked here, by name.</b> {@code T} must be an atom-family instance whose
 * constructor IS-A {@code text_type}: an enum's members are texts under a naming vocabulary's equality, and a
 * value set on any other family is that family's {@code members} facet. The relation is family membership --
 * one hop through the argument's {@code source} -- and not IS-A, since a family instance is a construction and
 * records no supertypes; §5.10's bound resolves in the type-name namespace, where a constructor cannot be named.
 * So the kernel declares {@code enum_of => <T> atom & { members: set<T> }} unbounded, and this check stands in
 * for the bound, identifying the kernel's {@code enum_of} by name and origin the way §4.2 dispatches
 * {@code unit}'s instances by name.
 *
 * <p><b>Why at linking.</b> The check follows the argument to the end of its reference chain and then to its
 * constructor, which may live in another schema or in the governing meta; the linked closure is the first place
 * all of those are present.
 */
final class EnumLabels {

    static final String ENUM_OF = "enum_of";
    private static final String TEXT_TYPE = "text_type";
    private static final String IDENTIFIER_TYPE = "identifier_type";
    private static final String KERNEL = CanonicalIdentity.canonicalize(TsonBundledSchemas.META_KERNEL_ID);

    /** One refused application, and the entry it is reported against -- always one this schema declares. */
    record Violation(String entry, String message) {
    }

    private EnumLabels() {
    }

    /**
     * Whether an enum whose constructor is {@code constructor} has names for members: its label type is an
     * identifier family. An enum whose constructor records no {@code enum_of} application is taken to have
     * names, which is the stricter reading -- every per-name rule applies.
     */
    static boolean membersAreNames(String constructor, Function<String, TypeDefinition> lookup) {
        return labelType(constructor, lookup).map(label -> isFamily(label, IDENTIFIER_TYPE, lookup)).orElse(true);
    }

    /**
     * Every application of the kernel's {@code enum_of} among {@code localNames} whose argument is not a text
     * family. The kernel's {@code enum_of} is the one whose origin is meta-kernel; a schema declaring a
     * template of the same name without importing the kernel declares its own, which this does not constrain.
     */
    static List<Violation> check(Map<String, TypeDefinition> merged, Set<String> localNames,
                                 Map<String, String> origins, Function<String, TypeDefinition> lookup) {
        if (!KERNEL.equals(origins.get(ENUM_OF))) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        for (String name : localNames) {
            TypeDefinition definition = merged.get(name);
            Optional<TypeRef> application = definition == null ? Optional.empty() : definition.source()
                    .filter(source -> source.name().equals(ENUM_OF) && !source.arguments().isEmpty());
            if (application.isEmpty()) {
                continue;
            }
            TypeArgument argument = application.get().arguments().getFirst();
            if (!(argument instanceof TypeArgument.Ref ref) || !ref.ref().arguments().isEmpty()
                    || !isFamily(ref.ref().name(), TEXT_TYPE, lookup)) {
                violations.add(new Violation(name, "'" + name + "' applies " + ENUM_OF + "<"
                        + describe(argument) + ">, but an enum's labels are drawn from a text family: " + ENUM_OF
                        + "'s argument must be an atom-family instance whose constructor IS-A " + TEXT_TYPE
                        + " (§7.4). A value set on another family is that family's own 'members' facet -- "
                        + "'!integer ^ { members: [80 443] }', not an enum"));
            }
        }
        return List.copyOf(violations);
    }

    /** {@code T} of the {@code enum_of<T>} application {@code constructor} is, if it is one. */
    private static Optional<String> labelType(String constructor, Function<String, TypeDefinition> lookup) {
        TypeDefinition definition = lookup.apply(constructor);
        if (definition == null) {
            return Optional.empty();
        }
        return definition.source()
                .filter(source -> source.name().equals(ENUM_OF) && source.arguments().size() == 1)
                .map(source -> source.arguments().getFirst())
                .filter(argument -> argument instanceof TypeArgument.Ref)
                .map(argument -> ((TypeArgument.Ref) argument).ref().name());
    }

    /**
     * Whether {@code name}, followed to the end of its reference chain, is an atom-family instance built by
     * {@code family} or by a constructor IS-A it -- a refinement records its instance's constructor as its own
     * {@code source}, so this answers for {@code !identifier ^ { ... }} as for {@code identifier}.
     */
    private static boolean isFamily(String name, String family, Function<String, TypeDefinition> lookup) {
        TypeDefinition instance = lookup.apply(ReferenceChain.terminal(name, lookup));
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

    private static String describe(TypeArgument argument) {
        return switch (argument) {
            case TypeArgument.Ref ref -> ref.ref().name();
            case TypeArgument.Value value -> value.value().text();
        };
    }
}
