package io.ltr8.tson.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for this module's own CLI ({@code BACKLOG.md}'s "Front door / ergonomics" -- a CLI,
 * ajv-cli-style): {@code tson validate --type <name> [--output text|json|tson] <schema> <data...>}
 * and {@code tson compile [--output text|json|tson] <schema>}. Hand-rolled argument parsing --
 * deliberately, matching this codebase's own "no external runtime dependencies" constraint; the
 * flag set is small and fixed enough that a real parsing library buys nothing here.
 *
 * <p>Exit codes are Unix-conventional: 0 valid/compiled cleanly, 1 a real validation/compile
 * failure, 2 a usage error (bad arguments, a file that can't be read) -- so a script or agent
 * shelling out gets a clean pass/fail signal without parsing prose.
 */
public final class TsonCli {

    private TsonCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 2;
        }
        String subcommand = args[0];
        List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));

        try {
            return switch (subcommand) {
                case "validate" -> runValidate(rest);
                case "compile" -> runCompile(rest);
                default -> {
                    System.err.println("unknown command '" + subcommand + "' -- expected validate or compile");
                    printUsage();
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            return 2;
        }
    }

    private static int runValidate(List<String> args) {
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
            throw new IllegalArgumentException(
                    "usage: tson validate --type <name> [--output text|json|tson] <schema> <data...>");
        }
        Path schema = positional.get(0);
        List<Path> data = positional.subList(1, positional.size());
        return ValidateCommand.run(schema, typeName, data, format);
    }

    private static int runCompile(List<String> args) {
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
            throw new IllegalArgumentException("usage: tson compile [--output text|json|tson] <schema>");
        }
        return CompileCommand.run(positional.get(0), format);
    }

    private static String requireValue(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args.get(index);
    }

    private static void printUsage() {
        System.err.println("""
                usage:
                  tson validate --type <name> [--output text|json|tson] <schema> <data...>
                  tson compile [--output text|json|tson] <schema>""");
    }
}
