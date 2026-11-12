# Name hygiene

Design notes for §8.2's three name-hygiene rules as the linker applies them (`TsonSchemaLinker.checkNames`). Every name
the walk judges is one an author wrote: the resolver mints none ([TSON-SCHEMA] §8.2). Current form only; history lives in
git.

**Invariants**

- All three §8.2 rules run in one walk over scopes (`checkNames`), never at the positions where a name is read.
- What stays at the reading positions is §7.7's grammar (`IdentifierGrammar.validate`); `IdentifierGrammar.hygiene`
  returns a verdict rather than throwing.
- A template's parameters are a checked scope although §11.4 declines to list them; a choice's variants are not.
- A record written in place is a scope of its own, its field names judged under its own name type, at any depth
  (`FieldNames.inPlace`), and reported against the declaration holding it; the record holding it is another scope. A
  record an application closes to is skipped with all it holds: its names are its open record's, judged there once.
- Each schema map is a scope of its own, the root and each nested `!schema` map, grouped by `SchemaPaths.parent`; the
  per-name rules judge a member's own name, its path's last segment, never the dotted path.
- A value entry's payload and an annotation value are judged at the payload read (`SchemaResolver`, under the
  loader's identifier policy), not in the walk, which cannot see into them; every constructor payload is read under
  no policy.
- A refusal carries a policy code (`CONFUSABLE_NAMES`/`RESTRICTED_CHARACTER`/`RESTRICTED_SCRIPT`), not `SCHEMA_ERROR`.
- The bootstrap links under the default policy, not a caller's.

Related: `design/linking-and-compilation.md`, `design/schema-resolution.md`, `design/schema-grammar-and-desugaring.md`,
`design/name-hygiene-read-path.md`, `design/conformance-suite.md`.

## The one walk over scopes (`TsonSchemaLinker.checkNames`)

**All three of §8.2's name-hygiene rules run in one walk, over scopes** (`checkNames`) — names that read
alike, a character outside the identifier profile, and a script combination the restriction level does not
admit. The scopes are §11.4's — the
merged namespace, which is where §2.2.3's own disjointness rule is exact equality and a confusable pair passes
it by construction, split into one scope per schema map (the root's entries, and each nested `!schema` map's members
by their own names, a refusal naming the map); each entry's record field names, its groups' member labels arriving flattened
among them; and its enum members, where the enum declares itself a vocabulary — plus **a template's parameters, which §11.4
declines to list** (`<T, Т>` otherwise declares two parameters that render identically, and a body referencing `T` binds one
of them with nothing in the source to say which). §11.4 and §5.10 both say why they are left out — one author writes the list
whole on one line — and this stays stricter deliberately: mechanisms 2 and 3 reach every identifier position anyway (§8.2),
so only the look-alike rule is a divergence, and a walk with an exemption is the shape that grows the holes one walk exists
to close. A **choice's variants are deliberately not checked**: a variant is a reference to a declared name, so a confusable
pair is already two confusable namespace entries and a check there could never fire.

**A field's default or fixed value is a name when its type is an identifier family** (`checkFieldValues`), and
meets the two per-name rules in the same pass, under that family's own profile. It is not a scope — one value
stands alone — but it is the schema's own copy of a value a read judges: a default reaches every document that
omits the field, so a value the reader would refuse if written must not be one it injects
(`design/name-hygiene-read-path.md`). An open record's literal values are judged the same way, once, at the
template, applied or not: they are every application's, and are judged once, against the declaration that wrote them.

