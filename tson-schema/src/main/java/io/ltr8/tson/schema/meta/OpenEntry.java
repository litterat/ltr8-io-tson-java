package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Map;

/**
 * An open entry ([TSON-SCHEMA] §5.10): one whose value declares parameters, and so is not closed until an
 * application substitutes them away. Every question a phase asks of an entry because it is open -- whether it is,
 * what it binds, whether it is a family base -- is answered here, so which kernel shape carries an open entry is
 * decided in one place and not at each phase that asks.
 *
 * <p>Today every open entry's value is a {@link TemplateBody}, whatever constructor its held body applies.
 *
 * <p><b>A family base</b> is the one open entry that is itself a type (§5.10): a template whose held body is a
 * record. A position may name it, a value there being a value of one of its declared members, and it is ABSTRACT by
 * construction. Every other open entry denotes nothing until applied.
 */
public final class OpenEntry {

    private OpenEntry() {
    }

    /** Whether an entry whose value is {@code value} declares parameters. */
    public static boolean isOpen(Top value) {
        return value instanceof TemplateBody;
    }

    /** The parameters {@code value} binds, in declaration order -- {@code []} for a closed entry. */
    public static List<TemplateParam> parameters(Top value) {
        return value instanceof TemplateBody held ? held.parameters() : List.of();
    }

    /**
     * The parameter names {@code value} binds, in declaration order -- {@code []} for a closed entry. What
     * substitution binds and what an application's arity is checked against.
     */
    public static List<String> parameterNames(Top value) {
        return value instanceof TemplateBody held ? held.parameterNames() : List.of();
    }

    /** Whether {@code value} is a family base: an open entry whose held body is a record. */
    public static boolean isFamilyBase(Top value) {
        return value instanceof TemplateBody held && held.extension().isPresent();
    }

    /**
     * {@code value} with each parameter replaced by {@code settled}'s entry for it, where there is one -- how a
     * parameter's derived type is stamped. A closed entry is returned as it is.
     */
    public static Top withParameters(Top value, Map<String, TemplateParam> settled) {
        return value instanceof TemplateBody held ? held.withParameters(settled) : value;
    }
}
