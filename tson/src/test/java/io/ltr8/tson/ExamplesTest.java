package io.ltr8.tson;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs every single-file program in {@code examples/} as a real subprocess -- the same
 * {@code java --module-path tson/build/modules --add-modules io.ltr8.tson examples/X.java} a reader
 * runs -- so an API change that breaks an example (a rename, a signature change, a thrown exception)
 * fails the build instead of silently rotting the docs.
 *
 * <p>Wired by the {@code :tson:test} task, which depends on {@code :tson:modules} (gathering the
 * runtime module jars) and passes the {@code examples/} and module directories as system properties.
 * Run from a bare IDE without those properties, it skips (rather than fails) -- like
 * {@code ConformanceSuiteTest}'s own "sibling not checked out" tolerance.
 */
class ExamplesTest {

    @TestFactory
    Stream<DynamicTest> everyExampleRunsCleanly() throws IOException {
        String examplesProp = System.getProperty("tson.examples.dir");
        String modulesProp = System.getProperty("tson.modules.dir");
        assumeTrue(examplesProp != null && modulesProp != null,
                "run via `./gradlew :tson:test` -- needs the :tson:modules module path");

        Path examplesDir = Path.of(examplesProp);
        Path modulesDir = Path.of(modulesProp);
        assumeTrue(Files.isDirectory(examplesDir), "examples dir missing: " + examplesDir);
        assumeTrue(Files.isDirectory(modulesDir), "modules dir missing: " + modulesDir);

        List<Path> examples;
        try (Stream<Path> files = Files.list(examplesDir)) {
            examples = files.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(examples.isEmpty(), "no example .java files found in " + examplesDir);

        return examples.stream().map(example ->
                DynamicTest.dynamicTest(example.getFileName().toString(),
                        () -> runExample(modulesDir, example)));
    }

    private static void runExample(Path modulesDir, Path example) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path outFile = Files.createTempFile("example-out", ".txt");
        try {
            Process process = new ProcessBuilder(
                    java.toString(),
                    "--module-path", modulesDir.toString(),
                    "--add-modules", "io.ltr8.tson",
                    example.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(outFile.toFile())
                    .start();

            // Redirecting to a file (rather than draining the pipe ourselves) keeps this timeout
            // effective even if an example were to hang or produce a lot of output.
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError(example.getFileName() + " did not finish within 2 minutes");
            }

            String output = Files.readString(outFile);
            assertEquals(0, process.exitValue(),
                    example.getFileName() + " exited " + process.exitValue() + ":\n" + output);
            assertFalse(output.isBlank(), example.getFileName() + " produced no output");
        } finally {
            Files.deleteIfExists(outFile);
        }
    }
}
