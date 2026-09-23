# Name hygiene and minted names

Design notes for §8.2's three name-hygiene rules as the linker applies them (`TsonSchemaLinker.checkNames`), and for how
a resolver-minted name is built so that the same walk can judge it (`InternalName`, `MintedNames`). Current form only;
history lives in git.

**Invariants**

- All three §8.2 rules run in one walk over scopes (`checkNames`), never at the positions where a name is read.
- What stays at the reading positions is §7.7's grammar (`IdentifierProfile.validate`); `IdentifierProfile.hygiene`
  returns a verdict rather than throwing.
- A template's parameters are a checked scope although §11.4 declines to list them; a choice's variants are not.
- A minted name is ASCII and an identifier by construction, and is never exempted from the walk.
- Non-ASCII or non-admitted content in a minted name is hashed rather than dropped; a part is capped at 64 characters,
  hash included.
- `MintedNames.claim` compares the canonical renderings, not the name's own 32-bit hash; it is per phase.
- A refusal carries a policy code (`CONFUSABLE_NAMES`/`RESTRICTED_CHARACTER`/`RESTRICTED_SCRIPT`), not `SCHEMA_ERROR`.
- The bootstrap links under the default policy, not a caller's.

Related: `design/linking-and-compilation.md`, `design/schema-resolution.md`, `design/schema-grammar-and-desugaring.md`,
`design/name-hygiene-read-path.md`, `design/conformance-suite.md`.

## The one walk over scopes (`TsonSchemaLinker.checkNames`)

**All three of §8.2's name-hygiene rules run in one walk, over scopes** (`checkNames`) — names that read
alike, a character outside the identifier profile, and a script combination the restriction level does not
admit. The scopes are §11.4's — the
merged namespace, which is where §2.2.3's own disjointness rule is exact equality and a confusable pair passes
it by construction; each entry's record field names, its groups' member labels arriving flattened among them;
and its enum members, where the enum declares itself a vocabulary — plus **a template's parameters, which
§11.4 declines to list** (`<T, Т>` otherwise
declares two parameters that render identically, and a body referencing `T` binds one of them with nothing in
the source to say which). §11.4 and §5.10 both say why they are left out — one author writes the list whole on
one line — and this stays stricter deliberately: mechanisms 2 and 3 reach every identifier position anyway
(§8.2), so only the look-alike rule is a divergence, and a walk with an exemption is the shape that grows the
holes one walk exists to close. A **choice's variants are deliberately not checked**: a variant
is a reference to a declared name, so a confusable pair is already two confusable namespace entries and a check
there could never fire.

