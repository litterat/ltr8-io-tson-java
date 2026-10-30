# Inhabitance and record extension

Design notes for two structural checks the linker runs over the merged closure after validation: the inhabitance
check (`TypeInhabitance`, §5.10.1's productivity rule) and what `record.extension` obliges (`RecordExtension`, §5.2,
§5.7, §5.9). Both are checkers that hand back verdicts and leave reporting to `TsonSchemaLinker`, the shape
`ChoiceDisjointness` keeps. Current form only; history lives in git.

**Invariants**

- Inhabitance is a least fixed point over the entry graph: exact, total and two-valued, with no third answer.
- Every local entry is judged, referenced or not; imported entries are skipped, having been judged when their own
  schema linked.
- An open entry (a held template or a record template) is inhabited by fiat; the entries materialisation mints from
  it are judged like any other, so a template nobody applies is judged nowhere.
- An unresolved reference is inhabited by fiat, and the check runs after `validateEntry`, so one defect is reported
  once.
- A kind is inhabited exactly when it has a member; a value entry and every atom are inhabited here. An atom whose
  facets admit nothing is its family's coherence question, not this one.
- `RecordExtension`'s FINAL check reads the entry's chain (`TypeChain.supertypesOf`), a template's included, which a
  §5.9 subtraction leaves empty.
- A family's selectors are the base's own `discriminators` statement, read through `FamilySelectors`, the derivation
  the readers dispatch on; a record template is a family base and judged as one.
- Every family in the closure is judged; a pin collision only the merge produced is reported against the schema
  (`""`), naming both origins.
- A family member is declared: a record a use-site application minted that composes onto another is refused.
- Pins compare as values (`ValueIdentity`), read at the base's declared type.

Related: `design/linking-and-compilation.md` (the link order), `design/schema-resolution.md` (marks and template
families), `design/template-materialisation.md` (`refuseMintedMember`), `design/record-dispatch.md`,
`design/choice-disjointness.md`.

## The inhabitance check (`TypeInhabitance`)

**An entry no finite document can satisfy is rejected.** `x => { y: y }` with `y => { x: x }` resolves and links
cleanly otherwise, and fails at the first document as `missing required field 'x'`: blaming the data for a defect in
the schema, at a line the data's author does not control. The diagnostic spells the chain that has to be broken
(`cycleThrough`), each minted entry named as the form or application that produced it.

- **A least fixed point, not a search.** Every entry starts unknown; a round marks each one whose body is satisfied by
  what is already marked; rounds repeat until nothing changes, at most one per entry. Decidable because the graph is
  finite: the question is not "does this type have a value" but "does this recursion reach a base case". Unlike
  disjointness (`design/choice-disjointness.md`), it needs no trade of exactness to stay total.
- **The base cases, and nothing else**: a field that admits `_` or may be omitted with no value of its own, an optional
  tuple position, a container whose `min_items` is zero or absent (an array also where it is voidable), and a choice
  variant that does not recur. `void`'s only value is `_`, so a `void` field that does not admit `_` is stated by no
  document: `a: void` empties its record, where `a?: void` empties only the field. A choice is the one place the walk
  **branches** rather than conjoins: one good variant is enough, where a product needs every part.
  - **Field groups are walked separately**, because §5.11 makes their members uniformly optional in `fields` with the
    requirement carried by the group's own state; reading the field list alone would call every group satisfied. A
    required group needs one choosable option: every member it does not mark `?` stated, and at least one member.
  - **Every other field counts, one carrying a value included**: a fixed or default value of a type nothing can
    satisfy does not exist either.
- **Every local entry is judged, referenced or not**, on the footing of a declared type parameter the body never uses
  (§5.10). So an uninhabited *variant* is rejected even where the choice around it still works. Repeating the verdict
  for imported entries would report one defect once per importer.
- **An open entry is not walked.** Its parameters mean nothing until an application supplies them, so it is inhabited
  here, and the closure materialisation mints is an ordinary entry in the map by the time linking runs, judged like
  any other. §8's own `tree` fixture is caught the moment anything applies it, and a template nobody applies is judged
  nowhere, the same answer §5.10's deferred checking gives everywhere else. `TypeInhabitanceTest` pins both halves.
