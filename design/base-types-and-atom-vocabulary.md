# Base types and atom vocabulary

Design notes for what a token means: §4 base type resolution for untyped tokens, and the built-in atom vocabulary
(`tson-atom`) that reads typed ones. Current form only; history lives in git.

**Invariants**

- `AtomType` takes a `String`; only the kernel's `value` and `Token` need the lexical form, and they stay with the text
  encoding.
- A token's form is consulted exactly once, by base type resolution: a quoted token is a `StringValue` only at an
  *untyped* position, and no atom parser consults `TokenForm` (§7.4).
- There is no `null`: absence is `_` and is lexical, never a `TokenValue`; the unquoted token `null` is the string
  `null`.
- `NumberGrammar` identifies and extracts a `NumberForm`; it does not convert to a host numeric type — binding does.
- Three indices, three questions, and no fourth: `BuiltinTypeVocabulary` (name), `AtomParsers` (resolved body),
  `HostAtoms` (host class); a second body→parser table is what drifts.
- Pattern facets are `String`, validated and matched through `tson-regex` (I-Regexp), never `java.util.regex`.
- `unit`'s instances resolve to one identical body, so dispatch is keyed on the declaration's own name ([TSON-SCHEMA]
  §4.2).
- Facet comparisons are on the value denoted, never the token: `members` uses §4.3 identity (`compareTo`), and no facet
  counts written digits.

Related: `design/lexer-and-data-parsing.md` (the tokens these rules read), `design/schema-resolution.md` (atom
narrowing and coherence checks), `design/json-encoding.md` (the other encoding the vocabulary serves),
`design/readers-and-diagnostics.md`.

## Where the atom vocabulary lives

`tson-atom`, not `tson-compiler`. [TSON-JSON] §5.1 hands a JSON string's content to the atom's own parser
exactly as a TSON quoted token's text would be, so which families a reader can bind, and what host value
each produces, is a property of the type system rather than of the encoding that carried them. Left inside
the text engine it would be TSON text's by accident of placement, and a second encoding would either depend
on the whole engine or mint a second vocabulary for one fact.

**`AtomType` takes a `String`.** Of 25 parsers, two need the lexical form and both are escape hatches rather
than types — the kernel's `value`, decoded by §4 base type resolution whose §4.4 rule is that a quoted token
is a string, and `Token`, which *is* the token because §8's resolved form records the spelling. Those two,
`TokenAtomType` which describes them, `TokenValue`/`TokenForm` and `BaseTypeResolver` stay with the text
encoding. That the form-dependent set is exactly where the two encodings legitimately differ is not a
coincidence: JSON has no token forms and reads a `value` position by [TSON-JSON] §5.7's own rule.

**Three indices, three questions, and no fourth.** `BuiltinTypeVocabulary` maps a built-in *name* to a
parser carrying the constraints §5 fixes; `AtomParsers` maps a resolved *body* to one carrying whatever the
schema resolved, which is what a user's own `!integer ^ { max: 100 }` needs; `HostAtoms` maps a *host class*
back to the family that produces it, which is what a reader with no type-ref dispatches on and what a
`value`-typed slot asks. `VocabularyAtoms` is the write direction. A second body→parser table in the compiled
reader stack, restating `AtomParsers` entry for entry, is what drifts — a `period`-typed field's `~` default
reported as "not a scalar type" while `duration` beside it works.
The compiled readers and the linker both ask `AtomParsers`, so there is no second opinion about which parser reads
which body. Of `unit`'s instances, two are the *encoding's* rather than this vocabulary's: `AtomParsers` answers for
`identifier` and declines `value` and `void`, whose readings depend on the lexical form and on a sentinel no token is.

`HostAtoms` is itself three maps over one question, split by **what may reach the position**.
`forStringContentHostType` and `forNumberContentHostType` are [TSON-JSON] §5's per-family *kinds*, since
JSON's grammar tells a string from a number and §5's table admits one or the other per family;
`forTypedPosition` is the union plus `text` and `boolean`, for TSON, where a typed position never consults
the form and `12` and `"12"` are one `int32` ([TSON-SCHEMA] §4.2). One question, two encodings, and the split
is the encodings' rather than the vocabulary's. `forHostType` is the fourth and answers something else: what
a `value`-typed slot meant, where §7.4's bootstrap ordering leaves the position's own type as the only
evidence.

## Base type resolution (`tson-compiler/.../base/`, over `tson-atom`'s `atom.number`)

`BaseTypeResolver.resolve(TokenValue)` implements §4's fixed order (boolean → number → string,
§4.5) for untyped tokens. `NumberGrammar.tryParse` (`atom.number`, exported because base type resolution
stays with the text encoding and reads it) recognizes the `number` production (§7.6).

