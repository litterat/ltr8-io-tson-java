# Atom refinement and coherence

How `!I ^ { ... }` merges with its source, and the two per-family checks every atom body meets at resolution — that a
refinement narrows (`Atom.constraintsCheck`) and that a body's own facets admit anything at all
(`Atom.coherenceCheck`, with `Product.coherenceCheck` its container twin). Current form only; history lives in git.

**Invariants**

- Atom refinement merges with its source on the **wire record, before binding** (`mergeWithSource`); the text
  round-trip has no cheaper substitute, or `REQUIRED`-no-default constructor fields fail `FIELD_REQUIRED`.
- `DefinitionResolver` holds the engine (`DataClassObjectWriter`), never the `TsonObjectWriter` facade.
- `checkNarrows` compares the **merged** result against the source's **effective** range; the refinement side is
  deliberately not folded first, or every widening compares vacuously equal.
- Coherence is hooked in `bindAtomInstance`, where `!C value` and `!I ^ { ... }` meet, and runs after binding.
- Emptiness is the rule, not narrowness: `{ min: 5 max: 5 }` resolves; integer folds its `size`-derived range in first.
- `Product.coherenceCheck` lives on the family, not with any one spelling, and `TsonSchemaLinker` asks every family
  again for the entries materialisation mints.
- A network family's `within`/`excluding` entries are judged by the family's own `coherenceCheck`
  (`AtomCoherence.checkNetworks`), never by the linker or the resolver; its prefix bounds fold in.
- Unchecked by design: `pattern` against `pattern`, selector facets, `duration_type`'s text bounds.

Related: `design/schema-resolution.md` (the resolution phase and its exception boundary),
`design/constructor-application.md` (what makes an entry an atom instance), `design/template-materialisation.md`
(the minted entries the linker re-checks), `design/linking-and-compilation.md`.

## Atom refinement, narrowing and coherence (`tson-compiler/.../resolver/DefinitionResolver.java`)

