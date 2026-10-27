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

**Nothing is open against Revision 38.** The entries Revision 38 adjudicated are gone, and the spec carries their
rules; the change log in `spec/` keeps them under their numbers.
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
