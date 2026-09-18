# Lexer and data parsing

Design notes for the front of the Class 1 input path: the lexer, the Tier 2 event stream and the Tier 3 AST. Current
form only; history lives in git.

**Invariants**

- The lexer decodes UTF-8 itself, code-point addressed; malformed UTF-8 is a `LexException`, never a U+FFFD
  substitution (§7.1), and §8.1's byte offset is counted from the input, not derived.
- Multi-line closing-delimiter detection checks the line content *after* removing leading whitespace against `"""`;
  backwards, every multi-line token is spuriously "unterminated" (`LexerTest`).
- Never embed literal BOM/NEL/LINE SEPARATOR/PARAGRAPH SEPARATOR in tests or source — use `\uXXXX` escapes.
- U+200E/U+200F are ignorable format controls, not horizontal space: a run of them with no real space is refused where
  both neighbours would have continued one token; trailing controls are not consumed into the token, and `..` is carved
  out.
- NFC normalization applies to *unquoted* tokens only; `Pattern_White_Space` is the spec's fixed 11-character set,
  hardcoded.
- `TsonDataStream` is the only thing that walks source text; `TsonDataParser` holds no grammar logic of its own.
- Neither tier dedupes fields or keys, resolves `EmptyBrace`, or interprets token text — intentional gaps (§1.2). A name
  is the exception: type-ref, annotation and field names are matched against `IdentifierProfile`, after NFC
  normalisation for a field name.
- The stream judges no document kind: it reads §2.2's header once, leaves a directive the header does not admit
  unconsumed, and frames the root value on first demand, not with the header.

Related: `design/base-types-and-atom-vocabulary.md` (base type resolution, the atom vocabulary),
`design/schema-grammar.md` (the schema grammar built on this parser), `design/readers-and-diagnostics.md`.

## Lexer (`tson-compiler/.../lexer/`)

