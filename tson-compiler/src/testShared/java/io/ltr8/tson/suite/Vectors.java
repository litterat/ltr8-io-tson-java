package io.ltr8.tson.suite;

import io.ltr8.tson.compiler.ast.RecordValue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Walking the corpus. {@code RUNNER.md}'s discovery rule is that there is no manifest: a vector is a
 * {@code <slug>.tn} subject and the {@code <slug>-expected.tn} sidecar beside it, under
 * {@code tests/<class>/<layer>/<bucket>/}.
 *
 * <p>Shared by both runners for the same reason {@link Sidecar} is: the layout is the corpus's, not either
 * implementation's, and a second copy of it is a second thing to get wrong.
 */
public final class Vectors {

    /** What a runner does with one vector, once its sidecar has been parsed. */
    public interface Check {
        void check(String bucket, Path subject, RecordValue sidecar) throws IOException;
    }

    private Vectors() {
    }

    /**
     * Every vector under one layer, as a dynamic test each. Aborts through {@link Assumptions} when the
     * corpus has no such layer -- a runner asking for a layer the pinned corpus predates is not a failure,
     * where a corpus that is missing entirely is (see {@link SuiteCheckout#assumeAvailable}).
     */
    public static Stream<DynamicTest> in(String conformanceClass, String layer, Check check) {
        SuiteCheckout.assumeAvailable();
        Path layerRoot = SuiteCheckout.testsRoot().orElseThrow().resolve(conformanceClass).resolve(layer);
        Assumptions.assumeTrue(Files.isDirectory(layerRoot),
                "no " + conformanceClass + "/" + layer + " layer in this suite checkout");

        try (Stream<Path> buckets = Files.list(layerRoot)) {
            return buckets.filter(Files::isDirectory).sorted()
                    .flatMap(bucket -> inBucket(conformanceClass, layer, bucket, check))
                    .toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<DynamicTest> inBucket(String conformanceClass, String layer, Path bucketDir, Check check) {
        String bucket = bucketDir.getFileName().toString();
        try (Stream<Path> files = Files.list(bucketDir)) {
            return files
                    .filter(path -> path.toString().endsWith(".tn") && !path.toString().endsWith("-expected.tn"))
                    .sorted()
                    .map(subject -> {
                        String slug = subject.getFileName().toString().replace(".tn", "");
                        Path sidecar = bucketDir.resolve(slug + "-expected.tn");
                        String name = conformanceClass + "/" + layer + "/" + bucket + "/" + slug;
                        return DynamicTest.dynamicTest(name,
                                () -> check.check(bucket, subject, Sidecar.parse(sidecar)));
                    })
                    .toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
