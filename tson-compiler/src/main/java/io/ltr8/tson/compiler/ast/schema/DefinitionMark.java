package io.ltr8.tson.compiler.ast.schema;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code definition-mark} (Part 2 §12.1) -- the word a declaration writes between {@code =>} and its type
 * definition to say how the type may be realised: {@code abstract} (no instances of its own) or {@code final}
 * (instances, and no subtype). An unmarked declaration is OPEN, which is why the slot is optional and OPEN has
 * no spelling.
 *
 * <p><b>One slot, not two flags.</b> The three ways a record may be realised are alternatives, so writing two
 * of them is ungrammatical rather than a rule the resolver has to state and diagnose.
 *
 * <p><b>Surface only.</b> This mirrors the grammar; {@code RecordExtensionType} is the resolved vocabulary the
 * mark lowers into, exactly as {@link FieldDef.Modifier.Kind} mirrors {@code ~}/{@code =} and the resolver maps
 * it to a field state. Keeping the two apart is what lets the AST name a form the resolver may still refuse --
 * {@code final} on a template is written here and rejected there.
 */
public enum DefinitionMark {

    /** {@code abstract} -- no instances of its own, so a value at a position typed by it is a subtype's. */
    ABSTRACT,

    /** {@code final} -- instances of its own and no subtype: nothing may compose or refine onto it. */
    FINAL;

    /** The mark {@code word} spells, or empty where it is an ordinary name. */
    public static Optional<DefinitionMark> of(String word) {
        return switch (word) {
            case "abstract" -> Optional.of(ABSTRACT);
            case "final" -> Optional.of(FINAL);
            default -> Optional.empty();
        };
    }

    /** The word, which is the enum member lower-cased -- what a diagnostic prints and the grammar reads. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }
}
