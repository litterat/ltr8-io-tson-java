package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TSON readers ask the binder what {@link TsonLinkedSchema#bindingCandidates} answers, and name each scope by its
 * canonical identity.
 */
class BindingCandidatesReadTest {

    private static final String HOST = "https://example.test/host.tn";

    private static final String LIB = "https://example.test/lib.tn";

    private static final Map<String, String> SOURCES = Map.of(HOST, """
            !!id:"https://example.test/host.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              t    => { n: int32 }
              near => t
              far  => near
              y    => !meta.extern { schema: "https://example.test/lib.tn"  type: x }
              h    => { v: y }
            }
            """, LIB, """
            !!id:"https://example.test/lib.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              x => { n: int32 }
            }
            """);

    public record T(int n) {
    }

    private static Tson tson(Map<String, Class<?>> bound) {
        DataBindContext context = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bound))
                .registerAtoms(AtomContext.hostTypes()).build();
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(context)
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(SOURCES))));
    }

    /** {@code linked} with every entry's source position dropped, as a resolved form read back holds it. */
    private static TsonLinkedSchema positionless(TsonLinkedSchema linked) {
        TsonSchema schema = linked.schema();
        Map<String, TsonSchema.EntryFacts> facts = new LinkedHashMap<>();
        schema.facts().forEach((name, fact) -> facts.put(name,
                new TsonSchema.EntryFacts(Optional.empty(), fact.annotations(), fact.constructor())));
        return new TsonLinkedSchema(new TsonSchema(schema.id(), schema.imports(), schema.entries(), schema.bootstrap(),
                schema.valuesByName(), schema.heldValues(), schema.restatements(), facts), linked.entryOrigins(),
                linked.enumForms(), linked.nameTypes(), linked.externs());
    }

    private static Object read(TsonCompiledSchema compiled, String type, String value, DiagnosticsReceiver receiver) {
        TsonReadContext ctx = TsonReadContext.of(new TsonDataStream(ByteSource.of(value)), receiver);
        ctx.next();
        return compiled.get(type).read(ctx);
    }

    /** Every name whose chain ends at a positionless entry means it ([TSON-SCHEMA] §8.3), two hops as one. */
    @Test
    void aPositionlessEntryBindsThroughAnAliasTwoHopsAway() {
        Tson tson = tson(Map.of("far", T.class));
        TsonLinkedSchema linked = positionless(tson.resolve(SOURCES.get(HOST)));

        TsonCompiledSchema compiled = tson.bindRegistry().compile(linked);

        assertEquals(new T(7), read(compiled, "t", "{ n: 7 }", DiagnosticsReceiver.throwing()));
    }

    /** A diagnostic inside a foreign value names the extern's schema by its canonical identity ([TSON-DATA] §2.2.1). */
    @Test
    void theScopeADiagnosticNamesIsACanonicalIdentity() {
        Tson tson = tson(Map.of());
        TsonLinkedSchema resolved = tson.resolve(SOURCES.get(HOST));
        Map<String, String> imported = new LinkedHashMap<>(resolved.entryOrigins());
        imported.values().removeIf(resolved.identity()::equals);
        TsonLinkedSchema linked = new TsonLinkedSchema(resolved.schema(), imported, resolved.enumForms(),
                resolved.nameTypes(), resolved.externs());

        List<Diagnostic> problems = new ArrayList<>();
        read(tson.treeRegistry().compile(linked), "h", "{ v: { n: x } }", problems::add);

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().endsWith("(read in the scope of example.test/lib.tn, through the "
                + "extern 'y' of example.test/host.tn; [TSON-SCHEMA] §7.9)"), problems.getFirst().message());
    }
}
