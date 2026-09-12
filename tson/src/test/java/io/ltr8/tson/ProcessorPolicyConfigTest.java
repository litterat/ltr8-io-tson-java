package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.UnicodePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ProcessorConfig#withProcessorPolicy} -- the whole of what a deployment constrains this processor with,
 * stated as the one value {@link Tson#processorPolicy()} reports and {@code Json.withProcessorPolicy} takes.
 *
 * <p>The three component setters fold into it rather than standing beside it, so what is pinned here is
 * that folding: each reaches the built instance, none clobbers another, and the composed form and the
 * component forms are the same statement.
 *
 * <p>Mixed-script spellings are built from code points rather than typed -- the subject is spellings that
 * look alike, so a literal would be unreviewable.
 */
class ProcessorPolicyConfigTest {

    /** Cyrillic а (U+0430). */
    private static final String CYR_A = new String(Character.toChars(0x0430));

    private static final ProcessorPolicy TIGHTENED = ProcessorPolicy.of(
            UnicodePolicy.asciiOnly(), UnicodePolicy.asciiOnly(), LimitsPolicy.defaults().withMaxDepth(8));

    @Test
    void theWholePolicyRoundTripsThroughTheInstance() {
        assertEquals(TIGHTENED, Tson.of(ProcessorConfig.defaults().withProcessorPolicy(TIGHTENED)).processorPolicy());
    }

    /**
     * <b>The composed form and the three component setters are one statement.</b> That is what makes the
     * components sugar rather than a second configuration surface -- a deployment handed a policy by a
     * platform library and one assembling it setting by setting reach the same processor.
     */
    @Test
    void theComponentSettersReachTheSameValue() {
        Tson piecewise = Tson.of(ProcessorConfig.defaults()
                .withIdentifierPolicy(TIGHTENED.identifierPolicy())
                .withTokenPolicy(TIGHTENED.tokenPolicy())
                .withLimits(TIGHTENED.limits()));

        assertEquals(TIGHTENED, piecewise.processorPolicy());
    }

    /**
     * <b>Each component setter changes exactly its own component.</b> The three used to be independent
     * fields, so this could not fail; folding them into one value is what makes it worth pinning, since a
     * setter written to replace the policy rather than derive from it would silently discard whatever was
     * stated before it.
     */
    @Test
    void statingOneComponentLeavesTheOthersAlone() {
        ProcessorPolicy stated = Tson.of(ProcessorConfig.defaults()
                        .withLimits(LimitsPolicy.defaults().withMaxDepth(8))
                        .withIdentifierPolicy(UnicodePolicy.asciiOnly()))
                .processorPolicy();

        assertEquals(8, stated.limits().maxDepth(), "the limit stated first survives the policy stated after it");
        assertEquals(UnicodePolicy.Level.ASCII_ONLY, stated.identifierPolicy().level());
        assertEquals(ProcessorPolicy.defaults().tokenPolicy(), stated.tokenPolicy(),
                "the component nothing stated keeps its default");
    }

    /** A component stated after the whole policy refines it; the policy is not a floor the components clear. */
    @Test
    void aComponentStatedAfterTheWholePolicyRefinesIt() {
        ProcessorPolicy stated = Tson.of(ProcessorConfig.defaults()
                        .withProcessorPolicy(TIGHTENED)
                        .withLimits(LimitsPolicy.defaults()))
                .processorPolicy();

        assertEquals(TIGHTENED.withLimits(LimitsPolicy.defaults()), stated);
    }

    /**
     * <b>The identifier half of a whole policy reaches the linker</b>, which is the one component
     * {@code build()} has to hand somewhere other than the {@link Tson} itself -- the schema registry is
     * constructed with it, since the linker judges declared names. A policy that were only reported would
     * make {@link Tson#processorPolicy()} state a rule nothing applied.
     */
    @Test
    void theIdentifierHalfOfAWholePolicyReachesTheLinker() {
        List<Diagnostic> refused = Tson.of(ProcessorConfig.defaults().withProcessorPolicy(TIGHTENED)).validateSchema("""
                !!id:"https://example.test/whole-policy.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                { p%sy => text }
                """.formatted(CYR_A));

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                refused.stream().map(Diagnostic::code).toList(), refused::toString);
    }

    /** And the limits half reaches a read, the other component that has to travel past the report. */
    @Test
    void theLimitsHalfOfAWholePolicyReachesARead() {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withProcessorPolicy(ProcessorPolicy.defaults().withLimits(LimitsPolicy.defaults().withMaxDepth(3))));

        assertEquals(LimitsPolicy.defaults().withMaxDepth(3), tson.limitsPolicy());
        assertEquals(List.of(Diagnostic.Code.LIMIT_EXCEEDED),
                tson.validate("[[[[1]]]]").stream().map(Diagnostic::code).toList());
    }

    /**
     * <b>The per-segment refusal holds on the composed route too.</b> It is an invariant of what a token
     * policy can mean, so {@link ProcessorPolicy} enforces it and no assembly route is a way around it --
     * which is what the named setter alone could not promise.
     */
    @Test
    void aPerSegmentTokenPolicyIsRefusedWhicheverSetterCarriesIt() {
        UnicodePolicy perSegment = UnicodePolicy.highlyRestrictive().perSegment();

        assertThrows(IllegalArgumentException.class, () -> ProcessorConfig.defaults().withTokenPolicy(perSegment));
        assertThrows(IllegalArgumentException.class,
                () -> ProcessorConfig.defaults().withProcessorPolicy(ProcessorPolicy.defaults().withTokenPolicy(perSegment)));
    }
}
