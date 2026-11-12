# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation. See `CLAUDE.md`
for why this file exists and when to add to it. Spec quotes below are from 2026 Revision 39 — Part 1 and Part 2,
the working copies in `spec/`, which publish at https://tson.io/raw/2026/39/tson-part1-data.md and
https://tson.io/raw/2026/39/tson-part2-schema.md — unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a suggested
resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a revision
closes.** It is an input to the next revision's adjudication, so its numbering is the numbering that revision's
change log will answer against — a stable index of the open set, not an archive of everything ever raised.

**Revision 39 closed every entry open against Revision 38; the entries below are open against Revision 39.** The
closed entries are gone, and the change log in `spec/` keeps them under their numbers: the spec now carries their
rules — one namespace per schema, one `!!import` directive and the root's `!schema` tag saying the kind; resolution
order within a schema; the roots holding the common vocabulary and every `!schema` value declaring from the root; `!`
marking an instance and `^` refining any value; value entries as ordinary values and `namespace` building map types;
the alias body; one reference atom, a reference in data as grammar and an application as a reference value; a count as
a 64-bit range; a facet typed by its family's instance; a restated field narrowing by IS-A; a record template as its
own constructor; an applied parent's edges; dependent fields; the `type-expr` and `type-ref` productions; a float's
ordered bounds; and the two identities. What the implementation still has to build for them is in `BACKLOG.md`. **This
file is the as-built record**, not a pointer to one: where an entry proposes a design this implementation has built,
the entry states the design, what is running, and what is not, so that a reviewer editing the spec needs nothing
beside it. **Where the evidence is a consumer of this library rather than this library** — the HTTP layer in
`ltr8-io-tson-java-http` is one, and this register is the collection point for all of it — the entry says so and
states what is running there on the same terms.

**Part 3 is edited, not registered.** [TSON-JSON] is an early draft this implementation exists to validate, and
`spec/tson-part3-json.md` is edited **directly** as findings arise — so a Part 3 finding becomes a spec change in
the same session, with git history as its record, rather than an entry waiting for adjudication. A Part 3 entry
appears here only where the finding is a choice between the text and the code that the author has to make, and it
closes by the edit or the code change. What stays here otherwise is Parts 1 and 2, whose current revision is
published and whose changes this implementation proposes rather than makes. An entry spanning both stays, and says
which half is which. **Numbers are not reused and gaps are left** while a
revision is open — the register renumbers only when one closes, so a citation written against the open set stays
valid until then.

**Cite the spec, not the argument that got it there:** `design/` and the Javadoc name the section that requires a
behaviour, and a `SPEC-FEEDBACK.md #N` citation is for an entry below, where there is no section to point at yet.
When an entry closes, its citations become spec citations and the entry is deleted — nothing here is an archive.

---

## 1. [TSON-DATA] §2.1 — the worked example says untagged tokens resolve by §4's base rules under an imported schema

**Problem.** §2.1's example document imports `order.tn` (`!!import`), and the paragraph after it says type
annotations "invoke the built-in vocabulary — `!uuid`, `!date`, and `!number` parse their tokens by atom contract
(§3.2, §5) — while unannotated tokens resolve by the base rules of §4: `1042` and `0b0110` are integers, `.5` is a
float, and `GOLD` and `A-100` are strings". Both halves contradict the rest of the revision for a document with an
`!!import`: §4 applies base resolution only "at a position nothing types — a document whose header carries no
`!!import`", §5.1 confines the built-in vocabulary to schemaless documents ("Under a schema … the vocabulary does
not apply: all type annotations resolve through the schema's namespace"), and [TSON-SCHEMA] §7.3 reads every token
by its position's declared type. The same paragraph's earlier sentences already say the right thing — every type a
document names comes from the one namespace its imports bind — so the sentence reads as a Revision 38 survivor.

**What is running.** §4, §5.1 and [TSON-SCHEMA] §7.3: under an `!!import` a tag resolves in the document's namespace
and a token is read by its declared type, so `!uuid` in the example works because `order.tn`'s closure (through
`core.tn`) declares `uuid`, not because the built-in vocabulary applies.

