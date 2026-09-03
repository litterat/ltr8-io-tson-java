# Review questions — the Revision 35 vocabulary changes

Scratch document for this review round. Delete it when the questions are answered; nothing should cite it.
Items resolved during the work are gone; what is left is open. `SPEC-FEEDBACK.md` carries everything that
belongs in the register — this file is only what still needs a decision from you.

---

## Rules the spec asserts that are not enforced, and mostly cannot be where they belong

### 1. §4.2's value-route-only rule is not enforced, and cannot be where it belongs

A `~` constructor's parameters may occur only as value routes; a type-channel one is a resolver error at the
declaration. There is no such check. A parameterized container constructor resolves, links and compiles, and
the first symptom is a *read* failing with "'set' is a template taking 1 type argument" — a diagnostic about a
data document, for a mistake in a schema.

I wrote the check and backed it out. The rule turns on **the type of the slot the parameter stands in**, and an
open entry's body is a `HeldBody` with no slot types. `element_type: = T` and `max_items: = N` are spelled
identically; the first is a type channel and the second a legal value route. My check passed the first and
false-positived the second. Same wall as §5.10's argument-kind rule, reached from a new direction.

### 2. §5.7's selector rule is unenforced, and for a defaulted selector unenforceable

"A selector may be set where the source leaves it at the constructor's default." Nothing checks it. The two
selectors left are `float_type.format` (REQUIRED, no default — so the rule has nothing to fire on, which is
its own small defect) and `complex_type.component` (`~ NUMBER`), and for the defaulted one the check cannot be
written in the value model at all: after resolution, `complex` and an explicit `^ { component: NUMBER }` are
the same record, so a legal set-from-default and an illegal re-set are indistinguishable. `ComplexType` says so
at the class.

This bit during the binary work — a selector check I had added refused `base64url` outright — and it is why the
`bytes` redesign is better than a facet: with no selector there is no rule to leave unenforced.

### 3. #25's rule (c) is a live defect and blocks `@rest`'s check

`DefinitionResolver.resolveField` rebuilds a restated field with `Annotations.empty()`, so a subtype that
tightens a field silently drops its inherited annotations — a `@rest`, a `@deprecated`. §5.7's modifier-only
entry (`extra: ?`) un-marks the field it names. **`@rest`'s "at most one per composed chain" cannot be phrased
against a chain a restatement severs without saying so.**

The entry recommends a restatement's annotations merge over the inherited ones. That is a small change and I
can make it — but it changes resolved output for any schema restating an annotated field, so it wants your
call, and it is the one item here that is squarely an implementation defect rather than a spec question.

---

## Declared but not enforced

All of it is in `BACKLOG.md` now — the two load-time checks under **Checked annotations**, `members`'
missing read-time half and `scoped`'s absent reader under **Built-in types**. Only one needs a decision from
you rather than someone's time:

**#25(c), the restated-field annotation drop**, is what blocks `@rest`'s "at most one per composed chain" —
a restatement severs the chain silently, so there is nothing to count along. The recommended fix is that a
restatement's annotations merge over the inherited ones, and it changes resolved output for any schema that
restates an annotated field. That is the call; the rest is work.

## Judgement call worth confirming

### 4. `duration` is nanosecond-resolved, where meta.tn says rational seconds

The host type is `java.time.Duration`, so `PT0.0000000001S` is refused rather than rounded. Chosen because
`time_type` and `datetime_type` already work at nanosecond resolution for the same fractional-second component,
and silently rounding would make a bound comparison lie. True rational seconds means `Rational` as the host
type — a different change.
