package io.ltr8.tson.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code tson init-example [<dir>]} -- writes a small, working example schema ({@code person.tn}) and
 * a matching data file ({@code person-data.tn}) to disk, so a newcomer can validate, edit, and re-run
 * entirely from the shell without writing any Java or hand-authoring a first schema. The example is a
 * short tour of TSON: a record, an enum, an optional field, built-in types (uuid/date), a nested
 * record, an array, and a field group ("one of"). The two files are the starting point the README's
 * Getting Started section walks through.
 *
 * <p>Refuses to overwrite either file if it already exists (exit 1) -- this is a scaffold, not
 * something that should ever clobber a user's own edits when re-run.
 */
final class InitCommand {

    private static final String SCHEMA = """
            !!id:"https://example.com/2026/32/getting-started/person-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            @doc:"An example schema from `tson init-example` -- a short tour of TSON. Edit this file or person-data.tn, then re-run tson validate to see what changes."
            {
              role => !enum [admin member guest]

              address => {
                street: text
                city: text
                country: text
              }

              person => {
                id: uuid
                name: text
                age: int32
                role: role
                joined: date
                email: text?
                address: address
                skills: [text]
                ( phone: text | mobile: text )?
              }
            }
            """;

    private static final String DATA = """
            {
              id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
              name: "Ada Lovelace"
              age: 30
              role: member
              joined: !date 1843-12-10
              address: {
                street: "12 Analytical Ave"
                city: "London"
                country: "UK"
              }
              skills: [ mathematics analysis "computing" ]
              mobile: "+44 20 7946 0958"
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
        System.out.println("Then edit person-data.tn to see the diagnostics -- for example: change a value's type");
        System.out.println("(age: \"thirty\"), remove a required field, use a role that isn't admin/member/guest,");
        System.out.println("or set both phone and mobile (the ( ... | ... ) group allows at most one). Edit the");
        System.out.println("schema person.tn and re-run `tson compile person.tn` to change the shape.");
        return 0;
    }
}