**What counts as untyped is narrower than "no `!!schema`".** §4.1 states its applicability over what types a
position, not over the header, and a document with no `!!schema` read *into a Java class* is the case that
difference decides: nothing in the document types the position and the target does. That is a **typed**
position — the class already fixes the shape of every record and array under it, and fixing the leaf too is
what makes `{ i: "12" }` read as `12` at an `int`, exactly as it does at a schema's `int32`. So §4 is reached
only where nothing types the position at all: a **tree** read (`TsonValue`, and `JsonValue` beside it), and
the handful of targets no built-in family names — `char`, an opaque `Object`, a host type outside §5's
vocabulary. [TSON-DATA] §4.1 names a declared host type naming a §5 family among what types a position, and
[TSON-JSON] §4.1 and §5.7 answer the identical question the same way for JSON. `HostAtoms.forTypedPosition` is
the lookup, and `ClassTypedPositionTest` asserts every case against the schema declaring the same types, so the
schema is the oracle rather than a literal in a test.

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
  because a regex backtracks there and the two must agree. It is also a fifth of a read's allocation
  cheaper: nine anchored patterns tried in turn cost a `Matcher` and its internals per attempt, 47 of them
  per read of a document holding seven numbers.
  - **The scanner is checked against a regex oracle.** `NumberScannerEquivalenceTest` holds the equivalent
    patterns — whose `MAGNITUDE` (the complex form's part, group-less because a named group cannot repeat)
    must parenthesise `decimal-natural`'s bare `|`, or a zero-led magnitude with anything after it (`0.5i`,
    `0e3j`, `0.5-0.25i`) is refused where §7.6 admits it — running both over every string up to length four
    across the grammar's own alphabet and 120,000 fuzzed longer ones, comparing whole `NumberForm`s rather
    than match/no-match.
- **Quoted tokens always resolve to `StringValue`** regardless of content (§4.4) — form is consulted once,
  here. `"42"` and unquoted `42` differ even though their text is identical.
    - **And exactly once, which is the half that keeps getting re-derived backwards.** §7.4: "a token's form
      is consulted exactly once: by base type resolution (§4) … Everywhere else only the text matters. Type
      contracts operate on text — `!number 10.2` and `!number "10.2"` are the same value". So a quoted token
      at a *typed* position — a field declared `int32`, an `array`'s own `min_items`, a `~`/`=` value — is
      that type's value if its text is, and no atom parser consults `TokenForm`. Reading §4.4 as a general
      rule about quoting rather than a rule about *untyped* tokens makes an implementation reject documents
      the spec requires it to accept.
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
- **§9.1's numeric-literal length limit** (4,096 digits by default, annotated tokens included) is **not
  enforced**: `LimitsPolicy` bounds nesting depth only, and `BACKLOG.md` carries the rest of §9.1's table.

## Built-in atom vocabulary (`tson-atom`)

`AtomType<T>` is a built-in atom's parsing contract (§5.2), over text: `read(String)` (its natural host
value), `write(T)`, and `boundTo(Class<?>)` — the family reading into a caller's target, bound once where the
reader is built rather than carried into every read. `BuiltinTypeVocabulary` is the fixed, closed
name→`AtomType` table (§5).

**`boolean` is in that table** ([TSON-DATA] §5.5): the tokens `true` and `false`, case-sensitive, over
meta-kernel's `!enum [true false]`. A typed position does not consult the form, so `!boolean "true"` and
`!boolean true` are one value — §4.2's special status for the two tokens is a base-resolution rule a typed
position never reaches. `BooleanParser` is the one statement of what `boolean` reads, and the compiled reader
stack asks the vocabulary for it rather than keeping a second (`AtomTypeReader.ENUM_OBJECT_MODE`); a token
that is neither member is the enum miss it is — `ATOM_CONSTRAINT_VIOLATION`, matching every other enum.

**An enum's members are text and `profile` says which kind of enumeration they spell**, which changes nothing
here: matching is an identity check of the token's decoded text against the members either way, and the host
value is the natural parse of the member that matched. The profile governs what may be *declared*, so it is a
schema-load question (`EnumBody.coherenceCheck`) and no reader sees it. `text_type.members` is likewise an
ordinary facet on the shared parser — `TextParser` checks it last, as the numeric tiers do, a member set naming
the whole value space so the other facets hold vacuously where it is present.
It stays out of `VocabularyAtoms` on `text`'s own terms: base resolution recovers a boolean from an unquoted
`true`, so a writer annotating every one with `!boolean` would be restating what the token already says.

