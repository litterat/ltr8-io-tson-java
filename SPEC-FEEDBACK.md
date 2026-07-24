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

**Interpretation chosen:** `TsonMapperReader.resolveUnionMember` (`io.ltr8.tson.parser.mapper`) treats this as purely an
application-binding decision, not a spec-conformance one — the Class 1/Class 2 preservation requirement is
satisfied upstream (the parser hands the type-ref through as an uninterpreted string), and everything
downstream of that is this implementation's own policy: try an exact match against a member class's
`@Typename` annotation first, then fall back to a case-*insensitive* match against the member's simple
Java class name (so `!circle` matches a class named `Circle` without requiring every fixture to carry an
explicit annotation). Note this fallback is deliberately *not* consistent with §5.1's case-sensitivity
rule for the built-in vocabulary — there was no spec basis to be consistent with, since §5.1 doesn't
claim to govern this case at all.

**Suggested resolution:** Not a Part 1 defect to fix — flagging so that whenever Part 2 defines real
schema-driven type-name resolution, this implementation's ad hoc `TsonMapper` heuristic gets revisited and
either conformed to the real rule or clearly scoped as "no schema in play" fallback behavior.

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

**Interpretation chosen:** Treat the Part 1 §5.5 table as authoritative and exhaustive for the schemaless
vocabulary — `!email` is not implemented as a built-in annotation in this implementation's Class 1
resolver, matching the letter of §5.5. An unannotated email-shaped token, or one under an unrecognized
`!email` annotation, falls through to ordinary base type resolution (§4) / uninterpreted-marker
preservation (§3.2) respectively, same as any other non-vocabulary name.

**Suggested resolution:** Either add an `!email` row to §5.5 (if the omission is accidental), or add a
sentence to §5.1/§5.5 stating explicitly that `email` is deliberately schema-only and not part of the
schemaless built-in set — RFC 5322 email validation is notoriously heavyweight/contentious to fully
implement, which would be a reasonable rationale, but the spec doesn't currently say so.

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

**Interpretation chosen:** `tson-parser`'s built-in vocabulary (`BuiltinTypeVocabulary`,
`resolver.vocab` package) implements the full sixteen-instance `integer_type` family from `core.tn1` —
`int8` through `int256`, `uint8` through `uint256`, and all four bound-only refinements — not just the
four §5.6 currently lists. `IntegerType`/`IntegerConstraints`/`IntegerSize` are written generically
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
`TsonMapper.toObject(source, MyRecord.class)`) has a real choice to make on hitting a marker it can't
interpret: treat the value as if the annotation weren't there (silently falling back to base type
resolution), or treat an unresolvable annotation on a value it's actively trying to type-check as an
error. Getting this wrong either way has a real cost: silently ignoring means a typo like `!Uuid`
(case-sensitive per §5.1, so not the same as `!uuid`) quietly disables the validation the author clearly
intended; erroring unconditionally means an application that deliberately wants passthrough/lenient
behavior for forward compatibility has no way to ask for it. Every implementation doing typed binding on
top of TSON will face exactly this decision, and Part 1 has nothing to say about it — reasonably, since
it's application-binding policy, not format conformance, but worth recording as a gap a future
implementer's guide could usefully address.

**Interpretation chosen:** `TsonMapper` treats an atom-typed value carrying a type-ref that
`BuiltinTypeVocabulary` doesn't recognize as a binding error (`DataBindException`), not silent fallthrough
to base type resolution. The Class 1 processing step itself (`tson-parser`'s `Parser`/`BaseTypeResolver`)
still faithfully preserves the type-ref exactly as §5.1 requires — this is a binding-layer policy choice
layered on top, not a change to Part 1 conformance. Rationale: a mistyped or unimplemented type-ref on a
value the caller is actively binding to a specific Java type is far more likely to be a bug worth
surfacing than an intentional forward-compatibility signal, and `TsonMapper` has no schema layer yet to
make "this annotation is legitimately not mine to interpret" a safe default assumption.

**Suggested resolution:** Not a Part 1 defect — flagging as guidance worth a note in a future
implementer's guide ([TSON-GUIDE]?) rather than the format spec itself: implementations binding typed
values directly to host objects should consider failing on unrecognized type-refs by default, with
passthrough as an explicit opt-in, rather than the reverse.

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

**Interpretation chosen:** This implementation's atom types (`resolver.vocab.AtomParseException`) live in
`tson-parser`'s resolver package, architecturally alongside — not inside — the structural parser
(`Parser`/`ParseException`), and are raised only from atom-type `read()` calls, never from `Parser` itself.
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
`BinaryType` class (in `tson-parser`'s `resolver.vocab` package) is named to match the established
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

**Interpretation chosen:** `io.ltr8.annotation.Annotated`, a marker on one Java record component,
opts a caller into recovering *only* the annotations on the value the whole record itself corresponds
to (`TsonAnnotations`, in `io.ltr8.tson.parser.mapper`, wrapping the raw, ordered `Annotation` list) — deliberately not
a general "child annotations" mechanism. A record-field-keyed (or array-index-keyed, or map-key-keyed)
carrier for children's annotations was considered and rejected: it would only push the same problem down
one level without resolving the recursive case, needs a different bespoke convention per container kind,
and is real API surface for a capability likely rarely exercised in practice (per the meta-kernel's own
`core.tn1`/`meta.tn1`, annotations overwhelmingly describe *the thing itself* — `@doc:"..."
@ordered:TOTAL` on whole type definitions — not individual scalar fields). An application that needs
full-fidelity annotation access at positions `@Annotated` can't reach still has one: the parsed AST
directly (`DataValue.annotations()`), which is already fully general and doesn't need a schema or a
Java type to project onto.

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
`tson-parser`'s `SchemaParser.parseConstructionDefContinuation`.

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
container. `tson-parser`'s `FieldDef.Modifier` models this as a plain `TokenValue`/`AbsentValue` (reusing
`io.ltr8.tson.parser.ast`'s existing leaf types), not a full `DataValue`.

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
already covers every real positional/braced/bare-array shape `instance` needs). `tson-parser`'s
`ast.schema.Instance` was reshaped accordingly: it no longer carries a separate `target: String`
field alongside a full-generality `value: DataValue` (the redundancy that surfaced this while designing
`SchemaResolver`'s generalized constructor-application resolution — `target` and `DataValue.typeRef()`
were two fields saying the same thing). Instead `Instance(DataValue value)` wraps a `DataValue`
constructed directly from the parsed `core-value`, with `typeRef` pre-set to the constructor name and
`annotations` always empty; `target()` is a thin accessor over `value.typeRef()`. `SchemaParser` widened
`Parser.parseCoreValue()` from `private` to package-private (the same treatment every other grammar
primitive `SchemaParser` reuses from `Parser` already has) to reach the bare production directly instead
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
