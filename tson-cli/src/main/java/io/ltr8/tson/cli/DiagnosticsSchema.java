package io.ltr8.tson.cli;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.parser.base.TsonAtomContext;
import io.ltr8.tson.parser.compiler.SchemaMetaNameBinder;
import io.ltr8.tson.parser.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.parser.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.parser.compiler.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Compiles this module's own {@code diagnostics.tn1} (a small schema, governed by meta.tn1 and
 * importing core.tn1, declaring {@code diagnostic}/{@code validation_report}), *compiled* in
 * object-binding mode -- bound directly to {@link CliDiagnostic}/{@link ValidationReport} via
 * {@link #BINDER} -- what {@link OutputFormat#TSON} reads a written report back through, to prove
 * the emitted text is genuinely valid against a real TSON schema, not just structurally similar to
 * one.
 *
 * <p>A fresh {@link StandardLibrary} bootstrap of its own -- always resolved in object-binding mode
 * internally (see {@link StandardLibrary}'s own Javadoc for why), and independently *compiled* here
 * in object-binding mode too, unlike a user schema {@code validate}/{@code compile} reads in DOM
 * mode instead. Each caller of {@link StandardLibrary#bootstrap} gets its own fresh registry, so
 * this schema's own registration never collides with a user schema's.
 */
final class DiagnosticsSchema {

    private static final DataNameBinder BINDER = name -> switch (name) {
        case "validation_report" -> ValidationReport.class;
        case "diagnostic" -> CliDiagnostic.class;
        default -> SchemaMetaNameBinder.INSTANCE.resolve(name);
    };

    private DiagnosticsSchema() {
    }

    static TsonCompiledMetaSchema compiled() {
        DataBindContext context =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(BINDER).build());
        ValueReaderFactoryResolver resolver = ValueReaderFactoryRegistry.bind(context);
        StandardLibrary.Bootstrapped stdlib = StandardLibrary.bootstrap();
        TsonLinkedSchema linked = StandardLibrary.resolveUserSchema(stdlib, readResource("/diagnostics.tn1"));
        return StandardLibrary.compile(linked, resolver);
    }

    private static String readResource(String path) {
        try (InputStream in = DiagnosticsSchema.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
