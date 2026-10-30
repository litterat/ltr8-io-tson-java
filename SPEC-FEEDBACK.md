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
sentence it takes `box`'s form and reads `int8` in the schema's namespace. But an alias's resolved entry is a
`reference`, a meta constructor, which by the first sentence makes it a structure-namespace template. The section
does not say which sentence wins for an alias.

**What is running.** The resolved entry decides: `rb`'s held head is `reference`, not `record` and not an open
template, so `ParameterTypes.readsInStructure` answers true and the written type is checked in the structure
namespace. The schema above is refused at `rb`: "parameter 'N' is declared 'N: int8', and this template applies a
constructor of the governing meta, so its value parameters are the meta's types and a written type names one there --
'int8' is not in the structure namespace (§5.10)". The same alias with no written type (`rb => <N> box<N>`) resolves,
`N`'s type being derived from `box`'s use rather than written. `design/held-template-bodies.md` ("The template's form
decides where value types are read") states the rule as built.

**Resolution to decide.** The third sentence is the one an author relies on — an alias of a record template is used
exactly like the record template — so the proposal is that §5.10 says an alias takes the form of the template its
target applies, following aliases through to the first non-alias head, and that `reference` is never itself a form.
The implementation then follows the target through `reference.target` in `readsInStructure`. The alternative, that
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

The kernel measured from `meta-kernel-resolved.tn`'s own declarations, no compiled reader consulted, has one wait
cycle, holding `record` and 29 more of the 79 entries (`boolean`, `integer`, `text`, `identifier`,
`non_negative_integer`, the enum and set machinery, `field_group`, `namespace` and `field_map`, and the synthetics
they name), every other entry ordered after it, the longest chain 4. The kernel declares `annotation => void`:
annotating itself with `@annotation` would be a second cycle, and the marker changes no value (§6). The bootstrap
builds by hand exactly the instances and the one refinement on that cycle (a test holds the set to the measurement),
and makes every other entry ready by the algorithm above. A pin reached through an entry not yet ready binds as
written (rule 3), which is what lets `iri` be read against `iri_type`, whose `spec` pin is typed by `iri`. The output
equals the kernel's ordinary resolution against it, entry for entry. Compilation follows rule 3: a record's reader
holds its pins and defaults as written while it is built and decodes them through their types once every reader of
the schema exists, before the schema is handed out. Link remains the one place a pin is judged, and a bad pin or
default still fails the load (rule 6).

What is not running: a resolved form is still read under its source's imports rather than by this algorithm over its
own entries (§8.1, "Resolved forms" in the proposal), so a fixture holding an instance of a constructor it declares
does not read back. And rule 2's example pair, a constructor whose facet is typed by its own instance (`decimal_type`
beside `number`), is expressible only in the kernel: an instance of a constructor of types the kernel and meta.tn do
not declare has no body this library models, and is refused as `NOT_IMPLEMENTED`.

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
2. **A reference orders nothing.** A name at a slot typed `type_kind`, `kind<C>`, `ref<T>` or `type_ref` is read by
   its atom's grammar alone; what it names is resolved and judged once every entry is ready (§4.2, §8.3). So
   `tree => { t: tree }`, mutual recursion, and a constructor whose facet is typed by its own instance
   (`decimal_type => atom & { min?: number }` beside `number => !decimal_type {}`) are sound.
3. **A held value orders nothing.** A `value`-typed slot (a field's default or pin, a type argument's value) and a
   template's body hold what a schemaless read gives ([TSON-DATA] §4), and bind as written: a pin or default is not
   decoded through its type when the reader carrying it is built, but judged against its type once every entry is
   ready, still at load (rule 6). Where a declaration names the type a form reads against, a read reaches that type
   by rule 1, the name itself ordering nothing: a value of `field_map` reaches `record_field`, which `field_map` pins
   as its `value_type`, and a value of a family base (§5.2) reaches each subtype, whose selector pins §5.2 confines to
   an atom or an enum.
4. **A cycle is refused.** Entries waiting on each other through rule 1 are a resolver error naming the entries on the
   cycle. A loop of references is not a cycle under this rule; §8.3 refuses a reference chain with no terminal. The
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

