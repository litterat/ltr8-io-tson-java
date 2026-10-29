# Base types and atom vocabulary

What a token means: §4 base type resolution for untyped tokens, and the built-in atom vocabulary (`tson-atom`) that
reads typed ones. Current form only; history lives in git.

**Invariants**

- `AtomType` takes a `String`; only the kernel's `value` and `Token` need the lexical form, and they stay with the text
  encoding.
- A token's form is consulted exactly once, by base type resolution: a quoted token is a `StringValue` only at an
  *untyped* position, and no atom parser consults `TokenForm` ([TSON-DATA] §2.4).
- There is no `null`: absence is `_` and is lexical, never a `TokenValue`; the unquoted token `null` is the string
  `null`.
- `NumberGrammar` identifies and extracts a `NumberForm`; it does not convert to a host numeric type — binding does.
- Three indices, three questions, and no fourth: `BuiltinTypeVocabulary` (name), `AtomParsers` (resolved body),
  `HostAtoms` (host class); a second body→parser table is what drifts.
- Pattern facets are `String`, validated and matched through `ltr8-regex` (I-Regexp), never `java.util.regex`.
- Every atom is told apart by its body's constructor, never by its declared name — `value`, `void` and
  `identifier` included, each with a constructor of its own. `boolean` is the one name-keyed read, and only for
  its host value.
- `IdentifierGrammar` is §7.7's grammar and nothing else: `PROFILE` is `IdentifierType.IDENTIFIER.profile()`, never
  restated, and `validate`/`hygiene` report a violation and never throw.
- Facet comparisons are on the value denoted, never the token: `members` uses §4.3 identity (`compareTo`), and no facet
  counts written digits.

Related: `design/lexer-and-data-parsing.md` (the tokens these rules read), `design/atom-refinement-and-coherence.md`
(refinement, narrowing and the per-family coherence checks over these facets), `design/json-encoding.md` (the other
encoding the vocabulary serves), `design/readers-and-diagnostics.md`.

## Where the atom vocabulary lives

`tson-atom`, not `tson-compiler`, because [TSON-JSON] §5.1 hands a JSON string's content to the atom's own parser
exactly as a TSON quoted token's text would be: which families a reader can bind, and what each produces, is the type
system's, not an encoding's. `module-info.java` states the split by package.

**`AtomType` takes a `String`.** Two atoms need the lexical form, and both are escape hatches rather than types: the
kernel's `value`, decoded by §4 base type resolution whose §4.4 rule is that a quoted token is a string, and `Token`,
which *is* the token because §8's resolved form records the spelling. Those two, `TokenAtomType` which describes them,
`TokenValue`/`TokenForm` and `BaseTypeResolver` stay in `tson-compiler`. The form-dependent set is exactly where the
two encodings legitimately differ: JSON has no token forms and reads a `value` position by [TSON-JSON] §5.7's own rule.

**Three indices, three questions, and no fourth.** `BuiltinTypeVocabulary` maps a built-in *name* to a parser carrying
the constraints §5 fixes; `AtomParsers` maps a resolved *body* to one carrying whatever the schema resolved, which is
what a user's own `!integer ^ { max: 100 }` needs; `HostAtoms` maps a *host class* back to the family that produces
it. `VocabularyAtoms` is the write direction. The compiled readers (`AtomTypeReader.ATOM`, one factory for every atom
family) and the linker both ask `AtomParsers`, so there is no second opinion about which parser reads which body; a
second table restating it entry for entry is what lets a family be present in one and missing from the other.
`value_type` and `void_type` are the *encoding's*: `AtomParsers` declines both, their readings depending on the
lexical form and on a sentinel no token is.

`HostAtoms` answers by **what may reach the position**. `forStringContentHostType` and `forNumberContentHostType` are
[TSON-JSON] §5's per-family *kinds*, since JSON's grammar tells a string from a number and §5's table admits one or
the other per family; `forTypedPosition` is their union plus `text` and `boolean`, for TSON, where a typed position
never consults the form and `12` and `"12"` are one `int32` ([TSON-SCHEMA] §7.3). `forHostType` answers something
else: what a `value`-typed slot meant (below).

