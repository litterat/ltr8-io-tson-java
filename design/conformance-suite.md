# Conformance suite

How the two conformance runners consume the shared corpus, what each layer asserts, and where §8.2's name-hygiene
rules are walked. Current form only; history lives in git.

**Invariants**

- A subject reaches the lexer as the bytes on disk; §8.1's category is asserted at every layer; position never is.
- `refused` is not a verdict: something was refused *and* nothing was reported invalid.
- The grammar runs where a name is read; the hygiene policy runs once per layer, over scopes — no reading position
  applies a policy.
- A skip is not a pass: CI sets `TSON_REQUIRE_TEST_SUITE`, and the pin is a commit, never a branch.
- At the schema and link layers the category is the phase's; each diagnostic must be a verdict (`Code.verdict()`).
- Add vectors in the same session as lexer/parser/resolver work; the corpus moves before `SUITE_PIN`.

Related: `design/name-hygiene-and-minted-names.md`, `design/name-hygiene-read-path.md`, `design/json-unicode-policies.md`.

Separate from the fine-grained unit tests, these run every vector in the sibling
[ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite) repo as JUnit 5 dynamic
tests — a conformance/integration check against an external, language-agnostic, spec-derived fixture set,
to catch drift against the spec.

**Two runners, split by conformance class and therefore by module.** `ConformanceSuiteTest`
(`tson-compiler`) runs `class1/` against the real `Lexer`/`TsonDataParser`/`BaseTypeResolver`/
`BuiltinTypeVocabulary`. `Class2ConformanceSuiteTest` (`tson`) runs `class2/` against the `Tson` front
door, because a Class 2 vector is about a phase boundary — did this schema resolve, did it link, does this
document validate against it — and those boundaries are `Tson.validateSchema`/`Tson.validate`'s to own,
not a test's to reassemble. What the two share lives in `tson-compiler/src/testShared`, added to both test
source sets: `SuiteCheckout` (finding the corpus), `Sidecar` (reading a sidecar and splicing a subject's
header) and `Vectors` (walking the tree). One statement of the corpus's contract rather than two, which is
what `RUNNER.md` exists to keep from drifting — the first two runners written against its prose had already
disagreed about what a subject even is.

**The corpus states its own contract, and this runner obeys it rather than inferring it.**
`schemas/<layer>-sidecar.tn` gives each layer's sidecar shape and every sidecar names one with
`!!schema`; `RUNNER.md` is normative for the runner. Three rules bind here: a subject reaches the lexer
as the **bytes on disk**, never a decoded and re-encoded string; an `error` vector's §8.1 **category is
asserted at every layer**, not only the vocabulary one (the layers are pipeline stages and cross the
categories — the vocabulary layer raises `resolver` and `validation` errors and never a "vocabulary"
one); and **position is never asserted**, implementations legitimately failing at different points
depending on lookahead. A sidecar carries its outcome as a **field group member** (§5.11), so exactly
one of `valid`/`error`/`schema-document`/`refused` is present and the payload cannot be separated from it;
`absent`, `empty-brace` and `schema-document` carry nothing and are typed `void`, written `_`.

**`refused` is §8.1's fifth outcome and is not a verdict on the document or the schema.** §8.2's name-hygiene
rules refuse without making a document invalid — each reads data the UCD does not freeze, so none of
them may decide validity — and §8.2 says the refusal MUST NOT be reported in any of the four categories.
`checkRefusedVector` therefore asserts both halves: that something was refused, and that *nothing* was
reported as invalid, `CONFUSABLE_NAMES`/`RESTRICTED_CHARACTER`/`RESTRICTED_SCRIPT` being the three codes that mean
policy, one per rule. A vector
names the rule it exercises and the UTS #39 data version it was computed against, and a version this
implementation does not carry is `RUNNER.md` rule 5's fourth legitimate skip — the only one that is about
the vector rather than the conformance class. It has two homes: `class1/reader/refused/` for Part 1's one
scope, and `class2/schema/refused/` for §11.4's, where the enum-member and group-member-label vectors are
the ones that catch a processor checking each name where it is *read* rather than where a scope is
*walked* — the failure this implementation had. Template parameters stay out of the corpus: §11.4 and §5.10
both decline to list them as a scope, so a vector asserting the look-alike refusal would fail a conforming
implementation, and `ConfusableNameScopesTest` carries those cases instead. Only that rule diverges —
mechanisms 2 and 3 are per-name and reach every identifier position anyway (§8.2).

