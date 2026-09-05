package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * The names this resolver mints, and the renderings their hashes run over -- [TSON-SCHEMA] §8.2's
 * {@code head_arg_arg_hash}, "a readable head plus a structural hash".
 *
 * <p><b>Two families, not one.</b> A <em>binding record</em> names a closed form -- what a sugar form lifts
 * to, and what closing an open synthetic produces -- and is rendered with its fields under their own names.
 * An <em>application</em> names an instantiation and is rendered positionally. The two are deliberately
 * separate functions over separate shapes; merging them would be merging two questions that happen to share
 * a spelling, and they reuse the same tag letters in different roles.
 *
 * <p><b>What must not fork is each family's own rendering.</b> {@link #ofBinding} is called by both lift
 * channels -- {@code SchemaDesugarer} lifting a sugar form and {@code TemplateMaterialiser} closing an open
 * one -- and that shared call is exactly what makes a form written directly and the same form arriving
 * through a materialised template land on one entry (§8.2). Two functions of one record would name it twice.
 *
 * <p><b>The name is derived from the resolved binding record, not from the spelling that produced it.</b>
 * That is the one identity rule for internal entries: one entry per distinct concrete form, schema-wide, so
 * {@code [T; 3]} and {@code [T; 3..3]} collapse onto the same entry and a form arising from two different
 * declarations is written once.
 *
 * <p><b>Every hash runs over a rendering built here</b>, never over the AST's own {@code toString}. Both of
 * the JDK's ready-made answers are unusable: {@code Record::toString}'s format is documented as "subject to
 * change" (and shifts whenever a record's components are renamed or reordered), and {@code Record::hashCode}
 * "need not remain consistent from one execution of an application to another execution of the same
 * application". {@code String.hashCode} is specified exactly, so hashing a string built here is the one
 * construction that is deterministic by contract rather than by accident.
 *
 * <p>That determinism is load-bearing, not cosmetic. An entry name is part of the resolved form, and an
 * importing schema reaches an <em>imported</em> entry by deriving the same name for the same form --
 * meta.tn's {@code extern.types: [type_name]?} landing on the entry meta-kernel already produced.
 *
 * <p><b>The canonical renderings are separate from the names</b> so each is stated once: a rendering decides
 * whether two forms are the same form, which {@link MintedNames} asks with it, and a second copy free to
 * drift would answer differently in the two places that ask. Each is injective by construction -- every
 * value shape written under its own tag, each piece of author text written length-first ({@code 4:text}) --
 * so no arrangement of delimiters inside a token can spell a different record.
 */
final class DerivedName {

    private DerivedName() {
    }

    // ── A binding record: the closed form a lift produces ────────────────────────────────────────

    /** The name for a binding record, from both lift channels -- see the class Javadoc on why they share it. */
    static String ofBinding(String head, List<RecordValue.Field> fields) {
        // The head too: a constructor name is an identifier, but a consumer's meta layer may declare one
        // outside ASCII, and a name that mixes it with the ASCII parts below is refused by §8.2's own walk.
        StringBuilder readable = new StringBuilder(InternalName.part(head));
        for (RecordValue.Field field : fields) {
            appendReadable(readable, field.value().value().coreValue());
        }
        return readable.append('_')
                .append(String.format("%08x", canonicalBinding(head, fields).hashCode())).toString();
    }

    /** The readable half of a derived name: every scalar the binding record holds, in order, under {@code _}. */
    private static void appendReadable(StringBuilder out, CoreValue value) {
        switch (value) {
            case TokenValue token -> out.append('_')
                    .append(InternalName.part(
                            NumericIdentity.textOf(token.text(), token.form() == TokenForm.UNQUOTED)));
            case RecordValue record -> record.fields()
                    .forEach(field -> appendReadable(out, field.value().value().coreValue()));
            case ArrayValue array -> array.elements()
                    .forEach(element -> appendReadable(out, element.value().coreValue()));
            case MapValue map -> map.entries().forEach(entry -> {
                appendReadable(out, entry.key().coreValue());
                appendReadable(out, entry.value().value().coreValue());
            });
            default -> out.append("_v");
        }
    }

    /** A binding record rendered structurally and injectively; two renderings are equal exactly when the records are. */
    static String canonicalBinding(String head, List<RecordValue.Field> fields) {
        StringBuilder out = new StringBuilder();
        appendText(out.append('A'), head);
        appendFields(out, fields);
        return out.toString();
    }

    private static void appendFields(StringBuilder out, List<RecordValue.Field> fields) {
        out.append('(');
        for (RecordValue.Field field : fields) {
            appendText(out.append('f'), field.name());
            appendValue(out, field.value().value().coreValue());
        }
        out.append(')');
    }