**Resolution to decide.** Rewrite the sentence for a document under a schema: the tags name types of the imported
namespace (`order.tn`'s closure supplies `uuid`, `date` and `number`), and each untagged token is read by the type
its position declares — `1042` as the field's integer type, `GOLD` as an enum member — with a pointer to §4 and §5.1
for what the same tokens would mean in a document with no `!!import`. Nothing in the normative rules changes.

## 2. [TSON-DATA] §2.4, §7.4, [TSON-SCHEMA] §5.3 — whether a quoted token at a reference-typed position is a reference

**Problem.** Two rules answer this oppositely, and neither names the case.

- *Form is not meaning.* §2.4 says a token's form "is consulted exactly once" — by base type resolution, and at a
  disjoint choice — and that "two tokens with the same text denote the same value regardless of form — identity is
  text". §5.2 says the same of every atom ("Content expressible unquoted may be written either way"), and a
  reference is an atom (`meta.reference_type`). By these, `t: "int32"` at a field typed `meta.type_ref` is the
  reference `int32`.
- *A quoted token is never a name.* §7.4's grammar defines `qualified-name-token = unquoted-token` and says, at
  `type-arg`, "A quoted token is never a reference", and at `sibling-ref`, "A quoted token is never a sibling
  reference"; [TSON-SCHEMA] §5.3 makes a quoted argument a value argument ("Number and quoted-string arguments are
  unambiguously values"). By these, a quoted token is text wherever a reference is read.

The grammar comment on `application` is where the case would be settled and is not: "A qualified-name without
arguments is a token: one value, read as a reference by a position typed by one and as a token elsewhere" leaves
open whether a quoted token whose text matches `qualified-name` is that token. The note on `qualified-name-token`
says only that "at a tag and an annotation the spelling is an unquoted token", which reads as if other positions
might differ.

**What is running.** The two paths of this implementation answer differently, which is the evidence that the text
does not decide it.

- *A schema document refuses the quoted form* at every reference position it reads: a field type (`{ b: "text" }`,
  "expected a type reference, found the quoted token 'text'"), a template argument (`box<"text">`, "'text' is no type
  argument: an argument is a type, written as a reference"), and a constructor body's reference-typed slot
  (`!meta.array { element_type: "text" }`). On the Revision 40 proposal branch an argument is a reference in both
  grammars (#3), so [TSON-SCHEMA] §5.3's quoted value argument is gone and a data document refuses `box<"text">` too.
- *A data document accepts it* at a field typed by a reference: with `s => { t: meta.type_ref }`, `!s { t: "int32" }`
  is valid, and with `s => { t: meta.ref<meta.record> }` and `r => { x: text }`, `!s { t: "r" }` is valid and
  `!s { t: "no_such" }` is refused as not a member — the token's text is resolved as a name.

**Resolution to decide.** The proposal is that a reference is an **unquoted** token at every position, in data and
in schema documents alike: state in §7.4 that a quoted token is never a reference wherever a reference is read (not
only inside an argument list), and add a reference position to §2.4's list of the places where form is consulted —
it decides whether the token can be a name at all. That keeps one spelling per reference, matches what a schema
document already does, and keeps a quoted token's meaning (text) the same at every position. The alternative — form
is not meaning here either, so `"int32"` is the reference — should then be stated in §7.4 and [TSON-SCHEMA] §5.3,
since both currently read against it, and the schema-document refusals above become errors in this implementation.
Until it closes, the implementation keeps the split described above.

## 3. [TSON-SCHEMA] §4.2, §5.3, §5.10 — templates as open records: a proposal for Revision 40, and what of it runs

**Problem.** §5.10 gives a template two representations and a parameter two kinds. A record template is its own
constructor and a family base; every other open entry is held as text, because a value parameter can stand wherever
a token stands, so a body carrying one is typed by no constructor's shape until it closes. The cost is the largest in
the series: held bodies, parameter types and bounds derived by a fixed point across templates, a head edge at a
closed operand, and a lookup that resolves an application written in *data* against the applications a schema closed
(§4.2). No bundled schema and no consumer schema measured declares a value parameter, a template over anything but a
record, or a template named bare at a position.

**The proposal**, in three sentences.

1. A template is an open record: `meta.record` carries `parameters`, each a type standing at a field's type or as an
   argument of an application there or in a parent, and nowhere else. A reference names an open record only as the
   head of an application.
2. `C<args>` over a constructor is construction sugar: `!C { f1: a1 … }` over its required fields typed by a
   reference naming types, in declaration order, as `[T]` and `{K => V}` already are.
3. At a reference slot of a schema document `H<args>` is a reference, substituting where `H` is an open record and
   constructing where it is a constructor. In a data document a reference is a name.

What follows from them: no value parameters, no derived bounds, no template named as a type, no template over a body
that is not a record, and no `meta.template` or `meta.record_template`.

**What is running** on the Revision 40 proposal branch, step by step; every step of the proposal runs.

- *§5.10, value parameters: gone.* A parameter is a type. It stands at a slot typed `meta.type_ref` or a refinement
  of it, or as an argument of an application; anywhere else is a resolver error at the declaration, naming the slot:
  a facet, a field's default or fixed value (a pin on a reference-typed field included), an enum member, a size
  bound, a field name, a modifier. An argument is a reference in both grammars: a number or a quoted token in an
  argument list is a grammar error, in a schema document and in data (`maxint<10>`, `box<"x">`).
  `meta.template_param` is `{ name, bound? }` and `meta.record_template_field` has no `value_param`. A value that
  differs per application is written where the application is declared,
  `created => response<order> & { status?: = 201 }`. A parameter spelled like a name an absorbed field's type
  writes is refused by the shadowing rule alone, every such name being an entry, and has no rule of its own.
- *§5.10, bounds: written, never inherited.* A parameter's bound is what its declaration writes, `<T: text>`, and is
  otherwise absent; nothing about a parameter is inferred from its uses, so a parameter is known in full at its
  declaration and no fixed point runs across templates. A parameter passed as an argument to a bounded parameter of
  another template is written with a bound that IS-A that one, wherever it is an argument: a field type, an
  application nested in one or inside container sugar (`[boxed<T>]`, the reference `meta.array<boxed<T>>`), an
  applied parent. With `boxed => <T: text> { v: T }`, `rebox => <T: text> { b: boxed<T> }`
  and `<T: non_empty_text>` resolve, `rebox => <T> { b: boxed<T> }` is a resolver error at `rebox` naming `boxed`'s
  `T: text`, and `<T: int32>` stays refused. An imported template's recorded bound binds the same way, being the
  one its author wrote. A parameter passed to a constructor's slot (`<K> { m: {K => text} }`) needs no
  bound: the slot judges the argument that arrives when the record is applied. A closed argument is judged against
  the written bound as before; one in an application closed while declarations are still being made ready is judged
  once every declaration is, since an argument is a name at a type position and orders nothing (§3.4.1), and is
  reported at the declaration that wrote it.
- *§4.2, an application in a data document: running.* In a data document a value at a reference-typed slot is a
  name. One carrying an argument list is a validation error whether or not the schema's closure closed that
  application, in both encodings: a constraint violation of the reference atom, whose message gives the remedy, the
  entry the schema declares for the application. A schema document and a resolved form still hold applications, the
  resolved form being read as one because its reader is told so. The lookup of an application in data against the
  closure's applications is gone, and a refusal's list of admitted names leaves out minted entries. [TSON-JSON]
  §1.6, §5 and §5.6 are edited to match. Left as it was: data may still write a minted entry's internal name.
- *§5.3, construction sugar: running.* At a type position of a schema document, `C<a1 … an>` where `C` resolves
  through aliases to a constructor, a closed record IS-A `meta.type`, is the construction `!C { f1: a1 … fn: an }`
  over `C`'s required fields typed by a reference naming types, in declaration order. `meta.array<text>` is the entry
  `[text]` is, and `meta.map<text int32>` the entry `{text => int32}` is. A declared `x => meta.array<text>` is that
  construction and carries no `@meta.application`. A refined constructor applied, `nonempty_type<uuid>` over
  `nonempty_type => meta.array ^ { min_items?: = 1 }`, is an entry of its own and not the one `[uuid; 1..]` is. A
  wrong argument count, an argument the field's `of` refuses (`meta.namespace<int32 text>`) and a construction left
  incomplete (`meta.enum_type<meta.identifier>`, with `members` unwritten) are refused. Three points the rule leaves
  open are settled as built: a field with a default is no argument slot; a use-site construction names its
  content-keyed entry and never a declaration of the same construction; and an argument is not followed through an
  alias, which is the sugar forms' identity. An open record is applied by substitution and never constructed,
  whatever its chain reaches.
- *§5.10, a template as a type: gone.* No position names a template bare: not a field, element, map key or value,
  tuple position, choice variant or argument, not a slot typed by a reference over types, and not an operand of `&`
  or `^`. A template named inside its own record body is refused too. Each is a resolver error at the declaration
  that wrote it, naming the application or the abstract record a family's applications compose as the remedy; a data
  tag naming a template is a validation error. An application's IS-A edges are its template's written parents': each
  closed parent, and the entry an applied parent denotes where one exists. The template's own name is never among
  them, a template is in no `subtypes` index, and no reference holds one. `abstract` and the selectors on a template
  are each application's, so `result => abstract <T> { ok: boolean =? }` makes `result<text>` an abstract record
  dispatching over the declarations composing it; a selector makes a template's applications abstract as it makes a
  record so. `final` on a template is a resolver error. A family is declared with records: an abstract base, a
  template composing it, and members composing an application that pin the selector. A use-site application whose
  template composes a declared type is still refused as an unnameable member.
- *§5.10, a template is an open record: running.* `meta.record` carries `parameters?: [meta.param; 1..]`, each
  `{ name, bound? }`; a record with parameters is open, a template, and the kernel declares no other template
  constructor and no template: `template`, `template_param`, `record_template`, `record_template_field`,
  `record_template_field_map`, `tuple1` and `voidable_tuple1` are gone, `meta.ref` is the refined constructor
  `meta.reference_type ^ {}` (#6) and the root's `set` an alias of `meta.set_type`, so
  `meta.ref<X>` and `set<text>` are constructions and carry no `@meta.application`. An open record is read at its
  declaration against `record`'s own vocabulary: a parameter is a reference value, written as its name and looked up
  in the record's `parameters` before the namespace, standing at a field's `type` or among an application's
  arguments there or in a parent, and a field's value, a field's name and the record's `name_type` take none. Its
  resolved form is `!meta.record { parameters: [ !meta.param { name: T } ] … type: T … }`; an application substitutes
  the parameters away, computes the chain from the substituted parents and closes to a `!meta.record` without
  `parameters`. A template's body is a record body -- braces, a composition or a refinement, the explicit
  `<T> !meta.record { … }` among them -- and a parameter list before anything else is a resolver error at the
  declaration naming the remedy: before a container constructor or container sugar (`<T> !meta.array { … }`, `<T> [T]`),
  the application at the use site or a refined constructor applied; before a choice or a tuple, a discriminated
  record family or a field group; before a value (`<T> !api { … }`), one value per instantiation; before a bare
  reference (`<B> pair<uuid, B>`, `<T> T`), no alias template. Inside an open record a form over a parameter is
  admitted only where it is a reference: `[T]` and `{K => V}` are the references `meta.array<T>` and `meta.map<K V>`,
  held as written until the record is applied and then constructed; `[T; 1..]`, `[T?]`, `{K => V; ..10}`, a choice and
  a tuple over a parameter are each a resolver error at the declaration naming the form, a choice's pointing at field
  groups. Nothing is lifted for a form over a parameter, so there are no open synthetic entries. Writing `parameters`
  as a binding of a construction of `record` is a resolver error: parameters are the list before the body. The
  kernel measures 81 entries, one wait cycle of 25 holding `meta.record`, a closure of 69 with `meta.param` and
  `meta.param_name` in it, and six synthetic entries.

**Resolution proposed.** In §4.2, replace "and in a data document an application at a reference-typed slot names the
entry the closure closed it to … a schema closing every application it holds before it loads" with "and in a data
document a reference is a name: an application is a validation error". To §5.3's sugar table add "`C<a1 … an>`,
`C` a constructor: `!C { f1: a1 … fn: an }`, its required fields typed by a reference naming types and not pinned by
its chain, in declaration order", saying whether a field with a default is one of them; in §5.6 say the positional,
braced and applied spellings of one construction are one entry; and in §8.2 scope the clause comparing an
application's arguments past their aliases to substitutions. For value parameters: replace §5.10's
"A parameter's type is derived …" through its narrowing rules with "**Parameters are types.** Every parameter stands
for a type: an argument for it is a reference, a name or an application, and a parameter stands only at a field's
type, or as an argument of an application there or in a parent. A parameter anywhere else is a resolver error at the
declaration, naming the slot"; replace §5.7's "Open modifiers" with the rule that a value differing per application
is written where the application is declared; delete §8.2's "Value arguments compare as values"; end §5.3's size
bound at a decimal integer; make §12.1's and [TSON-DATA] §7.4's `type-arg` a reference; and rewrite §1.6's worked
example, whose `flagged<status, 2>` carries a value argument. For bounds: replace §5.10's "A bound is inherited"
paragraph with "**A bound is written.** A parameter passed as an argument to a parameter that has a bound MUST be
written with a bound that IS-A it: `rebox => <T: text> boxed<T>`. `rebox => <T> boxed<T>` is a resolver error at
`rebox`. No bound is inferred, so a parameter is known in full at its declaration", and delete the paragraph on an
argument to another template settling by a fixed point and the "derived, never required of the author" framing of
the parameter paragraph. For a template as a type: replace ¶999 with "**A template is not a type.** No position
names a template bare — a field, element, variant or argument position or an operand of `&` or `^` — and an
application of one is a type: the declaration naming it where one does, its expansion otherwise (§8.2). A family is
declared by records: an abstract base states the selectors and the fields its members share, a template composes the
base, and each member composes an application and pins the selectors. An application's IS-A edges are its
template's written parents' — each closed parent, and for an applied parent the entry its application denotes — and
never the template's own name. `abstract` and the selectors on a template are each application's `extension` and
`discriminators`, a selector making the application abstract as it makes a record so; `final` on a template is a
resolver error", and delete the family/abbreviation split in ¶952, ¶1001's "a family base is reached by a position",
the record-template half of ¶1003 and of §8.1 ¶1457, and §4.2's `kind<record_template>`.

For a template as an open record, replace ¶954, ¶958 and ¶1003 with: "**An open record is a record with
parameters.** `<T, U: bound>` before a record body -- braces, a composition or a refinement -- declares its
parameters, each a name standing at a field's type, or as an argument of an application there or in a parent, and
nowhere else: a field's value is never a parameter. There a parameter is a reference value: inside an open record a
reference is a name, an application or one of the record's parameters, looked up before the
namespace (the shadowing rule). The slot judges the argument that arrives there when the record is applied. So the
body is read at the declaration against `record`'s own vocabulary. A reference names an open record only as the head
of an application (§5.10, "A template is not a type"); only `record` carries parameters. **At a reference slot of a
schema document, `H<args>` is a reference.** Resolving it substitutes where `H` is an open record -- binding the names
it writes and filling the slots they mark -- and constructs where `H` is a constructor (§5.3). It supplies every
argument, so there is no partial application, and `void` is an argument like any type. In a data document a
reference is a name, and an application is a validation error (§4.2). With every argument closed it resolves at
once, to the declaration that names it or its expansion (§8.2); with a parameter among its arguments it is held as
written until the open record holding it is applied. The result is a value of the body's constructor and a type. Its
IS-A edges are a substituted record's parents, or none for a construction; the head is never one. An argument is
judged against the parameter's written bound, or for a constructor's field against the slot's `of`. **No other form
holds a parameter.** Inside an open record, a sugar form over a parameter is the reference its construction is
written as (`[T]` is `meta.array<T>`, `{K => V}` is `meta.map<K V>`). Any other form over a parameter is a resolver
error at the declaration. Container sugar carrying a size or a `?` -- `[T; 1..]`, `[T?]` -- sets a field no argument
list carries, so §5.3's freedom of position for those forms stops at a parameter. A choice or a tuple -- `( T | error
)`, `[T, text]` -- is not written as a reference over reference fields, and an untagged sum over a parameter cannot be
judged disjoint until it is applied (§5.4). A labelled alternative inside the record is a field group: `( body: T |
problem: problem )`." Replace ¶1017-1024 with: "**No alias templates.** A template's body is a record body; a body
that is a bare reference -- `<B> pair<uuid, B>`, `<T> T` -- is a resolver error. A use site writes the application it
means." Then delete ¶986 ("Declaration-time checking splits …") with its stand-in-value prohibition, most of ¶988
("Where a deferred diagnostic lands"), the example block at ¶994-997, §8.2 ¶1535's "parsed held body" and its
identity clause for open synthetic entries, §5.3's open half of the lift rule, every mention of `meta.template` in
§8.1, and narrow §12.1's `type-def` alternative `[type-params] type-expr` to a record body; and in §13.1 and the
kernel's companion text replace `template`, `template_param`, `record_template`, `record_template_field`,
`record_template_field_map`, `tuple1` and `voidable_tuple1` with `record.parameters` and `param`, `meta.ref` with the
refined constructor and the root's `set` with an alias of `meta.set_type`. The spec's own examples that need a record
body move to one: `result => <T> ( T | error )` becomes a discriminated family (`result_of => <T> result & { value?: T
}`), and §5.10.1's and §8.2's `nested => <T> ( T | [nested<T>] )` becomes a record, `nested => <T> { value?: T  more?:
[nested<T>] }`.

## 4. [TSON-SCHEMA] §5.4, §5.2 — a tag at a choice names a variant; a choice with an abstract variant has no values

**Problem.** §5.4's tagging rule says a value at a choice "carries a type annotation (`!variant`) selecting the
variant", and §5.2's ABSTRACT rule refuses a tag naming an abstract record itself: a value at a position typed by it
is a value of some subtype. Nothing says what a tag naming a **subtype** of a variant selects. Read as written, the
tag names a variant exactly, and the two rules together leave an abstract variant without values:

```
shape  => abstract { name: text }
circle => shape & { r: integer }
item   => ( text | shape )
```

At a position typed `item`, `!shape { … }` is refused because `shape` is abstract, and `!circle { … }` is refused
because `circle` is no variant of `item`. The choice derives `disjoint: true` (string-class beside brace-class), so
the tag MAY be omitted, but an untagged record at an abstract position has no member to be read as unless the base
declares selectors. At a position typed `shape` alone, `!circle { … }` is accepted (§7.2): a position typed by a type
admits a value of a subtype, and a choice over that type does not.

The same gap covers a concrete variant with subtypes: at `( text | base )`, a value tagged with a subtype of `base`
is refused, where a field typed `base` admits it.

**What is running.** The tag is matched against the variant names exactly. With the schema above, `!circle { … }`
at `item` is reported "'circle' is not a declared variant of 'item'", and `!shape { … }` "a tag naming 'shape'
selects nothing -- it is abstract and has no direct instances. Name the member this value is, one of (circle)" — the
second message recommends the tag the first refuses. Separately, and a gap of this library, not of the text: an
untagged value at a disjoint choice is recovered for scalar-class variants only, so an untagged record or list at a
disjoint choice is refused for want of a tag, where §5.4 counts "the brace/bracket delimiter" as recovering it.

**Why it matters now.** The inline-types proposal for Revision 40 types a type slot as a choice of a reference and
an abstract family, `( meta.type_ref | meta.type_inline )`, whose values are tagged by their constructor
(`!meta.array { … }`). That is this case in the kernel.

**Resolution to decide.** In §5.4: a tag selects the variant it names, or the one variant its type IS-A (§7.2), and
the value is then read at that variant as it would be at a position typed by it. Two points the rule has to state:

- **A tag that IS-A two variants.** With composition a type can reach two variants. Either the tag is refused at
  the data, or a choice whose variants share a subtype is a resolver error, as overlapping classes are for an
  untagged choice. A spike of the first reads cleanly: no variant is selected and the tag is refused.
- **Disjointness is unaffected.** `disjoint` is still derived over the variants' classes; a subtype has its
  variant's class.

The alternative is to keep the tag exact and refuse an abstract variant at the declaration, since it can hold no
value. That keeps one rule for a tag and rules out the kernel shape above.

## 5. [TSON-SCHEMA] §4.2, §5.3, §5.10, §8.2 — inline types: a type slot holds a name or a type written in place

**Problem.** One sentence of §4.2 — every slot that names the type of a value holds a bare name in a closed entry —
is paid for across the series. A container, tuple, choice or reference written at a use site, and every application
of an open record no declaration names, lifts to a resolver-materialised entry named by its content. That brings
`@meta.synthetic` and its recompute-on-ingest rule, internal names and a freshness rule for them, a merge of equal
lifted forms within a document and of equal expansions across imports, a self-reaching instantiation entry for a
recursive expansion, resolved forms compared "up to renaming of internal entries", a minted name a data document may
write as a tag and that fails at the next processor, and a way to recover the written form for every diagnostic. None
of it describes a value: two schemas that differ only in how their resolvers name lifted entries admit the same data.

**The proposal.** A type slot holds a name or a type written where it is used, and the kernel's type says so:
`type_inline => { of: meta.type }` and `type_expr => @meta.disjoint ( meta.type_ref | meta.type_inline )`, which
types every type slot, a record field's included (#6). `tags: [text]` resolves to
`type: { of: !meta.array { element_type: text } }`; a refined constructor's construction carries its own tag
(`!meta.set_type`, `!meta.ref`). The slots are a record field's `type`, `array.element_type`, `map.value_type`,
`tuple_element.element_type` and each of `choice.variants`. What may be written in place is a container, a tuple, a
choice or a reference, or a construction of a constructor refining one, or a record: a record body written at the
slot or the record an application of an open record closes to, one form. An enum and every other atom are declared
and named, as §5.2 already requires. A map's key is a name. A type written in place is no entry: structural
identity (same constructor, same fields, names as the leaves, annotations taking no part), no chain, a member of no
kind, named by no tag.
Nothing is lifted, minted or merged, and `@meta.application` stays on a declared application's key alone. An
application of an open record is the substituted record under a declaration's name, absorbed by value at a
composition operand or refinement source, where an applied parent's edge is the one lookup of a declaration by its
application, and closed in place at a type slot, never looked up; recursion ties to the declaration being closed,
and a recursive application with none to tie to is refused. An argument of an open record's application is a name or
a parameter; a slot typed `meta.type_ref` holds a name and refuses an application. Three rules widen: a dependent
field's sibling may be typed by the type-slot choice, a constructor's argument slots include fields typed by it, and
the positional form of a one-field record admits a field typed by it.

**What is running** on the inline-types branch off the Revision 40 proposal branch, against the hand-written kernel
and resolved fixtures (`spec/m/meta.tn`, `spec/fixtures/meta-resolved.tn`, `net-resolved.tn`).

- *The kernel and the slots.* `meta.type_inline` and `meta.type_expr` are declared and the five slots retyped;
  `map.key_type` stays `meta.type_ref`. `@meta.synthetic` is gone. The bundled schemas resolve, link and compile, and
  `ResolvedFixtureTest` passes against the hand-written fixtures. The kernel's hand-built wait cycle is 31 entries:
  `meta.type_expr`, and through `of: meta.type` every constructor's record, `meta.record_extension_type` and `void`.
- *Sugar in place.* `[T; n..]`, `{K => V}`, `[A, B]`, `( A | B )`, `set<T>` and `meta.ref<T>` at a slot desugar to
  `{ of: !C { … } }`, nested forms nested, each construction carrying its constructor's tag. Nothing is lifted or
  merged, no name is derived from content, and no code that derived one remains. A user schema with inline forms reads
  data in tree and bind mode and through the JSON stack; the reader recovers an untagged value at a disjoint choice
  whose variant is a reference or a `{ of: … }` record.
- *Identity.* A type written in place compares by its constructor and fields, and a name and a construction are
  never one type: a restated field's narrowing refuses `text_list` restated as `[text]` and the reverse, and a choice's
  variant distinctness counts `text_list` and `[text]` as two variants. Nothing follows a name to its construction.
- *Values and choices.* A value modifier on an inline reference (`handler?: meta.ref<method> ~ orders.place_order`)
  is admitted and judged at link as a declared reference's is; one on any other type written in place is refused at
  the declaration. A dependent value whose sibling holds a type written in place is read against it in bind mode and
  judged at link in a schema document. A choice that is not disjoint with a variant written in place
  (`( [text] | [int32] )`) is refused, a tag naming a variant by its name.
- *Applications.* A declared application is its substituted record under its name, `@meta.application` on its key.
  An application at a composition operand or a refinement source is absorbed by value, an applied parent's edge
  naming the declared entry where there is one. At a type slot an application closes in place: `f: page<order>` holds
  `{ of: !meta.record { … } }`, the substituted record with no name, no chain and nothing recording it, never looked
  up to a declaration, and so does a nested application a closing leaves at a slot (`cursor: box<T>` once `page =>
  <T> { cursor: box<T> }` is applied). Two records in place are one type when their bodies are equal, whichever open
  records produced them; `f: page<order>` and `f: page_order` are two types of one shape, so restating one as the other
  is refused. An abstract open record applied in place closes to an abstract record nothing is a member of.
  Inside an open record an application over a parameter is held as written whatever its head, and a constructor's
  closes in place when the record is applied, one nested in another constructor's arguments included
  (`meta.map<text meta.array<T>>`).
- *Recursion.* A recursive application closing a declaration ties to it: `node_text => node<text>` over `node => <T>
  { value: T  next?: node<T> }` holds `next: node_text`. Mutual recursion through records in place ties the same way:
  closing `a_text => a<text>` over `a => <T> { b: b<T> }` and `b => <T> { a?: a<T> }` closes `b` in place, whose
  `a<text>` names `a_text`. Recursion is judged by closing: an application reached again inside its own closing in
  place, with no declaration closing it, is refused (`f: b<text>`, with or without `a_text` declared). An open record
  holding an application of a recursive open record it is not on a cycle of (`wrap => <T> { n: node<T> }`) could never
  close, and is refused at its own declaration, applied or not, judged over the open records' held heads once every
  declaration is ready; its appliers report nothing.
- *Records in place, read and bound.* A record in place reads in tree and bind mode and through the JSON stack, by a
  reader built from its body: the open record's field states, defaults, pins, groups, name type and dependent fields,
  each diagnostic located by its field path and naming the application as written. A value there carries no tag and
  admits none (a `$type` included). In bind mode it binds to the record class the component holding it declares -- at
  a field, an element, a map's value or a tuple's position -- no binding map being able to name it; a component that is
  no record class is a bind mismatch.
- *Arguments and names.* A sugar form, an applied constructor or an application of an open record passed as an
  argument of an open record's application is refused, at a closed record and, inside an open record, at its own
  declaration, naming the declaration to write (`text_box => box<text>`); an argument of a constructor's application
  inside an open record (`meta.array<box<T>>`) is held and closes at its slot. An application at a slot typed
  `meta.type_ref` — a map's key, a reference's `of`, a parameter's bound, an alias's target — is refused the same way.
- *Refused.* An application whose record in place would be IS-A a declared type (`k: dog_of<text>`, §5.2) is a
  resolver error at the declaration that wrote it or, held in an open record, at the declaration whose application
  closed it, judged before the record is closed. A recursive application with no declaration closing it (`f:
  node<text>`, §5.10.1) is one at the closed record that wrote it; held in an open record, the open record is refused
  at its declaration (*Recursion*, above).
- *A record body at a slot.* `at: { x: int32 }` parses at a field's type, a group member's, an array's element, a
  map's value, a tuple's element and a choice's variant, by the brace dispatch a declaration's body uses, `{}` the
  empty record, and desugars to `{ of: !meta.record { fields: { … } } }`, nested records innermost first. It holds
  what a declared body holds -- marks, modifiers, groups, dependent fields, annotations resolved in the schema's
  namespace -- and answers a declared record's checks at the declaration, its field names under its own name type and
  as a name-hygiene scope of their own; a selector makes it abstract, and so empty.
  It is the same type as an application closing to equal fields. It reads untagged in tree and bind mode and through
  the JSON stack, each diagnostic at its field path and its field's source line. A map's key, an argument and a
  parameter's bound refuse it; `at: point & { z: int32 }`, `^` and `-` at a slot are parse errors naming the
  declaration to write.
- *The three widened rules.* A dependent field's sibling typed by the type-slot choice, a constructor's argument
  slot typed by it (`meta.array<text>` constructs), and the positional `fields: { p => text }` all run.
- *Kinds.* A construction in place is a member of no kind; a kind over a refined constructor whose only member was a
  lifted construction now needs the member declared.

**What is not yet running.** A tree read reports a dependent value whose sibling holds a type written in place as not
implemented, holding the sibling as the record it is written as rather than as the type. A record written in place over
a parameter of the open record holding it (`page => <T> { meta: { cursor: T } }`) is reported as not implemented: the
open record's parameters are substituted at its own field types and the applications there, and not yet inside a
record in place.

**Resolution proposed.** The Revision 40 proposal text carries it, edited in place: Part 2 §1.3, §1.6, §2.2.3,
§3.3.3, §3.4.1, §4.1, §4.2 (the type-slot choice), §4.3, §5.2, §5.3, §5.4, §5.6, §5.7, §5.8, §5.10, §5.10.1, §5.12,
§6, §7.2, §8.1, §8.2 (rewritten as *Instantiation and Identity*), §8.3, §9, §10.1, §11.5, §12.1 and §12.2, and Part 3
§3.3; `spec/tson-rev40-changelog.md` records each section's rule before and after. Four points the
design left open are settled there as ruled: a refinement source's application is absorbed as an operand's is; a
value modifier on a field typed by a reference written in place is admitted and read against it, and refused on a
container, tuple or choice written in place; a choice that is not disjoint names every variant, a variant written in
place there being a resolver error; and a name and a type written in place are never the same type, even where the
name's construction is identical.

## 6. [TSON-DATA] §7.4, [TSON-SCHEMA] §4.2, §5.2, §12.1, [TSON-JSON] §5.6 — a dependent field's type is a sum in place

**Problem.** A dependent field's type, `kind!`, is a reference value in Revision 39, and it is no name: it names a
field of the record instance, bound at each read, and is never replaced by an entry's name. Holding it in
`meta.reference_type` costs a facet that exists to be withdrawn (`allow_sibling`, true at `meta.field_type_ref` and
false at every other reference), two near-identical type-slot choices (`type_expr`, `field_type_expr`), a position rule
the grammar cannot state because it parses `kind!` wherever a reference stands, and a data-grammar form, with a tree
node and a JSON string form, that no data document but a resolved schema may hold.

**The proposal.** A dependent field's type is a type written in place, a sum in which a field of the enclosing record
names the type, as `meta.declared` is the sum in which a value's tag does:

```
dependent => meta.sum & { on: text }
payload   => !meta.record_field { type: { of: !meta.dependent { on: kind } } }      -- payload: kind!
```

`kind!` is schema-grammar sugar for it, admitted only as a record field's type. A reference is a name, an
application or a parameter.

**What is running** on a branch off the Revision 40 proposal branch, against the hand-written kernel and resolved
fixture (`spec/m/meta.tn`, `spec/fixtures/meta-resolved.tn`).

- *The kernel.* `allow_sibling`, `meta.field_type_ref` and `meta.field_type_expr` are gone; `meta.dependent` is added;
  `record_field.type` is a `meta.type_expr`, as every type slot is; `meta.type_ref` is `!meta.reference_type { of:
  meta.type }`; and `meta.ref` is an empty refinement, `meta.reference_type ^ {}`, kept so `meta.ref<T>` keeps its
  spelling. The kernel's own `record_field.value` is typed `type!`. `on` is `text` and names a declared field by its
  declared spelling, as a `discriminators` entry does.
- *The data grammar* ([TSON-DATA] §7.4, §7.5). `sibling-ref` and its adjacency row are gone, and `reference =
  application`; no tree node or writer case holds the form. A data document writing `kind!` — or a schema document
  writing it inside a construction's payload, which is data — gets the separation rule's syntax error at the `!`
  ("adjacent values must be separated by whitespace, a comma, or both"), the token `kind` before it read as the
  position reads any token. No targeted message names the mark, which would put the form back in this grammar.
- *The schema grammar* ([TSON-SCHEMA] §12.1, §5.3's sugar table). `kind!` parses only as a record field's type or a
  field group member's, a member being a field: `field-type = type-expr ["?"] / dependent-type`, `dependent-type =
  unquoted-token "!"`, `group-member = *annotation field-name ["?"] ws ":" ws field-type`, with `sibling-ref` gone
  from `type-expr`, `refined-def`, `supertype-ref`, `map-key` and the imported productions. The token is one
  identifier and the `!` is followed by whitespace (§12.3), so `kind!?` is a parse error and a dependent field is
  never voidable by grammar. An adjacent `!` after a name at any other position — inside a field's type, a
  declaration's body, a refinement source, a composition operand, a map key, a parameter's type, an argument, a
  field's value — is a parse error: "'kind!' is a dependent field's type, and stands only as a record field's type, a
  field group member's included -- never inside a type, nor at any other position". The desugar step writes `kind!`
  as `{ of: !meta.dependent { on: kind } }` in place, and resolved output writes that.
- *The link rule* ([TSON-SCHEMA] §4.2, §5.2). A `meta.dependent` is admitted in place only, and only directly at a
  record field's type: declared (`x => !meta.dependent { on: kind }`), or in place at an element type, a map's value
  type, a tuple element or a choice variant, it is a resolver error at link, nothing else binding `on`. Two records
  each holding `{ of: !meta.dependent { on: kind } }` hold equal values and two types; a dependent field is read
  through its record's reader, never through a reader shared by a type in place's structural identity. §5.2's rules
  on the sibling and the dependent field are unchanged, and hold for the sugar and the canonical form alike.
- *The JSON encoding* ([TSON-JSON] §5.6). The string form of a sibling reference is gone: `"kind!"` at a kind's
  position spells no reference and is refused as a malformed value (`ATOM_FORM_INVALID`).

**Open against the text.**

- *A kernel-typed data document can write what the list refuses.* `meta.type_inline.of` is typed `meta.type`, and
  §4.2's list is a resolver rule, so a data document typed by the kernel (`!meta.array { element_type: { of:
  !meta.dependent { on: kind } } }`) reads with no diagnostic. This predates `meta.dependent`, which only adds an entry
  to the list; it is not solved here.
- *A declared sum in place is refused with the wrong diagnostic.* The text now says a declared sum
  (`meta.declared_type`) is never written in place (§4.2, §5.2). The implementation refuses it at resolution, where the
  model's type for a type in place admits only what the list names, so the diagnostic reads as the schema and the
  bound class disagreeing rather than naming the rule.

**Resolution proposed.** The Revision 40 proposal text carries it, edited in place: Part 1 §1.3, §1.5, §2.3, §2.5,
§2.8, §4.1, §7.2, §7.2.4, §7.2.5, §7.3, §7.4, §7.5, §7.7 and §8.1; Part 2's header, §1.4, §4.2 (the reference
listing, the sibling bullet, the type-slot choice, and the list of what `of` holds, which gains `meta.dependent` at a
record field's type alone and states that a declared sum is never written in place), §5.2 (the opening and the
position rule), §5.3 (the sugar table), §5.5, §5.6, §5.7, §5.10, §5.11, §7.4, §8.1, §8.2, §9, §12.1, §12.2 and §12.3;
Part 3 §1.6 (items 16 and 21) and §5.6; `spec/tson-rev40-changelog.md` records each section's rule before and after.
Four points the design left open are settled there as ruled: `kind!` in data takes the separator rule's parse error
and no targeted one; `meta.ref` stays as an empty refinement; `on` names a field by its declared spelling; and a
declared sum is named, as a namespace type is.
