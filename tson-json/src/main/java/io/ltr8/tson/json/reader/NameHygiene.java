package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.unicode.IdentifierProfile;
import io.ltr8.tson.json.JsonReadContext;

import java.util.List;

/**
 * [TSON-DATA] §8.2's per-name hygiene rules, at the schema-directed positions that carry a name --
 * [TSON-JSON] §9.4's reach.
 *
 * <p><b>A refusal is not a verdict, and that is the whole reason this exists.</b> §8.2 says a name-hygiene
 * refusal "MUST NOT be reported in any of the four categories" of §8.1, because these rules read data the UCD
 * does not freeze and so may not decide validity. So hygiene is judged <em>before</em> the verdict an
 * unmatched name would otherwise draw: {@code p\u0430ssword}, spelled with U+0430, against a record declaring
 * {@code password} matches no field, and {@code UNRECOGNIZED_FIELD} there would be a validation error in
 * exactly the case the rule exists for -- advice to add a field already declared, when the fix is one character.
 *
 * <p><b>Two kinds of name reach here</b>, which is §9.4's reach and is narrower than it looks. A member name is
 * judged only when it is unmatched: one matching a declared field, or a {@code $type} naming a declared type,
 * carries that declaration's own verdict, given when the schema loaded. And a value whose type is an identifier
 * family is judged wherever it stands -- a member value, or a member name keying a map whose key type is one --
 * since the schema typed it as a name ({@link #refusesValue}). The schemaless bind reader checks every member
 * name instead, and is right to: there the target class is the schema and nothing judged its component names at
 * load.
 */
final class NameHygiene {

    private NameHygiene() {
    }

    /**
     * Judges {@code name}, reporting each rule it fails, and answers whether it refused -- so a caller can
     * report the refusal <em>instead of</em> the verdict it was about to give rather than as well as.
     *
     * <p>Looped rather than {@code forEach}-ed, matching the bind reader: judging is allocation-free when a name
     * passes, which is every name of an ordinary document, where a capturing lambda allocates per name.
     */
    static boolean refuses(JsonReadContext ctx, String name) {
        return judge(ctx, name, IdentifierProfile.NAME, true);
    }

    /**
     * The same judgement of a value whose type is an identifier family, under that family's own {@code
     * profile} -- §8.2's per-name rules reach every identifier-typed value, a map key or a member value alike
     * -- reported at {@code at}, which is the value's own position.
     */
    static boolean refusesValue(JsonReadContext at, String name, IdentifierProfile profile) {
        return judge(at, name, profile, false);
    }

    private static boolean judge(JsonReadContext ctx, String name, IdentifierProfile profile, boolean member) {
        List<IdentifierPolicy.Violation> violations = ctx.identifierPolicy().judge(name, profile);
        if (violations.isEmpty()) {
            return false;
        }
        JsonReadContext at = member ? ctx.field(name) : ctx;
        for (IdentifierPolicy.Violation violation : violations) {
            refuse(at, name, violation.reason(), violation.code());
        }
        return true;
    }

    /**
     * One shape for both rules, and the same one the TSON read context uses: refused under this read's
     * <em>name</em> policy, never invalid. Which rule fired is the {@code Code} -- the two want different
     * remedies, and a code is what a consumer routes on.
     */
    private static void refuse(JsonReadContext at, String name, String violation, Diagnostic.Code code) {
        at.report(code, "the name " + violation, "a name this processor will accept", "'" + name + "'");
    }
}