- **Each constructor splits into two classes across two modules:** a pure constraint-*values* record in
  `io.ltr8.tson.schema.meta` (`IntegerType`, `TextType`, `RegexType`, `DateType`, …, matching the kernel's
  `*_type` shape) and a same-named `*Parser` in `tson-atom`'s unexported `atom.parser` (`IntegerParser`,
  `TextParser`, …) that holds one and does the `read`/`write`/validate work. `tson-atom` depends on
  `tson-schema`, which is what lets a parser consult its constraint record directly.
- **`RegexParser` returns `String`, and `TextType.pattern`/`UriType.pattern` are `Optional<String>`, not
  `Pattern`** — `regex` IS-A piece of text (§5.7), so its host value is `String` like every other
  text-composing atom; the text is validated as I-Regexp via `tson-regex`'s `TsonRegex.parse` (not
  `java.util.regex`, whose grammar is a superset — `regex_type`'s `spec` is fixed to RFC 9485),
  and the parsed form discarded once it's confirmed well-formed. Keeping these as plain equatable `String`
  (not a compiled matcher) is also what lets them bind generically with no `DataBridge`. **Matching** a value
  against a `pattern` constraint (`TextParser`/`UriParser`) runs through `tson-regex`'s `TsonRegex.matches` —
  a Thompson-NFA, linear-time and ReDoS-safe — not `java.util.regex`.
- **`unit`'s three instances are read three ways**, not one: `value` (`ValueParser`, in `tson-compiler`,
  runs base-type resolution to the natural host), `identifier` (`IdentifierAtom`, the one `AtomParsers`
  answers for: the text matched against `IdentifierProfile`), `void` (`VoidReader`, accepts only the absent
  sentinel `_`). They resolve to the byte-identical `Unit` body — nothing in the *schema* distinguishes them —
  so dispatch is keyed on the declaration's own name, which [TSON-SCHEMA] §4.2 requires ("implementations
  MUST dispatch `value`, `identifier`, and `void` by their declared names").
- **The network family reuses one grammar per address form, never a second copy.** Both grammars are
  `base.atom.InternetAddress`'s: its IPv6 half parses RFC 4291 §2.2's embedded IPv4 tail through the same
  strict `dec-octet` pattern `Ipv4Parser` reads, and
  `Cidr4Parser`/`Cidr6Parser` parse the address half of a network through those two — so the leniency gap
  `Ipv4Parser`'s Javadoc documents is shut down once, in one place. What the CIDR pair adds on top is
  §5.5's own two validation rules (prefix length inside the family range; host bits zero under that
  prefix, since a network that accepted and masked would be lossy) plus the `min_prefix`/`max_prefix`
  facets. **`within`/`excluding` apply across all four**, each family asking its own question of the same
  arithmetic: an address must fall inside some permitted network and outside every excluded one, and a
  network must be a subnet of a permitted one and must not overlap an excluded one — the difference being
  that a block partly inside an exclusion is partly excluded, which for a value denoting a whole block is a
  rejection.
- **A CIDR value is a network, not its text, and a family each** — `cidr4` reads to
  `base.atom.CidrInet4Network` and `cidr6` to `CidrInet6Network` (the prefix octets and the prefix length),
  so two spellings of one network are one value and `2001:0db8:0000:…/32` binds equal to `2001:db8::/32`.
  Writing goes back through RFC 5952's canonical form rather than the authored spelling, which is what it
  means for the value to be the octets. **A type each because a host type is how a component names a family**:
  `HostAtoms` inverts the vocabulary by class, so one class two families produce answers nothing and a
  schemaless read of it is refused — the two are separate for exactly the reason `Inet4Address` and
  `Inet6Address` already are. They share the sealed `CidrNetwork` supertype and, through `CidrBits`, one
  implementation of the prefix arithmetic: only the width differs and it arrives with the value. A component
  naming the supertype binds as a union, which is what it honestly is. `mac` and `email` keep `String` for
  the reason the CIDR pair does not: nothing about their text decomposes into a value a schema
  compares. Both networks are Java records registered as **atoms** (`AtomContext.hostTypes()`), or
  tson-bind's record auto-detection would expect `{ prefix: … prefixLength: … }` on the wire where one token
  stands.
- **The exact tiers' sparse `members` set is a facet, and its identity is [TSON-DATA] §4.3's.** `integer`
  and `number` carry a member set (§5.6) for a value set that is neither a contiguous range nor an
  arithmetic progression, so none of the other facets denotes it; `IntegerParser`/`DecimalParser` apply it
  beside the bounds and `multiple_of`. Membership is the value denoted, never the token: `0x50` is the
  member written `80`, having reduced to one `BigInteger` before the check runs, and `2.5` is the member
  written `2.50`, which needs `compareTo` — `BigDecimal` carries its scale and its own equality is not
  §4.3's. `decimal_type.members` is typed `set<value>` (the family cannot name its own atom, §7.4), so a
  collection element arrives as whatever §4 resolved it to and nothing narrows it the way a record field's
  scalar is narrowed; `DecimalType`'s own constructor reads each member as a decimal before the set is
  formed, which is where "`1` and `1.0` are one member and a duplicate rather than two" is enforced and what
  lets the read, the tightening (`AtomNarrowing.checkSubset`) and the coherence check
  (`AtomCoherence.checkMembers`) share one identity. `float_type` carries no member set, on the same
  rationale it carries no `multiple_of`: a step cannot hold on a binary grid.
