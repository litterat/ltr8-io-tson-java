package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Entry point for the {@code tson} command ({@code BACKLOG.md}'s "Front door / ergonomics" -- a CLI,
 * ajv-cli-style): {@code tson validate [--output text|json|tson] <file|->...} and {@code tson compile
 * [--output text|json|tson] <schema>}. Hand-rolled argument parsing --
 * deliberately, matching this codebase's own "no external runtime dependencies" constraint; the
 * flag set is small and fixed enough that a real parsing library buys nothing here.
 *
 * <p><b>Help is two levels, and the split is what keeps either readable.</b> {@code tson --help} lists the
 * commands and nothing else; {@code tson <command> --help} gives that command what it needs -- what it does,
 * its own options, its exit codes, and the shared {@code POLICY_OPTIONS} block for the three that judge a
 * name. A single page carrying all of it made the policy flags a wall of text in front of someone who only
 * wanted to know what {@code hash} does. A usage <em>error</em> still prints the short one-line usage plus
 * the command list, since what a caller needs there is the shape of the invocation they got wrong.
 *
 * <p>Exit codes are Unix-conventional: 0 valid/compiled cleanly (or an explicit {@code --help}), 1 a
 * real validation/compile failure, 2 a usage error (bad arguments, a file that can't be read), and 70
 * ({@code EX_SOFTWARE}) this library failing to reach a verdict rather than anything wrong with the input --
 * so a script or agent shelling out gets a clean pass/fail signal without parsing prose, and never reads a
 * bug as a verdict. Help requested explicitly ({@code --help}/{@code -h}/{@code help}) prints to stdout and
 * exits 0; usage shown because of a mistake (no command, a bad flag) prints to stderr and exits 2.
 *
 * <p>70 covers the two ways a run can end without a verdict, and they print differently: a gap in this
 * library ({@code UnsupportedOperationException}) renders as {@link #notImplemented} -- its own message,
 * which usually names the workaround -- while an internal fault renders as {@link #internalError}, with the
 * stack trace and the request to report it.
 */
public final class TsonCli {

    private static final String USAGE = """
            usage: tson <command> [options]

            commands:
              init-example [<dir>]                 write an example schema + data file to try
              validate [<options>] <file|->...     validate data documents against the schemas they name
              compile [<options>] <schema>         check that a schema document resolves and compiles
              policy [<options>]                   print the Unicode policy this run would apply
              hash <file>                          stamp a document's content hash onto its own !!id

            options:
              --output text|json|tson    output format (default: text)
              --help, -h                 this help; `tson <command> --help` for a command's own options

            exit codes: 0 ok, 1 checked and rejected, 2 usage error, 69 a schema could not be obtained,
                        75 a schema could not be reached (retry may help), 78 a type has no Java class
                        in this tool, 70 not implemented / internal error""";

    /**
     * The [TSON-DATA] §8.2 flags, printed by the help of each command that takes them.
     *
     * <p>Here rather than in the top-level usage because that is a list of commands, and a caller who has
     * chosen one wants its options and not everything the tool can do. Three commands share the block, so it
     * is stated once -- a second copy is a second thing to keep true.
     */
    private static final String POLICY_OPTIONS = """
            policy options -- [TSON-DATA] §8.2 name hygiene, which is what decides whether a name is
            refused here but accepted elsewhere. Every report states what it was judged under.
              --identifier-policy <level>   level for identifiers (default: highly-restrictive)
              --identifier-per-segment      apply it per _/- segment rather than the whole identifier,
                                            which admits url_адрес while still refusing id_pаy
              --identifier-scripts <A+B>    admit one script combination over and above the level,
                                            e.g. Latin+Cyrillic (repeatable)
              --token-policy <level>        level for values (default: unrestricted, which scans nothing)
              --token-scripts <A+B>         the same for values; on its own it raises the token level to
                                            single-script, a list of combinations being no configuration
                                            at all under a level that scans nothing (repeatable)

            <level> is a UTS #39 §5.2 restriction level: ascii-only, single-script, highly-restrictive,
            moderately-restrictive, minimally-restrictive, unrestricted. The spelling `tson policy` prints
            (HIGHLY_RESTRICTIVE) is accepted too, so its output is usable as its input.

            Reach for the unit or a named combination before dropping a level: both keep the rule
            everywhere else. `tson policy` with the same flags prints what they would apply.""";

    private static final String VALIDATE_USAGE =
            "usage: tson validate [--output text|json|tson] [<policy options>] <file|->...   (`-` reads one"
                    + " data document from stdin)";

    private static final String VALIDATE_HELP = """
            usage: tson validate [--output text|json|tson] [<policy options>] <file|->...

            Validates data documents. Each file is auto-classified as a schema document (its header
            carries !!meta) or a data document, by content and never by filename, so the order of the
            arguments does not matter. A data file's own !!schema selects the schema and its root
            type-ref (!person) the type; a file with no !!schema gets a base-syntax and built-in-type
            check instead. There is no --type and no --schema, and nothing is fetched over the network:
            a schema no file here declares is reported as SCHEMA_UNAVAILABLE, which is exit 69 and not a
            verdict on your document.

            `-` reads one data document from standard input, at most once, always data, and is reported
            under the name "-" (a file really named - is reachable as ./-). Schemas must be files.

            options:
              --output text|json|tson    output format (default: text)

            """ + POLICY_OPTIONS + """


            exit codes: 0 every data file valid, 1 at least one checked and rejected, 2 usage error,
                        69 a schema nothing would supply, 75 a schema that could not be reached,
                        78 a type with no Java class in this tool, 70 a gap in this library or a fault""";

    private static final String COMPILE_USAGE =
            "usage: tson compile [--output text|json|tson] [<policy options>] <schema>";

    private static final String COMPILE_HELP = """
            usage: tson compile [--output text|json|tson] [<policy options>] <schema>

            Resolves, links, registers and compiles one schema document and reports whether it did so
            cleanly -- every problem it has at the first phase that finds any, each naming the
            declaration it came from and where that declaration is in the source. Needs no --type: this
            checks the whole document, not one type against a value.

            options:
              --output text|json|tson    output format (default: text)

            """ + POLICY_OPTIONS + """


            exit codes: 0 compiled cleanly, 1 checked and rejected, 2 usage error, 69 an !!import or
                        !!meta of its own could not be obtained, 75 one that could not be reached,
                        78 a type with no Java class in this tool, 70 a gap in this library or a fault""";

    private static final String INIT_USAGE =
            "usage: tson init-example [<dir>]   (writes an example person.tn and person-data.tn; default dir: .)";

    private static final String INIT_HELP = """
            usage: tson init-example [<dir>]

            Writes an example schema (person.tn) and a data document that validates against it
            (person-data.tn) into <dir>, defaulting to the current directory. Edit either and re-run
            `tson validate person.tn person-data.tn` to see what changes. Refuses to overwrite an
            existing file.""";

    private static final String POLICY_USAGE =
            "usage: tson policy [--output text|json|tson] [<policy options>]   (the §8.2 Unicode policy this"
                    + " run would apply; see `tson policy --help` for the policy options)";

    private static final String POLICY_HELP = """
            usage: tson policy [--output text|json|tson] [<policy options>]

            Prints the [TSON-DATA] §8.2 identifier and token policy this run would apply, and the Unicode
            data version behind them -- what decides whether a name is refused here but accepted
            elsewhere, which is in neither your document nor your schema. The same record rides on every
            validate and compile report; this prints it with no document in hand, so a generator can
            conform before it writes rather than after being refused.

            It takes the policy options itself, so it doubles as their dry run: `tson policy
            --identifier-policy ascii-only` prints exactly what a validate under that flag would apply.

            options:
              --output text|json|tson    output format (default: text)

            """ + POLICY_OPTIONS + """


            Exit code is always 0: this is a question about this processor, and it has an answer whatever
            the state of anyone's documents.""";

    private static final String HASH_USAGE =
            "usage: tson hash <file>   (stamps the document's content hash onto its !!id as ?sha256=...)";

    private static final String HASH_HELP = """
            usage: tson hash <file>

            Computes the document's content hash ([TSON-DATA] §2.2.1 -- SHA-256 over every byte after the
            !!id line) and stamps it onto that !!id as ?sha256=<hex>, in place and idempotently. Requires
            an !!id; the id line is excluded from the hash, which is what lets a document carry its own.

            A pinned reference is verified on use: if a data file's !!schema, or a schema's !!import or
            !!meta, carries ?sha256=..., validate hashes what it obtained and errors on a mismatch. The
            pin is not identity, so a pinned reference and a plain one still resolve to the same
            schema.""";

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
                case "policy" -> runPolicy(rest);
                case "hash" -> runHash(rest);
                default -> {
                    System.err.println("unknown command '" + subcommand
                            + "' -- expected init-example, validate, compile, policy, or hash");
                    System.err.println(USAGE);
                    yield 2;
                }
            };
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return 2;
        } catch (UnsupportedOperationException e) {
            return notImplemented(e);
        } catch (RuntimeException e) {
            return internalError(e);
        }
    }

    /**
     * A gap in this library, reported as one: the exception's own message is the whole report, and the exit
     * code is 70 ({@code EX_SOFTWARE}) -- the same code as a fault, because neither is a verdict on the
     * document.
     *
     * <p>The framing is what differs, and it follows the project's exception-classification policy: an {@code
     * UnsupportedOperationException} across this pipeline means <i>this library hasn't implemented that
     * yet</i>, a thing the person running the command can often work around, and these messages routinely
     * name the workaround ("naming the inner form in its own declaration ... is the way to write this
     * today"). Wrapping that in a bug report and a stack trace buries the one line worth reading, and asks
     * for a report of something already known. The please-report-it treatment stays with {@link
     * #internalError}, where a broken internal invariant really is news.
     */
    static int notImplemented(UnsupportedOperationException e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        System.err.println("not implemented yet: " + message);
        System.err.println("This is a gap in tson, not a problem with your document"
                + " -- it could not be checked, which is not the same as invalid.");
        return 70;
    }

    /**
     * The exit code for a run that produced problems.
     *
     * <p><b>Rank by who must act first; permanence breaks the tie.</b> A mixed run is the normal path -- the
     * pipeline collects rather than abandoning -- so the order is a stated rule rather than an accident of
     * which check ran first. The party who must act before anyone else's work counts is the one the code
     * names, because until they do, every other fix is wasted. Permanence decides only between ranks where
     * nobody present can act at all.
     *
     * <p>At a command line the actors present are the runner and their files:
     * <ol>
     *   <li><b>70</b> -- nobody present; it needs a release of this library. The more permanent of the two
     *       nobody-present ranks, which is what puts it above 78.</li>
     *   <li><b>78</b> -- nobody present; it needs a differently-wired application. {@code tson} has no flag
     *       that supplies a binding, which is exactly why this is not the runner's to fix.</li>
     *   <li><b>69</b> -- the runner: edit the reference, or the allow-list it is checked against.</li>
     *   <li><b>75</b> -- the runner, by rerunning.</li>
     *   <li><b>1</b> -- the runner: edit the document.</li>
     * </ol>
     *
     * <p><b>1 means checked and rejected</b>, not "invalid". A [TSON-DATA] §8.2 name-hygiene refusal is one,
     * though §8.2 calls it a fifth outcome that must not be reported in any of §8.1's four categories: that
     * rule is about which layer detected it, and this code answers what the caller should do now. A refusal
     * was checked and declined and the sender holds the fix, which is what 1 means. What is genuinely
     * portable-sensitive about a refusal -- that another deployment may accept the same document -- is
     * carried by the diagnostic's own code and by the run's own {@link CliPolicy}.
     * {@code SPEC-FEEDBACK.md} #14 proposes §8.2 stop asking for the separate channel.
     *
     * <p><b>78 rather than 70 for a bind mismatch</b>, because {@code EX_CONFIG} is "found in an
     * unconfigured or misconfigured state" and unconfigured is what this is: no class is registered for a
     * type the schema needs. 70 would say this library cannot do it, which is the reading {@link
     * io.ltr8.tson.compiler.TsonMissingBindingException} exists to prevent.
     *
     * <p>Each note goes to stderr so the report on stdout stays exactly what {@code --output json|tson}
     * promises.
     */
    static int exitCodeFor(Collection<Diagnostic.Code> codes) {
        if (codes.contains(Diagnostic.Code.NOT_IMPLEMENTED)) {
            System.err.println("note: some of this could not be checked -- a construct is not implemented yet"
                    + " (see the NOT_IMPLEMENTED entries above). That is a gap in tson, not a problem with your"
                    + " document.");
            return 70;
        }
        if (codes.contains(Diagnostic.Code.BIND_MISMATCH)) {
            // Deliberately not naming TsonConfig.bindings or DataNameBinder the way the diagnostic's own
            // message does: neither has a command-line surface, so the remedy it states is not one the
            // person reading this can carry out.
            System.err.println("note: some of this could not be checked -- a type the schema needs has no"
                    + " Java class in this tool (see the BIND_MISMATCH entries above). Nothing is wrong with"
                    + " your document; checking it needs an application that binds that type.");
            return 78;
        }
        if (codes.stream().anyMatch(PERMANENTLY_UNAVAILABLE::contains)) {
            System.err.println("note: some of this could not be checked -- a schema could not be obtained"
                    + " (see the SCHEMA_ entries above). Nothing here has read that schema, so nothing here"
                    + " is saying your document, or that schema, is wrong. Rerunning will not obtain it.");
            return 69;
        }
        if (codes.stream().anyMatch(TEMPORARILY_UNAVAILABLE::contains)) {
            System.err.println("note: some of this could not be checked -- a schema could not be reached"
                    + " (see the SCHEMA_ entries above). Nothing here has read that schema. This one may"
                    + " succeed if you run it again.");
            return 75;
        }
        return 1;
    }

    /** Fetch codes a rerun cannot fix: the reference, or the allow-list it is checked against, has to change. */
    private static final Set<Diagnostic.Code> PERMANENTLY_UNAVAILABLE = Set.of(
            Diagnostic.Code.SCHEMA_NOT_PERMITTED, Diagnostic.Code.SCHEMA_NOT_FOUND,
            Diagnostic.Code.SCHEMA_TOO_LARGE);

    /** Fetch codes a rerun might fix: nothing about the request was wrong, the world did not answer. */
    private static final Set<Diagnostic.Code> TEMPORARILY_UNAVAILABLE = Set.of(
            Diagnostic.Code.SCHEMA_UNREACHABLE, Diagnostic.Code.SCHEMA_TIMEOUT);

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
     *
     * <p>Everything that isn't a declared gap lands here -- {@link #notImplemented} takes {@code
     * UnsupportedOperationException} first, since a gap is known already and has nothing to report.
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
            System.out.println(INIT_HELP);
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
            System.out.println(HASH_HELP);
            return 0;
        }
        if (args.size() != 1) {
            throw new UsageException(HASH_USAGE);
        }
        return HashCommand.run(Path.of(args.get(0)));
    }

    private static int runValidate(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(VALIDATE_HELP);
            return 0;
        }
        PolicyOptions policies = PolicyOptions.consume(args);
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
        return ValidateCommand.run(inputs, format, policies);
    }

    private static int runCompile(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(COMPILE_HELP);
            return 0;
        }
        PolicyOptions policies = PolicyOptions.consume(args);
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
        return CompileCommand.run(positional.get(0), format, policies);
    }

    private static int runPolicy(List<String> args) {
        if (hasHelpFlag(args)) {
            System.out.println(POLICY_HELP);
            return 0;
        }
        PolicyOptions policies = PolicyOptions.consume(args);
        OutputFormat format = OutputFormat.TEXT;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--output")) {
                format = OutputFormat.parse(requireValue(args, ++i, "--output"));
            } else {
                throw new UsageException(POLICY_USAGE);
            }
        }
        return PolicyCommand.run(format, policies);
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
