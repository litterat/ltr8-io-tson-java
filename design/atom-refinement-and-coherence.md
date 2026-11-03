# Atom refinement and coherence

How `!I ^ { ... }` merges with its source, and the two per-family checks every atom body meets at resolution — that a
refinement narrows (`Atom.constraintsCheck`) and that a body's own facets admit anything at all
(`Atom.coherenceCheck`, with `Product.coherenceCheck` its container twin). Current form only; history lives in git.

**Invariants**

- Atom refinement merges with its source on the **wire record, before binding** (`mergeWithSource`); the text
  round-trip has no cheaper substitute, or `REQUIRED`-no-default constructor fields fail `FIELD_REQUIRED`.
- The merge copies no field the constructor fixes: its reader supplies it, and re-reading the copy would wait on the
  field's type, which can be the refinement itself (`iri => !iri_reference ^ { ... }`, `spec` typed `iri`).
- `DefinitionResolver` holds the engine (`DataClassObjectWriter`), never the `TsonObjectWriter` facade.
- `checkNarrows` compares the **merged** result against the source's **effective** range; the refinement side is
  deliberately not folded first, or every widening compares vacuously equal.
- Coherence is hooked in `bindAtomInstance`, where `!C value` and `!I ^ { ... }` meet, and runs after binding.
- Emptiness is the rule, not narrowness: `{ min: 5 max: 5 }` resolves; integer folds its `size`-derived range in first.
- `Product.coherenceCheck` lives on the family, not with any one spelling, and `TsonSchemaLinker` asks every family
  again for the entries materialisation mints.
- A bound, step or member of a bundled constructor is typed by its family's instance in the same document
  (`temporal.duration_type.min` is a `duration`, `net.ipv4_type.within` a `[cidr4]`), so it is read by that instance's
  reader and binds the family's host type; a float's bounds are `number`, validated before rounding. A refinement of a
  refinement reads its bound as the base instance, and widening is the tightening check's to refuse, not the read's.
- A network family's `within`/`excluding` pair is judged for emptiness by the family's own `coherenceCheck`
  (`AtomCoherence.checkAdmitsAValue`), never by the linker or the resolver; its prefix bounds fold in.
- Unchecked by design: `pattern` against `pattern` (and `pattern` emptiness), selector facets.
- A reference's `of` narrows to the source's or a type IS-A it, and that one rule is judged at **link**, not in
  `checkNarrows`: `ReferenceBody.constraintsCheck` asks only that a reference refines a reference, and
  `ReferenceBody.ofNarrowing` takes the IS-A as a predicate the linker supplies (`TsonSchemaLinker.checkReferenceNarrows`).
- `unicode.text_type`'s `pattern` and `members` are each **settable once** — they occupy one logical position and the pattern
  half cannot be narrowed, so the position takes one rule. A member set may still be *added* to a patterned source.
- `unicode.text_type`'s `normalization` is **identity-only**, default or not: setting it where the source left `NONE` changes
  what a token means rather than narrowing the values, so no refinement moves it. Its members are judged as values in
  the form, and two that are one value are refused.
- `members` against `pattern` is checked on the family with the length facets — one rule, one place. `tson-schema`
  depends on `ltr8-regex` for it; the engine is an internal library like any other.
- Every count facet is the kernel's `numeric.count`, `!int64 ^ { min: 0 }` (0 to 2^63-1), and a Java `long` in the
  model (`Optional<Long>`);
  a facet holding a value of the constrained type keeps that family's type (`integer_type`'s bounds, `members` and
  `multiple_of` are `integer`, `BigInteger`). A count past what a Java string or collection holds compares as any
  other: a `max_*` past `Integer.MAX_VALUE` is never reached, a `min_*` past it is met by no value, and neither is a
  bind failure or a schema error.
- `numeric.integer_size.bits` is `integer`, not a count: `int64`'s `size` is read against `integer_size` and `count`
  refines `int64`, so typing `bits` by `count` makes `count` wait on itself. That `bits` is above zero is
  `IntegerType.coherenceCheck`'s.