- **A `value`-typed slot is read under the atom of the position it stands in.** §7.4 types a constructor's
  constraint fields `value` and the bootstrap ordering leaves no alternative — `duration_type` is what defines
  a duration, and `duration => !duration_type {}` is a layer up in core.tn — so a bound is decoded by §4 base
  type resolution, which resolves boolean, number and string and none of those is a duration, a date or a
  UUID. `ValueParser.read(token, target)` asks `HostAtoms` which built-in produces the position's own host
  type and re-reads the token under it; `RecordBindReader.rebindValueIfNeeded` is where a field's reader is
  swapped for one, beside `rebindContainerIfNeeded` and `tokenAware`, which specialise the same slot on the
  same evidence. **The host type is the class a component's bridge takes, never the one it declares** — a
  registered atom or a `@Transparent` wrapper is reached through its wire type, so the family is chosen by
  what the bridge can be handed, the same class the ordinary atom branch binds against. **Additive by
  construction**: a value the component can already hold, or that the caller's
  own numeric narrowing reaches, is returned untouched, so `!number ^ { min: 0x10 }` stays the integer 16
  rather than being re-read under `number`, whose grammar admits no based-integer form. Only a token the
  position could not have held under any narrowing reaches the atom — which is also what turns
  `!number ^ { min: "abc" }` from a cast failure reported as a library gap into `number`'s own verdict.
- **No facet counts written digits, because scale is not part of the value.** meta.tn says it for both
  families that carry a digit-count facet — `decimal_type`'s "`1`, `1.0` and `1.00` are one value… whether a
  spelling's trailing zeros survive a round trip is an encoding's promise, not the type's", and
  `time_type`'s worked example, "a text encoding may spell an admitted value with trailing zeros
  (`12:00:00.500` under `precision: 1`)". So `precision: N` tests that the value is a whole number of 10⁻ᴺ
  seconds (`FractionalSeconds`, over the parsed nanosecond field for `time`/`datetime` and over the seconds
  count's own scale for `duration`), and `total_digits`/`fraction_digits` measure `stripTrailingZeros()`
  (`DecimalParser`). The value handed back is still exactly as written — only the measurement strips, the
  same split `members` already makes.
- **`duration` is a signed exact decimal number of seconds, bounded at both ends by a signed 64-bit count of
  nanoseconds.** The lexical form puts a fraction on the seconds component and nowhere else, so no
  non-terminating fraction is writable and every duration is a terminating decimal count — `number`'s value
  space in seconds, which is what makes `precision` exactly `fraction_digits` on that count and `multiple_of`
  exactly `number`'s. Both ends are §5.5's and not the host's: `DurationParser` refuses a tenth fractional
  digit and a magnitude past 2⁶³ − 1 ns, though `java.time.Duration` would take spans three orders of
  magnitude wider. That ceiling is also what makes `DurationType.isMultiple`'s `toNanos` total — the range
  is the range `toNanos` has — and `coherenceCheck` refuses a bound or a `precision` outside it, so a
  `DurationType` built in Java cannot carry one either. Longer spans are `period`, finer or wider quantities
  are `number` in the unit the schema names. [TSON-SCHEMA] §5.5 and §5.4 here state both ends.
- The full `int8`..`int256` width ladder is seeded, which is what §5.6's table lists.
- **The atom vocabulary is complete** — `complex`/`ipv4`/`ipv6`/`cidr4`/`cidr6`/`mac`/`email` all have parsers, the
  CIDR pair reusing the two address grammars and validating §5.5's family-range and host-bits-zero rules on top. All
  four network families apply `within`/`excluding` and judge the pair for emptiness at schema load — exactly,
  prefix-tree cover being counting rather than searching, with a network family's prefix bounds folded in, both halves
  stated by §5.5. The address grammars (`InternetAddress`) and the network values live in `tson-base`'s `base.atom`,
  beneath `tson-schema`, so that each family's `coherenceCheck` can judge its own `[value]`-typed facet entries
  without the linker or the resolver holding a rule of one family's. **`email` is a built-in of §5.5 like its
  siblings**, and its format check is the subset §5.5 pins: the `dot-atom "@" dot-atom` core, without quoted
  local parts, domain literals or comments.
