/**
 * The built-in atom vocabulary -- which tokens each family accepts, and what host value results
 * ([TSON-DATA] §5.2's parsing contracts).
 *
 * <p><b>A module rather than a package inside an engine, because the vocabulary is not an engine's.</b>
 * [TSON-JSON] §5.1 hands a JSON string's content to the atom's own parser exactly as a TSON quoted token's
 * text would be, so which families a reader can bind, and what they read to, is a property of the type
 * system rather than of the encoding that carried them. Left inside {@code tson-compiler} it would be
 * TSON text's by accident of placement, and a second encoding would either depend on the whole text engine
 * or mint a second vocabulary for one fact.
 *
 * <p>Three packages, split by who touches them.
 *
 * <ul>
 *   <li>{@code io.ltr8.tson.atom} -- what a caller names: {@code AtomType}, the two indices over it
 *       ({@code BuiltinTypeVocabulary} by name, {@code HostAtoms} by host class), {@code AtomParsers} from
 *       a resolved body, the exceptions a refusal arrives as, and {@code IdentifierProfile}, which is
 *       [TSON-DATA] §7.7's name profile as much as it is an atom.</li>
 *   <li>{@code io.ltr8.tson.atom.number} -- §4's number production and the narrowing over it, exported
 *       because base type resolution stays with the text encoding and reads it.</li>
 *   <li>{@code io.ltr8.tson.atom.parser} -- the family implementations, <b>unexported</b>. A caller asks an
 *       index for an {@code AtomType} and never names {@code UuidParser}; the same rule
 *       {@code tson-compiler} applies to its own {@code lexer} and {@code reader} packages.</li>
 * </ul>
 *
 * <p><b>What is deliberately not here</b> is everything that depends on <em>how</em> a token was written.
 * {@code AtomType} takes a {@code String}, and the two atoms that need the lexical form -- the kernel's
 * {@code value}, whose §4.4 rule is that a quoted token is a string, and {@code Token}, which records the
 * spelling §8's resolved form carries -- stay in {@code tson-compiler} with {@code TokenValue} itself.
 * That those two are exactly the positions where the encodings legitimately differ is not a coincidence:
 * JSON has no token forms and reads a {@code value} position by [TSON-JSON] §5.7's own rule.
 */
module io.ltr8.tson.atom {
    exports io.ltr8.tson.atom;
    exports io.ltr8.tson.atom.number;

    requires transitive io.ltr8.tson.schema;
    requires transitive io.ltr8.tson.base;
    requires transitive io.ltr8.bind;
    requires io.ltr8.tson.regex;
}
