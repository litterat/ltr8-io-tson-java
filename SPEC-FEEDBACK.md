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

## 2. [TSON-SCHEMA] §5.7, §8.2 — a closed refinement source: "mints nothing" against "IS-A the source"

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

## 3. [TSON-SCHEMA] §5.10 — an alias of a schema-namespace template: which namespace its value parameters read in

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

## 4. [TSON-DATA] §2.4, [TSON-SCHEMA] §5.4 — at a disjoint choice the form selects the variant, with no emitter rule

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
