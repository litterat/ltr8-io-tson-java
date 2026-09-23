# Held template bodies

What an open entry's body is — text, carried by the kernel's `template` constructor (`schema.meta.TemplateBody`,
`HeldBody`) — and how each held shape closes: a record template, an open instance, an alias, an application at a
container position; with the synthetic merge and parameter kinds that closing relies on. Current form only; history
lives in git.

**Invariants**

- Every open entry's body is held (a `TemplateBody`), an error placeholder included (`WireForm.heldEmptyRecord`);
  `close` dispatches on the constructor head — `record` closes to the instantiation, `reference` to a name, everything
  else to a synthetic — and any other body shape is an `IllegalStateException`.
- A held body is text; the parsed form is a working value that is never retained, and nothing outside `resolver` sees a
  `DataValue`.
- Substitution is one rule at every depth: an **unquoted** token in a tree, rewritten when its text resolves into the
  entry's `parameters` (§8.1's shadowing rule); a quoted token is a literal.
- §5.7's fixation happens at closing (`fixRoutedValues`): a routed `= P` arrives required and FREE and becomes
  optional and FIXED; a `~ P` arrives a DEFAULT and stays one.
- Applications inside a held body close before the entry is named, and in the synthetic merge the closed-record name
  wins; only a form whose binding held an application moves.
- A closed form is named for itself through `DerivedName.ofBinding`, the application gets a second `Reference` entry,
  and a generated head mints none.
- Both closure paths share one memo; a self-applying alias is reported as a cycle, not tied as a knot.
- A container position closes in three ordered steps: replace the `param` bindings, bind the parameters inside an
  application a binding holds, then close what results.

Related: `design/template-materialisation.md` (the pass itself, kind checking, regularity),
`design/resolver-vocabulary-and-bootstrap.md` (`WireForm`, `DerivedName`, `@synthetic`), `design/schema-resolution.md`
(`holdIfOpen`, marks spliced into a held body), `design/constructor-application.md`,
`design/schema-grammar-and-desugaring.md`, `design/linking-and-compilation.md`.

## Held bodies and how they close (`schema.meta.TemplateBody`, `tson-compiler/.../resolver/HeldBody.java`)

- **A held body is text, and the kernel's `template` constructor is what carries it.** `schema.meta.TemplateBody`
  is a record over `parameters` and `template`, the application as written — so `set` resolves to
  `body: !template { parameters: [T]  template: "!set_type { element_type: T }" }`. It is an ordinary body of
  the value model: `Top` is sealed over it, the binder finds it by the same `template` → `template_body` alias
  `record` → `record_body` already uses, and `type_definition.body` is a `top` with no exception.
  - **Why text and not a value of the constructor's own vocabulary.** A parameter stands wherever a token
    stands — `element_type: T` in a type slot, `min_items: N` in a value slot, `variants: [T error]` inside a
    collection — so a body carrying one is typed by no constructor's record shape until it closes. Writing it
    as though it were leaves the two halves disagreeing, and both failures are measurable: `min_items: N`
    refuses to read at all against `non_negative_integer`, and core.tn's own `extern_of` reads *cleanly* and
    binds `S` as a schema identity literally named `S`. `OpenEntryResolvedFormTest` pins both.
  - **The parsed form is a working value, not part of the entry** (`HeldBody`). `HeldBody.held(parameters, ast)`
    emits the text through `TsonObjectWriter`; `HeldBody.of(body)` parses it back for the phases that need a
    tree. Nothing outside `resolver` sees a `DataValue`, and nothing is retained: template closure is the one
    caller that needs the tree twice, and it parses once into a local and drops it. `held` hands back no tree
    of its own, so a writer/parser disagreement about §5.10's one spelling fails in `HeldBodyTest` rather than
    surfacing later as two entries that ought to be equal and are not.
  - **Which is also why comparison needs no channel of its own.** §5.10's one-spelling rule is about the
    application, not the whitespace around it, so `ResolvedForm` reduces a held body to its *parsed* form and
    an open entry compares like every other entry.
