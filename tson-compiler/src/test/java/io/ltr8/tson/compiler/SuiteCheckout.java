package io.ltr8.tson.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

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
 * <p>Every caller treats an absent suite as a <em>skip</em>, never a failure: CI in a fork that
 * cannot reach the suite repo, and a bare clone, both stay green. What must not happen is a skip
 * nobody notices, which is why CI fetches the suite rather than relying on this fallback --
 * see {@link ConformanceSuiteTest}.
 */
final class SuiteCheckout {

    /** Overrides the search entirely: the path to a suite checkout's own root directory. */
    private static final String OVERRIDE_PROPERTY = "tson.testSuite.dir";

    private SuiteCheckout() {
    }

    /** The suite's {@code tests/} directory, if a checkout was found. */
    static Optional<Path> testsRoot() {
        return root().map(r -> r.resolve("tests")).filter(Files::isDirectory);
    }

    /** The suite's {@code schemas/} directory, if a checkout was found. */
    static Optional<Path> schemasRoot() {
        return root().map(r -> r.resolve("schemas")).filter(Files::isDirectory);
    }

    /**
     * Where the search looked, for a skip message that tells the reader what to do about it rather
     * than naming one path that happened to be tried last.
     */
    static String searchedLocations() {
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
        return (override == null || override.isBlank())
                ? List.of(sibling, fetched)
                : List.of(Paths.get(override).toAbsolutePath().normalize(), sibling, fetched);
    }
}
