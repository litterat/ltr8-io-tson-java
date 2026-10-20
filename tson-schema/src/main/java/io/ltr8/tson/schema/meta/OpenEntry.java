package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Map;

/**
 * An open entry ([TSON-SCHEMA] §5.10): one whose value declares parameters, and so is not closed until an
 * application substitutes them away. Every question a phase asks of an entry because it is open -- whether it is,
 * what it binds, whether it is a family base -- is answered here, so which kernel shape carries an open entry is
 * decided in one place and not at each phase that asks.
 *
 * <p><b>Two shapes carry one.</b> A record with parameters ({@link RecordBody#parameters}) is a <b>family base</b>:
 * the one open entry that is itself a type, ABSTRACT by construction, which a position may name, a value there being
 * a value of one of its declared members. Every other open entry is a {@link TemplateBody}, whose held body denotes
 * nothing until it is applied.
 */
public final class OpenEntry {

    private OpenEntry() {
    }

    /** Whether an entry whose value is {@code value} declares parameters. */
    public static boolean isOpen(Top value) {
        return value instanceof TemplateBody || isFamilyBase(value);
    }

    /** The parameters {@code value} binds, in declaration order -- {@code []} for a closed entry. */
    public static List<TemplateParam> parameters(Top value) {
        return switch (value) {
            case TemplateBody held -> held.parameters();
            case RecordBody record -> record.parameters();
            case null, default -> List.of();
        };
    }

    /**
     * The parameter names {@code value} binds, in declaration order -- {@code []} for a closed entry. What
     * substitution binds and what an application's arity is checked against.
     */
    public static List<String> parameterNames(Top value) {
        return parameters(value).stream().map(TemplateParam::name).toList();
    }

    /** Whether {@code value} is a family base: a record that declares parameters. */
    public static boolean isFamilyBase(Top value) {
        return value instanceof RecordBody record && !record.parameters().isEmpty();
    }

    /**
     * {@code value} with each parameter replaced by {@code settled}'s entry for it, where there is one -- how a
     * parameter's derived type is stamped. A closed entry is returned as it is.
     */
    public static Top withParameters(Top value, Map<String, TemplateParam> settled) {
        return switch (value) {
            case TemplateBody held -> held.withParameters(settled);
            case RecordBody record when isFamilyBase(record) -> record.withParameters(
                    record.parameters().stream().map(p -> settled.getOrDefault(p.name(), p)).toList());
            case null, default -> value;
        };
    }
}
