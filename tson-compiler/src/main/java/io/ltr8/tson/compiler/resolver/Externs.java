package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.Top;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What every phase says of an extern ([TSON-SCHEMA] §5.13): which declarations of a document are externs, found by
 * their written head before any name resolves, and the one refusal of an operation an extern is sealed against.
 */
public final class Externs {

    /** The kernel's constructor of externs, which an extern's head names as written. */
    public static final String HEAD = KernelNames.of("extern");

    private Externs() {
    }

    /**
     * Each declaration whose head is written {@code !meta.extern}, members of nested schema maps included, with what
     * its payload writes. Found on the parsed document, so a document's externs are known with its imports (§3.4.1).
     */
    public static Map<String, Written> written(Map<String, SchemaMap.Declaration> declarations) {
        Map<String, Written> externs = new LinkedHashMap<>();
        declarations.forEach((name, declaration) -> {
            if (declaration.typeDef() instanceof Instance instance && HEAD.equals(instance.target())) {
                externs.put(name, new Written(textField(instance.value(), "schema"),
                        textField(instance.value(), "type")));
            }
        });
        return externs;
    }

    /**
     * An extern's payload as written, each field empty where it is not written as a token: the read of the payload
     * refuses it.
     */
    public record Written(Optional<String> schema, Optional<String> type) {
    }

    private static Optional<String> textField(DataValue value, String field) {
        if (!(value.coreValue() instanceof RecordValue record)) {
            return Optional.empty();
        }
        return record.fields().stream().filter(written -> written.name().equals(field)).findFirst()
                .filter(written -> written.value().coreValue() instanceof TokenValue)
                .map(written -> ((TokenValue) written.value().coreValue()).text());
    }

    /** The extern {@code name} names past its alias hops, or empty where it names something else or nothing. */
    public static Optional<Extern> terminal(String name, Function<String, ? extends Top> entries) {
        return AliasChain.terminalDefinition(name, entries).filter(Extern.class::isInstance).map(Extern.class::cast);
    }

    /** How a message names an extern: the foreign entry and its schema. */
    public static String describe(Extern extern) {
        return "an extern of '" + extern.type() + "' in '" + extern.schema() + "'";
    }

    /**
     * The refusal of {@code operation} on {@code written}, which names {@code extern} directly or through its alias
     * hops to {@code terminal}, stated against {@code subject}.
     */
    public static SchemaValidationException sealed(String subject, String operation, String written, String terminal,
                                                   Extern extern) {
        return new SchemaValidationException(subject + ": " + operation + " '" + written + "'"
                + (written.equals(terminal) ? "" : ", an alias of '" + terminal + "'") + ", which is "
                + describe(extern) + " -- an extern is sealed: it names another schema's type as that schema "
                + "declares it, so it takes no '&', '-' or '^', heads no construction or application, and a field "
                + "typed by it takes no default or pin ([TSON-SCHEMA] §5.13)");
    }
}
