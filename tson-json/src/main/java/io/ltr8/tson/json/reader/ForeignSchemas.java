package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.ContentHashMismatchException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.json.JsonCompiledSchema;
import io.ltr8.tson.json.JsonReadContext;

import java.util.Optional;

/**
 * How a read reaches a schema the document names <em>inside</em> itself -- [TSON-JSON] §8.5's scope push, where
 * a value at a {@code scoped} position leads with its own {@code $schema}, and the type its {@code $type} names
 * has to be compiled before that value can be read.
 *
 * <p><b>Read-time, unlike every other name a reader resolves.</b> A compiled reader's children are wired as
 * object references when the schema compiles, because the schema names all of them. A foreign schema is named
 * by the <em>document</em>, so which one it is cannot be known until the value arrives. What can be fixed in
 * advance is where to ask: {@code JsonCompiledSchemaRegistry} hands its own lookup to every compile it performs,
 * so a scope push resolves through the same cache, the same loader and the same read mode as the schema that
 * admitted it.
 *
 * <p>A compile with no registry behind it passes {@link #none()}, whose every lookup is {@code
 * SCHEMA_NOT_PERMITTED}: nothing was configured to supply a foreign schema, which is a fact about this
 * deployment and not a verdict on the document.
 */
@FunctionalInterface
public interface ForeignSchemas {

    /**
     * The compiled schema at {@code uri}, in the compiling registry's own read mode, or empty where no source
     * has it.
     *
     * @throws RuntimeException if it was supplied and could not be resolved, linked or compiled
     */
    Optional<JsonCompiledSchema> get(String uri);

    /**
     * {@link #get} with every failure reported through {@code ctx} rather than thrown, yielding {@code null}
     * when the schema could not be had.
     *
     * <p>The classification is the one both read facades apply to a schema they are handed: a schema nobody
     * would supply is one of the five {@code SCHEMA_*} codes, and a schema that is wrong is {@code
     * SCHEMA_ERROR} -- neither a verdict on the document, which was never read against it. A fault in this
     * library still propagates as itself.
     */
    default JsonCompiledSchema get(String uri, JsonReadContext ctx) {
        try {
            Optional<JsonCompiledSchema> found = get(uri);
            if (found.isEmpty()) {
                ctx.report(Diagnostic.Code.SCHEMA_NOT_FOUND, "no schema was supplied for \"" + uri + "\"",
                        "a schema this processor can obtain", uri);
            }
            return found.orElse(null);
        } catch (SchemaFetchException e) {
            ctx.report(Diagnostic.Code.of(e.reason()), e.getMessage(), "a schema this processor can obtain", uri);
        } catch (BindMismatchException e) {
            ctx.report(Diagnostic.Code.BIND_MISMATCH, e.getMessage(), "a schema whose types the bound classes match",
                    uri);
        } catch (UnsupportedOperationException e) {
            ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, e.getMessage(), "a schema this library can compile", uri);
        } catch (SchemaValidationException e) {
            ctx.report(Diagnostic.Code.SCHEMA_ERROR, e.getMessage(), "a resolvable schema", uri);
        } catch (ContentHashMismatchException e) {
            ctx.report(Diagnostic.Code.SCHEMA_ERROR, e.getMessage(), "a schema matching its ?sha256= pin", uri);
        }
        return null;
    }

    /** A lookup for a compile with no registry behind it -- see this interface's own note. */
    static ForeignSchemas none() {
        return uri -> {
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_PERMITTED,
                    "this read has no schema source behind it, so the scope it names cannot be loaded -- read "
                            + "through a Json facade, whose registry supplies one", null);
        };
    }
}
