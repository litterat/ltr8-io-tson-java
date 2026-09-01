package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.lexer.Xid;

/**
 * The readable half of a name the resolver mints, built so the whole name is <b>ASCII and a valid {@code
 * identifier}, whatever the author wrote</b>.
 *
 * <p>Both minting sites splice author-written content into that half -- {@code SchemaDesugarer} from a lifted
 * binding record, {@code TemplateMaterialiser} from an application's head and value arguments -- which makes
 * a derived name a place where a document's own text reaches the schema namespace. Two things follow, and
 * the second is why the first is not enough on its own.
 *
 * <ul>
 *   <li><b>[TSON-SCHEMA] §8.2's freshness MUST</b>: an internal name is a valid {@code identifier}. Splicing
 *   raw text broke it outright -- a {@code text} field holding a path put {@code /} in a name, and
 *   [TSON-DATA] §7.7 admits only {@code XID_Continue} and {@code -}.</li>
 *   <li><b>§8.2's name hygiene has to be able to judge the result.</b> Admitting every {@code XID_Continue}
 *   character would keep the name legal and still let author text shape it: a Cyrillic {@code o} in a value
 *   would sit in a namespace name, and a Latin head spliced with non-Latin content is mixed-script by
 *   construction -- so the hygiene walk would refuse ordinary schemas, and exempting minted names from it
 *   would leave the namespace taking on whatever a document happened to contain. Restricting to ASCII is
 *   what lets the walk stay on: an ASCII name is single-script and inside the identifier profile, so it
 *   satisfies all three rules at every restriction level.</li>
 * </ul>
 *
 * <p><b>What is not ASCII is hashed rather than dropped.</b> Replacing it would collapse two different values
 * onto one readable half; hashing keeps them visibly distinct and keeps the name inspectable -- a reader who
 * has the schema can hash the same text and match it. Nothing is lost that identity depends on either way:
 * that is carried by the structural hash at the end, computed over the binding itself and never over this
 * text.
 */
final class InternalName {

    private InternalName() {
    }

    /**
     * {@code text} as one part of a derived name -- its head, or one of its segments.
     *
     * <p>Three cases, in the order they are tested:
     *
     * <ul>
     *   <li><b>ASCII and admitted by §7.7</b> -- spliced verbatim. This is the ordinary case: a type name, a
     *   verb, a bound, an enum member.</li>
     *   <li><b>ASCII but not admitted</b> -- the admitted characters, then a hash of the whole. A path
     *   {@code "/x"} reads {@code x_h00000f2f} and {@code 1.0} reads {@code 1_0_h0002f0a5}: the readable
     *   part still says what it came from, and the hash keeps two texts that sanitise alike apart.</li>
     *   <li><b>Anything else</b> -- the hash alone, so no non-ASCII character reaches the name.
     *   Unrecognisable by design, and the price of the hygiene walk being able to run at all.</li>
     * </ul>
     *
     * <p>{@code XID_Continue} rather than {@code XID_Start} for the admitted set: a head is a constructor or
     * template name and so already starts legally, and everything after it sits at a continue position.
     */
    static String part(String text) {
        if (isAdmittedAscii(text)) {
            return text;
        }
        return isAscii(text) ? joined(admittedOf(text), hash(text)) : hash(text);
    }

    /** Every character ASCII and admitted by [TSON-DATA] §7.7 -- the case that needs no rewriting at all. */
    private static boolean isAdmittedAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7F || !(Xid.isContinue(c) || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * The admitted characters of {@code text}, each run of the rest collapsed to one {@code _} and the edges
     * trimmed -- parts are already joined by {@code _}, so a replacement there would only double a separator
     * that is present.
     */
    private static String admittedOf(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean replacing = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Xid.isContinue(c) || c == '-') {
                out.append(c);
                replacing = false;
            } else if (!replacing) {
                out.append('_');
                replacing = true;
            }
        }
        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == '_') {
            start++;
        }
        while (end > start && out.charAt(end - 1) == '_') {
            end--;
        }
        return out.substring(start, end);
    }

    /**
     * {@code h} plus eight hex digits of {@code String.hashCode}, which is specified exactly -- so two
     * processors reading one schema derive the same name, as both minting sites already rely on for the
     * structural hash.
     *
     * <p>It is a rendering, never an identity: a collision here costs legibility and nothing else, because
     * the entry is keyed by the structural hash over its binding.
     */
    private static String hash(String text) {
        return "h" + String.format("%08x", text.hashCode());
    }

    private static String joined(String admitted, String hash) {
        return admitted.isEmpty() ? hash : admitted + "_" + hash;
    }
}
