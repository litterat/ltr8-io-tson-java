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

**Interpretation chosen:** `TsonMapper.resolveUnionMember` (tson-mapper) treats this as purely an
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
