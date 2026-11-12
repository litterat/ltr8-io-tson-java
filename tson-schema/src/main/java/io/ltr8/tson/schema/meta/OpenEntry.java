package io.ltr8.tson.schema.meta;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The questions a phase asks of an entry because it may be open ([TSON-SCHEMA] §5.10): whether it is, what it binds,
 * and what its body writes. An open entry is an open record, a {@link RecordBody} whose {@code parameters} is not
 * empty -- the one shape a template has -- so each answer is a question of that record, asked of any entry.
 *
 * <p><b>An open record is no type</b> (§5.10): no position names one bare, and only an application of one is a
 * type. Code that builds a reader or a writer from a {@link RecordBody} asks {@link #isOpen} first.
 */
public final class OpenEntry {

    private OpenEntry() {
    }

    /** Whether an entry whose value is {@code value} is an open record. */
    public static boolean isOpen(Top value) {
        return value instanceof RecordBody record && record.isOpen();
    }

    /** The parameters {@code value} binds, in declaration order -- {@code []} for a closed entry. */
    public static List<Param> parameters(Top value) {
        return value instanceof RecordBody record ? record.parameters() : List.of();
    }

    /**
     * The parameter names {@code value} binds, in declaration order -- {@code []} for a closed entry. What
     * substitution binds and what an application's arity is checked against.
     */
    public static List<String> parameterNames(Top value) {
        return parameters(value).stream().map(Param::name).toList();
    }

    /**
     * Every application an open record writes, in its fields' types and its parents ({@link RecordBody#references}),
     * each as written with its arguments; an application nested in another's arguments comes back inside it. Empty
     * for a closed entry.
     */
    public static List<TypeRef> applications(Top value) {
        if (!isOpen(value)) {
            return List.of();
        }
        return ((RecordBody) value).references().stream().filter(reference -> !reference.arguments().isEmpty())
                .toList();
    }

    /**
     * Every name an open record's references write, at any depth -- each head, each name and each parameter. What
     * §5.10's unreferenced-parameter rule asks: a declared parameter none of them writes is the author's error.
     */
    public static Set<String> names(Top value) {
        Set<String> names = new LinkedHashSet<>();
        if (isOpen(value)) {
            ((RecordBody) value).references().forEach(reference -> collect(reference, names));
        }
        return names;
    }

    private static void collect(TypeRef reference, Set<String> into) {
        into.add(reference.name());
        reference.arguments().forEach(argument -> collect(argument, into));
    }

    /**
     * The first of {@code value}'s parameters that {@code type} names, as itself or in an argument at any depth --
     * empty where {@code type} means the same thing in every application, and so is closed already. A type written in
     * place holds no parameter: inside an open record a form over one is held as the application it spells.
     */
    public static Optional<String> parameterIn(TypeExpr type) {
        if (type instanceof ParamRef parameter) {
            return Optional.of(parameter.name());
        }
        if (type instanceof TypeRef reference) {
            for (TypeRef argument : reference.arguments()) {
                Optional<String> found = parameterIn(argument);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * {@code value} with each parameter replaced by {@code settled}'s entry for it, where there is one -- how a
     * declaration's written bounds are put on its parameters. A closed entry is returned as it is.
     */
    public static Top withParameters(Top value, Map<String, Param> settled) {
        if (!isOpen(value)) {
            return value;
        }
        RecordBody record = (RecordBody) value;
        return record.withParameters(record.parameters().stream()
                .map(p -> settled.getOrDefault(p.name(), p)).toList());
    }
}
