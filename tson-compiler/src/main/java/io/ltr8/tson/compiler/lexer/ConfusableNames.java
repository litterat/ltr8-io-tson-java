package io.ltr8.tson.compiler.lexer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §9.4's confusability, decided over a closed set of names rather than over one name
 * ({@code SPEC-FEEDBACK.md} #3 Step 2): no two names in the same scope may share a UTS #39
 * {@link Confusables#skeleton} .
 *
 * <p><b>Scope is what makes this decidable, and TSON has scopes where a general-purpose language does
 * not.</b> UTS #39's own confusable detection is a relation between strings and so answers nothing about a
 * single identifier — which is why §9.4 could only say "consider it". The series names the sets itself: the
 * fields of one record, the members of one enum, the variants of one choice, the declared names of one
 * schema, and the merged namespace at an {@code !!import}. Each is small, closed, and known at the moment
 * the check runs.
 *
 * <p>Because it is a relation it has no false positives on a lone name: {@code id_пользователя} collides
 * with nothing and passes. That is the property a per-name restriction level cannot have, and the reason
 * this is the rule and that is an option (#3 Step 4).
 */
public final class ConfusableNames {

    private ConfusableNames() {
    }

    /** A pair of names in {@code names} that share a skeleton, or empty when every name is distinguishable. */
    public static Optional<Collision> firstCollision(Iterable<String> names) {
        Map<String, String> bySkeleton = new HashMap<>();
        for (String name : names) {
            String previous = bySkeleton.putIfAbsent(Confusables.skeleton(name), name);
            if (previous != null && !previous.equals(name)) {
                return Optional.of(new Collision(previous, name));
            }
        }
        return Optional.empty();
    }

    /** The same over a plain list, for call sites that already have one. */
    public static Optional<Collision> firstCollision(List<String> names) {
        return firstCollision((Iterable<String>) names);
    }

    /**
     * Two names a reader cannot tell apart. {@code first} is the one that appeared earlier, so a message can
     * report the second as the offender and the first as what it collides with -- the shape §2.6 already
     * uses for a repeated map key.
     */
    public record Collision(String first, String second) {

        /** The clause a diagnostic appends, naming both and saying what the reader sees. */
        public String describe() {
            return "'" + second + "' is confusable with '" + first + "' -- the two are different names that "
                    + "read alike (UTS #39), so one of them must be renamed";
        }
    }
}
