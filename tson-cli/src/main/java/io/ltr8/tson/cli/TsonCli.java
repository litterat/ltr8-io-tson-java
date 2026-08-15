package io.ltr8.tson.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for the {@code tson} command ({@code BACKLOG.md}'s "Front door / ergonomics" -- a CLI,
 * ajv-cli-style): {@code tson validate [--output text|json|tson] <file|->...} and {@code tson compile
 * [--output text|json|tson] <schema>}. Hand-rolled argument parsing --
 * deliberately, matching this codebase's own "no external runtime dependencies" constraint; the
 * flag set is small and fixed enough that a real parsing library buys nothing here.
 *
 * <p>Exit codes are Unix-conventional: 0 valid/compiled cleanly (or an explicit {@code --help}), 1 a
 * real validation/compile failure, 2 a usage error (bad arguments, a file that can't be read), and 70
 * ({@code EX_SOFTWARE}) a fault in this library rather than anything wrong with the input -- so a script or
 * agent shelling out gets a clean pass/fail signal without parsing prose, and never reads a bug as a
 * verdict. Help requested explicitly ({@code --help}/{@code -h}/{@code help}) prints to stdout and exits 0;
 * usage shown because of a mistake (no command, a bad flag) prints to stderr and exits 2.
 */
public final class TsonCli {

    private static final String USAGE = """
            usage:
              tson init-example [<dir>]
              tson validate [--output text|json|tson] <file|->...
              tson compile [--output text|json|tson] <schema>
              tson hash <file>

            commands:
              init-example        write an example schema + data file to try, then edit and re-run validate
              validate    validate data documents; each file is auto-classified as a schema or data
                          document, and a data file's !!schema selects its schema and its root type-ref
                          (!person) the type (or, with no !!schema, a base-syntax + built-in-type check).
                          `-` reads one data document from standard input, reported under the name "-"
                          (a file really named - is reachable as ./-); schemas must be files
              compile     check that a schema document itself resolves and compiles
              hash        compute a document's content hash and stamp it onto its !!id (?sha256=...)

            options:
              --output text|json|tson    output format (default: text)
              --help, -h                 print this help

            exit codes: 0 ok, 1 validation/compile failure, 2 usage error, 70 internal error""";

    private static final String VALIDATE_USAGE =
            "usage: tson validate [--output text|json|tson] <file|->...   (`-` reads one data document from stdin)";

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
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return 2;
        } catch (RuntimeException e) {
            return internalError(e);
        }
    }

    /**
     * A fault in this library, reported as one: the stack trace goes to stderr and the exit code is 70
     * ({@code EX_SOFTWARE}), distinct from every documented outcome.
     *
     * <p>The distinctness is the point. {@code Tson.validate} deliberately rethrows anything that isn't a
     * base-syntax failure rather than dressing a bug up as a diagnostic, and that care is wasted if the CLI
     * lands it on 1 -- a script reading the exit code would be told the document is invalid, and the person
     * running it would go looking for the mistake in their own file. Nothing here can distinguish a fault
     * from a verdict after the fact, so the two get separate codes and the trace is printed rather than
     * summarized.
     */
    static int internalError(RuntimeException e) {
        System.err.println("internal error: " + e);
        System.err.println("This is a bug in tson, not a problem with your document."
                + " Please report it with the stack trace below.");
        e.printStackTrace(System.err);
        return 70;
    }

    private static int runInit(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(INIT_USAGE);
            return 0;
        }
        if (args.size() > 1) {
            throw new UsageException(INIT_USAGE);
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
            throw new UsageException(HASH_USAGE);
        }
        return HashCommand.run(Path.of(args.get(0)));
    }

    private static int runValidate(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(VALIDATE_USAGE);
            return 0;
        }
        OutputFormat format = OutputFormat.TEXT;
        List<ValidateInput> inputs = new ArrayList<>();
        int stdin = 0;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--output" -> format = OutputFormat.parse(requireValue(args, ++i, "--output"));
                case "-" -> {
                    stdin++;
                    inputs.add(new ValidateInput.OfStdin());
                }
                default -> inputs.add(new ValidateInput.OfFile(Path.of(arg)));
            }
        }

        if (stdin > 1) {
            // There is one standard input and it is consumed by the first read, so a second `-` could only
            // ever report an empty document. Saying so beats validating nothing and calling it valid.
            throw new UsageException("standard input can only be read once, but `-` was given "
                    + stdin + " times");
        }
        if (inputs.isEmpty()) {
            throw new UsageException(VALIDATE_USAGE);
        }
        return ValidateCommand.run(inputs, format);
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
            throw new UsageException(COMPILE_USAGE);
        }
        return CompileCommand.run(positional.get(0), format);
    }

    private static String requireValue(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new UsageException(flag + " requires a value");
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