## 5. [TSON-DATA] §1, §7.2.5; [TSON-SCHEMA] §5.5, §12.1 — `!` marks a value in data and a construction in a schema

**Problem.** In data and in resolved output `!T x` is a value of `T`. In a schema declaration `!C { … }` builds a
type from the constructor `C`, and `!I ^ { … }` refines the instance `I`: the same mark asserts a construction, and
`^` is overloaded between record refinement (field declarations) and atom refinement (data). Under one level a `!`
head would take both roles, a type from `!decimal_type { … }` and a value from `!operation { … }`, so the line could
not be classified before its head resolves.

**What is running.** None of the proposal below.

**Resolution, a proposal.** One binary operator, `<-`, at a declaration's right-hand side:

1. **Construction is `c <- payload`.** `c` resolves to `top` or an entry IS-A it, and the payload is a core value read
   against `c`'s vocabulary, braced or positional (§5.6): today's `!C payload`. Kind transfers, no IS-A (§5.5).
2. **Atom refinement is `i <- { … }`.** `i` resolves to an atom-family instance and the body is a `record` or
   `empty-brace` merged over `i`'s bindings, under §5.7's tightening rules: today's `!I ^ { … }`. IS-A `i` (§5.5).
   Which of the two applies is decided by what the left operand resolves to (§3.3.1), not by the grammar.
3. **A parameter list requires a constructor operand.** `<N> integer_type <- { max: N }` is a template, as
   `<N> !integer_type { … }` is today; `<N> integer <- { min: N }` is refused, as `<N> !integer ^ { … }` has no form
   today (§5.10).
4. **`<-` builds types.** An operand whose instances are not types, a namespace type (§4.1), is refused naming `!`:
   `orders => !interface { … }` keeps `!`, the entry being a value. A record-type operand is refused naming `^`, which
   owns record refinement.
5. **`!` at a declaration head builds no type.** `!C { … }` with `C` a constructor whose instances are types, and
   `!I ^ { … }`, are refused naming `<-`. `!` keeps one meaning in every grammar: a value of the named type.
6. **Resolved output is unchanged.** It is a data document, and `!record { … }`, `!integer_type { … }` there are data
   tags describing a construction, not asserting one.

Grammar (§12.1): `instance` and `atom-refinement` become one production,

```abnf
construction = [type-params ws] type-name ws "<-" ws core-value
             ; rules 1-4: a constructor operand takes any core value and
             ; may carry type-params; an atom-instance operand takes a
             ; record or empty-brace and none
```

`refined-def` is unchanged, and §12.3 drops the prefix `!` row and adds `<-`: binary, whitespace on either side
optional.

**Lexer ([TSON-DATA] §7.2.5, §1).** `<-` is a compound special token, lexed as `=>` is: `<` immediately followed by
`-`, where that `-` would otherwise lex as the bare special token. A `-` that begins an unquoted token keeps doing so,
so `box<-3>` lexes as `<`, `-3`, `>` as today. No new character, and no data document lexes differently, `<` being a
parse error in the data grammar. §1's list of lexer changes under principle 6 gains it.

**Kept out.** The operator's generalisations: refining a non-atom constructed type (`names => [text]`,
`few_names => names <- { max_items: 3 }`), and a type as the right operand, its bindings merged over the left's
(`pos_int32 => int32 <- positive`, `smallint => integer <- maxint<10>`). Each adds a capability with rules of its own
(which constructors state a tightening rule; whether the right operand adds IS-A), and neither is needed to retire the
`!` head.

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
- `scoped`, `scope_kind`, `scope_set` and `schema_identity` are gone from meta.tn, and `extern`, `dynamic`,
  `extern_of` and `extern_type` from core.tn.
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

A foreign type returns as a constructor meta.tn declares, `extern => atom & { schema: schema_identity  type:
type_name }`, applied in the schema that admits it and read in the foreign schema's own closure, loaded with the
declaring schema rather than by the document. That is a later step and is not running.

## 7. [TSON-SCHEMA] §2.2.2, §2.2.3, §3.1, §3.3, §3.4, §6, §9 — one namespace per schema: the ladder's rules go

