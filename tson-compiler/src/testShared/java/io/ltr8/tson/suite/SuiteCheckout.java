package io.ltr8.tson.suite;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Locates the shared {@code ltr8-io-tson-test-suite} checkout the conformance tests read their
 * vectors and sidecar schemas from.
 *
 * <p><b>Two checkout shapes, deliberately.</b> A working copy has the suite beside this repo, which
 * is what a developer with both repos cloned already has and what an IDE run finds with no setup.
 * CI has no such sibling, so {@code scripts/fetch-references.sh} fetches a pinned copy into the
 * gitignored {@code .references/} directory. <b>The sibling wins</b> where both exist: a developer
 * editing vectors in their own checkout must see those edits, and a pinned copy shadowing them
 * would report on a corpus nobody is looking at. {@code -Dtson.testSuite.dir=...} overrides both.
 *
 * <p><b>Shared by both runners</b>, which is why it sits in {@code src/testShared} rather than either
 * module's own test tree: the Class 1 runner is in {@code tson-compiler}, where the lexer and both
 * grammars are, and the Class 2 runner is in {@code tson}, where the front door that resolves, links and
 * validates a schema is. Two copies of a checkout search is exactly the drift {@code RUNNER.md} exists to
 * stop.
 *
 * <p>An absent suite is a <em>skip</em>, never a failure, so a bare clone stays green. But a skip
 * nobody notices is how CI came to run none of the corpus for as long as it did, so
 * {@code TSON_REQUIRE_TEST_SUITE} inverts that: where it is set -- CI sets it -- an absent checkout
 * fails the build instead of aborting. Green then means the corpus ran, which is the only thing a
 * conformance signal is worth.
 */
public final class SuiteCheckout {

    /** Overrides the search entirely: the path to a suite checkout's own root directory. */
    private static final String OVERRIDE_PROPERTY = "tson.testSuite.dir";

    /** Set by CI: an absent checkout is a build failure here, not an abort. */
    private static final String REQUIRE_VARIABLE = "TSON_REQUIRE_TEST_SUITE";

    private SuiteCheckout() {
    }

    /**
     * Aborts the calling test when no checkout was found -- or fails it, where
     * {@code TSON_REQUIRE_TEST_SUITE} says the corpus was meant to be there.
     */
    public static void assumeAvailable() {
        if (root().isPresent()) {
            return;
        }
        String message = "ltr8-io-tson-test-suite not found (searched " + searchedLocations()
                + ") -- run scripts/fetch-references.sh";
        if (System.getenv(REQUIRE_VARIABLE) != null) {
            fail(message + "; " + REQUIRE_VARIABLE + " is set, so this is a failure rather than a skip");
        }
        Assumptions.abort(message + "; skipping conformance vectors");
    }

    /** The suite's {@code tests/} directory, if a checkout was found. */
    public static Optional<Path> testsRoot() {
        return root().map(r -> r.resolve("tests")).filter(Files::isDirectory);
    }

    /** The suite's {@code schemas/} directory, if a checkout was found. */
    public static Optional<Path> schemasRoot() {
        return root().map(r -> r.resolve("schemas")).filter(Files::isDirectory);
    }

    /**
     * Where the search looked, for a skip message that tells the reader what to do about it rather
     * than naming one path that happened to be tried last.
     */
    public static String searchedLocations() {
        return candidates().stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static Optional<Path> root() {
        return candidates().stream().filter(Files::isDirectory).findFirst();
    }

    /**
     * The module directory is the test working directory under both Gradle and every IDE tried, so
     * the repo root is its parent and the sibling checkout its grandparent.
     */
    private static List<Path> candidates() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        Path moduleDir = Paths.get("").toAbsolutePath();
        Path fetched = moduleDir.resolve("../.references/ltr8-io-tson-test-suite").normalize();
        Path sibling = moduleDir.resolve("../../ltr8-io-tson-test-suite").normalize();
        // An override is authoritative, not merely first: pointing at one checkout and silently
        // getting another is worse than finding none, and it is what makes the absent case testable.
        return (override == null || override.isBlank())
                ? List.of(sibling, fetched)
                : List.of(Paths.get(override).toAbsolutePath().normalize());
    }
}
