# Desugaring: open forms and templates

Design notes for the half of `SchemaDesugarer` that deals with type parameters: which sugar forms lift open, and how a
parameterised alias and a record template are normalised into held `!C value` bodies. Current form only; history lives
in git. The sugar table and the naming rule these bullets refer to are in `design/schema-grammar-and-desugaring.md`.

**Invariants**

- A form naming none of the enclosing declaration's parameters lifts *closed*; a form naming one lifts *open*, carrying
  just the parameters it uses, renamed positionally.
- A declaration's own sugar body keeps the declaration's parameter list as written, not the subset the body names.
- A binding's channel follows §12.1's `type-arg` rule, not the slot: a quoted or number-shaped token is a literal,
  every other token rides the reference channel.
- `WireForm.refValue` is the single place a type slot's shape is decided: one producer, one spelling, because
  `DerivedName.ofBinding` hashes what is written.
- `DataClassObjectWriter` cannot produce a held body: `HeldBody.names()` and substitution key on a token being
  *unquoted*.
- Only a *template* is normalised to a held body; a closed record or closed alias resolves at its declaration.
- Only what the author wrote is written into a held record — no fixed constructor fields, no field facts at their
  `record_field` defaults.
- §5.11's uniqueness rule is asked here as well as in the resolver; the two see different phases' bodies.

Related: `design/schema-grammar-and-desugaring.md` (the sugar table, derived names, reporting),
`design/schema-grammar.md`, `design/template-materialisation.md`, `design/held-template-bodies.md`,
`design/class2-compilation.md` (reading `type_argument`).

## Which entry a form lifts to: open and closed lifts

- **Which entry a form lifts to is §5.3's one lift rule, and the enclosing declaration's parameters do not
  enter it.** A form naming none of them lifts *closed*, template or not; a form naming one lifts *open* — an
  `Instance` carrying just the parameters it uses, with the position that held it applying them straight
  back (`<T> { a: [T] }` injects `array_p0_… => <p0> !array { element_type: p0 }` and the field becomes
  `array_p0_…<T>`). So `<T> { a: [T]  b: [order] }` injects one of each, and only the first waits for
  materialisation.
  - **The parameters are renamed positionally**, because two forms alike up to a consistent renaming are one
    template (§8.2) and the name is derived from the record — so normalising the record is what normalises
    the name. The prefix grows (`p`, `pp`, …) until it collides with nothing the record already names: a
    binding may hold a concrete reference to a type genuinely called `p0`.
  - **A declaration's own sugar body is the open construction**, one tier up from the same rule:
    `vector => <T> [T]` *is* the open construction, and its parameters are the declaration's list as
    written, not the subset the body names — a declared parameter the body never uses is an error the linker
    reports, and dropping it here would hide the very thing it looks for.
  - **A binding's channel follows §12.1's own `type-arg` rule**, not the slot: a quoted or number-shaped
    token is a literal, every other token rides the reference channel, and resolution settles what it turns
    out to be. Deciding it here instead would make `[text; N]` bind the *literal* `"N"`.
  - **A scalar type slot may hold an application**, not just a name — which is what makes `[tree<T>; 1..]`
    and `[[T]]` lift at all. The table keeps both renderings of such a slot: the wire field a closed
    construction would write (`type_ref`'s record form, `{ name: tree  arguments: [ … ] }`, which is what
    `DerivedName.ofBinding` hashes) and the reference *as written*, which is what an open binding holds. Only
    `element_type`/`key_type`/`value_type` are reached this way, a named slot being the one an open binding
    can address; `tuple` and `choice` put their positions inside a collection, so they keep only the first
    rendering — written through the same `WireForm.refValue` producer, since a `[type_ref]` holds what a `type_ref`
    holds, and rewritten a pass later by `MetaRefs.mapBodyRefs`, which maps a choice's variants
    and a tuple's elements like any other reference.
    - **No slot refuses an application, because a slot that did would not fail where it decided.** A
      `choiceBinding` requiring a bare name per variant would leave `( box<text> | int32 )` unlifted *whole*, to
      reach `DefinitionResolver` as a `ChoiceRef` it has no case for — and, inside a template, to reach
      `refValue`, whose two inputs are `SimpleRef` and `GenericRef`.
    - **A closed construction writes the record form and lets materialisation close it.** Its body goes
      through the constructor's own reader, so the application has to survive a wire hop, and the resulting
      entry names something that is not an entry yet — for exactly the window an ordinary forward reference
      lives in, since `close()` walks every closed entry's references after the driving loop. It rests on the
      bind readers reading an untagged labelled choice, which is what `type_argument` is
      (`design/linking-and-compilation.md`).
    - **A *value* argument makes the trip intact.** `type_argument`'s value channel binds a raw `Token` —
      §5.10 calls a type argument's literal a bare token rather than the value it denotes — so the slot reads
      the token rather than decoding it (`RawTokenParser`). The spelling is therefore what reaches identity,
      and `NumericIdentity` applies [TSON-DATA] §4.3's equivalence there, so `<255>` and `<0xFF>` are one
      application — radix, digit separators and a redundant sign falling away, and a float's written scale
      with them, while the base-type line does not (`1` is an integer and `1.0` a float under §4, so those
      stay two). §8.2 states exactly that split: recorded as written, compared as the value the token
      denotes under §4, "and no wider".
  - **All four sugar forms lift open, collections included** — §5.10's "collection-valued slots are
    parameterizable". `tuple` and `choice` bind a collection (`elements`, `variants`); a held body is not
    read against the constructor's vocabulary at all until materialisation substitutes, so a parameter
    inside a collection is a token inside an array and lifts like any other, `result => <T> ( T | error )`
    being the spec's own example.
  - **The open form is the closed form**, so there is no per-slot analysis: one binding record
    serves both, since a parameter in a slot is simply the token standing there. `instance(binding,
    typeParams)` builds either, and the phase needs no rule for how to quote a parameter — only for whether
    the declaration around it has one.

