# Spec feedback against Revision 39

This is the register of the first implementation of TSON against 2026 Revision 39 of Part 1 ([TSON-DATA],
https://tson.io/raw/2026/39/tson-part1-data.md) and Part 2 ([TSON-SCHEMA],
https://tson.io/raw/2026/39/tson-part2-schema.md). It holds two kinds of entry.

**Entries #1 to #9 are issues raised against Revision 39** and registered before Revision 40 answered them, and
Revision 40 answers every one. Each gives the sections concerned, what was wrong or unclear in Revision 39's text, the
interpretation the implementation took, what it runs, and the resolution it proposed.

**Entries #10 to #21 are design changes Revision 40 made that were worked out in the proposal's text directly**, and
not registered first. They are written up here in the register's form at the revision's close, so that the record
is whole: what was wrong or costly in Revision 39, the design and the reasons recorded for it, what the
implementation runs and what it does not, what was considered and why it lost, and what is lost. They were not
adjudicated from this register; each entry's **Revision 40** line says what the text carries, and which of its points
the spec author ruled on where a record of the ruling exists.

The **Revision 40** line under each heading gives the disposition. Spec quotes are Revision 39's, and section numbers
are the same in both revisions but for §5.13 and §7.9, which Revision 40 adds.

The answers are recorded in the Revision 40 change log: its §2, *Disposition summary*, gives each entry's verdict, its
§3 items give each section's rule before and after, and its §5 lists the readings the spec author ruled on and what
is still open. Where an entry states a design, it states it as the implementation runs it, so a reviewer needs nothing
beside it. Where the evidence comes from a consumer of the library, an HTTP layer built on it, the entry says so.

Part 3 ([TSON-JSON]) is drafted alongside the implementation and edited directly as findings arise, so a Part 3
finding is a change to its text rather than an entry here. An entry spanning Part 2 and Part 3 says which half is
which.

---

## 1. [TSON-DATA] §2.1 — the worked example says untagged tokens resolve by §4's base rules under an imported schema

**Revision 40:** accepted as proposed. Part 1 §2.1's paragraph is rewritten for a document under a schema, and no rule
changes (change log §3.1 item 20).

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

**Resolution proposed.** Rewrite the sentence for a document under a schema: the tags name types of the imported
namespace (`order.tn`'s closure supplies `uuid`, `date` and `number`), and each untagged token is read by the type
its position declares — `1042` as the field's integer type, `GOLD` as an enum member — with a pointer to §4 and §5.1
for what the same tokens would mean in a document with no `!!import`. Nothing in the normative rules changes.

## 2. [TSON-DATA] §2.4, §7.4, [TSON-SCHEMA] §5.3 — whether a quoted token at a reference-typed position is a reference

**Revision 40:** accepted by the spec author's ruling, the other way from the entry's proposal. At a position typed by
a reference, in data and in a body a schema document reads as data, a quoted token is the reference its text spells.
Each segment is a name of the family of what it enters, and no family a segment is matched against admits `.`. A tag,
an annotation name and every type position of the schema grammar stay unquoted, and an argument is schema grammar.
Part 1 §2.4, §7.1, §7.4, §7.7; Part 2 §1.3, §4.2, §5.2, §5.12, §12.1; Part 3 §5.6 (change log §3.1 item 22, §3.2
items 117–118, §3.4 item 14).

**Problem.** Two rules answer this oppositely, and neither names the case.

- *Form is not meaning.* §2.4 says a token's form "is consulted exactly once" — by base type resolution, and at a
  disjoint choice — and that "two tokens with the same text denote the same value regardless of form — identity is
  text". §5.2 says the same of every atom ("Content expressible unquoted may be written either way"), and a
  reference is an atom (`meta.ref`). By these, `t: "int32"` at a field typed `meta.type_ref` is the
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

**What is running.** While the entry was open the implementation's two paths answered differently, which was the
evidence that the text did not decide it: a schema document refused the quoted form at every reference position it
read, a constructor body's reference-typed slot included (`!meta.array { element_type: "text" }`), and a data
document accepted it at a field typed by a reference (`!s { t: "int32" }` under `s => { t: meta.type_ref }`). It now
runs Revision 40's rule: the quoted token is the reference in data and in a construction's payload, so
`!meta.array { element_type: "text" }` constructs `[text]`, and `{ b: "text" }` at a field's type is still refused,
a type position being schema grammar.

**Resolution proposed.** A reference is an **unquoted** token at every position, in data and in schema documents
alike: state in §7.4 that a quoted token is never a reference wherever a reference is read, and add a reference
position to §2.4's list of the places where form is consulted. The alternative — form is not meaning here either, so
`"int32"` is the reference — should then be stated in §7.4 and [TSON-SCHEMA] §5.3, since both read against it.
Revision 40 takes the alternative, which reaches names outside the token profile (`"main.public api.ping"`).

## 3. [TSON-SCHEMA] §4.2, §5.3, §5.10 — parametric records as records with parameters: a proposal for Revision 40

**Revision 40:** accepted as proposed, every step as this entry states what runs. Part 2 §5.10, now *Parametric
Records*, is rewritten around the three rules below, and §1.3, §1.6, §3.3, §3.4.1, §4.1, §4.2, §4.3, §5.2, §5.3,
§5.6, §5.7, §5.8, §5.10.1, §6, §7.2, §7.4, §8.1, §8.2, §8.3, §9, §11.5 and §12 follow; Part 1 §2.3, §3.2, §4.1 and
§7.4 take the argument list out of the data grammar; Part 3 §1.6, §3.3, §3.4 and §5.6 follow (change log §3.2 items
2–30, 77–96, 102, 130 and 131; §3.1 items 2–5 and 14–19; §3.4 items 1–5 and 9). The change log's §5 records three
editorial readings. **Still open in the implementation:** a parametric record is judged where it is applied
and not at its declaration, so while nothing applies it three errors go unreported: a name in it that resolves to
nothing, a recursion that can never close (§5.10.1), and a selector on a type no token denotes (§5.2) (change log §5,
"#3 (remainder)").

**Problem.** §5.10 gives a template two representations and a parameter two kinds. A record template is its own
constructor and a family base; every other open entry is held as text, because a value parameter can stand wherever
a token stands, so a body carrying one is typed by no constructor's shape until it closes. The cost is the largest in
the series: held bodies, parameter types and bounds derived by a fixed point across templates, a head edge at a
closed operand, and a lookup that resolves an application written in *data* against the applications a schema closed
(§4.2). No bundled schema and no consumer schema measured declares a value parameter, a template over anything but a
record, or a template named bare at a position.

**The proposal**, in three sentences.

1. A parametric record is a record with parameters: `meta.record` carries `parameters`, each a type standing at a
   field's type or as an argument of an application there or in a parent, and nowhere else. A reference names a
   parametric record only as the head of an application.
2. `C<args>` over a constructor is construction sugar: `!C { f1: a1 … }` over its required fields typed by a
   reference naming types, in declaration order, as `[T]` and `{K => V}` already are.
3. At a type slot of schema source `H<args>` is an application, the construction `meta.apply` in place, substituting
   where `H` is a parametric record and constructing where it is a constructor. A reference is a name.

What follows from them: no value parameters, no derived bounds, no parametric record named as a type, none over a
body that is not a record, and no `meta.template` or `meta.record_template`.

**What is running.** Every step of the proposal.

- *Value parameters: gone.* A parameter is a type, standing at a field's type, at a slot of a type written in place
  there at any depth, or as an argument of an application at either or in a parent. Anywhere else — a facet, a
  field's default or fixed value, an enum member, a size bound, a field name, a modifier — is a resolver error at the
  declaration, naming the slot. An argument is a name: a number or a quoted token in an argument list is a parse
  error in a schema document (`maxint<10>`, `box<"x">`). A value that differs per application is written where the
  application is declared, `created => response<order> & { status?: = 201 }`.
- *Bounds: written, never inherited.* A parameter's bound is what its declaration writes, `<T: text>`, and is
  otherwise absent; nothing is inferred from its uses, so no fixed point runs across parametric records. A parameter
  passed as an argument to a bounded parameter is written with a bound that IS-A it, wherever it is that argument.
  With `boxed => <T: text> { v: T }`, `rebox => <T: text> { b: boxed<T> }` resolves and `rebox => <T> { b: boxed<T> }`
  is a resolver error at `rebox` naming `boxed`'s `T: text`. A parameter passed to a constructor's slot
  (`<K> { m: {K => text} }`) needs no bound: the slot judges the argument that arrives when the record is applied.
- *An application in a data document: a parse error.* In a data document a reference is a name and an argument list
  is no data grammar ([TSON-DATA] §7.2.5): `set<text>` in a value is a parse error at its `<`, whether or not the
  schema declares the application, and data names the entry a declaration gives it. A construction's payload in a
  schema document is data and takes the same error. In JSON the string `"set<text>"` is outside a reference's grammar
  and refused. The lookup of an application in data against the closure's applications is gone.
- *Construction sugar.* At a type position of a schema document, `C<a1 … an>`, `C` resolving through aliases to a
  constructor, is the construction `!C { f1: a1 … fn: an }` over `C`'s required fields typed by a reference naming
  types or by the type-slot choice (#5), in declaration order: `meta.array<text>` is the form `[text]` is, and
  `meta.map<text int32>` the form `{text => int32}` is. An incomplete entry applied, `nonempty<uuid>` over
  `nonempty => !meta.array ^ { min_items: 1 }`, is its completion. A wrong argument count, an argument the field's
  `of` refuses (`meta.namespace<int32 text>`) and a construction left incomplete are refused. Settled as built: a
  field with a default is no argument slot, an argument is not followed through an alias, and a parametric record is
  applied by substitution and never constructed.
- *No parametric record is a type a position names.* Not a field, element, map key or value, tuple position, choice
  variant or argument, not a slot typed by a reference, not an operand of `&` or the source of `^`, and not its own
  body. Each is a resolver error at the declaration that wrote it, naming the application or the abstract record a
  family's applications compose; a data tag naming a parametric record is a validation error. An application's IS-A
  edges are its head's written parents': each closed parent, and the entry a declaration gives an applied parent.
  The head's own name is never among them, and no `subtypes` index or reference holds one. `abstract` and the
  selectors on a parametric record are each application's; `final` on one is a resolver error. A family is an
  abstract base, a parametric record composing it, and members composing an application and pinning the selectors.
- *`parameters` on `record`, and the kernel.* `meta.record` carries `parameters?: [meta.param; 1..]`, each
  `{ name, bound? }`, and the kernel declares no other parametric form: Revision 39's `template`, `template_param`,
  `record_template`, `record_template_field`, `record_template_field_map`, `tuple1` and `voidable_tuple1` are gone.
  `meta.ref` is the one reference constructor, and the root's `set` names the incomplete entry `meta.set_type`, so
  `meta.ref<X>` is a construction and `set<text>` a completion, neither carrying `@meta.application`. A parametric
  record is read at its declaration against `record`'s own vocabulary, a parameter looked up in its `parameters`
  before the namespace. Its body is a record body: a parameter list before anything else is a resolver error naming
  the remedy, and there is no parametric alias. Inside one, a form over a parameter is written in place like any
  other, and a constructor's application over a parameter is its construction in place. Writing `parameters` as a
  binding of a construction of `record` is a resolver error.

**Resolution.** The entry proposed edits to Revision 39's §1.6, §4.2, §5.3, §5.6, §5.7, §5.10, §8.1, §8.2, §12.1,
§13.1 and [TSON-DATA] §7.4. Revision 40's §5.10 is the result, and the change log items above record each section
before and after.

**See also** #10, the incomplete entries `set` and `nonempty` complete into; #14, the one reference constructor; #15,
a choice over a parameter; #18, an alias of a parametric record; and #20, the term.

## 4. [TSON-SCHEMA] §5.4, §5.2 — a tag at a choice names a variant; a choice with an abstract variant has no values

**Revision 40:** accepted, the entry's first resolution, on the spec author's ruling. A tag at a choice names a
variant, or a type that IS-A exactly one variant, and the value is read at that variant as at a position typed by it.
A tag whose type IS-A two variants and names neither selects none, a validation error at the data, and the choice
loads. Part 2 §5.4, §7.2; Part 3 §3.3, §8.1 (change log §3.2 item 119, §3.4 item 15).

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

**What is running.** While the entry was open the tag was matched against the variant names exactly: `!circle { … }`
at `item` was refused as no declared variant, and `!shape { … }` as abstract with a message recommending `circle`,
the tag the first refusal refused. The implementation now runs Revision 40's rule, in both encodings: `!circle` at
`item` selects `shape` and is validated as a `circle`.

**Resolution proposed.** In §5.4: a tag selects the variant it names, or the one variant its type IS-A (§7.2), and
the value is then read at that variant as it would be at a position typed by it. Two points the rule has to state:

- **A tag that IS-A two variants.** With composition a type can reach two variants. Either the tag is refused at
  the data, or a choice whose variants share a subtype is a resolver error, as overlapping classes are for an
  untagged choice. A spike of the first reads cleanly: no variant is selected and the tag is refused.
- **Disjointness is unaffected.** `disjoint` is still derived over the variants' classes; a subtype has its
  variant's class.

The alternative is to keep the tag exact and refuse an abstract variant at the declaration, since it can hold no
value. That keeps one rule for a tag and gives up "text or any shape" as an open family.

## 5. [TSON-SCHEMA] §4.2, §5.3, §5.10, §8.2 — inline types: a type slot holds a name or a type written in place

**Revision 40:** accepted as proposed. Part 2 §1.3, §1.6, §2.2.3, §3.3.3, §3.4.1, §4.1, §4.2, §4.3, §5.2, §5.3,
§5.4, §5.6, §5.7, §5.8, §5.10, §5.10.1, §5.12, §6, §7.2, §8.1, §8.2 (retitled *Instantiation and Identity*), §8.3,
§9, §10.1, §11.5, §12.1 and §12.2; Part 3 §3.3 (change log §3.2 items 33–57 and 70–76, §3.4 item 6). Four points the
design left open were ruled as built: an application at a composition operand is absorbed by value; a value modifier
is admitted on a reference written in place and refused on a container, tuple or choice written in place; a choice
that is not disjoint names every variant; and a name and a type written in place are never the same type, even where
the name's construction is identical (change log §5). **Still open in the implementation:** a tree read reports a
dependent value whose sibling holds a type written in place as not implemented.

**Problem.** One sentence of §4.2 — every slot that names the type of a value holds a bare name in a closed entry —
is paid for across the series. A container, tuple, choice or reference written at a use site, and every application
of a template no declaration names, lifts to a resolver-materialised entry named by its content. That brings
`@meta.synthetic` and its recompute-on-ingest rule, internal names and a freshness rule for them, a merge of equal
lifted forms within a document and of equal expansions across imports, a self-reaching instantiation entry for a
recursive expansion, resolved forms compared "up to renaming of internal entries", a minted name a data document may
write as a tag and that fails at the next processor, and a way to recover the written form for every diagnostic. None
of it describes a value: two schemas that differ only in how their resolvers name lifted entries admit the same data.

**The proposal.** A type slot holds a name or a type written where it is used, and the kernel's type says so:
`type_inline => { of: meta.type }` and `type_expr => @meta.disjoint ( meta.type_ref | meta.type_inline )`, which
types every type slot, a record field's included (#6). `tags: [text]` resolves to
`type: { of: !meta.array { element_type: text } }`; a construction carries its constructor's tag (`!meta.ref`), and a
completion its constructor's (`set<text>` under `!meta.array`). The slots are a record field's `type`,
`array.element_type`, `map.value_type`, `tuple_element.element_type` and each of `choice.variants`. What may be
written in place is a container, a tuple, a choice or a reference, a completion of an incomplete entry of one, or a
record: a record body written at the slot or the record an application of a parametric record closes to, one form.
An enum, every other atom and a namespace type are declared and named. A map's key is a name. A type written in place
is no entry: structural identity (same constructor, same fields, names as the leaves, annotations taking no part), no
chain, a member of no kind, named by no tag. Nothing is lifted, minted or merged, and `@meta.application` stays on a
declared application's key alone. An application of a parametric record is the substituted record under a
declaration's name, absorbed by value at a composition operand, and closed in place at a type slot, never looked up;
recursion ties to the declaration being closed, and a recursive application with none to tie to is refused. An
argument of a parametric record's application is a name or a parameter; a slot typed `meta.type_ref` holds a name and
refuses an application. Three rules widen: a dependent field's sibling may be typed by the type-slot choice, a
constructor's argument slots include fields typed by it, and the positional form of a one-field record admits a field
typed by it.

**What is running**, against the kernel and the resolved fixtures.

- *The kernel and the slots.* `meta.type_inline` and `meta.type_expr` are declared and the five slots retyped;
  `map.key_type` stays `meta.type_ref`. `@meta.synthetic` is gone. The bundled schemas resolve, link and compile.
- *Sugar in place.* `[T; n..]`, `{K => V}`, `[A, B]`, `( A | B )`, `set<T>` and `meta.ref<T>` at a slot desugar to
  `{ of: !C { … } }`, nested forms nested. Nothing is lifted or merged and no name is derived from content. A schema
  with inline forms reads data in tree and bind mode and through the JSON stack.
- *Identity.* A type written in place compares by its constructor and fields, and a name and a construction are
  never one type: a restated field refuses `text_list` restated as `[text]` and the reverse, and a choice counts
  `text_list` and `[text]` as two variants.
- *Values and choices.* A value modifier on an inline reference (`handler?: meta.ref<method> ~ orders.place_order`)
  is judged at link as a declared reference's is; one on any other type written in place is refused. A choice that
  is not disjoint with a variant written in place (`( [text] | [int32] )`) is refused.
- *Applications.* A declared application is its substituted record under its name, `@meta.application` on its key.
  At a type slot an application closes in place: `f: page<order>` holds `{ of: !meta.record { … } }`, never looked up
  to a declaration, so `f: page<order>` and `f: page_order` are two types of one shape. Inside a parametric record a
  parametric record's application over a parameter is `meta.apply` written in place until the record is applied.
- *Recursion.* A recursive application closing a declaration ties to it: `node_text => node<text>` over
  `node => <T> { value: T  next?: node<T> }` holds `next: node_text`, and mutual recursion ties the same way. A
  recursive application with no declaration closing it (`f: node<text>`) is refused at the declaration that wrote
  it, and a parametric record holding an application of a recursive one it is not on a cycle of
  (`wrap => <T> { n: node<T> }`) is refused at its own declaration (change log §3.2 item 56).
- *Records in place, read and bound.* A record in place reads in tree and bind mode and through the JSON stack, each
  diagnostic located by its field path. A value there carries no tag and admits none. In bind mode it binds to the
  record class the component holding it declares.
- *Arguments.* A sugar form, an applied constructor or an application of a parametric record passed as an argument of
  a parametric record's application is refused, naming the declaration to write (`text_box => box<text>`). An
  application at a slot typed `meta.type_ref` — a map's key, a reference's `of`, a parameter's bound, an alias's
  target — is refused the same way.
- *A record body at a slot.* `at: { x: int32 }` parses at every type slot and desugars to
  `{ of: !meta.record { fields: { … } } }`. It holds and is judged as a declared body is, and is the same type as an
  application closing to equal fields; `at: point & { z: int32 }` and `^` or `-` at a slot are parse errors.
- *The three widened rules* all run.

**What is not yet running.** A tree read reports a dependent value whose sibling holds a type written in place as not
implemented, holding the sibling as the record it is written as rather than as the type (outstanding work in the
implementation).

## 6. [TSON-DATA] §7.4, [TSON-SCHEMA] §4.2, §5.2, §12.1, [TSON-JSON] §5.6 — a dependent field's type is a sum in place

**Revision 40:** accepted as proposed. Part 1 §1.3, §1.5, §2.3, §2.5, §2.8, §4.1, §7.2, §7.2.4, §7.2.5, §7.3, §7.4,
§7.5, §7.7 and §8.1; Part 2 §1.4, §4.2, §5.2, §5.3, §5.5, §5.6, §5.7, §5.10, §5.11, §7.4, §8.1, §8.2, §9, §12.1,
§12.2 and §12.3; Part 3 §1.6 and §5.6 (change log §3.1 items 6–13, §3.2 items 58–69 and 90, §3.4 items 7–8). Of the
four points ruled, `kind!` in data takes the separator rule's parse error, `on` names a field by its declared spelling,
and a declared sum is never written in place; the fourth, `meta.ref` kept as an empty refinement, is superseded by
`meta.ref` as the one reference constructor (§3.2 items 97–100). The point this entry left open against the text is
settled by §3.2 item 122.

**Problem.** A dependent field's type, `kind!`, is a reference value in Revision 39, and it is no name: it names a
field of the record instance, bound at each read, and is never replaced by an entry's name. Holding it in
`meta.reference_type` costs a facet that exists to be withdrawn (`allow_sibling`, true at `meta.field_type_ref` and
false at every other reference), two near-identical type-slot choices (`type_expr`, `field_type_expr`), a position rule
the grammar cannot state because it parses `kind!` wherever a reference stands, and a data-grammar form, with a tree
node and a JSON string form, that no data document but a resolved schema may hold.

**The proposal.** A dependent field's type is a type written in place, a sum in which a field of the enclosing record
names the type, as `meta.declared` is the sum in which a value's tag does:

```
dependent => final meta.sum & { on: text }
payload   => !meta.record_field { type: { of: !meta.dependent { on: kind } } }      -- payload: kind!
```

`kind!` is schema-grammar sugar for it, admitted only as a record field's type. A reference is a name or, inside a
parametric record, a parameter.

**What is running**, against the kernel and the resolved fixture.

- *The kernel.* `allow_sibling`, `meta.field_type_ref` and `meta.field_type_expr` are gone; `meta.dependent` is added,
  final; `record_field.type` is a `meta.type_expr`, as every type slot is; `meta.type_ref` is
  `!meta.ref { of: meta.type }`; and `meta.ref` is the one reference constructor, `meta.atom & { of: meta.type_ref }`,
  so `meta.ref<T>` keeps its spelling. The kernel's own `record_field.value` is typed `type!`. `on` is `text` and
  names a declared field by its declared spelling, as a `discriminators` entry does.
- *The data grammar* ([TSON-DATA] §7.4, §7.5). `sibling-ref` and its adjacency row are gone, and with the argument
  list gone too (#3) so is `reference`. A data document writing `kind!` — or a schema document writing it inside a
  construction's payload, which is data — gets the separation rule's syntax error at the `!`, the token `kind` before
  it read as the position reads any token. No targeted message names the mark, which would put the form back in the
  data grammar.
- *The schema grammar* ([TSON-SCHEMA] §12.1, §5.3's sugar table). `kind!` parses only as a record field's type or a
  field group member's: `field-type = type-expr ["?"] / dependent-type`, `dependent-type = unquoted-token "!"`. The
  `!` is followed by whitespace (§12.3), so `kind!?` is a parse error and a dependent field is never voidable by
  grammar. An adjacent `!` after a name at any other position is a parse error. The desugar step writes `kind!` as
  `{ of: !meta.dependent { on: kind } }` in place, and resolved output writes that.
- *The link rule* ([TSON-SCHEMA] §4.2, §5.2). A `meta.dependent` is admitted in place only, and only directly at a
  record field's type: declared, or in place at any other slot, it is a resolver error, nothing else binding `on`.
  Two records each holding `{ of: !meta.dependent { on: kind } }` hold equal values and two types.
- *The JSON encoding* ([TSON-JSON] §5.6). The string form of a sibling reference is gone: `"kind!"` at a kind's
  position spells no reference and is refused as a malformed value.

**Open against the text, now settled.** A data document typed by the kernel could write a type in place that
§4.2's list refuses (`!meta.array { element_type: { of: !meta.dependent { on: kind } } }`), `meta.type_inline.of`
being typed `meta.type`. Revision 40 states that such a value is held to the kernel's shape alone and declares
nothing (§4.2).

**See also** #14, `meta.ref` as the one reference constructor once `allow_sibling` is gone.

## 7. [TSON-SCHEMA] §5.2, §11 — a free sibling hands the choice of a dependent field's type to the document's author

**Revision 40:** accepted as proposed. Part 2 §11.1 gains the paragraph *A free sibling gives the document's author
the choice of a type*, and §5.2's rules are unchanged (change log §3.2 item 110).

**Problem.** §5.2 reads a dependent field against the type its sibling's value names, and admits as a sibling a
reference naming types (`meta.type_ref`, `meta.ref<C>` over a constructor, a refinement of one) or the type-slot
choice `meta.type_expr`. Where the sibling is free, neither pinned nor a discriminator, the author of the document
chooses the type the value is read against, from everything the sibling admits:

- `kind: meta.type_ref` admits every type of the closure, the kernel's among them, so `kind: meta.record` reads the
  value as a record definition inside an application's own document;
- `kind: meta.type_expr` admits, beside those names, a type the author writes in place.

The rules are complete: the set is the sibling's type, and the schema narrows it by pinning the sibling, by making it
a discriminator over a family, or by refining the reference. What is missing is the warning. §11 names the other
places a document steers its own interpretation (`!!import`, external references) and says nothing of this one, and
a schema author writing `payload: kind!` for a tagged payload has no prompt to ask how wide `kind` is.

One limit of the narrowing is worth stating beside it: a reference narrows by constructor (`meta.ref<meta.record>`
is every record of the closure, the kernel's included), never to the subtypes of one record. A family with a
discriminator is the spelling for that set.

**What is running.** A tree read reads the value against whatever type the sibling names. A bind read does the same
where a class is bound for that type, and throws `MissingBindingException` during the read where none is: the
receiver's wiring, found only when a document names the type. Moving that check to the bind-mode load is outstanding
work in the implementation. In an HTTP layer built on this library the exception is a server error any client can
produce, so that layer refuses at startup a bound request record whose dependent field lets a sender name a type no
class binds, or one of the kernel's vocabulary.

**Resolution proposed.** One paragraph in §11, and no change to §5.2's rules: a free sibling typed `meta.type_ref` or
`meta.type_expr` gives the document's author the choice of the type a value is read against, the kernel's vocabulary
included and, for `meta.type_expr`, a type the author writes; a schema for untrusted input SHOULD pin the sibling,
make it a discriminator, or narrow the reference.

Two sentences the HTTP layer's report suggested are not proposed. *That a receiver may hold a sender to fewer types
than the reference admits*: the set is the schema's, and a receiver accepting less is using another schema without
naming it. *That a chosen type the receiver cannot read is the document's fault*: the document is valid under the
schema and a tree read accepts it; whether a host has a class for a type is a fact about one receiver, which the
type system does not see.

## 8. [TSON-SCHEMA] §5.12, §6, §3.3.3 — a name inside an annotation's value: that it is judged, where, and when

**Revision 40:** accepted as proposed, and no rule changes. Part 2 §5.12 gains *The rule reaches an annotation's
value*, and §6's `@T:value` bullet points at it (change log §3.2 item 111). **Still open in the implementation:** a
name in a schema document's annotation value is checked for shape but not judged at link (change log §5, "#8
(remainder)").

**Problem.** §6 says an annotation's value is "a single data-value conforming to `T`", validated against `T`'s
contract, and §5.12 judges a name at a `meta.ref<T>` slot by what it reaches, in a schema document and in a data
document alike. Together they judge a name inside an annotation's value. Neither says so: §5.12 lists the slots the
rule reaches (a value entry's field, a field's default or pin, a data document's field) and never an annotation's
value, and §3.3.3 states the namespace for the annotation's own name only. §6's opening, "Annotation values are
always data values", is read by a consumer as making the name an inert token; the sentence opposes a data value to a
type definition, and says nothing of whether the value is checked.

Two facts are unstated:

- **The namespace.** A name inside an annotation's value resolves in the annotating document's namespace, as the
  annotation's name does (§3.3.3): for a data document the closure its `!!import`s bind, for a schema document its
  own entries and its import closure.
- **The time.** In a schema document the name is judged when the schema links, as a field's default or pin is
  (§5.2, §5.12), since the entries it may name are the schema's own.

**What is running.** A data document's annotation value is judged at the read: with `about => @meta.annotation
meta.ref<order>` and `typed => @meta.annotation meta.type_ref`, `@about:nope` is refused as naming no entry and
`@typed:nope` as no member, each `ATOM_CONSTRAINT_VIOLATION`, and `@about:o1` naming a value of `order` reads clean.
A schema document's is not: `@binds:[no_such]` under `binds => @meta.annotation [meta.ref<protocol>]` loads, the
value's shape checked and the name held unjudged. That is a gap of this implementation, and no reading of the text.
An HTTP layer built on this library keeps its annotations' `meta.ref<…>` typing and checks the names itself.

**Resolution proposed.** One sentence in §5.12's "Where the rule stands", naming an annotation's value among the
slots the rule reaches, with the namespace and the time above; and in §6, beside "conforming to `T`", a pointer to
it. No rule changes: §6's "an annotation never changes whether a value is valid" is about the annotated value, and
an annotation whose own value does not conform is already an error.

## 9. [TSON-SCHEMA] §5.5, §9, [TSON-DATA] §5.5 — `net.uuid_type.version` has no stated meaning

**Revision 40:** accepted as proposed. Part 2 §5.5 states the meaning and §5.7 lists `version` among the
settable-once facets (change log §3.2 item 112), which is what runs.

**Problem.** net.tn declares `uuid_type => meta.atom & atom.specification & { spec?: = "…/rfc9562"  version?:
numeric.count }`. Nothing says what `version` constrains: §5.5's per-family list has no entry for `uuid`, §5.7's
facet kinds name no `version`, [TSON-DATA] §5.5 gives the lexical form only, and the entry's own `@doc` was "UUID
constraint vocabulary. Instance is `uuid`." Three readings fit the declaration: the value's RFC 9562 version field
equals `version`; it is at least `version`; or `version` is advice to a generator. Which facet kind it is under
§5.7 is unstated too, so whether `!v4 ^ { version: 7 }` is a refinement is undecided.

**What is running.** The first reading: a value whose version field differs from `version` is a validation error
("is version 1, expected version 4"). Under refinement it is settable once: `!v4 ^ { version: 7 }` over
`v4 => !uuid ^ { version: 4 }` is refused, "a set version may be restated but not changed". The entry's `@doc` now
says "`version` admits one UUID version", which is this reading.

**Resolution proposed.** One clause in §5.5's list: `version` admits a UUID whose RFC 9562 version field equals it,
a value outside it being a validation error; and in §5.7, `version` among the settable-once facets, since two
versions admit disjoint values and a change would narrow nothing.

## 10. [TSON-SCHEMA] §5.5, §5.7, §7.5, §8.1 — a construction may be left open; `enum` and `set` are incomplete entries

**Revision 40:** made in the proposal's text and written up here at the close; no register entry carried it.
`!C ^ { values }` over a constructor of types constructs `C` with a required field left absent and declares an
**incomplete entry**: no type and no value, written field by field through `!X ^ { values }`, each field once, and
completed by `!X { … }`, `!X value` or `X<A …>` into the plain construction `!C { … }`, which carries no chain. It is
refused wherever a type is named, imported like any entry, and not written in resolver output. The kernel's
`meta.set_type` and root `enum` are two, and a declared completion carries the derived `@meta.completion`. Part 2
header, §1.6, §3.3.1, §3.3.3, §3.4, §4.1, §4.2, §5.1–§5.7, §6, §7.4, §7.5, §8.1, §8.3, §9, §12.1, §12.2 and §13.2, and
the end-to-end reading of item 109; Part 3 §1.6 (change log §3.2 items 106, 107 and 109, §3.4 item 11). What an
incomplete entry admits is the spec author's ruling (change log §5, "#10"). **Still open in the implementation:** a
field of an incomplete entry typed by a type written in place (`meta.choice.variants`, `meta.tuple.elements`) is read
only where the entry is completed, and a JSON `$type` naming an incomplete entry reports a gap where the text
encoding reports the mismatch.

**Problem.** Revision 39 had two ways to build a type and none to build part of one. `!C { … }` constructed a type
and wrote every required field (`!meta.array { element_type: _ }` was refused, `element_type` admitting no absence),
and `!I ^ { … }` refined an instance that already existed. What a schema says of arrays in general — at least one
item, unordered and unique — had no entry to be said of, `meta.array`'s `element_type` being required. Two spellings
stood in for it, each at a cost.

- *A template over a container*, `nonempty => <T> [T; 1..]`, which #3 removes with every template whose body is not
  a record. The kernel's own `set` was one: `set => <T> !meta.set_type { element_type: T }`, documented as "A §5.10
  template, not a constructor".
- *A record refinement of a constructor*, `minlist => meta.array ^ { min_items?: = 1 }`. §5.7 admitted it, "a
  constructor refinement (`meta.set_type => meta.array ^ { ordered?: = false }`) moves a default to a pin or a required
  field to an injected one as any refinement does, and never replaces a pin", and the kernel wrote two:

  ```
  set_type => meta.array ^ { voidable?: = false  ordered?: = false  unique_items?: = true }
  enum     => meta.enum_type ^ { name_type?: = meta.identifier }
  ```

  §5.7 explained the second: "a refinement of the *constructor* may pin it, which is how `enum` states its
  `name_type`". What such a declaration writes is a pin on a new constructor, not a value: `min_items?: = 1` refuses
  `min_items: 2`, which is narrower, so "every array built this way has at least one item" could not be said by
  restricting a declaration. Its instances have the parent's shape and bind as the parent's, and the first
  implementation recognised such a constructor by a fact recorded when it resolved, that it was written with `^`: the
  one place its main code told the two operators apart after resolution. The same constructor written with `&`
  resolved to the identical entry and could not be applied (#11).

**The design.** One operation: construct a constructor `C` from a base with values. The base is nothing or an entry
`C` built, the result's tag is always `C`, `^` never makes a constructor, and `&` is the only thing that does (#11).

| Operation | Written | Result | IS-A |
|---|---|---|---|
| Construction | `!C { … }`, every required field | a type | none |
| Construction left open | `!C ^ { … }`, a required field absent | an incomplete entry | none |
| Refinement of a type | `!T ^ { … }` | a type | `T` and its chain |
| Refinement of an incomplete entry | `!X ^ { … }`, a required field still absent | an incomplete entry | none |
| Completion | `!X { … }`, `!X value`, `X<A …>`, every required field `X` lacks | `!C { … }`, a type | none |

```
nonempty   => !meta.array ^ { min_items: 1 }
tags       => nonempty<text>                  ; !meta.array { element_type: text  min_items: 1 }
array_min  => !meta.array ^ { min_items: 10 }
max_things => !array_min ^ { max_items: 5 }   ; loads; max_things<text> is refused where it is completed
by_name    => !meta.map ^ { key_type: text }  ; a required field may be written, one being left absent
```

- **Completeness is derived from the fields**, never marked. A constructor is its own empty incomplete entry, so a
  construction left open and a refinement of an incomplete entry are one rule.
- **A field is written once**, by the entry, a refinement of it or its completion; one written again is refused
  whatever the value. To narrow, the entry is completed and the type refined.
- **Each field is read by its own type where it is written**, and a name it holds is judged when the schema links;
  the required fields, the defaults and the coherence of the fields with one another are judged where the entry is
  completed, and reported there.
- **It is a base for `^` and a head to complete, and nothing else.** Named where a type is — a field's type, a
  variant, an argument, a bound, an operand of `&`, a reference's `of` — it is a resolver error naming its absent
  fields, and its name as a tag in a data document is a validation error. An alias of one is another name for it.
- **A completion is the construction and nothing more**: no chain and no relation to the entry, equal to the
  construction written directly. The positional form completes an entry with exactly one required field absent.
- **Resolver output holds complete entries only.** An incomplete entry resolves to no value and is not written,
  nor an alias of one; every use closes to a plain construction, so nothing dangles. What a schema offers to be
  completed is its source's (§8.1, §10.1). A declared completion records where it came from in one derived key
  annotation, the incomplete entries it closed, nearest first and past alias hops, which an author may not write:

  ```
  bounded => !meta.array ^ { min_items: 2 }
  capped  => !bounded ^ { max_items: 5 }
  tags    => capped<text>
  ; resolves to
  @meta.completion:{ of: [capped bounded] }
  tags => !meta.array { element_type: text  min_items: 2  max_items: 5 }
  ```

The kernel follows. `meta.set_type` and `enum` become incomplete entries, and the root's `set` an alias of the first:

```
set_type => !meta.array ^ { voidable: false  ordered: false  unique_items: true }
enum     => !meta.enum_type ^ { name_type: meta.identifier }
set      => meta.set_type
```

`!enum [A B]` is `!meta.enum_type { name_type: meta.identifier  members: [A B] }` and `set<text>` is
`!meta.array { element_type: text  ordered: false  unique_items: true }`. Declaring an enum and typing a field by it
are unchanged. The refinement of a type a collection constructor built, which the same item introduced, is #12.

**What is running.** All of the above but the two remainders, a completed type reading in both encodings.

- `nonempty` and `tags` load, and `!tags []` is refused, "has 0 elements, fewer than the minimum 1". `max_things<text>`
  is refused at its declaration, "min_items 10 is above max_items 5".
- `!array_min ^ { min_items: 12 }` is refused, "a field of an incomplete entry is written once, whatever the value";
  `!meta.array ^ { element_type: text }` is refused as leaving nothing open, and `!enum ^ { members: [A B] }` as
  writing every field `enum` leaves absent.
- `r => { f: nonempty }`, `r => { a: enum }` and `meta.ref<meta.set_type>` are each refused, naming the absent
  field; `r => { a: meta.enum_type }` loads. A data document tagging a value `!nonempty` is refused: "'nonempty' is
  an incomplete 'meta.array' with [element_type] absent, not a type".
- `numeric-resolved.tn` writes its two enums and its set as constructions carrying `@meta.completion:{ of: [enum] }`
  and `@meta.completion:{ of: [meta.set_type] }`, and `meta-resolved.tn` writes neither `enum`, `set` nor
  `meta.set_type`.
- The linked schema holds an incomplete entry as a fourth case of its entries (`IncompleteEntry`: the constructor,
  the absent fields and the parsed node), so imports, collisions, annotations and alias hops needed nothing new, and
  the projection to resolver output leaves it out. The lookup that bound a refined constructor's instances as its
  parent, and the fact recorded for it, are deleted. `meta.identifier_ref` joins the kernel's hand-built wait cycle,
  `enum` writing a value at `name_type` where it is declared, and the cycle stays 31 entries, `meta.set_type`
  leaving it.

**What is not.** The two remainders above, each outstanding work in the implementation.

**Considered, and why it lost.**

- *An incomplete entry in resolver output*, under its constructor's tag with a read rule admitting a body that lacks
  required fields, or as a value of a constructor of its own. Lost: it is a partial form held as output, which is
  what the template was; a reader cannot tell a deliberate gap from a damaged entry; and the read rule was the
  expensive part of a throwaway spike, none of it built. Nothing needs it, every use closing to a construction.
- *A completion keeping the chain*, `tags` IS-A `nonempty`. The spike ran both. Kept, every declared enum and set
  gains a chain line in its resolved form and a resolved incomplete refinement loses the chain its body cannot carry;
  dropped, IS-A stays between types. The bound `<E: enum>` goes with it, and nothing is lost against Revision 39,
  where a bound naming a constructor was already refused.
- *The gap written as `_`* at a slot declared required and voidable: every body stays valid data of its constructor
  and needs no read rule, at the cost of a voidable field on every constructor and a new meaning for `_`.
- *A refinement of an incomplete entry that never writes a required field*, the stricter rule the spike built. Ruled
  the other way: one rule, `^` writing any unwritten field and leaving one required field absent.
- *Replacing a written field by a narrower value.* Ruled out: a field is written once, and the completion checks the
  whole.
- *Reading each field only at the completion*, which keeps `meta.identifier_ref` off the kernel's wait cycle. A
  published entry could then carry a field no completion can use.
- *A second map beside a schema's entries*, in a processor: both import merges, the collision rule, origins, key
  annotations and alias targets are each done twice.
- Left out of the design, not refused: named tops (`meta.array` and `meta.map` taking a `_type` name, with an `array`
  every array refines), which nothing built needed; `!X { … }` reading as a completion or a value entry by what `X`
  is; and incomplete entries beside `record`, `tuple`, `choice` and `ref`.

**What is lost.**

- **A field admitting only enums over identifiers.** `a: enum` typed a field whose value is an enum type; `enum` is no
  type, and `a: meta.enum_type` admits enums over any name type. Nothing narrower exists.
- **A reference admitting only sets.** `meta.ref<meta.set_type>` is refused, `meta.ref<meta.array>` is wider, and
  nothing narrower exists: a set is an array with two facets written.
- **A constructor narrowing a set.** `meta.set_type & { … }` is written `meta.array & { … }`, restating
  `voidable?: = false  ordered?: = false  unique_items?: = true`.
- **A set's `voidable: false` as a pin.** It held only through `meta.set_type`: a set constructed directly,
  `!meta.array { element_type: text  ordered: false  unique_items: true  voidable: true }`, loads, as it did in Revision
  39. A constructor composing `meta.array` pins it itself.
- **A resolved form listing every name its source offers.** What a schema offers to be completed is its source's.
- **A new category of entry.** An incomplete entry is neither type, value, alias nor extern, and every position that
  names a type refuses it by a rule of its own; a processor holds it beside the schema value for the documents that
  import it, the kernel's `enum` and `set` being used by every schema.

## 11. [TSON-SCHEMA] §5.5, §5.7, §5.8, §12.1 — `^` follows `!name` and takes values; `&` is the one way to derive a record

**Revision 40:** made in the proposal's text on the spec author's ruling (change log §5, "#11"), and written up here
at the close; no register entry carried it. The operator decides the body. `^` follows `!name` and its body is
values; it never declares a record or a constructor. `&` follows a bare name and its body is field definitions; it is
the one way to derive a record, and so the one way to declare a constructor. `refined-def` leaves §12.1, so
`name ^ { … }` is a parse error naming `name & { … }`, and `!R ^ { … }` over a record is a resolver error naming the
same. A restated field's rules, unchanged, stand in §5.8 with the composition body that writes one, and §5.7 states
refinement alone. The kernel's `meta.namespace` is a composition, `meta.map & { … }`. Part 2 header, §1, §4.1–§4.3,
§5.1, §5.2, §5.5, §5.7–§5.11, §8.1, §8.2, §12.1–§12.3 and [TSON-JSON] §6.1.5 (change log §3.2 items 105, 108, 109 and
127). **Still open in the implementation:** a constructor declared with `&` that adds a field cannot be instantiated.

**Problem.** Revision 39 gave `^` one meaning at every level — "`^` always means *refine, preserving IS-A*, at every
level — record types (`config ^ { ... }`), constructors (`meta.array ^ { ... }`, a constructor being a record, §4.1),
and atom and other instances" (§5.7) — and let the operand choose the body's grammar: "The two forms differ only in
the body's grammar: `!` before the operand means the body is data, and its absence means field declarations" (§5.5).
So a record was derived in two ways. Over a record, `&` expressed every type `^` did, and the two resolved to one
entry; resolver output does not record the operator, so no consumer of a resolved form could tell them apart:

```
base => abstract { kind: text  host: text  port?: integer ~ 8080  note?: text? }
r    => base ^ { kind?: = "r"  port?: = 9090  note?: text }
c    => base & { kind?: = "r"  port?: = 9090  note?: text }      ; the same entry as r
```

Record `^` added one check and no type: a body field that named no inherited field was refused, where `&` adds it.
Over a constructor the two spellings were again one entry and behaved apart in a processor (#10): a refined
constructor's instances bound as its parent by a recorded fact, and a composed one's did not bind. A restated field
had two homes, a refinement body (§5.7) and a composition body, whose restatements §5.8 sent to §5.7's rules; the
section named for one operator carried the other's rules.

**The design.** The operator decides the body.

| Intent | Revision 39 | Revision 40 |
|---|---|---|
| Narrow a record's fields | `employee => person ^ { age: adult }` | `employee => person & { age: adult }` |
| Nominal subtype of a record | `vip => person ^ {}` | `vip => person & {}` |
| Partly filled constructor | `meta.array ^ { min_items?: = 1 }` | `!meta.array ^ { min_items: 1 }`, incomplete (#10) |
| New constructor | `meta.map ^ { … }` or `&` | `meta.map & { … }` only |

- **A record takes no `^`**, as a tuple or a choice takes none: a record type is a value of `meta.record`, which states
  rules for a field restated in a composition body and none for a body of values.
- **A restated field in an `&` body follows the rules it followed in a refinement body.** They move whole to §5.8:
  the three orders a field's facts move along, a restated type narrowing by IS-A, a default changing where a pin does
  not, narrowing to `void`, the elided type, a value in a parametric record, and body materialisation. §5.7 keeps
  what `^` refines and the facet kinds. Section numbers stand, and each citation of §5.7 for a restated field names
  §5.8.
- **`meta.namespace` is a composition.** `namespace => meta.map ^ { key_type: meta.identifier_ref  voidable?: =
  false }` restated a field under a narrower declared type and pinned another. That changes a field's declaration and
  narrows no value: a slot's declared type belongs to the constructor, and narrowing it makes one, which is `&`'s.
  Its resolved form, its chain and every namespace type are unchanged.

**What is running.**

- `e => person ^ { age?: integer }` and `minlist => meta.array ^ { min_items?: = 1 }` are refused at the `^`: "'^'
  follows '!name' and takes values (§5.5, §5.7), and 'person' has no '!': a record's fields are restated in a
  composition, 'person & { ... }' (§5.8)". `!person ^ { name: x }` is refused: "'person' is a record, which takes no
  '^'".
- `e => person & { agee?: integer }` loads, and a document writing `agee` reads; `e => person & { agee?: ~ 5 }` is
  refused, a modifier-only entry naming no inherited field.
- The kernel's `meta.namespace` moved with no code change and no fixture change, §5.8 already admitting a restatement
  in a composition body. The resolver's record-refinement path is deleted.

- `minlist => meta.array & { min_items?: = 1 }` loads, and `minlist<text>` is `!minlist { element_type: text }`, its
  pin in force: an instance of a constructor composed over a constructor of types with no field added binds as its
  one direct parent, `meta.record` excepted.

**What is not.** One gap on the form that is now the only one, outstanding work in the implementation. An instance
of a constructor composed with a field added, or composed over `meta.record`, is reported as not implemented.

**Considered, and why it lost.**

- *Record `^` kept over a plain record and refused over a constructor alone*, the design as first proposed. Removing
  it everywhere gives each operator one body grammar, `^` always taking values and always following `!`, and `!` no
  longer selects a grammar. The spec author's ruling took the removal.
- *Record `^` over a constructor kept, under one of its three readings*: a second spelling of the composition; the
  entry whose instances bind as its parent by a recorded fact; or the construction left open, with its pin read as a
  bound. The form is refused instead, its diagnostic pointing at the construction left open for values of what the
  constructor builds and at the composition for a change to what it admits.
- *§5.7 retitled to cover both operators*, the rules for a restated field staying where they were. Ruled: they move
  to §5.8 (change log §5, "#11, #20").

**What is lost.**

- **The typo check.** `person ^ { agee: adult }` was refused, `agee` naming no field of `person`;
  `person & { agee: adult }` adds a field and loads. There is no replacement. A modifier-only entry naming no
  inherited field is still refused, so the check survives where a restatement writes no type.
- **The re-spelling.** A measurement taken for the design counted record `^` in 62 places of Part 2's prose and
  examples, 3 kernel declarations (with every bundled pin re-stamped) and 37 corpus spellings, 9 of them over a
  constructor; the HTTP layer built on this library wrote none, against 13 compositions.

## 12. [TSON-SCHEMA] §4.2, §4.3, §5.7 — `^` refines a collection type, a namespace type among them

**Revision 40:** made in the proposal's text with open construction (#10), and written up here at the close; no
register entry carried it. A type built by `meta.array`, by `meta.map` or by a constructor that IS-A either is refined
by `!T ^ { values }`, and the result IS-A `T`. The collection facets take §5.7's kinds: `min_items` and `max_items`
are ordered bounds, `unique_items` and `voidable` permissions, and `ordered` and the type slots fixed at construction.
A facet fixed at construction that holds a type's name — `element_type`, `key_type`, `value_type`, an enum's
`name_type` — is restated as the type it names, one by the entry a name reaches and not by spelling. Part 2 §4.2,
§4.3, §5.5, §5.7 (change log §3.2 items 106, 120 and 121). That a namespace type is such a source is the spec
author's ruling (change log §5, "#12").

**Problem.** Revision 39's §5.7: "A container instance (`lookup => {text => integer}`) is **finished**: its bindings
are set, `meta.map` states no tightening rule for them, and `^` on it is a resolver error", and "authors wanting a
narrower relative write the bounds on the form (`strict_lookup => {text => integer; 1..}`)". A narrower collection was
another construction, a peer with no IS-A to the type it narrowed, and the collection facets had no kind among §5.7's
seven, no array or map being refinable; §5.7 left "a structural narrowing between containers" as "a rule of its own
for each container constructor to state". Two narrower gaps followed once collections were refined. As the text
first stated it with open construction, the source of `^` was a type `meta.array` or `meta.map` built, so a namespace
type, built by `meta.namespace`, which IS-A `meta.map`, was refused. And a type-holding facet was compared by
spelling: over `label => text`, `!tags ^ { element_type: label }` was refused, "label replaces the source's own text",
an alias read as another type at this one position, where §5.2 already judged a record's `name_type` by the entry its
name reaches.

**The design.** Collections are refined as atoms are, each facet by its kind, against the source's effective value,
its default where it wrote none:

```
tags      => [text]
some_tags => !tags ^ { min_items: 1 }          ; IS-A tags
label     => text
short     => !tags ^ { element_type: label }   ; restates the slot, label being text
api       => !meta.namespace { key_type: meta.identifier  value_type: method }
small     => !api ^ { max_items: 3 }           ; a namespace type IS-A api
```

`!tags ^ { element_type: uuid }` and `!tags ^ { ordered: false }` are refused: another element type or another order
is another type, and its construction. The rules are the fields', so a constructor that composes a collection
constructor keeps them for the fields it takes, and a field it adds beside them is fixed at construction:
`!api ^ { value_type: text }` is refused as another value type is over a map. A record, a tuple, a choice and a
namespace value stay finished to `^`. The resolved value states a restated slot as the source wrote it.

**What is running.** `short => !tags ^ { max_items: 3 }` loads and types a field; `!tags ^ { element_type: label
max_items: 3 }` loads; `!tags ^ { ordered: false }` is refused, "ordered false replaces the source's own true, and a
refinement only restates ordered"; `small => !api ^ { max_items: 3 }` loads and `!api ^ { value_type: other }` is
refused the same way; `!p ^ {}` over a tuple is refused as finished. The resolver's source test is that the
constructor is `meta.array` or `meta.map` or lists one among its supertypes, with no case per constructor, and a slot
restated by another name is judged at link, so an alias declared later or imported is followed.

**Considered, and why it lost.** *The source kept to the two constructors themselves*, so that a namespace type is
bounded only where it is constructed. Ruled the other way, as the general rule, with no case for a namespace.

**What is lost.** Nothing Revision 39 admitted is refused.

## 13. [TSON-DATA] §3.3; [TSON-SCHEMA] §3.5, §5.13, §7.8, §7.9; [TSON-JSON] §8.6 — an extern; the scope of a foreign value

**Revision 40:** made in the proposal's text on the spec author's rulings (change log §5, "#13"), and written up here
at the close; no register entry carried it. `order_v1 => !meta.extern { schema: "…/order-1.tn"  type: order }` brings
one entry of another schema into a document under a name the document chooses, and none of that schema's names enter
the document's namespace. An extern is a hop judged by its target's identity; it names a type a value can have; it is
sealed against derivation, a default, a pin and a qualified name; and it is a dependency read with the imports, on the
same library, policy and kernel, the reference relation over imports and externs acyclic. A value at a position typed
by an extern is read against the target in the foreign schema's scope, and the tag in front of it is the enclosing
scope's. Part 1 §2.1, §2.2.1, §3.2, §3.3, §9.1; Part 2 §1.4, §2.2.3, §3, §3.3, §3.3.1, §3.4.1, §3.5, §4.1, §5.12,
§5.13 (new), §6, §7, §7.2, §7.8, §7.9 (new), §8.1, §8.3, §9, §10.1, §10.2, §11.2, §11.4, §11.5; Part 3 §1.6, §3.3,
§6.4, §8.2, §8.6 (new), §9.2 (change log §3.1 item 21, §3.2 items 114–116 and 124, §3.4 items 13 and 16). The JSON
half is Part 3's, edited directly. **Still open in the implementation:** in TSON text, a root typed by an extern and
an extern reached through an alias of a container write no boundary tag; a tag naming an extern at a position no
extern types is refused in both encodings; a map key typed by an extern reports outside the foreign scope in JSON; no
JSON writer writes a `$type`; and the import closure limit is counted by nothing, externs included.

**Problem.** Revision 39 removed Revision 38's external forms — the `!!schema` directive on a value, the scope push,
the `extern` and `dynamic` constructors and the foreign-schemas limit (Revision 39's register #6) — and put nothing in
their place. §7.8 gave the absence as the design:

> A schema that means to admit a type from a document it does not import has no way to say so, by design: a foreign
> schema that kept its own names would put two namespaces in one document, so either a tag's meaning would depend on
> its position or the data would carry a prefix, and neither is taken.

So a schema could address only the namespace its imports bind, and §2.2.3 admits one declaration of a name to a
closure. Two versions of one schema that both declare `order` could be named by no one document. An HTTP layer built
on this library serves `order-1.tn` and `order-2.tn` side by side, and its service description, which names the types
its methods take at `meta.type_ref` slots, could name only one: importing both is refused, `'card' is declared by two
different schemas reached through !!import`. §3.5 left "accepting data validated against multiple schema versions" as
"a deployment concern", and the series gave a schema no way to state it. A layout answers the case with no new
construct — the version in the path, `orders => !schema { v1 => !schema { … } }`, one open map across two documents —
but reaches only schemas whose authors can still choose their names.

**The design.** The spec author's ruling is that the schema layer needs an external reference, in a form under which
the data still names no schema, and that the form is singular and a scope change. It takes the first branch §7.8
refused — a tag's meaning depends on its position — and bounds it: the positions are the schema's, never the data's.

```
!!id:"https://example.com/shipping.tn"
!!import:"https://tson.io/2026/40/m/core.tn"
!schema {
  order_v1 => !meta.extern { schema: "https://example.com/order-1.tn"  type: order }
  order_v2 => !meta.extern { schema: "https://example.com/order-2.tn"  type: order }
  envelope => { id: uuid  body: (order_v1 | order_v2) }
}
```
```
!!import:"https://example.com/shipping.tn"
!envelope { id: 9f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f  body: !order_v2 { sku: A  payment: !card { number: "4111" } } }
```

`!order_v2` is the reading document's name, and `!card` is `order-2.tn`'s. The kernel gains
`extern => meta.top & { schema: iri_reference  type: text }` beside `meta.alias`.

- **A hop.** An extern is another name for its target, as an alias is for its own, across a schema boundary. Its
  identity is the target's — the declaring schema and the path there — and every judgement through it is made of the
  target, by identity: a reference's membership (`meta.ref<meta.record>` admits `order_v1`), a choice's distinct
  variants (two externs of one entry in one choice are one type twice) and its disjointness
  (`(order_v1 | order_v2)`, two records, discriminates by tag). Two names for one type is what an alias already
  gives; nothing lets one name mean two types. The entry is no type and carries no chain.
- **What it names.** A type a value can have, anywhere in the foreign schema's namespace: a record, abstract or not,
  an atom instance, an enum, a choice, a named collection, an alias of one, a declared application, a member of a
  nested schema map, another extern. A parametric record, an incomplete entry, a constructor, a value entry, a schema
  map and a name the foreign namespace lacks are each refused. A schema that wants another's value or constructor
  imports it. Several externs under one prefix are a nested schema map of them.
- **Where it stands.** Where a type's name stands — a field, element, map key or value, tuple position, choice
  variant, argument, bound, alias target — and at a slot typed by a reference over a constructor.
- **Sealed, and the boundary absolute.** Nothing is derived from an extern: `&`, `-`, `^`, an application head and a
  construction head are refused, and so are a default or a pin at a field typed by one, through an alias of the extern
  as directly. A record derived from an extern would carry fields typed by another namespace's names, and its values
  would lie half in each. No qualified name selects through an extern, in a schema or in data.
- **`type` is `text`** and `schema` an `iri_reference`. The name is judged in the foreign schema's namespace, so
  `meta.type_ref`, judged in the declaring document's, would state what is not so. `schema` is read on an `!!import`
  value's terms, so an extern names every schema an import may, a path-only reference to a library entry among them.
- **A dependency, read with the imports.** An extern is recognised by its written head, an alias of `meta.extern` at
  the head being refused, so a document's externs are found before any name resolves, and each foreign schema joins
  the reference closure on an import's terms: the same library and policy, one digest per identity across imports and
  externs, resolved first, counted by §11.5's import closure. Three requirements refuse the declaring document: every
  foreign schema loads and holds an admitted target; a kernel the foreign closure holds is the declaring document's,
  by identity, since a judgement through the hop compares entries of one kernel; and the reference relation over
  imports and externs together is acyclic. Revision 38's foreign-schemas limit stays retired: the schema fixes which
  foreign schemas a document reaches, and the data adds none.
- **The scope.** Every name inside a foreign value — a tag, an annotation, a name at a reference-typed slot, the type
  named at a declared position — resolves in the foreign schema's namespace and no other, so a foreign value is
  written exactly as in a document importing its schema. Scopes nest as values do, and a name means one type
  throughout a scope. A document's scopes are where its schema says and never where its data does: no directive, tag
  or annotation in a value opens one, and [TSON-DATA] §3.3's parse error for a directive at a value position stands.
- **The boundary is the enclosing scope's.** The tag and annotations in front of a foreign value are read where the
  position is: at a choice the tag selects the variant before any scope is known, and `(order_v1 | order_v2)` read
  the other way would be `!order` against `!order`. A tag there is admitted as §7.2 admits one at a position of the
  target, each name followed to the entry it reaches and compared by identity: the extern; a name of the enclosing
  scope reaching the target or a subtype of it, an extern of a subtype or the entry's own name where the scope imports
  its schema; or a variant where the target is a choice. A tag naming the position's own extern says no more than the
  position does.
- **A family across the boundary** (item 124). A subtype or a variant is tagged at the boundary only by a name the
  enclosing scope holds, so a family meant to be read through an extern names selectors, and the foreign schema adds a
  member without the declaring one changing; where the target is abstract and names none, or is a choice told apart
  by tag, each tagged type is an extern of its own.
- **One document.** Limits, policies and name hygiene run across the boundary, each name judged in the scope that
  reads it, so a look-alike pair with one name in each scope is a pair in none (§11.4). A diagnostic inside a foreign
  value identifies the foreign schema and SHOULD name the extern it was read through.
- **JSON** (Part 3 §8.6, §3.3). One object carries both scopes, so the `$type` leading the object at an extern position,
  or its wrapper, is the boundary's and every other name is the foreign schema's. A wrapper's `$value` is the
  annotated value itself and carries no `$type` of its own, so a subtype across the boundary is named as in text, by
  a name of the enclosing scope: one value, one type name, in both encodings.

§3.5 says what follows for versions: two that declare a name in common cannot share an import closure, and a schema
names types of both through externs, or the versions declare their names under maps that differ.

**What is running.** Every rule above, with the remainders listed in the **Revision 40** line; the first
implementation's tests read and bind a value through an extern in TSON text and in JSON.

- The schema and data documents above validate. An untagged `body` is refused as needing a tag naming one of
  `[order_v1, order_v2]`, and `payment: !order_v1 { … }` inside the `order_v2` value is refused at `/body/payment`,
  "read in the scope of example.com/order-2.tn, through the extern 'order_v2' of example.com/shipping.tn".
- Importing `order-1.tn` and `order-2.tn` together is refused, and naming both through externs loads.
- Over `o1`, an extern of `order-1.tn`'s `order`: `more => o1 & { note: text }`, `v => !o1 { sku: A }` and a default
  at a field typed `o1` are each refused, "an extern is sealed"; `o1.card` as a default at a `meta.type_ref` slot
  "names nothing in scope"; `o1` as a default at a `meta.ref<meta.record>` slot loads; a second extern of the same
  entry beside `o1` in one choice is refused, both resolving to `order` of `example.com/order-1.tn`; and an extern of
  `order-1.tn`'s parametric `box` is refused, a parametric record denoting no type until it is applied.
- A foreign schema importing `/2026/39/m/meta.tn` is refused, "its closure holds the kernel
  'tson.io/2026/39/m/meta.tn', and the schema naming it holds the kernel 'tson.io/2026/40/m/meta.tn'". A schema
  externing its own identity is refused as "a reference cycle through an extern".
- A foreign value's body is read by the foreign schema's own compiled reader, each foreign schema compiled once per
  identity and mode; the boundary consumes the tag and the annotations and hands that reader the value untagged. What
  a boundary tag may name is fixed when the schema compiles, by identity, and both encodings read the one fact.
- A host binder is asked a class by the entry's identity, declaring schema and name. One binder serves the declaring
  schema and every foreign one, and a throwaway spike found that two schemas' `card` then collided in bind mode: the
  collision an extern removes from the schema came back in the host until the binder was told the schema.

**Considered, and why it lost.** The change log's §5 lists the alternatives to the form; the design notes add the
alternatives within it.

- *An open sum over a foreign schema, the place deciding the mapping.* A sum gives a place, where references, type
  slots, choices and `&` take a name, and whoever declares the place must know the foreign schema: a layer declaring
  `response?: meta.type_ref` does not know which schemas a service description will use.
- *A namespace entry reached by a prefix*, `v1 => !meta.extern { schema: … }` then `v1.order`, with or without a list
  of admitted types. Written in data, one value has two spellings, `!appeal` alone and `!v1.appeal` embedded, a writer
  maps identities back to paths, and a type two closures share has two paths. A prefix is a nested map of externs.
- *An extern admitted at reference slots alone.* No data ever holds a foreign value, so nothing changes scope, and a
  schema cannot hold a foreign value at all.
- *A `!!schema` directive in the value* (Revision 38): the data chooses its own schema. *A type carried in the data*
  (Revision 38's `dynamic`, Protocol Buffers' `Any`): data-driven, and not coming back.
- *A versioned layout in place of any form*: it reaches only schemas whose authors can still choose their names, and
  stays as guidance in §3.5.
- *`type` typed `meta.type_ref`*, which would claim a name judged in the declaring namespace; *`schema` typed `iri`*,
  which requires a scheme and refused `/local/orders.tn` where `file:/local/orders.tn`, the same identity, passed.
- *An alias of `meta.extern` at the head.* Refused as the easier of the two: an extern is then found before anything
  resolves, and an alias that resolved would build an extern whose schema nothing loaded.
- *Loading a foreign schema when the declaring schema links*: too late for a value read while the schema resolves, an
  annotation through an extern among them, and it leaves the cycle guard blind to the extern edge.
- *Comparing across the boundary by spelling*: exact only while both closures hold one kernel and share no other
  schema.
- *Admitting externs alone as boundary tags*, the narrower reading of which tags the boundary admits. Ruled: by
  identity, as written, as §7.2 admits a tag.
- *Refusing an abstract record or a choice as a target*, since a subtype across the boundary needs a name of the
  enclosing scope: it would refuse the selector-dispatched family, and a record that is not abstract has subtypes too.
- *A second tag in text*, `!order_v1 !rush { … }`, matching what a JSON wrapper's inner `$type` could write: one
  value with two spellings in both encodings. Ruled: one type name per value, JSON aligned to text.

**What is lost.**

- **One namespace per document.** Revision 39's data document named every type from the one namespace its imports
  bind; inside a foreign value the names are another schema's. The data still names no schema and opens no scope,
  and a name means one type throughout a scope, but a type name alone no longer identifies a type to a host: a tree
  consumer or a binder needs the schema with the name, and the first implementation asks its binder by declaring
  schema and name.
- **An extern of a schema written against another revision.** The one-kernel requirement refuses it, with the reason
  the text gives: a judgement through the hop compares entries of one kernel, by identity. The first implementation
  also pre-loads its own kernel alone, so another revision's kernel would fail as an ordinary document, about its
  grammar. Neither the text nor the change log names a route for reading such a schema through an extern.
- **Derivation from a foreign type.** Nothing composes, refines, subtracts from, applies or constructs an extern, and
  no default or pin names a foreign value at a field typed by one; a schema wanting more than the type imports the
  schema.
- **A foreign subtype named by the foreign schema's name at the boundary.** It is named by a name of the enclosing
  scope, an extern of its own in the usual case; a family read through an extern names selectors to avoid one extern
  per member.
- **Look-alike checking across scopes.** Name hygiene compares names within a scope, so two scopes may hold names
  that render alike.
- **A member named `extern` contributed to the kernel's `meta` map** now collides, and is renamed.

## 14. [TSON-SCHEMA] §4.2, §9; [TSON-JSON] §1.6, §5.6 — one reference constructor, `meta.ref`

**Revision 40:** made in the proposal's text, and written up here at the close; #6 carried the step that left the two
constructors with nothing between them and states the result among what runs. Revision 39's `meta.reference_type`
and its `ref`, applied over it, are one constructor, `ref => meta.atom & { of: meta.type_ref }`, with
`type_ref => !meta.ref { of: meta.type }`; `meta.ref<T>` is the construction `!meta.ref { of: T }`, and whether a
reference names types or values is read from `of`. Part 2 header, §4.2, §9, §13; Part 3 §1.6 and §5.6 (change log §3.2
items 97–100, §3.4 item 10).

**Problem.** Revision 39's kernel spelled a reference with five entries and a permission facet:

```
reference_type => meta.atom & { of: meta.type_ref  allow_sibling?: boolean ~ true }
ref            => <T> !meta.reference_type { of: T  allow_sibling: false }
field_type_ref => !meta.reference_type { of: meta.type }
type_ref       => !meta.field_type_ref ^ { allow_sibling: false }
identifier_ref => !meta.type_ref ^ { of: unicode.identifier_type }
```

`ref` was a template over a body that is not a record, which #3 removes, and `allow_sibling` and `field_type_ref`
existed for the sibling reference, which #6 removes. With both gone, `meta.reference_type` and `meta.ref` were two
constructors for one thing, `meta.ref` an empty refinement of the other, and which one a schema wrote decided nothing.

**The design.** One constructor, under the name an author writes:

```
ref            => meta.atom & { of: meta.type_ref }
type_ref       => !meta.ref { of: meta.type }
identifier_ref => !meta.type_ref ^ { of: unicode.identifier_type }
```

`meta.ref<T>` keeps its spelling, now construction sugar over a constructor (#3). The kernel's documentation of the
two merges onto `meta.ref`, and a schema naming `meta.reference_type` names an unresolved entry.

**What is running.** `!meta.ref { of: order }` and `meta.ref<order>` both load and type fields, and a field typed
`meta.reference_type` is refused as an unresolved reference. In the first implementation a reference naming a value
reads to a `ValueRef` holding the name and the value it names, one naming a type to a `TypeRef`, and a `TypeRef`
component bound at a value's slot is a bind mismatch when the schema compiles.

**What is lost.** Nothing a schema could say: `!meta.reference_type { of: X }` is `!meta.ref { of: X }` and
`meta.reference_type & { … }` is `meta.ref & { … }`. Reading from `of` whether a reference names types or values,
which the proposal examined among the costs of references, is kept on purpose.

## 15. [TSON-SCHEMA] §5.4, §5.10, §5.13 — a nested choice is disjoint; a choice over a parameter is judged where it closes

**Revision 40:** made in the proposal's text, each rule on the spec author's ruling (change log §5, "#15" and "Items
77–83 (ruled)"), and written up here at the close; no register entry carried them. A variant that is a choice is
disjoint: the tag naming it is spent, and the value is read there untagged, so a choice whose variant is a choice that
is not disjoint is a resolver error at the declaration, written with the variants listed, and a choice nests one deep.
The variant is judged at the entry it reaches, past aliases and externs. A tag names a variant by the entry it
reaches, past its alias chain and an extern. A choice over a parameter loads at the parametric record, and each
application is judged where it closes. Part 2 §5.4, §5.10, §5.13 (change log §3.2 items 79, 125 and 126; item 79 is
also among #3's).

**Problem.** Three cases §5.4 did not decide.

- *A choice as a variant.* Revision 39's §5.4 counted "a nested choice" among the types with no class, "and a
  classless variant makes its choice not disjoint". So `( text | either )` over `either => ( a | b )`, two records,
  loaded, and a value of `a` or `b` had no spelling in either encoding: the outer choice takes a tag, `!either { … }`
  selects the variant, the value is then read at `either`, which needs a tag of its own, and a value carries one;
  `!a { … }` is refused, a member of a choice not being IS-A it. Where the nested choice was disjoint the text did not
  say the tag was spent, and the first implementation refused `!narrow 5` at `( boolean | narrow )` over
  `narrow => ( int32 | text )`, "'narrow' is not a declared variant of 'narrow'", where its JSON reader read
  `{"$type":"narrow","$value":5}`.
- *What naming a variant is.* #4's rule, as first written, selected a variant the tag names before one its type only
  IS-A, and did not say what naming is where a variant is an alias or an extern.
- *A choice over a parameter.* Revision 39 held a template's choice body as text and closed it per application
  (`result => <T> ( T | error )`). Inside a parametric record a choice over a parameter is written in place,
  `v: ( T | int32 )`, and whether its variants are distinct and whether it is disjoint depend on the argument:
  `pick<text>` is disjoint, and `pick<int32>` lists `int32` twice.

**The design.**

- **A variant that is a choice is disjoint.** The tag selecting it is spent naming it, and the value is read at the
  nested choice untagged, which only a disjoint choice can do; a choice holding a choice is never disjoint, so a choice
  nests one deep. The rule is the one that refuses a variant written in place in a choice that is not disjoint, one
  level down: there no tag names the variant, here the tag is spent naming it.

  ```
  narrow => ( int32 | text )
  outer  => ( boolean | narrow )      ; loads; !narrow 5 reads 5 at narrow, untagged
  either => ( a | b )                 ; a and b two records
  bad    => ( text | either )         ; resolver error: written ( text | a | b )
  ```

- **Judged at the entry it reaches.** A variant reached through an alias or an extern is judged at the choice it
  reaches, every judgement through an extern being made of its target (#13): over `either_x`, an extern of such a
  choice, `( text | either_x )` is refused the same, and is written with an extern of each variant.
- **A tag names a variant by the entry it reaches**, past its alias chain and an extern, as every judgement through
  a hop is made.
- **A choice over a parameter is judged where it is applied.** The parametric record's own choice states
  `disjoint: false`, a parameter having no class, and each application is judged where it closes, its variants'
  distinctness and its disjointness alike.

**What is running.** `( text | either )` is refused at its declaration: "the choice '( text | either )' has a variant
[either] that is a choice not disjoint … list its variants in its place, '( text | a | b )'". `outer` loads and a
document writing `!narrow 5` at it reads, in both encodings and both read modes. The first implementation's tests hold
an extern of a foreign choice that is not disjoint refused, an alias of an extern of a foreign alias followed to the
choice, and an extern of a disjoint foreign choice loading and reading; a tag at a choice of externs reaches a subtype
through an extern, in both encodings. `pick => <T> { v: ( T | int32 ) }` loads, `pick<text>` loads, and
`p => pick<int32>` is refused at `p`, "'(int32 | int32)' lists the variant 'int32' twice".

**Considered, and why it lost.**

- *Refusing every nested choice.* It would refuse `( boolean | narrow )`, which reads.
- *A nested choice standing for its variants*, a tag naming a variant's variant and disjointness judged over all of
  them. That is the question of how sums are expressed, and is left to the next revision; it would lift this refusal
  and refuse nothing this text admits.
- *The nested choice judged past aliases and stopped at an extern*, as the rule was first built: `( text | either_x )`
  then loaded and held no value a document could write.
- *For a choice over a parameter*: refusing it at the declaration, or requiring it to be disjoint for every argument.

**What is lost.** A choice that is not disjoint, as a variant. Its variants are listed in the outer choice, or through
an extern an extern of each variant. No value a document could write at the nested choice is lost.

## 16. [TSON-SCHEMA] §4.1, §5.5, §8.1 — an entry has no derived kind

**Revision 40:** made in the proposal's text, and written up here at the close; no register entry carried it.
§8.1's rule labelling each entry ATOM, PRODUCT, SUM or ALIAS, and §4.1's statement that every entry has exactly one
kind, are deleted. The base kinds `meta.atom`, `meta.product` and `meta.sum` stay as kernel entries, and §5.5's rule
that a constructor's chain reaches at most one gives its reason: two are a diamond over `meta.type`, refused by §5.8.
"-kinded" is reworded where it stood, and the glossary's **kind** is a reference's admitted set alone. Part 2 §4.1,
§5.2, §5.4, §5.5, §7.8, §8.1, §8.3 (change log §3.2 item 132).

**Problem.** Revision 39's §4.1: "What is universal is *kind* membership — every closed type or alias entry has exactly
one kind, derived from where its value's constructor reaches the kernel's order", and "Bare `{...}` definitions
without explicit composition resolve to PRODUCT by structural default, and so does an entry composing with `meta.type`
or `meta.top` alone". §5.5 settled a constructor's kind the same way: "Zero base kinds in the chain → PRODUCT by
structural default; exactly one → that kind; two or more → resolver error". No rule of the series turned on the label,
and its default was wrong: `meta.top`, `meta.type`, `meta.alias`, `meta.atom` and `meta.sum` came out PRODUCT, as did
any constructor reaching no base kind, `meta.apply` among them. The extern (#13) would have needed a fifth label,
EXTERN, published only to be removed.

**The design.** No kind is derived. What a consumer read from the label it reads from the entry: an alias or an
extern by its tag, the base kind of a type by whether its constructor IS-A `meta.atom`, `meta.product` or `meta.sum`.
A constructor reaching two base kinds stays refused, by §5.8's diamond rule, each base kind holding `meta.type`'s
`chain`; §5.5 states that as its reason and keeps no rule of its own.

**What is running.** The first implementation's derived kind is deleted, its two lookups with it; nothing in its main
code decided by them. Its separate two-base-kinds check is deleted as unreachable, the diamond refusing first, and a
test pins that refusal through the front door.

**What is lost.** Nothing a rule used. A consumer of resolver output that read the label reads the entry's tag or its
constructor's chain.

## 17. [TSON-SCHEMA] §5.2, §5.10, §12.1 — `extension` is the record definition's: its selectors and its mark

**Revision 40:** made in the proposal's text on the spec author's rulings (change log §5, "#17"), and written up here
at the close; no register entry carried them. `discriminators` on a record written as `!meta.record`, declared or in
place, is a resolver error unless its `extension` is `ABSTRACT`, a missing `extension` being `OPEN` by the field's
default. A definition mark, `abstract` or `final`, is sugar for a record definition's `extension`, and §12.1 has it in
that alternative alone, `[ definition-mark ws ] [type-params] structural-def`: before `!`, a name, a declared
application, a sugar form or a choice it is a parse error at the mark. Part 2 §5.2, §5.10, §12.1 (change log §3.2
items 133 and 136). **Still open in the implementation:** a record written as `!meta.record` is not judged against
§8.1's invariants for a `meta.record_field`, and `extension: FINAL` on a parametric record written so loads.

**Problem.** Revision 39's §5.2 ruled on the source marks alone: "`=?` **implies ABSTRACT**: a record its members are
selected from has no values of its own. Writing `abstract` beside a selector asserts what the body already says and is
admitted …; `final` beside one is refused". Nothing gave the kernel's own form a verdict, so
`{ … extension: FINAL  discriminators: [x] }` could load as a record no value names, and the first implementation
derived ABSTRACT over whatever was written, so this loaded as an abstract record, its missing `extension` ignored:

```
pet => !meta.record {
  fields: { pet_type => !meta.record_field { type: text } }
  discriminators: [pet_type]
}
```

And §12.1 read the mark before every type definition, `[ definition-mark ws ] type-def`, noting that "`definition-mark`
is read unconditionally", the resolver refusing it on a definition that was no record. Before the kernel's form it was
admitted, and overrode a stated field:

```
p => abstract !meta.record { fields: { … }  extension: FINAL }                              ; loaded, abstract
p => final    !meta.record { fields: { … }  extension: ABSTRACT  discriminators: [kind] }   ; refused, as FINAL
```

Before a declared application, `x => abstract box<text>`, it made that one declaration's record abstract in place of
the head's `extension`.

**The design.** A stated field means what it says, and the mark belongs to the syntax that lowers to it.

- **`discriminators` requires `extension: ABSTRACT` beside it** on `!meta.record`, a missing `extension` being its
  default, `OPEN`, and refused. The `=?` sugar is unchanged: it lowers to both.
- **The mark is sugar for a record definition's `extension`**, `!meta.record { … }` being what it lowers to, so it
  stands before a record body, a composition or a subtraction, with or without a parameter list, and nowhere else.
  A resolved form, whose entries are constructions, holds none. A declared application is substitution and nothing
  more, its `extension` its head's: every application abstract is the parametric record marked,
  `box => abstract <T> { … }`, and one application marked alone is a record composing it,
  `x => abstract box<text> & {}`, as a value differing for one application is a restatement where it is declared.

**What is running.** A record stating `discriminators` and no `extension` is refused: "'p' states 'discriminators: [k]'
beside 'extension: OPEN', the default where none is written … write 'extension: ABSTRACT' beside 'discriminators'"; with
`extension: ABSTRACT` it loads. `abstract !meta.record { … }`, `abstract box<text>` and `abstract text` are each a
parse error at the mark, "'abstract' marks a record definition -- a record body, a composition or a subtraction --
and none follows it", the last of them a resolver error before; `x => abstract box<text> & {}` loads.

**Considered, and why it lost.**

- *Deriving ABSTRACT from `discriminators` over what is stated*: it made a stated field mean something else.
- *Admitting the mark before `!meta.record` where `extension` is unwritten or agrees, and refusing a contradiction*:
  two cases where the grammar needs none.

**What is lost.** A mark on one declared application: `x => abstract box<text>` made that declaration's record
ABSTRACT, or FINAL, in place of the head's `extension`. The replacement, `x => abstract box<text> & {}`, is a new
record composing the application and not the application's declaration: its key carries no `@meta.application`, and
where the application is declared elsewhere it is a subtype of that declaration. A mark before the kernel's form is
written as the `extension` field.

## 18. [TSON-SCHEMA] §5.10, §8.3 — an alias of a parametric record is another name for it

**Revision 40:** made in the proposal's text on the spec author's ruling (change log §5, "#18"), and written up here
at the close. `bx => box` gives `box` another name: `bx<text>` is the application `box<text>` and one type with it, and
`bx` is named bare at no position, as `box` is at none. Part 2 §5.10, §8.3 (change log §3.2 item 135). **Still open in
the implementation:** no conformance vector holds an application through such an alias at a declaration or at a
field's type, only at a composition operand.

**Problem.** Revision 39 had alias templates, an open application under a name (`uuid_pair => <B> pair<uuid, B>`, held
as `!meta.alias { target: pair<uuid B> }`), and a record template a position could name bare as a family base. #3
removed both: there is no parametric alias, and no position names a parametric record. Revision 40's text then listed
every slot typed `meta.type_ref`, an alias's `target` among them, as naming no parametric record, while §8.3 followed
an application's head through an alias: whether `bx => box` was refused or applied as `box` is, it did not say.

**The design.** An alias's `target` is no position. An alias of a parametric record is applied as its target is, as an
alias of a constructor and of an incomplete entry are, and named bare nowhere.

**What is running.** `bx => box` with `f: bx<text>` and `g: box<text>` loads, and `f: bx` is refused, "'bx', which is
no type a value can have". The first implementation applies the parametric record the alias reaches, so `bx<text>`
closes to the record `box<text>` closes to.

**Considered, and why it lost.** *Refusing `bx => box`*: a rule for one target of an alias, where a constructor and an
incomplete entry have none.

**What is lost.** Nothing: the alias template, a partial application under a name, went with #3.

## 19. [TSON-SCHEMA] §3.4.1 — a value a family places by its pins waits on each declaration that may be its member

**Revision 40:** made in the proposal's text, and written up here at the close; no register entry carried it.
§3.4.1's rule 1 gains: a value read at a family's selectors whose pins no ready member states waits on each declaration
not yet ready that may be that member, judged from what it writes — its written operands reach the family's base, and
the nearest pin written along them does not differ from the value's. Part 2 §3.4.1 (change log §3.2 item 101).

**Problem.** Revision 39's rule 1 named what a read waits on: "Reading a value against a type `T` waits on `T` being
ready, and on each type a part of the value is read against as it is reached: a field's type, an element's, a nested
`!` tag's, an annotation's." A value a family places by its pins (§5.2) names no member, so nothing waited on the
member it selects, and a schema the full closure accepts was refused by declaration order:

```
route  => abstract { kind: text =?  path: text }
routed => { r: route }
posts  => !routed { r: { kind: put  path: "/" } }   ; refused: no member of 'route' pins kind to put
put    => route & { kind?: = "put" }
```

**The design.** The read waits on each declaration that may be the member, judged from what each writes, resolving
nothing: a declaration whose written operands reach the family's base and whose nearest written pin along them does
not differ from the value's.

**What is running.** The schema above loads. The first implementation reads every value of a schema document through
readers compiled over a checkpoint of that document, its imports and the entries ready so far; a read that reaches
what its readers lack stops, the entry is made ready, and the read reruns, the pin-placed value stopping at the
member-dispatch reader. A test reads every value a resolution read at a checkpoint again through the full closure's
readers, over the bundled schemas and every valid schema of the corpus, and requires the same verdict.

## 20. [TSON-SCHEMA] §5.10, throughout — a record with parameters is a parametric record

**Revision 40:** made in the proposal's text on the spec author's ruling (change log §5, "#11, #20"), and written up
here at the close; the item is also among #3's. A record with parameters is a **parametric record** throughout Parts
2 and 3 and the kernel's documentation; §5.10 is *Parametric Records* and §8.1's paragraph has the same head. An
application still **closes** a parametric record, and a record without parameters is **closed** (change log §3.2 item
131).

**Problem.** Revision 39 called a record with parameters a template — §5.10 *Templates and Parameters*,
`meta.template`, `meta.record_template` — and Revision 40's first text, after #3, defined "an open record, a template"
and wrote the first.

**The design.** The term says what the record is and nothing more. A template carries what other languages mean by
one — specialisation, value parameters, expansion — and none of it holds here. "Open" already names a record's
extension, a schema map and a family across schemas, and an open record reads outside the series as a record
admitting further fields.

**What is running.** The first implementation's diagnostics, comments and identifiers use the term
(`ParametricMaterialiser`, `ParametricRecordReader`); no behaviour changed.

**Considered, and why it lost.** *Open record*, for the reason above; *template*, likewise. *Generic record* was
not taken.

## 21. [TSON-DATA] §8.2; [TSON-SCHEMA] §4.1, §5.2, §5.5, §7.4, §9 — the kernel and the libraries: one name for each thing

**Revision 40:** made in the proposal's text, `fraction_digits` on the spec author's ruling (change log §3.2 item
113), and written up here at the close; no register entry carried them. Five small changes, each making a name or a
value say one thing. Part 1 §8.2; Part 2 §4.1, §4.2, §5.2, §5.5, §5.8, §7.4, §8.1, §9, §13.2; `temporal.tn`, `policy.tn`
and `meta.tn` (change log §3.1 items 23 and 24, §3.2 items 103, 113 and 123).

- **The fraction-digit grid is `fraction_digits` on every family.** Revision 39's §5.5 explained the temporal facet by
  the decimal one: `precision` "on `temporal.time_type`, `temporal.datetime_type` and `temporal.duration_type` is a
  constraint on the *value*, not on a spelling: `precision: N` admits a value that is a whole number of 10⁻ᴺ seconds —
  `fraction_digits` on the seconds count". One facet had two names. The three temporal constructors declare it
  `fraction_digits`, as `numeric.decimal_type` does; its meaning and its cap of 9 on the temporal families are
  unchanged. Running: `!time ^ { fraction_digits: 3 }` loads, `precision` is refused as an unknown field, and
  `fraction_digits: 10` is refused at load on `time`, `datetime` and `duration` alike, where the first implementation
  had enforced the cap on `duration` alone. Considered: declaring each constraint facet once, as a mixin the families
  compose, of which the rename is the one piece taken; the rest was deferred past Revision 40, a facet's kind not
  being normative. Lost: the name `precision`, re-spelled `fraction_digits`.
- **The token policy's type carries the policy's name.** Revision 39's Part 1 §8.2 names the two policies "so that
  two implementations reporting them … agree on what they are called", and said "`policy.tn` states it as
  `policy.script_policy`, the `token_policy` field of a `policy.processor`": one policy under two names. The type is
  `policy.token_policy`, beside `policy.identifier_policy`, its shape unchanged. Lost: the name
  `policy.script_policy`.
- **Over a token the two loosest levels judge alike.** Revision 39's §8.2 said "The two loosest levels differ and MUST
  be offered apart: Minimally Restrictive drops the script rule and keeps the identifier profile; Unrestricted drops
  the profile too", which is so of a name. No token is read under an identifier profile, so over a token the two
  differ in nothing, and the text says so; `policy.restriction_level` keeps both, the levels being UTS #39's and one
  enumeration serving both policies. Nothing a processor does changes.
- **`meta.field_name` is removed.** Revision 39's kernel declared `field_name => meta.identifier`, "A field's name, in
  a record or a field group", and [TSON-SCHEMA] §5.8 said that a record stating no name type "is
  `meta.field_name`'s". It typed no slot of the kernel — `meta.field_map`'s key is `text`, judged by the record's name
  type, and `meta.record.name_type` carries no default — and it did not cover its role, a record over a wider name
  type having field names outside it. A record that states no name type has `meta.identifier`'s, which an enum's
  default already writes. Running: a field typed `meta.field_name` is refused as an unresolved reference. Lost: a
  schema that typed a field by the role writes `meta.identifier`, or an alias of its own. `meta.type_name` and
  `meta.param_name` stay, each typing a slot.
- **A chain's `supertypes` and `subtypes` are sets.** Revision 39 typed each `[meta.type_ref]`, and an array's order
  is part of its value, so one chain in two orders was two values, which the fixtures met by sorting. Each is
  `meta.set_type<meta.type_ref>`; `supertypes` is still written sorted (§8.1). Running: the kernel declares both so,
  and the first implementation's schema model needed no change, a set binding to a list.
