package io.ltr8.unicode;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Skeleton distinctness over a set of names: whether two names in one set share a UTS #39
 * {@link Confusables#skeleton}, and so read alike while differing.
 *
 * <p><b>The set is the caller's, and it is what makes the question decidable.</b> UTS #39's confusable detection
 * is a relation between strings and answers nothing about a single identifier; over a closed set of names -- the
 * fields of one record, the members of one enum -- it answers exactly. The set may be known whole
 * ({@link #firstCollision}) or met one name at a time ({@link Scope}).
 *
 * <p>Because it is a relation it has no false positives on a lone name: {@code id_пользователя} collides
 * with nothing and passes. That is the property a per-name restriction level ({@link RestrictionLevel}) cannot
 * have.
 */
public final class ConfusableNames {

    private ConfusableNames() {
    }

    /**
     * Up to this many names are compared pairwise rather than through a {@link Scope}: at most 120 string
     * comparisons, against a map, its table and an entry per name.
     */
    private static final int PAIRWISE = 16;

    /**
     * A pair of names in {@code names} that share a skeleton, or empty when every name is distinguishable --
     * the earlier name first, and a name equal to an earlier one colliding with nothing, as {@link Scope#add}.
     *
     * <p><b>A small scope is compared pairwise.</b> The common scope is one record's field names, a handful,
     * checked once per record read -- and a skeleton is free for a name that maps nothing,
     * which is most of them, so a map built per record was nearly the whole cost of the rule. Pairwise, a
     * record costs one array of its names and their skeletons; past {@link #PAIRWISE} names the map's linear
     * time wins and a {@link Scope} is used.
     */
    public static Optional<Collision> firstCollision(Collection<String> names) {
        int size = names.size();
        if (size < 2) {
            return Optional.empty();
        }
        if (size > PAIRWISE) {
            Scope scope = new Scope();
            for (String name : names) {
                Optional<Collision> collision = scope.add(name);
                if (collision.isPresent()) {
                    return collision;
                }
            }
            return Optional.empty();
        }
        // Each name at an even index and its skeleton at the odd one after it: one array per scope.
        String[] seen = new String[2 * size];
        int count = 0;
        for (String name : names) {
            String skeleton = Confusables.skeleton(name);
            for (int i = 0; i < count; i += 2) {
                if (seen[i + 1].equals(skeleton) && !seen[i].equals(name)) {
                    return Optional.of(new Collision(seen[i], name));
                }
            }
            seen[count] = name;
            seen[count + 1] = skeleton;
            count += 2;
        }
        return Optional.empty();
    }

    /**
     * One scope filled a name at a time, for a reader that meets the names as it goes -- the keys of a map, say
     * -- and can report a pair at the second name's own position.
     */
    public static final class Scope {

        private final Map<String, String> bySkeleton = new HashMap<>();

        /** An empty scope. */
        public Scope() {
        }

        /**
         * Adds {@code name}, answering the earlier name it reads alike with, or empty. A name equal to one already
         * added collides with nothing: a repeat is a duplicate, which is another rule's to report.
         */
        public Optional<Collision> add(String name) {
            String previous = bySkeleton.putIfAbsent(Confusables.skeleton(name), name);
            return previous == null || previous.equals(name) ? Optional.empty()
                    : Optional.of(new Collision(previous, name));
        }
    }

    /**
     * Two names a reader cannot tell apart. {@code first} is the one that appeared earlier, so a message can
     * report the second as the offender and the first as what it collides with.
     */
    public record Collision(String first, String second) {

        /** The clause a diagnostic appends, naming both and saying what the reader sees. */
        public String describe() {
            return "'" + second + "' is confusable with '" + first + "' -- the two are different names that "
                    + "read alike (UTS #39), so one of them must be renamed";
        }
    }
}