## Base type resolution (`tson-compiler/.../base/`, over `tson-atom`'s `atom.number`)

`BaseTypeResolver.resolve(TokenValue)` implements §4's fixed order (boolean → number → string, §4.5) for untyped
tokens. `NumberGrammar.tryParse` (`atom.number`, exported because base type resolution stays with the text encoding
and reads it) recognizes the `number` production (§7.6).

**What counts as untyped is narrower than "no `!!import`".** [TSON-DATA] §4.1 states its applicability over what
types a position, and names a declared host type naming a §5 family among what does. So a document with no `!!import`
read *into a Java class* is **typed**: `{ i: "12" }` reads as `12` at an `int`, exactly as at a schema's `int32`. §4 is
reached only where nothing types the position: a **tree** read (`TsonValue`, and `JsonValue` beside it), and targets
no built-in family names — `char`, an opaque `Object`, a host type outside §5's vocabulary. [TSON-JSON] §4.1 and §5.7
answer the same way. `HostAtoms.forTypedPosition` is the lookup (`DataClassObjectReader`), and
`ClassTypedPositionTest` asserts every case against a schema declaring the same types, the schema being the oracle.

- **Identification is separate from binding to a host numeric type.** `NumberGrammar` decides which grammar
  alternative matches and extracts its structural pieces into `NumberForm`; it does **not** convert to
  `long`/`double`/`BigInteger`/`BigDecimal`. The spec leaves that mapping to the implementation (§4.3); binding is
  where the required `255`/`0xFF`, `.5`/`0.5` equivalences are enforced, and different consumers want different host
  types.
- **The grammar is hand-written, one method per ABNF rule** (`NumberScanner`, package-private beneath
  `NumberGrammar`), because of what a *reference* implementation should contain: a `java.util.regex` pattern with
  named groups is stated in a dialect no other language shares, an unspecified host dependency in the artifact other
  implementations copy. `ltr8-regex` is not the substitute: I-Regexp has no named groups, so it cannot extract what
  `NumberForm` carries. The scanner is single-pass, with explicit `mark`/`reset` at each optional group a regex
  would backtrack over (a fraction, an exponent, a radix prefix whose digits do not follow), and is checked against a
  regex oracle: `NumberScannerEquivalenceTest` runs both over every string up to length three across the grammar's
  alphabet, 120,000 seeded fuzzed longer ones and near-misses of real numbers, comparing whole `NumberForm`s.
- **Quoted tokens always resolve to `StringValue`** regardless of content (§4.4) — `"42"` and unquoted `42` differ
  though their text is identical. **And the form is consulted exactly once** ([TSON-DATA] §2.4), the half that is
  easy to re-derive backwards: a quoted token at a *typed* position — a field declared `int32`, an `array`'s own
  `min_items`, a `~`/`=` value — is that type's value if its text is. Reading §4.4 as a rule about quoting rather than
  about *untyped* tokens rejects documents the spec requires accepted.
  `FieldValueConformanceTest.aQuotedNumericIsAValueOfAnIntegerFieldBecauseFormIsNotMeaning` pins it.
- **There is no `null`, and the order has three steps.** Absence has one spelling, `_`, and it is lexical (`TokenType.VOID`),
  so it is never a `TokenValue` and no order here could reach it. The unquoted token `null` is the string `null`, as
  `frobnicate` is — §7.7 rule 3 holds with no word to except. `BaseValue` carries a `VoidValue` member all the same,
  which `BaseTypeResolver` never returns: binding an identified value to a host type is one switch
  (`AtomBinder.bind`), and a schemaless bind reaching `_` needs a way into it.
- **§9.1's numeric-literal length limit** (4,096 digits, annotated tokens included) is **not enforced**: `LimitsPolicy`
  bounds nesting depth only, and `BACKLOG.md` carries the rest of §9.1's table.

## Built-in atom vocabulary (`tson-atom`)