## Parameterised aliases

- **A parameterised alias is normalised here as well** (§5.10's partial application). `uuid_pair => <B>
  pair<text, B>` leaves this phase as `<B> !reference { target: pair<text, B> }` — §8.1's own reading of what
  an alias body is, spellable because the kernel's `reference.target` is a `type_ref` rather than a bare name.
  With it no open form is anything but a constructor application: §12.1's
  `[type-params] "!" type-name ws core-value` covers every template and one walk closes them all.
  - Only a *parameterised* one. A closed alias (`text_box => box<text>`) resolves to a `REFERENCE` entry
    directly, the way a closed record resolves to a `RecordBody`: nothing about it is deferred, so there is
    nothing to hold.

## Record templates

- **A record template is normalised here too, and for the same reason the sugar forms are** (`recordBinding`).
  §5.2 says a bare record body denotes `!record { fields: [ … ] }`, so `test => <T> { x: T }` leaves this
  phase as `test => <T> !record { fields: [ { name: x  type: T } ] }` — a held application like `<T> [T]`, and
  closed by the same process. The rule is as fixed and as closed as the sugar table: §5.2's six field
  spellings decide `state` and `value` from the two marks the author wrote, and nothing else is consulted.
  - **Only a *template*.** A closed record still resolves at its declaration into a `RecordBody`, because
    nothing about it is deferred. **Every** template takes it: there is no marker to route one elsewhere. The
    two paths share the §5.2 state
    table (`FieldModifiers`) so the spellings and the errors around them cannot drift apart between a template and
    the closed record beside it.
  - **Only what the author wrote is written.** `access_pattern` and `size_type` are fixed on the `record`
    constructor, and an unmarked field's facts are `record_field`'s own defaults, so none of them is stated —
    the same economy `arrayBinding` makes with an unmarked element's `state`, and what keeps the held form the
    one the author would recognise.
  - **The rewrite has to be here rather than in the resolver.** Resolving the body and writing the resolved
  form back out puts a *second producer* in front of
    a wire form two later phases read, and they disagree: `DataClassObjectWriter` states a no-argument `type_ref`
    in the explicit record form (`{ name: N  arguments: [] }`) where this phase states it positionally (`N`).
    That makes a `type_argument` indistinguishable from a `type_ref` application to a walk that reads neither
    against a vocabulary — and since `DerivedName.ofBinding` hashes what is written, a second spelling is also
    a second entry for one type. One producer, one spelling: `WireForm.refValue` is the single place a type
    slot's shape is decided.
  - **A parameter rides the ordinary `value` slot**, with §8.1's shadowing rule to tell it from a literal,
    which is why the kernel declares one `value` slot and no labelled group. §5.7's fixation then happens at
    materialisation
    (`TemplateMaterialiser.fixRoutedValues`), where the value is concrete.
  - **A composition or refinement template is held too, but from one phase later** (`WireForm.heldRecord`, called by
    `DefinitionResolver.holdIfOpen`). Both absorb fields from a source, and the form to hold is the
    *flattened* one — a §5.7 tightening entry states a modifier and no type-ref, so it is not a `record_field`
    at all until the inherited field supplies one. That needs a namespace this phase does not have, so the
    rewrite happens in the resolver; the **spelling** is `WireForm`'s, which is the whole point.
    `WireForm.heldRecord` and this phase's `recordBinding` are two producers of the wire form and one
    producer of its spelling — both going through `WireForm.refValue` and `WireForm.nameField` is what makes
    that true by construction rather than by two authors agreeing.
    - **`DataClassObjectWriter` cannot serve as that second producer**, which is why `WireForm.heldRecord` exists rather
      than a round-trip. Measured against the desugar spelling it differs four ways: `{ name: "text"
      arguments: [] }` for a bare `text`, `!ref { … }` for a `type_argument`, every token quoted, and `state:
      REQUIRED` written where the default covers it. The first two are the two-spellings problem; the third is
      fatal on its own, since `HeldBody.names()` and substitution both key on a token being *unquoted*, so a
      fully-quoted body references no parameters at all. Its output is canonical-explicit — a different
      language from the one a held body is written in.
    - **The writer is used for exactly one leaf**: a resolved annotation carries its value as a *bound
      object* (`Annotation.value` is `Optional<Object>`), and unbinding one is what an object writer is for.
      That is a self-contained value rather than part of the spelling, so it goes through
      `DefinitionResolver.annotationWireValue` and nothing structural does.
  - **§5.11's uniqueness rule is asked here as well**, and asking it twice is not duplication: the resolver's
    copy sees a closed record body and this one sees a template's, which after normalisation are two
    different phases. Left to the constructor's own reader instead, the wire form carries two `record_field`
    records in an array, where repetition is not an error at all — so `bl => <T> { v: T  v: T }` would ship
    with no verdict.
