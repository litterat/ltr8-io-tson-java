# Spec feedback against Revision 39

This is the register of issues the first implementation of TSON raised against 2026 Revision 39 of Part 1
([TSON-DATA], https://tson.io/raw/2026/39/tson-part1-data.md) and Part 2 ([TSON-SCHEMA],
https://tson.io/raw/2026/39/tson-part2-schema.md). Revision 40 answers every entry. Each entry gives the sections
concerned, what was wrong or unclear in Revision 39's text, the interpretation the implementation took, what it runs,
and the resolution it proposed. The **Revision 40** line under each heading gives the disposition. Spec quotes are
Revision 39's, and section numbers are the same in both revisions.

The answers are recorded in the Revision 40 change log: its §2, *Disposition summary*, gives each entry's verdict, its
§3 items give each section's rule before and after, and its §5 lists the readings the spec author ruled on and what
is still open. Where an entry proposes a design, it states the design as the implementation runs it, so a reviewer
needs nothing beside it. Where the evidence comes from a consumer of the library, an HTTP layer built on it, the entry
says so.

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