- **An unresolved reference is inhabited by fiat.** `validateEntry` has already reported it against this entry;
  calling it uninhabited too would report one defect twice, the second time naming a different problem.
- **Scope is structural.** An atom whose facets admit nothing (`int8 ^ { min: 300 }`) is uninhabited too, but that is
  its family's coherence rule (`Atom.coherenceCheck`, over `AtomCoherence`), asked by the resolver of each written
  body and by the linker's `checkCoherent` of every entry (`design/atom-refinement-and-coherence.md`). A value entry
  (`ValueEntry`) is inhabited because nothing may be typed by it, which is what makes that refusal load-bearing
  (`design/meta-layer-namespaces.md`).

## What `record.extension` obliges (`RecordExtension`)

The fact is written by the author's mark and lowered by the resolver (`design/schema-resolution.md`); what is left is
whether the rest of the closure agrees with it: nothing composes onto a FINAL record, a sealed family's selectors are
usable and its members pin them distinctly, and every member is declared.

- **Why the linker and not the resolver.** Every rule needs a namespace the declaration does not have. FINAL
  constrains whoever composes onto it, which may be another schema; the family rules range over `subtypes`, which
  nothing populates until linking; and a selector's type has to be followed to the end of its alias chain. What is
  local (two definition marks on one declaration, a mark carrying a value, a mark on a non-record) the resolver
  already refused while lowering. It runs on the merged map, not the annotated one: the marks are consumed into the
  body, so no annotation is left to consult.
- **The FINAL check reads the entry's chain**, a template's as a closed type's: a template composing or refining a
  final record is refused at its own declaration, whether or not anything applies it, since every application IS-A
  its parents too. §5.9's subtraction leaves the chain empty, minting no IS-A edge, which is the only thing FINAL
  constrains, so subtracting from a final type is admitted. No *invalid* case would show a regression there; the
  corpus carries a `valid` vector for that reason.
- **The selectors are the base's statement.** A family dispatches on the fields its ABSTRACT base names in
  `discriminators` (§5.2), resolved to the base's own fields by `FamilySelectors`, the derivation both encodings'
  readers dispatch on, so the check and the read cannot disagree about which fields select a member. A member states
  no discriminators of its own, so an inherited selector needs no exemption. A record template is a family base and
  is judged as one, its body assembled from what it states as a base: skipping it would accept a marked template with
  no family check at all.
- **Every family in the closure is judged**, not only one with a part declared here. §3.3.4 makes `subtypes` open
  across schemas, so an importer can add a member that collides with an imported one, and two imports that each link
  cleanly can each add a member that collides with the other's. The second is the merge's own fault and is reported
  against the schema (`""`), naming both origins. A collision inside one import cannot reach here: that import was
  refused when it linked.
- **Extending a family is a declaration.** A record a use-site template application minted that composes onto another
  (`k: dog_of<text>`, or `dog_of<T>` in a template body) is refused, reported at the declaration that wrote it
  (`reportedAgainst`). An expansion is refused where it is minted (`TemplateMaterialiser.refuseMintedMember`), with
  the application in hand; what reaches here is the instantiation entry a self-reaching expansion keeps. A member is
  what a read reports, a tag names and a binding maps, all by name, and a minted name is unwritable (§8.2). It also
  keeps §8.2's content-derived duplicates harmless: two schemas minting one form hold two entries no value can tell
  apart, and with members declared no duplicate is a family member. An undeclared member is skipped by the collision
  check, so one mistake gets one verdict; so is a record template IS-A the base, whose applications are the members.
- **Pins compare as values through `ValueIdentity`**, which is why that class is visible outside its package. §4.3
  makes `= 255` and `= 0xFF` one pin and §5.5 makes `= 1` and `= 1.0` one, so a comparison of tokens would accept a
  schema whose dispatch table is not a function. They are read **at the base's declared type**, an enum selector in
  its name type's form (`enumForms`), which is [TSON-JSON] §6.1.5's dispatch rule: those are the types known before a
  subtype is selected, so distinctness is judged in the terms a decoder compares in.
- **Group membership is reported instead of the state rule, not beside it.** §5.11 forces a group member optional,
  so a discriminator in a group would fire both, and the second would name the symptom.
