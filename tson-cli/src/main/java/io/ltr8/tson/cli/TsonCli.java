package io.ltr8.tson.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for the {@code tson} command ({@code BACKLOG.md}'s "Front door / ergonomics" -- a CLI,
 * ajv-cli-style): {@code tson validate --type <name> [--output text|json|tson] <schema> <data...>}
 * and {@code tson compile [--output text|json|tson] <schema>}. Hand-rolled argument parsing --
 * deliberately, matching this codebase's own "no external runtime dependencies" constraint; the
 * flag set is small and fixed enough that a real parsing library buys nothing here.
 *
 * <p>Exit codes are Unix-conventional: 0 valid/compiled cleanly (or an explicit {@code --help}), 1 a
 * real validation/compile failure, 2 a usage error (bad arguments, a file that can't be read) -- so a
 * script or agent shelling out gets a clean pass/fail signal without parsing prose. Help requested
 * explicitly ({@code --help}/{@code -h}/{@code help}) prints to stdout and exits 0; usage shown
 * because of a mistake (no command, a bad flag) prints to stderr and exits 2.
 */
public final class TsonCli {

    private static final String USAGE = """
            usage:
              tson init-example [<dir>]
              tson validate [--output text|json|tson] <file>...
              tson compile [--output text|json|tson] <schema>
              tson hash <file>

            commands:
              init-example        write an example schema + data file to try, then edit and re-run validate
              validate    validate data files; each file is auto-classified as a schema or data
                          document, and a data file's !!schema selects its schema and its root type-ref
                          (!person) the type (or, with no !!schema, a base-syntax + built-in-type check)
              compile     check that a schema document itself resolves and compiles
              hash        compute a document's content hash and stamp it onto its !!id (?sha256=...)

            options:
              --output text|json|tson    output format (default: text)
              --help, -h                 print this help

            exit codes: 0 ok, 1 validation/compile failure, 2 usage error""";

    private static final String VALIDATE_USAGE =
            "usage: tson validate [--output text|json|tson] <file>...";

    private static final String COMPILE_USAGE =
            "usage: tson compile [--output text|json|tson] <schema>";

    private static final String INIT_USAGE =
            "usage: tson init-example [<dir>]   (writes an example person.tn and person-data.tn; default dir: .)";

    private static final String HASH_USAGE =
            "usage: tson hash <file>   (stamps the document's content hash onto its !!id as ?sha256=...)";

    private TsonCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0) {
            System.err.println(USAGE);
            return 2;
        }
        String subcommand = args[0];
        if (isHelpRequest(subcommand)) {
            System.out.println(USAGE);
            return 0;
        }
        List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));

        try {
            return switch (subcommand) {
                case "init-example" -> runInit(rest);
                case "validate" -> runValidate(rest);
                case "compile" -> runCompile(rest);
                case "hash" -> runHash(rest);
                default -> {
                    System.err.println("unknown command '" + subcommand + "' -- expected init-example, validate, compile, or hash");
                    System.err.println(USAGE);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return 2;
        }
    }

    private static int runInit(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(INIT_USAGE);
            return 0;
        }
        if (args.size() > 1) {
            throw new IllegalArgumentException(INIT_USAGE);
        }
        Path dir = args.isEmpty() ? Path.of(".") : Path.of(args.get(0));
        return InitCommand.run(dir);
    }

    private static int runHash(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(HASH_USAGE);
            return 0;
        }
        if (args.size() != 1) {
            throw new IllegalArgumentException(HASH_USAGE);
        }
        return HashCommand.run(Path.of(args.get(0)));
    }

    private static int runValidate(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(VALIDATE_USAGE);
            return 0;
        }
        OutputFormat format = OutputFormat.TEXT;
        List<Path> files = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--output" -> format = OutputFormat.parse(requireValue(args, ++i, "--output"));
                default -> files.add(Path.of(arg));
            }
        }

        if (files.isEmpty()) {
            throw new IllegalArgumentException(VALIDATE_USAGE);
        }
        return ValidateCommand.run(files, format);
    }

    private static int runCompile(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(COMPILE_USAGE);
            return 0;
        }
        OutputFormat format = OutputFormat.TEXT;
        List<Path> positional = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--output" -> format = OutputFormat.parse(requireValue(args, ++i, "--output"));
                default -> positional.add(Path.of(arg));
            }
        }

        if (positional.size() != 1) {
            throw new IllegalArgumentException(COMPILE_USAGE);
        }
        return CompileCommand.run(positional.get(0), format);
    }

    private static String requireValue(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args.get(index);
    }

    /** Top-level help: {@code --help}, {@code -h}, or a bare {@code help} subcommand. */
    private static boolean isHelpRequest(String arg) {
        return arg.equals("--help") || arg.equals("-h") || arg.equals("help");
    }

    /** A {@code --help}/{@code -h} flag anywhere in a subcommand's own arguments. */
    private static boolean hasHelpFlag(List<String> args) {
        return args.contains("--help") || args.contains("-h");
    }
}
