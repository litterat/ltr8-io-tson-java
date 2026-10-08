package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.unicode.Normalization;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TextFamily;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Where an enum's {@code type} resolves ([TSON-SCHEMA] §7.4): for the value the enum's constructor pinned, in the
 * governing meta's namespace, where that value was written -- so {@code !enum [A B]} names the kernel's
 * {@code identifier} whatever the schema declares or imports; otherwise in the schema's own namespace, where an
 * author-written one resolves. Shared by the linker's enum checks and by materialisation, which checks a template's
 * value argument of an enum type before linking has run.
 */
public final class EnumLabelType {

    private static final String TYPE = "type";

    private EnumLabelType() {
    }

    /** The label type's definition, and the namespace its own names resolve in. */
    public record Resolved(TypeDefinition definition, Function<String, TypeDefinition> lookup) {
    }

    /**
     * {@code enumeration}'s label type, given the schema's own namespace and the governing meta's. Empty for a body
     * that is not an enum and for a {@code type} that resolves in neither.
     */
    public static Optional<Resolved> of(TypeDefinition enumeration, Function<String, TypeDefinition> local,
                                        Function<String, TypeDefinition> structure) {
        if (!(enumeration.body() instanceof EnumBody body)) {
            return Optional.empty();
        }
        if (pinned(enumeration, body, fallingBackTo(local, structure)) && structure.apply(body.type()) != null) {
            return Optional.of(new Resolved(structure.apply(ReferenceChain.terminal(body.type(), structure)),
                    structure));
        }
        if (local.apply(body.type()) != null) {
            return Optional.of(new Resolved(local.apply(ReferenceChain.terminal(body.type(), local)),
                    fallingBackTo(local, structure)));
        }
        return Optional.empty();
    }

    /**
     * The form {@code enumeration} matches its members in: its label type's {@code normalization}
     * ([TSON-SCHEMA] §5.5). {@code NONE} for a body that is not an enum, a type that resolves to nothing, or one
     * that is not a text family -- each refused or reported on its own.
     */
    public static Normalization form(TypeDefinition enumeration, Function<String, TypeDefinition> local,
                                     Function<String, TypeDefinition> structure) {
        return of(enumeration, local, structure)
                .map(label -> label.definition() != null && label.definition().body() instanceof TextFamily family
                        ? family.normalization()
                        : Normalization.NONE)
                .orElse(Normalization.NONE);
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
}
