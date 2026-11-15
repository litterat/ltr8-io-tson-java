package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Schema documents served by identity for the extern tests ([TSON-SCHEMA] §5.13): two schemas that both declare
 * {@code order}, the case an extern exists for, and whatever a test adds.
 */
final class ExternWorld {

    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String ORDER_1 = "https://example.test/order-1.tn";
    static final String ORDER_2 = "https://example.test/order-2.tn";

    final Map<String, String> documents = new LinkedHashMap<>();

    ExternWorld() {
        schema(ORDER_1, """
                order => { id: text  payment: card }
                card  => { number: text }
                """);
        schema(ORDER_2, """
                order => { ref: text  lines: [line]  payment: card }
                rush  => order & { by: text }
                line  => { sku: text  qty: int32 }
                card  => { pan: text }
                """);
    }

    /** A schema of identity {@code id} importing core.tn, then {@code imports}, declaring {@code body}. */
    ExternWorld schema(String id, String body, String... imports) {
        documents.put(id, text(id, body, imports));
        return this;
    }

    static String text(String id, String body, String... imports) {
        StringBuilder text = new StringBuilder("!!id:\"" + id + "\"\n!!import:\"" + CORE + "\"\n");
        for (String each : imports) {
            text.append("!!import:\"").append(each).append("\"\n");
        }
        return text.append("!schema {\n").append(body.indent(2)).append("}\n").toString();
    }

    /** {@code entry => !meta.extern { … }}, written as an author writes it. */
    static String extern(String entry, String schema, String type) {
        return entry + " => !meta.extern { schema: \"" + schema + "\"  type: " + type + " }\n";
    }

    Tson tson() {
        return Tson.of(config());
    }

    /** A processor binding the schema type names {@code classes} maps, beside the schema model's own. */
    Tson tson(Map<String, Class<?>> classes) {
        return tson(DataNameBinder.ofMap(classes).orElse(SchemaModelNameBinder.INSTANCE));
    }

    /** A processor binding classes through {@code binder}, beside the schema model's own. */
    Tson tson(DataNameBinder binder) {
        return Tson.of(config().withDataBindContext(DataBindContext.builder()
                .nameBinder(binder).registerAtoms(AtomContext.hostTypes()).build()));
    }

    private ProcessorConfig config() {
        SchemaSource source = uri -> documents.entrySet().stream()
                .filter(document -> CanonicalIdentity.sameIdentity(uri, document.getKey()))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                        "no document is served under '" + uri + "'", null));
        return ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source));
    }

    /** The diagnostics validating a schema of {@code body} reports, as one string per diagnostic. */
    List<String> validate(String body, String... imports) {
        return validateText(text("https://example.test/subject.tn", body, imports));
    }

    List<String> validateText(String text) {
        return tson().validateSchema(text).stream().map(ExternWorld::shown).toList();
    }

    static String shown(Diagnostic diagnostic) {
        return diagnostic.code() + " " + diagnostic.schemaPointer().orElse("") + ": " + diagnostic.message();
    }

    static String joined(List<String> diagnostics) {
        return diagnostics.stream().collect(Collectors.joining("\n"));
    }
}