**Problem.** A schema document resolves in two stores: `!` targets, `kind`/`ref` `of` and annotations in the
structure namespace of its `!!meta` target (§3.3.1, §3.3.3), every other name in its type-name namespace (§3.3.2).
Four rules hold the two apart and keep each document on one rung: placement (§2.2.2, only a kernel-governed
document declares a constructor, a namespace type or a reference), direction of service (§3.3.1, a schema's `!`
targets come from its meta, never its own entries), the level of an import (§2.2.3) and a `!!meta` target's
eligibility (only a kernel-governed schema governs). They cost core.tn nine sibling declarations of the kernel's and
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
- **core.tn imports meta.tn** and no longer declares `void`, `boolean`, `integer`, `text`, `regex`, `iri`, `kind` or
  `doc` (the kernel's) or `set` (meta.tn's). Core's `iri` was IS-A its `iri_reference`; the kernel's is not, and core
  keeps `iri_reference`. The descriptive annotations core put on its copies (`@ordering`, `@bounded`, …) go with them.
- **A same-document read waits:** applying a constructor declared in the same document (`!C { … }` with `C` local),
  reading a value of a type declared there, and reading an annotation value against one each wait on that
  declaration being ready, in #4's wait order, whichever is written first. A type used by name waits on nothing.
- **The cost:** every name in the closure is reserved. A schema importing core reserves every name of meta.tn and
  the kernel — `identifier`, `field_name`, `record`, `map`, `namespace`, `bounded`, `exact`, `title`, `comment`,
  `examples` among them — until the kernel moves under one name of its own.

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
- §4.2 (core's `kind`, `void` and `set` siblings), §4.x's "Core declares no sibling of `identifier`", §1's worked
  example ("reached through the structure namespace"), §6, §9 and §13.2: core imports meta.tn; core's sibling rows
  go.

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
  `schema`, whatever the document imports, and is read as a spelling; a schema parser refuses an untagged root.
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
  own names. meta.tn imports the kernel, core.tn and net.tn import meta.tn, and policy.tn imports core.tn.
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
value. Every rule that tells types from values carries an exception for it — `type_kind` holds the namespace
constructors, `kind<namespace>` holds both namespace types and their values, `ref<T>`'s "`of` is no constructor"
reads `api` as one — and a schema cannot hold a value of an ordinary record type at all
(`create_order => !operation { … }` is refused as "not applicable"). The schema map's own `value_type: top` is false
once any entry holds a value. And with one namespace per schema (#7), a meta layer's vocabulary is ordinary types, so
"governed by a meta layer" no longer says which documents describe schemas.

**What is running.**

- **`namespace` is a constructor under `product`**, `namespace => product & { key_type?: identifier_kind  value_type:
  type_kind  ordered?: boolean ~ false }`, and a namespace type is an instance of it, as a map type is one of `map`:
  `schema => !namespace { key_type: type_name  value_type: declared }`, `field_map => !namespace { value_type:
  record_field  ordered: true }`, `api => !namespace { key_type: identifier  value_type: method }`. A value of one is
  the map its facets describe. `key_type` is optional: absent, the host of a value supplies the family, as a
  record's `fields` are keyed by its `name_type`. A stated `key_type` is a member of `identifier_kind`, judged at link.
  Its form is `map`'s: the same access and size facets, and a value of a namespace type is read as a map. It is
  `map`'s sibling under `product` rather than `map ^ { … }`, so `kind<map>` holds no namespace type, and one thing
  holds it there: the optional `key_type`. §5.7 is right to refuse a refinement that makes `map`'s required `key_type`
  optional, since a value IS-A `map` is one a map's reader can read, and that needs its key type. The absent
  `key_type` stands in for a value that depends on its host — `field_map`'s key family is its record's `name_type` —
  which the kernel has no spelling for yet.
- **An entry is a type exactly when its value is built by a constructor IS-A `type`.** `name => !T { … }` with `T` a
  constructor builds a type; with `T` an ordinary type — a record, a namespace type, any type of the closure — the
  entry is a value of `T`, read against `T` as a data value is, and marked by nothing but its tag. A value entry is a
  member of no kind (`type_kind` excludes it unmarked), nothing is typed by it, and `ref<T>` names it where its type
  IS-A `T`: `ref<api>` names `orders => !api { … }`. `kind<api>` is refused at the declaration, `api` being no
  constructor. A value entry's payload lifts and mints nothing; its names at kind- and reference-typed slots are
  judged at link, and a qualified name walks into it by key and by field. A template over an ordinary type closes to
  a value entry (`fetch => <T> !operations { … }`, `get_order => fetch<order>`). A type declared in the same
  document is applied in wait order (#4).
- **The schema map's values are `declared`.** The kernel now declares `declared_type` and `declared` (moved from
  meta.tn and core.tn), and `schema`'s `value_type` is `declared`: every entry's value names its type by its tag — a
  constructor for a type, an ordinary type for a value. `top` is the root of the constructors only. `declared` is
  now a kernel name, reserved in every closure that imports the kernel (#7), and the descriptive annotations core put
  on its copy (`@ordering`, `@bounded`) go with it.
- **A kind-typed position is judged by membership in every data document**, over the reading document's closure. A
  name in a schema document — a construction's or a value entry's payload, or a value of the kernel's `schema` read
  as data, a resolved form — resolves in that schema, which the reading closure does not hold: its grammar is read
  and its membership judged where that schema links, or at ingest. That replaces the stand-in that read grammar alone
  under the kernel and any schema importing it directly: a data document importing meta.tn that writes
  `!record_field { type: int32 }` is refused, `int32` being no member of `type_kind` in meta.tn's closure.
- **The kernel's wait cycle** (#4) gains `namespace`, `field_map` being its instance, and loses `field_name`,
  `field_map`'s keys being read by their spelling: 30 of the 79 entries, longest chain 4. The bootstrap builds
  `field_map` by hand with the rest of the pre-loaded set.
- **Hosts.** One bind context serves schemas and data; the names the kernel and meta.tn declare always bind to
  the schema model, and every other name binds through the consumer's binder. A value entry whose type no class binds is
  held as a tree, so a schema loads and validates without host classes; a schema obtained to bind refuses such an
  entry with `BIND_MISMATCH` at the entry, anywhere in the closure it compiles, since a bound reference can reach a
  value of any schema there. An instance of a constructor whose instances are no types (one composing `top` and not
  `type`, `s => !step { … }` over `step => top & { … }`) is held the same way, as the value it is. These are a
  processor's choices, not the spec's.

**Resolution, a proposal.** Sections amended:

- §4.1: the kinds are `atom`, `product` and `sum` under `type`, beside `reference` and `template` under `top`;
  `namespace` is a constructor under `product` whose instances are namespace types, and the paragraph making a
  namespace type the one constructor whose instances are not types goes. An entry is a type exactly when its value
  is built by a constructor IS-A `type`, and any other entry is a value, its tag naming its type. `schema`'s
  `value_type` is `declared`, which with `declared_type` is the kernel's.
- §4.2: `kind_type`'s exclusions are "not a constructor"; a namespace type is a member of `type_kind`, `kind<product>`
  and `kind<namespace>`; a value entry is a member of no kind; `of` names `type` or a constructor below it.
  `ref_type`'s `of` names any type, a namespace type included.
- §5.7: "Constructor refinement in a meta-schema is the meta-level exception and MAY replace fixed values (§4.2)"
  is ladder wording; with no meta-schema it is restated for a constructor refinement wherever it is written, or the
  exception goes. The omission order stands: it is what keeps `namespace` from being `map ^ { … }` today.
- §5.12: a qualified name's first segment names an entry holding a value; a further segment selects a key of a value
  of a namespace type or a field of a record value.
- §7.4, §8.1: a kind-typed position in a data document is judged by membership in the document's closure; in a
  schema document, and a resolved form read as one, its grammar is read and membership is the schema's link's or
  ingest's.

**Open.** `namespace => map ^ { key_type: identifier_kind … }`, with `key_type` required, once a field's value can
depend on its host (dependent fields): `field_map` then becomes a template applied to its record's `name_type`, and
`kind<map>` holds every namespace type. §5.7 is unchanged by it.
