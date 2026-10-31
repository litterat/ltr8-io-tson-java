package io.ltr8.tson.schema;

/**
 * The kernel's names: every declaration of the pre-loaded kernel ({@link TsonBundledSchemas#META_ID}) sits under one
 * entry of its root, {@value #MAP}, a schema map nested as an entry, so each is named by its path from the root,
 * {@code meta.record}, {@code meta.integer_type} ([TSON-SCHEMA] §5.12). Those paths are the kernel entries' identities
 * in every schema whose closure holds the kernel, and what each phase addresses a kernel entry by.
 */
public final class KernelNames {

    private KernelNames() {
    }

    /** The kernel's one root entry, the schema map holding every kernel declaration. */
    public static final String MAP = "meta";

    /** A kernel path's first segment and its dot. */
    public static final String PREFIX = MAP + ".";

    /** The path of the kernel's member {@code member}: {@code record} gives {@code meta.record}. */
    public static String of(String member) {
        return PREFIX + member;
    }

    /** Whether {@code name} is the path of a member of the kernel's map, {@code meta.record}. */
    public static boolean isPath(String name) {
        return name != null && name.startsWith(PREFIX) && name.indexOf('.', PREFIX.length()) < 0;
    }

    /** The member's own name where {@code name} is a kernel path ({@link #isPath}), else {@code name} itself. */
    public static String member(String name) {
        return isPath(name) ? name.substring(PREFIX.length()) : name;
    }
}
