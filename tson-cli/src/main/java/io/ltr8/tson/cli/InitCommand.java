package io.ltr8.tson.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code tson init-example [<dir>]} -- writes a small, working example schema ({@code person.tn}) and
 * a matching data file ({@code person-data.tn}) to disk, so a newcomer can validate, edit, and re-run
 * entirely from the shell without writing any Java or hand-authoring a first schema. The example is a
 * short tour of TSON: a record, an enum, an optional field, built-in types (uuid/date), a nested
 * record, an array, and a field group ("one of"). The data file is self-describing -- a {@code
 * !!schema} header naming the schema plus a root {@code !person} type-ref -- so {@code tson validate}
 * needs no {@code --type}. The two files are the starting point the README's Getting Started section
 * walks through.
 *
 * <p>Refuses to overwrite either file if it already exists (exit 1) -- this is a scaffold, not
 * something that should ever clobber a user's own edits when re-run. A target directory that does not
 * exist yet is created; anything else that stops the files being written is also exit 1, never the
 * exit 70 this CLI reserves for a fault in the library itself.
 */
final class InitCommand {

    private static final String SCHEMA = """
            !!id:"https://example.com/2026/32/getting-started/person.tn?sha256=573155c579b4537d6d3cf17f0b04cda8b040cbff363578a74b45036d2bdf3426"
            !!meta:"https://tson.io/2026/32/m/meta.tn?sha256=5f2d3a4a85d23de9331edb303712e09ff20bee58f830fe5162276a3e6659dac8"
            !!import:"https://tson.io/2026/32/m/core.tn?sha256=5ed51742a0416c427cddf4f02a0f70a356e022927c1de78e7cda405462270a84"
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
            !!schema:"https://example.com/2026/32/getting-started/person.tn"
            !person {
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
            // Naming a directory that doesn't exist yet is how every other scaffolding command is used
            // (`git init dir`, `cargo new dir`), so create it rather than failing on the first write.
            Files.createDirectories(dir);
            Files.writeString(schema, SCHEMA);
            Files.writeString(data, DATA);
        } catch (IOException e) {
            // An unwritable target is the *user's* problem -- a read-only directory, a name already taken by
            // a regular file -- so it is this command's own exit 1, as documented above. Letting it out as an
            // UncheckedIOException would reach TsonCli's fault handler and print "this is a bug in tson" with
            // a stack trace over `tson init-example /nope`, which is the first command a newcomer runs.
            // `e` rather than `e.getMessage()`: an NIO filesystem exception's message is usually just the
            // path again, so the exception's own type is the only thing that says what went wrong -- the
            // name is taken by a regular file, the directory is read-only, and so on.
            System.err.println("could not write the example files to " + dir + " -- " + e);
            return 1;
        }

        System.out.println("Wrote " + schema + " and " + data + ".");
        System.out.println();
        System.out.println("Try it (the data names its own schema and type, so no --type is needed):");
        System.out.println("  tson validate " + schema + " " + data);
        System.out.println();
        System.out.println("Then edit person-data.tn to see the diagnostics -- for example: change a value's type");
        System.out.println("(age: \"thirty\"), remove a required field, use a role that isn't admin/member/guest,");
        System.out.println("or set both phone and mobile (the ( ... | ... ) group allows at most one). Edit the");
        System.out.println("schema person.tn and re-run `tson compile person.tn` to change the shape.");
        return 0;
    }
}
