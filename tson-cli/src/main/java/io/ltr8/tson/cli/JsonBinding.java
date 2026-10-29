package io.ltr8.tson.cli;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The schema and root type a JSON input is read against -- [TSON-JSON] §3.4's out-of-band binding, which is
 * the only route a JSON document on a command line has.
 *
 * <p>A TSON document names its schemas in its header's {@code !!import}s and its own root type with a type-ref,
 * so {@code validate} auto-classifies it and needs nothing. A JSON document's counterpart is §3.4's in-band route,
 * a root annotation object led by {@code $import} and {@code $type}, which the JSON reader does not read yet -- it
 * declines such a root as {@code NOT_IMPLEMENTED}. So the binding comes from the command line or nowhere.
 *
 * <p><b>One binding per run.</b> It applies to every JSON input, and two bindings mean two runs -- stated
 * rather than solved with per-file syntax, which would invent a shape the format does not have.
 *
 * <p><b>{@code schema} is a schema file or a schema identity</b>, as {@code --schema} was given. A file binds
 * by the {@code !!id} it declares -- matching is by identity either way, and the path is only how the file
 * reaches the run, so a local draft or a cached copy binds wherever it sits. A value naming an existing
 * regular file is a path; anything else is an identity. The test is the file system rather than the value's
 * shape, since a Windows path ({@code C:\x.tn}) parses as a URI with a scheme.
 */
record JsonBinding(String schema, String rootType) {

    /** The schema file {@link #schema} names, or empty when it is an identity. */
    Optional<Path> schemaFile() {
        try {
            Path file = Path.of(schema);
            return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether {@code name} is a JSON input. §3.1: "a JSON encoding of TSON data is a JSON file, and
     * {@code .json} is its extension".
     */
    static boolean isJsonFile(String name) {
        return name.regionMatches(true, name.length() - ".json".length(), ".json", 0, ".json".length());
    }
}
