# The JSON encoding: JEP 540 alignment, lexer, structural layer and tree

Design notes for the three lower layers of `tson-json` — `lexer`, `stream` and `tree` — and for what the tree's
alignment with JEP 540 does and does not claim. Current form only; history lives in git.

**Invariants**

- The JEP 540 alignment is the `tree` package and its vocabulary only; reading, writing and exceptions follow the TSON side
  of this library, and there is no `JsonParseException`.
- The lexer reads bytes only and decodes UTF-8 itself: a malformed sequence is refused at its own first byte, nothing is
  replaced, and every position carries a byte offset.
- A number is its exact source lexeme; nothing converts. `JsonNumber` holds the lexeme and equality is over it.
- Whitespace is RFC 8259's four characters and line breaks are LF, CR and CRLF; NEL/LS/PS are ordinary characters here.
- Lexer and stream are grammar only: member names are not deduped, no value is interpreted, no member name is reserved.
- Producing `EndOfDocument` is what pulls past the root value, and so what rejects trailing content.
- `NullValue` is a value in the event layer; JSON null as the absent sentinel is a typed position's question.
- Nesting depth is bounded in `JsonStream` against the shared `LimitsPolicy` and refused with `LimitExceededException`,
  which stays distinct from `ParseException`.

Related: `design/json-encoding.md` (rationale, packages), `design/json-schema-directed-reading.md`,
`design/json-facades-binding-writing.md`, `design/json-unicode-policies.md`, `design/lexer-and-data-parsing.md` (the TSON
text counterparts).

## Aligned with JEP 540 — and only in the tree

