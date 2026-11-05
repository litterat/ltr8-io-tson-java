# Spec feedback

Issues, ambiguities, and inconsistencies found in the TSON spec while building this implementation. See `CLAUDE.md`
for why this file exists and when to add to it. Spec quotes below are from 2026 Revision 38 — Part 1 and Part 2,
the working copies in `spec/`, which publish at https://tson.io/raw/2026/38/tson-part1-data.md and
https://tson.io/raw/2026/38/tson-part2-schema.md — unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a suggested
resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a revision
closes.** It is an input to the next revision's adjudication, so its numbering is the numbering that revision's
change log will answer against — a stable index of the open set, not an archive of everything ever raised.

**The entries Revision 38 adjudicated are gone**, and the spec carries their rules; the change log in `spec/` keeps
them under their numbers. What is below was found on Revision 38.
**This file is the as-built record**, not a pointer to one: where an entry proposes a design this implementation
has built, the entry states the design, what is running, and what is not, so that a reviewer editing the spec needs
nothing beside it. **Where the evidence is a consumer of this library rather than this library** — the HTTP layer
in `ltr8-io-tson-java-http` is one, and this register is the collection point for all of it — the entry says so and
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

## 1. [TSON-SCHEMA] §5.7, §8.2 — a closed refinement source: "mints nothing" against "IS-A the source"

**Problem.** §8.2's trigger positions say an application at a composition target or a refinement source "is subsumed
as an operand and mints nothing (§5.8)". §5.7 says a refinement produces "a new definition with its own identity that
IS-A the source". For `x => box<text> ^ { n: integer }` the two cannot both hold: an IS-A edge needs an entry at its
far end, and §8.2 says the source application has none. §5.8's operand rule is written for composition, where the
operand contributes its fields and its template's ancestors and no edge to itself is owed; a refinement owes exactly
that edge.

**What is running.** A composition operand is absorbed by value and mints nothing, as §8.2 says. A closed refinement
source is closed on demand through the same path a type-position application takes, so it reuses a declaration that
owns the application or mints the expansion — and which of the two happens depends on declaration order. Against
`box => <T> { w: T  n?: integer }`:

- `tb => box<text>` declared before `x`: `x` has `supertypes: [box, tb]` and nothing is minted.
- `x` declared before `tb`: a synthetic `record_w_text_n_integer_true_…` entry is minted, `x` has `supertypes:
  [record_w_text_n_integer_true_…]` — `box` is not in its chain — and `tb` resolves to a second entry for the same
  application, with `supertypes: [box]` and no edge to the minted one.

So the resolved output of one schema varies with the order of two unrelated declarations, and in one of the orders
§8.2's "a declaration naming the application *is* that entry" does not hold either. `design/template-materialisation.md`
("Operands and on-demand closing") states the mechanism.

**Resolution to decide.** Either (a) §8.2 states that a refinement source *is* a trigger position — the application is
materialised (or unified with the declaration that owns it, in either order) and the refinement IS-A the entry it
denotes, which is what §5.7 already promises and what the declaration-first order already produces; or (b) §5.7 says
a refinement of an application IS-A the application's template and nothing else, the source is absorbed as a
composition operand is, and no entry is minted. (a) keeps §5.7's identity rule and asks the resolver to make the
closing order-independent, which §8.2's content identity is for. (b) keeps §8.2's sentence as written and loses the
edge from `x` to `box<text>`, so `x` would not be admissible where `box<text>` is expected. The implementation follows
(a) once this closes; until then the behaviour above is what runs.

## 2. [TSON-SCHEMA] §5.10 — an alias of a schema-namespace template: which namespace its value parameters read in

