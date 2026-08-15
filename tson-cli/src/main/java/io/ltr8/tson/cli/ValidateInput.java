package io.ltr8.tson.cli;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One document argument to {@code validate}: a file to open, or standard input ({@code -}).
 *
 * <p>The two differ in more than where the bytes come from, which is why this is a type rather than a
 * {@link Path} with a magic value. A file is opened twice -- once to classify it as schema or data,
 * once to read it -- and a stream cannot be rewound between the two, so <b>standard input is a data
 * document by definition</b> and never classified. That also makes {@code -} unambiguous: a file
 * genuinely named {@code -} is reachable as {@code ./-}, the usual Unix escape hatch.
 */
sealed interface ValidateInput {

    /** What this input reports under in a {@link FileReport} -- the argument exactly as given. */
    String name();

    /** A fresh stream over this input's bytes; the caller closes it. */
    InputStream open() throws IOException;

    record OfFile(Path path) implements ValidateInput {

        @Override
        public String name() {
            return path.toString();
        }

        @Override
        public InputStream open() throws IOException {
            return Files.newInputStream(path);
        }
    }

    record OfStdin() implements ValidateInput {

        @Override
        public String name() {
            return "-";
        }

        /**
         * Closing is suppressed: {@code System.in} belongs to the process, not to one read, and closing
         * it would outlive the run (a test substituting its own stream is the case that notices).
         */
        @Override
        public InputStream open() {
            return new FilterInputStream(System.in) {
                @Override
                public void close() {
                }
            };
        }
    }
}
