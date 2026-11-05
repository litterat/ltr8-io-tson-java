# Desugaring: open forms and templates

Design notes for the half of `SchemaDesugarer` that deals with type parameters: which sugar forms lift open, how a
parameterised alias is normalised into a held `!C value` body, and how a record template is written as the labelled
`!meta.record_template` payload it denotes. Current form only; history lives
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
- Only a *template* is normalised; a closed record or closed alias resolves at its declaration. A record template,
  sugar or explicit `<T> !meta.record { … }`, is written as the `!meta.record_template { … }` it denotes, each
  whole-type and whole-value parameter labelled here, where the parameters are in scope (`RecordTemplateDef`), and the
  resolver binds it (`DefinitionResolver.openRecord`).
- Only what the author wrote is written into a record template's payload — no fixed constructor fields, no field
  facts at their `meta.record_field` defaults.
- §5.11's uniqueness rule is asked here as well as in the resolver; the two see different phases' bodies.

Related: `design/schema-grammar-and-desugaring.md` (the sugar table, derived names, reporting),
`design/schema-grammar.md`, `design/template-materialisation.md`, `design/held-template-bodies.md`,
`design/references.md` (a reference carrying arguments).

## Which entry a form lifts to: open and closed lifts

- **Which entry a form lifts to is §5.3's one lift rule, and the enclosing declaration's parameters do not
  enter it.** A form naming none of them lifts *closed*, template or not; a form naming one lifts *open* — an
  `Instance` carrying just the parameters it uses, with the position that held it applying them straight
  back (`<T> { a: [T] }` injects `array_p0_… => <p0> !meta.array { element_type: p0 }` and the field becomes
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
    construction would write (the reference, `element_type: tree<p0>`, which is what
    `DerivedName.ofBinding` hashes) and the reference *as written*, which is what an open binding holds. Only
    `element_type`/`key_type`/`value_type` are reached this way, a named slot being the one an open binding
    can address; `tuple` and `choice` put their positions inside a collection, so they keep only the first
    rendering — written through the same `WireForm.refValue` producer, since a `[meta.type_ref]` holds what a
    `meta.type_ref` holds, and rewritten a pass later by `MetaRefs.mapRefsKeepingChain`, which maps a choice's variants
    and a tuple's elements like any other reference.
    - **No slot refuses an application, because a slot that did would not fail where it decided.** A
      `choiceBinding` requiring a bare name per variant would leave `( box<text> | int32 )` unlifted *whole*, to
      reach `DefinitionResolver` as a `ChoiceRef` it has no case for — and, inside a template, to reach
      `refValue`, whose two inputs are `SimpleRef` and `GenericRef`.
    - **A closed construction writes the reference and lets materialisation close it.** Its body goes
      through the constructor's own reader, so the application has to survive a wire hop, and the resulting
      entry names something that is not an entry yet — for exactly the window an ordinary forward reference
      lives in, since `close()` walks every closed entry's references after the driving loop. It rests on a
      kind slot reading an application in a kernel body (`KindSlotReader`, `design/references.md`).
    - **A *value* argument makes the trip intact.** The reference grammar keeps a value argument as its token
      (`TypeArgument.Value`) — §5.10 calls a type argument's literal a bare token rather than the value it
      denotes — so nothing decodes it. The spelling is therefore what reaches identity,
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
  pair<text, B>` leaves this phase as `<B> !meta.alias { target: pair<text B> }` — §8.1's own reading of what
  an alias body is, spellable because the kernel's `alias.target` is a reference, which may carry arguments.
  With it no open form is anything but a constructor application: §12.1's
  `[type-params] "!" type-name ws core-value` covers every template and one walk closes them all.
  - Only a *parameterised* one. A closed alias (`text_box => box<text>`) resolves to an `ALIAS` entry
    directly, the way a closed record resolves to a `RecordBody`: nothing about it is deferred, so there is
    nothing to hold.

## Record templates

- **A record template is normalised here too, and for the same reason the sugar forms are** (`recordTemplate`).
  §5.2 says a bare record body denotes a record construction, and with parameters that is a `record_template`, so
  `test => <T> { x: T }` leaves this phase as the `RecordTemplateDef` `<T> !meta.record_template { fields: { x => {
  type_param: T } }  parameters: [ { name: T  type: meta.type_ref } ] }`. The rule is as fixed and as closed as the
  sugar table: §5.2's six field spellings decide `state` and `value` from the two marks the author wrote, and nothing
  else is consulted. The explicit `<T> !meta.record { … }` takes the same labels (`labelled`), every other slot
  carried as written for the resolver to answer in `meta.record`'s terms.
  - **Its own `TypeDef`, so no flag tells it from an author's construction.** A written `!meta.record_template { … }`
    is an `Instance`, refused as resolver vocabulary (`requireAuthorable`); the desugarer's is a `RecordTemplateDef`,
    which no parser builds, and goes straight to `openRecord`.
  - **Only a *template*.** A closed record still resolves at its declaration into a `RecordBody`, because
    nothing about it is deferred. **Every** template takes it: there is no marker to route one elsewhere. The
    two paths share the §5.2 state
    table (`FieldModifiers`) so the spellings and the errors around them cannot drift apart between a template and
    the closed record beside it.
  - **Only what the author wrote is written.** `access_pattern` and `size_type` are fixed on the `record`
    constructor, and an unmarked field's facts are `meta.record_field`'s own defaults, so none of them is stated —
    the same economy `arrayBinding` makes with an unmarked element's `state`, and what keeps the held form the
    one the author would recognise.
  - **The rewrite has to be here rather than in the resolver.** Resolving the body and writing the resolved
    form back out puts a *second producer* in front of a wire form two later phases read, and since
    `DerivedName.ofBinding` hashes what is written, a second spelling is a second entry for one type. One
    producer, one spelling: `WireForm.refValue` is the single place a type slot's shape is decided.
  - **A parameter is labelled where it is written** (`label`, §5.10's shadowing rule over the source spelling): an
    unquoted parameter token in `value` is the field's `value_param`, a bare parameter name in `type` its
    `type_param`. The role beside it is the modifier's (`FieldModifiers`): `= P` is FIXED where it is written, and
    closing only substitutes the value. A positional field (`a => T`) is spelled out and labelled the same way, so the
    explicit form's three spellings of one field are one template field.
  - **A composition or refinement template is built one phase later**, as the *flattened* record template
    (`DefinitionResolver.Built`, over its own `BuiltField`). Both absorb fields from a source, and a §5.7 tightening entry
    states a modifier and no type-ref, so it is not a field at all until the inherited field supplies one. That needs a
    namespace this phase does not have, so the flattening happens in the resolver, which labels each entry the declaration
    writes as it resolves it and writes no wire form for the template at all.
  - **§5.11's uniqueness rule is asked here as well**, and asking it twice is not duplication: the resolver's
    copy sees a closed record body and this one sees a template's, which after normalisation are two
    different phases. Left to the constructor's own reader instead, the wire form carries one name twice as
    a key of `fields`, and the map's duplicate-key refusal would name neither the declaration nor which way
    the name was repeated — a field twice, or a group member repeating one.
