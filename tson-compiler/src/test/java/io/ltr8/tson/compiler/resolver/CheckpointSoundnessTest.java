package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonCompiledVocabularyRegistry;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.suite.Sidecar;
import io.ltr8.tson.suite.SuiteBindings;
import io.ltr8.tson.suite.SuiteCheckout;
import io.ltr8.tson.suite.Vectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §3.4.1's soundness: a value a resolution reads at a checkpoint has the verdict it has in the full
 * closure. Every value a resolution reads while its entries are made ready is read again through the readers of the
 * closure once every entry is ready, and the two outcomes compared -- over the bundled schemas and every valid
 * {@code class2} schema and link subject in the corpus.
 */
class CheckpointSoundnessTest {

    /** One read at a checkpoint: what it gave, and how to read it again. */
    private record Read(boolean tree, Function<TsonCompiledSchema, Object> again, Object result,
                        RuntimeException failure) {
    }

    /** The reads a resolution made before every entry was ready, and the readers of its full closure. */
    private static final class Log implements SchemaResolver.ReadLog {
        final List<Read> reads = new ArrayList<>();
        Function<Boolean, TsonCompiledSchema> whole;

        @Override
        public void read(boolean tree, Function<TsonCompiledSchema, Object> again, Object result,
                         RuntimeException failure) {
            if (whole == null) {
                reads.add(new Read(tree, again, result, failure));
            }
        }

        @Override
        public void whole(Function<Boolean, TsonCompiledSchema> readers) {
            whole = readers;
        }

        /** Each read whose outcome in the full closure differs from its outcome at its checkpoint. */
        List<String> divergences() {
            List<String> divergences = new ArrayList<>();
            TsonCompiledSchema[] full = new TsonCompiledSchema[2];
            for (Read read : reads) {
                int mode = read.tree() ? 1 : 0;
                if (full[mode] == null) {
                    full[mode] = whole.apply(read.tree());
                }
                String atCheckpoint = outcome(read.result(), read.failure());
                String inFull;
                boolean same;
                try {
                    Object result = read.again().apply(full[mode]);
                    inFull = outcome(result, null);
                    same = read.failure() == null
                            && (Objects.equals(result, read.result()) || inFull.equals(atCheckpoint));
                } catch (RuntimeException failure) {
                    inFull = outcome(null, failure);
                    same = inFull.equals(atCheckpoint);
                }
                if (!same) {
                    divergences.add("checkpoint: " + atCheckpoint + "\n  full:       " + inFull);
                }
            }
            return divergences;
        }

        private static String outcome(Object result, RuntimeException failure) {
            return failure == null ? "ok " + result
                    : "failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    private static final TsonCompiledVocabularyRegistry CORE = TsonCompiledVocabularyRegistry.withStandardLibrary(
            SuiteBindings.context(), CheckpointSoundnessTest::fetch);

    /** The corpus's own schemas, by the path under {@code schemas/} their identity ends in, beside the bundled. */
    private static String fetch(String uri) {
        String prefix = "https://tson.io/test-suite/schemas/";
        String identity = uri.startsWith("//") ? "https:" + uri : uri;
        int query = identity.indexOf('?');
        identity = query < 0 ? identity : identity.substring(0, query);
        if (identity.startsWith(prefix)) {
            Path file = SuiteCheckout.schemasRoot().orElseThrow().resolve(identity.substring(prefix.length()));
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND, "no such schema", e);
            }
        }
        return TsonBundledSchemas.fetch(identity);
    }

    /** The reads resolving {@code source} made at its checkpoints. */
    private static Log resolved(String source) {
        Log log = new Log();
        TsonSchemaParser parser = new TsonSchemaParser(source);
        new SchemaResolver(CORE, LimitsPolicy.defaults(), log).resolveSchema(parser.parseSchemaDocument(),
                parser.schemaPositions());
        return log;
    }

    @Test
    void theBundledSchemasReadAtEachCheckpointAsInTheFullClosure() {
        int reads = 0;
        for (String id : TsonBundledSchemas.IDS) {
            if (TsonBundledSchemas.isKernel(id)) {
                continue; // read through its pre-loaded stand-in, at no checkpoint
            }
            Log log = resolved(TsonBundledSchemas.fetch(id));
            assertEquals(List.of(), log.divergences(), id);
            reads += log.reads.size();
        }
        assertTrue(reads > 0, "the bundled schemas read no value at a checkpoint");
    }

    @TestFactory
    Stream<DynamicTest> validSchemaVectors() {
        return Vectors.in("class2", "schema", CheckpointSoundnessTest::check);
    }

    @TestFactory
    Stream<DynamicTest> validLinkVectors() {
        return Vectors.in("class2", "link", CheckpointSoundnessTest::check);
    }

    /** The corpus vectors checked, and the reads they made at a checkpoint. */
    private static final AtomicInteger VECTORS = new AtomicInteger();
    private static final AtomicInteger READS = new AtomicInteger();

    private static void check(String bucket, Path subject, RecordValue sidecar) throws IOException {
        if (!bucket.equals("valid") || !Sidecar.outcomeOf(sidecar).equals("valid")) {
            return;
        }
        Log log = resolved(Sidecar.subjectSource(subject));
        assertEquals(List.of(), log.divergences());
        VECTORS.incrementAndGet();
        READS.addAndGet(log.reads.size());
    }

    /** A corpus whose subjects read nothing at a checkpoint would make the vectors above vacuous. */
    @AfterAll
    static void theVectorsReadValuesAtCheckpoints() {
        assertTrue(VECTORS.get() == 0 || READS.get() > VECTORS.get(),
                VECTORS + " vectors read " + READS + " values at a checkpoint");
    }
}
