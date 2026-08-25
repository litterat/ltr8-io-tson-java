/**
 * A native RFC 9485 (I-Regexp) engine: {@link io.ltr8.tson.regex.TsonRegex#parse} validates a pattern against
 * the RFC's grammar and builds its AST, {@link io.ltr8.tson.regex.TsonRegex#matches} runs it in guaranteed
 * linear time over a Thompson NFA, and {@link io.ltr8.tson.regex.TsonRegex#isDisjointFrom} decides whether
 * two patterns share any string at all.
 *
 * <h2>Conformance statement</h2>
 *
 * [TSON-SCHEMA] §9 makes a spec-pinned atom's {@code spec} field a strict gate: an implementation "MUST
 * implement the pinned dialect as specified -- not a host library's near-relative -- and MUST document any
 * divergence it cannot avoid". TSON's {@code regex} atom pins RFC 9485, so this is that statement.
 *
 * <p><b>The dialect is implemented here, not delegated.</b> {@code java.util.regex} is a Perl-derived
 * superset that accepts patterns I-Regexp excludes and matches shared constructs differently, so wrapping it
 * would make "what does this schema's pattern mean" a question about the JDK. Parsing, matching and
 * disjointness are all this module's own.
 *
 * <p><b>What the grammar admits is exactly RFC 9485 §3's.</b> Rejected, as syntax errors rather than
 * silently reinterpreted: the shorthand classes {@code \d}/{@code \w}/{@code \s}, character-class
 * subtraction ({@code [a-z-[aeiou]]}), capture groups with back-references, lookaround, and Unicode block
 * properties ({@code \p{IsGreek}}). The category vocabulary of {@code \p{...}}/{@code \P{...}} matches the
 * RFC's {@code IsCategory} production one for one -- the seven single-letter groups and their subcategories,
 * and notably <em>not</em> {@code Cs}, which the RFC's {@code Others} production does not admit.
 *
 * <p><b>Matching is whole-string</b> (RFC 9485 §3's Boolean result, per [XSD-2]): a pattern is not anchored
 * and then partially matched, it either accepts the entire input or does not. {@code ^} and {@code $} are
 * ordinary literal characters, as the RFC specifies -- a pattern using them parses, and matches those
 * characters. {@code .} matches any character except {@code \n} and {@code \r}. Everything is code-point
 * addressed, so {@code .} matches one astral character rather than half of a surrogate pair.
 *
 * <h3>The one divergence to declare: Unicode version</h3>
 *
 * <b>RFC 9485 pins no Unicode version</b>, and this engine resolves {@code \p{...}} through
 * {@link java.lang.Character#getType(int)} -- so a category test answers against whichever Unicode version
 * the running JDK carries. The same "own the rules, borrow the JDK's tables" split the lexer makes for XID.
 *
 * <p>The consequence is worth stating plainly, because it is invisible until it bites: for a code point
 * assigned or recategorised between two Unicode versions, this engine and another conforming implementation
 * can disagree about {@code \p{L}} while both follow the RFC exactly. It is a portability limit of the RFC's
 * own silence rather than a departure from it, and it cannot be closed without shipping a pinned Unicode
 * table -- which would then diverge from the JDK the rest of this library uses for XID and NFC. Anything
 * relying on category membership at the edge of a recent Unicode release should say which version it means.
 *
 * <h3>ReDoS, and why disjointness is exact</h3>
 *
 * The Thompson-NFA simulation has no backtracking, so match time is linear in the input and no pattern is
 * adversarial -- an engine reading patterns out of untrusted schemas cannot afford otherwise.
 * {@link io.ltr8.tson.regex.TsonRegex#isDisjointFrom} decides intersection-emptiness over a symbolic product
 * NFA, exactly, because regular languages permit it: the answer is yes or no, never "unknown".
 *
 * <p><b>What that exactness is not for.</b> It does not feed [TSON-SCHEMA] §5.4's choice disjointness, which
 * is discrimination-class distinctness and forbids proving more: "value-set separation such as disjoint
 * numeric bounds or disjoint patterns does not make a choice disjoint". Two {@code regex}-constrained
 * variants are both string-class and therefore never disjoint, however separated their languages. The
 * decision procedure is available for a schema author's own reasoning, not for dropping a variant tag.
 */
package io.ltr8.tson.regex;
