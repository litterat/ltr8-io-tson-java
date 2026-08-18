package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.TsonEvent;

/**
 * The wire type-ref rules a <b>schemaless</b> read applies, and the one wording each produces -- shared by
 * {@link SchemalessTreeReader} and {@link SchemalessObjectReader} so the two report a given problem
 * identically. A schema-driven read never comes here: it resolves a type-ref against its compiled schema.
 *
 * <p>Given {@code !X} on a value being read with no schema in scope:
 *
 * <ol>
 *   <li>{@code X} <b>is</b> a built-in ([TSON-DATA] §5) -- the core-value must be a token ({@link
 *       #notScalar}), and the token must satisfy the atom ({@link #violation}).</li>
 *   <li>{@code X} is not a built-in but <b>names the target</b> the read is binding into ({@link #names})
 *       -- accepted. Object-binding only; a tree read has no target to name.</li>
 *   <li>Otherwise the name links to nothing and is {@link #unknown}.</li>
 * </ol>
 *
 * <p><b>Rule 3 is a reader policy, not a parsing one.</b> §5.1 requires the Class 1 <i>parsing</i> step to
 * preserve an unrecognized type annotation as an uninterpreted marker, and it does -- {@code TsonDataStream}
 * and {@code TsonDataParser} keep every name they see. What a reader actively type-checking a value does with
 * a marker it cannot link to anything is the layer above, where a typo like {@code !Uuid} (case-sensitive per
 * §5.1, so not {@code !uuid}) silently disabling the validation its author intended is the worse failure. Both
 * schemaless readers therefore report by default and offer preservation as an opt-in -- see {@code
 * SPEC-FEEDBACK.md} #7, whose suggested resolution this is.
 */
final class TypeRefCheck {

    private TypeRefCheck() {
    }

    /** A type-ref naming neither a built-in type nor anything else this read can link it to. */
    static void unknown(TsonReadContext ctx, String name) {
        ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF,
                "unknown type '!" + name + "' -- not a built-in type, and no schema is in scope to define it",
                "a built-in type name", "!" + name);
    }

    /** {@link #unknown} where a target class was in hand, so the diagnostic can say what the name failed to match. */
    static void unknown(TsonReadContext ctx, String name, Class<?> target) {
        ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF,
                "unknown type '!" + name + "' -- not a built-in type, and it does not name " + target.getName(),
                "a built-in type name or '!" + preferredName(target) + "'", "!" + name);
    }

    /** A built-in type-ref on a value that isn't a token -- every built-in atom is scalar. */
    static void notScalar(TsonReadContext ctx, String name, TsonEvent core) {
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "built-in type '!" + name + "' expects a scalar value",
                "a scalar for !" + name, describe(core));
    }

/**
     * A token the built-in atom named by {@code name} rejected -- both {@code AtomTypeException} subtypes land
     * here. {@code expected} is the atom's own account of the constraint that failed, not the atom's name: see
     * {@link AtomTypeException} for the vocabulary, and {@link io.ltr8.tson.compiler.reader.AtomTypeReader} for
     * why naming the type there is a loss.
     */
    static void violation(TsonReadContext ctx, String name, AtomTypeException e, String text) {
        ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, e.getMessage(), e.expected(), text);
    }

    /**
     * Whether {@code target} answers to the wire name {@code name} -- its {@link Typename} exactly, else its
     * simple class name case-insensitively, so {@code !point} links to a Java {@code Point} without every
     * fixture being annotated. The same match {@code SchemalessObjectReader.bindUnion} applies to a union's
     * members, so a member and a directly-bound target are recognized by one rule.
     */
    static boolean names(Class<?> target, String name) {
        return declares(target, name)
                || (target.getAnnotation(Typename.class) == null && target.getSimpleName().equalsIgnoreCase(name));
    }

    /**
     * Whether {@code target} <em>declares</em> the wire name -- {@link Typename} only, no simple-name
     * fallback. What an <b>atom</b> position uses, where the vocabulary is closed and a lookalike is a typo:
     * {@link #names} would match a UUID-targeted {@code !Uuid} against {@code UUID} and silently disable the
     * very check §5.1's case-sensitivity exists for. A container's wire name is a user-chosen type name, so
     * there the looser match is what a caller means.
     */
    static boolean declares(Class<?> target, String name) {
        Typename typename = target.getAnnotation(Typename.class);
        return typename != null && typename.name().equals(name);
    }

    /** The name {@link #names} would accept for {@code target}, for a diagnostic's {@code expected}. */
    private static String preferredName(Class<?> target) {
        Typename typename = target.getAnnotation(Typename.class);
        return typename != null ? typename.name() : target.getSimpleName();
    }

    /** A core-value's shape as a word, for a diagnostic's {@code actual} -- shared with {@link AtomTypeReader}. */
    static String describe(TsonEvent core) {
        return switch (core) {
            case RecordStart ignored -> "a record";
            case MapStart ignored -> "a map";
            case ArrayStart ignored -> "an array";
            case EmptyBraceEvent ignored -> "{}";
            case AbsentEvent ignored -> "the absent sentinel '_'";
            default -> String.valueOf(core);
        };
    }
}
