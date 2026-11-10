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
  "expected a type reference, found the quoted token 'text'"), a template argument (`box<"text">`, "a quoted token is
  a literal, never a name (§5.10)"), and a constructor body's reference-typed slot (`!meta.array { element_type:
  "text" }`).
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
