package io.ltr8.tson.schema;

import io.ltr8.annotation.Annotated;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.ValueEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A schema map nested as an entry, {@code geo => !schema { address => { … } }}, and the paths that address its
 * members. Every value of the kernel's {@code schema} namespace type declares as the document's root does, and a
 * member's identity is its path from the root, {@code geo.address}.
 *
 * <p><b>One map of entries, keyed by path.</b> A schema's {@link TsonSchema#entries} holds every declaration of the
 * document at every depth under its path, so each phase reads a nested member as it reads a root entry: a reference
 * to {@code geo.address} is a lookup of that key, as every name resolves from the root. The nested map's own entry,
 * {@code geo}, is a {@link ValueEntry} of {@value TsonSchema#SCHEMA} whose {@link Namespace} holds its members under
 * their own names, and it is derived from the path entries wherever a schema is built ({@link #synced}), so the two
 * never disagree. Resolved output writes the nested map and not the paths; a resolved form read back holds the nested
 * map alone, and {@link #synced} keys its members under their paths.
 */
public final class SchemaPaths {

    private SchemaPaths() {
    }

    /** Whether {@code name} is the path of a nested map's member, {@code geo.address}, rather than a root entry's. */
    public static boolean isPath(String name) {
        return name.indexOf('.') >= 0;
    }

    /** The path of the map {@code path} is a member of, {@code ""} for a root entry. */
    public static String parent(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    /** The member's own name, the path's last segment. */
    public static String member(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    /**
     * Whether {@code value} is a schema map nested as an entry: a value of {@value TsonSchema#SCHEMA}, held as a
     * {@link ValueEntry} in a schema, or the bare {@link Namespace} a resolved form read as data holds.
     */
    public static boolean isNestedMap(Object value) {
        return members(value) != null;
    }

    /** The members {@code value} holds where it is a nested map ({@link #isNestedMap}), {@code null} otherwise. */
    private static Namespace<?> members(Object value) {
        Object held = value instanceof ValueEntry entry && TsonSchema.SCHEMA.equals(entry.type()) ? entry.value()
                : value;
        return held instanceof Namespace<?> namespace && TsonSchema.SCHEMA.equals(namespace.type()) ? namespace
                : null;
    }

    /**
     * {@code entries} with every nested map and its members in step: a member a nested map holds and no path keys
     * (a schema built from a resolved form read back) is added under its path, and each nested map's value is then
     * rebuilt from the entries at its members' paths, with their key annotations, in their order, innermost first.
     * Returns {@code entries} itself where it holds no nested map.
     */
    public static Namespace<Top> synced(Namespace<Top> entries) {
        if (entries.values().stream().noneMatch(SchemaPaths::isNestedMap)) {
            return entries;
        }
        AnnotatedMap<String, Object> flat = new AnnotatedMap<>();
        entries.annotatedEntrySet().forEach(entry ->
                addWithMembers(flat, entry.getKey().value(), entry.getValue(), entry.getKey().annotations()));
        List<String> maps = new ArrayList<>();
        flat.forEach((name, value) -> {
            if (isNestedMap(value)) {
                maps.add(name);
            }
        });
        maps.sort(Comparator.comparingInt((String name) -> name.split("\\.", -1).length).reversed());
        for (String map : maps) {
            AnnotatedMap<String, Top> members = new AnnotatedMap<>();
            flat.forEach((name, value) -> {
                if (isPath(name) && parent(name).equals(map)) {
                    members.put(member(name), (Top) value, flat.getAnnotations(name));
                }
            });
            flat.put(map, new ValueEntry(TsonSchema.SCHEMA, new Namespace<>(TsonSchema.SCHEMA, members)),
                    flat.getAnnotations(map));
        }
        AnnotatedMap<String, Top> synced = new AnnotatedMap<>();
        flat.annotatedEntrySet().forEach(entry ->
                synced.put(entry.getKey().value(), (Top) entry.getValue(), entry.getKey().annotations()));
        return new Namespace<>(entries.type(), synced);
    }

    /**
     * A resolved form's entries as read back, each nested map's members added under their paths, recursively, and
     * each nested map held as a {@link ValueEntry} -- what a schema built from them holds. Order kept, a map's members
     * following it.
     */
    public static Map<String, Object> withPaths(Map<String, ?> read) {
        AnnotatedMap<String, Object> flat = new AnnotatedMap<>();
        read.forEach((name, value) -> addWithMembers(flat, name, value, Annotations.empty()));
        return new LinkedHashMap<>(flat);
    }

    /** {@code name} and, where it is a nested map, each member it holds that no path already keys, recursively. */
    private static void addWithMembers(AnnotatedMap<String, Object> flat, String name, Object value,
                                       Annotations annotations) {
        Namespace<?> members = members(value);
        if (!flat.containsKey(name)) {
            flat.put(name, members == null || value instanceof ValueEntry ? value
                    : new ValueEntry(TsonSchema.SCHEMA, members), annotations);
        }
        if (members != null) {
            for (Map.Entry<Annotated<String>, ?> member : members.annotatedEntrySet()) {
                addWithMembers(flat, name + "." + member.getKey().value(), member.getValue(),
                        member.getKey().annotations());
            }
        }
    }
}