[JEP 540](https://openjdk.org/jeps/540) puts a simple JSON API in the JDK — `jdk.incubator.json`, JDK 28, not
available to build against now. The tree model follows its shape and its names: a sealed `JsonValue` over
`JsonObject`/`JsonArray`/`JsonString`/`JsonNumber`/`JsonBoolean`/`JsonNull`. A consumer moving between the two
learns one value model, and a bridge is later a mapping rather than a rewrite.

**The alignment is the `tree` package and its vocabulary. It is not a claim about anything else.** Reading,
writing and the exceptions either raises follow the **TSON side of this library** — `TsonTreeReader`,
`TsonObjectReader` and what they throw — because a consumer here holds documents in two encodings and must
route on one rule, not on which encoding happened to refuse. Where the JDK's shape and this library's
behaviour disagree, this library wins, and every JEP 540 mention elsewhere in the code should be read as
naming a value model rather than settling a behaviour.

Three consequences, all of them already true:

- **A fail-fast read throws `ReadException`, never a parse exception.** A syntax failure goes through the
  read's own receiver like every other problem ([TSON-DATA] §8.1 makes it a verdict the sender can act on),
  and for a fail-fast read the receiver is what throws — so `Json.parse("[1] 2")` raises `ReadException` with
  the position and `Diagnostic.Code` on `diagnostic()`, exactly as `new TsonTreeReader().read(…)` does. There
  is no `JsonParseException` in this library; the stack raises `tson-base`'s shared `ParseException` beneath
  the readers, and the readers classify it (`JsonDiagnostics`).
- **A collecting read never throws for a bad document.** It returns nothing and the collector says why —
  again the TSON readers' rule, not a JDK one, JEP 540 having no diagnostics model at all.
- **Diagnostics are one vocabulary across both encodings** ([TSON-JSON] §9.4), so a JSON problem is a
  `Diagnostic` with a `Code` from the same closed enum, never a JSON-specific report.

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

`JsonLexer` is a single hand-written scanner over UTF-8 bytes read from a `ByteSource` (`tson-base`'s `base.io`) --
**bytes only, with no character entry point**, since §3.1 makes the document UTF-8 and a decoder handed
characters has already lost the malformed-sequence rule and the byte offset §8.1 requires. A caller holding
a string forms `ByteSource.of(String)`, which re-encodes it as a value, and `Json.parse(String)`/
`JsonObjectReader.read(String, …)` do it there so one place re-encodes rather than every layer offering to. It is
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

Errors are `tson-base`'s shared `ParseException`, thrown immediately — fail-fast, as the TSON lexer is. **The message states
what went wrong and never where**; `position()` is the location, so a diagnostic built from one carries it
structurally rather than by parsing prose. §9.4 splits what the exception covers across two of [TSON-DATA]
§8.1's categories — a lexer error for malformed UTF-8 and ill-formed strings, a parse error for grammar
violations. The exception does not carry that split, and neither does the classifier: `JsonDiagnostics`, like
`TsonDiagnostics`, reports a base-syntax failure of either kind as one `VALIDATION_ERROR`.

## Structural layer (`tson-json/.../stream/`)

`JsonStream` is RFC 8259's grammar over the lexer, as a lazy pull-based `JsonEventSource`. It is the only
thing above the lexer that walks source text; it holds a frame stack and nothing else, so memory is
proportional to nesting depth rather than to document size, and the layers above consume events and hold no
grammar of their own. `JsonEvent` is a sealed hierarchy of ten records — the two container pairs,
`MemberName`, the four leaves, and `EndOfDocument`.

**No token lookahead at all.** JSON's grammar is LL(1) on already-lexed tokens and never needs a pushback:
after `{` the next token is either `}` or a member name and consuming it decides which. That is where
`TsonDataStream` spends two tokens, on the record-versus-map brace idiom — an ambiguity JSON does not have
because it does not draw the distinction. `peek()` holds back a produced event, not a token.

**There is no document-start event.** `JSON-text` is one value and carries no header, so there is nothing for
one to hold; the TSON counterpart exists only for `!!id`/`!!schema`. `EndOfDocument` does exist and is not
merely `hasNext() == false` — **producing it is what pulls past the root value, and so what rejects trailing
content**. `[1] 2` is refused there and nowhere else, the same trap the TSON facades keep under
`requireDocumentEnd`.

**`NullValue` is a value here.** §7 makes JSON null the absent sentinel's spelling *at a typed position*, and
this layer has none. Settling it in the event vocabulary is exactly the mistake a shared `TsonEvent` would
have forced.

**Grammar only**, on §3.1's own layering, and three things follow: member names are not deduped (§3.1 makes a
repeat an error whose *category* follows the position's type, which no grammar layer holds — the tree applies
the rule where JEP 540 does, the schema-directed decode applies it with a category); no value is interpreted;
and no member name is reserved, §3.2's `$`-namespace being a question about the position's type.

**One constructor**, `JsonStream(ByteSource, ProcessorPolicy, DiagnosticsReceiver)`. A stream reads under a
policy and reports through a receiver, and both are always true, so neither is defaulted: a caller with
nothing particular to say forms `ProcessorPolicy.defaults()` and `DiagnosticsReceiver.throwing()` where they
can be seen, rather than picking them up from an overload that hides which defaults it chose. The bound comes
off the policy rather than as a number, so there is no way to hand the stream a depth `LimitsPolicy` would
have refused — that record refuses a bound below one, once, for every encoding.

**`withProcessorPolicy` is `JsonObjectReader`'s only policy derivation.** `ProcessorPolicy` already carries
`withIdentifierPolicy`/`withTokenPolicy`/`withLimits`, so a caller changing one component writes
`r.withProcessorPolicy(r.processorPolicy().withTokenPolicy(p))` — one method on the reader, and the component
derivations where the components live. `JsonTreeReader` has the same one derivation. The TSON facades carry the
three component derivations beside it; this surface states each fact once.

**Nesting depth is bounded here** (§10.1) — the one place every container opens, so a refusal lands before
any consumer descends, which matters because every consumer of this stream recurses where the stream itself
iterates. The stream reads the bound off the policy's `LimitsPolicy` once, at construction, and **the
number and the refusal are the processor's, not this encoding's**: §10.1 makes it [TSON-DATA] §9.1's policy
"in JSON clothing, and the same policy applies with the same defaults", so the stream counts against
`LimitsPolicy.maxDepth()` and refuses with `LimitExceededException` — the same type the text
encoding refuses with, from `tson-base`. A deployment that raises the bound raises it for both encodings at
once, which is what one policy means.

That refusal type stays distinct from `ParseException`, and has to be: §10.1 makes a refusal §8.1's
fifth outcome, never a verdict, so it must be distinguishable or a configured bound reaches a consumer as a
syntax failure. `JsonDiagnostics` keeps them apart on the way out too — `Diagnostic.ofLimitExceeded` is
tried ahead of `ofBaseSyntaxError`, so the code a consumer routes on says which happened.

**Error messages name the construct the position admits**, not the token class found — "a member name is due"
where "expected STRING" would tell an author what a lexer calls the thing they already wrote.
`TsonSchemaParser` makes the same choice. One case escapes it and is pinned rather than papered over: `{a: 1}`,
the JS object-literal habit and the commonest of these mistakes, never reaches the grammar at all — `a` starts
no JSON token, so the lexer refuses it first and names the character. The grammar cannot improve on that
without the lexer knowing what position it is at, which is the layering.

## Tree (`tson-json/.../tree/`)

`JsonValue` is a sealed interface over six records — `JsonObject`, `JsonArray`, `JsonString`,
`JsonNumber`, `JsonBoolean`, `JsonNull` — with JEP 540's navigation (`get`/`tryGet`/`tryValue`),
conversions (`asString`/`asInt`/`asLong`/`asDouble`/`asBoolean`/`asMap`/`asList`), `of` factories, and
`Json.parse`/`Json.toDisplayString`. `Json.parse` reduces a `JsonEventSource` and holds no grammar; both
`String` and `InputStream` overloads exist, and the `InputStream` one is where §3.1's rules actually bite.

**Three divergences from JEP 540, each with a reason.**

1. **No source position on a node.** JEP 540 reports a navigation failure with "Location: line 13,
   position 19", which means its nodes know where they came from. These do not, so equality is over
   content and two parses of one document are equal — the shape `TsonValue` takes, and for the same
   reason: a value model holds values. It costs less than it looks like. A parse failure and a duplicate
   member are already reported with a `JsonPosition`, and the schema-directed decode streams events,
   which carry positions, rather than walking a tree. What is lost is a line number on a
   `JsonValueException`, which names the step and what is actually there instead.
2. **`JsonNumber` holds the lexeme, and equality is over it.** `1`, `1.0` and `1e2` are three distinct
   nodes. That looks wrong and is the honest layering: [TSON-SCHEMA] §5.5 makes equality a property of a
   *value space*, and a value space comes from a type, which this layer has none of. Under a schema the
   three are one `number`, and §5.3's own text says so. `toBigDecimal()` is the one call for a caller who
   means numeric comparison — added beside JEP 540's `new BigDecimal(number.toString())` advice rather
   than instead of it.
3. **`Json.parse(InputStream)`**, because §3.1 makes the document UTF-8 and puts a byte offset in every
   error report, and a decoder handed an already-decoded `String` has lost both.

**What the tree deliberately keeps from JEP 540** is the pair of behaviours a `TsonValue` user will find
surprising, and they are surprising in the right direction: **`get` throws** where `TsonValue.get` returns
a `TsonMissing`, and every throwing accessor has a non-throwing peer. One method name must not carry
opposite semantics on the two sides a consumer moves between, and that is the whole reason to align at all.

**§3.1's duplicate-member rule lands here**, not in the event stream: the stream is grammar and a repeat is
not a grammar error. §3.1 gives the *category* to the position's type, which a schemaless parse has none
of — so this refuses unconditionally, where JEP 540 does and for the reason §10.2 gives (two `$type`
members, one seen by a security filter and the other by the decoder). The schema-directed decode
reports the same fact with the category its position gives it. Names are compared **decoded**, so
`"ab"` and `"\u0061b"` are one name.

**`toString()` is compact RFC 8259 and `Json.toDisplayString` is the indented form.** Both parse back to
the value they came from, which is §9.2's round trip — "the conformance test, not a separate rule set".
`JsonText` is the only place this module writes a string: it escapes what RFC 8259 requires and nothing
more, leaving `/` alone and writing every other character as itself, since a UTF-8 document has no reason
to spell an ordinary character as an escape. A lone surrogate in a *hand-built* value is written as its own
`\u` escape rather than raw, which keeps the output well-formed UTF-8 — the value is still one §3.1
refuses on the way back in, and refusing it there is where the spec puts the rule.
