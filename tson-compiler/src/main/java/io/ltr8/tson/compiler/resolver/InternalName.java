package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.lexer.Xid;

/**
 * The one rule every name the resolver mints must satisfy: [TSON-SCHEMA] §8.2's freshness MUST -- <em>an
 * internal name is a valid {@code identifier}</em>.
 *
 * <p>Both minting sites build the same shape, §8.2's own recommendation of a readable head plus a structural
 * hash: {@code SchemaDesugarer} for a lifted sugar form, {@code TemplateMaterialiser} for a closed template
 * application. Both splice author-written content into the readable half, and [TSON-DATA] §7.7 admits only
 * {@code XID_Continue} and {@code -} -- so a {@code text} field holding a path put {@code /} in a name and
 * made it not an identifier at all. An HTTP operation is the case that finds it, [TSON-SCHEMA] §4.1 naming
 * one as the motivating case for the {@code data} kind and every realistic path carrying a slash.
 *
 * <p><b>Sanitising costs nothing, which is what makes it the right fix.</b> The readable half is a
 * diagnostic convenience -- identity is carried by the structural hash beside it, computed over the binding
 * itself and not over this text -- so replacing what cannot appear in an identifier loses no information
 * that anything reads.
 */
final class InternalName {

    private InternalName() {
    }

    /**
     * {@code text} as one segment of a derived name: every run of characters [TSON-DATA] §7.7 does not admit
     * becomes a single {@code _}.
     *
     * <p>A run rather than a character each, and trimmed at both ends, so a path {@code "/x"} reads {@code x}
     * and joins as {@code head_x} rather than {@code head__x}: segments are already joined by {@code _}, so a
     * replacement at an edge would only double the separator that is there. A segment that is wholly
     * unadmitted contributes nothing beyond that separator.
     *
     * <p><b>{@code XID_Continue} and not {@code XID_Start}</b>, because a segment is never the first thing in
     * a derived name: the head is a constructor name, itself an identifier, so the name starts legally and
     * every segment after it sits at a continue position.
     */
    static String segment(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean replacing = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Xid.isContinue(codePoint) || codePoint == '-') {
                out.appendCodePoint(codePoint);
                replacing = false;
            } else if (!replacing) {
                out.append('_');
                replacing = true;
            }
        }
        // This can also trim an underscore the author wrote -- '_' is XID_Continue, so `_foo` was copied
        // through -- and that is harmless rather than merely tolerable: the readable half is a diagnostic
        // convenience, and `_foo` and `foo` still mint distinct entries because the hash beside it runs over
        // the binding, not over this text.
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
}
