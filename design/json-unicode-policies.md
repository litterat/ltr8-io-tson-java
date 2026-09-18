# The JSON encoding: the two Unicode policies

Design notes for how [TSON-DATA] §8.2's identifier and token policies reach `tson-json` — which positions each judges,
which reader can apply which, and why the look-alike rule reaches no JSON position. Current form only; history lives in
git.

**Invariants**

- The identifier policy reaches member names read as field names and every `$type`; the token policy reaches map keys and
  string values.
- Under a schema only an **unmatched** name is judged, and it is judged before it is reported as `UNRECOGNIZED_FIELD`:
  declared fields, then rest collection, then hygiene.
- A rest key is a map key, not a name.
- `JsonObjectReader` holds a position and `JsonTreeReader` does not: the tree reader applies no identifier policy, and
  `bindMap` applies nothing to keys.
- The look-alike rule reaches no JSON position; a deployment that will not accept look-alike keys raises the token policy.
- A refusal is kept apart by its code — `RESTRICTED_CHARACTER` or `RESTRICTED_SCRIPT` — and never reported in §8.1's four
  categories.
- The token policy is built into `JsonStream`, where each token is produced exactly once; a number is checked rather than
  exempted.

Related: `design/json-encoding.md`, `design/json-schema-directed-reading.md` (the record and choice readers that call the
check), `design/json-facades-binding-writing.md` (`JsonObjectReader`), `design/json-lexer-stream-tree.md` (`JsonStream`),
`design/name-hygiene-read-path.md`, `design/name-hygiene-and-minted-names.md` (the schema-side walk).

## The Unicode policies, and which reach JSON

[TSON-DATA] §8.2's two policies are one object (`ProcessorPolicy`) and reach this encoding
differently. §9.4 states the split: the **identifier policy** reaches member names read as field names and
every `$type`; the **token policy**, when a deployment sets one, reaches map keys and string values.

