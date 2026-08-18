# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation.
See `CLAUDE.md` for why this file exists and when to add to it. Spec quotes below are from Part 1
(https://tson.io/raw/2026/32/tson-part1-data.md), 2026 Revision 32, unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a
suggested resolution where there is one.

---

## 1. Multi-line token closing delimiter: is trailing whitespace after `"""` permitted?

**Section:** §7.2.3.

**Problem:** The opening delimiter rule is explicit about trailing whitespace: "The opening delimiter is
`"""` followed by *optional spaces and tabs* and a line terminator." The closing delimiter rule has no
equivalent clause: "the closing delimiter is `"""` on its own line, preceded only by optional
whitespace." "Preceded only by optional whitespace" covers *leading* whitespace on the closing line, but
says nothing about what may follow the `"""` before the line terminator. Read strictly, a line
consisting of `   """  ` (trailing spaces after the delimiter) is unaddressed: it's not clear whether
those trailing spaces are permitted and ignored (symmetric with the opening delimiter) or make the line
fail to qualify as "its own line" for the closing delimiter, causing an "unterminated token" error whose
actual cause is a stray trailing space.

**Interpretation chosen:** Treat trailing spaces/tabs after `"""` on the closing line as permitted and
ignored, by symmetry with the opening delimiter's explicit rule. Implemented in
`Lexer.isClosingDelimiterContent`.

**Suggested resolution:** Make the closing delimiter production explicit about trailing whitespace,
mirroring the opening delimiter's wording, e.g.: "the closing delimiter is `"""`, optionally followed by
spaces and tabs, on its own line, preceded only by optional whitespace."

---

## 2. Multi-line common-prefix stripping: what happens to a blank line shorter than the computed prefix?

**Section:** §7.2.3, rule 2.

**Problem:** Blank lines are explicitly excluded from *computing* the common prefix ("Blank lines do not
participate in the calculation"), but the same rule then says unconditionally "The prefix is then removed
from the start of every line" — which does include blank lines. A blank line by construction contains
only spaces/tabs (or is empty), so it may well be shorter than the computed prefix, or its whitespace may
not match the prefix character-for-character (e.g. the file uses spaces for indentation generally, but
one blank line happens to contain a single tab). The spec doesn't say what "removed from the start" means
when the prefix doesn't fully match: is it an error, does the line contribute nothing (stays as-is), or is
only the matching portion removed?

**Interpretation chosen:** Best-effort: remove the longest prefix of the line that matches the computed
prefix character-by-character, which for a shorter or non-matching blank line may be less than the full
prefix (including zero characters). Never an error. Implemented in `Lexer.removePrefix`.

**Suggested resolution:** State explicitly that prefix removal from a line shorter than (or not matching)
the common prefix removes only the matching portion, is a no-op past the point of mismatch, and is never
an error — or, if a stricter behavior is intended, say so and define what should happen instead.

---

## 3. The nested-annotation example `@a:@b:val target` cannot actually stand alone as a data-value

**Section:** §3.1, "Value scope".

**Problem:** The prose gives two examples back to back, apparently as parallel illustrations of the same
shape: `@a:@b:val target` and (contrasted) `@a:@b val target`. Mechanically tracing both against the
grammar (`data-value = *annotation [type-ref] core-value`; `annotation = "@" unquoted-token
[":" data-value]`) shows they are not actually parallel:

- **`@a:@b val target`** (no colon on `@b`): `@b` is valueless, so parsing `@b`'s containing data-value
  (which is `@a`'s value) stops as soon as it finds a core-value — `val` — since a valueless annotation
  doesn't keep consuming. That data-value is `{annotations: [b], core: val}`, fully consumed as `@a`'s
  value. Control returns to the outer level, which still needs its own core-value and finds `target`
  waiting. Outer result: `{annotations: [a], core: target}` — a complete data-value, exactly as the spec
  states ("`target` belongs to the surrounding context").

- **`@a:@b:val target`** (colon on `@b`): `@b` *has* a value, so parsing `@b`'s value recurses into a
  fresh data-value starting at `val target`, which finds `val` as its core-value and stops — `target` is
  *not* part of `@b`'s value. Control returns to the data-value that contains `@b` as an annotation (which
  is `@a`'s value) — and *that* data-value still needs its own core-value, which it finds: `target`. So
  `@a`'s value is `{annotations: [b], core: target}`, exactly as the spec states. But this fully consumes
  every remaining token. Stepping back up one more level to whatever data-value contains `@a` as an
  annotation, *that* data-value now also needs a core-value of its own — and there is nothing left. This
  is structurally identical to the spec's own error example two sentences later: `{ x: @a:@b:val }` is a
  parse error "because `@a`'s data-value still requires a core value after the annotation `@b:val`" — the
  same failure, just one level further out. I traced this by hand three times against the grammar
  (including embedded in `{ x: @a:@b:val target }`, which fails identically once `x`'s value still needs
  its own core-value after `@a` consumes everything) and it holds up: `@a:@b:val target`, as given, cannot
  be a complete data-value in any position. It would need one more trailing token (e.g.
  `@a:@b:val target extra`) for the outermost core-value to be satisfied.

**Interpretation chosen:** Implemented the grammar exactly as written (annotation-with-value recurses into
a full nested data-value, which always requires its own core-value). Verified in
`ParserTest.nestedAnnotationValueScopeSpecExample` (using `@a:@b:val target extra` — the smallest
extension of the spec's own example that actually parses) and
`ParserTest.nestedAnnotationValueScopeAloneIsIncomplete` (confirming `@a:@b:val target` alone fails, and
why).

**Suggested resolution:** Either extend the first example with one more trailing token so it's complete
and directly comparable to the second (e.g. `@a:@b:val target extra`), or add a sentence noting that
`@a:@b:val target` alone is intentionally incomplete and only illustrates `@a`'s value in isolation, not a
full data-value.

---

## 4. Custom (non-built-in) type-ref matching semantics are entirely undefined in Part 1

**Section:** §3.2 (type annotations / `type-ref`), §5.1.

**Problem:** §3.2 requires a Class 1 processor to preserve a type annotation it does not resolve "as an
uninterpreted marker attached to \[its] value" — i.e. Part 1 explicitly declines to define what a custom
type name like `!Circle` *means*, deferring that to Part 2's schema/type-system layer, which doesn't
exist yet (§1.3). §5.1 does establish a matching rule, but only for the closed, built-in vocabulary:
"Annotation names are case-sensitive. Only the exact names listed below are recognised." That rule's
scope is explicitly the fixed table in §5.3–§5.6 (`!uuid`, `!date`, `!int32`, etc.) — it says nothing
about, and by its own "only the exact names listed below" wording arguably excludes, how a name *outside*
that table should ever be matched against anything, since Part 1 has no concept of "the set of names a
schema declares." This isn't a bug in Part 1 — it's explicitly out of scope by design — but it means an
application-level consumer that wants to use `type-ref` for host-language type disambiguation (e.g.
resolving a Java union member from `!Circle`) has zero spec guidance today, and no way to know whether the
eventual Part 2 rule will be case-sensitive-only (as §5.1 is, for the built-in set) or something looser.

**Interpretation chosen:** `TypeRefCheck.names` (`io.ltr8.tson.compiler.reader`) treats this as purely an
application-binding decision, not a spec-conformance one — the Class 1/Class 2 preservation requirement is
satisfied upstream (the parser hands the type-ref through as an uninterpreted string), and everything
downstream of that is this implementation's own policy: try an exact match against a class's `@Typename`
annotation first, then fall back to a case-*insensitive* match against its simple Java class name (so
`!circle` matches a class named `Circle` without requiring every fixture to carry an explicit annotation).
One rule, applied both to a union's candidate members (`SchemalessObjectReader.resolveUnionMember`) and to
a directly-bound container target. Note this fallback is deliberately *not* consistent with §5.1's
case-sensitivity rule for the built-in vocabulary — there was no spec basis to be consistent with, since
§5.1 doesn't claim to govern this case at all.

The looseness is scoped, though, and entry #7 is where that scoping lives: an **atom** position takes
`TypeRefCheck.declares` (`@Typename` only, no simple-name fallback) precisely *because* §5.1's
case-sensitivity does govern there, and the fallback would otherwise let a `UUID`-targeted `!Uuid` through
on the strength of the class being called `UUID`.

**Suggested resolution:** Not a Part 1 defect to fix — flagging so that whenever Part 2 defines real
schema-driven type-name resolution, this implementation's ad hoc heuristic gets revisited and either
conformed to the real rule or clearly scoped as "no schema in play" fallback behavior. (It is now
explicitly the latter: the heuristic runs only on the schemaless path, never where a compiled schema is in
scope.)

---

## 5. `!email` is present in the core type library but missing from Part 1's built-in vocabulary table

**Section:** §5.5 ("Identifier and Network Types"), cross-referenced against the core type library
(`core.tn1`, reachable via `!!meta` from a schema; see also §5.1: "schemas wanting these names import the
core type library, whose entries denote the same parsing contracts defined here").

**Problem:** `core.tn1` groups `email` together with `uuid`, `ipv4`, `ipv6`, `cidr4`, `cidr6`, and `mac`
under one documentation banner ("Network Types") and gives it the same shape as its siblings —
`email => !email_type {}`, backed by an `email_type` constructor in `meta.tn1` pinned to RFC 5322,
identical in form to `uuid_type`/`ipv4_type`/etc. Every other member of that family is promoted to a Part
1 §5.5 built-in annotation (`!uuid`, `!ipv4`, `!ipv6`, `!cidr4`, `!cidr6`, `!mac`) — `!email` is not; the
§5.5 table has no row for it, and `!email` appears nowhere else in Part 1. Nothing in §5.1's applicability
rules or §5.5's prose explains the omission (no stated rationale like "email validation is intentionally
schema-only"), so a reader relying on core.tn1 as the built-in vocabulary's source of truth (as §5.1
explicitly invites) would reasonably expect `!email` to exist as a schemaless annotation and be surprised
to find it doesn't parse.

**Interpretation chosen:** Implement `!email` as a built-in annotation — registered in
`BuiltinTypeVocabulary` alongside its siblings, backed by `EmailParser`, and read identically whether or
not a schema is in scope. This is a **known, deliberate departure from §5.5's published table**, of the
same kind as the `int8`..`int256` ladder in entry #6: core.tn is treated as the vocabulary's source of
truth where the table and the library disagree, which is what §5.1 invites a reader to do.

The alternative — honouring the table by withholding the annotation while still shipping the parser for
schema-driven reads — was implemented first and then rejected. It buys nothing: the parser exists either
way, and the only effect is that the schemaless and schema-driven paths disagree about what `!email`
means, which is a worse outcome than diverging from a table that gives no reason for the omission.

**Suggested resolution:** Add an `!email` row to §5.5 — and, in the same edit, say what conformance to
its RFC 5322 pin actually requires. The row alone would not settle anything, because full RFC 5322
`addr-spec` admits quoted local parts (`"a b"@example.com`), domain literals (`user@[192.0.2.1]`) and
embedded comments, none of which belong in an interchange format's scalar and none of which this
implementation accepts (see `EmailParser`, and entry #22 for the general form of this question). Naming
the RFC without scoping it leaves every implementation to pick its own subset and call it conformant —
which is exactly what has happened here.

---

## 6. §5.6's published integer atoms are a strict subset of `core.tn1`'s `integer_type` family

**Section:** §5.6 ("Numeric Types"), cross-referenced against `core.tn1`.

**Problem:** §5.6's table lists exactly four fixed-width integer annotations: `!int32`, `!int64`,
`!uint32`, `!uint64`. `core.tn1` defines the same `integer_type` constructor applied across the full
`int8`/`int16`/`int32`/`int64`/`int128`/`int256` and `uint8`/`uint16`/`uint32`/`uint64`/`uint128`/`uint256`
width ladder, plus a `positive_integer`/`non_negative_integer`/`negative_integer`/`non_positive_integer`
bound-only refinement family — sixteen instances of `integer_type` total, of which §5.6 promotes only
four to the schemaless built-in vocabulary. Confirmed (outside the spec text itself, via direct guidance)
that the missing twelve are an oversight in the published table, not a deliberate narrowing of the
schemaless surface relative to the core type library.

**Interpretation chosen:** `tson-compiler`'s built-in vocabulary (`BuiltinTypeVocabulary`,
`atom` package) implements the full sixteen-instance `integer_type` family from `core.tn1` —
`int8` through `int256`, `uint8` through `uint256`, and all four bound-only refinements — not just the
four §5.6 currently lists. `IntegerType`/`IntegerSize`/`IntegerParser` are written generically
against the constructor (arbitrary width, arbitrary signedness, optional bounds), so this cost nothing
beyond populating the map with twelve more entries.

**Suggested resolution:** Update §5.6's table to list the full `integer_type` family, matching
`core.tn1`.

---

## 7. §5.1's "preserved as uninterpreted marker" rule doesn't address what a typed-binding consumer should do with it

**Section:** §5.1.

**Problem:** §5.1 requires a Class 1 processor to preserve an unrecognized type annotation "as an
uninterpreted marker" rather than erroring — correct and necessary at the parsing/resolution layer, since
a Class 1 processor can't know the full universe of names some future schema or application might define,
and choking on them would make the format not forward-compatible. But the rule only addresses that
processing step; it says nothing about what happens next. An application built on top of a Class 1
processor that binds a value directly to a caller-declared, strongly-typed target (this implementation's
`TsonObjectReader.read(source, MyRecord.class)`) has a real choice to make on hitting a marker it can't
interpret: treat the value as if the annotation weren't there (silently falling back to base type
resolution), or treat an unresolvable annotation on a value it's actively trying to type-check as an
error. Getting this wrong either way has a real cost: silently ignoring means a typo like `!Uuid`
(case-sensitive per §5.1, so not the same as `!uuid`) quietly disables the validation the author clearly
intended; erroring unconditionally means an application that deliberately wants passthrough/lenient
behavior for forward compatibility has no way to ask for it. Every implementation doing typed binding on
top of TSON will face exactly this decision, and Part 1 has nothing to say about it — reasonably, since
it's application-binding policy, not format conformance, but worth recording as a gap a future
implementer's guide could usefully address.

**Interpretation chosen:** report by default, preserve on request — this implementation's answer to its own
"rather than the reverse" below. `TypeRefCheck` (in `tson-compiler`'s `reader` package) states the rule once
for both schemaless readers: given `!X`, a name `BuiltinTypeVocabulary` resolves must sit on a token and
satisfy that atom; a name that instead *names the target being bound* is accepted; a name that links to
neither is `UNKNOWN_TYPE_REF`. `preservingUnknownTypeRefs()` on `TsonTreeReader`/`TsonObjectReader` relaxes
that last rule alone, which is the explicit opt-in this entry argued should exist — built-in names stay
checked either way.

The Class 1 processing step itself (`TsonDataStream`/`TsonDataParser`) still preserves every type-ref
exactly as §5.1 requires; this is a reader-layer policy on top, not a change to Part 1 conformance.
Rationale unchanged: a mistyped or unimplemented type-ref on a value the reader is actively type-checking is
far more likely to be a bug worth surfacing than an intentional forward-compatibility signal, and `!Uuid`
being case-sensitively distinct from `!uuid` (§5.1) means silence there disables exactly the validation the
author asked for.

What "names the target" means differs by position, and the split matters. A **container** accepts the
target's `@Typename` or, failing that, its simple class name case-insensitively, so `!point { x: 3  y: 4 }`
binds to a Java `Point` with nothing annotated. An **atom** accepts a declared `@Typename` only: the loose
match would let a `UUID`-targeted `!Uuid` through on the strength of the class being *called* `UUID`,
reintroducing the exact hole the rule exists to close. A tree read has no target at all, so rule 2 never
applies there and any non-built-in name is reported unless preserved.

**Suggested resolution:** Not a Part 1 defect — flagging as guidance worth a note in a future
implementer's guide ([TSON-GUIDE]?) rather than the format spec itself: an implementation that
*type-checks* a value against anything (a host class, or the built-in vocabulary alone) should consider
failing on unrecognized type-refs by default, with passthrough as an explicit opt-in, rather than the
reverse. Worth stating more broadly than "binding to host objects": the same choice faces a
schemaless-tree/validating reader, which has no host type at all and still has to decide.

---

## 8. §5.2's "is a parse error" phrasing for atom-format violations conflicts with §8.1's own category description

**Section:** §5.2, §8.1.

**Problem:** §5.2 states: "A token the atom's grammar rejects 'is a parse error'; a parsed value violating
the atom's range 'is a validation error'." §8.1's "Canonical phrasing" rule states these exact four
phrases each map "unambiguously" to a category, and lists "is a parse error" as mapping to the `parser`
category. But §8.1's own description of parser errors is "Structural mismatches: unclosed brackets,
adjacency violations, unexpected tokens, missing separators, `!!` without an adjacent colon form, a
directive name outside the closed positional set or outside its placement (§3.3)" — nothing about an
atom's own value-format contract. A built-in vocabulary annotation's parsing contract (§5) is checked well
after the structural parser has already accepted the document as well-formed — `!int32 twelve` is a
syntactically complete data-value (type-ref + token core-value); the failure only surfaces once something
interprets the token against `int32`'s specific format, which is architecturally a resolver-layer concern
(recognizing/binding a token against a type's contract) in every implementation this project is aware of,
not a structural-parser concern. §8.1's resolver-error description doesn't mention this case either
("Reference and resolution failures... an absent sentinel in map key position; a built-in type annotation
on a container value (§5.1)"). So §5.2's own use of "is a parse error" appears to invoke §8.1's `parser`
category by the letter of the canonical-phrasing rule, while conflicting with both categories' own prose
descriptions — most plausibly because §5.2 is using "parse error" in the ordinary-English sense ("this
token failed to be interpreted"), written without cross-checking §8.1's stricter technical claim that the
exact phrase is a fixed mapping to one specific processing-layer category.

**Interpretation chosen:** This implementation's atom types (`atom.AtomParseException`) live in
`tson-compiler`'s own `atom` package, architecturally alongside — not inside — the structural parser
(`TsonDataParser`/`TsonParseException`), and are raised only from atom-type `read()` calls, never from
`TsonDataParser` itself.
For the conformance test suite (`ltr8-io-tson-test-suite`'s `vocabulary/invalid` vectors), this failure
mode is tagged `category: resolver`, not `parser`, as the more architecturally coherent reading — but each
such vector's own `description` flags this as provisional, and the suite's README documents the ambiguity
explicitly, since a literal reading of §8.1's canonical-phrasing table would put it under `parser` instead.
Range/constraint violations (§5.2's other phrase, "is a validation error") have no such ambiguity — §8.1
unambiguously assigns "range violations by the numeric atoms" to the `validation` category, and both
`AtomValidationException` and the suite's vectors use it without qualification.

**Suggested resolution:** Either restate §5.2's phrasing to use "is a resolver error" (matching where this
check actually happens architecturally and avoiding the canonical-phrasing collision), or add a clause to
§8.1's canonical-phrasing rule or parser-error description explicitly carving out built-in-vocabulary
format violations as parser-category despite occurring after structural parsing completes.

---

## 9. `text_type` exists in meta-kernel.tn1 but `!text` is not part of Part 1's published built-in vocabulary

**Section:** §5 (all of §5.3–§5.6), cross-referenced against `meta-kernel.tn1`.

**Problem:** `meta-kernel.tn1` defines `text_type` (`min_length`/`max_length`/`length`/`pattern`) and an
instance `text => !text_type {}`, and several other constructors compose with it (`uri_type`,
`regex_type`, `email_type` all extend `text_type`'s shape). It would be reasonable to expect `!text` to be
promoted to a schemaless built-in annotation the same way `!uuid`/`!date`/etc. are, especially since it's
one of the *simplest* possible atoms — an unconstrained text check is nearly a no-op. It isn't: `!text`
appears nowhere in §5.3 (Binary), §5.4 (Temporal), §5.5 (Identifier and Network), or §5.6 (Numeric) — the
four family tables that between them are the complete published vocabulary (confirmed by grepping the
whole document for every `` `!name` `` table row). This is the same shape of gap as entry #5 (`!email`),
but for a type that's arguably a more natural inclusion than `email`, since `text` needs no external RFC
and is the foundation `uri_type`/`regex_type`/`email_type` all build on. Unlike entry #6 (the integer
family), this one has *not* been confirmed as an oversight — it may be entirely deliberate (an unannotated
token already resolves to a string via base type resolution, §4.4, so a bare `!text` annotation would add
essentially nothing beyond what's already the default), but the spec doesn't say so.

**Interpretation chosen:** `!text` is not implemented as a built-in annotation in this implementation's
Class 1 resolver, matching the letter of §5's tables. An unannotated string-shaped token, or one under an
unrecognized `!text` annotation, is handled the same as any other non-vocabulary name (§5.1: preserved as
an uninterpreted marker at the Class 1 layer; a binding error at the mapper layer's per entry #7).

**Suggested resolution:** Either add a `!text` row somewhere in §5 (there's no obviously-correct
subsection for it among the four existing family headings, which is itself a small structural
observation), or add a sentence noting that `text` is deliberately schema-only/omitted from the
schemaless vocabulary because base type resolution already covers the unconstrained case.

---

## 10. §5.3 doesn't say whether `!base64`/`!base64url` require padding

**Section:** §5.3, cross-referenced against RFC 4648 §3.2.

**Problem:** §5.3 says only "a token that is not a valid encoding under the named scheme is a parse
error" and meta.tn1 says only "Encoding alphabets are pinned to RFC 4648" — neither addresses whether a
`!base64`/`!base64url` token must include the `=` padding characters RFC 4648 §4/§5 describe. RFC 4648
§3.2 itself says implementations "MUST include appropriate pad characters at the end of encoded data
unless the specification referring to this document explicitly states otherwise" — TSON is exactly such a
referring specification, and §5.3 doesn't state otherwise, so a literal reading requires padding. But this
is exactly the kind of detail an implementation could easily get wrong by trusting a standard library
instead of the RFC text: `java.util.Base64.getDecoder()` accepts input with the padding omitted entirely
(`"TWE"` decodes identically to the correctly-padded `"TWE="`) — confirmed empirically before writing
`BinaryType`, not assumed. An implementation that just calls `Base64.getDecoder().decode(text)` and
propagates whatever it throws would silently accept unpadded input, deviating from RFC 4648 §3.2's MUST
without any test ever catching it, since the JDK never complains.

**Interpretation chosen:** `BinaryType`'s `BASE64`/`BASE64URL` encodings (via a shared `Base64Decoding`
helper) reject any token whose length isn't a multiple of 4 before ever reaching `java.util.Base64`'s
decoder — i.e. padding is required. Not similarly strict about RFC 4648 §3.5's *canonical* padding-bits
requirement (the unused bits in the last encoded character before `=` should be zero) — §3.5 makes
rejecting non-canonical encodings a MAY, not a MUST, so the JDK decoder's leniency there is left alone;
`BASE32`'s from-scratch decoder follows the same distinction (required padding *count*, not
required-canonical padding *bits*).

**Suggested resolution:** Add a sentence to §5.3 stating explicitly whether padding is required for
`!base64`/`!base64url`, rather than leaving it to RFC 4648 §3.2's general "unless stated otherwise" default
-- easy to get right by reading the RFC carefully, easy to get wrong by trusting a standard library's
decoder, which is exactly the trap this entry documents checking for empirically before writing any code,
rather than after a test failure caught it (the JDK never raises an error either way, so nothing would
have caught it automatically).

---

## 11. `binary`'s constructor name doesn't follow the `_type` suffix every other constructor uses

**Section:** meta.tn1.

**Problem:** Every constraint-vocabulary constructor in meta-kernel.tn1/meta.tn1 is named `xxx_type` --
`integer_type`, `float_type`, `decimal_type`, `rational_type`, `complex_type`, `uuid_type`, `text_type`,
`date_type`, `time_type`, `datetime_type`, `duration_type`, `email_type`, `ipv4_type`, `ipv6_type`,
`cidr4_type`, `cidr6_type`, `mac_type`, `uri_type`, `regex_type` -- nineteen constructors, one naming
convention, no exceptions among the `_type`-suffixed group. `binary` (§5.3's four encodings' shared
constructor) is the one constructor of this general shape that doesn't follow it: not `binary_type`, just
`binary`. It isn't obviously a typo, though -- meta.tn1's own introductory doc explicitly buckets
constructors into three families, and puts `binary` in a different bucket from the `_type`-suffixed ones:
"1. Structural constructors: `binary` (with `binary_encoding` enum) and `extern`... 3. Constraint
vocabulary constructors for atom families the kernel itself doesn't need: numeric..., temporal...,
identifier..., network..., and text (`email_type`)." So `binary`/`extern` are explicitly categorized as
"structural constructors," distinct from "constraint vocabulary constructors" -- but the *reason* for that
categorization isn't obvious from the constructor's own shape: `binary` is atom-kind like every `_type`
constructor (unlike `extern`, which is sum-kind, matching its "structural" label more intuitively), and it
has `min_length`/`max_length` fields playing exactly the same constraint-vocabulary role `text_type`'s
`min_length`/`max_length` do. Nothing else about `binary`'s definition explains why it's grouped with
`extern` rather than with the nineteen `_type` constructors it otherwise resembles.

**Interpretation chosen:** Treated as the same constructor either way -- this implementation's
`BinaryType` class (in `tson-schema`'s `schema.meta` package) is named to match the established
`_type`-suffix convention of its siblings (`IntegerType`, `FloatType`, ...) rather than mirror `binary`'s
own unsuffixed spelling, since the naming asymmetry doesn't appear to carry semantic weight for an
implementation (it's still one atom constructor, `~atom`, with a constraint-vocabulary-shaped field set).

**Suggested resolution:** Either rename `binary` to `binary_type` for consistency, or add a sentence
explaining what distinguishes a "structural constructor" from a "constraint vocabulary constructor" beyond
the naming convention itself, since as written the category boundary reads as arbitrary for `binary`
specifically (unlike `extern`, whose sum-kind and schema-reference-list shape make "structural" a much
more legible label).

---

## 12. Does `!duration` accept ISO 8601's `PnW` week form, or only `PnYnMnDTnHnMnS`?

**Section:** §5.4.

**Problem:** §5.4's table gives `!duration`'s format as "ISO 8601 duration (`PnYnMnDTnHnMnS`)" — a
parenthetical showing one specific designator sequence. ISO 8601-1:2019 (the spec `duration_type` itself
pins to, per meta.tn1's `spec` field) also defines a second, mutually-exclusive alternative form for
expressing a duration in whole weeks: `PnW` (e.g. `P3W` for three weeks), which cannot be combined with
the `Y`/`M`/`D`/`H`/`M`/`S` designators in the same value. §5.4's parenthetical doesn't mention `W`
anywhere, and nothing in the surrounding prose says whether that's because the week form is deliberately
excluded from the schemaless `!duration` atom, or because the parenthetical is a representative example of
the ISO 8601 duration format rather than an exhaustive grammar (the same way, elsewhere in the document,
a parenthetical sometimes illustrates rather than fully specifies). Both readings are defensible: excluding
`W` would be consistent with `!duration`'s host value being modeled as year/month/day/hour/minute/second
components (a week doesn't decompose uniquely into those without picking a day-length, though `P3W` itself
carries no such ambiguity on its own terms); including it would be consistent with simply deferring to "the
ISO 8601 duration format" as a whole, of which `PnW` is a normal part.

**Interpretation chosen:** `DurationType`'s parser accepts only `P` followed optionally by `Y`/`M`/`D`
designators, optionally followed by `T` and `H`/`M`/`S` designators, matching §5.4's parenthetical
literally — `P3W` is rejected as a parse error, not specially recognized. This was the more conservative
reading available (implementing a format the annotation's own table doesn't show would be a bigger leap
than declining to implement one it might have intended by reference), but it's a real coin flip, not a
confident call.

**Suggested resolution:** State explicitly whether `PnW` is part of `!duration`'s accepted format or not.
If it is, the table's parenthetical should show it (`PnYnMnDTnHnMnS` / `PnW`) the same way §5.6's table
spells out multiple accepted grammar forms per numeric atom explicitly rather than by implication.

---

## 13. §3.1's uniform annotation-attachment model has no host-language object-binding equivalent for scalar positions

**Section:** §3.1.

**Problem:** §3.1 lets an annotation attach to *any* `data-value` position, uniformly and recursively:
a record field's value, an array element, a map key, either side of a map entry, and (since an
annotation's own value is itself a full `data-value`) recursively inside all of those —
`{ a: { b: @foo 1 } }` is legal, annotating the deeply-nested `1`. This is a coherent model at the
data-format layer, where every value position is represented the same way regardless of shape. It has
no equivalent in a strongly-typed host-language object-binding layer: a Java `String`, `int`, or other
scalar-typed field has no place of its own to carry extra metadata alongside its value the way a
composite type (a class the caller controls) could be retrofitted to. So a POJO/record-style binder can
only ever recover annotations attached to positions that map onto a *composite* type the caller owns
(and even then, only that value's own annotations — not, recursively, its children's, without inventing
a separate carrier convention per container kind: field-keyed for records, index-keyed for
arrays/tuples, twice more for map keys and values). Annotations on a scalar leaf, an array/tuple
element, or a map key/value are structurally unreachable from a typed object-binding layer, full stop —
not a gap this implementation failed to close, but one no fixed set of host-language carrier
conventions closes, since the recursion is the whole shape of the problem. §3.1 doesn't address this at
all, reasonably, since it's a binding-layer concern rather than a format one — but it's worth being on
record about, since every implementation doing typed binding on top of TSON will hit the same wall.

**Interpretation chosen:** A Java class opts in by declaring a component of type
`io.ltr8.annotation.Annotations`, and receives *only* the annotations on the value the whole record itself
corresponds to — deliberately not a general "child annotations" mechanism. It works on both read paths and
writes back, and under a schema each annotation's value is bound through the type §6 says its name refers
to. The declared type is the opt-in because it is the one signal the binding engine can verify on its own:
an earlier marker-plus-separately-agreed-carrier-type arrangement left whichever layer saw only one of the
two unable to check anything.

A record-field-keyed (or array-index-keyed, or map-key-keyed) *sibling* carrier for children's annotations
was considered and rejected: it would only push the same problem down one level without resolving the
recursive case, needs a different bespoke convention per container kind, and is real API surface for a
capability likely rarely exercised in practice (per the meta-kernel's own `core.tn`/`meta.tn`, annotations
overwhelmingly describe *the thing itself* — `@doc:"..." @ordered:TOTAL` on whole type definitions — not
individual scalar fields).

**A generic box is a different answer, and it weakens the "full stop" above.** `record Annotated<T>(T value,
Annotations annotations)`, declared at the position itself (`Annotated<String> name`), puts the metadata in
the position's own *type* rather than in a parallel structure keyed by name or index. One type then serves
every position, nesting composes (`Annotated<List<Annotated<String>>>`) rather than needing a new convention
per level, and it costs nothing where unused. Not implemented — tracked in `BACKLOG.md` — but it means
"structurally unreachable from a typed object-binding layer" overstates the case. An application needing
full-fidelity access at positions the carrier cannot reach still has one today: the parsed AST directly
(`DataValue.annotations()`), already fully general and needing neither a schema nor a Java type to project
onto.

**Suggested resolution:** Not a Part 1 defect — §3.1 correctly stays silent on host-language binding,
that's out of scope by design. Flagging as guidance worth a note in a future implementer's guide, or a
question for Part 2: could a schema declare that a field's annotations bind to a sibling field of a
specific type (the way `record_field`'s `value`/`value_param` split already handles a related
value-vs-parameter distinction), giving typed object-binding layers a real, schema-driven answer instead
of each implementation inventing its own ad hoc partial carrier convention?

---

## 14. `construction-def`'s ABNF (§12.1) can't parse its own worked example from §5.8

**Section:** §12.1 (grammar), cross-referenced against §5.8 and §12.2.

**Problem:** The ABNF is:

```
construction-def = type-ref 1*(ws "&" ws type-ref)
                   [ws record-def] [ws removal-set]
                 / type-ref ws "&" ws record-def [ws removal-set]
                 / type-ref ws removal-set
```

Alternative 1's trailing `record-def` has no leading `"&"` in front of it — only `1*(ws "&" ws
type-ref)` does. But §5.8's own worked example is `customer => address & contact & { loyalty_tier: text
}`, which has a `&` immediately before the `{`. Under alternative 1 as literally written, `type-ref` binds
`address`, the `1*("&" type-ref)` repetition consumes `& contact` (one repetition — `record-def` can't
itself satisfy `type-ref`, since `type-ref`'s four alternatives never start with `{`), and the repetition
then cannot continue because the next token is `&` followed by `{`, not `&` followed by a `type-ref`. The
remaining `& { loyalty_tier: text }` has nothing left in alternative 1 to consume it: the next slot is a
*bare* `record-def` with no `&` in front. Alternative 2 doesn't rescue this either — it's fixed at exactly
one leading `type-ref` before its own single `"&" record-def`, so it only covers a two-item case
(`address & { ... }`), not the three-or-more-supertype chain the example shows. §12.2's disambiguation
notes assume the intended behavior directly: "When a `{` follows a `&`-chain, it always belongs to the
construction's record-def" — describing exactly the `& {` shape the ABNF's alternative 1 fails to admit.

**Interpretation chosen:** Implemented per the clear intent (the worked example plus the §12.2 note),
not the literal alternative-1 production: a construction's supertype list is `type-ref (ws "&" ws
type-ref)*`, and on each `&` the parser checks one token ahead — `{` means the trailing `record-def`
(terminating the supertype list), anything else means another `type-ref` supertype. This is equivalent to
alternative 1 with an implicit `"&" ws` inserted directly before its `[record-def]` slot. Implemented in
`tson-compiler`'s `SchemaParser.parseConstructionDefContinuation`.

**Suggested resolution:** Add the missing `"&" ws` before `record-def` in alternative 1, i.e.:
`type-ref 1*(ws "&" ws type-ref) [ws "&" ws record-def] [ws removal-set]` — at which point alternative 2
becomes redundant (a single-supertype instance of the same shape) and could be dropped.

---

## 15. §12.1's own summary claims `field-modifier` reuses `data-value`, but its ABNF restricts it to `token`/`absent`

**Section:** §12.1 (introductory prose) vs. its own ABNF, cross-referenced against §5.2.

**Problem:** §12.1's lead paragraph states: "`data-value` appears at exactly three points —
constructor-application values and atom-refinement values, and field-modifier values." But the ABNF two
lines later gives `field-modifier = ws ("~" / "=") ws ( token / absent )` — not `data-value`. The two are
materially different productions: `data-value` is `*annotation [type-ref] core-value` (annotations, an
optional type-ref, and any core-value, including nested records/maps/arrays), while `token / absent` is a
single unannotated, untyped leaf. This isn't just loose wording — §5.2 itself independently confirms the
narrower ABNF is the intended rule: "Value modifiers are restricted to scalar tokens — quoted or
unquoted — covering strings, numbers, booleans, and null; complex modifier values (arrays, records,
maps) are not supported in v1." So the summary sentence overstates what the grammar and §5.2's own prose
both agree `field-modifier` actually accepts.

**Interpretation chosen:** Implemented per the ABNF and §5.2 (the two mutually-consistent sources): a
field modifier's value is a bare token or the absent sentinel — no annotations, no type-ref, never a
container. `tson-compiler`'s `FieldDef.Modifier` models this as a plain `TokenValue`/`AbsentValue` (reusing
`io.ltr8.tson.compiler.ast`'s existing leaf types), not a full `DataValue`.

**Suggested resolution:** Fix the summary sentence in §12.1's lead paragraph to read "...and
field-modifier values, which are restricted to a bare token or the absent sentinel (§5.2), not full
`data-value`s" — or simply drop `field-modifier` from that sentence's list, since it isn't actually an
instance of the `data-value` import the sentence is introducing.

---

## 16. `instance`/`atom-refinement`'s ABNF uses the full `data-value`, letting a constructor-application or refinement payload carry a nonsensical second annotation/type-ref layer

**Section:** §12.1's ABNF for `instance` and `atom-refinement`, cross-referenced against [TSON-DATA]
§2.3's `data-value` production and against §12.1's own `refined-def` (the schema-level `^` refinement,
a different construct from `atom-refinement` but the same operator).

**Problem:** The ABNF is:

```
atom-refinement = "!" type-name ws "^" ws data-value
instance        = "!" type-name ws data-value
```

and [TSON-DATA] §2.3 defines `data-value = *annotation [type-ref] core-value`, with `type-ref = "!"
unquoted-token`. Expanding `data-value` inline, `instance` literally admits `"!" type-name ws
*annotation ["!" unquoted-token] core-value` — i.e. after the constructor's own `"!" type-name` prefix,
the grammar as written still permits *further* annotations and a *second*, entirely separate type-ref
before the actual payload (e.g. `!integer_type @foo !other_type {}` parses under the literal ABNF).
Neither §5.5's prose nor any real fixture (`meta-kernel.tn1`/`meta.tn1`/`core.tn1`) ever uses or implies
this — every real `instance`/`atom-refinement` payload is a bare core value (`{}`, an array, or a single
token), never annotated and never separately typed. `atom-refinement` additionally has its own prose
directly contradicting the wider grammar it's given: "the data-value MUST be a braced record of
constraint bindings" (§12.1's own comment) — i.e. `atom-refinement`'s payload isn't just "some
core-value", it's specifically a record, which `core-value`'s six-way alternation (record / map / array
/ empty-brace / absent / token) doesn't capture either.

The grammar contains its own corroborating evidence that this is a genuine slip, not intentional: the
sibling schema-level refinement production, `refined-def = type-name [ws "<" type-args ">"] ws "^" ws
record-def` (§12.1), already uses `record-def` directly for the identical `^` operator applied one level
up (record/map/array refinement, §5.7) — exactly the correction `atom-refinement` itself needs, sitting
right there in the same ABNF block.

**Interpretation chosen:** Implemented per the narrower, evidently-intended productions: `instance = "!"
type-name ws core-value` (dropping the `*annotation [type-ref]` prefix `data-value` would otherwise
contribute, since the constructor name is already fully supplied by `"!" type-name`, and `core-value`
already covers every real positional/braced/bare-array shape `instance` needs). `tson-compiler`'s
`ast.schema.Instance` was reshaped accordingly: it no longer carries a separate `target: String`
field alongside a full-generality `value: DataValue` (the redundancy that surfaced this while designing
`SchemaResolver`'s generalized constructor-application resolution — `target` and `DataValue.typeRef()`
were two fields saying the same thing). Instead `Instance(DataValue value)` wraps a `DataValue`
constructed directly from the parsed `core-value`, with `typeRef` pre-set to the constructor name and
`annotations` always empty; `target()` is a thin accessor over `value.typeRef()`. `TsonSchemaParser` widened
`TsonDataParser.parseCoreValue()` from `private` to package-private (the same treatment every other grammar
primitive `TsonSchemaParser` reuses from `TsonDataParser` already has) to reach the bare production instead
of going through `parseDataValue()`.

`atom-refinement` is left as `DataValue` (not narrowed to `record-def`) for now — a real, still-open
gap, deliberately not fixed in the same pass: correcting it properly needs `AtomRefinement.bindings` to
carry a `RecordDef` (schema-grammar AST) rather than a `DataValue` (data-grammar AST) directly, a larger
type change than `Instance`'s, and `SchemaResolver`'s own atom-refinement resolution (Part 2 §5.7's `!I ^
{ values }`) doesn't exist yet regardless (see `CLAUDE.md`'s "Not yet implemented"). Revisit alongside
that work.

**Suggested resolution:** Change §12.1's ABNF to:

```
atom-refinement = "!" type-name ws "^" ws record-def
instance        = "!" type-name ws core-value
```

matching `refined-def`'s own already-correct pattern for `atom-refinement`, and dropping the
unused-in-practice `*annotation [type-ref]` layer for `instance`.

---

## 17. Atom refinement's own desugaring rule ("retargeting") produces the wrong value for a chained refinement, contradicting §5.7's own materialization rule for the analogous record case

**Section:** §5.6 ("Atom refinement" -- the desugaring rule), cross-referenced against §5.5 (`age`'s
own worked example, which explicitly says a refined instance "can be refined further") and §5.7
("Body materialisation" -- record refinement's own, structurally analogous rule).

**Problem:** §5.6 states: "`!I ^ { values }` desugars by retargeting to the instance's source
constructor," with two worked examples, both single-hop (`!integer ^ { min: 0 max: 150 }` →
`!integer_type { min: 0 max: 150 }`). Read literally, "retargeting" means only the *head* changes
(`!I ^` → `!I.source`) while `values` carries over verbatim, unchanged -- so for a *chained*
refinement, where `I` is itself already the result of a prior refinement, the desugared form
contains only the new refinement's own `values`, with none of `I`'s own previously-bound fields.

Concretely: given

```
int8      => !integer ^ { size: { bits: 8  signed: true } }
bigNumber => !int8 ^ { min: -500  max: 5000 }
```

the literal "retargeting" rule gives `bigNumber`'s `I.source` as `integer_type` (not `int8` --
`int8`'s own `source` is already the base constructor, per §5.5's own rule that refinement always
records `source: I.source`), and desugars `bigNumber` to `!integer_type { min: -500  max: 5000 }`
-- `size` is gone entirely. `bigNumber` ends up an *unconstrained-width* integer with bounds, not
an 8-bit signed integer with bounds, even though it is declared as a refinement of `int8` and
nothing in the source text ever removes `int8`'s own `size` constraint.

This is a genuine internal inconsistency, not just an underspecified corner case: §5.7's own "Body
materialisation" rule, for the *structurally analogous* record-refinement case, is explicit that
inherited constraints survive a refinement that doesn't mention them -- "the refined body re-emits
the *complete* inherited field set... **Inherited REQUIRED_FIXED and REQUIRED_DEFAULT fields appear
with their pinned values even when the refinement did not refer to them**." There is no stated
reason atom refinement -- called "refinement," the same word, immediately adjacent in the same
section family, and explicitly said to support further chaining (§5.5: "`age`... can be refined
further") -- should behave in the *opposite* way (full replacement) rather than the same way
(tightening: explicit values override, everything else survives).

**Interpretation chosen:** Merge, not replace. `tson-compiler`'s `DefinitionResolver.resolveAtomRefinement`
re-serializes `I`'s own already-bound value back to wire form (reusing `TsonObjectWriter`, so no
hand-written per-type merge logic is needed for any of the many atom-constraint classes), merges it
field-by-field with the new refinement's own `values` (explicit values in `values` win; every field
`I` itself already bound but the new refinement doesn't mention keeps `I`'s own value), and binds
the merged record generically against the resolved constructor -- the same generic binding path
used for the non-chained case, since a fresh/`UNCONSTRAINED` source's own serialized form
contributes nothing (every field absent) and the merge is then a no-op, recovering exactly the
previous (correct) non-chained behavior as a special case.

**Suggested resolution:** Reword §5.6's "Atom refinement" paragraph to state a merge explicitly,
analogous to §5.7's own "Body materialisation" wording, e.g.: "`!I ^ { values }` desugars by
retargeting to the instance's source constructor, with `values` merged over `I`'s own already-bound
field values -- a field named in `values` overrides `I`'s own value for it; every field `I` itself
bound that `values` does not mention keeps `I`'s own value." Add a chained worked example (something
like the `int8`/`bigNumber` case above) alongside the existing two, since neither current example
exercises chaining at all and the ambiguity only surfaces there.

---

## 18. `unit`'s three real instances (`value`/`token`/`void`) are "distinguished by name and prose-level parsing contract, not by schema shape" -- an implementation has no mechanical way to discover the right data-parsing contract from the resolved schema alone

**Section:** §4.2/§8.1 (the `unit` atom constructor), meta-kernel.tn1's own doc comments on `unit`/
`value`/`token`/`void` (non-normative, but the only place the actual per-instance contracts are
written down at all).

**Problem:** `unit => ~atom & {}` declares an atom constructor with zero constraint fields. Every
instance of it -- `value`, `token`, `void` (meta-kernel's own three; core.tn1 adds a fourth, its own
`void` sibling under the same name) -- resolves to the byte-for-byte identical empty body (`Unit`).
Nothing in the *resolved* `type_definition` distinguishes them. Yet the three have genuinely
different, incompatible parsing contracts, stated only in prose:

- `value`: "the result of base type resolution ([TSON-DATA] §4) applied to a source token... value-
  typed fields receive whatever [TSON-DATA] §4 produces -- null, boolean, integer, float, or
  string."
- `token`: "the canonical NFC-normalised form of a source lexeme," taken verbatim, no further
  interpretation.
- `void`: "parsing contract admits only the absent sentinel `_`. The host value is absent."

meta-kernel's own doc comment for `unit` states this plainly: its instances "are opaque atoms
distinguished by name and prose-level parsing contract, not by schema shape." That is an explicit
admission that resolving `unit`'s constructor tells an implementation nothing actionable -- the only
way to implement `value`/`token`/`void` correctly is to special-case each by its declared *name*,
which works for the four names this spec itself defines but gives no guidance for a schema author
who instantiates `unit` under a new name expecting to document their own prose contract the same
way (there is no mechanism by which such prose could be machine-readable, unlike, say, a constraint
field on a proper atom family).

A concrete implementation bug this caused: this codebase originally had a single shared parser for
all three, which (a) accepted any token whatsoever for `void` (wrong -- should reject everything but
`_`) and (b) rejected `_` outright for `void` (backwards -- `AtomType.read(TokenValue)`'s contract
only ever sees a token, and the absent sentinel `_` is a distinct `core-value` variant, not a token
at all, so `void`'s real contract doesn't even fit the shape every other atom-family parser uses).

**Interpretation chosen:** Name-keyed, hand-picked parsers, matching this codebase's existing
precedent for other cases generic/schema-shape-driven binding can't handle (`enum`'s own `boolean`
member-collision gap, `uri_type`/`regex_type`'s schema-composed RFC defaults). `token` keeps the
original behavior (raw NFC-normalised text, unconstrained). `value` now actually runs
`BaseTypeResolver` and narrows to the natural host type. `void` is implemented outside the ordinary
atom-parser shape entirely -- it inspects the `data-value`'s own `core-value` directly and accepts
only the absent-sentinel variant, returning a host `null`. Dispatch is keyed on the *declaration's
own name*, not on anything in the resolved schema; an unrecognized `unit`-constructed name falls
back to `token`'s behavior (the previous, pre-split default) rather than failing, since the spec
gives no way to know what such a hypothetical fourth instance should actually do.

**Suggested resolution:** Either (a) give `unit`'s three-instance parsing-contract distinction a
machine-readable home -- e.g. a documentation-only annotation whose value is defined to carry
semantic weight for `unit` specifically (unusual, since annotations are elsewhere purely
informative), or (b) split `unit` into three separate, purpose-built constructors (`value_type`/
`token_type`/`void_type`, one 0-field atom family each) so each instance's contract is at least
nameable in the grammar even if still prose-defined, rather than three unrelated contracts sharing
one constructor purely by convention. Either way, state explicitly (the way §5.6's atom-refinement
section states its own worked examples) that an implementation MUST dispatch these three by name,
not by attempting to derive behavior from the (identical, uninformative) resolved shape -- the
current text mentions this only in a meta-kernel source comment, not in the spec prose itself.

---

## 19. Only a meta-kernel-governed schema may declare `~`-marked constructors, but this is never stated as a normative rule

**Section:** §2.2.2 ("The `!!meta` Directive"), §3.3 ("Schema Layering"), §4.2 ("Type Construction"), Part 2.

**Problem:** §2.2.2 draws a sharp line: "User schemas normally chain to `meta.tn1`. Chaining to
`meta-kernel.tn1` directly is a meta-programming case — an alternative type vocabulary replacing
meta, or an extension of the meta layer itself... The meta layer is the format's sanctioned
extension point: new type vocabularies arrive as alternative or extended meta-schemas chaining to
the kernel, never as grammar changes." §3.3 draws the same line from the other direction: "the
**meta-schema** defines the structural vocabulary the type-definition grammar produces... **type
libraries** define specific types... using that vocabulary — type libraries are ordinary schemas;
**application schemas** import type libraries and define domain types on top of them." Both passages
strongly imply that declaring a fresh `~`-marked constructor (§4.2: "the `~` marker prefix declares
a constructor; it sets `constructor: true` in resolver output") is something only a schema chaining
directly to the meta-kernel is entitled to do — an ordinary type library or application schema only
*applies* or *refines* constructors it doesn't declare itself.

But neither passage is phrased as a MUST/MUST NOT, and no other section states it as a rule the
resolver or linker is required to enforce. The grammar itself admits `~` at any type-def body,
regardless of the declaring document's own `!!meta` target — nothing in the ABNF (§12.1) restricts
it. This is corroborated empirically by the real bundled fixtures, not just the prose: meta-kernel.tn1
declares 9 constructors, meta.tn1 (governed directly by meta-kernel) declares a further 18, and
core.tn1 (governed by meta.tn1, one hop further down the chain) declares zero — but nothing says a
conforming implementation MUST reject a hypothetical core.tn1 that *did* declare one.

**Interpretation chosen:** Enforced as a resolver/linker-level rule, and stricter than "structurally
self-referencing": an entry with `constructor: true` is only valid if the declaring schema's own
`!!meta` target is *exactly* `https://tson.io/2026/32/m/meta-kernel.tn1` — the one specific
meta-kernel identity this implementation's own compiled-reader machinery is built against
(`TsonBundledSchemas.META_KERNEL_ID`), not merely "some schema whose own `!!meta` happens to equal its
own `!!id`." This distinction matters beyond pedantry: every resolved `TypeDefinition.body` and every
`!instance` construction (`!enum`, `!integer_type`, ...) is interpretable only because a matching
type constructor is declared in *this specific* meta-kernel — the Java dispatch tables
(`ValueReaderFactoryRegistry`/`AtomTypeReader`/`RecordAbstractReader`) are hard-wired to this one meta-kernel's
own fixed vocabulary. A structurally self-referencing but otherwise unrelated schema could declare a
completely different, incompatible `record`/`array`/... vocabulary and would pass a purely
structural self-reference test while being meaningless to this implementation's own reader
machinery. A library realistically supports one meta-kernel version at a time — a revision bump
would mean rebuilding that machinery, not accepting a differently-identified substitute — so the
check is a fixed-identity comparison, not a structural one. Implemented in
`TsonSchemaLinker.isMetaKernelGoverned`, checked once per locally-declared constructor entry during
`link`'s own validation pass. A schema violating this throws `TsonSchemaValidationException` naming
the offending entry.

**Suggested resolution:** State explicitly, as a MUST, that an entry with `constructor: true` is
only valid in a schema document whose own `!!meta` names *the* meta-kernel — most naturally as a
normative sentence in §2.2.2 or §4.2 rather than leaving it to be inferred from descriptive prose in
two different sections. Separately, and related: the spec should clarify whether "the meta-kernel"
is meant as a single canonical document every conforming implementation resolves against verbatim
(one fixed identity, à la this implementation's own `TsonSchemaLinker.META_KERNEL_ID`), or whether an
implementation is free to define its own compatible meta-kernel under a different identity — the
"one deliberate circularity" language (§1.5) and the pre-loading requirement (§3.4, §10.1) both read
as assuming the former, but neither says so explicitly.

---

## 20. `.tn1` is defined as the file extension for "TSON version 1," but the spec's own pre-release material already uses it

**Section:** §7.1 ("Encoding, Normalization, and Media Type"); cross-referenced against the document
status header and §1.2 principle 7 ("Permanent stability"), and against Part 2's own bundled-schema
table (§9).

**Problem:** §7.1 states: "TSON version 1 uses the file extension `.tn1` for all documents; future
major versions use correspondingly numbered extensions (`.tn2`, …)." This reads as a *positive claim
of stability* — reinforced by §1.2 principle 7: "TSON version 1 is a permanent specification...
There is no TSON 1.1 or TSON 2... The permanence guarantee attaches to the version 1 release:
2026-series revisions of this document, **including this one, may change anything**." The document's
own status header agrees: "The 2026 revision series is subject to change without compatibility
guarantees. **When finalised**, this specification will be published as **TSON version 1** and
frozen... until then, revisions are released under the 2026 series." Taken together, these three
passages say plainly that "TSON version 1" — and by extension the stability the `.tn1` extension is
defined to signal — has not been reached yet, and may never be reached in the document's current
form.

Yet the spec's own bundled, normative fixtures are already published using that exact extension,
during this explicitly-unstable period: `meta-kernel.tn1`, `meta.tn1`, and `core.tn1` (Part 2 §9) are
served at `https://tson.io/2026/32/m/meta-kernel.tn1`, `.../meta.tn1`, `.../core.tn1` — real,
resolvable URLs an implementation is required to fetch/pre-load verbatim to be conformant at all,
carrying a `2026/32` (year/revision) path segment that is, by the spec's own words, not "TSON version
1." A file extension defined as "this document will not change" is already load-bearing on documents
the spec's own status line says may still change in any way. Nothing in the spec addresses this gap:
there is no notion of a draft/pre-release file extension, no statement that the 2026-revision-series
bundled fixtures are a deliberate, acknowledged exception to §7.1's rule, and no guidance for a
downstream implementation (like this one) or an application built on this library about what
extension its own schemas should use while the spec itself is still in this state — every schema this
project publishes today necessarily also uses `.tn1` (see `CLAUDE.md`'s own "Project-owned schema
`!!id` convention"), for lack of any spec-sanctioned alternative, making the identical claim of
stability the bundled fixtures make.

**Interpretation chosen:** None yet — this implementation currently has no choice but to follow the
spec's own bundled-fixture precedent and use `.tn1` throughout (including for its own project-owned
schemas), since renaming the spec's own published `meta-kernel.tn1`/`meta.tn1`/`core.tn1` identities
is not something a consuming implementation can do unilaterally: those URLs are fixed, external
identities this implementation must fetch and reference verbatim to interoperate at all. Whether this
project's own artifacts (as opposed to the spec's) should adopt a different, explicitly-unstable
extension while the spec remains in the 2026 revision series is an open decision, being tracked
separately (`BACKLOG.md`) rather than resolved here — a leading candidate under discussion is an
unversioned `.tn` extension, scoped as "no stable version guarantee" (rather than strictly "pre-v1",
so it wouldn't need redefining if TSON ever entered a v2 draft period after v1 ships) and reserved
for project-owned, non-canonical artifacts only, since the spec's own bundled fixtures can't be
renamed regardless of what this project decides.

**Suggested resolution:** Add a normative statement to §7.1 (or the document status header) covering
the pre-finalization case explicitly — for example, either (a) state that 2026-revision-series
documents, including the spec's own bundled `meta-kernel.tn1`/`meta.tn1`/`core.tn1`, are a
deliberate, acknowledged exception to the "version 1" naming rule, made because there is currently no
alternative extension defined, and that this will be corrected at the version 1 release; or (b)
define an explicit, unversioned extension (e.g. `.tn`) for any document produced before the version 1
freeze, reserving `.tn1` as a claim that MUST NOT be made before that freeze actually happens — the
same distinction the spec already draws at the media-type level between bare `application/tson` (no
stability claim) and `application/tson; version=1` (a positive one). Either resolution should also
say whether a document renamed from a pre-release extension to `.tn1` at the version 1 release is
expected to change its own `!!id` (and therefore its canonical identity, §2.2.1) — a real
interoperability question for anything hash-pinned or referenced during the draft period.

---

## 21. §2.5's "last value wins" for a duplicate record field name doesn't say whether a shadowed occurrence's own value must still be validated

**Section:** §2.5 ("Records").

**Problem:** §2.5 states that when a field name is repeated within the same record, "the last value
associated with that name is the field's value" — a resolution rule for which value survives, said
purely in terms of the final result. It says nothing about what a conformant processor is required
(or permitted) to do with an *earlier*, shadowed occurrence's own value on the way there: whether it
must still be lexed/parsed/validated as a real value in its own right (so a malformed shadowed
occurrence is itself a parse/validation error, independent of whether it's ultimately kept), or
whether an implementation may skip it entirely once a later occurrence of the same name is known to
exist, treating it as inert, unvalidated text. Both readings are consistent with "the last value
wins" as a *result* — the gap is about the process that gets there, and specifically about a case
this implementation actually had to choose between: single-pass, forward-only, event-stream-based
reading (§7.4's own token stream is inherently single-pass) genuinely cannot know in advance, upon
seeing the *first* occurrence of a field name, whether it will recur later — so it must either buffer
that occurrence speculatively (undoing the memory/latency benefit of streaming in the first place) or
decode-and-validate it immediately and simply let it be overwritten if a later occurrence turns up.

**Interpretation chosen:** Every occurrence of a duplicate field name is read and validated in full,
forward, in source order — not just the one that ultimately wins. A malformed *shadowed* occurrence
(one later overwritten by a subsequent occurrence of the same name) still surfaces as a real
diagnostic (a thrown `TsonReadException` in fail-fast mode, or a collected `Diagnostic` in collecting
mode) even though its own decoded value is discarded once the later occurrence is read. This is a
deliberate, observable behavior change from an earlier version of this implementation, which scanned
a record's own (fully materialized) field list *backward* and skipped a field name already filled —
never touching a shadowed occurrence's own value at all, so a malformed shadowed occurrence was
silently ignored. The backward-scan approach was only possible because that earlier version always
had the record's complete field list in hand before reading any of it (built from a pre-parsed
`Document` tree); once reading moved to pull genuinely one event at a time directly off the lexer
(`RecordAbstractReader`/`TsonDataStream`, "Streaming readers" in `docs/readers-and-diagnostics.md`), backward iteration was
no longer available at all, and the forward, validate-everything behavior was chosen as the one
consistent with never buffering more than one open container's worth of state. Verified in
`DuplicateFieldTest`: a record with the same field name twice, first occurrence out of range
for its own atom type, second occurrence valid, confirms exactly one diagnostic is reported for the
first (shadowed) occurrence and the field's own final value is the second (surviving) occurrence's.

**Suggested resolution:** State explicitly, alongside "the last value associated with that name is
the field's value," whether a conformant processor MUST, MAY, or MUST NOT validate a shadowed
duplicate occurrence's own value — ideally phrased so a genuinely single-pass, streaming
implementation (one that cannot know in advance whether a field name will recur) remains conformant
either way, e.g.: "a processor MAY validate every occurrence of a duplicate field name as it is
encountered, or MAY validate only the occurrence that ultimately wins; a document MUST NOT be
considered invalid solely because an implementation chose to (or declined to) surface a problem in a
shadowed occurrence." This also has a direct interoperability consequence worth naming: two
conformant processors reading the identical malformed-duplicate document may legitimately disagree on
whether it's valid at all, purely as a function of their own internal parsing strategy (streaming vs.
buffered) — worth the spec saying so plainly rather than leaving it to be discovered.

---

## 22. `regex` is pinned to RFC 9485 (I-Regexp), but the spec never says whether the pin is a strict subset gate — nor that an implementation must document divergence from the RFC

**Section:** Part 2 — meta-kernel's `regex_type` (`spec: = "https://www.rfc-editor.org/rfc/rfc9485"`,
a `REQUIRED_FIXED` field), the `text_type`/`uri_type` `pattern: regex?` fields, and the RFC 9485
reference (Part 2 references table).

**Problem:** The spec pins its regex atom to I-Regexp normatively — `regex_type` fixes `spec` to
RFC 9485, and every `pattern:` constraint on `text`/`uri` is a `regex` — but leaves two things
unstated:

1. **Is the pin a strict subset gate, or just a label?** RFC 9485 defines I-Regexp as a deliberately
   restricted *subset* of common regex syntax (no anchors `^`/`$`, no back-references, no lookaround,
   no non-greedy quantifiers). The spec never says whether a `regex` value that steps outside that
   subset MUST be rejected at schema load, or is tolerated. An implementation that validates
   well-formedness by delegating to a host engine (`java.util.regex`, PCRE, ECMAScript `RegExp`) will
   *accept* a large superset of I-Regexp, so a schema carrying a non-portable pattern loads cleanly on
   one implementation and is rejected by a strict one — an interoperability break invisible to
   hash-pinning, since both documents are "valid" locally.

2. **Nothing requires an implementation to document where its regex semantics diverge from the RFC.**
   Even for constructs *inside* the I-Regexp subset, a host engine diverges observably: `.`
   line-terminator handling, `\d`/`\w`/`\s` ASCII-vs-Unicode membership, `\p{...}` category naming, and
   backtracking-vs-linear matching (ReDoS). Because the format's whole premise is cross-implementation
   determinism (hash-pinned schemas, "identical behavior everywhere"), an *undocumented* divergence in
   regex semantics silently defeats it: two conformant implementations accept the same schema and the
   same data yet disagree on whether a `pattern` matches.

**Interpretation chosen:** Treat the pin as strict *intent* — a `regex` value should be valid I-Regexp
or a resolver error. The native engine this called for has landed: `tson-regex` parses RFC 9485 to its own
AST and matches with a Thompson-NFA/Pike-VM, so `RegexParser`/`TextParser`/`UriParser` define I-Regexp
behaviour here rather than inheriting `java.util.regex`'s laxer superset.

**`email` is the second instance of the same question**, and is answered the other way. `email_type`'s
`spec` is `REQUIRED_FIXED` to RFC 5322, but `EmailParser` implements a documented *subset* — the
`dot-atom "@" dot-atom` core, rejecting quoted local parts, domain literals and comments, all of which RFC
5322 admits. Unlike I-Regexp, the full grammar here is not worth conforming to: it would admit addresses
containing spaces and parentheses into a field consumers treat as a token. So one RFC pin is honoured
strictly and another deliberately is not, which is exactly why the spec should say what a pin obliges.

**Suggested resolution:** Two additions. (a) State explicitly whether the RFC 9485 pin is a strict
subset gate — recommended, consistent with the `REQUIRED_FIXED` pin and the interoperability premise: a
`regex` value that is not valid I-Regexp is a resolver/validation error. (b) More importantly, add a
normative requirement that **an implementation MUST document any non-conformance from RFC 9485** — which
I-Regexp constructs it does not enforce, where it delegates to a host regex engine, and the Unicode
version its character-class semantics follow — mirroring §7.1's existing "SHOULD document which Unicode
version they support" convention. Cross-implementation determinism cannot be assumed for `pattern`
matching unless divergences are declared, so the spec should require them to be.

---

## 23. Can TSON text read an untagged choice whose scalar variants are value-set-disjoint but share a base-type class (e.g. `(positive_integer | negative_integer)`)?

**Section:** Part 2 §5.4 (Tagging + Disjointness).

**Problem:** §5.4 derives an encoding-independent `disjoint` fact and, for tagging, says a value MAY omit the
`!variant` tag "unless the choice is disjoint under the active encoding's discrimination... Where the tag is
omitted, the variant is recovered by the same form resolution the encoding already performs — for TSON text,
the single base-type-resolution pass of [TSON-DATA] §4 — never by a second, type-directed inspection of the
value's form. Where the encoding cannot separate the variants — variants that share a base-type class, such
as `(email | uri)` (both the string class) — the tag is REQUIRED."

Base-type resolution (§4) classifies a token only into null/boolean/number/string — it reaches the base
*class*, not the specific variant. So for `(positive_integer | negative_integer)` — both the number class,
and value-set-disjoint (their bound intervals don't meet) — recovering positive vs negative requires checking
the value's sign/range against each variant, which reads exactly like the forbidden "second, type-directed
inspection." Read strictly, the tag is therefore REQUIRED even though the variants are provably disjoint. But
then §5.4's own baseline rule "same-family numerics are compared by their bound intervals" can *never* enable
untagged TSON-text reading (every same-family numeric pair is the same number base class); its only consumers
would be the `@disjoint` assertion check and non-text encodings. It is unclear whether that is intended, or
whether TSON text is meant to use the value itself (a value inspection, not a type re-parse) to pick the
range-disjoint variant — which the "never a second, type-directed inspection" clause appears to forbid.

**Interpretation chosen:** The `disjoint` *fact* is derived regardless, and as fully as exact rules allow —
kind, atom family, IS-A, numeric bound intervals, text lengths, I-Regexp pattern disjointness, enum member
sets, and record closure. For TSON-text untagged reading specifically, this implementation reads §5.4
strictly: a choice's tag is omissible only when its variants occupy *distinct base-type classes* (so §4's
single pass discriminates them with no type-directed inspection); a same-base-class choice — including a
value-disjoint numeric one like `(positive_integer | negative_integer)`, and every pattern- or
enum-separated string choice — requires the tag. The two readings are kept independent in code rather than
by convention: `ChoiceReader` requires distinct base-type classes *in addition to* a proved `disjoint`, and
tests them itself, so every same-family rule above feeds only the `@disjoint` check and the
encoding-independent fact — no strengthening of the prover can drop a tag a reader needs.

**Suggested resolution:** State explicitly whether TSON text's discrimination may consult a scalar value's own
resolved form (sign/magnitude for numbers, content for strings) to select among same-base-class value-disjoint
variants, or whether — as "never a second, type-directed inspection" implies — same-base-class choices always
require the tag. If the latter (the reading chosen here), note that the "same-family numerics compared by bound
intervals" rule is, for TSON text, only ever consumed by `@disjoint` and other encodings, never by untagged
reading — worth saying plainly, since a reader naturally expects `(positive_integer | negative_integer)` to be
untagged-readable and it is not.

*(#47 has since resolved this in this implementation by definition: `disjoint` is now derived as
discrimination-class distinctness, so "disjoint" and "separable by §4's single pass" are one fact —
`(positive_integer | negative_integer)` derives `false` outright, and the strict reading above stops being
an extra gate and becomes the meaning of the fact.)*

---

## 24. §5.4's "`disjoint`: true when proved, absent otherwise" doesn't accommodate the "provably not disjoint" (`false`) state the `@disjoint` refutation check requires

**Section:** Part 2 §5.4 (Disjointness + The `@disjoint` assertion); meta-kernel `type_definition.disjoint: boolean?`.

**Problem:** The Disjointness paragraph says the resolver "records the result in `type_definition.disjoint`:
`true` when disjointness is proved, absent otherwise" — a two-valued description (true / absent). But the
`@disjoint` assertion paragraph distinguishes **refuted** ("`@disjoint` present, provably not disjoint" → a
resolver error) from **unprovable** ("neither proved nor refuted" → a warning). Telling "provably not disjoint"
from "merely unproven" is a third state the two-valued "true / absent" field can't carry. The kernel models the
field as `boolean?`, which does admit three states (absent / true / false), so the model can hold a `false`;
the prose just doesn't say the derivation records one.

**Interpretation chosen:** Treat `disjoint` as genuinely three-valued (`Optional<Boolean>`): `true` proved
disjoint, `false` provably not disjoint (an IS-A variant pair, or overlapping numeric bounds), absent otherwise
— so the `@disjoint` check can distinguish refuted (`false`) from unprovable (absent) directly from the stored
fact, rather than recomputing.

**Suggested resolution:** Amend the Disjointness paragraph to say the field records `true` (proved disjoint),
`false` (provably not disjoint), or is absent (neither proved) — matching the `boolean?` model and the three
cases the `@disjoint` check already enumerates. As written, "true when proved, absent otherwise" reads as
two-valued and leaves the refuted state's storage unspecified.

*(#47 has since dissolved this in this implementation: the derivation is total and two-valued, so a linked
choice always records `true` or `false` and there is no third state to store — the better fix is #47's
rewrite rather than the three-state prose amendment above.)*

---

## 25. Does the spec require rejecting a non-productive (unsatisfiable) recursive type — e.g. mutually required-recursive records?

**Section:** Part 2 §3.4.1 (resolution), §5.9 (composition), §8.

**Problem:** A schema can declare a recursive type that no *finite* data can satisfy — e.g. `x => { y: y }` /
`y => { x: x }` with REQUIRED fields: an `x` requires a `y`, which requires an `x`, with no base case. This is
distinct from legitimate recursion, where a cycle is *guarded* by an optional field (`node => { next: node? }`)
or a possibly-empty array/set (`tree => { children: [tree] }`) that provides a finite base case. §3.4.1 covers
resolution (populating the namespace, resolving bodies, computing the IS-A graph) but says nothing about
whether a resolver MUST/SHOULD reject a recursive type that is well-formed yet has no finite model — a
*productivity*/satisfiability property, not a resolution one. Left unstated, two conformant implementations
can disagree on whether such a schema is valid at all.

**Interpretation chosen:** This implementation resolves and links a required-recursive record pair like
`x => { y: y }` / `y => { x: x }` without complaint — it is structurally well-formed (every reference
resolves), and its unsatisfiability is treated as a semantic property outside resolution's remit, the same way
an over-constrained atom (`int8 ^ { min: 300 }`, an empty value set) is well-formed but unsatisfiable. Only
cycles that genuinely block *resolution* are rejected: a composition/refinement-source cycle (`a => b & {}` /
`b => a & {}`), where resolving one needs the other's resolved form (`SchemaResolver`'s own on-demand
dependency-following resolution, §3.4.1). A productivity analysis — is every recursive cycle guarded by an
optional or possibly-empty member? — is not implemented and is considered out of scope for resolution/linking.

**Suggested resolution:** State whether a resolver MUST reject a non-productive recursive type (no finite
model), SHOULD warn, or MAY leave it — the same tri-state §5.4's `@disjoint` handling uses. If left to the
implementation, name it as an explicit interoperability caveat (like the shadowed-duplicate case, #21): two
conformant processors can legitimately disagree on whether `x => { y: y }` / `y => { x: x }` is a valid schema,
purely by whether they attempt productivity analysis. If a MUST/SHOULD is intended, the spec should also define
what counts as "guarded" (which members provide a base case: optional fields, possibly-empty arrays/sets,
choice variants that bottom out, and so on).

---

## 26. Content-hash pinning rides in the URI query (`?sha256=`), where a hash is neither a request parameter nor part of identity — external review suggests a fragment, or a structured `{ url, sha256 }` directive, instead

**Section:** Part 1 §2.2.1 (canonical identity / hash-pinned references), §10.2 (per-identity verification).

**Problem:** The spec pins a reference's integrity by appending a *query* parameter to its URI —
`!!import:"…/core.tn?sha256=<hex>"` — and then defines canonical identity by **stripping** that query, so a
pinned and a plain reference name the same identity. Two objections from external review:

1. **Query is the wrong URI component for a hash.** By URI semantics (RFC 3986 §3.4) a query is part of the
   *request* — data conveyed to the origin to identify/produce the resource — whereas a content hash is
   *verification metadata about the retrieved bytes*, evaluated entirely client-side and never meaningfully
   sent to a server. A *fragment* (`#sha256=<hex>`, §3.5) is the component that actually matches: it isn't sent
   in the request, is interpreted by the client, and is already outside what a server sees. That the spec must
   special-case *stripping* the query to recover identity is itself a symptom that the hash sits in a component
   whose native semantics it doesn't share.

2. **Integrity arguably shouldn't be in the URI at all.** A second reviewer proposes separating the locator
   from the integrity outright — a structured directive value rather than a hash smuggled into a string:

   ```
   !!schema: { url: "https://example.com/people.tn"  sha256: "c4d5e6f7…a2b3c4d5" }
   ```

   This drops URI-parsing of hash parameters, the canonical-identity stripping rule, and the "only
   hash-algorithm query parameters permitted, everything else rejected" special case; makes the algorithm an
   explicit, extensible field rather than a magic query key; and mirrors how lockfiles / package managers /
   Subresource Integrity separate *where* from *what it must hash to*. It is the larger change: directives
   currently take a bare URI string, so this is a directive-grammar change, and `!!id` (which today carries its
   own pin on its own line, excluded from the hash) would need an equivalent structured form.

**Interpretation chosen:** This implementation follows the spec as written — the query form. `TsonContentHash`
parses `?sha256=<hex>` off a reference (rejecting any other/unrecognized query parameter or malformed hex),
`CanonicalIdentity`/`TsonSchemaRegistry` strip the query to key everything by identity, `tson hash <file>`
stamps `?sha256=` onto the `!!id` line, and the bundled chain (meta.tn pins meta-kernel, core.tn pins meta.tn)
is pinned end-to-end this way. No change made — flagging the design, not diverging from it.

**Suggested resolution:** Choose among three. (a) Keep the query form — simplest, but semantically stretched
and dependent on the identity-stripping rule. (b) Move the pin to a fragment (`#sha256=<hex>`) — better matches
URI semantics, and identity can still ignore it by dropping the fragment; here a small, localized change (parse
`#` rather than `?` in `TsonContentHash`, and in `tson hash`). (c) Lift integrity out of the URI into a structured
directive (`{ url, sha256 }`) — the cleanest separation of locator from integrity, at the cost of a
directive-grammar change plus an `!!id` equivalent; here it touches the directive grammar, `TsonContentHash`,
canonical identity, `tson hash`, and every bundled `.tn`'s pin lines. If the query form stays, the spec should
at least justify why a hash lives in the query and name the identity-stripping as its deliberate consequence.

## 27. §5.7 says a refinement "tightens," but core.tn's own worked examples call a *selector* swap a narrowing — leaving "tightens" undefined for any facet that doesn't order

**Section:** Part 2 §5.7 (refinement), §5.5 (atom refinement), with core.tn's own `complex` declaration as the
counterexample.

**Problem:** §5.7's refinement rule is stated in terms of tightening: a refinement narrows its source's
constraints and never loosens them. For an *ordered* facet that is unambiguous — an integer's `min`/`max`, a
text's `min_length`/`max_length` — one value is straightforwardly more restrictive than another, and a
refinement stating a looser one is checkable and rejectable.

A constraint vocabulary also carries facets that don't order at all. They *select* among unordered
alternatives rather than measuring anything:

- `complex_type.component` (`INTEGER`/`NUMBER`/`RATIONAL`/`FLOAT32`/`FLOAT64`)
- `float_type.format` (the IEEE format enum)
- `binary.encoding` (`BASE64`/`BASE64URL`/`BASE32`/`HEX`)
- `uuid_type.version`

Nothing in the spec says what "tightens" means for one of these, and core.tn's own prose answers it in a way
the §5.7 wording does not support. Its `complex` declaration reads:

> Components default to `NUMBER` (exact); **a narrowing may set `component`** to another member of the closed
> vocabulary — e.g. `!complex ^ { component: INTEGER }` for Gaussian integers, `!complex ^ { component:
> FLOAT64 }` for floating-point complex.

`complex`'s own resolved body has `component: NUMBER` (the schema default), so both examples *replace* an
already-bound selector, and the second replaces the exact tier with an approximate one — binary64 values are
emphatically not a subset of the exact arbitrary-precision `NUMBER` tier. The spec calls that a narrowing
anyway. So either "tightens" is meant loosely enough to admit any selector substitution, or core.tn's own
examples violate §5.7; the text supports neither reading over the other.

The distinction is load-bearing for anything that actually enforces §5.7. A checker has to decide, per facet,
between three behaviors: reject a substitution (treat the selector as an identity facet a refinement may
restate but not change), accept any substitution, or accept only substitutions that shrink the underlying
value set — and the third is not even well-defined here, since `FLOAT32` ⊂ `FLOAT64` as sets of reals while
`INTEGER` ⊄ `FLOAT64` and neither is a subset of `NUMBER` in the direction core.tn uses.

**Interpretation chosen:** Selector facets are left **unchecked**. This implementation enforces §5.7's
tightening rule on ordered facets (bounds, lengths, digit counts, prefix lengths), on permission flags
(`allow_nan` and friends: withdrawable, never re-grantable), and on member sets (an enum's `members` may only
shrink) — and says nothing about a selector, so `!complex ^ { component: FLOAT64 }` resolves exactly as
core.tn documents. Rejecting a substitution would reject the spec's own example; accepting only value-set
shrinkage would too. The conservative choice is that a false rejection of a documented construct is worse than
a missed error, so the hole is deliberate and documented on each affected class (`ComplexType` carries the
reasoning, `FloatType`/`BinaryType`/`UuidType` point at it).

**Suggested resolution:** State the rule per facet *kind* rather than as one word. Concretely, §5.7 should say
which of its constraint-vocabulary fields are ordered (tightening checkable and required), which are selectors
(substitution permitted, and whether the result must remain IS-A its source), and which are fixed (a
refinement may restate but not change — `spec`/`specification` are already `REQUIRED_FIXED` and behave this
way). Failing that, at minimum reconcile core.tn's "a narrowing may set `component`" with §5.7's own wording:
if a selector swap is legal, the refinement rule cannot be stated purely as tightening.

## 28. A generic-application head resolves through two namespaces with silent shadowing between them, and the precedence is stated less precisely than the equivalent rule for `!` targets

**Section:** Part 2 §3.3.1 (structure namespace, "Generic-application heads"), §3.3.2 (type-name namespace
lookup order), §5.10 (parameters).

**Problem:** §3.3.1 does answer the basic question, and clearly — a generic-application head *is* a
constructor role, with `map<text, text>` given as the literal example:

> - **Generic-application heads** — the name before `<` when the name is not otherwise in scope
>   (`map<text, text>`, `set<text>`).

So a user schema whose `!!meta` is `meta.tn` can write `map<text, X>` as a field type: `meta.tn` imports the
meta-kernel, and §3.3.1's own "Import what you expose" paragraph names that import as the delivery mechanism
for exactly this vocabulary. No re-declaration in `core.tn` is needed. What is underspecified is everything
around that answer.

**1. The same precedence rule is written two different ways.** For `!` targets §3.3.1 is explicit and
ordered: "`C` resolves first against the type-name namespace … and then against the structure namespace."
For generic heads it says only "when the name is not otherwise in scope". Presumably identical intent, but
one formulation is normative and testable and the other leaves "otherwise in scope" undefined. Reading it
against §3.3.2, "in scope" must mean the type-name namespace, whose lookup order is *parameters, then local
declarations, then imports* — but the reader has to assemble that themselves.

**2. Shadowing is silent, unpoliced, and has no escape hatch.** Because the type-name namespace is consulted
first, a schema that declares — or merely imports — anything named `map` silently captures every
`map<…>` in that document. Adding an unrelated local declaration called `map` retroactively changes the
meaning of existing field types, with no diagnostic and no syntax for naming the structure-namespace entry
once it is shadowed. The spec clearly does care about accidental capture elsewhere: §3.3.2 requires
`!!import` names to "be disjoint from each other and from local entries (§2.2.3)". Capture *across* the two
namespaces is the one direction left unchecked, and it is the direction where the shadowed name is invisible
in the document doing the shadowing.

**3. Are type parameters eligible at a generic head?** §3.3.2 puts parameters *first*, ahead of local
declarations. Taken literally that makes `weird => <map> map<text, text>` resolve `map` to the type
parameter, applying a parameter as though it were a constructor — which has no arity, no `parameters` list,
and no meaning under §5.10. Either parameters should be excluded from the "otherwise in scope" test at a
generic head, or applying one should be named as a resolver error; the text supports neither today.

**4. Whether the constructor gate applies depends on which namespace won.** §3.3.1 requires that "an entry
consumed at an *author-written* constructor role MUST be a constructor (`constructor: true`)". But a head
that *is* otherwise in scope was never at a constructor role, so the gate never fires for it — which is
correct and necessary, since a local parameterized template (`array_min<text, 1>`, §5.10) is not a
constructor and must remain applicable. The consequence is that one syntax carries two different validity
rules, selected by a lookup outcome the author cannot see. That is defensible, but it reads as a
contradiction until spelled out, and §5.10 never mentions the interaction.

**Interpretation chosen:** A generic-application head is resolved in a **desugar phase that runs before
resolution** (`SchemaDesugarer`): `map<text, X>` is rewritten into a real declaration
`map_text_X_<hash> => !map { key_type: text  value_type: X }` plus a bare reference to it, so by the time
anything resolves or links, a generic head no longer exists as a distinct construct. The head is looked up
in the governing meta's own entries (the structure namespace) and must be `constructor: true` with matching
arity; the arguments zip positionally against the constructor's `parameters` and route to vocabulary fields
by each field's `value_param`. That makes §3.3.1's answer mechanical for *every* constructor rather than the
handful an implementation happens to have hand-written support for.

On the four questions above, this implementation currently answers:

1. **Ordering:** not implemented as stated. A head is resolved against the structure namespace **only** —
   the type-name namespace is not consulted first. In practice the two agree, since a schema that declares
   its own `map` is exactly the shadowing case below.
2. **Shadowing:** silently resolves to the structure-namespace constructor, so a local or imported
   declaration named `map` does *not* capture `map<…>`. This is the opposite of what §3.3.2's ordering
   implies, chosen because it is the reading under which the spec's own worked example
   (`map<text, text>` in a user schema) always works. It should follow whichever way §3.3.1 is eventually
   worded; nothing depends on the current choice beyond this note.
3. **Parameters at a head:** never eligible — a parameter is not in the structure namespace, so
   `weird => <map> map<text, text>` resolves `map` to the constructor, not the parameter. Not a considered
   answer to the question so much as a consequence of (1).
4. **Constructor gate:** applies exactly when the head resolved to a constructor, which is the only way it
   resolves at all here. A **non-constructor head is passed through untouched** — a local parameterized
   template (`box<text>`, §5.10) stays a generic reference, and since §5.10 parameter substitution is not
   implemented, such a schema links and compiles but fails at read time with `'T' is referenced but not
   present in the schema`. Tracked in `BACKLOG.md`; the template case is the one shape this phase
   deliberately leaves alone.

**Suggested resolution:** State the generic-head rule with the same explicit ordering the `!`-target rule
already uses, rather than the looser "not otherwise in scope". Say whether parameters participate in that
lookup, and what happens when one wins. Say explicitly that the constructor gate applies only when the head
resolved through the structure namespace, so the template-application case is visibly legal. And decide
whether cross-namespace shadowing is intended: if it is, say so and note that a schema can capture a
constructor name; if not, either require a diagnostic when a local name shadows a structure-namespace
constructor that the document also applies generically, or provide a way to name the shadowed one.

## 29. §6 makes annotations typed, resolved and validated, but the Class 2 conformance list requires nothing of them — so the whole section has no normative force

**Section:** Part 2 §1.3 (Class 2 conformance), §6 (Annotations as Types), §3.3.3 (annotation resolution);
[TSON-DATA] §1.5 (Class 1 conformance), §3.1.

**Problem:** §6 states a strong contract:

> This section defines annotation semantics: an annotation is a typed metadata attachment, **resolved and
> validated against a type reachable through the schema chain.** … The value is validated against `T`'s
> contract.

with §3.3.3 fixing where the name resolves (one hop against the governing target's namespace — the
`!!schema` target for a data document). Nothing in the conformance definition requires any of it. §1.3's
Class 2 bullet list covers pre-loading the kernel, resolving type annotations, producing `type_definition`
output, ingest, identity agreement, hash verification, and error categories. **There is no bullet about
`@` annotations at all** — not to resolve them, not to validate a value against `T`'s contract, not to
preserve them in resolver output.

So a processor that ignores §6 in its entirety is fully Class 2 conformant, and two conforming processors can
disagree completely on whether `@nonexistent:"x"`, `@doc:42` (where `doc` is text-targeted), or a bare
`@doc` (where §6 makes it shorthand for `@doc:_`, which text does not admit) is valid — with nothing in the
conformance model to settle it. That is the outcome [TSON-DATA] §1.5 explicitly designs *against* one layer
down, in near-identical circumstances: "the vocabulary is implemented as a unit, so two conforming
processors never disagree on whether a built-in name is meaningful."

**A terminology trap makes the gap easy to miss.** §1.3's second bullet reads "MUST resolve type annotations
through the active schema when one is in scope" — which looks like it covers this and does not. Throughout
the series "type annotation" means `!T` (§3.2), a distinct construct from an "annotation" `@T` (§3.1); Part 1
§1 lists "annotations, type annotations, and directives" as three separate things, and §1.5 keeps them
separate in the same way. The bullet is about `!T` only. A reader scanning the conformance list for
annotation obligations will find that bullet and reasonably conclude the matter is handled.

**Compounding this**, §2.1 and §6 both assert that resolver output *preserves* annotations in their authored
positions, and §6 says `field-def` annotations "map to the `record_field` in resolver output" — but the
kernel's `record_field` and `type_definition` have no annotations field, and `grep -rn "annotations:" spec/`
finds none anywhere. So even the preservation half of §6 has no representation to land in. That deserves its
own entry; noting it here only because it means §6 is unenforced *and* unrepresentable, rather than merely
one or the other.

**Interpretation chosen:** §6 is implemented as though it were normative, because the alternative is that an
entire section of the specification means nothing. On the schema-driven tree path an annotation's name is
resolved against the governing schema and its value is read by that type's own compiled reader, so
`@doc:42` against a text-targeted `doc` fails for the ordinary reason any wrong-typed value fails; a name
the schema does not declare reports `UNKNOWN_TYPE_REF` and the annotation is then **kept**, read
structurally, because [TSON-DATA] §1.5 requires preserving annotations a processor does not act on and
dropping it would trade one conformance rule for another. §6's bare form is checked rather than assumed: `@T`
is treated as `@T:_`, so a bare annotation on a type that does not admit the absent sentinel is a
`TYPE_MISMATCH`. The schemaless path validates nothing, having no governing schema — that is Class 1's
treatment and is correct there.

This is a deliberate choice to be stricter than conformance requires, and it has a cost worth recording: a
document whose annotations resolve nowhere now produces diagnostics where it previously read silently. The
`@deprecated`/`@expires` case in §2.1 below is exactly that shape.

**Suggested resolution:** Either add a Class 2 bullet giving §6 force, or mark §6 informative and say so. If
it becomes normative, three things need stating that currently are not:

1. **What happens when an annotation's name does not resolve.** A resolver error, a diagnostic, or preserved
   unvalidated? This collides with [TSON-DATA] §3.1's "MUST preserve annotations without validating them" and
   §1.5's "MUST preserve … type annotations outside the vocabulary" — Part 1 mandates keeping what it cannot
   interpret, so Part 2 should say whether Class 2 keeps or rejects what it cannot resolve.
2. **Whether validation is required or permitted.** MUST and MAY give very different interoperability, and
   §1.5's own "implemented as a unit" argument suggests MUST is the intent.
3. **Part 1 §2.1's own example needs checking against the answer.** It writes `tier: @deprecated GOLD` and
   `@expires:"2026-12-31"` in a *data* document. `core.tn` declares only `annotation`, `documentation`,
   `doc`, and `alias` for data documents; `deprecated` lives in `meta.tn`, which governs schema documents.
   Under §3.3.3 both are unresolvable unless the example's own `order.tn1` declares them locally. If §6
   becomes enforced, the series' flagship example is the first thing a strict processor rejects.

## 30. An annotation's value cannot be optional — optionality is a property of a slot, and an annotation's value is not a slot

**Section:** Part 2 §6 (Annotations as Types), §5.2 (field states), §12.1 (`field-type`, `element-type`,
`group-def`, `type-def`); `spec/m/meta.tn`'s annotation vocabulary; [TSON-DATA] §2.1's worked example.

**Problem:** §6 gives an annotation exactly two forms, and they are mutually exclusive:

> - For `void`-targeted `T` (a type whose resolved body, after reference flattening, is `void` — such as
>   `annotation` or `numeric`), the annotation form is `@T` with no colon and no value. Bare `@T` is shorthand
>   for `@T:_`; the resolver fills the implicit `_` and validates against `void`'s contract (§4.2) — presence
>   is the information.
> - For any non-`void` `T`, the form is `@T:value`, where `value` is a single data-value conforming to `T`.

There is no third case, and no way to construct one, because **optionality in TSON attaches to a slot rather
than to a type**. The `?` marker appears in exactly three productions (§12.1):

```
field-type    = type-ref ["?"]                      ; field optionality
element-type  = ( container-def / type-ref ) ["?"]  ; tuple element optionality
group-def     = ... ["?"]                           ; group optionality
```

`type-def`'s own five alternatives carry none, and §12.1's own note is explicit that "the `?` in field-type
marks FIELD optionality (§5.2)". An annotation's value is not a field, element, or group — §6 governs it
directly by the type the annotation names — so there is nowhere for a `?` to attach. `deprecated =>
@annotation text?` is not merely unsupported; it is ungrammatical.

The consequence is that **no annotation type can accept both `@T` and `@T:value`**, which rules out the most
common annotation shape there is: a marker that optionally carries a reason. `@Deprecated` with an optional
message is canonical in Java, Kotlin, Rust and C#.

**This bites the spec's own vocabulary.** `meta.tn` declares `deprecated => @annotation text` (and likewise
`since`, `todo`, `lang`). Being text-targeted, §6 requires `@deprecated:"reason"` and forbids a bare
`@deprecated`. But [TSON-DATA] §2.1's flagship example writes exactly `tier: @deprecated GOLD` — bare.
Granting that a data document's `deprecated` resolves against the user's schema rather than `meta.tn`, an
author who mirrors the spec's own declaration (the obvious thing to do) finds the example's syntax rejected.
This sharpens entry #29's third point: those annotations are not only unresolvable from the standard
library, they are written in a form the only available declaration would not accept.

**Neither workaround is satisfactory.** Declaring two types — `deprecated => @annotation void` alongside
`deprecated_because => @annotation text` — works but splits one concept across two names that every consumer
must then check separately. The structurally correct alternative, a choice spanning `void` and the value
type, fails twice: bare `@T` desugars to `@T:_`, so the reader must select the `void` variant from an
untagged `_`, and §5.4's tagging rule requires an explicit `!variant` tag unless the choice is both disjoint
and discriminable. An author would end up writing `@deprecated:!void _`, which defeats the purpose of the
bare form entirely.

**Interpretation chosen:** §6 is implemented exactly as written. A bare annotation is treated as `@T:_` and
validated by feeding the absent sentinel to `T`'s own compiled reader, so a bare annotation on a non-void
type is a `TYPE_MISMATCH` — meaning no optional-valued annotation is expressible here either, matching the
spec rather than quietly extending it. (§6's "resolved body, after reference flattening, is `void`" is also a
usable *static* test, which is worth noting: void-targeting is decidable from the schema without executing a
reader at all.)

**Suggested resolution:** Two shapes are available, and the second seems considerably better.

1. **Give the annotation's value slot a state**, reusing the concept fields, elements and groups already
   have. The cost is that the `?` would then hang off a `type-def`, where it would mean something different
   from everywhere else it appears — optionality of *this annotation's value*, not of a field.
2. **Make a union with `void` work as the general way to say "optional".** This adds no new concept: it
   requires only that `_` be recoverable untagged against a choice whose variants include `void`. That is
   better founded than §5.4's general caution suggests, because absent-versus-anything-else is *structurally*
   decidable on the wire — a distinct event, not the same-base-type-class ambiguity §5.4 is actually
   guarding against (see #23). It also generalises beyond annotations: union-with-`void` becomes the way to
   express an optional value at any position that has no slot state to carry one.

If (2) is taken, §5.4's untagged-reading rules should say explicitly that a `void` variant is recoverable
from the absent sentinel, and §6's first bullet should be restated as "for `T` admitting `void`" rather than
"for `void`-targeted `T`", so a union qualifies. Failing either, §6 should at least acknowledge that a
marker-with-optional-reason requires two declarations, and `meta.tn`'s `deprecated`/`since`/`todo`/`lang`
should be reconciled with [TSON-DATA] §2.1's bare usage.

---

## 31. `inline-array` and `container-def` are two grammar productions for one construct, and `type-def` is ambiguous between them

**Section:** [TSON-SCHEMA] §12.1 (`type-def`, `type-ref`, `inline-array`, `container-def`, and the notes
following the ABNF), §5.3.

**Problem:** The bracket form is defined twice. `container-def` is reachable from `type-def` directly;
`inline-array` is reachable from `type-def` via `type-ref`. They are not disjoint — `container-def` accepts
every shape `inline-array` accepts, plus size specifiers and element/position `?`:

```abnf
type-def = ... / [type-params] container-def / [type-params] type-ref
type-ref = paren-type / inline-array / type-name "<" type-args ">" / type-name

inline-array  = "[" type-ref ws "]" / "[" type-ref 1*(separator type-ref) "]"
container-def = "[" element-type [ ws ";" ws size-spec ] ws "]" / "[" element-type 1*(separator element-type) "]"
```

So `xs => [text]` has **two derivations**, and the grammar does not choose between them. §12.1's own notes
choose in prose instead:

> `inline-array` and `container-def` overlap on the plain-array and all-REQUIRED-tuple shapes; at top-level
> type-def position `container-def` is tried first, and the two parses are semantically identical there.

A production ordering that only exists in prose is the defect. "Tried first" is a statement about a
particular parsing strategy, not about the language, and it is unnecessary — the ambiguity is entirely
self-inflicted, since one production would cover both positions.

**The restriction the split enforces is real, but it is two different restrictions with two different
justifications, and only one of them needs a separate production — which is to say, neither does.**

- **Element/position `?` is principled.** It records `state: OPTIONAL` on the containing `array` or
  `tuple_element`, so it is a property of the *slot being declared*. A type-ref position declares no slot, so
  there is nothing for it to attach to. The spec already has the right shape for expressing this and uses it
  one production away: `field-type = type-ref ["?"]` puts the `?` **outside** the type-ref. Element `?`
  wants the same treatment — a modifier on the element position, not a reason to fork the bracket.
- **The size specifier is a different argument entirely.** `[T; 1..5]` is not a slot property; it denotes a
  different type. §5.3 says so plainly — "the dividing line is the bracket syntax itself, not expressiveness"
  — and gives the reason the restriction is nonetheless harmless: an ordinary schema cannot name
  `array_ranged` anyway, since the size templates are declared in the meta-kernel and neither `meta.tn` nor
  `core.tn` re-exports them, so the sugar is the only route to a sized array and confining it to declaration
  position forces the author to name the type. That is a defensible authoring rule. It is not a reason for a
  second production: it is a constraint on where a *feature of one* production is admissible, exactly like
  the `?` case, and exactly like the several other position-sensitive rules §12.1 already states as notes
  (`removal-set` on construction heads only; `{` after a bare type-ref in type-def position; `_` invalid in
  type-ref and type-def bodies).

**The two-production shape reads like drift.** The §12.1 note quoted above does not define the split so much
as reconcile it after the fact — it observes the overlap, declares the two parses equivalent where they
collide, picks a winner, and then has to add that the exclusion "applies to type-ref positions only" because
`container-def` nests within itself. That is three clarifications to keep two productions from contradicting
each other. §5.3's "two tiers of type expression, distinguished by position" is the intent, and it is a
statement about *where a feature is allowed*, not about there being two syntaxes.

**Interpretation chosen:** Implemented as specified, prose tie-break included. `TsonSchemaParser` hard-codes
it: at type-def position a `[` goes unconditionally to `parseContainerDef`, never to `parseTypeRef`. The
cost is visible in the AST — `ArrayContainerDef`/`TupleContainerDef` and `InlineArrayRef`/`InlineTupleRef`
are four node types for two concepts, and identical source text yields different nodes depending on
position. Every consumer then has to re-establish that they mean the same thing: `SchemaDesugarer` walks
both paths (`containerDef`/`elementType` for one, `typeRef` for the other) to produce the same
`!array { element_type: T }`, and `SchemaDesugarerTest.inlineArraySugarBecomesTheSameShapeAsAnExplicitApplication`
exists purely to assert that the two shapes converge. Nothing is gained for that; the four nodes carry no
distinction anything downstream acts on.

**Suggested resolution:** Collapse to one bracket production reachable from `type-ref`, and state the two
restrictions as notes the way §12.1 already states its other position-sensitive rules:

```abnf
type-ref     = paren-type / bracket-type / type-name "<" type-args ">" / type-name
bracket-type = "[" element-type [ ws ";" ws size-spec ] ws "]"
             / "[" element-type 1*(separator element-type) "]"
element-type = type-ref ["?"]
```

with: a size specifier and an element/position `?` are valid only where the bracket form is a declaration
body or nested within one; elsewhere they are a parse error, with the diagnostic §12.1 already prescribes.
`type-def` then loses its `container-def` alternative and reaches the bracket form through `type-ref` like
everything else, the ambiguity and the "tried first" rule both disappear, and nesting (`[[T; N]; N]`) works
by the recursion already present rather than by a second `container-def` reference inside `element-type`.
Every shape legal today stays legal and every shape rejected today stays rejected — this is a
simplification of how the rule is written, not a change to the language.

If the split is deliberate and meant to stay, then §12.1 should at least say *why* two productions exist
rather than only how to disambiguate them, since on the evidence of the notes the answer is "so that
`type-ref` cannot reach the size specifier" — which a note on one production expresses more directly.

---

## 32. §8.2 requires a template instantiation to keep supertypes that §3.3.2 puts out of reach of the schema carrying it

**Section:** [TSON-SCHEMA] §8.2 (*Entry shape*), §3.3.2 (type-name namespace), §3.3.1 (constructor roles).

**Problem:** §8.2 is explicit about what a materialised instantiation carries:

> `supertypes`: the template's supertypes, unchanged by substitution (§8.1)

For the kernel's own size templates that chain begins at the constructor being refined. `array_ranged =>
<T, MIN, MAX> array<T> ^ { … }` resolves with `supertypes: [array, product, top]`, and §8.2's own worked
example shows the instantiation carrying exactly that list. So a user schema writing `tag_list =>
[text; 1..2]` materialises an entry naming `array`, `product` and `top`.

None of those three is in that schema's type-name namespace. §3.3.2 is equally explicit that the type-name
namespace is "NOT extended by the structure namespace", and §3.3.1's constructor roles — where the structure
namespace *is* consulted — are author-written positions: constructor-application targets, generic-application
heads, sugar desugar targets, refinement sources. A supertype on a resolved entry is none of those. It is not
author-written at all; it is the residue of one, produced by substitution.

So a conforming resolver following §8.2 produces an entry that a conforming validator following §3.3.2
rejects, in the ordinary case of an ordinary schema using the spec's own sugar. The two rules never meet in
the text: §8.2 does not say the supertypes remain resolvable, and §3.3.2 does not exempt derived references.

**Not merely theoretical, and not confined to the kernel.** Any refinement template over a constructor has
this shape, so any schema layer that publishes such templates hands the same problem to every schema that
applies them. It is also invisible until instantiation exists: a *constructor* application (`set<text>`)
resolves in place as a construction, and §5.5 gives a construction only its target's kind — no supertypes, so
nothing to fail on.

**Interpretation chosen:** A supertype reference gets the structure-namespace fallback, the same one `source`
already has and for the same reason — it is derived from a §3.3.1 role rather than written at one.
Implemented in `TsonSchemaLinker.validateEntry`. The fallback deliberately admits the whole structure
namespace rather than only its constructors: a transitive chain runs past the constructor into the base kinds
(`product`, `top`), which are ordinary non-constructor entries, so a constructors-only rule rejects the very
example §8.2 prints.

The fallback is now defensive rather than load-bearing here: dropping §8.2's supertype transfer (#45's defect
(a)) removed the only case that reached the linker with unnameable supertypes, since a schema that *derives*
from a constructor resolves that source through the type-name namespace and so already names it. The
underlying question — which namespace a resolved entry's derived references are checked against — is
unchanged, and `source` still needs the answer.

**Suggested resolution:** State which namespace a resolved entry's derived references are checked against.
The cleanest reading is that §3.3.2 governs *author-written* type references — what a schema may name — while
resolver output is checked against whatever the entry was derived from, since by construction those names
were reachable at the point of derivation. Saying so once would also settle `source`, which has the identical
shape and today is only reachable by the same unstated reasoning.

---

## 33. A sized array IS-A `array`; the same array without a size specifier is not

**Section:** [TSON-SCHEMA] §5.3, §5.5, §5.6, §8.2.

**Problem:** The two spellings of a declaration-level array take different resolution paths, and the paths
disagree about subtyping:

```
id_list  => [text]        ; array<text>  -- a constructor application
tag_list => [text; 1..2]  ; array_ranged<text, 1, 2>  -- a template application
```

`id_list` is a **construction**: §8.2 says "constructor applications never materialise entries… as declaration
bodies, resolve in place as constructions (§5.6)", and §5.5 says construction "transfers only the
constructor's kind" — no supertypes. `tag_list` is a **template instantiation**: §8.2 requires "the template's
supertypes, unchanged", and `array_ranged` refines `array<T>`, so the entry IS-A `array`.

Both denote an array of text. One is a subtype of `array` and the other is not, decided entirely by whether
the author wrote a size specifier. Nothing in §5.3 suggests adding a bound changes an array's place in the
type hierarchy, and §5.3 argues the opposite when it explains why the size templates are declared without
`~`: "their closures are ordinary members of the array family, IS-A `array` and substitutable where arrays
are expected." The unsized form is the one that fails that description.

This is observable wherever IS-A is: `subtypes` (§8.1), substitutability, and §5.4's disjointness derivation,
which uses IS-A to prove two variants are *not* disjoint — so `(id_list | text)` and `(tag_list | text)` can
be classified differently for no reason an author would recognise.

**Interpretation chosen:** The asymmetry is removed — **both** spellings resolve with no supertypes. The
sized form still materialises §8.2's instantiation entry (that much is real: application-structural identity),
but the entry records empty `supertypes` rather than the template's, per #45's defect (a): a size template's
chain begins at the constructor it refines, and a constructor is not a type anything can be a subtype of. So
a bound is a constraint, not a change of place in the hierarchy, and `[text]`, `vector<text, 3>` and
`[text; 1..2]` agree. Pinned by
`DefinitionResolverTest.aSizeLessDeclarationLevelArrayIsAConstructionWithNoSupertypes`,
`GenericApplicationHeadTest.everySpellingOfAnArrayFamilyDeclarationRecordsNoSupertypes`, and
`GenericApplicationHeadTest.sizedSugarMaterializesTheInstantiationEntrySpecifiedByEightTwo` (which asserts
§8.2's entry shape, `supertypes` apart).

The suggested resolution below is unchanged in substance, but this implementation has answered its question
the other way: a construction does *not* establish IS-A with the constructor it applies (§8.1's own rule),
and it is §5.3's "IS-A `array` and substitutable where arrays are expected" claim that needs deleting.

**Suggested resolution:** Decide whether a construction establishes IS-A with the constructor it applies.
Saying it does would make both spellings agree and cost little — the constructor is known at the point of
construction, so its own supertype chain is available — and it would let §5.4 treat every closure of a
constructor uniformly. If it genuinely should not (the reading that construction is *fresh*, §4.1), then
§5.3's "IS-A `array` and substitutable where arrays are expected" claim needs qualifying, because it holds
only for the sugar-declared sizes and not for the plain `[T]` sitting beside them.

---

## 34. §9.4 cites UTS #39 but names the one mechanism that cannot be applied to a document in isolation, and leaves the rest unmentioned

**Section:** [TSON-DATA] §9.4 (Confusable Characters), §7.1 (UAX #31 profile), §2.5 (field-name identity),
§7.2.1 (NFC normalization); [TSON-SCHEMA] §2.2.3 (`!!import` name disjointness).

**Problem:** §9.4 is one sentence of advice:

> Implementations processing untrusted TSON input SHOULD consider Unicode confusable detection (UTS #39)
> when field name identity is security-relevant.

Two things make that hard to act on.

**1. UTS #39 is several mechanisms, and §9.4 points at the one that needs context TSON has not defined.**
"Confusable detection" is the `skeleton()` mapping (UTS #39 §4, `confusables.txt`): two strings are
confusable iff their skeletons are equal. That is a *relation between strings*, so it answers nothing about a
single identifier — it requires a defined comparison set, and §9.4 names none. The mechanisms that *are*
decidable on one token, with no set and no context, go unmentioned:

- **Identifier_Status** (`IdentifierStatus.txt`, the General Security Profile, UTS #39 §3.1) — a per-character
  Allowed/Restricted partition. Composes directly with a UAX #31 profile and needs nothing but the token.
- **Restriction levels** (UTS #39 §5.2: ASCII-Only, Single-Script, Highly Restrictive, …) and **mixed-script
  detection** — properties of one identifier, computed from script extensions.

This matters because UAX #31 itself directs implementers to pair an identifier profile with UTS #39, and §7.1
already *declares* a UAX #31 profile, in a table, with a documented exclusion (ZWNJ/ZWJ) justified by exactly
this threat. That table is where an Identifier_Status requirement would go, and it is the one place a
conforming implementation could act without the application telling it anything.

**2. The comparison set §9.4 lacks is one TSON can actually name.** A general-purpose language cannot bound
"which identifiers might be confused with which"; a TSON document can, several times over:

- field names within one record — §2.5 already defines their identity and a duplicate rule
- keys within one map — §2.6, which already asks implementations to *warn* on textually identical keys
- `enum` members, and the variants of a choice ([TSON-SCHEMA] §5.4)
- the declared type names of one schema, and — sharpest — the merged set at an `!!import`, since
  [TSON-SCHEMA] §2.2.3 requires imported names be "disjoint from each other and from local entries".
  Disjointness there is exact equality, which a confusable pair passes by construction: two entries a
  reviewer reads as one name are, to the resolver, simply two names.

Each of those is a small, closed set at a well-defined point in processing. §9.4 could name them instead of
deferring to "when field name identity is security-relevant" — a judgement an implementation is not in a
position to make, since only the application knows.

**3. The strictness is inverted relative to the risk.** §7.2.1 makes NFC normalization a MUST, so two
canonically equivalent names *are the same name*. Confusability gets SHOULD-consider, so two visually
identical names *are different names*. The format takes a firm, testable position on the case that is a
convenience issue and none on the case that is the attack.

**4. The prescribed workaround reopens the surface it closes.** §7.1 excludes ZWNJ and ZWJ from the profile
and gives the reason: "They are invisible, which makes them confusable and spoofing surface (§9.4); names
whose orthography requires them MUST be quoted." But the §7.1 profile constrains *unquoted* tokens only, and
§2.5 makes a quoted name an ordinary field name. So the sanctioned route for a name needing ZWNJ is also an
unconstrained route for every character the profile excludes — the hardening is bypassable by the mechanism
the same sentence prescribes. Whatever §9.4 eventually requires needs to say whether it applies to quoted
names, and if it does not, §7.1 should not describe quoting as the remedy.

**5. Nothing here is conformance-visible.** As with #29, a SHOULD-consider in Security Considerations makes
no implementation measurably better than one that ignores it, and there is no vector a test suite could
carry.

**Interpretation chosen:** NFC normalization of unquoted tokens (§7.2.1) is implemented; no part of UTS #39
is. Nothing in this implementation detects a confusable pair, a mixed-script identifier, or a Restricted
character, at either the data or schema layer — including at `!!import` merge, where the disjointness check
is exact string equality.

Worth recording *why*, because it bears on how a requirement here should be worded: this implementation
already approximates UAX #31, using the JDK's `Character.isUnicodeIdentifierStart`/`Part` in place of
XID_Start/XID_Continue, because the JDK exposes no UAX #31 properties and building the tables was out of
scope. The JDK likewise exposes no UTS #39 data at all — no `confusables.txt`, no Identifier_Status. So any
normative UTS #39 requirement obliges every implementation to ship UCD data, which is a materially larger ask
than the rest of the Unicode surface in this spec and should be a deliberate decision rather than a
side effect of tightening §9.4.

**Suggested resolution:** Split §9.4 by what an implementation can decide alone and what it cannot.

1. **Adopt the General Security Profile in §7.1**, where the UAX #31 profile is already declared: require or
   recommend that unquoted-token characters be Identifier_Status=Allowed. This subsumes the ad-hoc ZWNJ/ZWJ
   exclusion, which is currently a hand-picked instance of a rule UTS #39 states generally.
2. **State the comparison scopes** for skeleton-based detection — the record, the map, the enum, the choice,
   the schema namespace, the import merge — rather than leaving the set to the implementation. Detection
   within a closed set is a mechanical check; "is identity security-relevant here" is not.
3. **Say what a processor does on detection**: reject, warn, or emit a diagnostic. §2.6 already chose "SHOULD
   warn" for textually identical map keys; confusable names deserve at least the same treatment, and the two
   should not disagree.
4. **Decide whether a restriction level applies**, and if none does, say so — silence reads as an oversight
   rather than a decision.
5. **Address quoted names explicitly**, per point 4 above.
6. **Consider making the schema layer normative and the data layer advisory.** [TSON-SCHEMA] §2.2.3's
   disjointness check is a resolver-time operation over a closed, already-materialised set of names — the
   cheapest place in the whole series to make confusability an error rather than advice, and the place where
   a spoofed name does the most damage, since it changes which type a document is validated against.

---

## 35. §6 says metadata about a definition "must follow `=>`", but every `@doc` in the spec's own schemas precedes the name — and the resolved fixtures preserve it there

**Section:** [TSON-SCHEMA] §6 (Annotations as Types), §8.1 (Output Records), §10.1 (ingest);
[TSON-DATA] §3.1.

**Problem:** §6 draws a line through the declaration and tells authors which side metadata belongs on:

> In schema declarations, an annotation immediately preceding the declared name binds to the name (the
> `type_name` token at the declaration's name position), not the `type_definition` value; the resolver does
> not hoist annotations from key to value. **Metadata about the definition must follow `=>`**:
> `name => @doc:"..." {...}`.

The spec's own bundled schemas do the opposite, without exception:

| document | `@doc` before the name | `@doc` after `=>` |
|---|---|---|
| `meta-kernel.tn` | 23 | 0 |
| `meta.tn` | 32 | 0 |
| `core.tn` | 49 | 0 |

Not one of the 104 is written where §6 says definition metadata must go. The universal form is

```
@doc:"Two-value boolean enumeration."
boolean => !enum [true false]
```

and by §6's own rule that annotation is about the *name token* `boolean`, not about the type — which is not
what any author writing it means, and not what a documentation generator consuming it would want.

**And the resolved fixtures preserve it, on the key.** `meta-kernel-resolved.tn` carries 24 `@doc`
annotations, `meta-resolved.tn` 32, `core-resolved.tn` 49 — the same counts as their sources. They appear
exactly where they were authored, ahead of the map key, alongside definition-bound ones on the value:

```
  @doc:"Annotation type markers."
  annotation => @annotation !type_definition {
    kind: REFERENCE
    source: void
    body: !reference { target: void }
  }
```

So name-position annotations are not a discouraged spelling the resolver may discard — they are load-bearing
output that the reference fixtures round-trip. The `@annotation` marker on the value is the *only* kind §6's
rule actually describes, and it is a marker, not metadata.

**The normative text does not cover what the fixtures show.** §8.1 enumerates `type_definition`'s fields and
has no annotations field, which is correct and sufficient for definition-bound annotations: [TSON-DATA] §3.1
attachments ride on the value, as `!record_field { name: owner  type: @alias:id uuid }` demonstrates. But a
*name*-bound annotation's only representable home is a wire annotation on the schema map's **key**, and §8.1
never mentions key annotations, nor does §10.1's ingest paragraph — which is otherwise scrupulous, saying
precisely what happens to `subtypes` (discard, recompute), `disjoint` (discard, recompute) and `supertypes`
(take as input, verify). A consumer implementing §8.1 from the prose alone would not know key annotations
exist; a consumer implementing it from the fixtures would.

**A related gap in the same area: `@alias` is unclassified.** §6 has the resolver *attach* it — "when a
reference is flattened (§8.3), the resolver attaches `@alias:name` to the resolved type" — so it is derived
output, not authored input, with exactly the character §8.1 is careful to assign to `subtypes` ("a cache:
fully derivable, always recomputable, never trusted") and `disjoint` ("like `subtypes` it is a cache"). Yet
the ingest rules never mention it. On ingest an `@alias` in the document is either taken as truth, though
nothing verifies it against the entry's actual `source`, or discarded and recomputed like its peers. The spec
says neither.

**Interpretation chosen:** Both sides are kept, and neither is hoisted -- §6's "the resolver does not hoist
annotations from key to value" is honoured by keeping two separate homes rather than by discarding one.

Definition-bound annotations (after `=>`) are carried on the entry's `TypeDefinition`, and re-serialize as
wire annotations ahead of the value, matching the fixtures. Name-bound annotations (before the name) are
carried on the schema map's **key**, which is where the fixtures put them and the only place §8.1's model has
for them. Each value is bound through the type §6 says its name refers to, so `@doc:"..."` arrives as a
`String`. All 104 of the bundled documentation strings survive resolution and linking; `core.tn` documents
every one of its 48 declarations and every one is reachable.

The key's annotations are reached through an `AnnotatedMap<String, TypeDefinition>`, which presents the plain
`String`-keyed `Map` interface and exposes `getAnnotations(name)` beside it. Making the key type itself
`Map<Annotated<String>, TypeDefinition>` would model §3.1 more literally but is unusable: `Map.get` takes
`Object`, so every existing `get(plainKey)` would keep compiling and start returning `null`.

**This implementation therefore treats the "must follow `=>`" sentence as the thing that is wrong**, per
reading 2 below -- name-position documentation is preserved and usable, as the fixtures show it should be.
Nothing depends on that reading being correct: the two sets are stored separately, so if the spec settles the
other way the definition-bound set is already right and the name-bound set simply stops being populated.

**Suggested resolution:** Decide which the spec means, and make the artifacts agree.

1. If documentation genuinely belongs *after* `=>`, the three bundled schemas need 104 edits and the
   resolved fixtures follow — and it is worth saying why, because the name position is where every other
   language puts a doc comment and authors will keep reaching for it.
2. If the name position is right — the reading the fixtures support — then §6's "must follow `=>`" should go.
   What replaces it is a statement that an annotation on the name is metadata about the declaration, which is
   usually what the author meant, together with §8.1 saying that the schema map's keys carry annotations in
   resolver output and §10.1 saying what ingest does with them.
3. Either way, classify `@alias` alongside `subtypes` and `disjoint`: derived, discarded on ingest, and
   recomputed by re-flattening.


## 36. §5.11 says what happens to a group reduced to one member, but not to one reduced to none

**Section:** [TSON-SCHEMA] §5.11 (Field Groups), §5.9 (Subtraction).

**Problem:** §5.11's last word on removal covers exactly one arity:

> A removal clause naming a member (§5.9) removes it from `fields` and from its group's `members`; a group
> reduced to one member is dissolved, and the surviving field takes the group's state (REQUIRED group →
> field REQUIRED, OPTIONAL group → field OPTIONAL).

A removal set may name several fields (§5.9's own `staff_public` names two), and nothing restricts them to
different groups. So `bounds - { min  exclusive_min }` over

```
bounds => { a: text  ( min: integer | exclusive_min: integer ) }
```

removes both members of the one group. The reduced-to-one rule doesn't apply — there is no survivor to take
the group's state — and no other rule does either. An implementation must still pick something, and the
choices are not equivalent: keeping a memberless REQUIRED group means "exactly one of {} must be present",
which no document can satisfy, so every instance of the type fails validation for a reason the author never
wrote down.

The gap widens slightly under §5.11's own "A group MUST contain at least two members." That is stated as an
authoring rule on the group's declaration; whether it is also an invariant on resolver *output* is what
decides this case. If it is, a zero- or one-member group is unrepresentable and both the dissolution rule and
this case are consequences of one invariant — which is worth saying, because it also answers the same
question for any future operation that can shrink a group.

**What this implementation does:** drops the group entirely, alongside its members. `DefinitionResolver`'s
`applyRemovals` rebuilds the group list, keeping a group with two or more survivors, dissolving one with
exactly one (per the stated rule), and emitting nothing for one with none. An empty group has no members to
choose between, so there is nothing left for it to constrain, and dropping it is the only reading that leaves
the type inhabitable. Pinned by `DefinitionResolverTest.removingEveryMemberDropsTheGroupItself`.

**Suggested resolution:** State the two-member minimum as an invariant on resolved output, not only on the
declaration, and make §5.11's removal sentence cover the whole arity ladder — a group with two or more
members left survives; one left is dissolved into a plain field carrying the group's state; none left is
removed with its members. One extra clause, and it closes the case rather than leaving each implementation
to reason from inhabitability.

## 37. Composition grants IS-A per parent, but subtraction revokes it for all of them — including parents that contributed nothing to the removal

**Section:** [TSON-SCHEMA] §5.9 (Subtraction), §4.3 (Operations), §5.8 (Composition), §7.2 (Subsumption),
§8.1 (Output Records).

**Problem:** §4.3 states the asymmetry in a single sentence, without remarking on it:

> composition grants IS-A **per parent**, and subtraction revokes **it** while keeping lineage.

Per parent, then — *it*. Which parent's IS-A does subtraction revoke? §5.9 answers "all of them", flatly:

> `type_definition.supertypes` (the IS-A lattice) is empty — the subtracted type is not source-compatible

with the operations table agreeing ("broken (lineage kept)") and rule 3 assuming the total break as its
premise rather than deriving it ("since IS-A is already broken, there is no contract to violate").

So in

```
account => { name: text  email: text  password: text }
user    => { badge_id: text }
staff_public => account & user - { password }
```

`staff_public` loses IS-A with `user`, whose fields it retains in full. Nothing about `user`'s contract is
violated: every field `user` declares is present, unchanged, in `staff_public`. A value of `staff_public` is
a perfectly good `user`, and §7.2's subsumption rule will nonetheless refuse it at a `user`-typed position.
The information is not merely unstated — it is stated wrongly, because `supertypes` is the index §7.2 reads.

**The finer rule is well-defined and cheap.** Keep an ancestor `A` in the transitive chain iff none of the
removed names appears in `A`'s own field set. §5.8 already forbids two supertypes contributing the same field
name, so each field has exactly one contributing parent; an ancestor declaring a removed field drops, and so
does every descendant of it in the chain, which falls out of the same test since a descendant's field set
contains the inherited name. It needs each ancestor's resolved definition — which the resolver necessarily
already holds, having just copied their fields.

**Two reasons the blunt rule may be deliberate**, both real, and neither stated as the reason:

1. **Head-level legibility.** §5.9 sells the clause's position: "a reader of the declaration line knows the
   contract is broken without scanning the body." Under a per-ancestor rule the reader knows only that *some*
   contract broke; which ones survive needs every parent's field set. That is a genuine loss, and it is the
   strongest argument for the flat rule.
2. **Ingest must not diff.** §5.9's own closing sentence: "For ingest (§8.1), broken-IS-A-with-lineage is
   declared by the source syntax rather than inferred by diffing field sets against the parents." A
   per-ancestor rule is exactly that diff. But note this argues about how an *ingesting* consumer learns the
   answer, not about what the answer is: a resolver computing `supertypes` from source already diffs nothing
   it does not have, and §8.1 treats `subtypes` and `disjoint` as caches to recompute while taking
   `supertypes` as input to verify — under a per-ancestor rule, verification is the same recomputation.

**The workaround is good, which is why this is a question and not a defect.** Subtract first, compose second:

```
account_public => account - { password }
staff_public   => account_public & user
```

`account_public`'s chain is empty, so `staff_public` resolves to `[account_public, user, …]` — IS-A `user`,
IS-A `account_public`, not IS-A `account`. Exactly the lattice the one-liner should arguably have produced,
and it arrives without any provenance reasoning. An author who wants partial IS-A can always write it.

**What this implementation does:** empties `type_definition.supertypes` whenever a removal clause is present,
per the letter of §5.9, and keeps the head's list in `record.supertypes` as lineage. `DefinitionResolver`'s
`resolveComposition`; pinned by `DefinitionResolverTest.subtractionRemovesTheFieldAndBreaksIsAWhileKeepingLineage`.

**Suggested resolution:** Say which is meant, in §5.9's Resolution paragraph, and say why — a reader who
notices §4.3's "per parent" will ask.

1. If the flat rule is intended, replace rule 3's circular "since IS-A is already broken" with the actual
   justification: the clause is head-level, so its effect must be readable without consulting the parents'
   field sets. One clause, and the design stops looking like an oversight.
2. If partial retention is wanted, state the ancestor test (an ancestor survives iff it declares none of the
   removed fields), and reconcile it with the ingest sentence — most simply by saying that ingest verifies the
   declared `supertypes` against that test rather than inferring them.

Either way §4.3's "revokes it" deserves the same "per parent" precision the clause before it uses.

## 38. `construction-def` draws its operands from `type-ref`, which admits forms that can never denote a record — where `refined-def` takes a name

**Section:** [TSON-SCHEMA] §12.1 (Grammar), §5.8 (Supertype Composition), §5.7 (Refinement), §12.2
(Disambiguation Summary).

**Problem:** the two operators disagree about what may sit on their left, and the difference is in the
productions, not the prose. §5.7's source is a name:

```
refined-def  = type-name [ws "<" type-args ">"] ws "^" ws record-def
```

§5.8's operands are full type-refs:

```
construction-def = type-ref 1*(ws "&" ws type-ref) [ws record-def] [ws removal-set]
                 / type-ref ws "&" ws record-def [ws removal-set]
                 / type-ref ws removal-set

type-ref = paren-type / inline-array / type-name "<" type-args ">" / type-name
```

So a **choice** and an **inline array or tuple** are grammatical supertypes. Neither can denote a record: a
choice has variants, a bracketed form has elements, and composition's entire job is to merge *field sets*.
There is no reading under which `a & (b | c)` or `a & [b]` means anything.

`refined-def` gets this right by construction — its source production simply cannot spell those forms. §5.8
inherits them by reaching for `type-ref` where it wanted "a named type, possibly applied".

**The grammar and §12.2 already contradict each other about it.** §12.2 dispatches the type-def position by
first token:

> ```
> ;   name &        → construction-def (composition, §5.8)
> ;   (             → paren-type (choice)
> ;   [             → container-def (array or tuple, full syntax)
> ```

`(` and `[` are routed to paren-type and container-def outright, so a `construction-def` whose *first*
operand is one of those is unreachable — while the production says it is fine, and the same forms remain
reachable at every operand after the first. An implementation following the productions and one following
the summary produce different parsers, and this one splits the difference exactly where the spec does:
`(a | b) & { … }` is a parse error, `a & (b | c)` parses and must be caught during resolution. That
asymmetry is not a design; it is two parts of §12.1 disagreeing.

**Restricting to a bare name would be too strong**, which is presumably how `type-ref` got there. §5.8's own
"Parameterized references" paragraph requires the applied form:

> composing with a parameterized supertype works the same way: `vip => <T> customer & box<T> & { … }`

`box<T>` must stay legal — provided the result is a record. `refined-def`'s source shape, `type-name`
optionally followed by `<type-args>`, is exactly the production that admits `box<T>` and excludes the other
two. The two operators should be spelling their operand the same way, because they want the same thing.

**And the grammar alone does not finish the job.** Even restricted to names, an operand may name a real type
whose body is not a record:

```
weird       => integer & { extra: text }             ; integer => !integer_type {}
also_weird  => customer & array_min<text, 2>         ; instantiates to an array binding record
```

Both are grammatical under any of these productions and both must be rejected. §5.7 already has the rule and
the vocabulary for it —

> **Refinement requires a vocabulary body.** The source of `^` — after flattening references (§8.3) — MUST be
> a definition whose body is a `!record` […] A definition whose body is a binding record — a top-level
> constructor application (§5.6), a template instantiation (§8.2), or an alias resolving to either — is
> **finished**

— and §5.8 has no equivalent, though composition needs it for the same reason: it copies the parent's fields,
and a binding record has none to copy. The gap is easy to miss because every composition in the three bundled
schemas has a record-bodied parent (`~atom & { … }`, `~text_type & atom_specification & { … }`), so an
implementation built against the fixtures never meets the case.

**What this implementation does:** rejects a choice or bracketed form at a supertype position outright ("`&`
composes record types, and this form has variants/elements, not fields"), and rejects a named operand whose
resolved body is a binding record with §5.7's own reasoning. A parameterized supertype is accepted in
principle but not yet implemented — §5.10 substitution has to carry the arguments into the absorbed fields
first — so it raises the coverage-gap exception rather than the author-error one.
`DefinitionResolver.resolveComposition`; pinned by
`DefinitionResolverTest.rejectsAChoiceOrABracketedFormAsASupertype` and
`rejectsComposingWithASupertypeWhoseBodyIsABindingRecord`.

**Suggested resolution:** two changes, one per layer.

1. **Grammar.** Give `construction-def` the operand shape `refined-def` already uses:
   ```
   supertype-ref    = type-name [ws "<" type-args ">"]
   construction-def = supertype-ref 1*(ws "&" ws supertype-ref) [ws record-def] [ws removal-set]
                    / …
   ```
   This removes `paren-type` and `inline-array` from every operand position rather than only the first,
   which retires §12.2's disagreement instead of codifying it, and keeps `box<T>`.
2. **Semantics.** Make §5.7's vocabulary-body requirement cover both operators. It is really a statement
   about what construction and refinement *consume*, so §4.3 — where the two families are introduced — is
   its natural home, with §5.7 and §5.8 referring to it. Composition then admits `box<T>` exactly when the
   applied result is a record, and rejects `integer`, `array_min<text, 2>`, and `lookup => map<text, integer>`
   for the one stated reason.

While that paragraph is open, it is worth saying whether the omission of **choice** bodies from its list of
admissible sources ("a fresh or refined record, a composition, a constructor, or an open template") is
deliberate. If it is — a choice has variants, not fields, so there is nothing to tighten — saying so settles
the same question for `&`, and makes the grammar restriction above obviously right rather than merely
convenient.

## 39. §5.2 never says whether OPTIONAL_FIXED is injected — which is the entire difference between it and REQUIRED_FIXED

**Section:** [TSON-SCHEMA] §5.2 (Field States), §5.7 (Refinement), §5.11 (Field Groups), §7.6.

**Problem:** `REQUIRED_FIXED` and `OPTIONAL_FIXED` (valued) differ in exactly one observable way, and §5.2
specifies it for one of them only. The Default injection paragraph enumerates:

> When a field has `state: REQUIRED_DEFAULT` (or `REQUIRED_FIXED`) and the data does not provide a value, the
> decoder injects the default (or fixed) value into the output: decoded values are fully populated, and
> consumers do not consult the schema to retrieve defaults.

and the data paragraph likewise:

> In data, a REQUIRED_FIXED field may be provided with a value matching the fixed value, or omitted (the fixed
> value is used). A contradicting value is a validation error.

`OPTIONAL_FIXED` appears in neither. Its whole read-time specification is one table cell — "If present, must
be this value" — which says what is *valid* and not what the decoder *produces* when the field is omitted.
Two readings are available and neither is contradicted:

- **Not injected.** Omission leaves the field absent. The field's *presence* is the information; the value is
  pinned. This is the reading that makes the state worth having.
- **Injected**, by extending the enumeration to the other FIXED state, on the grounds that a fixed value is
  knowable from the schema in both cases and "decoded values are fully populated" is stated as a general
  principle.

Under the second reading `OPTIONAL_FIXED` and `REQUIRED_FIXED` become behaviourally identical, and the `?`
that distinguishes them decides nothing. Two conformant implementations return different decoded documents
for the same bytes and the same schema. **This implementation took the second reading and did not notice for
some time** — the enumeration did not include the state, and extending it looked as reasonable as not.

**The confusion is not incidental to the wording; it is what the wording invites.** "If present, must be this
value" reads as a mechanical variant of REQUIRED_FIXED's "may be provided with a value matching the fixed
value, or omitted", so an implementer naturally asks which of two similar states applies rather than what
each one is *for*. Under the first reading they are not variants at all: REQUIRED_FIXED pins a value the
document need not carry, while OPTIONAL_FIXED makes the field's *appearance* meaningful and its value inert.

**Why the state must be kept even so.** The obvious simplification — drop `OPTIONAL_FIXED` and let any `=`
assignment land in `REQUIRED_FIXED`, from OPTIONAL and REQUIRED alike — fails on two counts:

1. **`= _` has nowhere else to live.** §5.7 calls it "the IS-A-preserving counterpart of removal (§5.9)", and
   §5.11 relies on it for groups: "an inherited member is OPTIONAL, so it may be tightened to any state the
   transition table permits, including `= _` (forbidding that alternative's value)". A `REQUIRED_FIXED`
   fixed-to-absent is self-contradictory — §5.2 already makes `= _` on a REQUIRED field an error, correctly.
   Without `OPTIONAL_FIXED` the only way to say "this subtype does not use that alternative" is subtraction,
   which breaks IS-A and so cannot express it.
2. **In a group, presence is the payload.** Given `( min: integer | exclusive_min: integer )`, a refinement
   writing `min: = 0` means "either `min`, which is always 0, or `exclusive_min`, which is free" — a tag.
   Upgrade that to REQUIRED_FIXED and `min` is injected into every document, so the group's own count sees two
   members whenever `exclusive_min` is used, and the alternative becomes unreachable. A refinement meant to
   narrow the choice would silently collapse it.

Outside a group the simplification is more tempting — a plain `field: type? = value` can only ever be absent
or the single value the schema already names, so it carries no information in either state — but that is an
argument about one spelling's usefulness, not grounds for removing the state its sibling form depends on.

**A third gap in the same area, affecting REQUIRED_FIXED directly.** "A contradicting value is a validation
error" states the rule but no section says what the decoder yields for a *conforming* document that writes
the fixed value explicitly, versus omitting it. That is harmless while the values agree, but it leaves the
verification step implicit, and an implementation that seeds fixed fields up front and never re-checks the
written token will silently replace a contradicting value instead of rejecting it — producing a decoded
document that differs from the bytes with no diagnostic. (That is exactly the bug this implementation had.)

**Suggested resolution:**

1. **Say whether each state is injected, for all five, in one place.** The Default injection paragraph should
   name `OPTIONAL_FIXED` explicitly and say it is *not* injected: an omitted OPTIONAL_FIXED field is absent
   in the decoded output. One sentence, and the state stops being a near-duplicate.
2. **Say what OPTIONAL_FIXED is for**, next to the table row: the state where the field's *presence* carries
   the information and its value is pinned — which is why it is useful on a group member and nearly
   contentless on a plain field.
3. **Make the fixed-value check explicit**: a document that writes a FIXED field MUST have its token verified
   against the fixed value, and a mismatch is a validation error — not a value the decoder overwrites.
4. **Retire the "two independent axes" framing** in §5.2's opening. Six combinations, five states, one state
   with two forms, and three carve-out errors (`~ _` anywhere, `= _` on REQUIRED, `type? ~ value`): presence
   and mutability visibly are not independent, and presenting them as such is what makes `type? = value` look
   like a mechanical variant of `type = value` rather than a different intent.

## 40. Closure is stated for data records only, so an unknown member in a *schema's* constructor body has no stated verdict — and the silent reading is the dangerous one

**Section:** [TSON-SCHEMA] §7.2 (Type Annotation Resolution), §5.5 (Type Constructors and Application),
§5.7 (Refinement).

**Problem:** §7.2 states record closure clearly, and states it for data:

> **Records are closed under their type.** When a schema is in scope and a record's type is known, the record
> MUST contain only fields defined by its type; fields not present in the type definition are validation
> errors. This applies to directly-typed records (`!person { ... }`) and structurally-positioned records (a
> record at a record-typed field position). Schemaless records have no closure rule.

A constructor application or atom refinement in a *schema* document writes a record too — `!integer ^ { min:
0  max: 255 }` — and §5.5/§5.7 describe binding it against the constructor's vocabulary. Neither section says
what happens to a member the vocabulary does not declare. The rule that answers it is two chapters away, in a
section about data values, and reaches this case only through an inference the reader has to make
unprompted: that a constructor body *is* a record whose type is known, so §7.2 governs it.

§7.2 does supply the premise, in a sentence about validating data:

> A **constructor** is a record-shaped type, so it validates a record against its constraint-field
> vocabulary: `!integer_type { min: 0  max: 255 }` is a record conforming to `integer_type`'s fields,
> receiving ordinary record validation.

But that is stated where a reader is thinking about `!integer_type` appearing in a *data* document (the
paragraph's subject is resolved-form output), not about `!integer ^ { … }` in the schema they are writing.
Nothing at the schema-authoring sections points here.

**Why the omission is worse than a normal gap.** The two readings are not symmetric. Reject-unknown fails
loudly and is trivially correctable. Ignore-unknown *reports success* while silently discarding the
constraint the author wrote — the schema compiles, validates, and enforces nothing at that facet. An
implementation that binds a body field-by-field against the vocabulary lands on ignore-unknown by
construction, because unmatched members simply never map to anything; nothing has to go wrong for the wrong
behaviour to appear. **This implementation shipped exactly that**, and it was invisible until someone wrote a
schema with the wrong facet names on purpose.

**The wrong facet names are not a contrived case.** The vocabulary an author is most likely to reach for by
mistake is JSON Schema's — `minimum`/`maximum` for `min`/`max`, `maxLength` for `max_length`, `required`,
`pattern` at the wrong level. Every one of those spellings is plausible, and under ignore-unknown every one
is a silent no-op:

```
quantity_t => !integer ^ { minimum: 1  maximum: 100 }     # compiles clean; constrains nothing
```

This matters more as TSON schemas are authored by language models, which reach for JSON Schema vocabulary
constantly. A validator that accepts a schema it does not enforce is worse than one that rejects it, because
the author stops looking.

**Interpretation chosen:** unknown member is a resolver error, on the strength of §7.2's closure rule read
together with its "ordinary record validation" sentence. This implementation enforces closure at one place —
the compiled record reader — which covers data records and constructor bodies alike, since a body is bound by
replaying it through the governing meta's own compiled reader. The diagnostic names the member and lists the
constructor's real vocabulary, which is the part that makes the error correctable in one attempt.

**Suggested resolution:**

1. **State closure where bodies are written.** §5.5 and §5.7 should say that a construction or refinement
   body is validated against the constructor's vocabulary as an ordinary closed record, and that a member the
   vocabulary does not declare is a resolver error — or, at minimum, cross-reference §7.2 from both.
2. **Move or duplicate the "constructor is a record-shaped type" sentence** so it appears in the schema
   chapters, not only in the data chapter where its example is a resolved-form document.
3. **Say it is an error, explicitly, rather than leaving it to the closure rule.** The failure mode here is
   an implementation that never asks the question, so the rule needs to be stated where an implementer of
   body binding is looking, in the imperative.

---

## 41. Duplicate field names and map keys are SHOULD-level, so the ambiguity lives in the format rather than in one document

**Section:** §2.5 ("Records"), §2.6 ("Maps"). Related: §7.4 (the token stream is single-pass),
[TSON-SCHEMA] §7.2 (record closure), and this file's #21.

**Problem:** Both duplicate rules are stated softly, and each then defines what a processor does with the
ambiguity rather than removing it:

> Field names within a record SHOULD be unique. If duplicate field names are present, the last value wins.
> (§2.5)

> Duplicate keys SHOULD NOT be present. If duplicate keys are present, the last value wins. […] The parser
> SHOULD warn when textually identical keys are detected. (§2.6)

This is JSON's compromise, inherited whole. RFC 8259 says the same thing for the same reason, and JEP 540
("Simple JSON API", incubating in JDK 28) sets out why that reason does not transfer to a format being
designed now:

> When the issue of duplicate names was discussed on the ECMAScript Discussion List in 2013, the concern
> about prohibiting duplicate names was that doing so would invalidate existing documents. Thus, the
> "should be unique" wording (instead of "must") was retained, and it has been carried over to current
> specifications.

The SHOULD in RFC 8259 buys backward compatibility with a corpus that already existed in 2013. **TSON has
no such corpus.** The series is an unreleased working revision with no compatibility guarantees between
revisions and no frozen version 1 (#20), so it is paying the interoperability cost of that compromise while
collecting none of the benefit it was made for — and the window in which fixing it is free closes at
version 1.

What the cost is, in RFC 8259's own words, quoted by the JEP:

> An object whose names are all unique is interoperable in the sense that all software implementations
> receiving that object will agree on the name-value mappings. When the names within an object are not
> unique, the behavior of software that receives such an object is unpredictable.

and what makes it more than a theoretical concern:

> The unpredictability arises when the object is processed by a system consisting of multiple,
> independently-developed JSON libraries. This can lead to hard-to-diagnose errors, security
> vulnerabilities, decreased interoperability, and general lack of robustness.
> (JEP 540, citing RFC 9413, "Maintaining Robust Protocols")

JEP 540's own resolution is unconditional rejection — "This policy, permitted by the RFC, provides maximum
interoperability and predictability, and reduces concerns about processing malformed or ambiguous JSON
documents" — reached by a group with every incentive to stay compatible with an installed base, choosing
strictness anyway.

**Four things make the case stronger for TSON than for JSON.**

1. **The spec already pays for detection and then declines to use it.** §2.6 requires a parser to detect
   textually identical keys ("The parser SHOULD warn when textually identical keys are detected"), including
   the structural comparison compound keys need. The work is mandated; only the verdict is soft. Turning a
   warning into a rejection costs an implementation nothing it is not already doing.

2. **"Last value wins" is unimplementable as a *warning* by a processor with one severity.** Warning
   requires a diagnostic model with a severity axis, and a processor that has only "error" must choose
   between staying silent about a construct the spec itself calls suspect and failing a document the spec
   calls conforming. This implementation is in exactly that position and stays silent (below). MUST NOT
   dissolves the problem instead of pushing it into every implementation's diagnostic model.

3. **It contradicts the schema layer's own posture.** [TSON-SCHEMA] §7.2 closes a record under its type: a
   field name the type does not declare MUST be a validation error, precisely so a stray name cannot pass
   unnoticed. A *repeated* name is the same class of authoring mistake with a stronger proof — no schema is
   needed to know that a record stating `x` twice states one of them for nothing — yet closure rejects the
   first and §2.5 accepts the second.

4. **A Class 1 and a Class 2 processor can decode one map to different values.** §2.6's textual rule makes
   `1` and `1.0` distinct keys, while "type-aware key equality requires declared type information" — so
   under an `integer`-keyed schema they are one key. With "last value wins", that difference is not a
   disagreement about validity but about *content*: two entries under Class 1, one entry under Class 2,
   silently, from the same bytes. Under MUST NOT the same document is accepted by one and rejected by the
   other, which is a disagreement a user can see and fix.

**Interpretation chosen:** the suggested resolution, implemented ahead of the spec — a repeated record
field name is a `DUPLICATE_FIELD` error and a duplicate map key a `DUPLICATE_MAP_KEY` one, at the repeat's
own position, under a schema (`RecordAbstractReader.readFields`, `MapAbstractReader.readInto`) and
schemalessly (`SchemalessTreeReader`, `SchemalessObjectReader`) alike. This entry originally recorded the
opposite — implemented as written, detected nowhere, because a warning had no severity to be emitted at —
and #42 settled the general question in favour of the error, which is what let the verdict land.

The last-value-wins *recovery* still runs underneath, because a single forward pass cannot know a name
recurs without buffering; what changed is that the document is now invalid, not what it decodes to. Point 4
above is therefore realised: the second occurrence is the error, reported at its own position, and #21 is
dissolved rather than documented.

Point 3 — what a schema-governed processor does with a type-aware duplicate — is answered as a Class 2
error, but the layering it assumes turned out not to survive contact with the implementation. See **#43**:
a Class 1 *reader* sits between §2.6's textual identity and §7.7's typed identity, and the series names no
equality for it.

**Suggested resolution:** make both rules MUST NOT, and delete the resolution rules that exist only to
disambiguate what would then be malformed.

1. **§2.5:** "Field names within a record MUST be unique. A record containing the same field name more than
   once is malformed and MUST be rejected." Delete "the last value wins".
2. **§2.6:** the same for textually identical keys, replacing both "last value wins" and "SHOULD warn" —
   the detection §2.6 already requires becomes the rejection.
3. **Say what a schema-governed processor does with a type-aware duplicate** (`1` and `1.0` at an
   `integer`-keyed map): recommended as a Class 2 validation error, so the two conformance classes differ
   in *when* a document is rejected rather than in what it decodes to.
4. **This dissolves #21.** The shadowed-occurrence question — whether a processor must validate an
   occurrence a later one overwrites — exists only because there is something to shadow. With uniqueness
   required, the second occurrence *is* the error, reported at its own position, and the
   streaming-vs-buffered interoperability hazard that entry describes disappears rather than being
   documented.
5. **If full strictness is judged too strong at the data layer**, the fallback is to say so explicitly
   rather than through SHOULD: keep last-value-wins for Class 1 and make uniqueness a MUST for Class 2,
   where a schema is in scope and §7.2 already closes the record. That is a weaker outcome, but it is a
   decision the format makes once, instead of one every implementation makes silently.

---

## 42. Every warn-level rule in the series resolves to an error or a deletion — the spec never needs a warning severity

**Section:** Part 2 §5.2 ("Field States"), §5.3 ("Type Expressions"), §5.4 ("Choice Types"), §5.10
("Templates and Parameters"), §5.10.1 ("Self-Referential Types"), §7.1 ("The `!!schema` Directive"), §7.5
("Sets"), §7.6 ("The Absent Sentinel Under a Schema"), §7.7 ("Resolver Behaviours at Typed Positions");
Part 1 §2.5 ("Records"), §2.6 ("Maps"). Related: #7, #21, #25, #41.

**Problem:** The series asks for warnings in roughly ten places, and #41 already argues the two Part 1
cases (duplicate field names, duplicate map keys) should be MUST NOT. This entry generalizes #41's
argument: examined one by one, *every* warn-level rule in both parts is better as an error or as nothing,
so the severity axis the warnings collectively demand of every implementation's diagnostic model is
machinery the format does not need — and each individual WARN reads as a decision the spec declined to
make.

The general argument, sharpened by the format's own target consumer. A warning channel presumes a human
reader exercising judgment over a legitimate-but-suspect construct. TSON's stated near-term consumer is an
LLM in a generate-validate-retry loop, where there is no such reader: a validating processor has exactly
two behaviors — fail the document or don't — so every WARN forces each implementation or pipeline to
privately promote it to an error or silently drop it. That is #41's interoperability failure (RFC 9413,
"Maintaining Robust Protocols") reproduced per-diagnostic instead of per-duplicate-key: two conforming
processors, or one processor under two configurations, disagree about the same bytes in a way the spec
sanctioned. And a warning costs the retry loop either way: ignored, it hides a generation defect;
promoted, it was an error with extra steps. #41's point 2 — "last value wins" is unimplementable as a
*warning* by a processor with one severity — is this thesis stated for one rule.

The inventory, with the verdict each case argues for:

| Rule | Written verdict | Argued verdict |
|---|---|---|
| Duplicate record fields / map keys (P1 §2.5/§2.6) | SHOULD warn, last wins | Error (#41) |
| Type-aware duplicate map keys (§7.7) | MAY detect, SHOULD warn | Class 2 error (#41 pt 3) |
| `_` at a REQUIRED_DEFAULT field (§5.2, §7.6) | warn and inject the default | Error |
| Duplicate at a set-typed position (§7.5) | dedupe first-wins, SHOULD warn | Error |
| Vacuous `0..` array bound (§5.3) | SHOULD warn, desugar anyway | Error (reject the spelling) |
| Unused (phantom) parameter (§5.10) | blessed *and* warned | Error (or bless silently) |
| Parameter shadowing a schema type (§5.10) | SHOULD warn | Error |
| Non-productive required recursion (§5.10.1) | MUST NOT instantiate, SHOULD warn | Error at declaration |
| Unprovable `@disjoint` (§5.4) | resolver warning | Error, given a pinned prover baseline |
| Inline nesting depth (§5.2) | MAY warn | Delete (or a §9.1-style hard limit) |

Case by case:

1. **`_` at REQUIRED_DEFAULT is the strongest case.** §5.2: "the decoder SHOULD warn — `_` asserts absence
   at a position the schema always fills — and injects the default." Warn-and-inject substitutes a value
   the document explicitly disclaimed: the document said "absent," the decoder answers "here is a value."
   For an LLM emitter, `_` at a defaulted field means the model misread the schema, and injection papers
   over exactly the signal the retry loop exists to catch. It is also internally inconsistent: `_` is
   already a validation error at both REQUIRED and REQUIRED_FIXED, leaving REQUIRED_DEFAULT the lone
   warn-cell in §7.6's table. And the spec already takes the strict posture for the analogous FIXED case —
   a stated FIXED value is checked against the schema's, not obeyed. The emitter's fix is trivial (omit the
   field), the profile of a rule that should simply be MUST NOT.

2. **The set rule carries a bonus inconsistency.** §7.5: "duplicates are silently deduped — first
   occurrence wins. The resolver SHOULD warn when dedup occurs." Sets resolve duplicates *first*-wins while
   §2.6 resolves map keys *last*-wins — two different tie-break rules for the same mistake, each existing
   only to disambiguate what an error would reject outright. A stated duplicate at a set position states an
   element for nothing (the same no-schema-needed proof #41 makes for a repeated field), and erroring
   deletes both resolution rules along with both warnings.

3. **The phantom-parameter sentence is the purest indecision in the spec.** §5.10 blesses the construct and
   warns about it in one sentence: "An unused parameter is a type parameter (the phantom-parameter reading)
   and the resolver SHOULD warn." Either phantom parameters are a feature (then silence, documented) or a
   mistake (then an error) — a warning is the refusal to choose. For generated schemas the
   forgot-to-use/typo reading dominates, and nominal separation is available explicitly via distinct named
   declarations, so error is the better pick.

4. **Shadowing is the same shape with a sharper edge.** §5.10: "implementations SHOULD warn when a
   parameter shadows a schema type." `box => <text> { v: text }` silently captures a builtin — the §9.4
   confusable-hazard class the spec elsewhere takes seriously — and renaming a parameter is free, so an
   error costs no expressiveness. (§5.4's "A resolver SHOULD NOT warn merely because a choice's variants
   are not disjoint" is the one SHOULD-NOT-warn in the series, and it survives trivially: under this
   entry's position there is nothing to warn with.)

5. **Vacuous `0..` is worse than a style nit, which strengthens the error case.** Because identity is
   application-structural (§8.2), `[T; 0..]` produces an entry *distinct from* `[T]` that means the same
   thing — two semantically identical schemas with different entry graphs. Rejecting the spelling removes
   an identity trap, not just noise, and the author's fix is written in the spec's own sentence ("Authors
   wanting the unconstrained array write `[T]`").

6. **Non-productive recursion: "MUST NOT be instantiated" plus "SHOULD warn at declaration" means a
   declaration that can never be used loads successfully.** There is nothing the warning preserves. Make it
   an error, with the caveat #25 already demands: define "guarded" (optional field, possibly-empty
   array/set, bottoming-out variant), and decide whether a non-productive base a subtraction repairs
   (§5.9) deserves blessing — if it does, productivity is checked where the type is *used* at a data
   position rather than where declared.

7. **Unprovable `@disjoint` is the one place WARN carries real information** ("your assertion may hold but
   I cannot verify it") — and even here, error is defensible. §5.4's own text observes that an *unproved*
   assertion has no operational effect: discrimination "will fall back to the encoding's structural
   testing where the encoding permits, or require the tag where it does not," proved or not. So an
   unproved `@disjoint` is dead weight, and rejecting dead weight costs no expressiveness (delete the
   assertion, or restructure until it proves). It also makes `@disjoint` mean "machine-verified disjoint" —
   the only meaning a downstream encoding can actually rely on to drop tags. The genuine blocker is that
   prover strength is implementation-defined (the record-set and pattern MAYs), so error-on-unprovable
   would make one schema load under a strong prover and fail under a weak one. The fix belongs in the
   spec: pin the required decision procedure (the cheap exact rules at minimum) and make unprovable-under-
   that-procedure an error. If the spec will not pin the prover, this becomes the *only* WARN in the
   language — and a severity axis, a strictness flag, and a diagnostics-schema revision are a lot of
   machinery for one diagnostic; silence (the current pinned behavior of this implementation) would be the
   lesser cost.

8. **The MAY-warn on inline nesting depth (§5.2) should be deleted** or restated as a §9.1-style
   configurable hard limit, which errors when enabled and exceeded. As written it is a style lint with no
   addressee.

9. **One sleeper that is not spelled "warn": the unannotated root value.** §7.1: "Encoders SHOULD annotate
   the value a `!!schema` directive governs with the type it instantiates; an unannotated value under a
   bound schema is legal but vocabulary-only — validation engages only where annotations appear within
   it." For the structured-output use case this is the worst trap in the series: an LLM omits the root
   annotation and the validator answers "valid" while having checked nothing — vacuous validity,
   indistinguishable from real validity. The root annotation should be a MUST when `!!schema` is present,
   at least for a validating processor.

**What should stay SHOULD** — these are not severity questions, and several are actively good for the
retry-loop consumer: the diagnostic-*wording* SHOULDs ("SHOULD suggest `^` or `&`", "SHOULD note the
absorbed hyphen", "SHOULD suggest `= S`") — an actionable fix in the message is what turns a retry into a
one-shot repair; §8.1's "SHOULD continue processing after an error" — multi-error collection is *the*
retry-loop feature; encoder-side style guidance (write defaulted fields, quote binary, own-line
directives, `_`-vs-omission control), which changes no decoder verdict; and the §9/§11 security and
resource SHOULDs (pre-register schemas, pin hashes, confusables, bidi marks, fetch policy, DoS limits),
which are deployment policy — though where a limit is enabled, exceeding it is already a hard error.

**Interpretation chosen:** This implementation adopts the error-based outcomes throughout, deliberately
ahead of the spec's written warn-level verdicts, and will not grow a severity axis: `Diagnostic` stays
single-severity, a non-empty `validate` result still means invalid, and each warn case lands as an
ordinary error as its owning feature is built (`BACKLOG.md`'s "Diagnostics" section tracks the work).
Current state per rule: duplicate fields and duplicate map keys are errors at both reader loops (with
last-value-wins recovery underneath); a stated `_` at a REQUIRED_DEFAULT field is a validation error where
plain omission still injects; `@disjoint` on a non-disjoint choice is a linker error, and #47's total
two-valued derivation means "unprovable" no longer exists — the pinned decision procedure case 7 asks the
spec for is `ChoiceDisjointness`'s discrimination-class rule (pinned by
`rejectsADisjointAssertionOnSameClassVariants`); the vacuous `0..`
bound is a desugar-time error; set-typed positions currently read as plain
arrays with no dedup at all, and the phantom-parameter, shadowing, and productivity checks belong to
features not yet implemented — each arrives as an error when its feature does. The implementation had
already chosen strictness twice where the spec offered leniency: an unresolvable type annotation is
`UNKNOWN_TYPE_REF` rather than "informational" (#7), and a missing root type-ref under a schema-aware
read is reported rather than read vacuously.

**Suggested resolution:** State the position once — *a conforming TSON processor has one severity; this
specification never asks for a warning* — and resolve the table above accordingly:

1. §2.5/§2.6 per #41: duplicates MUST be rejected; delete both last-value-wins rules.
2. §7.5: a duplicate at a set-typed position is a validation error; delete first-occurrence-wins.
3. §7.7: a type-aware duplicate key is a Class 2 validation error (#41 point 3).
4. §5.2/§7.6: `_` at a REQUIRED_DEFAULT field is a validation error, completing the table row that
   already errors at REQUIRED and REQUIRED_FIXED; omission remains the injection route.
5. §5.3: the `0..` size specifier is a resolver error; the diagnostic suggests `[T]`.
6. §5.10: an unused parameter is a resolver error (or, if phantom parameters are wanted, silently legal
   and documented as a feature — but not warned); a parameter shadowing a schema type is a resolver
   error.
7. §5.10.1: a non-productive recursive type is a resolver error, with "guarded" defined (#25).
8. §5.4: pin the required disjointness decision procedure (#47 supplies it, total and two-valued, so
   "unprovable" disappears outright), and make an assertion it decides `false` a resolver error; keep the
   SHOULD-NOT-warn-on-mere-overlap sentence by deleting its premise.
9. §5.2's inline-nesting MAY-warn: delete, or restate as a §9.1-style configurable limit.
10. §7.1: when `!!schema` is present, the governed value MUST be annotated with its type (at minimum for
    validating processors), so a validator can never return "valid" having engaged nothing.

---

## 43. Map-key identity is defined for a parser and for a schema-governed resolver, but not for the reader in between

**Section:** §2.6 ("Maps"); [TSON-SCHEMA] §7.7 ("Resolver Behaviours at Typed Positions"). Related: #41,
#42, #7.

**Problem:** The series defines two notions of map-key identity, at two named layers:

> The parser detects **textually identical** keys: scalar keys are identical if they produce the same
> NFC-normalized string after escape processing (`Alice` and `"Alice"` are duplicates; `1` and `1.0` are
> not); compound keys are identical if they have the same structure with textually identical elements at
> every position. […] Type-aware key equality requires declared type information ([TSON-SCHEMA]). (§2.6)

> When the key type is known, the resolver MAY additionally apply type-specific equality […]. The MAY is
> deliberate: once keys are realised as host-language values, equality semantics are determined by the
> host's collection types. ([TSON-SCHEMA] §7.7)

Between those two sits a layer the series never addresses: a **Class 1 reader** — a processor that has run
§4 base type resolution but has no schema. It is not the parser, because §4 has already turned `0xFF` and
`255` into one value and `1` and `1.0` into two. It is not the §7.7 resolver, because no type was declared.
And it is the layer at which duplicate keys actually become observable, because §2.6's "last value wins" is
a rule about *decoded output*, and only something producing decoded output can apply it.

The gap is not academic: it is where the two rules give different answers.

| Keys | §2.6 (textual) | Base-resolved | §7.7 (typed, `integer` key) |
|---|---|---|---|
| `Alice` / `"Alice"` | duplicate | duplicate | duplicate |
| `0xFF` / `255` | **distinct** | **duplicate** | duplicate |
| `1` / `1.0` | distinct | distinct | **duplicate** |
| `1_000` / `1000` | **distinct** | **duplicate** | duplicate |

A processor that binds keys into a host `Map` cannot honour the textual rule even if it wants to: §7.7's own
sentence — "once keys are realised as host-language values, equality semantics are determined by the host's
collection types" — is *equally true with no schema in scope*, because base resolution realises host values
too. `{ 0xFF => a  255 => b }` read schemalessly into a `Map<BigInteger, ?>` has one entry whatever §2.6
says, and the second silently replaces the first. §2.6's textual rule cannot describe that outcome, so a
Class 1 processor either reports a duplicate the spec calls distinct, or stays silent about a collapse the
spec's own "last value wins" is supposed to govern.

**A second, smaller underspecification, in the same sentence.** §2.6 says a scalar key's identity is "the
same NFC-normalized string after escape processing" and a compound key's is "the same structure with
textually identical elements at every position" — neither of which mentions a key's own **type-ref or
annotations**, both of which a map key may carry (§3.1 permits an annotation at both sides of a map entry).
Are `!token a => 1` and `a => 2` one key or two? Under the quoted text they are one, since the type-ref is
not part of the normalized string; under a naive implementation that compares whole decoded values they are
two. The spec should say so outright, in either direction.

**Interpretation chosen:** key identity is the key's **structure and decoded values, with each node's
type-ref and annotations stripped** — §7.7's host-value equality, applied at every layer that produces
decoded output rather than only where a schema is in scope. `SchemalessTreeReader.keyIdentity` implements
the stripping for the tree path; `SchemalessObjectReader` and the compiled `MapAbstractReader` compare bound
host values, which strips both by construction. So the row that diverges from §2.6 is `0xFF`/`255`, and it
diverges deliberately: those are one key in every host map the value can land in, and reporting them is the
only outcome that does not silently drop an entry. `1`/`1.0` still read as two keys with no schema and one
under an `integer`-keyed schema, which is §7.7 working as designed.

Note this makes the type-aware detection §7.7 leaves as a MAY unconditional here, and — per #42 — an error
rather than a warning.

**Suggested resolution:**

1. **Name the third layer.** §2.6 should say that textual identity is the *minimum* a processor detects, and
   that a processor which decodes values (any Class 1 reader, not only a schema-governed one) compares
   decoded values instead — with the consequence spelled out, that different spellings of one number are one
   key from base resolution onward.
2. **Fold §7.7's MAY into it.** Once the rule is "compare what you decoded", the schema-governed case stops
   being a separate permission and becomes the same rule with more type information available. §7.7 then
   only needs to say that a declared key type may make *more* keys equal (`1` and `1.0` at `integer`), never
   fewer.
3. **Say whether a key's type-ref and annotations participate in identity.** The recommendation is that they
   do not, matching §2.6's existing "NFC-normalized string" wording and §3.1's framing of annotations as
   metadata that a processor may not act on.
4. **With #41 applied, this all becomes a rejection rule** rather than a tie-break, which is the outcome
   worth optimising for: the question stops being "which duplicate wins" and becomes "is this document
   valid", and the three layers then differ only in *how many* documents they reject — a monotone
   relationship a reader can reason about, unlike the current one where the same bytes decode to different
   maps at different conformance classes (#41's point 4).


## 44. A constructor with a shadow-channel parameter is grammatical, implied invalid from three directions, and rejected nowhere

**Section:** §4.2 ("Type Construction"); §5.10 ("Templates and Parameters", the shadowing/label rule); §7.2
("Parameterized heads over binding records"); §8.2 ("Template Instantiation"); §12.1 (grammar). Related: #28.

**Problem:** §5.10 gives a parameter two possible channel forms: **labelled** (a `value_param` route or
`= P`/`~ P` pin on a field — including a `type_ref`-typed field, where the slot's *type* is fixed and the
reference argument flows through its *value*) and **shadowing** (the parameter name standing in a
type-reference channel: a field type, element type, variant, or argument). Three sections jointly assume a
**constructor's** parameters are labelled-only, but none states it as a rule on the declaration:

- §4.2 *describes* the shape: "A parameterized constructor's open slots are ordinary fields of its
  vocabulary, typed `type_ref` and routed by parameter … the parameter rides the value channel." Every
  kernel and meta constructor (`array`, `map`, `set`, `vector`) conforms. But prose describing the shipped
  declarations is not a constraint on authored ones.
- §8.2 makes the labelled-only property *necessary*: "Constructor applications never materialise entries —
  they are carried structurally wherever they occur." A labelled parameter closes by routing the argument
  into a vocabulary slot of the binding record; a shadowing parameter can close only by rewriting the body
  it sits in — §5.10's substitution — which produces a new `type_definition`, i.e. exactly the
  materialisation constructors never get. There is no structural representation of a closed shadow channel.
- §7.2 permits a parameterized head over binding records "if and only if every occurrence of its parameters
  in its vocabulary is a `value_param` member" — and heading binding records is a constructor's defining
  role (§5.6: closed applications carry `!C` bodies). A shadow-parameter constructor's every instance is
  therefore invalid resolver output.

Yet the declaration itself parses and resolves. §4.2 explicitly allows a parameter list before the
constructor marker ("with or without a preceding parameter list — `~product & { ... }`, `<T> ~array<T> ^
{ ... }`"), and nothing stops the composition body from using the parameter in a field-type position:

```
boxer => <T> ~product & { boxed: T }
```

No rule rejects this line. The incoherence surfaces only at each *use*: `boxer<text>` desugars per §5.6's
table to `!boxer { <bindings> }` — but there is no vocabulary slot for `text` to bind to, and the spec
never says what the bindings of a shadow-channel argument even are; a closed declaration `!boxer { boxed:
text }`(?) is barred from heading a binding record by §7.2. Each failure arrives one section away from the
actual mistake, with a diagnostic about applications or resolver output rather than about the declaration
that was wrong on the day it was written.

**Interpretation chosen:** labelled-only is a **declaration-time validity rule for constructors**: a `~`
declaration whose parameter occurs anywhere outside a labelled value channel — in a field type, element
type, variant, or an argument of its source chain in a non-routed position — is a resolver error at the
declaration. Shadow-channel parameters are template-only. (Not yet enforced in this implementation —
`SchemaDesugarer` currently rejects record-template *application* wholesale, which happens to catch the
downstream symptom — but this is the boundary the implementation builds toward: closing strategy is keyed
per parameter occurrence — labelled ⇒ argument routing, shadowing ⇒ §5.10 body substitution — and `~` is
what forbids the substitution-requiring kind.)

**Suggested resolution:**

1. **State the rule in §4.2**, where constructors are defined: a constructor's parameters MUST occur only as
   labelled value-channel routes (`value_param` members in its resolved vocabulary); a parameter of a `~`
   declaration occurring in any type-reference channel is a resolver error at the declaration. One sentence
   turns three sections' worth of implication into a direct diagnostic.
2. **Cross-reference from §5.10's shadowing/label rule** — "shadow where the grammar position admits only
   references; label wherever a value stands" is the natural place to add "and only in non-constructor
   templates", since shadowing is precisely the channel whose closure requires substitution.
3. **Optionally note the why in §8.2**: the labelled-only rule is what makes "constructor applications never
   materialise" coherent — every argument of a constructor application has a vocabulary slot to land in, so
   the structural representation is always sufficient.


## 45. The size templates are partial applications wearing refinement syntax — `^` over an application misapplies an IS-A rule and hides the closing behind a chain-walk

**Section:** §5.10 ("Templates and Parameters" — the substitution paragraph's "nearest `~` constructor in the
source chain"); §5.3/§5.6 (size sugar, desugaring table, "Layer visibility"); §8.1 (the `array_ranged` closure
sentence); §8.2 ("Template Instantiation" — entry shape and worked example); §3.3.1 (the constructor-gate
exemption); §4.2 and the kernel's `array_min`/`array_max`/`array_ranged` declarations. Related: #44, #31, #28.

**Problem:** the interaction the size templates exist to describe is simple — `array_min<text, 2>` means
`!array { element_type: text  min_items: 2 }`, a partially-filled construction of `array` completed at the
application — but the spec reaches that outcome through four cooperating mechanisms: the template is spelled as
a *refinement* over a constructor application (`array_min => <T, MIN> array<T> ^ { min_items: = MIN }`), §5.7
materialises the source chain's vocabulary with `value_param` routes, §8.2 materialises an instantiation entry
per distinct application, and the head of the closed body is discovered by a rule stated about output rather
than read off the declaration:

> the vocabulary body collapses to a binding record headed by the nearest `~` constructor in the source chain
> (§5.10)

Two defects follow, one of substance and one of economy.

**(a) The borrowed `^` grants an IS-A whose precondition fails — a category error the spec commits in three
places.** "Refinement establishes IS-A" is a true rule for refinement over an *instance*: `age => !integer ^
{ min: 0 }` IS-A `integer`, and both sides are types. The size templates borrow the `^` spelling with a
constructor *application* as source, and the IS-A rule rides along even though its precondition — a type on
the left — does not hold. `array` is a constructor: a factory, `constructor: true`, not something a value can
have as its type; `type_definition.supertypes` is a relation between types, and there is no "array of
anything" type in the lattice for `[text]`, `[uri]`, `[integer]` to be subtypes of. Those three close to
`!array` constructions with **empty** `supertypes` — §8.1's own rule, "Construction and instantiation are not
IS-A", with family membership a `source.name` question — and `[text; 2..]` closes to the *same* `!array`
construction, so it must record the same. Yet the spec grants sized closures `[array product top]`:

- §5.6: "their closures are ordinary members of the array family, IS-A `array` and substitutable where arrays
  are expected."
- §8.1: "a closure of `array_ranged` records `supertypes: [array product top]` — the template's own
  supertypes."
- §8.2's entry shape ("`supertypes`: the template's supertypes, unchanged by substitution") and its worked
  example, `array_ranged_pixel_af3` with `supertypes: [array product top]`.

The result is an asymmetry with no design behind it: three spellings of "an array-family thing" — `[text]`,
`vector<text, 3>`, `[text; 2..]` — of which the first two record empty `supertypes` and the third records
three entries headed by a constructor name. The grant is also **inert**: §7.2's subsumption requires the
*position's* type to appear in the value's type's `supertypes`, and two distinct sized-array entries never
list each other — both list only `array` — so no sized array was ever admissible at another's position. The
only substitution it ever enabled was at a position literally typed `array`, which is nameable solely in
kernel-importing layers and used nowhere in the three bundled schemas. Removing it changes no real program.

**(b) The machinery outweighs the interaction.** The three size templates exist only as the sugar's route to
`array`'s vocabulary, and everything around them is scaffolding for that one trip: §3.3.1 must exempt "the
implicit desugar targets" from the `constructor: true` gate precisely because the templates are
non-constructors an ordinary schema cannot name; §5.6's "Layer visibility" paragraph exists to explain that
reachability knot; §8.2 must mint an instantiation entry (internal name, structural identity, dedup,
non-exposure rules) for every `[text; 2..]` while `[text]` stays structural; and the author-facing account of
what `array_min` *means* routes through §5.7's state-transition table applied to something that was never an
instance.

**Interpretation chosen:** sized closures record **empty `supertypes`** — the three quoted passages are
transcription errors induced by the `^` spelling, not design, and family membership is a head question for
every construction uniformly. **Defect (a) is implemented**: `DefinitionResolver.resolveTemplateInstance`
completes a `TemplateInstance` with §8.2's `source` alone, and the supertype transfer is gone, so the
divergence from §8.2's entry shape is deliberate and pinned by test. It costs nothing: the grant was inert
(no two sized entries ever listed each other, so none was ever admissible at another's position) and no
bundled schema writes a sized form. The size *templates'* own entries keep their chain, which the desugarer
walks to find the head. Defect (b) and the redesign below are the recommended resolution and the direction
implementation will follow; the rest of the staging is in `BACKLOG.md`.

**Suggested resolution:** replace refinement-over-application with a form that states the binding directly —
**named partial applications**.

1. **Application-with-bindings.** Extend the application form to `C<args; member  member ...>`: positional
   arguments before the `;` bind `C`'s declared parameters; labelled members after it bind further fields of
   `C`'s vocabulary. Each member is `field: value` (concrete) or `field: = P` (parameter-routed — the `=`
   kept for §5.7's existing reason: a bare token at a scalar position is always a literal, so only a label
   can mark a parameter). The `;` means exactly what it means in `[T; 2..]`: "constraint section follows" —
   `[text; 2..]` *is* `array<text; min_items: 2>` in bracket spelling. §5.6's table already defines an
   application as a binding record in sugar form (`map<K, V>` → `!map { key_type: K  value_type: V }`); this
   completes the correspondence by letting the sugar state members the parameters don't cover. The size
   templates become one-liners: `array_min => <T, MIN> array<T; min_items: = MIN>`.
2. **Closing is evaluation, not a chain walk.** One sentence replaces the "nearest `~` constructor" rule: *a
   named partial application is transparent — applying it applies its body with its parameters bound; every
   application chain terminates at a `~` constructor, so every fully-bound application is a construction of
   that constructor.* `array_min<text, 2>` is `array<text; min_items: 2>` is `!array { element_type: text
   min_items: 2 }`. `!array_min` never exists because `array_min` was never a head — it names a
   partially-filled `!array` record. The head of the result emerges from evaluation instead of being found by
   a rule about output.
3. **Template taxonomy by channel** (replacing §5.10's kind-inference framing, and aligning with #44): a
   **partial application** — body is an application with open bindings, parameters occur only in labelled
   value channels — closes by *routing*, is carried *structurally*, and never materialises an entry
   (`array_min`, `text_keyed_map`, `matrix`); a **structural template** — a record or reference body with
   parameters in type-reference channels — closes by *substitution* and materialises an instantiation entry
   per §8.2 (`container`, `box`). A constructor MUST be of the first kind (#44's rule), which this syntax
   makes nearly unstatable-to-violate: `vector => <T, S> ~array<T; min_items: = S  max_items: = S>` has no
   position for a shadow parameter. (#44 remains needed for the `~product & { ... }` composition form.)
4. **Two completing rules.** *Slot-binds-once*: a vocabulary slot bound twice along an application chain —
   positionally and by label, or an outer application rebinding what an inner one supplied — is a resolver
   error; this replaces the pin-based finality story (`array_min<text, 2; min_items: 5>` fails as a
   duplicate binding, not as a FIXED-state transition). *No recursion through partial applications*: routing
   has no entry to tie a knot through, so a cycle of named partial applications is a resolver error;
   recursion stays with structural templates (§5.10.1), which have entries. Family-coherence checks
   (`min_items ≤ max_items`) run where they do now, at the point the bindings become concrete.
5. **Deletions.** §3.3.1's gate exemption and §5.6's "Layer visibility" reachability knot (the sugar now
   targets the constructor `array` directly, so the gate holds uniformly). The kernel's three size templates
   (their only normative consumer was the sugar; keep one as §5.10's worked example of a named partial
   application, which any kernel-importing layer can still declare). §8.2 narrows to structural templates —
   sized arrays rejoin `[text]` and `vector<text, 3>` as ordinary structural constructions, and the
   instantiation-entry machinery serves only the case that genuinely needs an entry. `^` comes to mean one
   thing — refinement of a *named* instance or type — removing the application-source use and, with it, the
   misapplied IS-A rule that produced defect (a).
6. **Correct the three IS-A passages regardless** of whether the redesign is adopted: §5.6's "IS-A `array`"
   sentence, §8.1's `array_ranged` closure sentence, and §8.2's entry shape and example (`supertypes: []`).

Grammar cost is modest: labelled members inside `<>` reuse the existing record-member productions, and the
lexer — frozen for the series — already emits `;`. Implementation-side the change is mostly deletions: the
desugarer's sized path converges with the `[T]` path (one injected `!array` construction, no
`TemplateInstance`), `DefinitionResolver`'s instantiation completion (source flattening; the supertype
transfer is already gone) is deleted, application routing gains a labelled-member merge with a duplicate-slot
error, and §8.2 materialisation remains only for the still-unimplemented shadow-channel substitution.


## 46. Refinement and composition are level-preserving everywhere but one place — a constructor operand must require a `~` result, and the kernel's only violations are the three lines #45 respells

**Section:** §4.2 ("Type Construction" — the constructor marker's two `~` uses, "constructor refinement is a
meta-level operation"); §5.7 ("Refinement"); §5.8 ("Composition"); §5.9 (subtraction, rule 8); §4.1 (base
kinds); the kernel's declarations. Related: #44, #45.

**Problem:** `^` and `&` each do two jobs at once: derive the new entry's *body* from the operands (vocabulary
tightening, field-set merging) and mint the new entry *at the operands' level*, with IS-A lineage to them.
Every use the spec discusses is level-preserving:

- **Atom refinement** — `age => !integer ^ { min: 0 }`: type → new type, IS-A kept.
- **Record refinement** — `person ^ { ... }`: type → new type, IS-A kept.
- **Composition** — `employee => person & { ... }`: types → new type, IS-A per parent (§4.3).
- **Constructor refinement/composition** — `set => <T> ~array<T> ^ { ... }`, `record => ~product & { ... }`:
  constructor(/kind) → new constructor, lineage kept. §4.2 already marks this as a *different* operation
  ("meta-level", MAY replace fixed values), keyed by the `~` marker.

But the spec never states the level discipline as a rule, and the grammar admits the one combination that
crosses: a constructor operand in a declaration *not* marked `~`. The size templates are that combination —
`array_min => <T, MIN> array<T> ^ { min_items: = MIN }` wants job one (pin a field of `array`'s vocabulary)
and cannot coherently have job two (mint a *type* in IS-A relation to a *constructor*), which is precisely
what produced #45's `supertypes: [array product top]` category error. The crossing is the bug; everything
level-preserving is fine.

**The kernel audit confirms the joint.** Every `^`/`&` in meta-kernel.tn against the rule "a constructor
operand requires a `~` result":

| Declaration | Operand level | `~`? | Verdict |
|---|---|---|---|
| `atom => top & {}`, `product => top & { ... }`, `sum => top & {}` | kind | no | legal (no constructor operand) |
| `record => ~product & { ... }`, `array => <T> ~product & { ... }`, `enum => ~atom & { ... }`, `unit`, `integer_type`, `text_type`, `tuple`, `choice`, `map` | kind | yes | legal |
| `set => <T> ~array<T> ^ { ... }` | constructor | yes | legal |
| `uri_type => ~text_type & atom_specification & { ... }`, `regex_type` | constructor + record mixin | yes | legal |
| `array_min`, `array_max`, `array_ranged` — `array<T> ^ { ... }` | constructor | **no** | **the only violations** |

The rule and #45 independently select the same three lines.

**The rule is one-directional, and must be.** Constructor operand ⇒ `~` result. The converse — `~` result ⇒
constructor operands — is false by the kernel's own design: `record => ~product & {}` seeds the constructor
level from a kind, and `uri_type => ~text_type & atom_specification & { ... }` borrows vocabulary from a
plain record mixin. So the discipline is not "operand and result levels must match on both sides"; it is *you
cannot step down* — deriving from a constructor keeps you at constructor level. Stepping *up* (kinds and
mixins feeding a `~` declaration) is how the constructor level is built at all.

**Lattice payoff.** Under the rule the two IS-A relations separate completely: types relate to types (the
governed lattice §7.2's subsumption reads — atom and record refinement, composition), and constructors relate
to constructors and kinds (the meta lattice — `set` → `array` → `product` → `top`, the chain that admits
`!array { ... }` bodies at `top`-typed positions). No edge ever connects them; the size templates were the
only edge that tried, and #45 documents what came of it.

**Interpretation chosen** (design-review conclusion; not yet enforced): a refinement source, composition
operand, or subtraction head that resolves to a `constructor: true` entry is a resolver error unless the
declaring entry is itself marked `~`. This is a resolver check on what the operand resolves to, like
§3.3.1's existing gates, not a grammar change. In practice the crossing is nearly unreachable in user schemas
already — refinement sources and composition targets resolve through the type-name namespace alone (§3.3.2),
so naming a constructor there requires importing the kernel — which is why the kernel's own three lines were
the only instances anywhere. Implementation enforcement belongs in `DefinitionResolver` beside the existing
wrong-kind-of-target checks (refining a constructor via `!`, applying a non-constructor); it is not yet
written.

**Suggested resolution:**

1. **State the rule in §4.2**, where the `~` marker's two uses are already enumerated: an entry that refines,
   composes with, or subtracts from a constructor MUST itself be declared `~`; a constructor operand in an
   unmarked declaration is a resolver error at the declaration. One sentence makes the level discipline —
   currently implicit in the fact that §4.2 only ever *describes* `~`-marked derivations from constructors —
   into a checkable rule.
2. **Cross-reference from §5.7/§5.8**: refinement and composition preserve level; the `~` marker declares
   which level the operands are on, and §4.2's "meta-level operation" semantics (fixed values replaceable)
   apply exactly when it is present.
3. **Note the asymmetry explicitly**, so the rule isn't over-applied: non-constructor operands in `~`
   declarations remain legal (base kinds seed the level; record mixins like `atom_specification` lend
   vocabulary). The rule forbids stepping down, not mixing upward.
4. **Together with #44 and #45 this completes the characterization of `~`:** operands may come from any level
   but constructor operands force `~` (this entry); parameters are labelled-only (#44); and partial binding
   of a constructor is application syntax (#45), never refinement — after all three, `array<T> ^ { ... }`
   without `~` is ill-formed by rule and `array<T; min_items: = MIN>` is its only spelling.

---

## 47. §5.4's disjointness should be a total, two-valued discrimination-class decision — the value-set derivation answers a question no reader may act on

**Section:** Part 2 §5.4 (Disjointness + Tagging + The `@disjoint` assertion); §5.5 (the atom table);
meta-kernel `type_definition.disjoint: boolean?`; [TSON-DATA] §2.4 (once-only reading of form), §4 (base
type resolution). Related: #23, #24, #42 (case 7).

**Problem:** §5.4 defines `disjoint` as a value-set property with a *partial* derivation: a baseline
("different kinds are disjoint; different atom families are disjoint; same-family numerics are compared by
their bound intervals"), two MAY-prove cases (record composition/closure, pattern disjointness), and "MUST
leave absent when it cannot". Four defects compound:

1. **The different-atom-families rule is wrong as a value-set claim.** `(integer | decimal)` comes out
   "disjoint" while `5` is plausibly a value of both and the two write the identical token shape; `(uuid |
   text)` likewise, while every uuid literal is a valid text. The rule smuggles a nominal distinction in as
   a semantic one.
2. **The fine-grained rules are unreachable from reading.** Per #23's resolution, TSON text discriminates
   an untagged value only by §4's single base-resolution pass — so every same-class verdict the value-set
   rules produce (separated numeric bounds, disjoint patterns, disjoint enum member sets, record closure)
   feeds nothing but the `@disjoint` assertion check. A derivation whose hardest rules exist only to
   validate an optional annotation is effort in the wrong place.
3. **Partiality forces the third state and the warning tier.** "Neither proved nor refuted" is what
   demands `@disjoint`'s warn-on-unprovable (#42 case 7) and the three-valued field prose #24 found
   inconsistent. And because prover *strength* is implementation-defined (the MAYs), the same schema can
   load under one conforming resolver and fail under another — the fact is unusable by any encoding
   precisely because no two implementations are required to derive it identically.
4. **Exactness is expensive and author-illegible.** A conforming value-set prover needs interval algebra
   with open ends, `multiple_of`/digit-facet abstention, IEEE semantics (two default-`allow_nan` floats
   overlap via NaN however far apart their ranges — technically right, baffling to an author), and
   regular-language intersection-emptiness over I-Regexp, which an implementation delegating `pattern` to a
   host regex engine cannot build at all.

**Interpretation chosen** (implemented; supersedes the value-set prover this implementation briefly
carried): redefine `disjoint` as **discrimination-class distinctness** — a total, two-valued function of
the declarations.

Every type has at most one *discrimination class*, derived after following its §8.3 reference chain:

| Class | Types |
|---|---|
| `boolean` | the boolean family |
| `number` | **every** numeric family — `integer`, `decimal`, `float` |
| `string` | every text-form family — `text`, `uuid`, `uri`, `date`/`time`/`datetime`/`duration`, `binary`, `email`, the network-address families, … |
| `brace` | records **and** maps (both `{...}`; `{}` is ambiguous between them) |
| `bracket` | arrays **and** tuples (both `[...]`) |

(§4's `null` class exists at read time — a `null` token resolves to it — but no schema type occupies it:
`void` is not a legitimate variant at all, per #48, so the table has no row for it.)

An enum's class is its members' shared class (`[true false]` is boolean-class; mixed members → none). A
type with no single class — `rational`/`complex` (typed forms straddle classes), `unit`/`value`/`token`
(and so `void`, whose misuse in a choice #48 addresses), a nested choice, an extern, an unresolvable or
cyclic reference — has none, and a classless variant makes its choice `false`.

**The rule:** a choice is `disjoint: true` iff every variant has a class and no class appears twice;
`false` otherwise. The procedure is closed and normative — a resolver MUST record exactly this, MUST NOT
prove more or less — so the MAY-prove latitude, the "MUST leave absent" state, and the SHOULD-warn tier
all delete. `@disjoint` has two outcomes: verified (silent) or an error. The kernel field stays `boolean?`
only because non-choice entries omit it; a choice always carries it.

The payoffs, in order of weight:

1. **`disjoint` ⇔ "the encoding can tell the variants apart".** #23's two carefully-held-apart facts
   (value-set disjointness; encoding separability) collapse into one, and the Tagging rule simplifies to:
   the tag is REQUIRED when the choice is not disjoint, OPTIONAL when it is. `(positive_integer |
   negative_integer)` is simply `false` — which is what a reader could always do with it.
2. **Portable by construction.** The classes are §4's own semantic partition plus the two delimiter forms,
   and they map one-to-one onto JSON's (null/boolean/number/string, `{}`/`[]`) — so a future JSON encoding
   consumes the fact unchanged, which the implementation-varying value-set fact never could be.
3. **Trivially derivable and reasonable-about.** Every rule is answerable by looking at declarations — no
   arithmetic, no IEEE semantics, no automata. An emitter (the format's stated LLM consumer) needs one
   sentence: *if two variants share a class, tag every value of the choice; a tag is never wrong.*
4. **#24 dissolves** (no third state to store) and **#42 case 7 resolves** to its own suggested fix: the
   decision procedure is pinned, so error-on-unprovable is portable — indeed "unprovable" no longer exists.

What is deliberately given up: `@disjoint` on value-set-separated same-class pairs (bounded numerics,
disjoint patterns, disjoint enum member sets, records separated by a required field) is now an *error*
rather than a verified assertion. Nothing operational is lost — no encoding could drop those tags anyway —
and §5.4's own recommendation already points such choices at the labelled form (§5.11), which
discriminates by label and needs no disjointness at all.

**Suggested resolution:** Rewrite §5.4's Disjointness paragraph as the closed class-based procedure above
(the class table can live as a column of §5.5's atom table); restate Tagging as "REQUIRED unless
`disjoint`, OPTIONAL where it is; an omitted tag is recovered by the value's class"; cut the `@disjoint`
outcome list to verified/error; amend the meta-kernel prose so a choice always records the field. This
also delivers the "no MAY/SHOULD latitude" direction the next revision wants: one procedure, one severity,
no implementation-defined strength anywhere in §5.4.

---

## 48. `void` must not be a choice variant — `(T | void)` confuses optionality with choice

**Section:** Part 2 §5.4 (Choice Types); §5.2 (field states — optionality's real home); §7.3 (`void`'s
`null` concession); §7.6 (the absent sentinel under a schema). Related: #47.

**Problem:** Nothing in §5.4 forbids a variant resolving to `void`, so `(text | void)` is grammatical and
resolves. But it is a category error the spec elsewhere works hard to keep out: a choice selects among
*value types* — every variant a type whose values a position can hold — while optionality is a property of
the *position* (a field's `?`/OPTIONAL state, the `_` sentinel), not of the type occupying it. Admitting
`void` as a variant creates a second, worse spelling of "optional T":

1. **Two optionality mechanisms with different semantics.** `field: text?` means the field may be absent;
   `field: (text | void)` means the field is always present and may hold the null value (`void`'s §7.3 text
   form). The distinction between absent and null is one the format draws deliberately ([TSON-DATA] §4.1:
   "`null` is a value that can be stored and transmitted; `_` indicates that no value occupies a
   position") — and the choice spelling invites authors to blur it, JSON-habitually (`type: ["string",
   "null"]` is exactly this construct).
2. **It degrades mechanically under #47.** `void` resolves to the same `unit` body as `value`/`token`, has
   no discrimination class, and so makes any choice containing it non-disjoint — every `(T | void)` value
   carries a `!void` tag around a `null` token, which no author wants and which reads as a bug report
   against the schema rather than a design.
3. **The tag names the wrong thing.** A `!variant` tag selects a variant *type*; `!void null` asserts "this
   position holds the no-value value of type void", a sentence with no data-model payoff over the position
   simply being optional or the field being typed `void` outright where a placeholder is genuinely meant.

**Interpretation chosen:** implemented — `TsonSchemaLinker` rejects a choice with a variant resolving to
`void` (judged after §8.3 flattening, so an alias of `void` is caught too), with a diagnostic that says
why: "optionality is not choice — a value's absence is the position's own state, so mark the position
optional (`?`) instead of uniting its type with void". No bundled schema is affected. `value`/`token`
variants are left legal for now (they degrade to `disjoint: false` via classlessness, per #47).

**Suggested resolution:** Add to §5.4: **a variant MUST NOT resolve to `void`** — a resolver rejects the
declaration, and the diagnostic SHOULD say why: optionality is not choice; write the position optional
(§5.2's `?`), or type it `void` outright if a unit placeholder is genuinely meant. This also keeps #47's
class table closed over real value types (no `null` row needed) and forecloses the `(T | void)` idiom
before JSON-trained emitters — the format's stated consumer — reinvent it schema by schema. The same
reasoning suggests (though this entry does not insist) that `value` and `token` variants deserve a look:
`value` spans every class, so a choice containing it can never be disjoint either, and a position that
admits anything is better typed `value` directly than through a union.
