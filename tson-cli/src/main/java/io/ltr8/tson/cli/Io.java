package io.ltr8.tson.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared file reading for {@link ValidateCommand}/{@link CompileCommand} -- unchecked, matching this codebase's own read/parse-stack convention. */
final class Io {

    private Io() {
    }

    static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
