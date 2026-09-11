package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.policy.UnicodePolicy;
import io.ltr8.tson.base.unicode.IdentifierProfile;
import io.ltr8.tson.json.JsonReadContext;

import java.util.Optional;

/**
 * [TSON-DATA] §8.2's per-name hygiene rules, at the schema-directed positions that carry a name --
 * [TSON-JSON] §9.4's reach.
 *
 * <p><b>A refusal is not a verdict, and that is the whole reason this exists.</b> §8.2 says a name-hygiene
 * refusal "MUST NOT be reported in any of the four categories" of §8.1, because these rules read data the UCD
 * does not freeze and so may not decide validity. Without this check a document sending {@code pаssword} with
 * U+0430 against a record declaring {@code password} matched no field and drew {@code UNRECOGNIZED_FIELD} -- a
 * validation error, in exactly the case the look-alike rule exists for, and advice that told the sender to add
 * a field already there when the fix was one character.
 *
 * <p><b>Only an unmatched name reaches here</b>, which is §9.4's reach and is narrower than it looks: a member
 * name matching a declared field, or a {@code $type} naming a declared type, carries that declaration's own
 * verdict, given when the schema loaded. The schemaless bind reader checks every name instead, and is right
 * to: there the target class is the schema and nothing judged its component names at load.
 */
final class JsonNameHygiene {

    private JsonNameHygiene() {
    }

    /**
     * Judges {@code name}, reporting at most one refusal, and answers whether it refused -- so a caller can
     * report the refusal <em>instead of</em> the verdict it was about to give rather than as well as.
     *
     * <p>Tested rather than {@code ifPresent}-ed, matching the bind reader: both rules are allocation-free
     * when a name passes, which is every name of an ordinary document, where a capturing lambda allocates per
     * name whether or not the {@code Optional} holds anything.
     */
    static boolean refuses(JsonReadContext ctx, String name) {
        UnicodePolicy policy = ctx.identifierPolicy();
        // The restricted-character rule is gated on the level, per §8.2: Unrestricted "drops the profile too",
        // taking that rule with it. Script mixing gates itself inside violation().
        if (policy.appliesIdentifierProfile()) {
            Optional<String> restricted = IdentifierProfile.hygiene(name);
            if (restricted.isPresent()) {
                refuse(ctx, name, restricted.get(), Diagnostic.Code.RESTRICTED_CHARACTER);
                return true;
            }
        }
        Optional<String> script = policy.violation(name);
        if (script.isPresent()) {
            refuse(ctx, name, script.get(), Diagnostic.Code.RESTRICTED_SCRIPT);
            return true;
        }
        return false;
    }

    /**
     * One shape for both rules, and the same one the TSON read context uses: refused under this read's
     * <em>name</em> policy, never invalid. Which rule fired is the {@code Code} -- the two want different
     * remedies, and a code is what a consumer routes on.
     */
    private static void refuse(JsonReadContext ctx, String name, String violation, Diagnostic.Code code) {
        ctx.field(name).report(code, "the name " + violation, "a name this processor will accept",
                "'" + name + "'");
    }
}
