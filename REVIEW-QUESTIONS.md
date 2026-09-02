# Review questions — the Revision 35 vocabulary changes

Scratch document for this review round. Delete it when the questions are answered; nothing should cite it.
Items resolved during the work are gone; what is left is open.

---

## Open — decisions I made that you may want to reverse

### 1. `extern_of`/`extern_type` are the first published resolved output with a held body

They are in, and `core-resolved.tn` writes them as the application verbatim:

```
extern_of => !type_definition { kind: SUM  source: scoped  parameters: [S]
                                body: !scoped { scope: [EXTERN]  schemas: { S => _ } } }
```

That is legal — `scoped` IS-A `top`, and the fixture reads against meta.tn where `scoped` is declared. But
§8.1 is self-contradictory about whether a `type_definition` may carry a parameter reference at all
(`SPEC-FEEDBACK.md` #4, still open), so this branch is now the proposal for #4 as well as #23. Worth knowing
that it is a bigger claim than #23 makes on its own.

### 2. §8.1's shadowing rule is asserted and unimplemented, which is what the fixture comparison had to work around

§8.1 says a token in an open entry's body is a parameter reference rather than a value of the slot's declared
type. **No reader implements that**: both apply the slot's own type regardless. Where the parameter's
spelling happens to satisfy it, the read *succeeds and binds a misreading* — `extern_of`'s `S` binds as a
relative URI — and where it does not, it fails outright (`min_items: N` against `integer`).

So an open entry's body is compared as wire form on both sides (`ResolvedForm.heldBodies` /
`ourHeldBody`), which is what it is on both sides. That works, but the underlying gap is real: a resolved
document containing open entries cannot be ingested faithfully by this library. That is arguably the
substantive answer to #4, and it is not what #4 currently says.

### 3. §4.2's value-route-only rule is not enforced, and cannot be where it belongs

A `~` constructor's parameters may occur only as value routes; a type-channel one is a resolver error at the
declaration. There is no such check. A parameterized container constructor resolves, links and compiles, and
the first symptom is a *read* failing with "'set' is a template taking 1 type argument" — a diagnostic about
a data document, for a mistake in a schema.

I wrote the check and backed it out. The rule turns on **the type of the slot the parameter stands in**, and
an open entry's body is a `HeldBody` with no slot types. `element_type: = T` and `max_items: = N` are spelled
identically; the first is a type channel and the second a legal value route. My check passed the first and
false-positived the second. Same wall as §5.10's argument-kind rule, reached from a new direction.

### 4. §5.7's selector rule is likewise unenforceable in the value model

`encoding` carrying a default is what lets the four alphabets refine `bytes` (§5.7: a selector may be set
"where the source leaves it at the constructor's default"). But after resolution `bytes` and an explicit
`!bytes ^ { encoding: BASE64 }` are the same record, so nothing in `BinaryType` can tell a defaulted value
from a stated one. The check I had written refused `base64url` outright; it is removed, and the class says
why. Enforcing it needs the constructor's own vocabulary, which is the resolver's to hold.

### 5. `bytes` is base64-unless-refined, not alphabet-free

A consequence of the default worth naming: a `bytes`-typed position reads its values as base64, so an author
wanting hex says so at the position rather than on the value. That closes the refined-`bytes` hole (a
refinement of `bytes` is readable now) at the cost of the type no longer being neutral about spelling.

The alternative, still available: admit the four alphabet names at any position in the binary family,
treating the annotation as a *spelling selector* rather than a subtype claim. Left undone on your call.

### 6. #25's rule (c) is a live defect and is not fixed

`DefinitionResolver.resolveField` rebuilds a restated field with `Annotations.empty()`, so a subtype that
tightens a field silently drops its inherited annotations — a `@rest`, a `@deprecated`. §5.7's modifier-only
entry (`extra: ?`) un-marks the field it names. **`@rest`'s "at most one per composed chain" cannot be
checked until this is decided.** The entry recommends a restatement's annotations merge over the inherited
ones; that changes resolved output for any schema restating an annotated field, so it wants your call.

### 7. Declared but not enforced

Each resolves, links and round-trips, and none of it *does* anything yet.

- **`@discriminator` / `@rest`** carry no load-time check.
- **`members`** has its §5.7 narrowing and schema-load coherence; no *parser* enforces it at read time, so a
  value outside the member set is currently accepted.
- **`scoped`** compiles to an `ErrorReader`, as `extern`/`unknown_type` did.

### 8. `duration` is nanosecond-resolved, where meta.tn says rational seconds

The host type is `java.time.Duration`, so `PT0.0000000001S` is refused rather than rounded. Chosen because
`time_type` and `datetime_type` already work at nanosecond resolution for the same fractional-second
component, and silently rounding would make a bound comparison lie. True rational seconds means `Rational` as
the host type — a different change.

---

## Open — register upkeep this work owes

The **Interpretation chosen** and **What is running** paragraphs of #23, #24, #25, #26 and #29 all describe
the gap as what is running, and all now describe code. Each also carries the sentence *"the bundled schemas
are untouched for the reason #7 gives"*, false in all five since #311.

**#19's stated blocker is void** for the same reason: it says nothing can be built because the kernel is a
published, hash-pinned Revision 34 artifact and digests cannot be minted for an unpublished document. That is
exactly what this branch now does.

**#26 is partly closed by a better answer than it proposed** — splitting `duration` and `period` rather than
either adding exclusive bounds to a partial order or removing the facets. Its `contains` half is withdrawn
(question below).

**#27 needs no artifact change**; this implementation already diverges in the direction it recommends.

---

## Withdrawn during the work, recorded so it is not re-proposed

**#26's `contains`/`min_contains`/`max_contains`** were built and backed out. JSON Schema's `contains` is an
*applicator* — a subschema you try against each element — and TSON has no applicators: a type here is a
reader, and `ChoiceReader` dispatches on a precomputed discrimination class rather than trying variants.
A restricted form does translate (require `contains` to resolve to a type `element_type` already admits) and
is worth writing up, but it needs three things settled first: §26 asserts the refinement direction is
monotone and it is not (narrowing the type tightens `min_contains` and loosens `max_contains`),
`min_contains: 0` is JSON Schema's own wart, and the implicit `minContains: 1` wants writing out.
