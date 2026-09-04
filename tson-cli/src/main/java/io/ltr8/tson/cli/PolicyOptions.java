package io.ltr8.tson.cli;

import io.ltr8.tson.TsonConfig;
import io.ltr8.tson.compiler.TsonLimitsPolicy;
import io.ltr8.tson.compiler.TsonUnicodePolicy;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The [TSON-DATA] §8.2 policy flags, parsed off one subcommand's argument list -- what {@code validate},
 * {@code compile} and {@code policy} apply to the {@link io.ltr8.tson.Tson} they build.
 *
 * <p><b>Why a CLI may configure this at all, when §8.2 asks that a relaxation not be silent.</b> The rule is
 * about ambient authority -- a policy read from the environment is invisible at the call site and absent
 * from review. A flag is the opposite: it is written down in the CI file or the Makefile that runs the
 * command, and this run's own report states the policy it was judged under. Without it the person running
 * the CLI is told which configuration refused their document and cannot change it, being the deployment the
 * report is describing.
 *
 * <p><b>Every flag is consumed here and nowhere else</b> ({@link #consume}), which is what lets the three
 * subcommands' own argument loops go on seeing only {@code --output} and their positionals.
 */
record PolicyOptions(TsonUnicodePolicy identifierPolicy, TsonUnicodePolicy tokenPolicy,
                     TsonLimitsPolicy limits) {

    /**
     * What {@code TsonConfig} applies to a run that configures nothing, restated here because this is where
     * the CLI decides whether a report is worth printing to a person ({@link CliPolicy#isDefault()}).
     * {@code PolicyOptionsTest} pins the restatement against a real {@code Tson}.
     */
    static final PolicyOptions DEFAULTS = new PolicyOptions(TsonUnicodePolicy.highlyRestrictive(),
            TsonUnicodePolicy.unrestricted(), TsonLimitsPolicy.defaults());

    /**
     * The level a script list brings with it on a surface whose default scans nothing.
     *
     * <p>{@code permitting(...)} is consulted only by a level that scans, so naming scripts for the token
     * surface -- Unrestricted by default -- would otherwise configure nothing at all. Single Script is the
     * level at which a list of combinations <em>is</em> the whole configuration: a single-script value
     * passes, and a mixed one passes only where the list names it. Anything stricter (ASCII-only) would
     * refuse the very scripts being admitted; anything looser stops scanning again.
     */
    private static final TsonUnicodePolicy.Level IMPLIED_BY_SCRIPTS = TsonUnicodePolicy.Level.SINGLE_SCRIPT;

    /** This run's policies on a fresh {@link TsonConfig}. */
    TsonConfig applyTo(TsonConfig config) {
        return config.identifierPolicy(identifierPolicy).tokenPolicy(tokenPolicy).limits(limits);
    }

    /**
     * Removes every policy flag from {@code args} and returns what they configure -- {@link #DEFAULTS} when
     * none was given.
     *
     * <p>Order-independent: the flags are collected first and the two policies assembled after, so a level
     * stated before or after the relaxations that ride on it reads the same.
     *
     * @throws UsageException a bad level or script name, a flag missing its value, or a relaxation named for
     *                        a surface whose stated level scans nothing
     */
    static PolicyOptions consume(List<String> args) {
        TsonUnicodePolicy.Level identifierLevel = null;
        TsonUnicodePolicy.Level tokenLevel = null;
        int maxDepth = TsonLimitsPolicy.DEFAULT_MAX_DEPTH;
        boolean perSegment = false;
        List<UnicodeScript[]> identifierScripts = new ArrayList<>();
        List<UnicodeScript[]> tokenScripts = new ArrayList<>();

        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--identifier-policy" -> identifierLevel = level(value(args, ++i, "--identifier-policy"));
                case "--token-policy" -> tokenLevel = level(value(args, ++i, "--token-policy"));
                case "--identifier-per-segment" -> perSegment = true;
                case "--identifier-scripts" ->
                        identifierScripts.add(scripts(value(args, ++i, "--identifier-scripts")));
                case "--token-scripts" -> tokenScripts.add(scripts(value(args, ++i, "--token-scripts")));
                case "--max-depth" -> maxDepth = depth(value(args, ++i, "--max-depth"));
                default -> rest.add(args.get(i));
            }
        }
        args.clear();
        args.addAll(rest);

        return new PolicyOptions(
                assemble("identifier", identifierLevel, DEFAULTS.identifierPolicy().level(), perSegment,
                        identifierScripts),
                assemble("token", tokenLevel, DEFAULTS.tokenPolicy().level(), false, tokenScripts),
                new TsonLimitsPolicy(maxDepth));
    }

    /**
     * A [TSON-DATA] §9.1 nesting-depth bound, as a positive integer.
     *
     * <p>Refused where it is not one, rather than clamped: a caller who wrote {@code --max-depth 0} meant
     * something, and reading a document under a bound they did not ask for is the one outcome that leaves
     * them unable to explain the result. The library refuses the same value for the same reason.
     */
    private static int depth(String value) {
        int depth;
        try {
            depth = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new UsageException("--max-depth needs a whole number, not '" + value + "'");
        }
        if (depth < 1) {
            throw new UsageException("--max-depth must be at least 1, not " + depth
                    + " -- a document has to be allowed at least one container to be read at all");
        }
        return depth;
    }

    /**
     * One surface's policy: the stated level or the default, then the relaxations layered on it.
     *
     * <p><b>A script list brings its own level where the default scans nothing</b> (see {@link
     * #IMPLIED_BY_SCRIPTS}) -- {@code --token-scripts Latin+Cyrillic} on its own means "values are one
     * script, or one of these combinations", which is what someone naming scripts for a surface is asking
     * for. A level the caller stated is never overridden.
     *
     * <p><b>A relaxation against a level that scans nothing is refused, not ignored.</b> {@code
     * --token-policy unrestricted --token-scripts Latin+Cyrillic} configures nothing whatever, and silently
     * accepting it would leave the caller believing a restriction is in force. {@code withTokenPolicy}
     * refuses a per-segment token policy on the same ground: a policy that cannot mean what it says is never
     * quietly accepted.
     */
    private static TsonUnicodePolicy assemble(String surface, TsonUnicodePolicy.Level stated,
                                              TsonUnicodePolicy.Level fallback, boolean perSegment,
                                              List<UnicodeScript[]> scripts) {
        boolean relaxed = perSegment || !scripts.isEmpty();
        TsonUnicodePolicy.Level level = stated;
        if (level == null) {
            level = relaxed && !TsonUnicodePolicy.of(fallback).checksScripts() ? IMPLIED_BY_SCRIPTS : fallback;
        }

        TsonUnicodePolicy policy = TsonUnicodePolicy.of(level);
        if (relaxed && !policy.checksScripts()) {
            String given = scripts.isEmpty() ? "--" + surface + "-per-segment"
                    : "--" + surface + "-scripts" + (perSegment ? " and --" + surface + "-per-segment" : "");
            throw new UsageException("--" + surface + "-policy " + spelling(level) + " scans no scripts, so the "
                    + given + " given with it would configure nothing -- state a level that scans, or drop the"
                    + " relaxation");
        }
        if (perSegment) {
            policy = policy.perSegment();
        }
        for (UnicodeScript[] combination : scripts) {
            policy = policy.permitting(combination);
        }
        return policy;
    }

    /**
     * A UTS #39 §5.2 level, under the enum's own names -- so a level copied out of {@code tson policy}'s
     * output is a level this accepts. {@code -} and {@code _} are interchangeable and case is ignored,
     * because {@code highly-restrictive} is what a person types and {@code HIGHLY_RESTRICTIVE} is what they
     * copy.
     */
    private static TsonUnicodePolicy.Level level(String value) {
        try {
            return TsonUnicodePolicy.Level.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException("unknown restriction level '" + value + "' -- expected one of "
                    + String.join(", ", levels()));
        }
    }

    /** One admitted combination, written {@code Latin+Cyrillic} -- UTS #39 §5.2's own device for Latn+Jpan. */
    private static UnicodeScript[] scripts(String value) {
        String[] names = value.split("\\+");
        UnicodeScript[] scripts = new UnicodeScript[names.length];
        for (int i = 0; i < names.length; i++) {
            try {
                scripts[i] = UnicodeScript.forName(names[i].strip());
            } catch (IllegalArgumentException e) {
                throw new UsageException("unknown script '" + names[i].strip() + "' in '" + value
                        + "' -- expected Unicode script names joined by '+', such as Latin+Cyrillic");
            }
        }
        return scripts;
    }

    /** The six levels as the flags spell them, for a usage message. */
    private static List<String> levels() {
        return java.util.Arrays.stream(TsonUnicodePolicy.Level.values()).map(PolicyOptions::spelling).toList();
    }

    private static String spelling(TsonUnicodePolicy.Level level) {
        return level.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String value(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new UsageException(flag + " requires a value");
        }
        return args.get(index);
    }
}
