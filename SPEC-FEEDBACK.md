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

**Revision 39 closed every entry open against Revision 38, and nothing is open against it.** The closed entries are
gone, and the change log in `spec/` keeps them under their numbers: the spec now carries their rules — one namespace
per schema, one `!!import` directive and the root's `!schema` tag saying the kind; resolution order within a schema;
the roots holding the common vocabulary and every `!schema` value declaring from the root; `!` marking an instance
and `^` refining any value; value entries as ordinary values and `namespace` building map types; the alias body; one
reference atom, a reference in data as grammar and an application as a reference value; a count as a 64-bit range;
a facet typed by its family's instance; a restated field narrowing by IS-A; a record template as its own
constructor; an applied parent's edges; dependent fields; the `type-expr` and `type-ref` productions; a float's
ordered bounds; and the two identities. What the implementation still has to build for them is in `BACKLOG.md`.
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
