# Spec feedback

Issues, ambiguities and inconsistencies found in the TSON spec while building this implementation. See `CLAUDE.md`
for why this file exists and when to add to it. Spec quotes are from 2026 Revision 40, Part 1 and Part 2, the
working copies in `spec/`, unless noted otherwise.

Format per entry: spec section, the problem, the interpretation this implementation chose, and a suggested
resolution where there is one.

**This register holds what is open against the current revision, and it renumbers from #1 each time a revision
closes.** It is an input to the next revision's adjudication, so its numbering is the numbering that revision's
change log will answer against: a stable index of the open set, not an archive of everything ever raised.

**Revision 40 closed every entry open against Revision 39, and nothing is open against it.** The closed entries are
gone. The register as it stood is published with Revision 40 as its close-out record, and the change log in `spec/`
keeps each entry under its number: its §2 gives each verdict, its §3 each section's rule before and after, and its
§5 the readings the spec author ruled on. What the implementation still has to build for them is in `BACKLOG.md`.

**This file is the as-built record**, not a pointer to one: where an entry proposes a design this implementation
has built, the entry states the design, what is running and what is not, so that a reviewer editing the spec needs
nothing beside it. Where the evidence is a consumer of this library and not this library, the entry says so and
states what is running there on the same terms.

**Part 3 is edited, not registered.** [TSON-JSON] is drafted alongside the implementation, and
`spec/tson-part3-json.md` is edited directly as findings arise, with git history as its record. An entry spanning
Part 2 and Part 3 stays here and says which half is which.

**Numbers are not reused and gaps are left** while a revision is open, so a citation written against the open set
stays valid until the register renumbers.

**Cite the spec, not the argument that got it there:** `design/` and the Javadoc name the section that requires a
behaviour, and a `SPEC-FEEDBACK.md #N` citation is for an entry below, where there is no section to point at yet.
When an entry closes, its citations become spec citations and the entry is deleted.

---