**The grammar runs where a name is read; the policy runs once per layer, over scopes.** That split is
§8.2's own — §7.7 is validity, stable across Unicode versions, and a failure is a parse error; §8.2's three
name-hygiene rules are policy over *named scopes*, read unstable data, and a failure is a refusal. So
`IdentifierProfile.validate` is the grammar and `IdentifierProfile.hygiene` the restricted-character rule;
**both report a violation and neither throws**, so what a failure becomes is the caller's — a parse error
where the grammar is read, a refusal where the policy is applied — and **no position that reads a name
applies a policy**. The joiners belong to the grammar despite
being `Identifier_Status=Restricted` — §7.7 rule 2 makes their admission a question of form.

Each layer has exactly one place that walks its scopes, and all three rules run there — names that read
alike (`CONFUSABLE_NAMES`), a character outside the identifier profile (`RESTRICTED_CHARACTER`), a script
script the restriction level does not admit (`RESTRICTED_SCRIPT`, wider than a mix — at `ASCII_ONLY` a
single-script name is refused with nothing mixed):

| Layer | Walk | Scopes |
|---|---|---|
| Schema | `TsonSchemaLinker.checkNames` | §11.4's four, plus a template's parameters (§11.4 declines the scope) |
| Data (TSON) | `DefaultTsonReadContext` + `SchemalessTreeReader` | a type-ref/annotation name; one record's field names |
| Data (JSON), schemaless | `reader.DataClassObjectReader.checkNameHygiene` | one record's member names — the two per-name rules only |
| Data (JSON), schema-directed | `reader.NameHygiene`, from the record and choice readers | an **unmatched** member name; a `$type` naming nothing — the two per-name rules only |

**The schema-directed reach is narrower than the schemaless one, and deliberately so** ([TSON-JSON] §9.4): a
member name matching a declared field, or a `$type` naming a declared type, carries that declaration's own
verdict, given when the schema loaded — so only an **unmatched** name is judged. The schemaless bind reader
checks every name instead, and is right to: there the class is the schema and nothing judged its component
names at load. **The order is load-bearing**: §8.2 before §6.1.1, because a refusal MUST NOT be reported in
one of §8.1's four categories, and a look-alike field name told it is *unknown* is a verdict on the document
for a policy rule — advice to add a field that is already declared, when the fix is one character.

**JSON reaches fewer scopes, and the reason is §4.1 rather than an omission.** `{"a": 1}` is one syntax
for a record and a map, so the position decides which — and only a reader holding one can say. The object
reader's target class *is* that position (it plays the schema's part, §4.1), so a record component's
members are names and a `Map` component's are keys, which are data and the token policy's business. The
tree reader holds no position and so applies nothing; that is the same fact that made `tson-json` a
separate stack rather than a front end over `TsonEventSource`. **The look-alike rule reaches no JSON
position, and that is settled rather than owed**: it is a property of a *set*, and the one place any
encoding applies it to data is TSON's schemaless tree read, where the grammar has already said the members
are fields. In JSON the attack and a legitimate map of look-alike keys are spelled identically, so it must
be accepted — a deployment that will not accept it raises the **token** policy, which reaches every token
including a key. `design/json-unicode-policies.md` has the worked comparison.

**A minted name is judged by the same walk, and is built so it can be.** A derived name splices
author-written content into its readable half, so `InternalName` restricts that half to **ASCII**: what §7.7
admits is spliced, other ASCII keeps its admitted characters and gains a hash (`"/x"` → `x_h00000f2f`), and
anything else is the hash alone. That satisfies §8.2's freshness MUST — an internal name is a valid
`identifier` — and, because an ASCII name is single-script and inside the identifier profile, it also passes
all three hygiene rules at every level. Admitting `XID_Continue` instead would keep the name legal while
letting a document's own text shape a namespace name, and would refuse any schema written outside Latin
script; exempting minted names from the walk would answer that by leaving the hole open.
`design/name-hygiene-and-minted-names.md` has the detail.

