package io.ltr8.tson.cli;

import io.ltr8.annotation.Field;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.unicode.RestrictionLevel;
import io.ltr8.tson.base.policy.ScriptPolicy;

import java.lang.Character.UnicodeScript;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * This CLI's on-the-wire shape for {@link ProcessorPolicy} -- what this run's [TSON-DATA] §8.2
 * name hygiene was judged by, stated once per {@link ValidationRun}/{@link ValidationReport} and printable
 * on its own by {@code tson policy}.
 *
 * <p><b>Why the envelope carries it and a diagnostic does not.</b> §8.2's rules read data the Unicode
 * Consortium does not freeze, at a level this deployment chose, so one processor refuses a name another
 * accepts. That fact is constant for the whole run -- a copy on each refusal would be N copies of one
 * value -- and it is what a sender needs <em>before</em> it writes a document, which is why {@code tson
 * policy} prints this with no document in hand. The refusal itself carries the remedy: which name, and
 * which rule, in its own {@code code}.
 *
 * <p>A separate DTO from {@link ProcessorPolicy} for {@link CliDiagnostic}'s own reason --
 * {@code diagnostics.tn} declares these fields as {@code text}, and {@link UnicodeScript} is a JDK enum of
 * some 170 members that no wire schema should be restating. The scripts render by name; {@link
 * RestrictionLevel} stays the real enum, since enum narrowing is the proven binding path here.
 *
 * <p>The two surfaces keep {@code ProcessorConfig}'s own names, here and on the wire, so a deployment's
 * configuration and the report it produces are one vocabulary.
 */