- A width's range is never materialised. `IntegerSize.admits` judges a value by bit length (signed `n` bits: below `n`;
  unsigned: non-negative and at most `n`), `within` compares two widths by their bits and signedness, and both
  `IntegerType`'s checks and `IntegerParser`'s read go through them, so any `bits` a schema states loads and reads
  exactly (`IntegerWidthTest`).

Related: `design/schema-resolution.md` (the resolution phase and its exception boundary),
`design/constructor-application.md` (what makes an entry an atom instance), `design/template-materialisation.md`
(the minted entries the linker re-checks), `design/linking-and-compilation.md`.

## Atom refinement, narrowing and coherence (`tson-compiler/.../resolver/DefinitionResolver.java`)

- **Chained atom refinement merges with the source, it does not replace it** (§5.6's merge semantics):
  `bounded => !int8 ^ { min: -100 }` must still carry `int8`'s own `size`. `mergeWithSource` re-serializes the
  source's bound value via `DataClassObjectWriter` and merges field-by-field (explicit values win) — no
  per-atom-class merge logic needed. **The merge runs on the wire record, before binding, and has to**: a
  constructor field that is `REQUIRED` with no schema default (`numeric.float_type.format`, `binary.encoding`) is one
  a refinement body has no reason to restate, so binding the body alone would fail `FIELD_REQUIRED`
  (`DefinitionResolverTest.atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed` pins that case). This is
  why `DefinitionResolver` holds a writer at all. **It holds the engine, not the facade**: what it
  needs is one value's text with no document around it, which is exactly `DataClassObjectWriter`'s contract,
  and reaching for `TsonObjectWriter` instead would point this module at a front door built over it.
  **The text round-trip has no cheaper substitute**: the engine
  emits straight to a `TsonDataEmitter`, so there is no object→`DataValue` step to borrow
  that would skip it. Removing it for real would mean each constraint family owning its own wire decoding —
  duplicating number-grammar handling (`0xFF`, `_` separators, quoted-vs-unquoted) and bypassing the compiled
  reader's own defaults — which costs more than the round-trip does.
- **A refinement must narrow, and this is enforced** (§5.7). After binding, `checkNarrows` asks the
  constraint family itself — `Atom.constraintsCheck(refined)`, one rule per atom family of the schema model over the
  shared `AtomNarrowing` mechanics — whether the merged result is a valid tightening of the source's own body,
  and throws `SchemaValidationException` if not (`!uint8 ^ { min: -10 max: 300 }` is rejected). Comparing
  the *merged* result rather than the refinement body is what lets an unmentioned facet tighten vacuously; a
  stated bound is judged against the source's **effective** range, folding in a derived one like an integer's
  `size` (intersecting the refinement's own bounds first would make every widening vacuous). Unchecked by
  design, each documented on its class: `pattern` against `pattern` (regular-language containment; `IRegex`
  answers disjointness and exposes no complement to build containment from, so having the engine is not having
  the oracle — which is also why `unicode.text_type.members` takes the identity-only rule rather than the subset rule
  the numeric tiers use: one rule for a position whose other half cannot be narrowed) and **selector** facets
  (`component`/`format`/`encoding`/`version`) — numeric.tn's own prose calls a selector swap a narrowing, so
  rejecting one would reject a documented construct — §5.7 states the rule per facet kind, and a selector is
  settable where the source leaves it at the constructor's default, identity-only once bound. An
  `unicode.identifier_type`'s **profile** facets are identity-only outright, default or not: setting an addition set the
  source left unset widens the profile, so no set-once form of the rule is sound (`IdentifierType`). Every text
  family's `normalization` is identity-only on the same terms (`TextType`): turning folding on would make
  `Content-Type` one value under the source and another under the refinement.
- **A reference narrows `of` by IS-A, and the link judges it** (§5.7, `meta.reference`'s own `@doc`). The refined `of`
  is the source's or a type IS-A it -- a constructor IS-A one by its chain (`unicode.identifier_type` lists
  `meta.type`), an ordinary type by its own (`big_order => order ^ {}`) -- so every name the refinement admits, the
  source admits too: `meta.identifier_ref => !meta.type_ref ^ { of: unicode.identifier_type }` is the kernel's case.
  The two names are a namespace's, not the bodies', and `of` holds names that order nothing
  (`design/resolver-vocabulary-and-bootstrap.md`), so at resolution neither need be ready:
  `ReferenceBody.constraintsCheck` admits any reference, and the linker's `checkReferenceNarrows` asks the family's
  rule, `ReferenceBody.ofNarrowing`, with §7.2's IS-A over the merged namespace (alias hops followed, the entry's chain
  read), against each reference the refinement's chain names. A widening (`!maps ^ { of: meta.product }` over `of:
  meta.map`) and an unrelated `of` are `SchemaValidationException`s, reported as `checkNarrows` reports, "not a valid
  refinement of '!maps' (§5.7)". The refinement's chain lists its source, as every atom refinement's does, so kind
  membership and §7.2 see it IS-A its source.
- **A body must also be coherent with itself**, which is the other question about the same facets and
  needs no source to compare against. `checkCoherent` asks `Atom.coherenceCheck()` — one rule per family over
  the shared `AtomCoherence` mechanics, the `AtomNarrowing` twin — and throws `SchemaValidationException`
  when a body's own facets admit nothing (`{ min_length: 10 max_length: 3 }`, `{ min: 10 max: 3 }`,
  `{ min_prefix: 40 max_prefix: 8 }`). §7.2 puts the rule and its home in one sentence — "family coherence
  between bindings (e.g. `min ≤ max`) is a **compilation** and ingest concern (§8), **not data validation**"
  — which is also why it cannot live in the atom parsers. Running it at *resolution* rather than compilation
  is deliberate and strictly earlier: the bound constraint objects first exist here, and both are schema-load
  time. It asks the **container** families the same question through `Product.coherenceCheck()`, the
  structural twin: `min_items` above `max_items` admits no value of any length, and an array and a map share
  the one rule since they carry the identical pair. Stating it on the family rather than with any one
  spelling is what makes `[text; 5..3]` and the `!meta.array { … min_items: 5 max_items: 3 }` body it denotes get
  the same verdict — they are one type, and a rule kept in the desugar phase would only ever see the
  first. `TsonSchemaLinker` asks it a second time for the entries **materialisation** mints, which
  resolution never produced: §8.2's "family coherence rules whose operands were parameters" is exactly a
  template whose bounds were `MIN`/`MAX` until an application supplied both. numeric.tn's and temporal.tn's header
  `@doc`s state the same obligation from the other side: bounds are field
  groups so an inclusive/exclusive pair on one side is unrepresentable, while "value-level coherence (the
  lower bound not exceeding the upper) is a schema-load check". `net.cidr4_type`'s `@doc` adds the family
  range — prefixes narrow "within the family range 0-32", and "bounds outside that range are invalid at the
  schema level" — so the CIDR pair is judged against its address width as well as against itself.
  - **The linker asks every family, and needs no list to do it.** `TsonSchemaLinker` asks **every** family the
    same question again for the entries materialisation mints, which is how §8.2's "every family coherence rule
    ... asked once more of the closed record" is met without a list — "a resolver needs no list", as it puts it.
  - **Hooked in `bindAtomInstance`, not at either call site**, because that is where the `!C value` and `!I ^ { ... }`
    paths meet — one hook covers both. `checkCoherent` asks an `Atom`, `Product` or `Sum` body; any other passes
    through. Running it after binding is what makes it generic: facets arrive converted to the host type their family
    compares on, with the constructor's own schema-composed defaults already filled in.
  - **Emptiness is the rule, not narrowness.** `{ min: 5 max: 5 }` pins a constant and resolves; the same
    range with either end exclusive admits nothing and does not. Integer folds its `size`-derived range in
    first, so it is the one family where a single stated bound can be incoherent on its own — the opposite of
    `constraintsCheck`, which deliberately does *not* fold the refinement side (there, intersecting first
    would make every widening compare vacuously equal).
  - **`multiple_of: 0` is the one case where the check buys soundness rather than a diagnosis.** `IntegerParser`
    and `DecimalParser` validate with `value.remainder(m)`, which throws on a zero divisor — so without the
    check a valid *data* document read against such a type would fail on the library's own fault code, an
    author error reported against the wrong document. `RationalParser` guards its own divisor. A `size.bits` of
    zero or below is refused on the same footing: a width of no bits admits no value.
  - **`unicode.text_type.members` answers to every facet beside it, in one place.** `TextType.coherenceCheck` judges
    every member against the length facets and against the `pattern` — one rule ("every member satisfies the
    other facets on the same body"), not split by which engine each half needs, which is why `tson-schema`
    depends on `ltr8-regex`. `net.uri_type`/`unicode.regex_type`/`net.email_type` reach it through `textConstraints()`, the
    same delegation their length rule already uses.
  - Unchecked by design, each documented on its class and matching that family's existing narrowing gap: `pattern` emptiness
    and selector facets. `temporal.duration_type` and `temporal.period_type` bounds are compared as the values they denote (a
    `Duration`; a period's months), never as text — `"P1M"` vs `"P30D"` does not order lexically.
  - **The four network families judge their own `within`/`excluding` pair here**, through
    `AtomCoherence.checkAdmitsAValue`. The facets are typed `[cidr4]`/`[cidr6]` in net.tn's constructors, so every
    entry arrives as a `CidrInet4Network`/`CidrInet6Network` that `cidr4`'s or `cidr6`'s reader has already judged a
    network; a malformed entry is that reader's refusal at schema load, like any other value of it. What is left is the
    pair, and the family that owns the rule is the only place that judges it, which is why the `CidrNetwork` pair and
    `InternetAddress` sit beneath `tson-schema`, in `ltr8-net`: a check in the linker or the resolver would be a second
    home for one family's rule, which is what `Atom.coherenceCheck` exists to prevent.
    **The pair's emptiness**: an `excluding` set covering every network
    `within` permits admits nothing, which is `{ min: 10 max: 3 }` with a different spelling. Cover over a prefix tree is
    counting rather than searching — two blocks are nested or disjoint, so an exclusion meeting a permitted block either
    contains it or lies wholly inside one half — so the rule is exact and total, not a partial prover. **A network family
    folds its prefix bounds in**, because its value is a block and a block is refused for *overlapping* an exclusion:
    `within: ["10.0.0.0/24"] excluding: ["10.0.0.5/32"] max_prefix: 24` admits no network while admitting almost every
    address. That is the same fold `integer` performs with its `size`-derived range, and §5.5 states both halves: the pair
    MUST admit a value, and for a network family the prefix bounds participate.
  - **A bound binds the family's host type, so the temporal and duration families' rules are live at the resolver.**
    `temporal.date_type.min`/`max` and their siblings are typed by their family's instance in temporal.tn (`date`,
    `duration`, …), as numeric.tn types its bounds `number` and `rational`, so a bound is read by that instance's reader:
    `!date ^ { min: 2020-01-01 }` binds a `LocalDate` and `!duration ^ { min: PT30M }` a `Duration`. A bound the atom
    refuses (`!date ^ { min: "not-a-date" }`, `!duration ^ { min: P1Y }`, `!number ^ { min: 0x10 }`) is the author's error
    and reports `SCHEMA_ERROR` with that reader's verdict, as the kernel's `integer`-typed bounds do. `rational`'s host
    value is a record, so `ResolverBindContext` registers `Rational` as an atom for the schema model. **A refinement of a
    refinement reads its bound as the base instance**: `!positive ^ { min: -5 }` reads `-5` as a `number`, and its
    widening is `checkNarrows`' to refuse. `TypedFacetTest` pins the binding, the refusals, and that a loaded bound decides
    a read; `AtomCoherenceTest` reaches the same rules by direct construction.