- **A held body closes by one process, whatever wrote it** (`closeHeld`). `<T> [T]` and `<T> { x: T }` are both
  an application with a parameter standing in a slot — `!array { element_type: T }` and
  `!record { fields: [ { name: x  type: T } ] }` — so both substitute by the same walk and are then bound
  through **their own constructor's compiled reader**, the same one a written `!array { … }` or `!record { … }`
  binds through. Once its parameters go concrete a held body is no longer a template at all, but the
  constructor body those bindings always described, and the entry carries an ordinary
  `ArrayBody`/`MapBody`/`RecordBody`.
  - **What differs between the shapes is only what the result *is*.** A **record** template's closure is the
    instantiation entry itself (`closeHeldRecord`): a substituted record is the type the author named by
    writing the application, so there is nothing for an extra hop to record and the entry carries the
    application in its own `source` the way §8.2 says every instantiation does. Every other held form closes
    to a **synthetic** named for the form, which the instantiation then references — a form has no
    author-written name for identity to key on. That is the whole of the divergence; everything before it is
    shared.
  - **There is no other body shape.** Every open entry's body is held — an *error
    placeholder* included, which holds an empty record (`WireForm.heldEmptyRecord`) rather than being the one
    parameterised `RecordBody` in the system. A placeholder of that shape would oblige `TemplateMaterialiser`
    to keep a general substitution over *resolved* bodies beside the held one, to serve an entry with no
    fields to substitute into. Holding it makes `close` total over held bodies, with any other shape an
    `IllegalStateException` naming the invariant.
  - **§5.7's fixation happens here** (`fixRoutedValues`), which is what a held record body's retirement of
    the single `value` channel costs and where §5.7 says to pay it: a field routed by `= P` is held required
    and FREE with the parameter standing in `value`, and a FREE field carrying a value is that and nothing
    else — a closed FREE field has none. Closing makes it optional and FIXED, what the literal spelling gives.
    A `~ P` default arrives as a DEFAULT and stays one: data may still override it.
  - **Substitution is one rule, at every depth.** The body was never read against constructor vocabulary, so
    a parameter in a slot, one inside an application a slot holds (`tree<p0>` becoming `tree<text>`), and one
    inside a collection are the same thing here: an unquoted token in a tree, rewritten when its text resolves
    into the entry's `parameters` (§8.1's shadowing rule). **A quoted token is a literal and is never
    rewritten** (`WireForm.substitute`, and `HeldBody.names()` returns exactly the tokens it would rewrite) —
    which is what lets a body state the string `"T"` beside the parameter `T`, and why the held-record
    writers emit unquoted tokens. The slot's *kind* is what does not enter into it: a held body needs no
    `param`/`value` label, which is how §5.10 can state "substitution is one rule at any depth" with
    collection-valued slots included.
  - **Applications inside it close before the entry is named**, which is what keeps one type on one entry:
    the desugar phase lifts innermost-first, so a form it writes already names the entry its inner form
    became, and a form closed here has to agree or `[[pixel; 3]; 3]` written out and `grid<pixel, 3>` closed
    would be two entries for one type.
  - **The desugar channel cannot always reach that rule, and `SyntheticMerge` is where the two meet.** Both
    channels name a form by one function of one thing — the binding record with every inner form reduced to
    its entry name. Closing here satisfies it always; lifting innermost-first satisfies it for a nested
    *sugar* form and cannot for a nested *application*, `box<text>` having no entry until this pass runs. So
    a form lifted eagerly with an application in a slot is named from an unreduced record, and `[box<text>]`
    written directly would land apart from `[box<T>]` closed with `T := text` — §8.2's own example, and the
    split it calls the merge pass mandatory for. `SchemaResolver` re-derives each such form through
    `TemplateMaterialiser.closedFormName` after materialisation (the moment §8.2 names: "identity settles
    after Pass 2"), rewrites references onto the closed-record name and drops or moves the eager entry.
    **The closed-record name wins**, being a function of the resolved form alone — which is what makes two
    schemas reaching one form by different spellings agree on it, where the eager name or the smaller of the
    two would make an entry's name depend on what appeared beside it. Only a form whose binding held an
    application moves; every other synthetic re-derives to the name it already has.
  - **An argument is classified by the parameter it binds** (`ParameterKinds`, §5.10's "two parameter kinds,
    inferred by use"). §12.1 decides the channel by token shape, so an unquoted non-numeric argument arrives
    as a reference — the right default with nothing else known, and wrong for `e => <M> !enum { members:
    [a b M] }` applied as `e<c>`, where `c` is a member. What settles it is the **declared type of the slot
    the parameter stands in**, read from the constructor's own vocabulary: `type_ref` gives a TYPE parameter,
    a slot resolving to an `Atom` (which covers `identifier`, `value` and every enum) a VALUE parameter.
    §9 makes that general rather than a table of kernel names — a slot holding a type reference MUST be typed
    `type_ref` — so an extension meta-schema's constructors classify by the same walk.
    - **A fixed point, not one walk.** meta-kernel's own `type_argument` puts a parameter of *either* kind on
      the reference channel ("parameters ride the reference channel because a token there is always a
      reference"), so a parameter passed to another template says nothing locally: it takes the callee's kind
      at that position, and two templates may wait on each other. A parameter the fixed point leaves
      undetermined is a TYPE parameter (`ParameterKinds.groundRemainingAsType`), as §5.10 states: a value
      parameter is one standing in a scalar slot, so a parameter with no concrete use anywhere in its cycle
      cannot be one. `loop => <T> loop<T>` is then judged on what is wrong with it — it applies itself forever.
    - **Two declaration-time verdicts fall out**, neither of which has to wait for an application: a
      parameter standing for a whole collection or record (`<T> !enum { members: T }`) is neither a reference
      nor a scalar, and a parameter standing in both kinds of position (`<T> { v: T  w: int32 ~ T }`) has no
      argument that could satisfy both.
    - **An application closed on demand infers its own template.** A composition supertype and a refinement
      source close during resolution's driving loop, before the batch pass can run; the template in hand has
      resolved by then, which is all the walk needs, so only a parameter awaiting the cross-template fixed
      point is left undetermined there.
    - **The slot types are the constructor's own vocabulary.** `array.element_type` is typed `type_ref`,
      `enum.members` a set of `identifier`, `record_field.value` a `value` — so a `type_ref` slot gives a TYPE
      parameter, one resolving to an `Atom` a VALUE parameter, and anything else (a parameter standing for a whole
      collection or record) is refused at the declaration, along with a parameter standing in both kinds of position.
      §5.10 has an argument "read by the position it lands in", and the kind is what makes that position known at the
      application.
  - **That is where §8.2's deferred value-level check lands**, and it needs no code of its own:
    `<N> [text; N]` is a fine declaration, `<"two">` is where it stops being one, and the reader reports it
    (`'two' is not a valid integer`) exactly as it would for a written body. The split — binding names,
    REQUIRED coverage and concrete typing at the declaration; what substitution supplies, here — is the
    whole of it.
  - **The form is named for itself, not for the application** (§8.2). An open synthetic's own name is
    internal and derived, so keying its instantiations on it would make identity depend on an unstable name
    — and would leave `[text]` written directly and `[T]` closed to `text` on two entries for one type. Both
    go through `DerivedName.ofBinding` over the same binding record, so the two channels dedupe
    against each other and the closing usually finds the entry desugaring already injected.
  - **So the application gets a second entry**, a `Reference` to the form whose `source` is the application
    itself. One entry cannot carry two identities: the closure is a closed synthetic *and* an instantiation
    of the template, and §8.2 keys those on different things. Without it nothing in resolver output records
    that `grid<pixel, 3>` was written — the field would name the array and the template's name would vanish.
    The record shape needs no second entry, since substituting a record yields a record, structurally
    distinct from any synthetic.
    - **A generated head mints none.** Closing `array_p0_…<pixel, 3>` is an open synthetic closing its own
      intermediate form; nobody wrote that application, and an entry named for it would carry an internal
      name into identity. `SchemaResolver` tells the two apart by the plainest fact available — the
      difference between the declarations the author wrote and the ones desugaring added.
  - **Both closure paths share one memo**, so a template that applies itself (`weird => <T> [weird<T>]`) ties
    the knot on the entry under construction. An open instance goes through the memo and the depth backstop
    like a record template; short-circuiting ahead of them would make that spelling a `StackOverflowError`.
- **An alias holds its body too, and closes by composing rather than minting an entry.** §5.10's *partial
  application* — `uuid_pair => <B> pair<uuid, B>` — is a declaration whose whole body is an application some
  of whose arguments name parameters it re-declares, which makes the alias itself a template. §8.1 says that
  body denotes `!reference { target: pair<uuid, B> }`, and `SchemaDesugarer` writes it there — spellable
  because `reference.target` is a `type_ref`. So it substitutes by the same token walk as every other held
  form; applying it binds the arguments into that inner argument list and closes what results, so
  `uuid_pair<int32>` *is* the entry `pair<uuid, int32>` written directly denotes.
  - **`reference` is the one head materialisation dispatches to a name rather than an entry**
    (`closeHeldAlias`). The first two steps are shared — substitute, then close the application in the slot —
    and what differs is that there is nothing left to build. That is also why `close` tells the three cases
    apart by the constructor head: the body shape does not distinguish them, every open entry's being held.
  - **`reference`'s kind is not a base kind**, so `DefinitionResolver`
    dispatches the head instead of judging it by the generic `!C value` rule: §4.1 gives an alias
    `kind: REFERENCE`, a derived kind (`TypeDefinition.kind`, this resolver's `@Unbound` component — the
    kernel declares no `type_kind`) with nothing in the supertype chain to supply it, because `reference`
    describes no value. The
    binding check still runs — `reference`'s vocabulary is a record like any other. §5.10 is explicit that this mints no
  intermediate entry per
  alias hop, so a chain of aliases collapses and the origin survives only in the composed entry's own
  `source`. The degenerate spelling closes the same way: `ident => <T> T` applied to `text` is `text`.
  - **A self-applying alias is the author's error, not a knot.** The knot-tying memo answers a recursive
    application with the name of the entry under construction, and this path constructs none — so
    `loop => <T> loop<T>`, applied, would hand a field a name nothing ever defines. A second set tracks
    reference-template applications in flight and reports the cycle instead — routing has no entry to tie a
    knot through. Left unapplied, the declaration is caught
    earlier still, by `TypeInhabitance`.
  - **Arity is the alias's own**, checked against its `parameters` before any composition, so
    `uuid_pair<int32, text>` names `uuid_pair`'s one parameter rather than `pair`'s two. An unused one is
    the linker's existing §5.10 check, which reads a `Reference` body like any other.
- **An argument keeps the channel it was applied on.** An argument list is the one position where a type and
  a value are equally at home, so a value parameter passed straight through (`array_p0_…<N>` inside
  `<N> { a: [text; N] }`) stays a value. A parameter anywhere else is a type by construction, and a value
  arriving there is the kind error above (`design/template-materialisation.md`).
- **An application in a container position closes here too, and needs no name before it does.** An open
  binding holds a `type_ref` whole, arguments intact, so `[tree<T>; 1..]` inside `tree` lifts to a synthetic
  whose own `element_type` is `tree<p0>`. Closing runs in three steps and the order is the whole of it:
  replace the `param` bindings, bind the parameters *inside* an application a binding holds, then close what
  results. Closing first would close `tree<p0>` — an application of an argument nothing supplied.
  - **That is what ties §8's `tree` knot.** Closing `tree<text>` reaches the synthetic, whose binding reaches
    `tree<text>` again and finds it in `closing` — so the synthetic's `element_type` names the instantiation
    entry, recorded before that entry completes. The array is reached *through* the record and names it back.
  - **An application in a binding closes at the declaration when it can.** One naming none of the template's
    own parameters (`<N> !array { element_type: box<text>  min_items: N }`) is fully bound already, so
    `DefinitionResolver` closes it on the spot, the same treatment a composition supertype gets. One naming a
    parameter is carried open — closing it there recurses through the very entry being resolved, which is the
    shape `tree` takes.
  - **A closed container position takes one too** (`[box<text>]`). Its slot is written in `type_ref`'s record
    form, so the entry the desugar phase injects names something that is not an entry yet — and the batch pass
    here closes it, the same walk that closes every other ref. Nested arguments need no separate handling,
    since `close()` already builds `pair<int32>` before `box<pair<int32>>` names it. The wire hop rests on
    `type_argument` — an untagged labelled choice — being readable, value channel included
    (`design/class2-compilation.md`).


## What a held body admits: collection-valued slots, container positions, value arguments

§5.10 substitution works for both template shapes: a **record** template (parameters occupying field types and
values) and an **open instance** — `<T> { v: [T] }`, or the explicit `<T, N> !array { element_type: T
min_items: N }`. An open instance's body is **held** as text — the application as written, unread
until materialisation substitutes its parameters away (`schema.meta.TemplateBody` carries it, `HeldBody`
parses it)
— which is what makes §5.10's "collection-valued slots are parameterizable" work: `result => <T>
( T | error )` (the spec's own example), `<T> [T, text]` and `<T> { v: (T | text) }` all resolve. A
container position holding an
application works too: the binding keeps the `type_ref` whole, so `tree => <T> { value: T  children:
[tree<T>; 1..] }` ties its knot through the lifted synthetic. A *closed* container position takes one as
well (`[box<text>]`, nested arguments included): the slot is written in `type_ref`'s record form and
materialisation rewrites it to the instantiation entry one pass later, which rests on `type_argument` — an
untagged labelled choice — being readable (`GroupUnionBindReader`). **A collection-valued position is
no different** — `( box<text> | int32 )` and `[text, box<text>]` write the same record form into
`variants`/`elements`, closed or open, because a `[type_ref]` holds what a `type_ref` holds. What remains is narrower:
a *value*
argument keeps its token, so `[vector<float32, 3>]` closes to a nested array with both bounds at 3
(`RawTokenParser`); §4.3's equivalence is applied where identity is derived (`NumericIdentity`), so `<255>`
and `<0xFF>` are one application while `1` and `1.0` stay two, §4 resolving them to different base types —
§8.2's rule exactly, recorded as written and compared as the value denoted.

**Every template holds its body**, so one process closes them all.
§5.2 says `{ x: T }` denotes `!record { fields: [ { name: x  type: T } ] }`, and `SchemaDesugarer` rewrites
it there, where the body is written; a **composition or refinement** template is held one phase later
(`DefinitionResolver.holdIfOpen`), because both absorb fields from a source and the form to hold is the
*flattened* one — but through `WireForm.heldRecord`, so two producers of the wire form share one
spelling. **An alias is written the same way**, and is: `uuid_pair => <B> pair<text, B>` leaves the desugar
phase as `<B> !reference { target: pair<text, B> }`, spellable because the kernel's `reference.target` is a
`type_ref`. So **every** open entry's body is held, with no exception — which is what lets materialisation
dispatch on the constructor head (`record` closes to the instantiation, `reference` to a name, everything
else to a synthetic) rather than on what shape the body arrived in.
The kernel has no `record_field.value_param`, `instance_template` or `template_argument`: a
routed parameter rides `value` with §8.1's shadowing rule to tell it from a literal, and §5.7's fixation
happens at materialisation. What a held body cannot enforce is half of §5.10's argument-kind rule —
`design/template-materialisation.md`, under kind checking.