**The restriction level is refused per name, in the same pass** (`UnicodePolicy`, UTS #39 §5.2). The two
are complementary rather than overlapping: the confusable check is a *relation* and needs the whole set, so
it can never fire on a lone name; the level is a *property* of one name, so it is what reaches a name nothing
else in the schema resembles. Configured by `ProcessorConfig.withIdentifierPolicy` and carried on
`TsonCompiledMetaRegistry`, which is the one object every resolve and every read passes through.
**Two axes, not a ladder** — a level and a unit — because per-segment Highly Restrictive and Moderately
Restrictive are incomparable. The default is Highly Restrictive over a whole name, which refuses
`id_пользователя`; the relaxation to reach for is the *unit*, since `perSegment()` admits that and still
refuses `аdmin`. The bootstrap links under the default rather than under a caller's policy: a configuration
should not be able to break meta-kernel.

## Minted names (`InternalName`, `MintedNames`)

**A minted name is ASCII and an identifier by construction, which is what lets the walk judge it like any
other** (`InternalName`). Both naming sites splice author-written content into the readable half —
`SchemaDesugarer` from a lifted binding record, `TemplateMaterialiser` from an application's head and value
arguments — so a derived name is a place where a document's own text reaches the schema namespace. Two
requirements meet there, and the second is why meeting the first is not enough:

- **[TSON-SCHEMA] §8.2's freshness MUST**, that an internal name is a valid `identifier`. Splicing raw text
  breaks it outright: a `text` field holding a path puts `/` in a name, and §7.7 admits only `XID_Continue` and
  `-`. An HTTP operation is the case that finds it — §4.1 names one as the motivating case for the `data`
  kind, and every realistic path carries a slash.
- **§8.2's hygiene must still be able to judge the result.** Admitting every `XID_Continue` character keeps
  the name legal and still lets author text shape it: a Cyrillic `о` in a value would sit in a namespace
  name, and a Latin head spliced with non-Latin content is mixed-script by construction, so the walk would
  refuse ordinary schemas — `operation_путь_GET_…_bef13f0c` is a valid identifier refused under §8.2's
  recommended default for containing a Russian word. Exempting minted names from the walk answers that and
  opens a worse hole: the namespace then takes on whatever a document happens to contain, unchecked.

So the rule is ASCII, in three cases. What is ASCII and admitted by §7.7 is spliced verbatim — the ordinary
case, a type name or a verb or a bound. What is ASCII but not admitted keeps its admitted characters and
gains a hash, so `"/x"` reads `x_h00000f2f` and `1.0` reads `1_0_h0000bdb3`. Anything else is the hash alone.
**Hashed rather than dropped**, because replacing it would collapse two different values onto one readable
half, where a hash keeps them visibly distinct and keeps the name inspectable — a reader holding the schema
can hash the same text and match it. Nothing identity depends on is at stake either way: that is the
structural hash at the end, computed over the binding and never over this text.

**A part is capped at 64 characters, hash included.** Nothing in the series bounds a name — §8.2 asks for
freshness, stability and a content-derived spelling, and §7.7's grammar is unbounded — but a part is spliced
from author-written content and `DerivedName.ofBinding` walks a whole binding record, nested records and arrays
included, so an unbounded rule makes name length a function of document size: a realistic REST path already
mints 139 characters. Past the budget the readable half has stopped being readable and is only cost, at every
reference to the entry and in §8 output. Truncation appends the hash rather than simply cutting, so two long
texts sharing a prefix stay apart.

**Non-collision is decided, not assumed** (`MintedNames`). §8.2's freshness MUST asks that an internal name
collide with no declared entry and no other internal one. The first half is caught where a name is inserted —
`SchemaDesugarer` inserts into the document's declarations with `putIfAbsent` and raises `IllegalStateException`. The second
half is the subtle one: **deduping by name is the identity discipline working**, since two occurrences of one
form must land on one entry — it is what makes `[text]` written twice one type, what lets a form written out
and the same form arriving through a template agree, and what ties a recursive template's knot — and it is
therefore also what would hide a collision, a second arrival under a name being indistinguishable from two
different bindings that derived one.

So the derivations are compared. Both sites already render a binding canonically before hashing it, and that
rendering is injective — two are equal exactly when the bindings are — so `MintedNames.claim` states the MUST
exactly rather than trusting the name's own 32-bit hash, which is a rendering and is not load-bearing on
its own. **Per phase**: desugaring and materialisation hold one each and run either side of resolution, so a
name minted in one phase colliding with a different form in the other is not caught; the two share their
naming functions, so such a pair would have to have collided within a phase as well to exist.

## Why one walk, and what a refusal carries

**One walk rather than one check per naming position, and that is the load-bearing part.** Running the
restricted-character rule (`Identifier_Status`) where a name is *read* — the schema parser,
`DefinitionResolver`, the atom vocabulary — spreads it over three call sites, and leaves holes at exactly the
positions only some of those reach: an enum member and a group's member labels get checked for reading alike
and for script mixing, and never for a restricted character, invisibly.

**`enum.profile` is the one scope whose per-name rules are conditional, and the condition is declared.** Under
`IDENTIFIER` an enum's members are names and all three mechanisms reach them. Under `TEXT` they are values: the
restricted-character and restricted-script rules are per-*name* and lapse — a value set carries whatever its
domain carries, and nothing is looked up by name there — while the look-alike relation stays, because the set
is still what a value is matched against and two members that render identically is the same hazard either way.
`checkScope`'s `perNameRules` flag is that split, and it is the enum body's declaration that sets it, never the
shape of the members: inferring "these look like names, so police them" would switch a spoofing check on and
off by accident. A scope list
can be reviewed; three call sites cannot. What stays at the reading positions is §7.7's grammar
(`IdentifierProfile.validate`), which is validity, is stable across Unicode versions, and really is a parse
error; `IdentifierProfile.hygiene` returns the restricted-character rule's verdict rather than throwing,
because a refusal is not
one.

**A refusal carries a policy code, not `SCHEMA_ERROR`** (`TsonDiagnostics.ofSchemaRefusal`): `CONFUSABLE_NAMES`
for names that read alike, `RESTRICTED_CHARACTER` for a character outside the identifier profile and
`RESTRICTED_SCRIPT` for a script the restriction level does not admit — one per rule, and the same codes
a *read* reports for the same rules, so one schema and one document that break the same rule come back
alike. §8.2 requires a refusal be distinguishable from a
validity error, and a consumer that has to read prose to tell them apart is what the code exists to prevent.
It is still a verdict: the schema must change, or the deployment must relax the policy in code.

The one scope the linker cannot reach is a Class 1 record, which has no declaration; `SchemalessTreeReader`
checks its own field set, and `DefaultTsonReadContext` applies the restricted-character and
restricted-script rules to a type-ref or annotation name. Being a *relation*, the look-alike rule never
rejects a lone name — a mixed-script `id_пользователя` collides
with nothing and passes, which is the property that keeps it switched on and the reason [TSON-DATA] §8.2
defaults it on where the restricted-script rule's level is the one to relax.
