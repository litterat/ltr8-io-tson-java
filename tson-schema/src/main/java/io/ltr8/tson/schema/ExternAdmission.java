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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What a tag may name at a position typed by an extern ([TSON-SCHEMA] §7.9): every name of the enclosing scope, extern
 * or not, that reaches past its aliases and externs the entry the extern lands on or an entry IS-A it, or, where that
 * entry is a choice, selects one of its variants as a tag at a choice does (§5.4), the entries compared by identity. A
 * fact of the link, so every encoding's boundary reader and writer, and a choice of which the extern is a variant,
 * admit one set.
 *
 * @param landing     the entry the extern lands on
 * @param admitted    each admitted name, in the enclosing scope's declaration order, with the entry it reaches
 * @param selectsNone each name of the enclosing scope IS-A more than one variant of a choice the extern lands on and
 *                    reaching none, to those variants' names: it selects none (§5.4)
 */
public record ExternAdmission(ExternTarget.Landing landing, Map<String, Admitted> admitted,
                              Map<String, List<String>> selectsNone) {

    /** How an admitted name reaches what the extern lands on. */
    public enum By {
        /** It reaches the entry itself, and names it as the extern does. */
        TARGET,
        /** It reaches an entry IS-A the target (§7.2). */
        SUBTYPE,
        /** It selects a variant of the target, a choice: it reaches one, or an entry IS-A exactly one (§5.4). */
        VARIANT
    }

    /**
     * An admitted name: {@code terminal} past its alias chain in the enclosing scope, the landing of the extern it
     * reaches through ({@code through}, empty for a name that is no extern), the entry it reaches, as the schema that
     * declares it and its name there, and how that entry stands to the extern's own landing.
     */
    public record Admitted(String terminal, Optional<ExternTarget.Landing> through, String declaringSchema,
                           String entry, Top reached, By by) {

        /** The entry's identity: its declaring schema, canonical, and its path there ([TSON-SCHEMA] §8.3). */
        public String identity() {
            return declaringSchema + "#" + entry;
        }

        public boolean same() {
            return by == By.TARGET;
        }
    }

    /** The names {@code linked}'s scope admits at a position typed by its extern {@code extern}. */
    public static ExternAdmission of(TsonLinkedSchema linked, String extern) {
        ExternTarget.Landing landing = linked.landing(extern).orElseThrow(() ->
                new IllegalStateException("'" + extern + "' is an extern the linker attached to no entry, and a "
                        + "schema holding one does not link"));
        String identity = identityOf(landing);
        Map<String, String> variants = variantsOf(landing);
        Map<String, Admitted> admitted = new LinkedHashMap<>();
        Map<String, List<String>> selectsNone = new LinkedHashMap<>();
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
            // A supertype is a name of the namespace holding the entry, so its identity is read there.
            Function<String, String> originIn = through.<Function<String, String>>map(
                    end -> end.in()::declaringIdentity).orElse(linked::declaringIdentity);
            Set<String> above = new HashSet<>();
            TypeChain.supertypesOf(reached).forEach(supertype -> above.add(originIn.apply(supertype) + "#" + supertype));
            By by;
            if (reachedIdentity.equals(identity)) {
                by = By.TARGET;
            } else if (variants.containsKey(reachedIdentity)) {
                by = By.VARIANT; // a variant the tag names is selected before one its type only IS-A (§5.4)
            } else if (above.contains(identity)) {
                by = By.SUBTYPE;
            } else {
                List<String> among = variants.entrySet().stream().filter(variant -> above.contains(variant.getKey()))
                        .map(Map.Entry::getValue).toList();
                if (among.size() > 1) {
                    selectsNone.put(written, among);
                }
                if (among.size() != 1) {
                    continue;
                }
                by = By.VARIANT;
            }
            admitted.put(written, new Admitted(terminal, through, declaring, entry, reached, by));
        }
        return new ExternAdmission(landing, admitted, selectsNone);
    }

    /**
     * Whether a value at a position typed by {@code definition} must name a subtype: an abstract record naming no
     * selectors, which has no direct instances and nothing to place a value by but its tag ([TSON-SCHEMA] §7.2).
     */
    public static boolean namesASubtype(Top definition) {
        return FamilySelectors.extensionOf(definition).filter(RecordExtensionType.ABSTRACT::equals).isPresent()
                && FamilySelectors.namesOf(definition).isEmpty();
    }

    /**
     * The identities of a choice's named variants, each past its hops in the namespace holding the choice, to the
     * variant's name as the choice writes it.
     */
    private static Map<String, String> variantsOf(ExternTarget.Landing landing) {
        if (!(landing.definition() instanceof ChoiceBody choice)) {
            return Map.of();
        }
        Map<String, String> variants = new LinkedHashMap<>();
        for (TypeExpr variant : choice.variants()) {
            if (variant instanceof TypeRef reference) {
                TsonLinkedSchema in = landing.in();
                variants.putIfAbsent(in.landing(reference.name()).map(ExternAdmission::identityOf).orElseGet(() -> {
                    String terminal = terminal(reference.name(), in.schema().entries()::get);
                    return in.declaringIdentity(terminal) + "#" + terminal;
                }), reference.name());
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
