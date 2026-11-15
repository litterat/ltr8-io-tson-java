package io.ltr8.bind;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Resolves a schema/wire-format type name (e.g. {@code "numeric.integer_size"}) to the Java class a
 * {@link DataBindContext} should build a descriptor for -- the general form of the
 * "type-to-Class binding" problem every serialization library eventually needs: there is no
 * reflection API to enumerate "every class in a package," so this is necessarily a name lookup
 * (with whatever naming convention/namespace a caller's own classes follow), not an enumeration
 * of candidates. {@link DataBindContext#getDescriptor(String, String)} is where this gets consulted,
 * composing {@link #resolve} with {@link DataBindContext#getDescriptor(Class)} so a caller gets a
 * {@link DataClass} directly from a name.
 *
 * <p><b>A name is asked within its namespace</b> ({@link #resolve(String, String)}): two namespaces may each
 * declare a type of one name, and each binds to its own class. The namespace is opaque here, matched by
 * equality: an encoding with schemas passes the identity of the schema that declares the type. A binder that
 * answers by the name alone implements {@link #resolve(String)} only, which is what a caller with one namespace
 * writes; {@link #byNamespace} routes each namespace to a binder of its own.
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
     * The class the type {@code name}, declared in {@code namespace}, binds to. By default the answer for the name
     * alone ({@link #resolve(String)}), so a binder that does not tell namespaces apart answers every one alike.
     *
     * @param namespace the namespace declaring {@code name}, opaque and matched by equality
     * @throws DataBindException if this binder binds no class for that type
     */
    default Class<?> resolve(String namespace, String name) throws DataBindException {
        return resolve(name);
    }

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
     * either; the backstop's own message rides along as the cause. Both forms of the question compose, so a
     * namespace asked of the composition reaches both binders.
     */
    default DataNameBinder orElse(DataNameBinder next) {
        DataNameBinder first = this;
        return new DataNameBinder() {
            @Override
            public Class<?> resolve(String name) throws DataBindException {
                try {
                    return first.resolve(name);
                } catch (DataBindException notMine) {
                    try {
                        return next.resolve(name);
                    } catch (DataBindException notTheirs) {
                        throw new DataBindException(notMine.getMessage(), notTheirs);
                    }
                }
            }

            @Override
            public Class<?> resolve(String namespace, String name) throws DataBindException {
                try {
                    return first.resolve(namespace, name);
                } catch (DataBindException notMine) {
                    try {
                        return next.resolve(namespace, name);
                    } catch (DataBindException notTheirs) {
                        throw new DataBindException(notMine.getMessage(), notTheirs);
                    }
                }
            }
        };
    }

    /**
     * One binder per namespace: a type declared in a namespace {@code binders} holds is answered by that
     * namespace's binder alone, and every other by {@code fallback}, which also answers a name asked with no
     * namespace. What a caller holding one name binder per schema composes them into, so two schemas' types of one
     * name bind to two classes through one context.
     *
     * <p><b>A namespace's binder is the last word for it</b>, not a first try: a type it does not bind is reported
     * as that binder's missing line, never answered by {@code fallback} with a class meant for another namespace.
     * A caller wanting a backstop for one namespace composes it there ({@link #orElse}).
     *
     * <p>The map is copied, so a caller may keep and change theirs.
     *
     * @param binders  each namespace, as {@link #resolve(String, String)} is asked it, to the binder for its types
     * @param fallback the binder for a namespace {@code binders} does not hold, and for a name asked alone
     */
    static DataNameBinder byNamespace(Map<String, DataNameBinder> binders, DataNameBinder fallback) {
        Map<String, DataNameBinder> copy = Map.copyOf(binders);
        Objects.requireNonNull(fallback, "fallback");
        return new DataNameBinder() {
            @Override
            public Class<?> resolve(String name) throws DataBindException {
                return fallback.resolve(name);
            }

            @Override
            public Class<?> resolve(String namespace, String name) throws DataBindException {
                return copy.getOrDefault(namespace, fallback).resolve(namespace, name);
            }
        };
    }

    /**
     * Mangles {@code schemaTypeName} (after applying {@code aliases}) from snake_case to
     * PascalCase and tries {@code Class.forName} against each of {@code packages} in turn,
     * first match wins. A qualified name's earlier segments are sub-packages: {@code geo.route}
     * is {@code <package>.geo.Route}.
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

        /**
         * {@code snake_case} to {@code PascalCase}; a qualified name's segments before the last, {@code geo} in {@code
         * geo.route}, are a sub-package each, as written, so {@code geo.route} is {@code geo.Route}.
         */
        private static String mangle(String qualified) {
            int dot = qualified.lastIndexOf('.');
            return dot < 0 ? pascal(qualified) : qualified.substring(0, dot + 1) + pascal(qualified.substring(dot + 1));
        }

        private static String pascal(String snakeCase) {
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
