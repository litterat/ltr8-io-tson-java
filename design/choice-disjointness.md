# Choice disjointness

Design notes for §5.4's derived `disjoint` fact: `ChoiceDisjointness`, the `DiscriminationClass` table it classifies with,
and why the same table is what untagged reading dispatches on. Current form only; history lives in git.

**Invariants**

- `disjoint` is total and two-valued: `true` exactly when every variant has a discrimination class and no class appears
  twice, `false` otherwise, never absent.
- An `integer` and a `decimal` are one class (`number`); records and maps are one class (`brace`).
- A variant classifies through its §8.3 reference chain; no class at all makes the choice `false`.
- A `void` variant is rejected outright by the linker (`checkVariantsAreNotVoid`), after §8.3 flattening.
- `ChoiceReader.untaggedRecovery` and the derivation both go through `DiscriminationClass.of`: one fact, not two.
- Any change to the class table is a compatibility decision: it decides which schemas load and which documents read
  untagged.

Related: `design/linking-and-compilation.md`, `design/class2-compilation.md`, `design/readers-and-diagnostics.md`.

## The disjointness derivation (`ChoiceDisjointness`, `reader/DiscriminationClass`)

`ChoiceDisjointness.derive` decides §5.4's question for a choice **totally and two-valued**: `disjoint` is
`true` exactly when every variant has a *discrimination class* and no class appears twice, `false`
otherwise — never absent, and §5.4 asks for exactly this: "a resolver MUST record exactly this — it MUST
NOT prove more ... or less". The question the fact exists to answer is not "do the value sets intersect" but
"can an encoding's single form-resolution pass tell the variants apart", and *that* is a total function of
the declarations. A value-set prover (interval algebra, exact I-Regexp intersection-emptiness, record
closure) was built and discarded during PR #36's review: it answered questions no conforming reader may
act on — separating same-class variants takes the type-directed second inspection [TSON-DATA] §2.4
forbids — at the price of verdicts an author cannot predict (two default-`allow_nan` floats overlap via
NaN however far apart their ranges sit) and a conformance bar no second implementation should have to
match.
**The class table** (`DiscriminationClass`, in `reader/` because untagged recovery dispatches on it):
§4's three scalar classes — `boolean`, `number` (every numeric family: an `integer` and a `decimal`
are one class, so never disjoint), `string` (every text-form family: `text`, enums by their members' shared
class — so `[true false]` is boolean-class — `uuid`, `date`, `bytes`, …) — plus `brace` (records **and**
maps: both are `{...}` and `{}` is ambiguous between them, so calling them distinct would promise a
discrimination the wire can't deliver) and `bracket` (arrays and tuples). A variant classifies through its
§8.3 reference chain (an alias is its target; a cycle has no terminal, so no class). No class at all —
`rational`/`complex` (whose typed forms straddle classes), `unit`, a mixed-class enum, a scoped instance (its
membership is a namespace, not a shape), a nested choice, an unresolved name — makes the choice `false`, the
conservative side. A `void` variant
never even gets that far: the linker rejects the declaration outright (`checkVariantsAreNotVoid`, after
§8.3 flattening) — `(T | void)` confuses optionality with choice, which belongs to the position (`?`, `_`),
per §5.4's "a variant MUST NOT resolve to `void`", judged after §8.3 flattening as it is here.

**`disjoint` ⇔ the tag is droppable — one fact, not two.** `ChoiceReader.untaggedRecovery` builds its
`class → variant` dispatch map through the same `DiscriminationClass.of` the derivation classifies with,
so the derived fact and the reader's separability can never disagree — one fact, not a derivation and a
dispatch rule held carefully in step. Recovery still engages only when every class is *scalar* —
a `brace`/`bracket` variant is honestly disjoint from a scalar, but recovery dispatches on a token's
resolved class and structural recovery from an opening delimiter isn't attempted yet. **The class table is
pinned twice over**: it decides which schemas load (`@disjoint` on a `false` choice is an error) and which
documents read untagged, so any change to it is a compatibility decision, not a free improvement.
