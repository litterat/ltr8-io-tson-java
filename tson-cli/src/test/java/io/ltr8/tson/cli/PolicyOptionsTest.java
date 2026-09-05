package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.TsonUnicodePolicy;
import org.junit.jupiter.api.Test;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The [TSON-DATA] §8.2 policy flags: what they parse to, what they refuse, and what they leave behind for
 * the subcommand's own argument loop.
 */
class PolicyOptionsTest {

    private static PolicyOptions consume(String... args) {
        return PolicyOptions.consume(new ArrayList<>(List.of(args)));
    }

    @Test
    void noFlagsIsTheDefaultPair() {
        PolicyOptions options = consume();

        assertEquals(TsonUnicodePolicy.Level.HIGHLY_RESTRICTIVE, options.identifierPolicy().level());
        assertFalse(options.identifierPolicy().isPerSegment());
        assertEquals(TsonUnicodePolicy.Level.UNRESTRICTED, options.tokenPolicy().level());
    }

    /**
     * <b>The CLI's restatement of the defaults is pinned against a real {@code Tson}.</b> {@link
     * PolicyOptions#DEFAULTS} exists so {@link CliPolicy#isDefault()} can decide whether a person needs to
     * be told the policy at all; if {@code TsonConfig} ever changed a default, that decision would silently
     * invert and a relaxed run would stop announcing itself.
     */
    @Test
    void theRestatedDefaultsMatchTheOnesTsonActuallyApplies() {
        var tson = Tson.builder().build();
        var applied = tson.processorPolicy();

        assertEquals(applied.identifierPolicy().level(), PolicyOptions.DEFAULTS.identifierPolicy().level());
        assertEquals(applied.identifierPolicy().isPerSegment(),
                PolicyOptions.DEFAULTS.identifierPolicy().isPerSegment());
        assertEquals(applied.tokenPolicy().level(), PolicyOptions.DEFAULTS.tokenPolicy().level());
        assertEquals(tson.limitsPolicy(), PolicyOptions.DEFAULTS.limits());
        assertTrue(CliPolicy.from(applied, tson.limitsPolicy()).isDefault(),
                () -> "a run that configures nothing: " + applied + ", " + tson.limitsPolicy());
    }

    /**
     * Both spellings, because {@code highly-restrictive} is what a person types and {@code
     * HIGHLY_RESTRICTIVE} is what they copy out of {@code tson policy}'s own output. A CLI that took only
     * one of the two would make its own output unusable as its input.
     */
    @Test
    void aLevelIsAcceptedInEitherSpelling() {
        assertEquals(TsonUnicodePolicy.Level.ASCII_ONLY,
                consume("--identifier-policy", "ascii-only").identifierPolicy().level());
        assertEquals(TsonUnicodePolicy.Level.ASCII_ONLY,
                consume("--identifier-policy", "ASCII_ONLY").identifierPolicy().level());
        assertEquals(TsonUnicodePolicy.Level.MODERATELY_RESTRICTIVE,
                consume("--identifier-policy", "Moderately-Restrictive").identifierPolicy().level());
    }

    @Test
    void perSegmentAndScriptsLayerOnTheLevelInForce() {
        PolicyOptions options = consume("--identifier-per-segment",
                "--identifier-scripts", "Latin+Cyrillic", "--identifier-scripts", "Latin+Greek");

        TsonUnicodePolicy policy = options.identifierPolicy();
        assertEquals(TsonUnicodePolicy.Level.HIGHLY_RESTRICTIVE, policy.level(), "the default is kept");
        assertTrue(policy.isPerSegment());
        assertEquals(List.of(Set.of(UnicodeScript.LATIN, UnicodeScript.CYRILLIC),
                Set.of(UnicodeScript.LATIN, UnicodeScript.GREEK)), policy.permittedScripts());
    }

    /** Order-independent: the level is applied to the relaxations whichever side of them it was written. */
    @Test
    void aLevelReadsTheSameBeforeOrAfterTheRelaxationsRidingOnIt() {
        assertEquals(consume("--identifier-policy", "single-script", "--identifier-per-segment"),
                consume("--identifier-per-segment", "--identifier-policy", "single-script"));
    }