- **Chained atom refinement merges with the source, it does not replace it** (§5.6's merge semantics):
  `bounded => !int8 ^ { min: -100 }` must still carry `int8`'s own `size`. `mergeWithSource` re-serializes the
  source's bound value via `DataClassObjectWriter` and merges field-by-field (explicit values win) — no
  per-atom-class merge logic needed. **The merge runs on the wire record, before binding, and has to**: a
  constructor field that is `REQUIRED` with no schema default (`float_type.format`, `binary.encoding`) is one
  a refinement body has no reason to restate, so binding the body alone would fail `FIELD_REQUIRED`
  (`DefinitionResolverTest.atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed` pins that case). This is
  why `DefinitionResolver` still holds a writer at all. **It holds the engine, not the facade**: what it
  needs is one value's text with no document around it, which is exactly `DataClassObjectWriter`'s contract,
  and reaching for `TsonObjectWriter` instead would point this module at a front door built over it.
  **The text round-trip has no cheaper substitute**: the engine
  emits straight to a `TsonDataEmitter`, so there is no object→`DataValue` step to borrow
  that would skip it. Removing it for real would mean each constraint family owning its own wire decoding —
  duplicating number-grammar handling (`0xFF`, `_` separators, quoted-vs-unquoted) and bypassing the compiled
  reader's own defaults — which costs more than the round-trip does.
- **A refinement must narrow, and this is enforced** (§5.7). After binding, `checkNarrows` asks the
  constraint family itself — `Atom.constraintsCheck(refined)`, one rule per `schema.meta` family over the
  shared `AtomNarrowing` mechanics — whether the merged result is a valid tightening of the source's own body,
  and throws `TsonSchemaValidationException` if not (`!uint8 ^ { min: -10 max: 300 }` is rejected). Comparing
  the *merged* result rather than the refinement body is what lets an unmentioned facet tighten vacuously; a
  stated bound is judged against the source's **effective** range, folding in a derived one like an integer's
  `size` (intersecting the refinement's own bounds first would make every widening vacuous). Unchecked by
  design, each documented on its class: `pattern` against `pattern` (regular-language containment, and
  `tson-schema` has no `tson-regex` dependency) and **selector** facets
  (`component`/`format`/`encoding`/`version`) — core.tn's own prose calls a selector swap a narrowing, so
  rejecting one would reject a documented construct — §5.7 states the rule per facet kind, and a selector is
  settable where the source leaves it at the constructor's default, identity-only once bound.
- **A body must also be coherent with itself**, which is the other question about the same facets and
  needs no source to compare against. `checkCoherent` asks `Atom.coherenceCheck()` — one rule per family over
  the shared `AtomCoherence` mechanics, the `AtomNarrowing` twin — and throws `TsonSchemaValidationException`
  when a body's own facets admit nothing (`{ min_length: 10 max_length: 3 }`, `{ min: 10 max: 3 }`,
  `{ min_prefix: 40 max_prefix: 8 }`). §7.2 puts the rule and its home in one sentence — "family coherence
  between bindings (e.g. `min ≤ max`) is a **compilation** and ingest concern (§8), **not data validation**"
  — which is also why it cannot live in the atom parsers. Running it at *resolution* rather than compilation
  is deliberate and strictly earlier: the bound constraint objects first exist here, and both are schema-load
  time. It asks the **container** families the same question through `Product.coherenceCheck()`, the
  structural twin: `min_items` above `max_items` admits no value of any length, and an array and a map share
  the one rule since they carry the identical pair. Stating it on the family rather than with any one
  spelling is what makes `[text; 5..3]` and the `!array { … min_items: 5 max_items: 3 }` body it denotes get
  the same verdict — they are one type, and the rule used to live in the desugar phase, which only ever saw
  the first. `TsonSchemaLinker` asks it a second time for the entries **materialisation** mints, which
  resolution never produced: §8.2's "family coherence rules whose operands were parameters" is exactly a
  template whose bounds were `MIN`/`MAX` until an application supplied both. meta.tn's own header `@doc`
  states the same obligation from the other side: bounds are field
  groups so an inclusive/exclusive pair on one side is unrepresentable, while "value-level coherence (the
  lower bound not exceeding the upper) remains a schema-load check". `cidr4_type`'s `@doc` adds the family
  range — prefixes narrow "within the family range 0-32", and "bounds outside that range are invalid at the
  schema level" — so the CIDR pair is judged against its address width as well as against itself.
  - **The linker asks every family, and needs no list to do it.** `TsonSchemaLinker` asks **every** family the
    same question again for the entries materialisation mints, which is how §8.2's "every family coherence rule
    ... asked once more of the closed record" is met without a list — "a resolver needs no list", as it puts it.
  - **Hooked in `bindAtomInstance`, not at either call site**, because that is where the `!C value` and
    `!I ^ { ... }` paths meet — one hook covers both, and a non-`Atom` body passes through. Running it after
    binding is what makes it generic: facets arrive converted to the host type their family compares on, with
    the constructor's own schema-composed defaults already filled in.
  - **Emptiness is the rule, not narrowness.** `{ min: 5 max: 5 }` pins a constant and resolves; the same
    range with either end exclusive admits nothing and does not. Integer folds its `size`-derived range in
    first, so it is the one family where a single stated bound can be incoherent on its own — the opposite of
    `constraintsCheck`, which deliberately does *not* fold the refinement side (there, intersecting first
    would make every widening compare vacuously equal).
  - **`multiple_of: 0` is the one case that was unsound rather than merely undiagnosed.** `IntegerParser` and
    `DecimalParser` validate with `value.remainder(m)`, which throws on a zero divisor — so before this check
    a valid *data* document read against such a type failed on the library's own fault code, an author error
    reported against the wrong document. `RationalParser` already guarded its
    own divisor.
  - Unchecked by design, each documented on its class and matching that family's existing narrowing gap:
    `duration_type`'s text bounds (ordering them means parsing them — `"P1M"` vs `"P30D"` does not order
    lexically, and judging them as strings would call a coherent body empty), `pattern` emptiness, and
    selector facets.
  - **The four network families check their own `within`/`excluding` entries here**, through
    `AtomCoherence.checkNetworks`: the facets are typed `[value]` in meta.tn and must stay so (they list
    networks, and meta declares no network instance to type them by — core.tn does, and core imports meta),
    so they arrive as text and the family that owns the rule is the only place that can judge them. That is
    why `base.atom` carries the `CidrNetwork` pair and `InternetAddress` at all: a check in the linker or the
    resolver would be a second home for one family's rule, which is what `Atom.coherenceCheck` exists to
    prevent. **The pair's own emptiness is judged there too** (`checkAdmitsAValue`): an `excluding` set
    covering every network `within` permits admits nothing, which is `{ min: 10 max: 3 }` with a different
    spelling. Cover over a prefix tree is counting rather than searching — two blocks are nested or disjoint,
    so an exclusion meeting a permitted block either contains it or lies wholly inside one half — so the rule
    is exact and total, not a partial prover. **A network family folds its prefix bounds in**, because its
    value is a block and a block is refused for *overlapping* an exclusion: `within: ["10.0.0.0/24"]
    excluding: ["10.0.0.5/32"] max_prefix: 24` admits no network while admitting almost every address. That is
    the same fold `integer` performs with its `size`-derived range, and §5.5 states both halves: the pair MUST
    admit a value, and for a network family the prefix bounds participate.
  - **The three temporal families' rules are correct but not yet reachable from schema text**, for a reason
    that predates them and is nothing to do with coherence: `date_type.min`/`max` are declared `value?` in
    meta.tn (the untyped escape hatch), so a bound arrives as a `String` and the bind into `DateType`'s
    `Optional<LocalDate>` throws `ClassCastException` — surfaced as an `UnsupportedOperationException`, exit
    70, "not implemented yet". `!date ^ { min: 2020-01-01 }` and the `!date`-tagged spelling fail
    identically, so **no temporal bound can be written at all today**. `AtomCoherenceTest` reaches these
    families by direct construction; they go live at the resolver the moment the binding is fixed.
