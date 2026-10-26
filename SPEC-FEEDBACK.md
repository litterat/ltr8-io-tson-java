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

**Revision 38 closed every entry open against Revision 37, and nothing is open against it.** Verifying the
adjudicated text against each closed entry before publication found eleven defects, and they were fixed in the text
in the same pass (the Revision 38 change log, §9), so the register opens empty. The closed entries are gone: the
spec now carries their rules — the network library `net.tn` with `hostname`, `host` and `media_type`; refused and
undetermined as two outcomes beside the four categories; an unknown schemaless annotation as a resolver error; an
identity's host as a `host` value; the `type` kind; `record.name_type` and `enum_type.name_type`; a record's fields
as a map; the qualified name and `ref<T>`; `type_definition` gone and `type_chain` in its place; the abstract
kernel entries; record templates as `record` with `parameters`, the three places a parameter stands, an application
nothing declares as its expansion, and the selector rules; `kind_type`; `namespace`; and the import level rule.
**This file is the as-built record**, not a pointer to one: where an entry proposes a design this implementation
has built, the entry states the design, what is running, and what is not, so that a reviewer editing the spec needs
nothing beside it. **Where the evidence is a consumer of this library rather than this library** — the HTTP layer
in `ltr8-io-tson-java-http` is one, and this register is the collection point for all of it — the entry says so and
states what is running there on the same terms.

**Part 3 is not in this register.** [TSON-JSON] is an early draft this implementation exists to validate, and
`spec/tson-part3-json.md` is edited **directly** as findings arise — so a Part 3 finding becomes a spec change in
the same session, with git history as its record, rather than an entry waiting for adjudication. **The numbers are
not reused and the gaps are left** — the register renumbers only when a revision closes, so that a citation written
against the open set stays valid until then. What stays here is Parts 1 and 2, whose current revision is published
and whose changes this implementation proposes rather than makes. An entry spanning both stays, and says which half
is which.

**Cite the spec, not the argument that got it there:** `design/` and the Javadoc name the section that requires a
behaviour, and a `SPEC-FEEDBACK.md #N` citation is for an entry below, where there is no section to point at yet.
When an entry closes, its citations become spec citations and the entry is deleted — nothing here is an archive.
