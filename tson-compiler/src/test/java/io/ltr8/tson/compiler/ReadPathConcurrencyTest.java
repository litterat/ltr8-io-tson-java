package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Concurrent first use of one {@code Tson}'s registries and bind context.
 *
 * <p><b>Both caches on the read path fill on a miss, and both used to fail the loser of the race.</b> The
 * shape was the same twice over: check the cache, do the work, then <em>register</em> the result through a
 * method that throws when the key is already there. Two threads reaching the same cold entry together both
 * did the work, and the second was told "already registered" -- surfacing not as a crash but as a {@code
 * SCHEMA_ERROR} against a document with nothing wrong with it, non-deterministically, on the first
 * concurrent requests a process ever serves.
 *
 * <p>Nothing else on the read path is shared mutable state: a {@code Lexer}/{@code TsonDataStream} is built
 * per read, and every compiled reader is immutable (the whole {@code reader} package holds exactly one
 * non-final instance field, {@code CompiledReaders.delegate}, and it is {@code volatile}).
 *
 * <p>Each attempt starts from a cold cache, because that is the only window either race has; the loop makes
 * the interleaving likely rather than lucky. Both failed on the first attempt before the fix.
 */
class ReadPathConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ATTEMPTS = 20;

    private static final String ID = "https://example.test/concurrent.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/concurrent.tn"
            !!meta:"https://tson.io/2026/33/m/meta.tn"
            !!import:"https://tson.io/2026/33/m/core.tn"
            { person => { name: text  age: int32 } }
            """;

    public record Person(String name, long age) {
    }

    /** Runs {@code task} on {@link #THREADS} threads released together, and returns what each produced. */
    private static <T> List<T> inParallel(Callable<T> task) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                tasks.add(() -> {
                    gate.await();
                    return task.call();
                });
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(tasks)) {
                results.add(f.get());   // an ExecutionException here is the failure this test exists for
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * The schema half: a data document's {@code !!schema} resolving for the first time. The loser used to
     * get {@code TsonSchemaValidationException: a schema is already registered under '...'} out of
     * {@code TsonSchemaRegistry.register}; it now takes the winner's entry, so every thread sees one
     * schema rather than the registry holding a second, equivalent one.
     */
    @Test
    void concurrentFirstResolutionOfOneSchemaYieldsOneSchema() throws Exception {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            TsonSchemaSource source = uri -> {
                if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                    return SCHEMA;
                }
                throw new IllegalStateException("unexpected fetch: " + uri);
            };
            TsonCompiledMetaRegistry core =
                    TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
            TsonCompiledSchemaRegistry registry = TsonCompiledSchemaRegistry.tree(core);

            List<TsonCompiledSchema> compiled = inParallel(() -> registry.get(ID));

            for (TsonCompiledSchema each : compiled) {
                assertSame(compiled.get(0), each, "one compiled schema, shared by every thread");
            }
            TsonLinkedSchema linked = core.schemaRegistry().get(ID).orElseThrow();
            assertSame(linked, core.resolveLinked(ID), "and one registered linked form, not two");
        }
    }

    /**
     * The binding half: the first schemaless read of a class, whose descriptor {@code DataBindContext}
     * caches. The loser used to get {@code DataBindException: Class already registered}, reported as
     * {@code SCHEMA_ERROR: cannot bind to class ...} -- an invalid verdict on a valid document.
     */
    @Test
    void concurrentFirstSchemalessBindOfOneClassYieldsOneDescriptor() throws Exception {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            TsonObjectReader reader = new TsonObjectReader(TsonAtomContext.defaultContext());

            List<Person> people = inParallel(() -> reader.read("{ name: \"Ada\"  age: 36 }", Person.class));

            for (Person each : people) {
                assertEquals(new Person("Ada", 36), each);
            }
        }
    }
}