**Problem.** §5.10 says a template whose body applies a constructor of the governing meta other than `record` reads
its value parameters' types in the structure namespace, a record template reads them in the schema's own namespace,
and "a template whose body applies another template takes that template's form". An alias is a template whose body
applies another template — `rb => <N: int8> box<N>` against `box => <N> { w?: integer ~ N }` — so by the third
sentence it takes `box`'s form and reads `int8` in the schema's namespace. But an alias's resolved entry is an
`alias`, a kernel constructor (#10), which by the first sentence makes it a structure-namespace template. The section
does not say which sentence wins for an alias.

**What is running.** The resolved entry decides: `rb`'s held head is `alias`, not `record` and not an open
template, so `ParameterTypes.readsInStructure` answers true and the written type is checked in the structure
namespace. The schema above is refused at `rb`: "parameter 'N' is declared 'N: int8', and this template applies a
constructor of the governing meta, so its value parameters are the meta's types and a written type names one there --
'int8' is not in the structure namespace (§5.10)". The same alias with no written type (`rb => <N> box<N>`) resolves,
`N`'s type being derived from `box`'s use rather than written. `design/held-template-bodies.md` ("The template's form
decides where value types are read") states the rule as built.

**Resolution to decide.** The third sentence is the one an author relies on — an alias of a record template is used
exactly like the record template — so the proposal is that §5.10 says an alias takes the form of the template its
target applies, following aliases through to the first non-alias head, and that `alias` is never itself a form.
The implementation then follows the target through `alias.target` in `readsInStructure`. The alternative, that
the resolved entry's constructor is the form in every case, is what runs and should then be stated in §5.10 with the
alias named, since the third sentence reads against it.

## 3. [TSON-DATA] §2.4, [TSON-SCHEMA] §5.4 — at a disjoint choice the form selects the variant, with no emitter rule

**Problem.** [TSON-DATA] §2.4 says a token's form is consulted exactly once, by base type resolution, that
"quoting is a lexical necessity, not a semantic signal", and that content inside the profile "may be written in
any form"; §5.2 makes `!date 2025-03-13` and `!date "2025-03-13"` one value. [TSON-SCHEMA] §5.4 makes `disjoint`
mean that the same single pass recovers an untagged value's variant, so at a disjoint choice the one consultation
§2.4 allows is the one that picks the type. There, form is meaning, and §2.4's freedom changes the value:

- the text `42` written bare at `( int8 | text )` reads as the number 42, with no diagnostic, since the reader
  cannot know;
- the text `42` written bare at `( small | text )`, `small` an `int8` capped at 10, is refused by `small` — the
  variant its class selected — with no second attempt at `text`;
- the text `true` written bare at `( boolean | abc )` dispatches to `boolean`;
- the number 42 written `"42"` at `( int8 | text )` reads as text, where §2.4 says `!number "10.2"` is a number.

§5.4's emitter sentence — "if two variants share a class, tag every value of the choice; a tag is never wrong" —
covers the non-disjoint case only. Nothing tells an emitter what form to give an untagged value at a disjoint
choice, and nothing in §2.4 warns that there is a position where its freedom does not hold. [TSON-JSON] has no
counterpart problem: kind is meaning at every typed position there (§5.3, §5.6), so its encoder sentence in §8.2
is complete.

**What is running.** The readers do exactly what §5.4 says: an untagged value at a disjoint choice goes to the
variant of its token's class and is validated as that variant, with no fallback. The schemaless writers comply
without knowing the schema — `AtomWriter` quotes every host `String` and writes every number and boolean bare —
so a value written by this library round-trips through a disjoint choice to the variant it came from. The misread
is a hand-written document's, or a producer's that quotes everything or nothing, which is where the
structured-output case lives.

**Resolution, a proposal.** One sentence in §5.4's tagging paragraph, the twin of the one it has: *at a disjoint
choice an emitter writes each untagged value in the form of its variant's class — a text value whose content would
resolve to another class is quoted, and a value of a boolean or number variant is written bare; a tag is never
wrong.* And one clause in [TSON-DATA] §2.4 after "consulted exactly once": at a disjoint choice that consultation
selects the variant, so the form an emitter chooses there is its tag. No reader rule changes.

## 4. [TSON-SCHEMA] §3.4, §3.4.1, §5.10.1, §11.5 — resolution order within a schema is unstated

**Problem.** §3.4.1 orders resolution *between* documents (the closure, dependencies first) but not within one:
Pass 2 "resolves each entry's body against the populated namespace", forward references are permitted, and §5.10.1
says the resolver "MUST detect and handle cycles" without saying which dependencies form one. The kernel's own order
is left to pre-loading (§3.4). Under the ladder every constructor an ordinary schema applies is in another, already
resolved document, so the gap shows only in the kernel and in composition chains; once a constructor and its instances
share a document, an order has to be computed and its cycles defined.

**What is running.** Rules 1 to 7 run for every schema, by one algorithm; what differs is only what is ready before
a document's resolution starts: the pre-loaded set for the kernel, the import closure for every other schema. An
entry is made ready the first time anything asks for it, and source order is not consulted (rule 5). A declaration
waits on its head (`C` in `!C { … }`, the source in `!I ^ { … }`), on its refinement source and `&` operands, on the
type of each annotation it carries, and on every type a read of its value reaches; a value read against an entry of
the same document is read through readers compiled from the entries ready so far, and a read that reaches one not
yet ready makes it ready first, then starts over. A name at a type position, and a held value, wait on nothing
(rules 2, 3). So a constructor and its instances, a type and a value of it, and an annotation type and its uses share
a document in either order, and a test resolves each bundled schema, and a schema whose entries read each other, with
its declarations reversed and shuffled and gets the same output entry for entry.

Entries waiting on each other through rule 1 are refused naming the cycle in wait order (`'a' waits on 'b' waits on
'a'`, `'step' waits on 'step'`); a composition cycle (`a => b & {}`, `b => a & {}`) is the same rule, and a type
annotated with itself on its value (`note => @note:"x" text`) is a cycle of one. An alias loop is refused by §8.3, not
as a wait. A judgement that walks a name to what it names waits for every entry to be ready (rule 6): §5.8's one name
type over a composition's supertypes is judged then, so walking it never makes the composition wait on an entry that
waits on it.

Rule 7 is counted: the wait depth of an entry is none where it waits on nothing of its own document and one more than
the deepest entry it waits on otherwise, an import being ready before the resolution starts, so a chain is counted
within one document's resolution. A chain past the limit (default 64) refuses its head with `LIMIT_EXCEEDED`, a
refusal and not a verdict, whichever order the chain is reached in; the bundled schemas resolve under the default
whatever a deployment sets.

The kernel, meta.tn (#15), measured from `meta-resolved.tn`'s own declarations, no compiled reader consulted, has one
wait cycle, holding `meta.record` and 25 more of its 85 entries (`boolean`, `integer`, `text`, `numeric.count` and
`numeric.integer_size` (#16), the enum and set machinery, `meta.field_group`, `meta.record_field`, `meta.type_chain`,
`meta.map` and its instance `meta.field_map`, `meta.type_ref`, and the synthetics they name), every other entry ordered
after it, the longest chain 4; the kernel holds that cycle's closure and the concepts that go with it (#15). The kernel
declares `meta.annotation => void`: annotating itself with `@meta.annotation` would be a second cycle, and the marker
changes no value (§6). The bootstrap builds by hand exactly the instances and the one refinement on that cycle (a test
holds the set to the measurement), and makes every other entry ready by the algorithm above. A pin reached through an
entry not yet ready binds as written (rule 3), which is what lets `iri` be read against `iri_type`, whose `spec` pin is
typed by `iri`. The output equals the kernel's ordinary resolution against it, entry for entry. Compilation follows rule
3: a record's reader holds its pins and defaults as written while it is built and decodes them through their types once
every reader of the schema exists, before the schema is handed out. Link remains the one place a pin is judged, and a
bad pin or default still fails the load (rule 6).

What is not running: a resolved form is still read under its source's imports rather than by this algorithm over its own
entries (§8.1, "Resolved forms" in the proposal), so a fixture holding an instance of a constructor it declares does not read
back. And rule 2's example pair, a constructor whose facet is typed by its own instance (`decimal_type` beside `number`),
runs only as numeric.tn declares it. `decimal_type` composes a base kind directly, and a constructor that does makes a new
form, which a processor backs with readers and writers of its own: an extension point, not schema vocabulary. This library
backs the forms the kernel and the data-format libraries declare and no other, so it refuses an instance of any other such
constructor as `NOT_IMPLEMENTED`. Refining an existing constructor with a field of its own (`c => record & { extra: text }`)
is no new form and should follow record composition; it is refused the same way today.

**Resolution, a proposal.** Terms:

- **Form.** A structure serialized data takes, read by an algorithm the processor owns: an atom (one token), a record
  or tuple (fixed length, named or positional), an array or map (variable length, positional or keyed), a choice. Sets,
  enums and namespaces are refinements of these.
- **Constructor.** An entry that links a name to the data a form needs to be read: `array`'s `element_type` and
  `min_items`, `integer_type`'s facets.
- **Declaration.** An entry's value, the data its constructor describes. An entry is **ready** when its declaration
  has been read; a type's reader is its form over its declaration, so a type can be read against exactly when it is
  ready.

The rule is 1; the rest are its consequences.

1. **The rule.** Reading a value against a type `T` waits on `T` being ready, and on each type a part of the value is
   read against as it is reached: a field's type, an element's, a nested `!` tag's, an annotation's. An entry's
   declaration is read against its head (`C` in `!C { … }`, the source's constructor in `!I ^ { … }`) and waits as
   well on each refinement source and `&` operand, since it starts from their declarations. An alias is ready when its
   target is; a closed application when its materialised body is.
2. **A reference orders nothing.** A name at a slot typed `meta.type_ref` or `meta.ref<T>`, an
   application among them (#12), is read by its atom's grammar alone; what it names is resolved and judged once every
   entry is ready (§4.2, §8.3). So `tree => { t: tree }`, mutual recursion, and a constructor whose facet is typed by
   its own instance (`decimal_type => atom & { min?: number }` beside `number => !decimal_type {}`) are sound.
3. **A held value orders nothing.** A `value`-typed slot (a field's default or pin) and a
   template's body hold what a schemaless read gives ([TSON-DATA] §4), and bind as written: a pin or default is not
   decoded through its type when the reader carrying it is built, but judged against its type once every entry is
   ready, still at load (rule 6). Where a declaration names the type a form reads against, a read reaches that type
   by rule 1, the name itself ordering nothing: a value of `field_map` reaches `meta.record_field`, which `field_map` pins
   as its `value_type`, and a value of a family base (§5.2) reaches each subtype, whose selector pins §5.2 confines to
   an atom or an enum.
4. **A cycle is refused.** Entries waiting on each other through rule 1 are a resolver error naming the entries on the
   cycle. A loop of aliases is not a cycle under this rule; §8.3 refuses an alias chain with no terminal. The
   pre-loaded entries (§3.4) are ready before any declaration is read, and are exactly the kernel's one cycle, the one
   holding `record`, whose declaration is a `!record`. Every other kernel entry is read by these rules, and the
   kernel's resolved form must come out of them unchanged.
5. **Order independence.** Every order consistent with rule 1 gives one verdict and one resolved output, and the order
   entries are written in is not consulted. This replaces §3.4.1's forward-reference sentence.
6. **Everything closes at load.** Every entry is ready, every reference and held value judged and every reader built
   before the schema is used. No wait is left for a data read to discharge, and every type is known open or closed
   and usable in data once the schema has loaded.
7. **Limits.** §11.5 gains a row, *wait depth*: the longest chain of rule 1 waits in one closure, default 64. Template
   expansion needs no new bound; §5.10.1's regular recursion and §11.5's materialisation depth bound it.

With `nni => !integer ^ { min: 0 }`:

| declaration | waits | verdict |
|---|---|---|
| `tree => { t: tree }` | `record`; `t`'s type is a reference | sound |
| `uint8 => !integer ^ { min: 0  max: 255 }` | `integer` (source); `min` and `max` on `integer` | sound |
| `point => { x: int32  next?: point ~ { x: 1 } }` | the default is held | sound |
| `integer_type.multiple_of?: nni` | a `multiple_of` value on `nni`, on `integer` | sound: the kernel |
| `integer_type.min?: nni` | `nni`'s `min: 0` on `nni` | cycle: refused |
| `a => b & {}`, `b => a & {}` | `a` on `b` on `a` | cycle: refused |
| `c1 => !c2 {}`, `c2 => !c1 {}` | `c1` on `c2` on `c1` | cycle: refused |
| `a => b`, `b => a` | none | no cycle; refused by §8.3 |

Sections amended: §3.4 (the pre-loaded set is rule 4's), §6 (its "one exception is structural" sentence goes, the
kernel's `annotation` no longer annotating itself), §3.4.1 (Pass 2 runs in rule 1's order; rule 5), §5.10.1
(its cycle sentence cites rule 4), §11.5 (rule 7's row).

## 5. [TSON-SCHEMA] §5.5, §5.7, §12.1 — `!` marks an instance, and `^` refines any value under its type's rules

**Problem.** §5.5 and §5.7 state two refinements under one operator, each with its own rules: record refinement,
`T ^ { … }` over field declarations, and atom refinement, `!I ^ { … }` over data merged into an atom instance's
bindings. No other value can be refined. A container instance (`names => [text]`) has no refinement, and two
instances of one constructor have no IS-A edge between them even where one admits a subset of the other.
Declared as two instances of `meta.reference`, `meta.type_ref` (`!meta.reference { of: meta.type }`) and
`meta.identifier_ref` (`!meta.reference { of: unicode.identifier_type }`) are siblings, so a record refinement
narrowing a `meta.type_ref` field to `meta.identifier_ref` is not a narrowing §5.7 admits, although every identifier
family is a type.

Under one level a `!` head looks as though it plays two roles, a type from `!decimal_type { … }` and a value from
`!operation { … }`. It plays one: `!C { … }` is a value of `C`, and whether the entry is a type follows from `C`'s
chain (§3.3.1), as constructorness already does. Resolved output writes every type this way, so the source spelling
and the resolved form are one.

**What is running.** `!C { … }` construction, atom refinement `!I ^ { … }` with each atom family's tightening rules,
and record refinement `T ^ { … }`. Nothing else is refined. Item 5's worked example runs: `meta.reference` states its
rule for `of` (a refinement's `of` is the source's or IS-A it, judged at link, where both names resolve, and a widening
or an unrelated `of` is refused), `identifier_ref => !meta.type_ref ^ { of: unicode.identifier_type }` is IS-A
`meta.type_ref`, and `namespace => meta.map ^ { key_type: meta.identifier_ref  voidable?: = false }` is IS-A
`meta.map`, and passes §5.7 by that edge: the link judges every restated field's type against its source's by IS-A
(#18), and refuses a restatement whose type is no subtype.

**Resolution, a proposal.**

1. **`!` is an instance of the named type** in a schema as in data and resolved output. `!integer_type {}` is a value
   of `integer_type`, and a type because `integer_type` IS-A `meta.type`. The grammar does not classify a line; its
   head's chain does.
2. **`^` refines a value.** Given `foo => !bar { … }`, `foox => !foo ^ { … }` is `foo`'s value with the body merged
   over it, under the refinement rules `bar` states for its fields. The result is an instance of `bar`, and IS-A `foo`
   where `foo` is a type. Atom refinement is this rule with an atom constructor as `bar`.
3. **Record refinement is the same rule** with `record` as `bar`. A record type is a value of `record`, its
   field-declaration body is sugar for that value, and §5.7's rules are `record`'s: a field may only narrow, to a
   type IS-A the original. The two forms differ only in the body's grammar: `!` before the operand means the body is
   data, and its absence means field declarations.
4. **A type states its refinement rules, field by field**: a bound moves inward, a member set shrinks, a type slot
   narrows to a type IS-A the original. A value whose type states none is refused at `^`, naming the type, so the
   rule is total. A namespace type states none, so `!orders ^ { … }`, with `orders => !api { … }`, is refused.
5. **Worked example.** With `meta.reference` stating that `of` narrows to a constructor IS-A the original,
   `identifier_ref => !meta.type_ref ^ { of: unicode.identifier_type }` is IS-A `meta.type_ref`. A record
   refinement narrowing a `meta.type_ref` field to `meta.identifier_ref`, such as
   `namespace => meta.map ^ { key_type: meta.identifier_ref … }`, then passes §5.7 by that edge.

**Grammar (§12.1).** Unchanged. `atom-refinement`'s head generalises from an atom instance to any value whose type
states refinement rules; no new token, and no lexer change.

**Kept out.** A type as the right operand, its bindings merged over the left's (`pos_int32 => int32 ^ positive`), and
a refinement carrying type parameters (`<N> !integer ^ { min: N }`, refused as today; a template over the constructor
is the route). The tightening rules of the container constructors (`array`, `set`, `map`) are each that constructor's
to state before its instances can be refined.

## 6. [TSON-DATA] §2.3, §3.3, §7.4, §9.1; [TSON-SCHEMA] §7.1, §7.8 — the scope push names a foreign type in data

**Problem.** §7.8's EXTERN cell lets a data value name a schema its governing schema never imported, by a
`!!schema` directive on the value. The foreign schema is then found mid-document: a schema the processor will not
supply surfaces as a refusal halfway through a read, the pushed closure's pins need not agree with the governing one's,
and [TSON-DATA] §9.1 has to count the schemas a document can make a processor load. It is also a second directive
position, and the only one inside a value, for what is a fact about the schema: which foreign types a position admits.
The LOCAL cell needs none of this — a value naming, by its tag, a type of the governing closure.

**What is running.** The push is gone and `declared` is its own constructor:

- The kernel declares `declared_type => sum & {}` and `declared => !declared_type {}` (#9). A `declared` position
  reads exactly as `!scoped { scope: [LOCAL] }` did: the value names its type by its tag, the name resolves in the
  governing schema's closure (§2.2.3), and the value is checked in full against it; an untagged value is a validation
  error in every mode, and a tag naming no type of the closure a resolver error. `declared` is SUM-kinded and has no
  discrimination class (§5.4), as the `scoped` instances were.
- `scoped`, `scope_kind`, `scope_set` and `schema_identity`, and `extern`, `dynamic`, `extern_of` and `extern_type`,
  are in no bundled schema.
- [TSON-DATA]'s `scoped-value` production is gone: a record field value, a map entry value and an array element are
  each a `data-value`, so a directive there is outside its placement and a **parse error** (§3.3's closed positional
  set). Directives stand in the header alone, and the header's one binding directive is `!!import` (#8).
- [TSON-DATA] §9.1's foreign-schemas row is gone, from the limits policy and from `policy.tn`.
- [TSON-JSON] (edited in place) admits its binding member, `$import` since #8, at the top of a document alone;
  anywhere else it is a resolver error, and §8.5 is "Declared Positions".

**Resolution, a proposal.** Sections amended:

- [TSON-SCHEMA] §7.8 reduces to `declared`: the `scoped` constructor, its table, the two templates, the cell rule, the
  scope push, the typed-position restriction and the schemaless-document paragraph go, and what stays is the LOCAL
  rule above, stated of `declared_type`. §7.1 loses its scoped-value paragraphs (the binding of one value, the
  typed-position sentence) and keeps the header binding. §4.1's sum bullet names `choice` and `declared_type`;
  §5.10's `scoped.schemas` example, §7.5's `scoped.scope`, §8.1's table rows naming `scoped`, `extern_of` and
  `dynamic`, and §9's meta and core rows follow.
- [TSON-DATA] §2.3 has one value rule, `data-value`; §2.5, §2.6 and §2.7 lose their directive sentences, §2.1's example
  its field-level `!!schema`, §3's augmentation intro and §3.3 place directives in the header alone, §7.4's grammar
  drops `scoped-value`, and §1.5's Class 1 obligation to preserve `schema` directives concerns the header's.
- [TSON-DATA] §9.1 drops the foreign-schemas row.

A foreign type returns as a bundled constructor, `extern => atom & { schema: schema_identity  type:
meta.type_name }`, applied in the schema that admits it and read in the foreign schema's own closure, loaded with the
declaring schema rather than by the document. That is a later step and is not running.

## 7. [TSON-SCHEMA] §2.2.2, §2.2.3, §3.1, §3.3, §3.4, §6, §9 — one namespace per schema: the ladder's rules go

**Problem.** A schema document resolves in two stores: `!` targets, `kind`/`ref` `of` and annotations in the
structure namespace of its `!!meta` target (§3.3.1, §3.3.3), every other name in its type-name namespace (§3.3.2).
Four rules hold the two apart and keep each document on one rung: placement (§2.2.2, only a kernel-governed
document declares a constructor, a namespace type or a reference), direction of service (§3.3.1, a schema's `!`
targets come from its meta, never its own entries), the level of an import (§2.2.3) and a `!!meta` target's
eligibility (only a kernel-governed schema governs). They cost core.tn nine sibling declarations of the meta-kernel's and
meta.tn's entries (§3.3.5), and make a constructor declared beside its instance, or in an ordinary library its
importer applies, unexpressible. Each is a rule about levels, which the dependency model of #4 replaces.

**What is running.** One namespace per schema:

- **A schema's namespace is its own entries and every entry of its import closure**, transitively. `!!meta`
  contributed exactly as `!!import` does, and is retired by #8: `!!import` is the one directive, and the root's
  `!schema` tag classifies the document.
- **Every name resolves there:** a `!` head, an atom refinement's source, a type-ref, a kind's or a reference's
  `of`, a template bound, and an annotation (§3.3.3: in the annotating schema's namespace, for a schema document as
  for a data document).
- **Deleted:** placement (§2.2.2) — any schema declares a constructor, a namespace type or a reference, with no
  extension guard; direction of service (§3.3.1); the import-level rule (§2.2.3); the governing-target eligibility
  check; the structure/type-name split (§3.3.1, §3.3.2, §3.3.4's invisibility of structural vocabulary); and §3.3.5's
  sibling allowance. Two same-named entries in one closure are §2.2.3's collision, decided by identity.
- **core.tn imports numeric.tn, temporal.tn and net.tn**, each importing the kernel alone, and declares none of `void`,
  `boolean`, `integer`, `text`, `bytes`, `regex`, `iri`, `iri_reference`, `doc` or `set` (the kernel's root vocabulary),
  each where the kernel places it (#15), nor a `kind`, the kernel's `meta.ref` being the one spelling (#11). The
  kernel's `iri` refines its `iri_reference` (`iri => !iri_reference ^ { allow_relative: false }`), so `iri` IS-A
  `iri_reference` as `uri` IS-A `uri_reference`. The descriptive
  annotations Revision 38's core puts on its copies (`@ordering`, `@bounded`, …) go with them.
- **A same-document read waits:** applying a constructor declared in the same document (`!C { … }` with `C` local),
  reading a value of a type declared there, and reading an annotation value against one each wait on that
  declaration being ready, in #4's wait order, whichever is written first. A type used by name waits on nothing.
- **The cost:** every name in the closure is reserved. The bundled documents keep their roots to the vocabulary an
  author writes and their machinery under `meta`, `atom`, `numeric`, `unicode`, `net`, `binary` and `temporal`
  (#15), so a schema importing core reserves those seven and the roots' names — `text`, `enum`, `set`, `doc`, `uuid` and
  `title` among them; `record`, `map`, `namespace`, `template`, `identifier`, `field_name` and `bounded` are free.

**Resolution, a proposal.** Sections amended:

- §2.2.2: goes with `!!meta` (#8); its two bullets, the ladder paragraph, "Constructor declarations are a
  meta-schema privilege" and the namespace-type paragraph's level sentences go with it.
- §2.2.3: "An import stands at the importer's governing level" and "exactly as `!!meta` contributes" go; the sentence
  placing imports in the type-name namespace only goes.
- §3 intro, §3.1, §3.3, §3.3.1, §3.3.2, §3.3.4 restate one namespace: §3.3.1 keeps the constructor-role
  applicability rule (IS-A `top`) and the desugar targets, now resolved in that namespace, and its
  direction-of-service, one-hop and "import what you expose" paragraphs go; §3.3.2's lookup order gains the
  closure's imports alone; §3.3.5 goes.
- §3.3.3: an annotation resolves in the annotating document's namespace; core declares no `doc` of its own.
- §3.4: the kernel's entries reach a schema through its import closure; "NOT automatically available as type-refs"
  goes.
- §4.2 (core's `kind`, `void` and `set` siblings), §4.x's "Core declares no sibling of `meta.identifier`", §1's worked
  example ("reached through the structure namespace"), §6, §9 and §13.2: core imports numeric.tn, temporal.tn and net.tn;
  core's sibling rows go.

## 8. [TSON-DATA] §2.2, §7.1; [TSON-SCHEMA] §2.2.2, §2.2.3, §3.4, §7.1, §8.1 — one directive, the root says the kind

**Problem.** Three directives state one relation. `!!import` brings a schema's namespace into a schema; since #7
`!!meta` does the same and also classifies the document as a schema; and `!!schema` on a data document names "a
namespace, not a root contract" (§7.1), which is what an import is, but admits one. The kernel names itself with
`!!meta`, a cycle closed only by pre-loading, and a resolved form cannot carry its source's imports, so they are
supplied at ingest (§8.1).

**What is running.**

- **The header is an optional `!!id`, then any number of `!!import`s**, for every document. `!!meta` and `!!schema` are
  parse errors naming their replacement; an unknown directive, and an `!!id` after an `!!import`, are refused by the
  same grammar. A directive past the header is the parse error #6 left.
- **A document is a schema exactly when its root is tagged `!schema`.** The tag always names the pre-loaded kernel's
  root `schema`, `schema => meta.schema`, an alias, whatever the document imports, and is read as a spelling; a schema
  parser refuses an untagged root.
  Classification reads the header, the root's leading annotations and its tag, and never the root's value, so a schema
  map's sugar is never parsed to classify it. That is not §7.1's "at most two directives of lookahead and no value
  parsing": annotations stand before the tag (§7.4's `*annotation [type-ref] core-value`), so a schema carrying a
  document-level `@doc` is classified past that annotation's value. This is the reading running; the alternative, a
  schema's annotations after its tag, would make the root the one value whose annotations follow its type-ref.
- **A Class 1 processor still refuses a schema document** with §1.5's categorized diagnostic, now at the root's tag.
  Reading one as a map with a preserved tag would surface its sugar as a misleading parse error.
- **Data documents import as schemas do.** Several imports are read under one namespace over their closures, a name two
  of them declare differently being §2.2.3's collision; under imports the root names its type (§7.1). A read under
  several imports is linked as a schema with no entries of its own importing each, and compiled once per set.
- **Every import target is a schema document**: a target whose root is not tagged `!schema` fails to parse as one, and
  the importer fails to load. Two pins of one identity in one closure are refused, since at most one matches the
  content (§2.2.1). Two revisions of one base are refused where they declare a common name, as §2.2.3's collision;
  nothing defines a revision for an arbitrary identity, so no rule refuses them otherwise.
- **The kernel's header is its `!!id` alone**, its root `!schema`. It imports nothing, is pre-loaded (§3.4) and is never
  resolved from a document, and a document claiming its identity is checked against the bytes the processor ships.
  Its names reach a schema only by import: an import-free `!schema { a => { b: b }  b => {} }` is valid and sees its
  own names. numeric.tn, temporal.tn and net.tn each import the kernel alone, core.tn imports the
  three, and policy.tn imports the kernel alone.
- **A resolved form's header is its source's**, and its root `!schema`; nothing in it marks it resolved. Reading one is
  the caller's choice, as §10.1's ingest is: the reader is told the root's type, and reads under the form's own
  imports, the kernel's own form, which imports nothing, under the pre-loaded kernel. Nothing is supplied at ingest.
- **No reading rule is left of the ladder.** A kind-typed position is judged by membership in every data document,
  and read by its grammar alone in a schema document, whichever schema it is read under (#9).
- [TSON-JSON] (edited in place): the binding member is `$import`, a list of references, at the top of a document only.

**Resolution, a proposal.** Sections amended:

- [TSON-DATA] §2.2: a header is `[id-directive] *import-directive`; the directive table loses `meta` and `schema`; a
  document is a schema when its root is tagged `!schema`. §3.3's positional set is `id` then `import`. §7.1's
  classification reads the header, the root's annotations and its tag. §7.4: `document = [id-directive ws]
  *(import-directive ws) data-value ws`. §1.5 and §8.1 identify a schema document by its root's tag.
- [TSON-SCHEMA] §2.2.2 goes. §2.2.3 states imports for every document, every target a schema document, and the pin
  rule above. §3.4: the kernel's header is `!!id` alone, and it is pre-loaded, never resolved. §7.1: a data document's
  `!!import`s bind it, several in one namespace, and the root names its type. §8.1: a resolved form carries its
  source's header, and nothing is supplied at ingest. §12.1: `schema-document = [id-directive ws] *(import-directive
  ws) *(annotation ws) "!schema" ws schema-map`.

## 9. [TSON-SCHEMA] §4.1, §4.2, §5.12, §7.4, §8.1 — value entries are ordinary values; `namespace` builds map types

**Problem.** §4.1 makes `namespace` a fourth kind beside `type`: a constructor IS-A `top` alone, whose instances are
maps, so a namespace type is the one constructor whose instances are not types and the only way a schema map holds a
value. Every rule that tells types from values carries an exception for it — `meta.type_kind` holds the namespace
constructors, `meta.kind<namespace>` holds both namespace types and their values, `meta.ref<T>`'s "`of` is no constructor"
reads `api` as one — and a schema cannot hold a value of an ordinary record type at all
(`create_order => !operation { … }` is refused as "not applicable"). The schema map's own `value_type: top` is false
once any entry holds a value. And with one namespace per schema (#7), a meta layer's vocabulary is ordinary types, so
"governed by a meta layer" no longer says which documents describe schemas.

**What is running.**

- **`namespace` is a refinement of `map`**, `namespace => map ^ { key_type: meta.identifier_ref  voidable?: = false }`,
  IS-A `map` and through it `product`, and a namespace type is an instance of it, as a map type is one of `map`:
  `schema => !namespace { key_type: meta.type_name  value_type: meta.declared }`,
  `api => !namespace { key_type: meta.identifier  value_type: method }`. A value of one is the map its facets
  describe, every key a name of the family `key_type` states, a member of `meta.identifier_ref` judged at link, and
  no value void. `key_type` is required: no host supplies a namespace's key family. `ordered` and the size bounds are
  `map`'s, and a value of a namespace type is read as that map, its `min_items` and `max_items` judged as a map's are.
  `meta.ref<map>` holds every namespace type beside the plain map types, and a qualified name still enters a value of
  a namespace type alone: a plain map such as `field_map`'s is never entered. `schema` is the kernel's one.
- **A record's fields are no namespace.** `field_map => !map { key_type: text  value_type: meta.record_field  ordered:
  true }`. A field name is text within its own record, judged by the record's `name_type` as `discriminators` and a
  group's `members` are, and as `enum_set` is for an enum's members: the map's own key uniqueness compares the text
  as written, and two keys that are one value under the name type's equality are a resolver error. §5.12's further
  segments select in a value and a type entry admits none, so no qualified name selects a field declaration
  (`foo.bar` and `foo.fields.bar` for `foo => { bar: text }` are refused); and a record value whose field is typed
  `field_map` holds a map that is no namespace, which no segment enters (`point.p.fields.x` is refused at `fields`).
- **An entry is a type exactly when its value is built by a constructor IS-A `type`.** `name => !T { … }` with `T` a
  constructor builds a type; with `T` an ordinary type — a record, a namespace type, any type of the closure — the
  entry is a value of `T`, read against `T` as a data value is, and marked by nothing but its tag. A value entry is a
  member of no reference over a constructor (`meta.type_ref` excludes it unmarked), nothing is typed by it, and a
  reference over `T` names it where its type IS-A `T`: `meta.ref<api>` (#11) names `orders =>
  !api { … }`. A value entry's payload lifts and mints nothing (a value of `schema` is no value entry: it declares,
  #14); its names at kind- and reference-typed slots are judged at link, and a qualified name walks into it by key
  and by field. A template over an ordinary type closes to a value entry (`fetch => <T> !operations { … }`,
  `get_order => fetch<order>`). A type declared in the same document is applied in wait order (#4).
- **The schema map's values are `declared`.** The kernel declares `declared_type` and `declared` (Revision 38 declares them
  in meta.tn and core.tn), and `schema`'s `value_type` is `declared`: every entry's value names its type by its tag — a
  constructor for a type, an ordinary type for a value. `top` is the root of the constructors only. `declared` is the
  kernel's `meta.declared` (#15), and the descriptive annotations Revision 38's core puts on its copy (`@ordering`,
  `@bounded`) go with it.
- **A kind-typed position is judged by membership in every data document**, over the reading document's closure. A
  name in a schema document — a construction's or a value entry's payload, or a value of the kernel's `schema` read
  as data, a resolved form — resolves in that schema, which the reading closure does not hold: its grammar is read
  and its membership judged where that schema links, or at ingest. That replaces the stand-in that read grammar alone
  under the kernel and any schema importing it directly: a data document importing temporal.tn that writes
  `!meta.record_field { type: int32 }` is refused, `int32` being no member of `meta.type_ref` in temporal.tn's closure.
- **The kernel's wait cycle** (#4) holds `map`, `field_map` being its instance, and not `namespace` or
  `meta.field_name`, `field_map`'s keys being `text`: 26 of the kernel's 85 entries, longest chain 4 (#4). The
  bootstrap builds `field_map` by hand with the rest of the pre-loaded set.
- **Hosts.** One bind context serves schemas and data; the names the kernel declares and the libraries' members of `numeric`,
  `temporal` and `net` always bind to the schema model (#15), and every other name binds through the consumer's binder. A
  value entry whose type no class binds is held as a tree, so a schema loads and validates without host classes; a schema
  obtained to bind refuses such an entry with `BIND_MISMATCH` at the entry, anywhere in the closure it compiles, since a
  bound reference can reach a value of any schema there. An instance of a constructor whose instances are no types (one
  composing `top` and not `type`, `s => !step { … }` over `step => top & { … }`) is held the same way, as the value it is.
  These are a processor's choices, not the spec's.

**Resolution, a proposal.** Sections amended:

- §4.1: the kinds are `atom`, `product` and `sum` under `type`, beside `alias` (#10) and `template` under `top`;
  `namespace` is a refinement of `map` whose instances are namespace types, and the paragraph making a
  namespace type the one constructor whose instances are not types goes. An entry is a type exactly when its value
  is built by a constructor IS-A `type`, and any other entry is a value, its tag naming its type. `namespace`'s
  `key_type` is required. `schema`'s `value_type` is `declared`, which with `declared_type` is the kernel's.
  `field_map` is an ordered `map` keyed by `text`, each key judged by the record's `name_type`.
- §4.2: a namespace type is a member of `meta.type_ref`, `meta.ref<product>`, `meta.ref<map>` and
  `meta.ref<namespace>`, and a value entry of no reference over a constructor; the rest of §4.2 is #11's.
- §5.7: "Constructor refinement in a meta-schema is the meta-level exception and MAY replace fixed values (§4.2)"
  is ladder wording; with no meta-schema it is restated for a constructor refinement wherever it is written, or the
  exception goes. The omission order stands.
- §5.12: a qualified name's first segment names an entry holding a value; a further segment selects a key of a value
  of a namespace type or a field of a record value.
- §7.4, §8.1: a kind-typed position in a data document is judged by membership in the document's closure; in a
  schema document, and a resolved form read as one, its grammar is read and membership is the schema's link's or
  ingest's.

## 10. [TSON-SCHEMA] §4.1, §7.2, §8, §11.5, §12.1 — the alias body is `alias`; a reference is a name in a slot

**Problem.** "Reference" names two things. §4.1's entry kind and §8.3's body, `!reference { target: T }`, are an
alias: §8.3 is headed "References" and says a reference is a hop, §7.2 compares "after reference flattening", and
§11.5 bounds the "reference chain". §4.2 and §5.12 use the same word for a name held in a slot: `meta.ref<T>`, a type
reference, a qualified name. The kernel's `reference` holds the word an atom of names would take, and every rule that
says "reference" leaves the reader to work out which of the two it means.

**What is running.**

- **The kernel declares `alias => top & { target: meta.type_ref }`** (#12). An alias body is `!alias { target: T }`, written
  explicitly or derived from `name => T`, and its kind is ALIAS. The kernel's and core.tn's resolved forms state
  `!meta.alias` for `meta.type_name`, `meta.field_name`, `meta.param_name`, `annotation`, `doc` and the annotation markers.
- **§8.3's walk is the alias chain.** Policy's limit is `max_alias_chain`, default 64, and diagnostics that walk it say
  "resolves through its alias chain to".
- **Nothing else moves.** `alias` and `reference` are constructors, `meta.alias` and `meta.reference`, members of
  `meta` (#15), so neither reserves its bare name and `alias => port` in a schema importing core collides with
  nothing.

**Resolution, a proposal.** §4.1's fourth entry kind is **Alias**: "an entry whose value is a pointer to another
entry: `!alias { target: T }`". §8.1's record paragraph and table name `alias` and ALIAS. §8.3 is headed "Aliases":
"an alias is a hop, not a rewrite", "aliases are not supertypes", "instantiation entries are not aliases". Every
"reference chain" becomes "alias chain": §4.3, §5.2, §5.4, §5.7, §5.10, §5.12, §6, §7.2, §8.1 to §8.3, and §11.5's
row. §7.2's phrase becomes "after alias flattening of both", §5.10's "reference template" (`id => <T> T`, a partial
application) becomes "alias template", and §12.1's `instance` comment names the kernel's `alias`.
"Reference" keeps one meaning: a name held in a slot, as in §4.2's `meta.ref<T>` and §5.12's qualified name, an
application being one with arguments (#12).

## 11. [TSON-SCHEMA] §4.1, §4.2, §5.12, §7.4, §8.1 — one reference atom, and a constructor is a record type

**Problem.** Every entry of a schema map is a name and a value, and the value has a type, its tag: `order => { … }`
holds a value of `record`, `int32 => !integer ^ { … }` a value of `integer_type`, `orders => !api { … }` a value of
`api`. A field's type position (`meta.type_kind`), a `meta.kind<C>` slot and a `meta.ref<T>` slot all ask one
question of a name: does the named entry's value, past its alias hops, have a type that IS-A the slot's `T`? §4.2
asks it through two atoms, `kind_type` and `ref_type`, and partitions by `T`: `meta.kind<T>` requires a constructor
and `meta.ref<T>` refuses one, so `meta.kind<api>` and `meta.ref<record>` are each refused pointing at the other
spelling. `kind_type` also writes a derived `members` index into resolved output. And a kind excludes an entry that
is itself a constructor, and a field's type position refuses one ("a constructor, not a type"), although a
constructor's declaration is a record (`uuid_type => atom & { … }`) and a record type's values are records.

**What is running.**

- **One atom.** The kernel declares `reference => atom & { of: meta.type_ref }` (`of` a qualified name, #14).
  `kind_type` and `ref_type` are gone, and with #12's removal of the records `type_ref` and `type_argument` the kernel
  holds 85 entries (79 declarations, #15).
- **One spelling.** `ref => <T> !reference { of: T }` is the kernel's one template over it, and no `kind` stands beside
  it: what `of` names decides what a slot holds, so a second spelling could say nothing the first does not.
  `meta.ref<record>` reads as a reference to a record type and `meta.ref<method>` as one to a method value. The two
  named instances read as instances of it, `<target>_ref`: `type_ref => !reference { of: meta.type }`, a reference
  to a type, typing every slot that names the type of a value, and `identifier_ref => !meta.type_ref ^ { of:
  unicode.identifier_type }`, a reference to an identifier family, typing both `name_type` slots. Revision 38's
  `type_kind` and `identifier_kind` are these two renamed, and its `type_ref` record is #12's. "Kind" stays the
  concept, §4.2's set of the types a constructor builds, which a reference over a constructor holds.
- **The slot's meaning comes from `of`, never from the spelling.** Where `of` is a constructor, `top` or IS-A it, the
  values it admits are types: the slot holds a type's name, judged a member over the governing closure and delivered
  to a host as a `TypeRef`. Where `of` is any other type, the values it admits are values: the slot holds a value's
  qualified name, judged at link and delivered as the referent (`Ref<T>`). So `meta.ref<bar>` over a record type names
  values of `bar`, `meta.ref<record>` names types, and `meta.ref<api>` names `myapi`.
- **`of` names any type or constructor.** Refused are a name that resolves to nothing, an open entry and a value
  entry. `meta.ref<top>` holds every type; `meta.ref<alias>` holds nothing, no entry past its hops being an alias, and a
  required field typed by it is refused by §5.10.1's productivity rule.
- **`members` is no part of the body.** A resolved form states `of` alone, and an author writing `members` is refused
  as an unknown field. The admitted set is derived at link and kept by the processor for its own judgements.
- **Where a name is judged** is unchanged: in a data document a type's name by membership in the reading closure, and
  a value's qualified name is this library's gap there (`NOT_IMPLEMENTED`); in a schema document the grammar where it
  is written and the judgement when the schema links.
- **A qualified name selects a key of a value of a named product**: a namespace value's key, or a record value's field,
  the record being a namespace whose keys its type fixes. That is §5.12's walk restated, and nothing it admits moves.
- **A constructor is an ordinary record type.** Nothing in the predicate sets a constructor apart. `uuid_type`'s
  value is of `record`, so `meta.type_ref`, `meta.ref<meta.record>` and `meta.ref<product>` hold it, and `record`,
  `top`, `alias` and `template` with it. A field typed `uuid_type` holds a declaration's body as data,
  `!holder { spec: { version: 4 } }`, read in a TSON tree, bound to the constructor's own model class, and read from
  JSON; `boxed<uuid_type>` and `[uuid_type]` close as any application does. A declaration read as data is judged by
  its record shape alone: it is not resolved, so a derived field such as `chain` is admitted as written and no
  coherence rule runs.
- **Unchanged:** a template parameter's `bound` still refuses a constructor. A bound is IS-A, not membership, and
  construction transfers kind and not IS-A, so only another constructor (`set_type` IS-A `array`) could satisfy one.

**Resolution, a proposal.**

- §4.2: one atom, `reference => atom & { of: meta.type_ref }`, admitting a name exactly when the type of the named entry's
  value, past its alias hops, IS-A `of`; `meta.ref<T>` its one spelling; `of` any type or constructor of the
  writer's namespace, never a value or an open entry. A slot over a constructor holds a type's name and takes part in
  structural identity; any other holds a value's name. `kind_type`, `ref_type`, the `kind` template (the kernel's and
  core's) and both refusals go. `type_kind` and `identifier_kind` are renamed `type_ref` and `identifier_ref`, in
  §4.2, §5.10 and §8.1 alike.
- §4.1: a constructor is a type like any other at a name slot and a type position: its declaration is a record, whose
  instances are declarations. The sentence making a constructor "never itself the type of a value" goes.
- §5.12: a further segment selects a key of a value of a named product, a namespace value's key or a record value's
  field, the record being a namespace whose keys its type fixes; a map that is no namespace is not entered.
- §7.4 and §8.1: "a kind-typed position" reads "a position typed by a reference over a constructor"; `members` is no
  longer a derived index written in resolved output.

## 12. [TSON-SCHEMA] §4.2, §5.10, §8.1, §8.2 — an application is a reference value

**Problem.** A type is named in two value forms. A bare name is a value of the `reference` atom (#11); an application
still open in resolved output, an alias's target, a record template's parents, the `application` annotation, is the
§8.1 record `type_ref { name  arguments?: [type_argument] }`, with
`type_argument { ( name: type_ref | value: meta.value ) }`. The record exists only so a value argument has somewhere
to sit, and that somewhere is the kernel's last `value`-typed slot outside facets and defaults. It also says nothing
the token does not: §12.1 already reads an argument by its token and leaves the head's parameters to judge which it
binds.

**What is running.**

- **One value.** The `reference` atom's values are the reference #13 makes data grammar: a qualified name with an
  optional argument list, `geo.address<T>`, each argument a reference or a token. `type_ref` and `type_argument` are
  gone; `alias.target`, `meta.type_chain.parents` and the `application` annotation are typed `meta.type_ref`. The kernel
  holds 85 entries (79 declarations, #15).
- **Written unquoted, canonically.** Resolved output and held text write an open application as source does, one
  space between arguments and no comma: `!alias { target: pair<uuid B> }`, `chain: { parents: [result<T>] }`.
- **The value is an object, in every mode.** A reference read at a reference-typed position, in a tree read and a
  bound one alike, is one object: its qualified name's segments, its arguments (each a nested reference or a value
  token), its canonical text, and its referent, the value of the entry it names in the closure it was read under,
  each nested argument resolving to its own. A schemaless read keeps an application as a structured value with no
  referent (#13).
- **Identity is the parsed form.** Whitespace inside an argument list is free: `box< text, >` at an element type and
  `box<text>` at a type position close to one entry. An argument's channel is no part of the reference: `names<red>`
  is one application whether `red` was read as a reference or filed as a value at its parameter.
- **Arguments are typed where they land.** A type argument is judged against the parameter's `bound`; a value
  argument is a token of the parameter's type, substituted into the body, which is read as the constructor's
  instance (`maxint<10>` closes to `!integer_type { max: 10 }`). The linker judges as type names only the arguments
  at the head's type parameters.
- **Nested applications close innermost first** (§5.3), and a closed one is named bare where it stands. The
  instantiation entry's `@meta.application` states the application as written, its nested arguments unreduced, each bare
  name argument past its alias chain and a value argument a token: `@meta.application:pair<box<text> int32>`, where the
  inner `box<text>` closed to `inner` and `inner` states `@meta.application:box<text>`. An application nothing declares
  is its expansion and still states no `@meta.application`.
- **In a value body** an application at a kind-typed slot is refused and the alias spelling named: nothing lifts or
  mints there.
- **In a data document** a kind-typed slot may name an applied type, `t: set<text>`, resolved bottom-up, level by
  level, by structural identity against every application the closure closed, an expansion's among them (the
  resolver keeps them beside the entries; resolved output does not carry them). A schema loads only when every
  application in it closes, so data never creates one: an application nothing in the closure closed names no type
  the schema closes and is refused, as a name no member is.
- **A message names a minted entry by its application**, as written: `'b<10>': '5' is less than the minimum 10`, and
  a kind's refusal lists `meta.ref<uuid_type>` where it would have listed the entry minted for it.
- `value_type` stays: the libraries' facets and `meta.record_field.value` still use `value`.

**Resolution, a proposal.**

- §4.2: the `reference` atom's values are #13's reference, compared as their parsed form; a value carrying
  arguments is an application, stands where one is still open, and in data names the entry the schema closed it to.
- §8.1: `type_ref` and `type_argument` go from the kernel table; `alias.target`, `meta.type_chain.parents` and
  `application` are typed `meta.type_ref`; `@meta.application` states the application as written, nested arguments
  unreduced.
- §8.2: identity compares a reference's parsed form, its head, and its arguments as references or tokens, never the
  text or the channel a processor filed an argument in; a nested argument is compared as the entry it closed to.
- §5.10: an argument is a reference or a token by the grammar, and typed by the head's parameter; a parameter
  standing in an argument is its name, in held text as in a record template.

**Open.** Whether an expansion, an application nothing declares, should state its `@meta.application` too, so that every
entry an application closed to names the reference that closed it in resolved output; today the resolver keeps that
in memory only. An expansion is keyed on its content, so two applications closing to one record (`box<int32>` and
`crate<int32>`) would give one entry two applications.

## 13. [TSON-DATA] §4, §7.2.5, §7.4, §7.5, §7.7; [TSON-SCHEMA] §8.1, §12.1, §12.3 — a reference in data is grammar

**Problem.** A reference that carries arguments (#12) has no spelling in data: `<` and `>` are reserved there
(§7.2.5), so `target: result<T>` is a parse error, and the one spelling left, a quoted token (`"result<T>"`), makes a
quoted token text or a reference by its shape, so a held body's text literal that parses as an application would be
read as one. The production a data value
needs already exists: [TSON-SCHEMA] §12.1's `type-ref` has `type-name "<" type-args ">"`. Under one level a `!T { … }`
body is data read against `T` — a value entry, a constructor body, a resolved form, the schema document itself — so
whatever can stand in one is data grammar. The line between the two grammars is values against sugar: [TSON-DATA] is
every value, a reference with its arguments included, and [TSON-SCHEMA]'s grammar is the sugar at type positions that
desugars into values (`{ a: text }`, `[T]`, `{K => V}`, `(A | B)`, `^`, `&`, `-`, `~`, `=`, `?`).

**What is running.** The grammar below, in both encodings.

- The stream assembles a reference into one event, delivered as one value; a quoted token is never a reference, so a
  held body's `pattern: "box<T>"` is text and a quoted application at a reference-typed position is a type mismatch.
  `<` with whitespace before it, after a quoted token, or after a token that is no qualified name is a parse error,
  as is an unclosed list.
- A schemaless read keeps an application as a structured value: the qualified name's segments and the arguments,
  each read as a schemaless read reads a token, a nested application another such value, with its text recreated
  and no referent, there being no closure to resolve in. A Class 1 processor no longer refuses one.
- A qualified head parses at every position, data and schema, and resolves from the root as any name does (#14).
- The schema grammar keeps its own production, whose `type-arg` admits sugar, and produces the same tree; it applies
  the adjacency rule and admits a qualified head.
- JSON carries a reference as a string holding the same text, read by the same production.

**Resolution, a proposal.** Grammar only.

[TSON-DATA] §7.2.5: `<` and `>` join `!` and `@` as the special characters with a grammar role in data values; the
other eleven stay reserved by [TSON-SCHEMA]. The lexer is unchanged: each is a single-character special token, so
`map<text list<int32>>` lexes as `map` `<` `text` `list` `<` `int32` `>` `>`.

[TSON-DATA] §7.4: the reference production, [TSON-SCHEMA] §12.1's `type-name "<" type-args ">"` with a qualified
head, moves into the data grammar, with arguments limited to what data can spell.

```
core-value  = record / map / array
            / empty-brace / void / reference / token

reference   = qualified-name "<" ws type-args ws ">"
            ; "<" adjacent to the qualified-name (§7.5)
type-args   = type-arg *(separator type-arg) [ ws "," ]
            ; separator = ws "," ws / ws1, as [TSON-SCHEMA] §12.1
type-arg    = reference / token
            ; a token that is a qualified-name is a reference with no
            ; arguments; any other token (a number, a quoted token)
            ; is a value argument. A quoted token is never a reference.
qualified-name = unquoted-token
            ; segments joined by "." (§7.7), each an identifier:
            ; `order`, `geo.address`. One token, since "." is a
            ; token character.
```

- **One value.** `geo.address<T>` is data syntax delivered as one `reference` atom value, the arguments part of the
  reference. The parser builds the head and its arguments, nested to any depth, and a position typed by the
  `reference` atom rolls the tree up into one value; a qualified name without arguments is one token, rolled up the
  same way. An atom says what a value is, not how many tokens spell it. At any other typed position a reference with
  arguments is a type mismatch.
- **Where it stands.** Anywhere a core value does, a map key included. A `!` tag is `"!" qualified-name` (#14), with
  no arguments.
- **No sugar in data.** `[T]`, `{K => V}` and `(A | B)` are [TSON-SCHEMA]'s, and `[` and `{` are structure in data, so
  a data argument is a reference or a token. An applied sugar form is named by the entry that declares it.

[TSON-DATA] §7.5: one row, `<` (reference arguments) MUST be adjacent to the preceding unquoted token; whitespace
inside the argument list is free. `>>` is two tokens.

[TSON-DATA] §4: a schemaless read keeps a reference with arguments as a structured value, its head and arguments,
resolving nothing; a Class 1 processor no longer refuses one.

[TSON-SCHEMA] §12.1: `type-ref`'s `type-name "<" type-args ">"` is the data production with the schema's wider
`type-arg = type-ref / value-literal`, so a schema argument may be sugar; `type-name` becomes `qualified-name` there,
in `supertype-ref` and in `map-key`. §12.3 gains the adjacency row for `<`.

[TSON-SCHEMA] §8.1: an open application in resolved output is written as it is in source, unquoted:
`!alias { target: result<T> }`, `@meta.application:response<order 201>`, `parents: [result<T>]`. Identity is the parsed
tree (§8.2), so whitespace inside the list is free; canonical output has one space between arguments and no comma.

[TSON-JSON]: a JSON string holding the same text, read by the same production; JSON has no unquoted form.

**Open.** Whether a value argument may be any token, or only those §12.1's `value-literal` admits.

## 14. [TSON-DATA] §4, §7.4; [TSON-SCHEMA] §2.2.3, §4.1, §5.12, §12.1 — every `!schema` value declares, from the root

**Problem.** One namespace per schema (#7) reserves every name of an import closure, and the answer one level gives,
a library grouping its declarations under one entry of its own (`meta => !schema { … }`, `geo => !schema { … }`),
has no reading: §4.1 makes a value of a namespace type a value entry, "nothing in it lifting or minting", so `geo =>
!schema { address => { … } }` is read as data and refused (`declared` takes a value naming its own type). Nor could a
member be named: `type-name = meta.identifier` (§12.1) and [TSON-DATA] §7.4's `type-ref = "!" identifier` admit one segment,
and the kernel types `meta.reference.of` and `meta.type_chain.supertypes`/`subtypes` by `meta.type_name`, one identifier.

**What is running.**

- **Every value of `schema` declares.** `geo => !schema { … }` is a schema map nested as an entry: its tag, `!schema`
  or its target's path `!meta.schema`, names what the root's does and is read as a spelling, its payload is the schema
  grammar, and its members are declarations that lift, mint,
  link and compile exactly as root entries do — types, constructors, values, aliases, templates. It declares at least
  one member and takes no parameters. A value of any other namespace type (`!api { … }`) stays a value map. The
  document root is the outermost `!schema`.
- **A member's identity is its path from the root.** `geo.address` is an entry of the document: diagnostics, a kind's
  member list, a chain's `supertypes` and `subtypes`, and `@meta.application` name it so, and two maps may each declare
  `address` without colliding. The map's own entry, `geo`, is a value of `schema` keyed by its members' own names;
  resolved output writes it nested, its members in resolved form, and a resolved form read back keys each member
  under its path.
- **Open maps.** A schema map of one path declared by several documents of a closure is one map, the union of their
  members, as the root namespace is across imports: two imports each declaring `geo` contribute their members to one
  `geo`, and a member path both declare is §2.2.3's collision, by identity. A member's identity is its declaring
  document and its path. The annotations written on a map's entry are its declaring document's, and the map an
  importer sees carries none of an import's. A document contributes by declaring the map itself (`geo => !schema
  { … }`); no grammar declares a dotted name.
- **No lexical scope: every name resolves from the root.** A name's first segment resolves in the document's root
  namespace, its root entries and its import closure; each further segment selects a member of the `!schema` map
  reached so far. Inside `geo`, a sibling is written `geo.address`, and a bare `address` names the root's `address` or
  fails to resolve. There is no relative lookup, so there is no shadowing rule.
- **A qualified name stands at every type position:** a field's type, an element, key or value type, a choice
  variant, a supertype and an `&` operand, a refinement or subtraction source, a `!` head, a template bound, a
  reference's `of` (`meta.ref<geo.address>`), an alias target, and an application's head (`geo.box<text>`). A slot over a
  constructor reads a qualified name in data as it does in a schema (#11): `t: geo.location` at a
  `meta.ref<meta.record>` slot is a member.
- **The value walk passes through nested maps.** A value's qualified name enters a `!schema` map by member name, then
  a namespace value by key and a record value by field: `geo.home` names the value entry `home` of `geo`, and
  `geo.home.at` its field.
- **Synthetics mint at the root**, one entry per form across the document (§8.2), sugar written inside a nested map
  included: `[uuid]` in `geo.tagged` and in a root record is one `array_uuid_…`. A minted name is an identifier; a
  dotted name contributes its text with each dot written `_` (`array_geo_inner_tag_…`), a path under the kernel's
  `meta` its member's name (`array_record_…` for `[meta.record]`, #15); a path under another of the kernel's maps
  keeps its first segment (`numeric_integer_type_…` for an application of `numeric.integer_type`). A synthetic's
  identity is its form, not its declaring document: two documents that mint one form without importing each other
  declare one entry, which a closure holding both holds once, the first import's, as it holds one schema reached by
  two routes. A name only one of them mints is §2.2.3's collision. A document whose own sugar form derives a name an
  import already declares references the import's entry, which must be that form: a declared entry of the name is the
  collision, and a synthetic of another form an internal error, the naming function broken.
- **Qualified tags (Part 1).** A tag is `"!" qualified-name` with no arguments: `!geo.address { … }` in data resolves by
  the walk, and one naming no member is `UNKNOWN_TYPE`. A schemaless read keeps a dotted tag as written, as it keeps
  any tag. [TSON-JSON]'s `$type` takes the same text (edited in place).
- **Name hygiene is per map.** Each schema map is a §8.2 scope of its own, and the per-name rules judge each member's
  own name.
- **The kernel.** `meta.reference.of`, `meta.type_chain.supertypes` and `meta.type_chain.subtypes` are typed
  `meta.type_ref`; `meta.type_name` stays the schema map's key type, one identifier. `ref`'s parameter
  is then a type parameter, the `[meta.type_name]` synthetic is gone, and the kernel's measured wait cycle gains
  `[meta.type_ref]` and loses `meta.identifier` (#4).
- **Hosts.** The default name binder binds a dotted type name to the class of its last segment in one sub-package per
  earlier segment: `geo.route` to `geo.Route`. A processor's choice, not the spec's.

**Resolution, a proposal.**

- §4.1: "nothing in it lifting or minting" holds for a value of a namespace type other than `schema`. Added: "The rules
  for a schema document's root map apply to every value of `schema`: its members are declarations, which lift and
  mint, may be types, constructors or values, and are named by their path from the root."
- §2.2.3, §10: an entry's identity is its declaring document's `!!id` plus its path, and a synthetic's is its form
  (§8.2), so one form minted by two documents is one entry of a closure holding both. A schema map of one path
  declared by several documents of a closure is one map, the union of their members; a member path two of them
  declare collides as any entry's name does, and a schema map's name collides with an entry of that name that is no
  schema map.
- §3.3.2, §5.12: every name resolves from the root. The first segment names an entry of the root namespace; a further
  segment selects a member of a `!schema` map, a key of a namespace value or a field of a record value.
- §4.2, §8.1: `meta.reference.of`, `meta.type_chain.supertypes` and `meta.type_chain.subtypes` are typed `meta.type_ref`;
  a type's name is its path from the root.
- §12.1: `schema-map-entry`'s value admits `"!schema" ws schema-map`; a declared key stays `type-name = meta.identifier`,
  and every reference position reads `qualified-name` (#13's grammar).
- [TSON-DATA] §7.4: `type-ref = "!" qualified-name`. §4: a schemaless read keeps a dotted tag as written.

**Open.**

- **Lexical scope.** A bare name inside a map resolving in that map first, then outward, needs a way to say "from the
  root" once relative lookup exists, and with it a shadowing rule, silent or diagnosed.
- **Synthetics homed by the innermost map**, identity by path, two maps minting one form being two entries. It waits
  on lexical scope: a name minted inside `geo` must resolve where it is written.
- **Who may contribute to a map.** Any document may: the open rule protects a member already declared by collision,
  and nothing reserves a map's unclaimed paths for the document that declared the map first. A library that wants
  its members its own (`geo.` for geo.tn alone) has no way to say so; a closure where two libraries contribute to
  one map is the flat namespace's collision risk inside the map, found only when both are imported.

## 15. [TSON-DATA] §3.1, §7.4; [TSON-SCHEMA] §2.2.3, §3.4, §4.1, §5.3, §6, §8–§9, §13.2 — the roots hold the common
vocabulary

**Problem.** One namespace per schema (#7) reserves every name of an import closure, and every bundled library
imports the kernel, so a schema importing one reserved every kernel and meta-schema name: `record`, `map`,
`template`, `namespace`, `alias`, `type_kind`, `field_name`, `identifier`, `bounded`, and every `*_type`
constructor. Those are plausible names for an author's own entries, and almost none is a name a data schema writes:
it writes `text`, `integer`, `uuid` and `enum` on most lines, and the rest rarely. And one library holding every
data format made a schema that wants dates reserve every network and numeric name too.

**What is running.**

- **The placement rule.** A bundled document's root holds the vocabulary an author writes, and its machinery sits
  under schema maps named for concepts, related concepts kept together: a library's under one map of its own name,
  the kernel's under six, two of which numeric.tn and net.tn add to. The list is curated, entry by entry, not derived
  from what an entry is:

  | Document | Imports | At the root | Under its maps |
  |---|---|---|---|
  | meta.tn | nothing (pre-loaded) | `void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`, `iri_reference`, `regex`, `doc`, `enum`, `set`, `schema` | the other 66. `meta`, the type system (46): the kinds and constructors, `meta.schema` and `meta.field_map`, the map, tuple and choice constructors, `meta.template`, `meta.identifier` and its aliases (`meta.type_name`, `meta.field_name`, `meta.param_name`), the supporting records, sets and enums (`meta.type_chain`, `meta.record_field`, `meta.field_role`, …), the references (`meta.reference`, `meta.ref`, `meta.type_ref`, `meta.identifier_ref`), `meta.value`, `meta.declared`, the annotation machinery (`meta.annotation`, `meta.synthetic`, `meta.application`), the checked assertion `meta.disjoint` and the one-element tuple templates. `atom` (5): the ISO/IEC 11404 datatype properties `atom.ordering`, `atom.bounded`, `atom.exact`, `atom.numeric` and the mixin `atom.specification`. `numeric` (4): `numeric.integer_type`, `numeric.integer_size`, `numeric.integer_member_set`, `numeric.count` (#16). `unicode` (6): `unicode.text_type`, `unicode.text_member_set`, `unicode.normalization`, `unicode.identifier_type`, `unicode.identifier_base`, `unicode.regex_type`. `net` (3): `net.iri_type`, `net.scheme_set`, `net.scheme_name`. `binary` (2): `binary.bytes_type`, `binary.bytes_encoding` |
  | numeric.tn | meta.tn | `number`, `rational`, `complex`, `int8`…`int256` but `int64`, `uint8`…`uint256`, `float32`, `float64` | `numeric`, beside the kernel's members: `numeric.decimal_type`, `numeric.float_type`, `numeric.rational_type`, `numeric.complex_type`, `numeric.ieee_format`, `numeric.complex_component`, `numeric.decimal_member_set` |
  | temporal.tn | meta.tn | `date`, `time`, `datetime`, `duration`, `period` | `temporal`: their five `*_type` constructors |
  | net.tn | meta.tn | `email`, `ipv4`, `ipv6`, `cidr4`, `cidr6`, `mac`, `hostname`, `host`, `media_type`, `uri_reference`, `uri`, `uuid` | `net`, beside the kernel's members: their eleven `*_type` constructors |
  | core.tn | numeric.tn, temporal.tn, net.tn | the author annotations `deprecated`, `title`, `comment`, `examples`, `read_only`, `write_only` | nothing: it declares no map of its own |
  | policy.tn | meta.tn | nothing | `policy`: the processor policy's records (`policy.processor` and its parts), `policy.restriction_level` and `policy.limit` (#16) |

  So an author writes `text`, `!enum [A B]`, `set<text>`, `@doc`, `bytes`, `int32`, `date` and `uuid` bare, and
  `meta.record`, `meta.identifier`, `meta.type_ref`, `meta.ref<meta.record>`, `meta.ref<method>`,
  `@atom.ordering:TOTAL`, `@meta.disjoint`, `!numeric.decimal_type {}`, `!temporal.date_type {}` and
  `!net.uuid_type {}` by path.
- **A library imports the kernel alone; core.tn is the bundle.** numeric.tn, temporal.tn and net.tn each import
  meta.tn and nothing else, so a schema imports the libraries it uses: one importing temporal.tn alone has `date` and
  the kernel's vocabulary, does not see `int32`, and may declare `int32` as an entry of its own, though not
  `numeric`, which the kernel declares; one importing numeric.tn and net.tn has both, their closures meeting at the
  kernel by identity. Every schema reserves the kernel's map names `meta`, `atom`, `numeric`, `unicode`, `net` and
  `binary`. core.tn imports the three, so a schema wanting every bundled type imports core.tn and reserves those,
  `temporal` and every root above; `record`, `identifier`, `type_ref`, `field_name`, `bounded`, `value`, `kind` and
  `core` stay free. No two of the bundled documents declare one root name or one member path, and the maps two of
  them declare, `numeric` and `net`, are open and the union of their members, so no closure of them collides with
  itself. The corpus pins each case (`a-schema-importing-temporal-alone-*`,
  `a-schema-importing-numeric-and-net-has-both-libraries`, `a-schema-importing-a-library-declares-its-map-name`).
- **`enum` is declared at the kernel's root**, `enum => meta.enum_type ^ { name_type?: = meta.identifier }`. It is on
  the kernel's wait cycle (its own enumerations, `boolean` and `meta.field_role`, apply it), the kernel writes them
  `!enum [...]`, and resolved output tags an enumeration `!enum`. There is no `meta.enum`.
- **The author annotations are core.tn's**: `deprecated`, `title`, `comment`, `examples`, `read_only` and
  `write_only`, each `@meta.annotation`, written bare by a schema importing core.tn (`@title:"…"`, `@deprecated`).
  A schema importing the libraries without core.tn may declare annotations of those names itself. Nothing checks
  one against another: `@read_only` and `@write_only` on one field load. The reading form (`tson strip`) drops
  `@doc` alone.
- **An alias of a constructor is applied as its target** (§3.3.1), a template's held body included: the resolver's
  read of a declaration follows its head's alias chain and reads the value against the constructor at the end, so
  `<T> !my_enum [a T]` over `my_enum => enum` builds an enumeration. The bundled documents do not rely on it; it
  stands as the behaviour §3.3.1 states.
- **The kernel is one document, meta.tn**, header `!!id` alone and pre-loaded (§3.4): its one wait cycle (#4),
  every name its declarations reference, and the concepts that belong with them. A kind and a constructor are the
  kernel's by declaring document (§4.1), which is meta.tn alone, so the libraries' constructors are ordinary
  constructors of the documents that declare them.
- **The kernel, measured.** Over `meta-resolved.tn`'s own declarations (no compiled reader consulted), the cycle is 26
  entries: `meta.record`, `meta.array`, `meta.set_type`, `enum`, `meta.map`, `meta.field_map`, `meta.reference`,
  `numeric.integer_type`, `unicode.text_type`, `meta.value_type`, `boolean`, `integer`, `text`, `numeric.count`,
  `numeric.integer_size` (`count`'s resolved `size` is read against it, #16), `meta.value`, `meta.type_ref`,
  `meta.type_chain`, `meta.record_field`, `meta.field_group`, `meta.field_role`, `meta.enum_set` and four lifted arrays.
  Closing it under every name its declarations reference — every rule-1 wait, every field type and `of`, every
  annotation type its entries carry, and what reading the document itself waits on, its root tag's `schema`, an alias
  of `meta.schema`, with that map's key and value types and the annotations on its keys — gives 70 entries, adding
  `schema`, `meta.namespace`, the base kinds,
  `meta.alias`, `meta.declared_type`, the identifier, iri and regex families with `atom.specification`,
  `meta.template_param`, `meta.declared`, `meta.type_name`, `doc`, `meta.annotation`, `meta.synthetic`,
  `[meta.template_param]`, the classifying annotations `atom.ordering`, `atom.bounded`, `atom.exact` and `atom.numeric`,
  which the root atoms carry on their keys, `iri_reference`, the source `iri` refines, and `int64`, the source
  `numeric.count` refines. The iri and regex families are there because `text_type.pattern` is typed `regex` and
  `identifier_type`, `iri_type` and `regex_type` compose `atom.specification`, whose `spec` is an `iri`. The kernel is
  that closure and the concepts that belong with it, which nothing in the closure names: `meta.tuple`,
  `meta.choice`, `meta.tuple_element` (with its lifted array), `meta.template`, `meta.application`,
  `meta.ref`, `meta.field_name`, `meta.disjoint`, `meta.tuple1`, `meta.voidable_tuple1`, `set`, the octet sequence
  (`binary.bytes_type`, `binary.bytes_encoding`, `bytes`) — 85 entries, 66 under its six maps, 13 at the root and six
  synthetics, with the six maps themselves the 86th to 91st. A test holds the kernel to containing the closure, pins
  the cycle at 26 and the closure at 70, and pins the related set, so an entry that drifts in either
  direction fails it. The longest wait chain is 4 (`doc` → `meta.annotation` → `void` → `meta.void_type` →
  `[meta.type_ref]`). A schema importing meta.tn alone has every elided construction and `meta.ref<T>`.
- **The bootstrap** builds by hand 13 of the cycle's 26 entries — its instances and its one refinement (`meta.value`,
  `boolean`, `meta.field_role`, `meta.enum_set`, `integer`, `numeric.count`, `text`, `meta.type_ref`,
  `meta.field_map`) and the four lifted arrays — resolves the other thirteen, records and compositions that read
  nothing, from their grammar, and reads the 59 entries beyond the cycle in wait order (#4).
- **Each library declares `<name> => !schema { … }`** and its instances at its root (`uuid => !net.uuid_type {}`,
  `int32 => !integer ^ { size: { bits: 32  signed: true } }`). The schema model binds the kernel's entries and each
  library's members under its own map, by declaring document, each by its last segment (`meta.record` to
  `RecordBody`, `unicode.text_type` to `TextType`, `numeric.decimal_type` to `DecimalType`, `net.uuid_type` to
  `UuidType`, `atom.specification` by alias to `AtomSpecification`); no two of them share a last segment, and the
  binder refuses a vocabulary where two do. A member a consumer contributes to an open map binds through the
  consumer's binder, and a consumer's dotted name keeps #14's sub-package rule. A processor's choice, not the
  spec's.
- **Open namespaces (#14).** `meta` is open: any schema may contribute a member, `meta => !schema { code_type =>
  unicode.text_type ^ { max_length?: = 4 } }` beside `code => !meta.code_type {}` loads and reads. A member path already
  declared is the ordinary collision (`meta => !schema { record => … }` collides with the kernel's), and a member's
  identity is its declaring document and its path, so a user's `meta.code_type` is no kernel entry: an instance of a
  refinement of a backed constructor reads through it, and one composing a base kind directly is a new form this
  library does not back (#4). `atom`, `numeric`, `unicode`, `net`, `binary` and `temporal` are open on the same
  terms, and the bundled documents use it: numeric.tn adds its members to the kernel's `numeric` and net.tn to the
  kernel's `net`, and an importer of either sees one map holding both documents' members.
- **Every name resolves from the root (#14), inside the bundled documents too.** An entry names a member of a map by
  its path: `integer => !numeric.integer_type {}`, `record => meta.product & { fields: meta.field_map … }`,
  `format: numeric.ieee_format`, `@doc:"…"`. There is no lexical scope; synthetics a document's sugar lifts are its
  root's.
- **An annotation's name is a qualified name** ([TSON-DATA] §3.1, §7.4: `"@" qualified-name`), resolved from the root
  as every name is: `@doc` and `@title` bare, `@atom.ordering:TOTAL`, `@atom.numeric` and `@meta.disjoint` by path.
  A schemaless read keeps a dotted name as written, and an empty segment (`@x..y`) is a parse error.
- **The elided constructions target the kernel's entries**, whatever a document imports: `{ … }` is `meta.record`,
  `[T]` `meta.array`, `{K => V}` `meta.map`, `[A, B]` `meta.tuple`, `(A | B)` `meta.choice` and a partial application
  `meta.alias`. The root tag stays `!schema`, the pre-loaded kernel's root `schema`, `schema => meta.schema`, an alias
  (#8), so a position typed `meta.schema` admits it as it admits any alias of its type; a nested map is tagged
  `!schema` in source, or `!meta.schema`, as resolved output writes it.
- **`schema` is a kernel root name like the others.** A schema whose closure holds meta.tn writes it bare at a type
  position (`types: schema`, kept as written in resolved output) and may not declare it, the collision any root name is
  (`a-schema-importing-the-kernel-declares-schema`). A schema importing nothing may declare `schema`, as it may declare
  `int64`; a tag still names the pre-loaded kernel's, the root's and every nested map's, so `geo => !schema { … }`
  beside a local `schema => { … }` is a nested map and the local name is the schema's own everywhere else
  (`an-import-free-schema-declares-schema-and-nests-a-map`). The tag is matched as a spelling because classification
  and the choice of the payload's grammar precede reading any import, and the pre-loaded kernel gives the one answer
  a lookup would.
- **Resolved output** tags a body by its constructor's name (`!meta.record`, `!enum`, `!numeric.decimal_type`,
  `!net.uuid_type`) and names each entry as its document does (`!meta.record_field { type: text }`, `@meta.synthetic`,
  `@meta.application`). The kernel's form is `!schema { numeric => !meta.schema { … } … meta => !meta.schema { … }
  … }`, its 69 members in its six maps and its thirteen root entries and seven synthetics at the root; each library's is
  `!schema { numeric => !meta.schema { … } number => !numeric.decimal_type {} … }`, its map holding its own members
  and none of the kernel's; core's holds its six annotations. The five fixtures this implementation
  holds itself to are `meta-resolved.tn`, `numeric-resolved.tn`, `temporal-resolved.tn`, `net-resolved.tn` and
  `core-resolved.tn` (§13.3); policy.tn has none.
- **Hash pins** run bottom-up in the import order: each library and policy.tn pin meta.tn, core.tn pins the three
  libraries, so an edit to the kernel moves every pin above it.
- **Measured:** `Tson.standard()` loads six documents; in a fresh JVM, the first call about 440 ms and a warmed call
  about 65 ms.
- **What is not running:** a library's resolved form holds instances of constructors its own source declares
  (`!numeric.decimal_type {}`, `!net.ipv4_type {}`), or the kernel's form `!meta.template { … }`, so each reads back
  only beside its source, the gap #4 states for any such form. An annotation inside a value read through an imported
  type's reader resolves in the namespace of the schema declaring that type, not the annotating document's (§3.3.3):
  a value entry's payload annotated `@atom.numeric` is refused where the value's type is declared by a schema whose
  closure lacks it. This library's gap.

**Resolution, a proposal.**

- §3.4, §9, §13.2: the kernel, meta.tn, `!!id` alone and pre-loaded, holds its wait cycle, what it names and the
  concepts that go with them; numeric.tn, temporal.tn and net.tn each import it alone and declare a data-format
  family; core.tn imports the three and declares the author annotations; policy.tn imports the kernel alone. §2.2.3's
  reservation of a closure's names covers each root and the map names `meta`, `atom`, `numeric`, `unicode`, `net`,
  `binary` and `temporal`, and `policy` for a closure holding policy.tn.
- §4.1, §9: the bundled documents' convention, stated once with the table above: the root holds the vocabulary an
  author writes, the machinery sits under maps named for concepts: the kernel's `meta` (the type system), `atom`
  (datatype properties), `numeric`, `unicode`, `net` and `binary` (the atom constructors by family), and a
  library's map of its own name, which may be one of the kernel's. A kind and a constructor are the kernel's by
  declaring document.
- §2.2.3 (#14): a schema map of one path declared by several documents of a closure is one map, the union of their
  members; a member path two declare collides.
- §4.1, §5.3, §5.4, §5.10 and every bundled name in the spec's text: written as the table places it; the desugar
  targets are `meta.record`, `meta.array`, `meta.map`, `meta.tuple`, `meta.choice`, `meta.alias`.
- §6: the documentary annotation is `@doc`, the kernel's; the author annotations are core.tn's.
- §3.3.1: an alias of a constructor is applied as its target is, in a held body as anywhere.
- §3.4, §4.1, §12.1 ([TSON-DATA] §2.2): the kernel's root declares `schema => meta.schema`, and the `!schema` tag of a
  schema document's root and of a nested map names it, resolved against the pre-loaded kernel whatever the document
  imports or declares.
- §8.1 to §8.3: resolved output tags a body by its constructor's name; an alias of a template applies it.
- [TSON-DATA] §3.1, §7.4: `annotation = "@" qualified-name [ ":" data-value ]`.

**Open.**

- **The list is a judgement per entry.** It puts `iri`, `regex` and `bytes` at the kernel's root and
  `meta.identifier` in `meta`, `uuid` and `uri` in net.tn, `doc` at the kernel's root, the classifying annotations
  under `atom` and the kernel's atom constructors under `numeric`, `unicode`, `net` and `binary`. A revision that
  wants a rule rather than a list needs a test an entry can be put to; "what an author writes" is the intent, not yet
  a test.
- **The tag beside a local `schema`.** §2.2 and §3 say the root tag classifies the document and §3.3.1 that no local
  declaration captures a `!` target, but no section says which `schema` a nested map's tag names in a schema that
  imports nothing and declares its own. The running answer is the pre-loaded kernel's, as for the root, so the one
  spelling means two things in that document, the tag and every other position. The alternative, refusing a local
  `schema` in every schema, would be a reservation of a name no import brings, which no other root name has.
- **Where the author annotations live.** core.tn holds them for now; whether they belong in the kernel, in a library
  of their own, or in core.tn is open with the user.
- **Who may contribute to `meta`** (#14's open item): any schema may, the collision rule guarding the kernel's own
  members only by their being declared first. The same holds for every bundled map; numeric.tn and net.tn contributing
  to the kernel's `numeric` and `net` is that rule used by the bundled documents themselves.

## 16. [TSON-DATA] §9.1; [TSON-SCHEMA] §5.3, §5.10, §5.11, §9, §11.5, §13.2 — a count is a fixed 64-bit range

**Problem.** Every facet that counts something — `min_items`/`max_items`, the text and bytes lengths,
`total_digits`/`fraction_digits`, a temporal `precision`, a network's `min_prefix`/`max_prefix`, a UUID's `version` —
is typed `non_negative_integer`, `!integer ^ { min: 0 }`, which is unbounded. So a conforming processor must accept
`max_length: 10^40` and must hold it, which forces an arbitrary-precision integer into every implementation's schema
model for a value no string, array or digit count reaches. And §5.11's `integer_type` types `multiple_of` the same
way, though a step is not a count but a value of the constrained integer's domain: a `uint128` stepping by 2^64 is
refused. The resource limits of §9.1 and §11.5 carry the same type and the same problem, and the spec states no
range for any of them.

**What is running.**

- **The count type has a fixed range.** `int64` is declared at the kernel's root, beside `integer`, and numeric.tn
  declares the rest of the sized ladder. The kernel's `numeric.count => !int64 ^ { min: 0 }` is `int64`'s
  non-negative half, 0 to 2^63-1, a refinement rather than an alias so that it keeps its minimum and is an `int64` by
  its chain, as `iri` is an `iri_reference`: every implementation holds a count in a native type (JavaScript in a
  BigInt). It stands where Revision 38 writes `non_negative_integer`, which the bundled documents do not declare, and
  types every count facet of the six. `max_length: 9223372036854775807` resolves;
  `max_length: 9223372036854775808` is outside the facet's type, a resolver error.
- **A facet holding a value of the constrained type is typed by that type's family.** `integer_type`'s bounds and
  `members` were `integer` already; `multiple_of` is now `integer` too, its strictly-positive rule the load-time
  coherence check it was, so a `uint128` steps by 2^64.
- **`integer_size.bits` is `integer`, not a count.** `int64`'s `size` is read against `integer_size`, and `count`
  refines `int64`, so `bits: numeric.count` makes `count` wait on itself and the kernel never becomes ready (§3.4.1).
  That `bits` is above zero is a schema-load coherence check, as `multiple_of`'s sign is: `bits: 0` is a resolver error.
- **A count beyond what a host holds is not an error.** A `max_*` past the most a host's strings or collections hold
  is never reached and admits every value; a `min_*` past it is met by no value, so every value fails validation.
  Neither is a schema error or a refusal to bind.
- **policy.tn's limits are `policy.limit`**, `!int64 ^ { min: 1 }`, and its entries
  are members of a `policy` schema map: the record a deployment writes is `policy.processor`, beside
  `policy.identifier_policy`, `policy.script_policy`, `policy.restriction_level` and `policy.limits`. A processor
  holding a limit in a narrower type than the range caps a larger value at the widest it holds (this library holds a
  count or depth in a 32-bit `int`).
- The corpus pins the edges (`a-count-facet-holds-the-largest-signed-64-bit-value`,
  `a-count-facet-past-the-signed-64-bit-range`, `a-multiple-of-above-the-count-range`, `an-integer-width-of-zero`,
  `a-minimum-length-no-value-reaches`, `a-maximum-item-count-every-value-meets`,
  `a-schema-importing-the-kernel-declares-int64`, `a-schema-importing-policy-declares-the-policy-map-name`,
  `a-deployments-policy-document-is-a-policy-processor`, `a-policy-limit-of-zero`).

**Resolution, a proposal.**

- §9, §13.2: the kernel declares `int64` and `count => !int64 ^ { min: 0 }`, and every count facet of the bundled
  vocabulary is typed by `count`; §5.10's derived parameter type for a count slot (`min_items: N`) is `count`.
- §5.11: `integer_type.multiple_of` is `integer`, strictly positive by coherence; `integer_size.bits` is `integer`,
  above zero by coherence.
- §5.3 and the facet descriptions: a count facet admits any value of `count`; a bound past what a processor's values
  can reach is met or never reached, never a schema error.
- [TSON-DATA] §9.1, §11.5: a limit is a positive `int64`, and a processor holding one in a narrower type caps it.

## 17. [TSON-SCHEMA] §4.2, §5.2, §5.5, §7.4, §13.2 — a facet is typed by its family's instance, and `value` keeps one slot

**Problem.** §7.4's "Constraint fields typed as `value`" types every bound, step, member set and network list of the
non-kernel atom constructors `value`, because "the constrained atom cannot be referenced at the point of declaration"
(bootstrap ordering, §3.4), and then needs a rule of its own to make those slots mean anything: "the resolver reads
it under the atom it stands for, once that atom is in scope", a set's members read before the set is formed, and an
eager-conversion paragraph whose accepted types are "an implementation choice". Once a constructor and its canonical
instance sit in one document (#7's one namespace, #15's roots), that reason is gone: `number => !numeric.decimal_type
{}` is declared beside `numeric.decimal_type`, so `decimal_type.min` can be typed `number`, which is the kernel's own
pattern — `integer_type`'s bounds are `integer`, "the exception that proves the rule". With the facet typed, the
ordinary field reader does what the bespoke rule described, and the rule, its eager-conversion paragraph and the
implementation choice inside it have nothing left to govern.

**What is running.**

- **Every facet of a bundled constructor holding a value of the constrained family is typed by that family's
  instance**, declared at the root of the same document: numeric.tn's `decimal_type` bounds and `multiple_of` are
  `number` and `members` a set of `number`; `rational_type`'s bounds and `multiple_of` are `rational`; temporal.tn's
  `date_type`, `time_type` and `datetime_type` bounds are `date`, `time` and `datetime`, `duration_type`'s bounds and
  `multiple_of` `duration`, `period_type`'s `period`. Nothing in numeric.tn, temporal.tn or net.tn names `meta.value`.
- **A float's bounds are `number`, not `float64`.** float_type's own `@doc` validates them "against the value as
  written, before rounding", so they are exact; typing them by the grid would round a bound the type says is unrounded.
- **net.tn's network lists are `[cidr4]` and `[cidr6]`**: `ipv4_type` and `cidr4_type` list `cidr4`s, `ipv6_type`
  and `cidr6_type` list `cidr6`s. A malformed entry, or one with nonzero host bits, is refused at schema load by that
  reader, as any value of it would be, so the coherence rule of §5.5 is left only the pair's emptiness.
- **No wait cycle.** Each instance is `!C {}` with no facets, so a facet read against it waits on nothing further;
  the resolver's wait order (#4) reports none over the five documents.
- **A refinement of a refinement reads its bound as the base instance.** `positive => !number ^ { min: 0 }` then
  `!positive ^ { min: -5 }` reads `-5` as a `number`, which it is; its widening is §5.7's tightening rule to refuse,
  not the read's. The verdict is the one Revision 38's text gives, by the rule that owns it.
- **A bound is spelled as a value of its family.** `!number ^ { min: 0x10 }` is refused, as `!number 0x10` in data
  is (§5.6 admits no based-integer `number`), where a `value`-typed slot decoded `0x10` by §4 and narrowed it. A
  refused bound carries its family reader's verdict (`'P1Y' is not a valid duration … a month is a period`), as the
  kernel's `integer`-typed bounds already do.
- **`value` keeps one slot**: a field's default or pin (`meta.record_field.value`, and the same slot on #19's
  `meta.record_template_field`), whose type is the field's declared type and is not expressible in the kernel without
  dependent fields. `value => !meta.value_type {}` stays, and its `@doc` names that slot alone.
- The resolved fixtures (`spec/fixtures/numeric-resolved.tn`, `temporal-resolved.tn`, `net-resolved.tn`) carry the
  typed facets; net.tn's lifted `[meta.value]` becomes two synthetics, `[cidr4]` and `[cidr6]`.

**Resolution, a proposal.**

- §13.2 and the constructor listings: type each facet as above.
- §7.4: delete "Constraint fields typed as `value`" and the eager-conversion paragraph after it; what remains true is
  the ordinary rule that a field is read by its type at schema load, a value the type refuses being a resolver error.
- §4.2's `value` entry: "the escape hatch for fields whose type the schema language cannot express at the point of
  declaration" is `record_field.value` alone; say so. §5.2's eager-resolution paragraph keeps its reference to the
  `value`-typed default, and drops "this matches §7.4's eager-conversion rule for constraint-field values".
- §5.5: a network list is a list of the family's network instance, and its coherence rule is the pair's emptiness.

## 18. [TSON-SCHEMA] §5.7, §5.8, §7.2 — a restated field's type narrows by IS-A, which no section states

**Problem.** §5.7 says a refinement "can only restrict, never expand", and that "restating the type-ref remains
necessary when the tightening also narrows the field's type", so a restated type is meant to narrow. It never says by
which relation. Its one worked case argues by value sets — `void` "refines every type at a voidable position, the
value set `{_}` being a subset of every voidable position's" — while the only relation §7.2 defines between two
types is the nominal one, `T` in `S`'s `chain.supertypes`. The two readings part on ordinary schemas: `[int8]`
restating `[int32]`, a variant restating its choice (`x: (int32 | text)` restated `x: int32`), and a fresh
`!integer_type { size: { bits: 8 } }` restating `int32` each narrow the value set and are no IS-A. §5.8 applies
§5.7's rules to a composition's tightening entries, so the two operators agree, and the gap is §5.7's alone.

**What is running.** The nominal reading, with §5.7's `void` case beside it. A restated field's type is the inherited
type, or a type IS-A it by §7.2's chain, alias hops followed on both sides, or `void` at a voidable position; anything
else is a resolver error against the restating declaration, in refinement and composition alike. It is judged at link,
where every name both types hold resolves, against the nearest closed records on the declaration's chain. The kernel's
`meta.namespace => meta.map ^ { key_type: meta.identifier_ref … }` passes by `meta.identifier_ref`'s refinement of
`meta.type_ref`; `[int8]` for `[int32]` and a variant for its choice are refused, no IS-A edge joining either pair
(#5: a container instance has no refinement, and two instances of one constructor have no edge). A restatement over an
applied operand that denotes no entry, `dog => pet<"dog", dog_type> & { pet: cat_type }`, reaches only the open
family base, and a subtraction's tightening entry (§5.9's rule 4) has an empty chain; neither is judged yet.

Nominal, because it is the relation every typed position already admits by (§7.2's subsumption), so a value of the
refinement read at the source's position meets the field's type by the rule that reads it; and because it is total and
decidable from names, where value-set inclusion needs a containment judgement for every pair of families, containers
and choices included, that the series states nowhere.

**Resolution, a proposal.** §5.7, beside the elided type-ref paragraph: "A restated field's type MUST be the inherited
type or a type IS-A it (§7.2), or `void` at a voidable position; any other restatement is a resolver error. §5.8's
tightening entries follow the same rule." If a structural narrowing between containers or a variant restating its
choice is wanted, it is a rule of its own that each constructor states, as #5's last paragraph leaves the container
constructors' tightening rules theirs to state.

## 19. [TSON-SCHEMA] §1.3, §4.2, §5.7, §5.10, §8.1, §13.2 — a record template is its own constructor, its fields labelled

**Problem.** §5.10 and §8.1 make a record template "a `!record` with `parameters`", and §1.3's resolved-output
consumer recognises one "by its `parameters`". So one constructor holds two things with opposite contracts: a closed
record, which is what readers and writers are built for, and a family base, which nothing is ever read or written
against — a position it types dispatches to a member. Every consumer that builds a reader or writer from a `!record`
has to ask the optional list first, and a consumer that forgets builds a reader for a template; nothing in the
vocabulary stops it. Inside the template, a parameter rides a field's `type` or `value` unlabelled, told from a type
name or a literal by §5.10's shadowing rule "in resolved output as in source", so a resolved-output consumer that wants
to know whether `value: S` is a pin or a parameter needs the template's parameter list and the shadowing rule, and a
literal that happens to spell a parameter has no resolved form at all.

**What is running** (on the Revision 39 proposal branch; `spec/m/meta.tn` and the Part 2 draft carry it).

- **The kernel splits the constructor.** `meta.record` loses `parameters` and is closed: no field carries a parameter.
  `meta.record_template => meta.product & { access_pattern? size_type? fields: meta.record_template_field_map  groups?
  extension? discriminators? name_type?  parameters: [meta.template_param; 1..] }` is the family base: a type a
  position may name, ABSTRACT by construction, IS-A its parents and parent of its declared applications, and never
  read or written against. `extension` is each application's, `discriminators` the family's, `groups` and
  `name_type` each application's, unchanged by substitution.
- **A template's field labels a whole parameter.** `meta.record_template_field => { ( type: meta.type_ref | type_param:
  meta.param_name )  optional?  voidable?  role?  ( value: meta.value | value_param: meta.param_name )? }`: `type_param`
  names a parameter standing for the whole type, `value_param` one standing for the whole value, and `type` and
  `value` are as on a record field. A parameter as an argument of an application in `type` (`items: box<T>`) or in a
  parent stays where it is, recognised by its text resolving into `parameters` — the one place resolved output still
  applies the shadowing rule. `<T, S> { status?: int32 = S  body: T }` resolves to `!meta.record_template { fields: {
  status => !meta.record_template_field { type: int32  optional: true  role: FIXED  value_param: S }  body =>
  !meta.record_template_field { type_param: T } } … }`, and its application `created => response<order, 201>` to an
  ordinary `!meta.record` whose `status` is `value: 201`: substitution turns `type_param` into `type` and
  `value_param` into `value`, and changes no other fact.
- **The source spelling is unchanged.** The labels are the resolver's: a token in the body is a parameter by §5.10's
  shadowing rule where it is written, and is labelled then. `!meta.record_template { … }` written in source is refused
  as resolver vocabulary, as `!meta.template` already is.
- **Two declaration-time rules, one of them new.** A selector's type names no parameter — §5.10's existing condition,
  unchanged and already refused (`a-discriminator-typed-by-a-parameter`). **New: a literal `value` needs a closed
  type**, so a field typed by a parameter, whole or as an argument, takes `value_param` or no value: `<T> { v?: T ~ 5
  }` and `<M> { v?: capped<M> ~ 5 }` are refused at the declaration. Revision 38 accepts both and judges the literal
  per application (`box<int32>` and `box<text>` pass; `capped<3>` refuses `5`), so a template could be valid for
  some arguments and not others; the rule makes the template's verdict its own.
- **Kinds follow the constructor.** A record template is a member of `meta.ref<meta.record_template>` and of no
  `meta.ref<meta.record>`, where §5.10 calls it "a member of `kind<record>`"; its declared applications are records.
  The kernel's `meta.ref<meta.map>` gains `meta.record_template_field_map` (`the-kind-of-map-holds-the-namespace-types`).
- **In this implementation the split is a type-level fact.** The record template is its own Java type beside the
  record's, so a reader, a writer or a member dispatcher built from a record cannot be handed a template, and the
  "a template IS-A the base is no member" rule both encodings' dispatchers carried is the record type test they
  already made. The record template's fields are a second field type, and the two record shapes share no Java parent,
  as `record_template` and `record` share no kernel parent beyond `product`: the duplication is a handful of arms
  (field-name hygiene, the name type, the selector derivation) that read the same five record-level facts.
- `spec/fixtures/meta-resolved.tn` carries the three new entries and `record` without `parameters`; the corpus's
  record-template outputs move to the new form, with vectors for the labels, both literal refusals and the refused
  construction.

**Open edges, not resolved by the split.** An *inherited* literal whose text spells a parameter of the absorbing
template (`base => { kind: mode = T }` with `fam => <T> base & { … }`) is labelled `value_param: T` by the same
shadowing rule, though the author never wrote it in the template's body; §5.10's "a literal spelled like a live
parameter is unreachable" covers the body as written and is silent on an absorbed field. And the kernel duplicates
`record`'s record-level fields on `record_template` rather than composing a shared parent; if one is wanted, it is a
`meta.product` refinement holding `chain`, `groups`, `extension`, `discriminators` and `name_type`, with `fields`
left to each.

**Resolution, a proposal.**

- §5.10, "Template forms": a record template is a **`record_template`**, not a `record` with `parameters`; a record is
  closed. "Held bodies": `<T> { x: T }` is `!record_template { fields: { x => !record_template_field { type_param: T }
  }  parameters: [ … ] }`; the three places a parameter stands are a field's `type_param`, its `value_param`, and an
  argument of an application; add "A literal value needs a closed type: a field whose type is a parameter, or an
  application over one, takes a value parameter or no value, and a literal there is a resolver error at the
  declaration." "Substitution": a `type_param` and a `value_param` become `type` and `value`. The family-base
  paragraph: a member of `kind<record_template>`, recognised by its constructor; no reader or writer is built for it.
- §8.1, "Open entries" and the `record_field` paragraph: state `record_template` and `record_template_field` as above,
  and drop "in a record template it may hold a parameter, unlabelled"; `template` and `record_template` are both
  resolver vocabulary. §8.1's table row and "Closed entries are parameter-free": an open entry is a
  `!record_template` or a `!template`.
- §1.3: a consumer recognises a record template by its constructor; §4.2's application-form list names
  `record_template_field.type`; §5.7's open modifiers: the parameter is the field's `value_param` while open.
- §13.2: the kernel listing gains the three entries and `record` loses `parameters`.
