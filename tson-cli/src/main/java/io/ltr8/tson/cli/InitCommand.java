package io.ltr8.tson.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code tson init [<dir>]} -- writes a small, working example schema ({@code person.tn}) and a
 * matching data file ({@code person-data.tn}) to disk, so a newcomer can validate, edit, and re-run
 * entirely from the shell without writing any Java or hand-authoring a first schema. The two files
 * are the whole starting point the README's Getting Started section walks through.
 *
 * <p>Refuses to overwrite either file if it already exists (exit 1) -- {@code init} is a scaffold,
 * not something that should ever clobber a user's own edits when re-run.
 */
final class InitCommand {

    private static final String SCHEMA = """
            !!id:"https://example.com/2026/32/getting-started/person-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            @doc:"Example schema from `tson init`. Edit this file or person-data.tn, then re-run tson validate to see what changes. role is an enum; the ? on email makes it optional."
            {
              role => !enum [admin member guest]

              person => {
                name: text
                age: int32
                active: boolean
                role: role
                joined: date
                email: text?
              }
            }
            """;

    private static final String DATA = """
            {
              name: "Ada Lovelace"
              age: 30
              active: true
              role: member
              joined: !date 1843-12-10
            }
            """;

    private InitCommand() {
    }

    /** @return exit code: 0 files written, 1 a target file already exists or couldn't be written */
    static int run(Path dir) {
        Path schema = dir.resolve("person.tn");
        Path data = dir.resolve("person-data.tn");

        if (Files.exists(schema) || Files.exists(data)) {
            System.err.println("refusing to overwrite an existing person.tn or person-data.tn in "
                    + dir + " -- move or delete them first");
            return 1;
        }

        try {
            Files.writeString(schema, SCHEMA);
            Files.writeString(data, DATA);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        System.out.println("Wrote " + schema + " and " + data + ".");
        System.out.println();
        System.out.println("Try it:");
        System.out.println("  tson validate --type person " + schema + " " + data);
        System.out.println();
        System.out.println("Then edit person-data.tn -- change a value's type, remove a required field, or use");
        System.out.println("an enum member that isn't admin/member/guest -- and run that command again to see");
        System.out.println("the diagnostics. Edit person.tn and re-run `tson compile person.tn` to change the shape.");
        return 0;
    }
}
