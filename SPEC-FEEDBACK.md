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

**What is running.** None of the rule below as a rule. Pass 2 resolves on demand and refuses "a circular
composition/refinement chain" (rule 1's operand edge); the kernel bootstrap orders its entries by three hand-written
passes (ordinary declarations, instances, atom refinements); defaults and pins are `value`-typed and judged at link
(rule 3). A test harness measures the kernel's wait graph from `meta-kernel-resolved.tn`'s own declarations, no
compiled reader consulted: one cycle, holding `record` and 29 more of the 77 entries (`boolean`, `integer`, `text`,
`identifier`, `non_negative_integer`, the enum and set machinery, `field_group`, and the synthetics they name), every
other entry ordered after it, the longest chain 4. The kernel declares `annotation => void`: annotating itself with
`@annotation` would be a second cycle, and the marker changes no value (§6). The bootstrap's passes are not yet
replaced by this order. Compilation follows rule 3: a record's reader holds its pins and defaults as written while
it is built and decodes them through their types once every reader of the schema exists, before the schema is handed
out, so building `iri_type`'s reader reads nothing through `iri`'s (the `spec` pin). Link remains the one place a pin
is judged, and a bad pin or default still fails the load (rule 6).

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

- meta.tn declares `declared_type => sum & {}`, and core.tn `declared => !declared_type {}`. A `declared` position
  reads exactly as `!scoped { scope: [LOCAL] }` did: the value names its type by its tag, the name resolves in the
  governing schema's closure (§2.2.3), and the value is checked in full against it; an untagged value is a validation
  error in every mode, and a tag naming no type of the closure a resolver error. `declared` is SUM-kinded and has no
  discrimination class (§5.4), as the `scoped` instances were.
- `scoped`, `scope_kind`, `scope_set` and `schema_identity` are gone from meta.tn, and `extern`, `dynamic`,
  `extern_of` and `extern_type` from core.tn.
- [TSON-DATA]'s `scoped-value` production is gone: a record field value, a map entry value and an array element are
  each a `data-value`, so a `!!schema` there is a directive outside its placement and a **parse error** (§3.3's closed
  positional set). `!!schema` stands in the header alone.
- [TSON-DATA] §9.1's foreign-schemas row is gone, from the limits policy and from `policy.tn`.
- [TSON-JSON] (edited in place) admits `$schema` on the root's annotation object alone; anywhere else it is a resolver
  error, and §8.5 is "Declared Positions".

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
