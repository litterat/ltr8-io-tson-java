# The JSON encoding

Design notes for `tson-json` — the implementation of [TSON-JSON] (`spec/tson-part3-json.md`), the JSON
encoding of the TSON schema system. Current form only; history lives in git. `CLAUDE.md` holds the
one-paragraph orientation; this file holds the detail.

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
