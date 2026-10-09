package io.ltr8.bind;

import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Resolves a schema/wire-format type name (e.g. {@code "integer_size"}) to the Java class a
 * {@link DataBindContext} should build a descriptor for -- the general form of the
 * "type-to-Class binding" problem every serialization library eventually needs: there is no
 * reflection API to enumerate "every class in a package," so this is necessarily a name lookup
 * (with whatever naming convention/namespace a caller's own classes follow), not an enumeration
 * of candidates. {@link DataBindContext#getDescriptor(String)} is the one place this gets
 * consulted, composing {@link #resolve} with {@link DataBindContext#getDescriptor(Class)} so a
 * caller gets a {@link DataClass} directly from a bare name.
 *
 * <p>{@link DefaultDataNameBinder} is the default implementation, a fixed namespace (or several)
 * plus an alias table. A caller with a different naming convention, or one that needs to resolve
 * against classes outside any fixed namespace at all, supplies their own implementation instead
 * via {@link DataBindContext.Builder#nameBinder(DataNameBinder)}.
 */
public interface DataNameBinder {

    /** @throws DataBindException if {@code schemaTypeName} has no matching Java class under this binder's own convention */
    Class<?> resolve(String schemaTypeName) throws DataBindException;

    /**
     * The names in {@code bindings} and no others -- for a caller that already knows which class each type
     * binds to, where the convention-based default would be guessing.
     *
     * <p>Not {@code bindings::get}. A {@link DataNameBinder} says "I cannot bind that" by throwing, where a
     * map returns {@code null} for whichever name the <em>document</em> chose, and a {@code null} carries no
     * account of why. The same trap {@code SchemaSource.ofMap} exists for, and the same fix: a miss names
     * what the map does hold, so the failure reads as a missing line of configuration rather than as a
     * mystery.
     *
     * <p>The map is copied, so a caller may keep and change theirs.
     */
    static DataNameBinder ofMap(Map<String, Class<?>> bindings) {
        Map<String, Class<?>> copy = Map.copyOf(bindings);
        return name -> {
            Class<?> bound = copy.get(name);
            if (bound == null) {
                throw new DataBindException("'" + name + "' is not bound: the binding map holds " + copy.keySet());
            }
            return bound;
        };
    }

    /**
     * This binder, falling back to {@code next} for a name it cannot bind -- composition rather than
     * replacement, so a caller adding their own names keeps whatever the one they compose over knows.
     *
     * <p><b>The first binder authors the failure.</b> When neither resolves the name, what a caller needs to
     * see is the line missing from <em>their</em> configuration, not that the backstop did not recognise it
     * either; the backstop's own message rides along as the cause.
     */
    default DataNameBinder orElse(DataNameBinder next) {
        return name -> {
            try {
                return resolve(name);
            } catch (DataBindException notMine) {
                try {
                    return next.resolve(name);
                } catch (DataBindException notTheirs) {
                    throw new DataBindException(notMine.getMessage(), notTheirs);
                }
            }
        };
    }

    /**
     * Mangles {@code schemaTypeName} (after applying {@code aliases}) from snake_case to
     * PascalCase and tries {@code Class.forName} against each of {@code packages} in turn,
     * first match wins.
     */
    class DefaultDataNameBinder implements DataNameBinder {
        private final Set<String> packages;
        private final Map<String, String> aliases;

        public DefaultDataNameBinder(Set<String> packages, Map<String, String> aliases) {
            this.packages = packages;
            this.aliases = aliases;
        }

        @Override
        public Class<?> resolve(String schemaTypeName) throws DataBindException {
            String lookupName = aliases.getOrDefault(schemaTypeName, schemaTypeName);
            String mangledName = mangle(lookupName);
            for (String p : packages) {
                try {
                    return Class.forName(p + "." + mangledName);
                } catch (ClassNotFoundException e) {
                    // Tried in the next package, if any -- reported together below if none match.
                }
            }
            StringJoiner tried = new StringJoiner(", ");
            for (String p : packages) {
                tried.add(p + "." + mangledName);
            }
            throw new DataBindException(
                    "Failed to find class for '" + schemaTypeName + "' -- tried " + (packages.isEmpty() ? "no packages "
                            + "(none configured on this DataNameBinder)" : tried.toString()));
        }

        private static String mangle(String snakeCase) {
            StringBuilder result = new StringBuilder(snakeCase.length());
            boolean capitalizeNext = true;
            for (char c : snakeCase.toCharArray()) {
                if (c == '_') {
                    capitalizeNext = true;
                } else if (capitalizeNext) {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    result.append(Character.toLowerCase(c));
                }
            }
            return result.toString();
        }
    }
}
