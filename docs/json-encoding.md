# The JSON encoding

Design notes for `tson-json` — the implementation of [TSON-JSON] (`spec/tson-part3-json.md`), the JSON
encoding of the TSON schema system. Current form only; history lives in git. `CLAUDE.md` holds the
one-paragraph orientation; this file holds the detail.

## What this is for, and what it constrains

The target is an **on-ramp**: someone holds a JSON Schema or an OpenAPI contract, converts it to a TSON
schema, and their existing JSON documents validate against it **unchanged**. Not "JSON that has been adjusted
for TSON" — the documents already in flight, byte for byte. That is the test the encoding has to pass before
any of its other virtues matter, because a format that asks a producer to change first has already lost the
audience it was converting for. §1.3's principle 2 is the same commitment from the spec's side: the common
case encodes to JSON "with no TSON-specific apparatus at all".

The goal is a real constraint, not a slogan: it decides what a converted schema may contain. A converter that
emits a schema outside the profile below produces one whose documents have to be rewritten, which is the one
outcome the exercise exists to avoid.

### What a converted schema will not contain

Each of these is a shape TSON has and a converted schema cannot reach — either because JSON Schema has no
source for it, or because reaching it would break documents already on the wire.

- **Non-text map keys.** JSON Schema's `additionalProperties`/`patternProperties` are string-keyed, so
  `{K => V}` with a compound `K` never arises. §6.5's **pairs form is therefore unreachable**, and with it one
  of §8.3's two class-stability leaks.
- **Annotations on data values.** No JSON carrier exists (§4.3) and JSON Schema has no source for one, so the
  encode-side refusal never fires. It stays implemented, for values that arrive from the text encoding.
- **Approximate atoms admitting the special values.** `.nan` and the infinities encode as JSON *strings*
  (§5.4) and no JSON document carries one, JSON having no spelling for them. A converted `type: number`
  should narrow `allow_nan`/`allow_infinity` to false — which closes §8.3's other leak, so **every type in a
  converted schema is class-stable** and §8.2's route 2 is available wherever the choice is disjoint.
- **Scoped positions.** `dynamic`/`extern` require an annotation object naming the type (§5.7, §8.5), which
  existing JSON does not carry. `additionalProperties: true` converts to the recursive `json` choice of §5.7,
  which is tag-free, never to `dynamic`.
- **In-band root binding.** Existing JSON has no `$schema`/`$type`, so the root binds by §3.4's out-of-band
  route — "the expected production route" in the spec's own words. `BACKLOG.md` carries the front-door and
  CLI surface that owes.

And two shapes that look like they belong on that list and do not: **tuples** convert from `prefixItems` and
are ordinary JSON arrays, and **defaults** convert and inject on decode (§6.1.3), so a document omitting a
defaulted member reads. Neither is friction.

### Where the on-ramp actually breaks

These are the ones that cost a producer a change, ordered by how often real JSON hits them. They are the
work, and the first is the largest single obstacle to the stated goal.

1. **A member name that is not an identifier.** `user-name`, `2fa_enabled`, `@type`, `first name`, `""` — all
   ordinary JSON, none a TSON field name. This is [TSON-DATA] §7.7's **grammar**, not §8.2's policy, so no
   configuration reaches it and no relaxation exists: kebab-case, JSON-LD's `@`-prefixed keys and OpenAPI's
   own `x-` extensions are simply unspellable as declared fields. The two available answers are both bad —
   collect them into an `@rest` map, which discards the typing that was the point of converting, or refuse.
   A projection annotation binding a wire spelling to a declared field would fit [TSON-SCHEMA] §6's licence
   exactly, on `@rest`'s own precedent; `SPEC-FEEDBACK.md` #5 states it.
2. **Records are closed and JSON Schema's are open.** `additionalProperties` defaults to *true*, so a
   converted record with no `@rest` field fails §6.1.1 on the first document carrying an extra member.
   **`@rest` is the default shape of a converted record**, not an optional refinement — which makes this
   encoding the annotation's first real consumer, as `BACKLOG.md` says.
3. **An untagged `oneOf` over object schemas.** All brace class, so non-disjoint; with no OpenAPI
   `discriminator` there is no in-band selector, and §8.2 requires the tag. JSON Schema validates such a union
   by *trying each branch*, which §8.2 forbids in as many words ("no trying variants in order"). Existing
   documents carry nothing to dispatch on, so this is a genuine wall: the converter finds a discriminator or
   reports.
4. **Enum members must be identifiers.** `"in-progress"` converts; `"not found"` does not. The fallback is a
   `text` refinement with a pattern, which costs the enum its discrimination class and so costs §8.2 route 2
   a variant it could have dispatched on.
5. **Required-but-nullable.** `required: [x]` beside `type: [X, "null"]` has no TSON spelling — present with
   an absent value is not a state (§7.3), and OPTIONAL would *weaken* the source contract. Drop-with-report,
   per the companion note's list D.