`AtomType<T>` is a built-in atom's parsing contract (§5.2), over text: `read(String)`, `write(T)`, and `boundTo`, the
family reading into a caller's target, bound once where the reader is built.

**Each name denotes the type its library declares under it** — core.tn's, or net.tn's for its eight (`ipv4`, `ipv6`,
`cidr4`, `cidr6`, `mac`, `hostname`, `host`, `media_type`) — so a document keeps its meaning when it moves under a
schema importing that library ([TSON-DATA] §5.6). `HostnameParser` reads a domain name in either label form to an
`io.ltr8.net.HostName`, `HostParser` a host name or an address to an `io.ltr8.net.Host`, and `MediaTypeParser` a media
type with its parameters to an `io.ltr8.net.MediaType` ([TSON-DATA] §5.5). All three are string-class, and a `String`
target receives the canonical text — lowercase U-labels, a dotted-quad, unbracketed RFC 5952 — so a list of names
declared `List<String>` compares by name. A token outside the grammar is a parse failure; one outside a facet
(`allow_idn`, `allow_parameters`, `types`, `suffixes`) is a constraint violation, the value being one.

**`boolean` is in that table** ([TSON-DATA] §5.5): the tokens `true` and `false`, case-sensitive, over the kernel's
`!enum [true false]`. A typed position does not consult the form, so `!boolean "true"` and `!boolean true` are one
value — §4.2's special status for the two tokens is a base-resolution rule a typed position never reaches.
`BooleanParser` is the one statement of what `boolean` reads; the compiled reader stack's enum factory
(`AtomTypeReader.ENUM_OBJECT_MODE`) asks the vocabulary for it by name, which is the name-keyed read the invariant
allows, rather than keeping a second. A token that is neither member is the enum miss it is —
`ATOM_CONSTRAINT_VIOLATION`, matching every other enum. It stays out of `VocabularyAtoms` on `text`'s own terms: base
resolution recovers a boolean from an unquoted `true`, so a writer annotating every one would restate the token.

**An enum's members are text and its `type` says what they are labels of**, which changes nothing here: matching is
an identity check of the token's text against the members, and the host value is the member that matched. `type`
governs what may be *declared* — each member a value of it, none two of one value — so it is a schema-load question the
linker asks (`EnumLabels`). The one fact a reader needs from it is the name type's `normalization`: the token and each
member are compared in that form, so under a case-folding name type `Content-Type` is the member written
`content-type`. The type may be an imported entry, which only linking resolves, so the linker records each
enum's form (`EnumLabels.labelForm`, `TsonLinkedSchema.enumForms`) and every site that builds an enum parser passes it
to `AtomParsers.forType(body, form)` — both encodings' atom readers, map keys, pins and selectors, and the linker's own
default and pin checks. `text_type.members` is an ordinary facet on the shared parser, which `TextParser` checks last,
as the numeric tiers do: a member set names the whole value space, so the other facets hold vacuously where it is
present.