`Lexer` is a single hand-written scanner producing `Token`s, driven off `nextToken()` (never a
`tokenize()` batch). §1.3 says higher parts introduce no new tokens, modes, or character-classification
changes — a statement about the *layering*, which holds. It is not a promise that this class never changes:
the spec is a working revision and this implementation has no users, so a lexer rule that turns out wrong is
fixed rather than kept (Revision 35's escape-table change is exactly that).

- **Constructed from a `ByteSource`** (`tson-base`'s `base.io`), decoding UTF-8 and buffering a few code
  points of lookahead —
  never requires the whole document resident as a `String`. **Code-point addressed, not char-addressed**
  (surrogate pairs are never split; supplementary-plane identifiers per UAX #31 work). `Position` tracks
  line, code-point column, and a UTF-8 byte offset (§8.1 error reporting).
- **A source already in memory is never copied.** `ByteSource.resident()` hands back the whole input as a
  `MemorySegment` — a `byte[]`, a heap or direct `ByteBuffer`, a mapped file — and the lexer indexes it,
  allocating no block at all. The choice is made once, at construction, off a final field, so the streaming
  path is unchanged. Measured: the stream stage drops ~455 bytes per read on the resident path and gains
  ~32 on the streaming one (one wrapper object), which is the trade the abstraction makes.
- **The lexer decodes UTF-8 itself**, off a block it reads from the source when that source is *not*
  resident — sized by `ByteSource.block()` rather than by the lexer, since the size belongs to where the
  bytes come from and it is the one allocation here proportional to nothing at all. No
  `InputStreamReader`, no `char[]` in between. Three reasons, and only the third is performance:
  - **A port has to do this.** A language without Java's charset machinery writes exactly this loop, so a
    reference that hides it behind the platform decoder omits the one part it exists to show. §9.1 makes
    UTF-8 RECOMMENDED and permits UTF-16/UTF-32; this implementation has only ever read UTF-8, and the byte
    layer being explicit is what would make a BOM-sniffing choice of decoder a local change.
  - **§8.1's byte offset is counted, not derived.** Each buffered code point carries the byte length it was
    decoded from (`lookaheadByteLengths`). An offset recomputed from the decoded value is right only while
    the input is well-formed, which is the one case where the offset matters least.
  - **A decoder that reports what it rejects can reject.** Malformed UTF-8 is a `LexException`, not a
    U+FFFD substitution: a replacing decoder makes the same broken byte an error outside a quoted token and
    silent content inside one, and for a format whose identity can be a hash of its bytes, substituting
    bytes is the wrong default. Overlong forms, encoded surrogates and values above U+10FFFF are refused
    too — two spellings of one character is §9.4's confusability problem one layer down. §7.1 requires
    exactly this: a decoder MUST NOT substitute U+FFFD and continue.
  - Blocks are also what keeps reading cheap: a byte (or character) at a time costs a call and, through a
    `Reader`, an allocation per character — 47% of everything a bind read allocated, proportional to the
    document rather than fixed. The block is deliberately modest, because it is throughput and not a
    window: the lookahead is still two code points. Every lexical rule runs across refill boundaries
    invisible to it, including a multi-byte sequence or a surrogate pair split across one — `LexerTest`
    walks token boundaries and a split pair across the seam, and `AllocationHarnessTest` pins the
    per-character cost.
- **A quoted token that holds no escape is its own text.** Decoding every quoted token would build a second
  copy to discover the first was already right — and `lexSingleLineToken` has just read every character, so
  whether there was a backslash is *known* rather than searched for. A multi-line
  token's lines are checked individually (`decodeAllEscapes` returns its argument when it finds none), since
  one token may hold both kinds of line. Lexing a long quoted token costs 5.8 bytes per character of input,
  which `AllocationHarnessTest` pins.
- **The escape table is `\" \\ \b \f \n \r \t \s` plus two `\u` forms, and one rule covers both of those.**
  `\uXXXX` and `\u{1*6HEXDIG}` are two spellings of one number, checked by asking whether the value denoted is a
  **Unicode scalar value** — so a surrogate is refused in either form and there is nothing to pair. That single
  rule does the work of the three MUST clauses UTF-16 pairing would need, and the braced form is what makes it
  sufficient:
  four hex digits cannot reach past the BMP, so without it the format would either keep the pairing rules or lose
  the ability to escape a supplementary character at all — which costs something real, plane 14 holding the
  variation selectors and tag characters a document has reason to write visibly rather than embed invisibly.
  **There is no `\/`**: a solidus needs no escaping anywhere in the format, and the one reason to admit it (a JSON
  document parsing unchanged) is a claim the format does not make. A **leading BOM** is stripped, on §7.1's own
  authority as an encoding courtesy rather than a debt to another format.
- **`Token` is a flat record of six raw `int` coordinates plus type/text**, not nested `Position` objects,
  to keep allocation off the high-throughput read path; `start()`/`end()` materialize a `Position` on
  demand.
- **§7.1's identifier profile is exact, and the JDK predicate alone is not it.** `Character
  .isUnicodeIdentifierStart/Part` is `ID_Start`/`ID_Continue`, and the `Part` half is additionally unioned
  with everything `Character.isIdentifierIgnorable` covers — all of `Cf` plus the non-whitespace C0/C1
  controls. Standing it in unmodified would put a BOM, a soft hyphen, a raw control and every bidi override
  (U+202A–U+202E, U+2066–U+2069, U+061C) inside identifiers, with every ASCII test still passing. `Lexer`
  subtracts the ignorable set and two literal `ID_ \ XID_` tables (24 code points for start, 20 for
  continue — the characters XID drops for not being NFKC-closed), which is **exact** against Unicode 16.0:
  zero over-, zero under-acceptance on both predicates across all 1,112,064 non-surrogate code points.
  `Xid.UNICODE_VERSION` declares the version, as §7.1 asks.
- **`Xid` is the shared property, and neither profile is it.** `Xid.isStart`/`isContinue` are exactly
  `XID_Start`/`XID_Continue`, joiners included; the lexer's token profile adds `Nd`/`-`/`+`/`.` and subtracts
  nothing, and the kernel's `identifier` contract (`IdentifierProfile`) adds only `-`, requires NFC and applies
  the contextual joiner rule below. Keeping the property in one place is what stops the two drifting.
- **ZWNJ/ZWJ continue a token; whether they may appear in a *name* is decided one layer up.** U+200C and
  U+200D are in `XID_Continue` (Unicode 16.0 `DerivedCoreProperties.txt`), and §7.1 admits them on that basis,
  naming them as the two exceptions to its no-`Cf` rule. The lexer follows the property, and `IdentifierProfile`
  applies UTS #39 §3.1.1.1's contextual rule (`JoiningControls`): a joiner is admitted where it has a shaping effect —
  Persian `کتاب<ZWNJ>ها`, a Malayalam conjunct — and refused where it is invisible, which is every Latin
  position. That is sharper than a blanket exclusion in both directions, and it is why quoting is no
  remedy: the token profile governs unquoted tokens only, so a quoted spelling is the route by which
  `"ad<ZWNJ>min"` would reach a name. §7.7 rule 2 requires all three conditions for that reason.
  A JDK whose Unicode version moves needs both tables re-derived; their Javadoc says how.
- **NFC normalization** (`java.text.Normalizer`) applies to *unquoted* tokens only (§7.2.1) — quoted
  tokens preserve exact content. **Pattern_White_Space is the spec's fixed 11-character set**, hardcoded
  (not `Character.isWhitespace`). A single leading **BOM** is stripped; U+FEFF elsewhere falls through to
  "unrecognised character" naturally.
- **The whitespace set is three sets, not one** — [UAX31-R3a-1] sorts `Pattern_White_Space` into end-of-line
  (item 1), *ignorable format controls* (item 2, whose note names them as exactly U+200E and U+200F), and
  horizontal space (item 3, "all other characters"). Item 2's two are allowed only in contexts I1/I2/I3 —
  adjacent to horizontal space, wherever a space could have stood, at a line boundary — "where their
  insertion **shall have no effect on the meaning of the program**". §7.2 rule 1 sorts them the same three
  ways, and §9.5 rests on it: reading LRM as horizontal space is what would let `[1<LRM>2]` read as **two**
  elements, an invisible insertion changing the document's meaning.
  `skipWhitespace` implements R3a's own suggested strategy — ignore them when lexing, then refuse any lexical
  element containing one — as a two-character test at the run: a run holding no real horizontal space is
  refused when the code points on **either side of it** would have continued one token. That is I1 and I2
  decided locally, needing one field (`lastCodePoint`, the near neighbour) and no extra lookahead. Two
  details are load-bearing. **The trailing controls are not consumed into the token**, so a control where
  adjacency is required (`!type<LRM>{…}`) still leaves the positional gap §7.5's check reads — which is the
  right answer, a space being disallowed there too. And **`..` is carved out**: `.` is an unquoted-continuation
  character, so `1<LRM>..` would otherwise look interior when §7.2 rule 3 puts a boundary there regardless.
  The other ten bidi formatting characters need no rule — they are `Cf`, outside `Pattern_White_Space`, and
  the profile subtraction above already refuses them.
- **Multi-line common-prefix stripping** (§7.2.3) compares leading-whitespace prefixes character by
  character (a tab never matches a space). **Closing-delimiter detection checks the line content *after*
  removing leading whitespace against `"""`** — getting this backwards makes every multi-line token
  spuriously "unterminated"; `LexerTest` guards it.
- When embedding BOM/NEL/LINE SEPARATOR/PARAGRAPH SEPARATOR in tests or source, use `\uXXXX` escapes (and
  `\u{...}` past the BMP) — the
  literal invisible character is an editing hazard and exactly the confusable-character risk §9.4 warns
  about.
- Errors are **fail-fast** (`LexException`, unchecked), not the spec's "SHOULD continue to report multiple
  issues" recommendation (§8.1) — multi-error recovery is deferred.

## Structural parsing: Tier 2 stream + Tier 3 AST (`tson-compiler/.../`)

Two roles turn tokens into a `Document` (§2, §3, §7.4). There is exactly one implementation of the data
grammar, split by role, not duplicated:

- **`TsonDataStream` (Tier 2)** is the only thing that walks source text: a lazy, pull-based
  `TsonEventSource` (`stream` package — `hasNext()`/`next()`/`peek()` over a sealed `TsonEvent` hierarchy:
  `RecordStart`/`MapArrow`/`ArrayStart`/`TypeRef`/`SchemaRef`/`TokenEvent`/`AbsentEvent`/... each carrying
  its own `Position`). Driven off `Lexer.nextToken()` with an explicit frame stack (memory is proportional
  to open-container depth, never document size) and at most two tokens of lookahead (only to disambiguate
  `{}` record-vs-map).
- **`TsonDataParser` (Tier 3)** builds the full AST — the sealed `CoreValue` hierarchy in `ast`
  (`RecordValue`/`MapValue`/`ArrayValue`/`EmptyBrace`/`AbsentValue`/`TokenValue`) — by reducing the flat
  event sequence back into a tree. It holds no grammar logic of its own.

Key points:

- **Whitespace is invisible by the time tokens arrive** — the lexer discarded it, leaving only `Position`
  gaps. So `ws` in the grammar needs nothing special, and strict **adjacency** (`!`, `!!`, `@`, `:` to
  their operand, §7.5) is checked via `Position` equality between one token's end and the next's start.
  **Separator detection** (§2.4) works the same way: a real comma is optional evidence, a position gap is
  the other kind, and at least one is required unless the closing delimiter is immediately next. **A comma
  may follow a value, and that is the whole rule** — so a trailing one is ordinary and a leading or doubled
  one is not, the latter two needing no rule of their own since a comma is not a value. A trailing comma is
  admitted because nothing else could be meant by it: absence is spelled `_` and occupies a slot, so
  `[1, 2, ]` is two elements where `[1 2 _]` is three. RFC 8259 bans it because that grammar has elision —
  JavaScript's `[1, , 2]` is three elements with a hole — and this one does not.
  `consumeSeparatorOrCloseCheck` therefore answers "is there another element?", and the three container
  frames close on `false` rather than each re-checking the delimiter themselves.
- **Layering is deliberately incomplete, matching §1.2's division of labor.** Neither tier deduplicates
  record fields or map keys ("last value wins" is a resolver rule, §2.5/§2.6),
  rejects `_` as a map key (§2.9), resolves `EmptyBrace` to a record/typed container (§2.8), or interprets
  `TokenValue` text as boolean/number/string (base type resolution,
  `design/base-types-and-atom-vocabulary.md`). These are intentional gaps, not omissions.
- **§3.2's three type-expression forms are refused by name, not by the separation rule.** Array brackets,
  type arguments and the `?` suffix "exist only within the [TSON-SCHEMA] type-definition grammar, and their
  appearance after `!` in a data value is a parse error" — so `parseTypeRefName` checks for each and says
  which one was written and what to do instead. Left to the separation rule, `!paged<order>` reads as an
  adjacency problem ("expected whitespace before `'<'`"), whose advice produces a second error one column
  later and never states the rule that stopped it: a data type-ref is a bare name, and an application is
  named in the schema (`my_type => paged<order>`) and referenced as `!my_type`. Argument lists are refused
  whether or not a space precedes them, since an adjacency message sends authors to the spaced spelling;
  `?` only when adjacent, there being no message advising otherwise. The separation rule itself is
  unchanged and still catches everything else (`!int32"5"`).
- **A name position takes an `identifier`, not merely a bare token, and the check sits in the grammar.**
  `type-ref = "!" identifier` and `annotation = "@" identifier` (§7.4): `TsonDataStream` matches each name's
  text in full against `IdentifierProfile` once adjacency is settled, which is §7.6's own two-layer shape —
  the lexer produces a token, and a production that is no part of the token-stream grammar then matches its
  decoded text, exactly as a number is matched. It has to be the profile and not the token class, because
  token-Start carries `Nd`/`-`/`+`/`.` so a *number* can be an unquoted token, and those reach names only
  because names and values share one lexical class. So `!42x` and `@x.y` are syntax errors rather than a
  reference to an undeclared type and an annotation carrying a name the format reserves — the dot being
  reserved as a future identifier separator. **`field-name` reaches the same check** (`requireFieldName`), and
  its production's two spellings are two spellings of one name: `unquoted-token / single-line-token`
  (`isFieldNameTokenType`, narrower than the map key's `isBareTokenType`, a key being a value and not a name,
  §2.6) is the *token* rule, and the decoded text is matched against the profile whichever form carried it. So
  `{"first name": 1}` is a parse error, and the diagnostic names the remedy the format already has: a key that
  is not a name belongs in a map. A record's fields are the named members of a shape, which is what makes them
  declarable.
  **Normalisation runs before the match**, which is the one thing the profile does not decide here.
  `IdentifierProfile` requires NFC as a *form* and would refuse a decomposed name outright, where §2.5 gives a
  field name its identity by NFC-normalised comparison — a decomposed spelling is the same name, and a
  duplicate rather than a malformed one. The lexer already normalises the unquoted spelling, so refusing the
  form here would make the quoted spelling the stricter of the two, which is the asymmetry the rule removes.
  **Quoting buys the lexical accidents of the unquoted form** — a name that would otherwise resolve as a number — and
  never a key that is not a name; a key that is not a name is what a map is for, and the diagnostic says so. A map key
  keeps all three token forms.
- **`!!meta` in the header throws `TsonUnsupportedDocumentException`, not `ParseException`** (`tson-base`'s,
  which is what a malformed document raises) — and the
  throw is `parseDocument`'s, on the `DocumentStart` the stream hands it, never the stream's own. This is a
  Class 1 processor; a schema document isn't malformed input, it's a well-formed document of a kind this
  parser doesn't implement, and §8.1 requires that distinction be visible (a categorized diagnostic). The
  *stream* judges nothing: it reads §2.2's header once, for everyone, and `TsonSchemaParser` sits on the same
  stream and requires the very directive this parser refuses -- taking its own `!!id`/`!!meta` off the same
  `DocumentStart`, through `TsonDataParser.documentStart()`, so the grammar has one implementation and each
  parser applies only its own rule on top.
  - **`DocumentStart` carries all three of §2.2's directives**, so classifying a schema document (§7.1) is an answer
    the events give. Whether one may be *read* is a conformance-class question one tier up — `TsonDataParser` and both
    read facades raise `TsonUnsupportedDocumentException` on it, while `TsonSchemaParser` requires the directive. What
    stays with each parser is its own rule: §12.1 requires exactly one `!!meta` where §2.2 merely permits it, and a
    schema document governed by `!!schema` is told which directive it needs.
- **A directive §2.2 does not admit in a header is left unconsumed**, not refused by the stream. `!!import`
  is a schema document's and `TsonSchemaParser` reads it; a data document carrying one is an error, but the
  wording that names the broken rule belongs to the parser that knows which kind of document was expected.
  A refusal in the stream could only say *"expected `!!schema`, `!!meta` or the start of the document's value"*,
  to a schema document whose actual problem was a missing `!!meta`. What the stream keeps is the
  **value-position** rule (`notAValue`), which names the directive rather than saying "found `!!`" — true
  whoever is reading, because nothing spelled `!!` can start a value.
- **The root value is framed on first demand, not with the header.** `fill()` pushes `RootFrame`/
  `DataValueFrame` the first time an event past `DocumentStart` is wanted, so reading only the header costs
  nothing and leaves an empty frame stack — which is what lets a schema parser take `!!id`/`!!meta` off the
  event and then drive `drain` over a stack the header never touched. The stream therefore never needs to
  know what kind of document it holds: a schema document simply never asks for a value. `TsonDataStreamTest`
  pins both halves.
- **Nested annotation value-scope is right-recursive** and can legitimately leave an outer data-value
  without a core-value (`@a:@b:val`) — §3.1's own worked example says so; intentional, not a bug.
