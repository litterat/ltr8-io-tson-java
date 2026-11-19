package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.reader.DiscriminationClass;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.IncompleteEntry;
import io.ltr8.tson.schema.meta.ParametricEntry;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.ValueEntry;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * One namespace's judgements through its externs ([TSON-SCHEMA] §5.13, §8.3): an extern is another name for the
 * foreign entry it lands on, so every question asked of it is asked of that entry, in the namespace that holds it, and
 * every comparison is by identity -- the declaring schema and the path -- never by spelling, the two namespaces
 * spelling their names independently.
 */
final class ExternHops {

    private final Function<String, Top> entries;
    private final Function<String, String> originOf;
    private final Map<String, ExternTarget> targets;

    ExternHops(Function<String, Top> entries, Function<String, String> originOf, Map<String, ExternTarget> targets) {
        this.entries = entries;
        this.originOf = originOf;
        this.targets = targets;
    }

    static ExternHops of(TsonLinkedSchema linked) {
        return new ExternHops(linked.schema().entries()::get, linked::originOf, linked.externs());
    }

    /** Whether the namespace holds an extern at all: every answer below is the plain one where it holds none. */
    boolean any() {
        return !targets.isEmpty();
    }

    /** The foreign entry {@code name} lands on past its alias hops, or empty where it names no attached extern. */
    Optional<ExternTarget.Landing> landing(String name) {
        String terminal = AliasChain.terminal(name, entries);
        if (!(entries.apply(terminal) instanceof Extern)) {
            return Optional.empty();
        }
        ExternTarget target = targets.get(terminal);
        return target == null ? Optional.empty() : target.landing();
    }

    /**
     * The identity of the type {@code name} denotes: the schema declaring the entry at the end of its hops, alias and
     * extern alike, and its path there.
     */
    String identity(String name) {
        return landing(name).map(end -> end.declaringSchema() + "#" + end.name()).orElseGet(() -> {
            String terminal = AliasChain.terminal(name, entries);
            return originOf.apply(terminal) + "#" + terminal;
        });
    }

    /**
     * Whether {@code name}, landing through an extern, is a member of the reference over the constructor {@code of}
     * named in this namespace: the foreign entry's constructor, or one on that constructor's chain, is {@code of} by
     * identity ([TSON-SCHEMA] §4.2). Empty where {@code name} lands through no extern, the plain walk answering.
     */
    Optional<Boolean> membership(String name, String of) {
        return landing(name).map(end -> {
            Top definition = end.definition();
            if (ParametricEntry.isParametric(definition) || definition instanceof ValueEntry
                    || definition instanceof IncompleteEntry) {
                return false;
            }
            TsonLinkedSchema in = end.in();
            Function<String, Top> there = in.schema().entries()::get;
            String head = AliasChain.terminal(in.schema().factsOf(end.name()).constructor()
                    .orElseGet(() -> TsonSchema.constructorOf(definition)), there);
            Top headDefinition = there.apply(head);
            Function<String, String> declaredBy = in::originOf;
            if (headDefinition == null && TsonBundledSchemas.isKernel(originOf.apply(head))) {
                // A schema whose closure holds no kernel writes the processor's own kernel's forms (§3.3.1), which
                // this namespace holds.
                headDefinition = entries.apply(head);
                declaredBy = originOf;
            }
            Set<String> constructors = new LinkedHashSet<>();
            constructors.add(declaredBy.apply(head) + "#" + head);
            if (headDefinition != null && !TypeChain.isConstructor(head, headDefinition,
                    entry -> TsonBundledSchemas.isKernel(in.originOf(entry)) || TsonBundledSchemas.isKernel(
                            originOf.apply(entry)))) {
                return false;
            }
            if (headDefinition != null) {
                for (String supertype : TypeChain.supertypesOf(headDefinition)) {
                    constructors.add(declaredBy.apply(supertype) + "#" + supertype);
                }
            }
            return constructors.contains(identity(of));
        });
    }

    /**
     * The discrimination class of what {@code name} lands on, classified in the namespace holding it; empty in the
     * outer optional where {@code name} lands through no extern.
     */
    Optional<Optional<DiscriminationClass>> discrimination(String name) {
        return landing(name).map(end -> DiscriminationClass.of(end.name(), end.in().schema().entries()::get));
    }
}
