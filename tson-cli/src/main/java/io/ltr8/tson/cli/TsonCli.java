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
              tson validate --type <name> [--output text|json|tson] <schema> <data...>
              tson compile [--output text|json|tson] <schema>

            options:
              --type <name>              (validate) the schema type to read each data file against
              --output text|json|tson    output format (default: text)
              --help, -h                 print this help

            exit codes: 0 ok, 1 validation/compile failure, 2 usage error""";

    private static final String VALIDATE_USAGE =
            "usage: tson validate --type <name> [--output text|json|tson] <schema> <data...>";

    private static final String COMPILE_USAGE =
            "usage: tson compile [--output text|json|tson] <schema>";

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
                case "validate" -> runValidate(rest);
                case "compile" -> runCompile(rest);
                default -> {
                    System.err.println("unknown command '" + subcommand + "' -- expected validate or compile");
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

    private static int runValidate(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(VALIDATE_USAGE);
            return 0;
        }
        String typeName = null;
        OutputFormat format = OutputFormat.TEXT;
        List<Path> positional = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--type" -> typeName = requireValue(args, ++i, "--type");
                case "--output" -> format = OutputFormat.parse(requireValue(args, ++i, "--output"));
                default -> positional.add(Path.of(arg));
            }
        }

        if (typeName == null) {
            throw new IllegalArgumentException("validate requires --type <name>");
        }
        if (positional.size() < 2) {
            throw new IllegalArgumentException(VALIDATE_USAGE);
        }
        Path schema = positional.get(0);
        List<Path> data = positional.subList(1, positional.size());
        return ValidateCommand.run(schema, typeName, data, format);
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
