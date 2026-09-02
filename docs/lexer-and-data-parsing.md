# Lexer and data parsing

Design notes for the Class 1 input path: the lexer, the Tier 2 event stream, the Tier 3 AST, base type
resolution, and the built-in atom vocabulary. Current form only; history lives in git. `CLAUDE.md` holds
the one-paragraph orientation; this file holds the detail.

## Lexer (`tson-compiler/.../lexer/`)

`Lexer` is a single hand-written scanner producing `Token`s, driven off `nextToken()` (never a
`tokenize()` batch). **Complete and frozen for the whole series** (§1.3: higher parts introduce no new
tokens, modes, or character-classification changes).

- **Constructed from an `InputStream`**, decoding UTF-8 and buffering a few code points of lookahead —
  never requires the whole document resident as a `String`. **Code-point addressed, not char-addressed**
  (surrogate pairs are never split; supplementary-plane identifiers per UAX #31 work). `Position` tracks
  line, code-point column, and a UTF-8 byte offset (§8.1 error reporting).
- **The lexer decodes UTF-8 itself**, off a 512-byte block it reads from the `InputStream` — no
  `InputStreamReader`, no `char[]` in between. Three reasons, and only the third is performance:
  - **A port has to do this.** A language without Java's charset machinery writes exactly this loop, so a
    reference that hides it behind the platform decoder omits the one part it exists to show. §9.1 makes
    UTF-8 RECOMMENDED and permits UTF-16/UTF-32; this implementation has only ever read UTF-8, and the byte
    layer being explicit is what would make a BOM-sniffing choice of decoder a local change.
  - **§8.1's byte offset is counted, not derived.** Each buffered code point carries the byte length it was
    decoded from (`lookaheadByteLengths`), where the offset used to be recomputed from the decoded value —
    right only while the input is well-formed, which is the one case where the offset matters least.
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
- **A quoted token that holds no escape is its own text.** Decoding used to run over every quoted token,
  building a second copy to discover the first was already right — and `lexSingleLineToken` has just read
  every character, so whether there was a backslash is *known* rather than searched for. A multi-line
  token's lines are checked individually (`decodeAllEscapes` returns its argument when it finds none), since
  one token may hold both kinds of line. Halves the per-character cost of lexing a long quoted token
  (10.5 → 5.8 bytes per character of input, which `AllocationHarnessTest` pins).
- **The escape table is `\" \\ \b \f \n \r \t \s` plus two `\u` forms, and one rule covers both of those.**
  `\uXXXX` and `\u{1*6HEXDIG}` are two spellings of one number, checked by asking whether the value denoted is a
  **Unicode scalar value** — so a surrogate is refused in either form and there is nothing to pair. That single
  rule replaces the three MUST clauses UTF-16 pairing needed, and the braced form is what makes it sufficient:
  four hex digits cannot reach past the BMP, so without it the format would either keep the pairing rules or lose
  the ability to escape a supplementary character at all — which costs something real, plane 14 holding the
  variation selectors and tag characters a document has reason to write visibly rather than embed invisibly.
  **There is no `\/`**: a solidus needs no escaping anywhere in the format, and the reason it was admitted (a JSON
  document parsing unchanged) is a claim the format no longer makes. A **leading BOM** is still stripped, on
  §7.1's own authority as an encoding courtesy rather than a debt to another format.
- **`Token` is a flat record of six raw `int` coordinates plus type/text**, not nested `Position` objects,
  to keep allocation off the high-throughput read path; `start()`/`end()` materialize a `Position` on
  demand.
- **§7.1's identifier profile is exact, and the JDK predicate alone is not it.** `Character
  .isUnicodeIdentifierStart/Part` is `ID_Start`/`ID_Continue`, and the `Part` half is additionally unioned
  with everything `Character.isIdentifierIgnorable` covers — all of `Cf` plus the non-whitespace C0/C1
  controls. Standing it in unmodified put a BOM, a soft hyphen, a raw control and every bidi override
  (U+202A–U+202E, U+2066–U+2069, U+061C) inside identifiers, and every ASCII test still passed. `Lexer`
  subtracts the ignorable set and two literal `ID_ \ XID_` tables (24 code points for start, 20 for
  continue — the characters XID drops for not being NFKC-closed), which is **exact** against Unicode 16.0:
  zero over-, zero under-acceptance on both predicates across all 1,112,064 non-surrogate code points.
  `Lexer.UNICODE_VERSION` declares the version, as §7.1 asks.
- **`Xid` is the shared property, and neither profile is it.** `Xid.isStart`/`isContinue` are exactly
  `XID_Start`/`XID_Continue`; the lexer's token profile adds `Nd`/`-`/`+`/`.` and subtracts the joiners,
  and the kernel's `identifier` contract (`IdentifierParser`) adds only `-` and requires NFC. Keeping the
  property in one place is what stops the two drifting — the identifier profile is written to
  `XID_Continue`, joiners included, and the lexer no longer subtracts them.
- **ZWNJ/ZWJ continue a token; whether they may appear in a *name* is decided one layer up.** U+200C and
  U+200D are in `XID_Continue` (Unicode 16.0 `DerivedCoreProperties.txt`), so §7.1's set algebra admits them
  while its prose excludes them by name. The lexer follows the algebra, and `IdentifierParser` applies UTS
  #39 §3.1.1.1's contextual rule (`JoiningControls`): a joiner is admitted where it has a shaping effect —
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
  spuriously "unterminated"; this bug happened once and is guarded by `LexerTest`.
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
  the other kind, and at least one is required unless the closing delimiter is immediately next.
- **Layering is deliberately incomplete, matching §1.2's division of labor.** Neither tier deduplicates
  record fields or map keys ("last value wins" is a resolver rule, §2.5/§2.6), NFC-normalizes field names,
  rejects `_` as a map key (§2.9), resolves `EmptyBrace` to a record/typed container (§2.8), or interprets
  `TokenValue` text as boolean/number/string (base type resolution, below). These are intentional
  gaps, not omissions.
- **§3.2's three type-expression forms are refused by name, not by the separation rule.** Array brackets,
  type arguments and the `?` suffix "exist only within the [TSON-SCHEMA] type-definition grammar, and their
  appearance after `!` in a data value is a parse error" — so `parseTypeRefName` checks for each and says
  which one was written and what to do instead. Left to the separation rule, `!paged<order>` reads as an
  adjacency problem ("expected whitespace before `'<'`"), whose advice produces a second error one column
  later and never states the rule that stopped it: a data type-ref is a bare name, and an application is
  named in the schema (`my_type => paged<order>`) and referenced as `!my_type`. Argument lists are refused
  whether or not a space precedes them, precisely because the old wording sent authors to the spaced
  spelling; `?` only when adjacent, there being no message advising otherwise. The separation rule itself is
  unchanged and still catches everything else (`!int32"5"`).
- **A name position takes an `identifier`, not merely a bare token, and the check sits in the grammar.**
  `type-ref = "!" identifier` and `annotation = "@" identifier` (§7.4): `TsonDataStream` matches each name's
  text in full against `IdentifierParser` once adjacency is settled, which is §7.6's own two-layer shape —
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
  `IdentifierParser` requires NFC as a *form* and would refuse a decomposed name outright, where §2.5 gives a
  field name its identity by NFC-normalised comparison — a decomposed spelling is the same name, and a
  duplicate rather than a malformed one. The lexer already normalises the unquoted spelling, so refusing the
  form here would make the quoted spelling the stricter of the two, which is the asymmetry the rule removes.
- **`!!meta` in the header throws `TsonUnsupportedDocumentException`, not `TsonParseException`.** This is a
  Class 1 processor; a schema document isn't malformed input, it's a well-formed document of a kind this
  parser doesn't implement, and §8.1 requires that distinction be visible (a categorized diagnostic).
- **Nested annotation value-scope is right-recursive** and can legitimately leave an outer data-value
  without a core-value (`@a:@b:val`) — §3.1's own worked example says so; intentional, not a bug.

## Base type resolution (`tson-compiler/.../base/`)

`BaseTypeResolver.resolve(TokenValue)` implements §4's fixed order (boolean → number → string,
§4.5) for untyped tokens. `NumberGrammar.tryParse` recognizes the `number` production (§7.6).

- **Identification is separate from binding to a host numeric type.** `NumberGrammar` decides which of the
  four grammar alternatives matches and extracts structural pieces into `NumberForm` — it does **not**
  convert to `long`/`double`/`BigInteger`/`BigDecimal`. The spec leaves that mapping to the implementation
  (§4.3); binding is where the required `255`/`0xFF`, `.5`/`0.5` equivalences get enforced, and different
  consumers want different host types.
- **The grammar is hand-written, one method per ABNF rule** (`NumberScanner`, package-private beneath
  `NumberGrammar`), and that is a decision about what a *reference* implementation should contain rather
  than a performance one. A grammar stated as a `java.util.regex` pattern with named groups is stated in a
  dialect no other language shares — an unspecified host dependency in the artifact other implementations
  copy, and TSON pins I-Regexp for a schema's `pattern` facets while saying nothing about how a number is
  recognized. (This repo's own `tson-regex` is not the substitute: I-Regexp deliberately has no named
  groups, so it cannot extract what `NumberForm` carries.) The scanner is single-pass, with explicit
  `mark`/`reset` at the two places the grammar is genuinely optional — a float's fraction and its exponent —
  because a regex backtracks there and the two must agree. It also removed a fifth of a read's allocation:
  nine anchored patterns tried in turn cost a `Matcher` and its internals per attempt, 47 of them per read
  of a document holding seven numbers.
  - **Swapping it out found a real defect**, which is the argument for the oracle test rather than a
    coincidence. `MAGNITUDE` (the complex form's part, deliberately group-less because a named group cannot
    repeat) spliced `decimal-natural`'s own bare `|` into a larger alternation, so its `0` branch ended the
    alternative and a zero-led magnitude with anything after it — `0.5i`, `0e3j`, `0.5-0.25i` — was refused
    where §7.6 admits it. `1.5i` always worked, which is how it survived. `NumberScannerEquivalenceTest`
    holds the old patterns as an oracle (with that one defect corrected, and the correction explained),
    running both over every string up to length four across the grammar's own alphabet and 120,000 fuzzed
    longer ones, comparing whole `NumberForm`s rather than match/no-match.
- **Quoted tokens always resolve to `StringValue`** regardless of content (§4.4) — form is consulted once,
  here. `"42"` and unquoted `42` differ even though their text is identical.
    - **And exactly once, which is the half that keeps getting re-derived backwards.** §7.4: "a token's form
      is consulted exactly once: by base type resolution (§4) … Everywhere else only the text matters. Type
      contracts operate on text — `!number 10.2` and `!number "10.2"` are the same value". So a quoted token
      at a *typed* position — a field declared `int32`, an `array`'s own `min_items`, a `~`/`=` value — is
      that type's value if its text is, and no atom parser consults `TokenForm`. Reading §4.4 as a general
      rule about quoting rather than a rule about *untyped* tokens makes an implementation reject documents
      the spec requires it to accept; it was written down here as a defect once, before being checked
      against §7.4.
      `FieldValueConformanceTest.aQuotedNumericIsAValueOfAnIntegerFieldBecauseFormIsNotMeaning` pins it.
- **There is no `null`, and the order has three steps rather than four.** Absence has one spelling, `_`,
  and it is lexical: the lexer gives it `TokenType.ABSENT`, the stream an `AbsentEvent` and the parser an
  `ast.AbsentValue`, so it is never a `TokenValue` and no order here could reach it. The unquoted token
  `null` is the string `null`, as `frobnicate` is — §7.7 rule 3 holds with no word to except, and the
  token stays available to `enum`/`token`/FIXED-`text` positions like any other. `BaseValue` carries an
  `AbsentValue` member all the same, and `BaseTypeResolver` never returns it: binding an identified value
  to a host type is one switch (`AtomBinder.bind`), and a schemaless bind reaching `_` needs a way into
  it. A JSON document's `null` reaches absence through a JSON reader, which maps it in the model, where
  the position's own state decides whether absence is admitted at all.
- **§9.1's numeric-literal length limit** (SHOULD, 4096 digits, DoS-hardening) is **not enforced** — noted
  so it isn't mistaken for an oversight.

## Built-in atom vocabulary (`tson-compiler/.../atom/`)

`AtomType<T>` is a built-in atom's parsing contract (§5.2): `read(TokenValue)` (its natural host value),
`read(TokenValue, Class<?>)` (narrow to a caller target), `write(T)`. `BuiltinTypeVocabulary` is the
fixed, closed name→`AtomType` table (§5).

- **Each constructor splits into two classes across two modules:** a pure constraint-*values* record in
  `io.ltr8.tson.schema.meta` (`IntegerType`, `TextType`, `RegexType`, `DateType`, …, matching the kernel's
  `*_type` shape) and a same-named `*Parser` in `atom` (`IntegerParser`, `TextParser`, …) that holds one
  and does the `read`/`write`/validate work. This is what lets `atom` consult `schema.meta` constraint
  records directly.
- **`RegexParser` returns `String`, and `TextType.pattern`/`UriType.pattern` are `Optional<String>`, not
  `Pattern`** — `regex` IS-A piece of text (§5.7), so its host value is `String` like every other
  text-composing atom; the text is validated as I-Regexp via `tson-regex`'s `TsonRegex.parse` (not
  `java.util.regex`, whose grammar is a superset — `regex_type`'s `spec` is `REQUIRED_FIXED` to RFC 9485),
  and the parsed form discarded once it's confirmed well-formed. Keeping these as plain equatable `String`
  (not a compiled matcher) is also what lets them bind generically with no `DataBridge`. **Matching** a value
  against a `pattern` constraint (`TextParser`/`UriParser`) runs through `tson-regex`'s `TsonRegex.matches` —
  a Thompson-NFA, linear-time and ReDoS-safe — not `java.util.regex`.
- **`unit`'s three instances are three separate parsers**, not one: `value` (runs base-type resolution to
  the natural host), `token` (raw NFC-normalized token text, unconstrained), `void` (`VoidReader`, accepts
  only the absent sentinel `_`). They resolve to the byte-identical `Unit` body — nothing in the *schema*
  distinguishes them — so dispatch is keyed on the declaration's own name, which [TSON-SCHEMA] §4.2
  requires ("implementations MUST dispatch `value`, `token`, and `void` by their declared names").
- **The network family reuses one grammar per address form, never a second copy.** `Ipv6Parser` parses
  RFC 4291 §2.2's embedded IPv4 tail through `Ipv4Parser`'s own strict `dec-octet` pattern, and
  `Cidr4Parser`/`Cidr6Parser` parse the address half of a network through those two — so the leniency gap
  `Ipv4Parser`'s Javadoc documents is shut down once, in one place. What the CIDR pair adds on top is
  §5.5's own two validation rules (prefix length inside the family range; host bits zero under that
  prefix, since a network that accepted and masked would be lossy) plus the `min_prefix`/`max_prefix`
  facets; `within`/`excluding` stay unmodeled across all four, set membership against a list of networks
  being a materially bigger piece of work than a scalar bound. Their host type is `String` — the authored
  text, validated and handed back — for `MacParser`'s reason: Java has no type to map onto, and the
  round trip stays exact.
- The full `int8`..`int256` width ladder is seeded, which is what §5.6's table lists.