public record CliPolicy(@Field("identifier_policy") CliIdentifierPolicy identifierPolicy,
                        @Field("token_policy") CliScriptPolicy tokenPolicy,
                        @Field("unicode_data_version") String unicodeDataVersion,
                        CliLimits limits) {

    /**
     * The policy a run that passed no flag is judged under -- what {@link OutputFormat#TEXT} compares against
     * to decide whether a person needs to be told the policy at all.
     */
    private static final CliPolicy DEFAULTS = from(ProcessorPolicy.of(
            PolicyOptions.DEFAULTS.identifierPolicy(), PolicyOptions.DEFAULTS.tokenPolicy(),
            PolicyOptions.DEFAULTS.limits()));

    /**
     * Whether this is what a run configures by saying nothing.
     *
     * <p>A non-default policy is worth stating even on a clean run: [TSON-DATA] §8.2 requires that a
     * relaxation not be silent, and a run that passed {@code --identifier-policy unrestricted} and printed
     * {@code OK} would be exactly that. The machine formats carry {@code policy} either way.
     */
    boolean isDefault() {
        return equals(DEFAULTS);
    }

    /**
     * One argument, because a processor states one policy. This record's own shape -- the two surfaces, the
     * data version, and a nested {@code limits} -- is what {@code ProcessorPolicy} settled on, so the
     * conversion is now a field-for-field copy rather than a join of two values.
     */
    static CliPolicy from(ProcessorPolicy policy) {
        return new CliPolicy(CliIdentifierPolicy.from(policy.identifierPolicy()),
                CliScriptPolicy.from(policy.tokenPolicy()), policy.unicodeDataVersion(),
                CliLimits.from(policy.limits()));
    }

    /**
     * This run's [TSON-DATA] §9.1 and [TSON-SCHEMA] §11.5 resource limits -- what it will spend reading one
     * document and resolving one schema, where the two {@code *_policy} fields say what it will admit as a
     * name. The whole of {@code policy.tn}'s {@code limits} record, field for field from {@link LimitsPolicy}:
     * a limit the processor does not yet count is still its stated threshold, and a report that left it out
     * would leave a sender guessing at it.
     *
     * <p>Inside {@code policy} rather than beside it in the envelope because the envelope's one question is
     * "what judged this run", and a limit refusal answers it as much as a name refusal does. It also gets
     * {@link CliPolicy#isDefault()} for free, so a run that raised the depth states it even when nothing was
     * refused -- the same rule §8.2 imposes on a relaxation, and true here for the same reason.
     */
    public record CliLimits(@Field("max_depth") int maxDepth,
                            @Field("max_token_length") long maxTokenLength,
                            @Field("max_text_length") long maxTextLength,
                            @Field("max_numeric_literal_length") int maxNumericLiteralLength,
                            @Field("max_binary_size") long maxBinarySize,
                            @Field("max_document_size") long maxDocumentSize,
                            @Field("max_elements") int maxElements,
                            @Field("max_entries") int maxEntries,
                            @Field("max_fields") int maxFields,
                            @Field("max_annotations") int maxAnnotations,
                            @Field("max_values") int maxValues,
                            @Field("max_foreign_schemas") int maxForeignSchemas,
                            @Field("max_import_closure") int maxImportClosure,
                            @Field("max_schema_entries") int maxSchemaEntries,
                            @Field("max_reference_chain") int maxReferenceChain,
                            @Field("max_supertype_chain") int maxSupertypeChain,
                            @Field("max_materialisation_depth") int maxMaterialisationDepth) {

        static CliLimits from(LimitsPolicy limits) {
            return new CliLimits(limits.maxDepth(), limits.maxTokenLength(), limits.maxTextLength(),
                    limits.maxNumericLiteralLength(), limits.maxBinarySize(), limits.maxDocumentSize(),
                    limits.maxElements(), limits.maxEntries(), limits.maxFields(), limits.maxAnnotations(),
                    limits.maxValues(), limits.maxForeignSchemas(), limits.maxImportClosure(),
                    limits.maxSchemaEntries(), limits.maxReferenceChain(), limits.maxSupertypeChain(),
                    limits.maxMaterialisationDepth());
        }
    }

    /**
     * §8.2's identifier policy: the UTS #39 §5.2 restriction level, whether it applies to each segment of a
     * name rather than to the whole of it, whether no two names in one scope may share a skeleton, and the script
     * combinations admitted over and above the level. Together the whole of the policy, which is what lets a
     * reader of a refusal work out which of them another deployment set differently.
     */
    public record CliIdentifierPolicy(RestrictionLevel level, @Field("per_segment") boolean perSegment,
                                      @Field("skeleton_distinctness") boolean skeletonDistinctness,
                                      List<List<String>> permitting) {

        static CliIdentifierPolicy from(IdentifierPolicy policy) {
            return new CliIdentifierPolicy(policy.scripts().level(), policy.isPerSegment(),
                    policy.appliesSkeletonDistinctness(), CliScriptPolicy.from(policy.scripts()).permitting());
        }
    }

    /**
     * §8.2's token policy: the UTS #39 §5.2 restriction level over each whole token, and the script combinations
     * admitted over and above it.
     */
    public record CliScriptPolicy(RestrictionLevel level, List<List<String>> permitting) {

        static CliScriptPolicy from(ScriptPolicy policy) {
            return new CliScriptPolicy(policy.level(),
                    policy.permittedScripts().stream().map(CliScriptPolicy::names).toList());
        }

        /** One admitted combination, script names sorted so two deployments' reports compare as text. */
        private static List<String> names(Set<UnicodeScript> scripts) {
            return scripts.stream().map(CliScriptPolicy::alias).sorted().toList();
        }

        /**
         * A script's UAX #24 property value alias, the name {@code policy.tn} states a script by: {@code
         * Latin}, {@code Old_Italic}. The JDK's constant is the alias upper-cased, so each segment is
         * title-cased back; {@code SignWriting} is the one alias with a capital inside a segment.
         */
        static String alias(UnicodeScript script) {
            if (script == UnicodeScript.SIGNWRITING) {
                return "SignWriting";
            }
            StringBuilder alias = new StringBuilder();
            for (String segment : script.name().split("_")) {
                if (!alias.isEmpty()) {
                    alias.append('_');
                }
                alias.append(segment.charAt(0)).append(segment.substring(1).toLowerCase(Locale.ROOT));
            }
            return alias.toString();
        }
    }
}
