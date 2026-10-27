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

## 1. [TSON-JSON] §4.3 — annotations: encode error in the text, silent strip in the schemaless writer

**Problem.** §4.3 makes encoding a value that carries annotations an **encode error**, with stripping admitted only
"as an explicit code-level configuration". The schemaless object writer (`Json.objectWriter()`) strips
unconditionally: a bound class's `Annotations` carrier and an annotated box's annotations are dropped and the value
is written. The schema-directed path has no writer yet, so nothing in this library raises the error §4.3 requires.

**What is running.** Unconditional stripping, with no configuration surface. The schemaless reader binds every
carrier to `Annotations.empty()`, so a document the writer produces reads back to what was written minus the
annotations; nothing is misread, only lost.

**Why the text says error.** Principle 5 (a categorized error, never a silent drop) is stated against a *typed*
encoder, where an annotation at a typed position may be the information the tag carries (§3.3). At a schemaless
position no reader can give an annotation meaning, so a refusal there protects nothing a reader could recover.

**Resolution to decide, author's call.** Either (a) the writer refuses an annotated value with a `WriteException`
naming the position unless a `strippingAnnotations()` configuration is set, matching the text; or (b) §4.3 says the
rule binds the schema-directed encoder, and a schemaless encoder, having no annotation channel on either side, MAY
drop annotations without configuration, stating so in its contract. (b) is the smaller change and matches what the
schemaless reader already does with the carrier; (a) keeps one rule for both encoders. `BACKLOG.md` carries the
code half under "JSON encoding".

## 2. [TSON-JSON] §8.2, §8.3 — class stability carries revision history, and the linker disagrees with §5.4

**Problem.** §8.2 says "since Revision 36 a class is given only to types whose every value lands in it", and §8.3
opens "informative from Revision 36" and recounts "the draft against Revision 35". The document is a draft with no
published revision, so the phrases date nothing a reader can consult and should go: §8.3 states the fact (every
classed type is class-stable here, because [TSON-SCHEMA] §5.4 gives no class to the two straddling kinds) and
nothing about when it became so.

**What is running, and the disagreement.** [TSON-SCHEMA] §5.4 denies a class to an approximate atom whose
`allow_nan` or `allow_infinity` is true and to a map whose key type forces the pairs form, so `disjoint` is false
for a choice listing one. The linker's derivation does not do that: `DiscriminationClass.of` classes every
`FloatType` `NUMBER` and every `MapBody` `BRACE`, so `( float64 | text )` links `disjoint: true` with core's
`float64` as declared. The JSON reader compensates with its own `DiscriminationClass.stable` check, refusing the
untagged route for exactly those two kinds, which is the second condition §8.3 says the text no longer needs. The
observable difference is on the TSON text side: `( float64 | text )` dispatches untagged there, where §5.4 says it
must carry a tag. That is a Part 2 implementation gap, carried in `BACKLOG.md` under "Resolution & linking
generality"; once the linker follows §5.4, `stable` is redundant and goes, and §8.3 is right as written.

**Resolution.** For Part 3, remove the revision phrasing from §8.2 and §8.3 and state §8.3's rule in the present
tense; no rule changes. This entry closes with that edit and the linker fix.

## 3. [TSON-SCHEMA] §5.7, §8.2 — a closed refinement source: "mints nothing" against "IS-A the source"

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

## 4. [TSON-SCHEMA] §5.10 — an alias of a schema-namespace template: which namespace its value parameters read in

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