**A value entry and an annotation value are judged where they are read, not in the walk.** A value entry's payload (`!api { …
}`, a map keyed by names) binds to the consumer's own classes or is held as a tree
(`design/namespace-types-and-value-entries.md`), and an annotation value binds to whatever its type binds, so by link time
the field types that make a value a name are behind them — a schema's `replies: {status_name => …}` arrives as a `Map<String,
…>`. `SchemaResolver` reads those two payloads under the registry's policy instead, and the readers apply §8.2 exactly as
they do in a data document: a namespace value's keys are one scope, so are the keys of each identifier-keyed map in it, and
an identifier-typed value meets the per-name rules. A constructor's payload builds the schema layer's own vocabulary and is
read under `IdentifierPolicy.none()`: its names are the walk's scopes, and its identifier-typed values outside them are
references — a choice's variants, a supertype — whose verdict belongs to the declaration they name, possibly in another
schema. Judging a reference too would report one name twice, against the wrong declaration, and stop the schema reaching the
linker.

**The restriction level is refused per name, in the same pass** (`IdentifierPolicy.judge`, UTS #39 §5.2). The two
are complementary rather than overlapping: the confusable check is a *relation* and needs the whole set, so
it can never fire on a lone name; the level is a *property* of one name, so it is what reaches a name nothing
else in the schema resembles. Configured by `ProcessorConfig.withIdentifierPolicy` and carried on
`TsonCompiledVocabularyRegistry`, which is the one object every resolve and every read passes through. The same policy
switches the confusable check (`appliesSkeletonDistinctness()`), which each scope asks before comparing.
**Two axes, not a ladder** — a level and a unit — because per-segment Highly Restrictive and Moderately
Restrictive are incomparable. The default is Highly Restrictive over a whole name, which refuses
`id_пользователя`; the relaxation to reach for is the *unit*, since `perSegment()` admits that and still
refuses `аdmin`. The bootstrap links under the default rather than under a caller's policy: a configuration
should not be able to break the kernel.

## Why one walk, and what a refusal carries

**One walk rather than one check per naming position, and that is the load-bearing part.** Running the
restricted-character rule (`Identifier_Status`) where a name is *read* — the schema parser,
`DefinitionResolver`, the atom vocabulary — spreads it over three call sites, and leaves holes at exactly the
positions only some of those reach: an enum member and a group's member labels get checked for reading alike
and for script mixing, and never for a restricted character, invisibly.

**An enum's members and a record's field names are judged under their name type's profile.** Both `name_type`s are
identifier families (`enum`'s is the kernel's `meta.identifier`; `!meta.enum_type { name_type: words … }` or a record's
`name_type: json_name` names the author's own), so the names are names and all three mechanisms reach them, with the
profile's added characters — a `start_add`, a `continue_add`, a `medial` — counted as its own rather than as
restricted characters (`NameType.profile`, passed to `checkScope`). That exemption has a floor
(`IdentifierProfile.incoherence`): an addition may not be a Default_Ignorable_Code_Point, a control, or a space other
than U+0020, or a profile could carry an invisible character past every rule. A closed set of text that is no name is
no enum: it is `unicode.text_type`'s `members` facet, a value set no name rule reaches. A scope list can be reviewed; three
call sites cannot. What stays at the reading positions is §7.7's grammar (`IdentifierGrammar.validate`), which is
validity, is stable across Unicode versions, and really is a parse error; `IdentifierGrammar.hygiene` returns the
restricted-character rule's verdict rather than throwing, because a refusal is not one.

**A refusal carries a policy code, not `SCHEMA_ERROR`** (`TsonDiagnostics.ofSchemaRefusal`): `CONFUSABLE_NAMES`
for names that read alike, `RESTRICTED_CHARACTER` for a character outside the identifier profile and
`RESTRICTED_SCRIPT` for a script the restriction level does not admit — one per rule, and the same codes
a *read* reports for the same rules, so one schema and one document that break the same rule come back
alike. §8.2 requires a refusal be distinguishable from a
validity error, and a consumer that has to read prose to tell them apart is what the code exists to prevent.
It is still a verdict: the schema must change, or the deployment must relax the policy in code. A refusal at a
payload read travels as `SchemaRefusalException`, the one subtype of `SchemaValidationException`, so every collect
site already takes it and `SchemaResolver.Problems` reports it under its code; the message names the pointer
within the payload, which says which key of a map was refused. A fail-fast caller gets the exception itself,
from the resolver and the linker alike.

The one scope the linker cannot reach is a Class 1 record, which has no declaration; `SchemalessTreeReader`
checks its own field set, and `DefaultTsonReadContext` applies the restricted-character and
restricted-script rules to a type-ref or annotation name. Being a *relation*, the look-alike rule never
rejects a lone name — a mixed-script `id_пользователя` collides
with nothing and passes, which is the property that keeps it switched on and the reason [TSON-DATA] §8.2
defaults it on where the restricted-script rule's level is the one to relax.
