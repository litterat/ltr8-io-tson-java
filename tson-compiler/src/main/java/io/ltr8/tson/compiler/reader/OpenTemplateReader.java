package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.EventSkip;

import java.util.List;

/**
 * The reader an <b>open</b> entry compiles to -- one that declares type parameters, so it is a template
 * rather than a type ([TSON-SCHEMA] §5.10) -- unless its held body carries {@code extension}, which makes it
 * a family base with a dispatch of its own ({@link AbstractTemplateReader}). Reports the author's mistake
 * and skips the value, exactly like any other reader that finds the data isn't what the schema admits.
 *
 * <p><b>Reaching this is always a data error, never a schema one.</b> A schema referring to a template
 * without applying it is rejected far earlier, when the schema is linked ({@code TsonSchemaLinker}'s own
 * arity rule), so no field, element or supertype can route here. What is left is a <em>data</em> type-ref
 * naming the template directly -- {@code !paged { ... }} against {@code paged => <T> { ... }} -- at the
 * document root or anywhere a type-ref selects a reader. It is among the likeliest author mistakes: a
 * template is the natural thing to name for "a page of orders".
 *
 * <p><b>Why the whole entry, rather than a check at the root.</b> A parameterised body has no reader to
 * build -- its parameters name no entry -- so compiling it like any other entry would end in an {@link
 * ErrorReader}, which reports {@code NOT_IMPLEMENTED}: the library's own gap code, for a document that is
 * plainly invalid. Refusing the entry itself is one place and covers every position, and the parameters it
 * names in the message are the author's own.
 *
 * <p>The message deliberately mirrors the linker's schema-side wording for the same mistake, so the two ends
 * of one rule read as one rule, and adds the route out: name the application in the schema, then write that
 * name in the data.
 */
public final class OpenTemplateReader implements TsonTypeReader<Object> {

    private final String name;
    private final List<String> parameters;
    private final SchemaLocation schemaLocation;

    public OpenTemplateReader(String name, List<String> parameters, SchemaLocation schemaLocation) {
        this.name = name;
        this.parameters = List.copyOf(parameters);
        this.schemaLocation = schemaLocation;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        // Reported before anything is consumed, so the data position is the type-ref the author wrote.
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, message(), "a type, not a template", "!" + name);
        EventSkip.dataValue(ctx);
        return null;
    }

    /**
     * Both halves: what is wrong (the linker's own sentence for an unapplied template) and what to write
     * instead. {@code expected} stays the short machine-readable form -- the placeholder name in here is
     * prose, and a consumer that acted on it would be pattern-matching a sentence.
     */
    private String message() {
        return "'" + name + "' is a template taking " + parameters.size() + " type argument"
                + (parameters.size() == 1 ? "" : "s") + " " + parameters + ", and a template is not a type "
                + "until it is applied (§5.10) -- a data type-ref carries no arguments, so name the "
                + "application in the schema ('my_type => " + name + "<...>') and write '!my_type' here";
    }
}