**A text family's value is its text in the type's `normalization` form** (`Normalization`, `ltr8-unicode`'s, which
`schema.meta` binds the kernel's enum to directly; [TSON-SCHEMA] §5.5). `text_type` carries the facet and every family
composing it inherits it — `NONE` by default, `NFC` on `identifier_type`, fixed to `NONE` on `regex_type` (folding a
pattern changes what it matches) and on `uri_type`, `iri_type` and `email_type` (a URI's path and query and a
mailbox's local part compare with case). Each parser puts the text into the form first and judges the result: the
lengths, `pattern`, the members (normalised the same way, `TextType.normalizedMembers`), an identifier's profile, a
URI's or an address's grammar. The three lengths are one helper, `TextParser.checkLengths`, which `UriParser` and
`EmailParser` call too, and they count code points, as `text_type` says. Every comparison downstream — map keys, set
elements, pins, look-alike scopes, hygiene — runs on the parser's value rather than the token. A refusal names the
token as written and then the value it was judged as — `'PUT' (read as 'put' under NFKC_CASEFOLD) is not a member of
this type` — through `TextParser.subject`: the written spelling is what a reader or a repair loop has to find, and the
value is what the facet compared.

- **`ASCII_CASEFOLD` against `NFKC_CASEFOLD`** (`ltr8-unicode`'s `NfkcCasefold`): the first lowercases A..Z and
  touches nothing else — no NFC either — so a profile of ASCII letters under it refuses the full-width and Kelvin-sign
  spellings `NFKC_CASEFOLD` would fold into the profile. It is the form of the case-insensitive ASCII naming systems
  (field names, schemes, DNS names), and `scheme_name` uses it; `NFKC_CASEFOLD` is for names compared without case
  across Unicode.
- **No comparison goes below NFC** ([TSON-SCHEMA] §5.5), whatever the form: two values are one when, each in its
  form, they are NFC-equal, so `NONE` and `NFC` share one equality and differ in the value. `ValueIdentity` compares
  map keys, set elements, FIXED values and pins in NFC of the value, and the `members` checks in `TextParser` and
  `EnumParser` compare NFC of the form, so under `NONE` or `ASCII_CASEFOLD` a composed and a decomposed `É` are one key
  and one member while each value keeps its spelling.

**`text_type` has no facet for the text's format, deliberately.** A facet narrows a value space (§5.7), and a format
such as CommonMark narrows nothing — every string is valid CommonMark — so it would fail §6's test for an annotation
rather than a facet; that is why `doc`'s CommonMark is its type's documented contract and not a facet. A format that
does narrow, such as a media type that must parse, is a type of its own.

- **Each constructor splits into two classes across two modules:** a constraint-*values* record in
  `io.ltr8.tson.schema.meta` (`IntegerType`, `TextType`, …, the kernel's `*_type` shape) and a same-named `*Parser` in
  the unexported `atom.parser` that holds one and does the `read`/`write`/validate work.
- **`RegexParser` returns `String`, and `TextType.pattern`/`UriType.pattern` are `Optional<String>`, not `Pattern`**:
  `regex` IS-A piece of text (§5.7), and a plain equatable `String` binds generically with no `DataBridge`. The text is
  validated by `IRegex.parse` (`regex_type`'s `spec` is fixed to RFC 9485; `java.util.regex`'s grammar is a superset)
  and matched by `IRegex.matches`, linear-time and ReDoS-safe.
- **`value`, `void` and `identifier` each have a constructor**, and are read by it: `value_type` by `ValueParser` (in
  `tson-compiler`, base type resolution to the natural host), `void_type` by `VoidReader` (the void sentinel `_`
  alone), and `identifier_type` by `IdentifierParser` — the text put into its `normalization` form, matched against
  the type's own `IdentifierProfile` (a profile failure is a parse failure), then `text_type`'s facets through
  `TextParser`. So a naming convention is a `pattern`, a closed vocabulary of names is `members`, and an identifier is
  string-class (§5.4). The kernel's `void` is `!void_type {}`, so the linker's refusal of a `void` variant and the
  inhabitance check ask the body (`ReferenceChain.resolvesToVoid`), not the name.
- **`identifier_type` is a UAX #31 profile**: `start`/`continue` bases (`XID`, `ID`, `ASCII`, `NONE`), `start_add`,
  `continue_add`, `medial` and `exclude` code-point sets, with `text_type`'s `normalization` defaulting to `NFC`.
  `IdentifierType.profile()` builds the `IdentifierProfile` on each call, and `IdentifierParser` holds the one it
  built, so a read builds nothing. The kernel declares two instances, told apart by entry name:
  `identifier => !identifier_type { continue_add: "-" }` (`IdentifierType.IDENTIFIER`) and `scheme_name`
  (`IdentifierType.SCHEME_NAME`). `MetaKernelBootstrapResolver` refuses either stating any other body, so the
  constants and the declarations cannot drift. Every schema importing core reaches the kernel's `identifier`, and
  so cannot declare one of its own; a differently named identifier family is an `identifier_type` instance. How the
  profile facets behave under refinement and what `coherenceCheck` holds them to is
  `design/atom-refinement-and-coherence.md`'s.
- **`IdentifierGrammar` is the series' name grammar, beside the atom it is the profile of.** `PROFILE` is built from
  `IdentifierType.IDENTIFIER` and held statically by both encodings' readers, the schema parser, the resolver and the
  linker, since the kernel's own names are read before the kernel exists. `validate` is [TSON-DATA] §7.7 and
  `hygiene` §8.2's restricted-character rule, both over `PROFILE`, and both **report** a violation rather than
  throwing one, so one check serves a caller that owes a parse error and one that owes a diagnostic: the identical
  violation is a `ParseException` from the lexer and a refusal from the linker. It is not a parser — it refuses text
  not in NFC, where `IdentifierParser.IDENTIFIER` reads a value and puts it into NFC first. It lives in `tson-atom`
  rather than `tson-base` because its one definition is `tson-schema`'s, and `tson-atom` is the module under both
  encodings that can see it.
- **The network family reuses one grammar per address form, never a second copy.** Both are `ltr8-net`'s
  `InternetAddress`, which `Iri`'s grammar reads its IP literals through too: its IPv6 half parses RFC 4291 §2.2's
  embedded IPv4 tail through the same strict `dec-octet` rule `Ipv4Parser` reads, and `Cidr4Parser`/`Cidr6Parser`
  parse a network's address half through those two — so the JDK leniency `Ipv4Parser`'s Javadoc documents is shut out
  in one place. The CIDR pair adds §5.5's two validation rules (prefix length inside the family range; host bits zero
  under that prefix, since a network that accepted and masked would be lossy) and the `min_prefix`/`max_prefix`
  facets. **`within`/`excluding` apply across all four**, each family asking its own question of the same arithmetic:
  an address must fall inside some permitted network and outside every excluded one; a network must be a subnet of a
  permitted one and must not overlap an excluded one, since a block partly inside an exclusion is partly excluded.
- **`uri`/`uri_reference` (`uri_type`, RFC 3986) and `iri`/`iri_reference` (`iri_type`, RFC 3987) share one
  recognizer, `ltr8-net`'s `Iri`**, under its URI or IRI grammar, and read to the `Iri` itself: the text as written,
  split into its components. `java.net.URI` (RFC 2396) is a binding target, never the judge — `JavaUriAtom` holds a
  value as a `URI` or refuses it as a binding failure (`IllegalArgumentException`, which `AtomRefusal` files as
  `TYPE_MISMATCH`). Every one is an IRI-reference, so `HostAtoms` answers `URI` and `Iri` with `iri_reference`, the
  widest — one class over several families answers here, where the CIDR pair's does not, because one family's value
  space contains the others'. `VocabularyAtoms` names the narrowest atom per value, so a written value reads back.
  `iri_type`'s facets are `uri_type`'s, compared through `IriType.uriFacets`. A directive argument is read as
  `iri_reference` ([TSON-DATA] §3.3); whether an identity is absolute is §2.2.1's question, asked where the identity is
  formed. `schemes` is a member set of `scheme_name` values, so a scheme is held folded, two casings of one are a
  duplicate, and `UriType.admitsScheme` compares a value's scheme in that form (RFC 3986 §3.1).
- **A CIDR value is a network, not its text, and a family each** — `cidr4` reads to `io.ltr8.net.CidrInet4Network`
  and `cidr6` to `CidrInet6Network` (the prefix octets and the prefix length), so `2001:0db8:0000:…/32` binds equal to
  `2001:db8::/32`, and writing goes through RFC 5952's canonical form. **A type each because a host type is how a
  component names a family**: `HostAtoms` inverts the vocabulary by class, so a class two families produce answers
  nothing — the reason `Inet4Address` and `Inet6Address` are separate too. They share the sealed `CidrNetwork`
  supertype and, through `CidrBits`, one implementation of the prefix arithmetic; a component naming the supertype
  binds as a union, which is what it is. `mac` and `email` keep `String`: nothing about their text decomposes into a
  value a schema compares. Both networks are Java records registered as **atoms** (`AtomContext.hostTypes()`), or
  `ltr8-bind`'s record auto-detection would expect `{ prefix: … prefixLength: … }` on the wire where one token stands.
- **The exact tiers' sparse `members` set is a facet, and its identity is [TSON-DATA] §4.3's.** `integer` and `number`
  carry a member set (§5.6) for a value set that is neither a contiguous range nor an arithmetic progression;
  `IntegerParser`/`DecimalParser` apply it beside the bounds and `multiple_of`. Membership is the value denoted: `0x50`
  is the member written `80`, having reduced to one `BigInteger`, and `2.5` is the member written `2.50`, which needs
  `compareTo` — `BigDecimal`'s own equality carries scale and is not §4.3's. `decimal_type.members` is typed
  `set<value>` (the family cannot name its own atom, §7.4), so an element arrives as whatever §4 resolved it to;
  `DecimalType`'s constructor reads each member as a decimal before the set is formed, which is where "`1` and `1.0`
  are one member and a duplicate" is enforced and what lets the read, the tightening (`AtomNarrowing.checkSubset`) and
  the coherence check (`AtomCoherence.checkMembers`) share one identity. `float_type` carries no member set, on the
  rationale it carries no `multiple_of`: a step cannot hold on a binary grid.
- **A `value`-typed slot is read under the atom of the position it stands in.** [TSON-SCHEMA] §7.4 types a
  constructor's constraint fields `value`, and the bootstrap ordering leaves no alternative — `duration_type` defines a
  duration and `duration => !duration_type {}` is a layer up in core.tn — so a bound is decoded by §4, which resolves
  boolean, number and string and none of those is a duration, a date or a UUID. `ValueParser.read(token, target)` asks
  `HostAtoms.forHostType` which built-in produces the position's own host type and re-reads the token under it;
  `ValueParser.at(target)` is the same as a reader, which `reader.BindTargets` swaps in at a record field and at any
  depth below it, beside `RecordBindReader.tokenAware`, which specialises the same slot on the same evidence. **The
  host type is the class a component's bridge takes, never the one it declares** — a registered atom or a
  `@Transparent` wrapper is reached through its wire type, the same class the ordinary atom branch binds against.
  **Additive by construction**: a value the component can already hold, or that a numeric narrowing reaches, is
  returned untouched, so `!number ^ { min: 0x10 }` stays the integer 16 rather than being re-read under `number`,
  whose grammar admits no based-integer form. Only a token the position could not hold under any narrowing reaches the
  atom — which is what makes `!number ^ { min: "abc" }` `number`'s own verdict rather than a cast failure reported as
  a library gap. The resolver side of the same rule is `design/atom-refinement-and-coherence.md`'s.
- **No facet counts written digits, because scale is not part of the value.** meta.tn says it for both families
  that carry a digit-count facet — `decimal_type`'s "`1`, `1.0` and `1.00` are one value", and `time_type`'s
  "`12:00:00.500` is admitted under `precision: 1`". So `precision: N` tests that the value is a whole number of
  10⁻ᴺ seconds (`FractionalSeconds`, over the parsed nanosecond field, for `time`/`datetime`; `DurationParser`, over
  the seconds count's stripped scale, for `duration`), and `total_digits`/`fraction_digits` measure
  `stripTrailingZeros()` (`DecimalParser`). The value handed back is exactly as written — only the measurement strips,
  the same split `members` makes.
- **`duration` is a signed exact decimal number of seconds, bounded at both ends by a signed 64-bit count of
  nanoseconds** ([TSON-DATA] §5.4, [TSON-SCHEMA] §5.5) — the spec's range, not `java.time.Duration`'s, which is three
  orders of magnitude wider. `DurationParser` enforces it on read and `DurationType.coherenceCheck` on every bound and
  `precision`, so a `DurationType` built in Java cannot carry one outside it either; that is what makes
  `DurationType.isMultiple`'s `toNanos` total.
