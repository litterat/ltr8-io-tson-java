package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What a tag may name at a position typed by an extern ([TSON-SCHEMA] §7.9): every name of the enclosing scope, extern
 * or not, that reaches past its aliases and externs the entry the extern lands on, an entry IS-A it, or a variant of
 * it where it is a choice, the entries compared by identity. A fact of the link, so every encoding's boundary reader
 * and writer admit one set.
 *
 * @param landing  the entry the extern lands on
 * @param admitted each admitted name, in the enclosing scope's declaration order, with the entry it reaches
 */
public record ExternAdmission(ExternTarget.Landing landing, Map<String, Admitted> admitted) {

    /**
     * An admitted name: {@code terminal} past its alias chain in the enclosing scope, the landing of the extern it
     * reaches through ({@code through}, empty for a name that is no extern), the entry it reaches, as the schema that
     * declares it and its name there, and whether that is the extern's own landing ({@code same}).
     */
    public record Admitted(String terminal, Optional<ExternTarget.Landing> through, String declaringSchema,
                           String entry, Top reached, boolean same) {

        /** The entry's identity: its declaring schema, canonical, and its path there ([TSON-SCHEMA] §8.3). */
        public String identity() {
            return declaringSchema + "#" + entry;
        }
    }

    /** The names {@code linked}'s scope admits at a position typed by its extern {@code extern}. */
    public static ExternAdmission of(TsonLinkedSchema linked, String extern) {
        ExternTarget.Landing landing = linked.landing(extern).orElseThrow(() ->
                new IllegalStateException("'" + extern + "' is an extern the linker attached to no entry, and a "
                        + "schema holding one does not link"));
        String identity = identityOf(landing);
        Set<String> variants = variantsOf(landing);
        Map<String, Admitted> admitted = new LinkedHashMap<>();
        for (String written : linked.schema().entries().keySet()) {
            String terminal = terminal(written, linked.schema().entries()::get);
            Optional<ExternTarget.Landing> through = linked.landing(written);
            Top reached = through.map(ExternTarget.Landing::definition)
                    .orElse(linked.schema().entries().get(terminal));
            if (reached == null || through.isEmpty() && reached instanceof Extern) {
                continue;
            }
            String declaring = through.map(end -> end.in().declaringIdentity(end.name()))
                    .orElseGet(() -> linked.declaringIdentity(terminal));
            String entry = through.map(ExternTarget.Landing::name).orElse(terminal);
            String reachedIdentity = declaring + "#" + entry;
            Function<String, String> originIn = through.<Function<String, String>>map(
                    end -> end.in()::declaringIdentity).orElse(linked::declaringIdentity);
            boolean same = reachedIdentity.equals(identity);
            if (!same && !variants.contains(reachedIdentity) && TypeChain.supertypesOf(reached).stream()
                    .noneMatch(supertype -> identity.equals(originIn.apply(supertype) + "#" + supertype))) {
                continue;
            }
            admitted.put(written, new Admitted(terminal, through, declaring, entry, reached, same));
        }
        return new ExternAdmission(landing, admitted);
    }

    /**
     * Whether a value at a position typed by {@code definition} must name a subtype: an abstract record naming no
     * selectors, which has no direct instances and nothing to place a value by but its tag ([TSON-SCHEMA] §7.2).
     */
    public static boolean namesASubtype(Top definition) {
        return FamilySelectors.extensionOf(definition).filter(RecordExtensionType.ABSTRACT::equals).isPresent()
                && FamilySelectors.namesOf(definition).isEmpty();
    }

    /** The identities of a choice's named variants, each past its hops in the namespace holding the choice. */
    private static Set<String> variantsOf(ExternTarget.Landing landing) {
        if (!(landing.definition() instanceof ChoiceBody choice)) {
            return Set.of();
        }
        Set<String> variants = new HashSet<>();
        for (TypeExpr variant : choice.variants()) {
            if (variant instanceof TypeRef reference) {
                TsonLinkedSchema in = landing.in();
                variants.add(in.landing(reference.name()).map(ExternAdmission::identityOf).orElseGet(() -> {
                    String terminal = terminal(reference.name(), in.schema().entries()::get);
                    return in.declaringIdentity(terminal) + "#" + terminal;
                }));
            }
        }
        return variants;
    }

    private static String identityOf(ExternTarget.Landing landing) {
        return landing.in().declaringIdentity(landing.name()) + "#" + landing.name();
    }

    /**
     * The name at the end of {@code name}'s alias chain in {@code entries}, or the name the walk stopped at where it
     * reaches nothing or closes on itself ([TSON-SCHEMA] §8.3).
     */
    static String terminal(String name, Function<String, ? extends Top> entries) {
        Set<String> walked = new LinkedHashSet<>();
        String current = name;
        while (walked.add(current) && entries.apply(current) instanceof Alias alias) {
            current = alias.target().name();
        }
        return current;
    }
}