    /**
     * <b>A token script list brings its own level.</b> The token surface defaults to Unrestricted, which
     * scans nothing, so {@code permitting(...)} on it would be consulted by nobody and the flag would do
     * exactly nothing. Single Script is the level at which the list <em>is</em> the configuration.
     */
    @Test
    void aTokenScriptListRaisesTheLevelThatWouldHaveIgnoredIt() {
        PolicyOptions options = consume("--token-scripts", "Latin+Greek");

        assertEquals(TsonUnicodePolicy.Level.SINGLE_SCRIPT, options.tokenPolicy().level());
        assertTrue(options.tokenPolicy().checksScripts());
        assertEquals(List.of(Set.of(UnicodeScript.LATIN, UnicodeScript.GREEK)),
                options.tokenPolicy().permittedScripts());
        assertEquals(TsonUnicodePolicy.Level.HIGHLY_RESTRICTIVE, options.identifierPolicy().level(),
                "the identifier surface is untouched by a token flag");
    }

    /** An identifier list needs no such lift: its default already scans. */
    @Test
    void anIdentifierScriptListKeepsTheDefaultLevel() {
        assertEquals(TsonUnicodePolicy.Level.HIGHLY_RESTRICTIVE,
                consume("--identifier-scripts", "Latin+Cyrillic").identifierPolicy().level());
    }

    /** A level the caller stated is never overridden -- the lift is for a default, not for a decision. */
    @Test
    void aStatedLevelSurvivesAScriptList() {
        assertEquals(TsonUnicodePolicy.Level.MODERATELY_RESTRICTIVE,
                consume("--token-policy", "moderately-restrictive", "--token-scripts", "Latin+Han")
                        .tokenPolicy().level());
    }

    /**
     * <b>A relaxation against a level that scans nothing is refused, not ignored.</b> It configures nothing
     * whatever, and accepting it silently would leave the caller believing a restriction is in force --
     * the same ground on which {@code withTokenPolicy} refuses a per-segment token policy.
     */
    @Test
    void aRelaxationUnderALevelThatScansNothingIsAUsageError() {
        UsageException scripts = assertThrows(UsageException.class,
                () -> consume("--token-policy", "unrestricted", "--token-scripts", "Latin+Cyrillic"));
        assertTrue(scripts.getMessage().contains("--token-scripts"), scripts::getMessage);
        assertTrue(scripts.getMessage().contains("configure nothing"), scripts::getMessage);

        UsageException segment = assertThrows(UsageException.class,
                () -> consume("--identifier-policy", "minimally-restrictive", "--identifier-per-segment"));
        assertTrue(segment.getMessage().contains("--identifier-per-segment"), segment::getMessage);
    }

    @Test
    void anUnknownLevelOrScriptIsAUsageErrorNamingWhatIsAccepted() {
        UsageException level = assertThrows(UsageException.class,
                () -> consume("--identifier-policy", "paranoid"));
        assertTrue(level.getMessage().contains("highly-restrictive"), level::getMessage);

        UsageException script = assertThrows(UsageException.class,
                () -> consume("--identifier-scripts", "Latin+Klingon"));
        assertTrue(script.getMessage().contains("Klingon"), script::getMessage);
        assertTrue(script.getMessage().contains("Latin+Cyrillic"), script::getMessage);

        assertThrows(UsageException.class, () -> consume("--identifier-policy"));
    }

    /**
     * <b>Every policy flag and its value is removed, and nothing else is.</b> That is what lets the three
     * subcommands' own loops go on seeing only {@code --output} and their positionals -- a flag left behind
     * would be read as a filename.
     */
    @Test
    void consumeLeavesTheSubcommandsOwnArgumentsAlone() {
        List<String> args = new ArrayList<>(List.of("--identifier-policy", "ascii-only", "--output", "json",
                "schema.tn", "--identifier-scripts", "Latin+Cyrillic", "data.tn", "-"));

        PolicyOptions options = PolicyOptions.consume(args);

        assertEquals(List.of("--output", "json", "schema.tn", "data.tn", "-"), args);
        assertEquals(TsonUnicodePolicy.Level.ASCII_ONLY, options.identifierPolicy().level());
    }
}