6. **`format` becomes binding.** JSON Schema's `format` is advisory; the atom it converts to is not. A
   document that passed with a malformed `format: email` value fails here. That is the point of converting and
   still a change of behaviour, so a converter should say so rather than let it surface as a first-request
   failure.

## The Unicode policies, and which reach JSON

[TSON-DATA] §8.2's two policies are one object (`TsonUnicodeProcessorPolicy`) and reach this encoding
differently. §9.4 states the split: the **identifier policy** reaches member names read as field names and
every `$type`; the **token policy**, when a deployment sets one, reaches map keys and string values.

**The identifier policy runs at schema load, and at exactly one place in a data read.** A converted schema's
declared names are judged once, when the schema links (`TsonSchemaLinker.checkNames`, §11.4's scopes) — so a
member name matching a declared field has already inherited that verdict and needs no second test, which is
what §9.4's parenthetical means. The one name that reaches the policy fresh is a member matching **no**
declared field in a record with **no** rest field, and it must be tested before it is reported: a homoglyph
(`pаssword`, Cyrillic а) would otherwise get `UNRECOGNIZED_FIELD` — a *verdict* — where §8.2 requires a
refusal reported in none of the four categories. That single case is the whole of the identifier policy's job
at the JSON data layer, and it is why the check cannot simply be dropped as redundant.

**A rest key is a map key, not a name.** §6.2 collects unmatched members into the rest map, parsed by its key
type; §9.4 puts map keys under the *token* policy, which defaults to `unrestricted()`. So the order matters —
declared fields, then rest collection, then hygiene — and a converted schema's `@rest` tail is what keeps
ordinary foreign JSON from meeting an identifier rule at all. That is the on-ramp working as intended, not a
hole: the names in a rest map were never declared, so nothing about them is a name.

**A JSON tree read with no schema applies neither policy.** There is no Class 1 in this encoding (§1.3
principle 1, §1.5) — a JSON document with no binding is just JSON, and its member names are data. Judging
them under an identifier policy would refuse ordinary JSON for a rule that exists only where names are
declared.

**When each arrives.** Neither belongs in the lexer or the event layer, for the reason
`DefaultTsonReadContext`'s Javadoc gives on the other side: a refusal needs a receiver, and a layer that can
only throw can only say "invalid", which is the one thing a policy refusal is not. Both arrive with the
schema-directed decode: the identifier policy in the record reader's unmatched-member path, the token policy
upstream of it over keys and string values, the way `TokenPolicyEventSource` sits upstream on the TSON side.
§10.1's limits policy is different and arrives sooner — nesting depth is counted in the event layer, the one
place every token is consumed.

## A stack of its own

`tson-json` does not build on `tson-compiler`'s `TsonEventSource`. That was the plan `BACKLOG.md` carried, and
the argument for it was real — the compiled reader stack consumes that contract, so an encoding emitting those
events would reuse resolution, linking and every compiled reader unchanged. Two disagreements between the
formats defeat it, and both are the shape of the JSON-superset claim Revision 35 withdrew ([TSON-DATA] §1.1,
§6): a claim that looks like reuse and is paid for at every point where the two designs differ.

- **A brace does not say what it is.** TSON text tells a record from a map syntactically — `a: 1` against
  `k => v` — so `TsonDataStream` emits `RecordStart`/`FieldName` or `MapStart`/`MapArrow` and each compiled
  reader asserts which it got. JSON's `{"a": 1}` is one syntax for both, and §4.1 makes the *position* decide,
  never inspection of the value. A pull-only event source has no channel for the position to say so, and every
  way of giving it one — a hint call, a schema-walking stream, readers that accept either shape — puts the
  JSON encoding's problem inside the TSON reader stack.
- **`null` is two things.** In a plain JSON tree it is a value, and JEP 540 has a `JsonNull` for it. Under a
  schema it is the absent sentinel and nothing else (§7). Mapping it to `AbsentEvent` in the event layer
  settles that question one layer too early, and forces the schemaless reading to inherit a schema's answer.

So: `tson-json` has its own lexer, its own structural layer, its own tree, and its own readers. The schema
half of §5–§8 is where it will depend on `tson-compiler`; the JSON layers under that stay usable, and
testable, without one.

## Aligned with JEP 540

[JEP 540](https://openjdk.org/jeps/540) puts a simple JSON API in the JDK — `jdk.incubator.json`, JDK 28, not
available to build against now. The tree model follows its shape and its names: a sealed `JsonValue` over
`JsonObject`/`JsonArray`/`JsonString`/`JsonNumber`/`JsonBoolean`/`JsonNull`, `Json.parse`,
`JsonParseException`. A consumer moving between the two learns one API, and a bridge is later a mapping rather
than a rewrite.

**`Json` leads a name here, on `Tson`'s own terms.** `CLAUDE.md`'s rule reserves a prefix for types a consumer
of this library names in their own code, and the names a consumer writes in this module are the JDK's — so
this module keeps them rather than minting a second vocabulary for one hierarchy.

Three places this must differ, each because §3.1 requires it:

- **It decodes UTF-8 itself, from bytes.** JEP 540 parses an already-decoded `String` or `char[]`. §3.1 makes
  the document MUST-be-UTF-8 and an invalid byte sequence an error rather than a U+FFFD substitution; a
  decoder handed characters cannot report what it never saw.
- **Every position carries a byte offset.** [TSON-DATA] §8.1 requires one of every error report and §9.4
  carries the requirement into this encoding. JEP 540 reports line and position only.
- **Numbers keep their digits.** §3.1 forbids rounding one silently and §5.3 preserves an exact number's
  digits and scale. JEP 540 reaches the same place by a different route (`new BigDecimal(number.toString())`),
  so this is alignment rather than divergence — but it is load-bearing here, where it is a courtesy there.

## Lexer (`tson-json/.../lexer/`)

`JsonLexer` is a single hand-written scanner over UTF-8 bytes read incrementally from an `InputStream`,
code-point addressed, with one code point of lookahead — no JSON token needs more. `nextToken()` returns only
a `JsonTokenType`, the text and the six position coordinates read off separate accessors, so a token costs no
`JsonPosition` allocation unless a caller retains one; `tokenize()` materializes `JsonToken` snapshots and is
for tests and small inputs. It is `tson-compiler`'s `Lexer` in shape, and deliberately not in code: the two
grammars share no production.

**Grammar, not structure.** `"] : ,"` lexes clean; refusing it is the structural layer's job. Member names
are not deduped here either — §3.1 makes a repeat an error whose *category* follows the position's type, which
is a fact no lexer holds.

The §3.1 profile, at the points it reaches the lexical layer:

- **UTF-8, decoded here.** Overlong forms, encoded surrogates and out-of-range code points are refused by the
  decoder, and a malformed sequence reports the offset of its own first byte. Nothing is replaced. The
  smuggling case is the reason: two spellings of one character, one of which a validator upstream may not have
  seen — §10.2's concern, one layer down.
- **A single leading BOM is discarded** and counts toward neither line, column nor offset — which §3.1
  requires of a decoder in the same breath as forbidding an encoder to emit one. A BOM anywhere else is an
  ordinary character: content inside a string, and not the start of any JSON value between tokens.
- **Strings decode to Unicode scalar values.** A well-formed `😀` pair is one character — the
  pairing mechanism is JSON's and this profile accepts JSON's grammar. A surrogate escape with nothing to pair
  is refused, not repaired: `text`'s value set is scalar sequences ([TSON-DATA] §7.2.2), and a JSON text
  encoding data no value can hold is rejected. This is the I-JSON (RFC 7493) reading, which RFC 8259 permits.
- **`\/` stays.** [TSON-DATA] §7.2.2 dropped it from the text encoding, whose own table had labelled it "(JSON
  compat)" — a debt to this format that this format never owed itself. Here it is simply one of JSON's eight
  escapes.
- **A number is its exact source lexeme.** Nothing converts. `199.90` reaches the layer above as `199.90`,
  which is what §5.3 promises and what a `double` would have destroyed before any rule could keep it.
- **RFC 8259 exactly, tightened.** No comments, no trailing commas, no unquoted names, no leading `+`, no bare
  `.5` or `5.`, no hexadecimal, no `NaN`/`Infinity` — the special values reach a JSON document as *strings*
  and only where an approximate atom admits them (§5.4). JEP 540 refuses the same set, for the same reason.

Two lexical rules differ from the text encoding's and are worth naming, because both look like oversights:

- **Whitespace is four characters**, RFC 8259's, where the TSON lexer's Pattern_White_Space set is eleven. A
  U+00A0 between tokens is an error here.
- **Line breaks are LF, CR and CRLF.** NEL/LS/PS are line terminators to [TSON-DATA] §7.2 and ordinary
  characters to RFC 8259. Counting a line at one would put this lexer's positions out of step with every other
  JSON tool's, over a document that is otherwise byte-identical.

Errors are `JsonParseException`, thrown immediately — fail-fast, as the TSON lexer is. **The message states
what went wrong and never where**; `position()` is the location, so a diagnostic built from one carries it
structurally rather than by parsing prose. §9.4 splits what the exception covers across two of [TSON-DATA]
§8.1's categories — a lexer error for malformed UTF-8 and ill-formed strings, a parse error for grammar
violations — and that split is the schema-directed layer's to make when it classifies one into a `Diagnostic`.
Carrying it on the exception as well would be a second opinion about one fact.
