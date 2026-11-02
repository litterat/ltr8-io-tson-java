package io.ltr8.tson.schema;

/**
 * The kernel's machinery's names. The kernel ({@link TsonBundledSchemas#META_ID}) keeps its root to the vocabulary an
 * author writes ({@code text}, {@code integer}, {@code doc}, {@code enum}, {@code set}, …) and declares everything
 * else as a member of one of six open schema maps nested in its root, each named by its path from the root
 * ([TSON-SCHEMA] §5.12): the type system under {@value #MAP} ({@code meta.record}, {@code meta.identifier}, {@code
 * meta.type_kind}), the datatype properties under {@value #ATOM} ({@code atom.ordering}, {@code atom.specification}),
 * and the atom constructors under {@value #NUMERIC}, {@value #UNICODE}, {@value #NET} and {@value #BINARY} ({@code
 * numeric.integer_type}, {@code unicode.text_type}, {@code net.iri_type}, {@code binary.bytes_type}). A member's
 * identity is its declaring document and its path, so a path is the kernel's only where the kernel declares it
 * ({@link TsonBundledSchemas#isKernel}).
 */
public final class KernelNames {

    private KernelNames() {
    }

    /** The schema map holding the type system. */
    public static final String MAP = "meta";

    /** The schema map holding the datatype properties and the {@code atom.specification} mixin. */
    public static final String ATOM = "atom";

    /** The schema map holding the integer constructor, which numeric.tn adds to. */
    public static final String NUMERIC = "numeric";

    /** The schema map holding the text, identifier and regex constructors. */
    public static final String UNICODE = "unicode";

    /** The schema map holding the IRI constructor, which net.tn adds to. */
    public static final String NET = "net";

    /** The schema map holding the octet-sequence constructor. */
    public static final String BINARY = "binary";

    /** A path under {@value #MAP}: its first segment and its dot. */
    public static final String PREFIX = MAP + ".";

    /** The path of the type system's member {@code member}: {@code record} gives {@code meta.record}. */
    public static String of(String member) {
        return PREFIX + member;
    }

    /** The path of {@code member} under the kernel's map {@code map}: {@code unicode.text_type}. */
    public static String of(String map, String member) {
        return map + "." + member;
    }

    /** Whether {@code name} is the path of a member of {@value #MAP}, {@code meta.record}. */
    public static boolean isPath(String name) {
        return name != null && name.startsWith(PREFIX) && name.indexOf('.', PREFIX.length()) < 0;
    }

    /** The member's own name where {@code name} is a path under {@value #MAP} ({@link #isPath}), else {@code name}. */
    public static String member(String name) {
        return isPath(name) ? name.substring(PREFIX.length()) : name;
    }
}