**One place is the point, not a tidiness.** Run at the reading positions instead — spread over the schema
parser, the definition resolver and the atom vocabulary — the restricted-character rule would have holes at
exactly the positions only some of them reach, such as an enum member or a group's member labels. A scope
list can be reviewed; three call sites cannot. **A field name is a name and meets all three rules** (§2.5, §7.7) — the two
per-name ones in `DefaultTsonReadContext` beside a type-ref's and an annotation's, the look-alike one in
`SchemalessTreeReader`, which is where it belongs because it is a property of a *set*. There is no
conformance class in which a record's field names are judged by a different rule. The identifier policy
defaults to Highly Restrictive
whole-name (§8.2's SHOULD) and relaxes through `withIdentifierPolicy`, which §8.2 requires be code rather
than ambient; `withTokenPolicy` is the other surface and defaults to `unrestricted()`, a value being data that
may legitimately be anything.

`SidecarSchemaReadTest` is the other half and is what makes `schemas/` validation rather than
documentation: every sidecar read against the schema it declares, plus the negatives the groups exist
for. `SidecarSchemasTest` checks the schemas themselves resolve — every `.tn` in `schemas/`, listed rather
than named in a constant, so a layer schema added upstream is one this has to resolve — serving the suite's
own identities beside the bundled ones since the layer schemas `!!import` `sidecar-common.tn`.

**The corpus is a declared input of every `Test` task** (root `build.gradle.kts`). It lives outside this
build, so Gradle would otherwise report the previous run as up to date over an edited vector — a stale green
over a changed corpus, which is the one thing a conformance signal must not do.

**A skip is not a pass.** `SuiteCheckout` finds the corpus — a sibling working copy first (a developer
editing vectors must see their own edits), then the pinned copy `scripts/fetch-references.sh` fetches
into `.references/`, with `-Dtson.testSuite.dir` overriding both authoritatively. An absent corpus
aborts through `Assumptions` so a bare clone stays green, **except where `TSON_REQUIRE_TEST_SUITE` is
set — CI sets it — where it fails instead**. Without it a CI run with no corpus would abort every vector and
go green while measuring nothing.
**The pin is a commit, never a branch**: an upstream vector must not be able to turn this repo red with
no change here.

**The `reader` layer is where a Class 1 document gets its verdict**, and the rules §1.2 leaves to no tier
live there and nowhere else — §2.5's unique field names, §2.6's key identity (including the decoded-value
rule a parser cannot apply), §2.8's empty brace, §2.9's absent-key restriction. A `parser/invalid/` vector
cannot fail on `{ a: 1  a: 2 }`; the parser accepts it by design. An error vector there states
`category: resolver` and its subject must parse, which `checkReaderVector` asserts before asking the reader
for a verdict.

**The `class2/` layers are the three answers a Class 2 processor gives.** `schema/` needs no invented
expectation format: §1.3 makes producing a resolved schema value a MUST and §8 fixes its serialization, so a
valid vector's subject is a schema document and its expected side is that document's resolved output in §8's
own form, read back through `ResolvedForm` (shared with `ResolvedFixtureTest`, which asks the same question
of `spec/m/*-resolved.tn`) and compared entry for entry, `@synthetic` key markers included. `link/` states
individual facts about the linked namespace — §2.2.3's import closure, §5.4's derived disjointness, §8.2's
`subtypes` index. `validate/` is a data document against a schema that loaded, where the expected side of a
failure is §8.1's category plus the RFC 6901 pointer into the data and nothing else.

**At the schema and link layers the category is the phase's, not the diagnostic code's.** §8.1 says every
error that makes a schema fail to load or ingest is a resolver error "however value-like the violated rule",
so a schema-authoring mistake this library catches through the meta's own compiled reader arrives carrying a
record-shaped code and is still a resolver error. What is checked per diagnostic instead is that each one is
a **verdict** (`Code.verdict()`): a gap, a bind mismatch and the five fetch codes say the vector could not be judged, and
letting one satisfy an error vector is how a corpus comes to pass on the strength of not having been run.

**No `class2/schema/` subject declares a template yet, and nothing stops one now.** An open entry's body is
the kernel's `template` constructor carrying the application as text (`schema.meta.TemplateBody`), so it is a
`type_definition` like any other and reads back as ordinary data — where §8.1's older shape wrote the
application as though it were a value of the constructor's own vocabulary, which no reader could apply
(§8.1). `ResolvedForm` compares an open entry's body by its *parsed* form, §5.10's one
spelling being about the application and not the whitespace, so the layer needs no expectation format of its
own. Templates are covered at the `link/` layer meanwhile, over the entries they mint; `BACKLOG.md` carries
the vectors that are owed.

**Add test-suite vectors in the same session as any lexer/parser/resolver work**, not after a nudge —
with one standing exception: the corpus's `resolver` layer is Part 1 *base-type* resolution, so a Part 1
vector about schema resolution has nowhere to go and the honest move is to say so rather than wedge one into
the wrong bucket.
A vector whose sidecar carries `encoding` is fed the file's bytes unchanged (`checkEncodingVector`),
because the ordinary string round-trip would re-encode exactly the bytes such a vector exists to test;
an encoding this implementation does not read is skipped, not failed, which `RUNNER.md` admits as one of
its three legitimate grounds.