**The identifier policy runs at schema load, and at exactly one place in a data read.** A converted schema's
declared names are judged once, when the schema links (`TsonSchemaLinker.checkNames`, §11.4's scopes) — so a
member name matching a declared field has already inherited that verdict and needs no second test, which is
what §9.4's parenthetical means. The one name that reaches the policy fresh is a member matching **no**
declared field in a record with **no** rest field, and it must be tested before it is reported: a homoglyph
(`pаssword`, Cyrillic а) would otherwise get `UNRECOGNIZED_FIELD` — a *verdict* — where §8.2 requires a
refusal reported in none of the four categories. That case — and its twin at a tag, a `$type` naming no
declared type — is the whole of the identifier policy's job
in a schema-directed JSON read, and it is why the check cannot simply be dropped as redundant.

**A rest key is a map key, not a name.** §6.2 collects unmatched members into the rest map, parsed by its key
type; §9.4 puts map keys under the *token* policy, which defaults to `unrestricted()`. So the order matters —
declared fields, then rest collection, then hygiene; §6.2's flatten is not built (`BACKLOG.md`), so
`TreeRecordReader` goes from declared fields straight to hygiene — and a converted schema's `@rest` tail is what keeps
ordinary foreign JSON from meeting an identifier rule at all. That is the on-ramp working as intended, not a
hole: the names in a rest map were never declared, so nothing about them is a name.

**A JSON tree read with no schema applies neither policy.** There is no Class 1 in this encoding (§1.3
principle 1, §1.5) — a JSON document with no binding is just JSON, and its member names are data. Judging
them under an identifier policy would refuse ordinary JSON for a rule that exists only where names are
declared.

**Where each runs.** Neither belongs in a layer that can only throw, for the reason
`DefaultTsonReadContext`'s Javadoc gives on the other side: a refusal needs a receiver, and a layer that can
only throw can only say "invalid", which is the one thing a policy refusal is not. The identifier policy runs in
the readers that hold a position — `reader.NameHygiene` on the schema-directed record and choice readers'
unmatched-name paths, `DataClassObjectReader.checkNameHygiene` on the schemaless bind read. The token policy runs
in `JsonStream` over the tokens it hands out, reporting through the receiver the stream is constructed with, the
way `TsonDataStream` applies it on the TSON side.
§10.1's limits policy is different — nesting depth is counted in the event layer and refused by throwing
`LimitExceededException`, the one place every container opens.

### The two JSON walks

Each layer has exactly one place that walks its scopes, and the JSON data layer has two, one per reader that holds a
position:

| Layer | Walk | Scopes |
|---|---|---|
| Data (JSON), schemaless | `reader.DataClassObjectReader.checkNameHygiene` | one record's member names — the two per-name rules only |
| Data (JSON), schema-directed | `reader.NameHygiene`, from the record and choice readers | an **unmatched** member name; a `$type` naming nothing — the two per-name rules only |

**The schema-directed reach is narrower than the schemaless one, and deliberately so** ([TSON-JSON] §9.4): a
member name matching a declared field, or a `$type` naming a declared type, carries that declaration's own
verdict, given when the schema loaded — so only an **unmatched** name is judged. The schemaless bind reader
checks every name instead, and is right to: there the class is the schema and nothing judged its component
names at load. **The order is load-bearing**: §8.2 before §6.1.1, because a refusal MUST NOT be reported in
one of §8.1's four categories, and a look-alike field name told it is *unknown* is a verdict on the document
for a policy rule — advice to add a field that is already declared, when the fix is one character.

## The identifier policy, and the one reader that can apply it

[TSON-DATA] §8.2 has two Unicode surfaces. The **token** policy reaches every JSON token and is
`JsonStream`'s, upstream of everything. The **identifier** policy reaches *names* — and in this encoding
only one reader can tell a name from a key.

**§4.1 is why.** `{"a": 1}` is one syntax for a record and a map, and the position decides which. So
nothing that merely reads a member name can say whether it read a field name (§2.5 makes that an
identifier at every layer) or a map key (data, which is the token policy's business). `tson-compiler` has
no such problem: TSON text spells the two apart (`a: 1` against `k => v`), so its read context checks every
`FieldName` event it delivers. That difference is the same one that made this a separate stack rather than
a front end over `TsonEventSource`.

**`JsonObjectReader` holds a position; `JsonTreeReader` does not.** The target class plays the schema's
part in this encoding, so a `DataClassRecord` position means the members are names and a `DataClassMap`
position means they are keys. `DataClassObjectReader.checkNameHygiene` applies the two per-name rules
there and `bindMap` applies nothing, deliberately — which is also what keeps a JSON-Schema conversion from
meeting a name rule on `additionalProperties`. The tree reader applies nothing at all: a `JsonObject`'s
members could be either, and guessing is what §4.1 forbids.

**Both defaults are on**, as §8.2 requires — the identifier profile MUST apply and a name's scripts SHOULD
be judged at Highly Restrictive over the whole name — and both relax through
`withProcessorPolicy(policy.withIdentifierPolicy(…))`, which §8.2 requires be code rather than ambient.
Every member name a record position carries is judged, declared or not: §8.2's scope is the names the
document wrote, and the undeclared case is the one that matters, a look-alike of a declared name arriving
where the class will not keep it.

**A refusal is a verdict but not an invalidity.** §8.2 says it MUST NOT be reported in any of §8.1's four
categories and §9.4 carries those categories here unchanged, so what keeps it apart is the *code* —
`RESTRICTED_CHARACTER` and `RESTRICTED_SCRIPT`, one per rule, because the two want different fixes.
`Code.verdict()` stays `true`: the processor looked and declined, and the sender holds the fix, which is
the question a consumer routes on.

**The third rule does not reach JSON, and that is the answer rather than a gap.** Names that read alike
is a property of a *set*, which `tson-compiler` asks of a record's field names in its schemaless tree
reader — where the grammar has already said those members are *fields*, so two that read alike are
unambiguously a problem. JSON cannot get there from either reader: a tree has no positions at all, and at
an object reader's `Map` position the members are keys, where two look-alike keys are two legitimately
distinct keys. **The dangerous case and the safe case are spelled identically**, which is §4.1 in one
sentence, so JSON must accept it.

A deployment that will not accept it has a surface that does reach these: the **token** policy, which
governs every JSON token including a map key, and which a stricter deployment raises. That is the honest
division — §8.2's identifier policy judges names, and where JSON cannot know that a member is a name, what
is left is the rule that judges data.

The record positions need nothing extra either: the admissible names are declared, so a member reading
alike to a declared one is undeclared and already reports `UNRECOGNIZED_FIELD`. The TSON bind path draws
the line in the same place, and drawing it elsewhere would make this encoding stricter than that one for a
rule §8.2 states once.

**It costs nothing measurable**, and one detail is what keeps it so: both rule implementations are
allocation-free when a name passes, but `Optional.ifPresent` with a capturing lambda is not — it captures
and allocates whether or not the `Optional` holds anything. Two per member name is ~140 bytes per bound
record in `JsonAllocationHarnessTest`; tested rather than `ifPresent`-ed, the check is inside the noise.

## The token policy ([TSON-DATA] §8.2, reached by §9.4)

`JsonStream` applies it to every token it hands out — strings, numbers and member names — because at that
layer nothing yet knows which of those a member name will turn out to be. §9.4 names "map keys and string
values"; a member name read as a *field* name meets the identifier policy as well, where the position that
decides it is known, so **a token policy stricter than the identifier policy subsumes it**, exactly as on the
TSON side.

`withProcessorPolicy(policy.withTokenPolicy(…))` is the surface, on either reader or through
`ProcessorConfig` — there is no `withTokenPolicy` on a JSON reader, `withProcessorPolicy` being its one policy
derivation. The token policy defaults to `unrestricted()`: a value is data and may
legitimately be anything, so §8.2 scans none of it until a deployment says otherwise — and §8.2 requires that
saying so be code rather than ambient, which is what the derivation is.

**Built into the stream rather than wrapped around it**, as `TsonDataStream` does.
The property the check needs is that each token is
produced exactly once, which a stream gives and a read context does not (it rewinds). A wrapper would buy that
property and cost a second place to forget to apply it; with two encodings needing one rule, the mechanism
is one too.

A number's digits are ASCII so a number never trips the check, and it is checked anyway rather than exempted
— a rule with an exception nobody can state is a rule someone gets wrong when the exception stops holding.