    private static void appendValue(StringBuilder out, CoreValue value) {
        switch (value) {
            case TokenValue token -> {
                // The form by name, not ordinal: inserting a TokenForm constant would renumber every ordinal
                // invisibly, which is the same hazard as hashing a record's toString.
                appendText(out.append('v'), token.form().name());
                appendNumberAware(out, token.text(), token.form() == TokenForm.UNQUOTED);
            }
            case RecordValue record -> appendFields(out.append('r'), record.fields());
            case ArrayValue array -> {
                out.append("a(");
                array.elements().forEach(element -> appendValue(out, element.value().coreValue()));
                out.append(')');
            }
            // Both halves of every entry, in written order. Without this a map slot rendered as the
            // unknown-value mark and every binding differing only inside one was the same name --
            // `extern_of<"a.tn">` and `extern_of<"b.tn">` are two types and were one entry.
            case MapValue map -> {
                out.append("m(");
                map.entries().forEach(entry -> {
                    appendValue(out.append('k'), entry.key().coreValue());
                    appendValue(out.append('v'), entry.value().value().coreValue());
                });
                out.append(')');
            }
            default -> out.append('?');
        }
    }

    // ── An application: the instantiation entry a closure produces ───────────────────────────────

    /** The name for an application, rendered positionally where a binding record is rendered by field name. */
    static String ofApplication(String head, List<TypeArgument> arguments) {
        StringBuilder readable = new StringBuilder(InternalName.part(head));
        for (TypeArgument argument : arguments) {
            switch (argument) {
                case TypeArgument.Ref ref -> readable.append('_').append(InternalName.part(ref.ref().name()));
                case TypeArgument.Value value ->
                        readable.append('_').append(InternalName.part(canonicalText(value.value())));
            }
        }
        return readable.append('_')
                .append(String.format("%08x", canonicalApplication(head, arguments).hashCode())).toString();
    }

    /** An application rendered structurally and injectively -- what decides whether two are the same application. */
    static String canonicalApplication(String head, List<TypeArgument> arguments) {
        StringBuilder canonical = new StringBuilder();
        appendText(canonical.append('A'), head);
        canonical.append('(');
        for (TypeArgument argument : arguments) {
            switch (argument) {
                case TypeArgument.Ref ref -> appendRef(canonical.append('r'), ref.ref());
                case TypeArgument.Value value -> {
                    // The form by name, not ordinal: inserting a constant would renumber every ordinal.
                    appendText(canonical.append('v'), value.value().form().name());
                    appendNumberAware(canonical, value.value().text(),
                            value.value().form() == Token.Form.UNQUOTED);
                }
            }
        }
        return canonical.append(')').toString();
    }

    private static void appendRef(StringBuilder out, TypeRef ref) {
        appendText(out.append('n'), ref.name());
        out.append('(');
        for (TypeArgument argument : ref.arguments()) {
            switch (argument) {
                case TypeArgument.Ref nested -> appendRef(out.append('r'), nested.ref());
                case TypeArgument.Value value -> {
                    appendText(out.append('v'), value.value().form().name());
                    appendNumberAware(out, value.value().text(),
                            value.value().form() == Token.Form.UNQUOTED);
                }
            }
        }
        out.append(')');
    }

    /** A value argument's readable segment, with §4.3's numeric equivalence applied ({@link NumericIdentity}). */
    private static String canonicalText(Token token) {
        return NumericIdentity.textOf(token.text(), token.form() == Token.Form.UNQUOTED);
    }

    // ── Shared leaves ────────────────────────────────────────────────────────────────────────────

    /** Length-first, so concatenation stays unambiguous whatever the text contains. */
    private static void appendText(StringBuilder out, String text) {
        out.append(text.length()).append(':').append(text);
    }

    /**
     * A token's contribution to a hashed rendering, with §4.3's numeric equivalence applied
     * ({@link NumericIdentity}). A number writes its base-type kind and its canonical magnitude as two
     * fields where anything else writes its text as one; every field being length-prefixed, no token's own
     * text can be mistaken for a tagged number.
     *
     * <p>One method for both families: the two rendered a {@code TokenValue} and a {@code Token} separately
     * and identically, which is a shared decision about identity kept in two places.
     */
    private static void appendNumberAware(StringBuilder out, String text, boolean unquoted) {
        NumericIdentity.Canonical canonical = NumericIdentity.of(text, unquoted);
        if (canonical != null) {
            appendText(out, canonical.kind());
        }
        appendText(out, canonical == null ? text : canonical.text());
    }
}
