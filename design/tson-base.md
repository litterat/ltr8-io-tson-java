# `tson-base`: the shared vocabulary

What every encoding shares and no encoding owns — diagnostics, policies, schema sources, host atom values, byte I/O and
the Unicode tables — package by package, with why each thing is here. Current form only; history lives in git.

**Invariants**

- The root package names none of its subpackages; every dependency runs inward.
- Nothing here knows what a TSON document or a JSON one looks like.
- The exceptions stay at the root rather than following their subject — that is what keeps the inward rule true.
- The `Tson` prefix is dropped here and only here: the competing name is another *encoding's* type, not a consumer's.
- Each encoding owns the classifier over its own exceptions (`TsonDiagnostics`, `JsonDiagnostics`); only
  `Diagnostic.ofLimitExceeded` stays on the record.
- `ByteSource`/`ByteSink` carry bytes, never characters; closing releases what was acquired and nothing handed in,
  and closing is not flushing.
- `IdentifierProfile.validate`/`hygiene` report a violation and never throw.

Related: `design/modules.md`, `design/readers-and-diagnostics.md`, `design/json-encoding.md`.

## The packages

**`tson-base`** — four packages, and **the root names none of the other three**: every dependency runs
inward, so a subpackage reads on its own and the vocabulary at the centre stays free of the machinery
around it. `io.ltr8.tson.base` is how a problem is stated — `Diagnostic` (the record and its closed `Code`
enum), the three diagnostics receivers, `SourcePosition`, `CanonicalIdentity` (§2.2.1's algorithm, how a
schema is named), and the exceptions whose fact is the **processor's** rather than
any one encoding's — `ReadException`, `ParseException`, `WriteException`, `LimitExceededException`,
`SchemaValidationException`,
`BindMismatchException` and its
`MissingBindingException` subclass, `SchemaFetchException` (whose `Reason` is what `Diagnostic.Code.of`
maps), `ContentHashMismatchException`. **The exceptions stay at the root rather than following their
subject**, which is what keeps the inward rule true — `Diagnostic.ofLimitExceeded` and
`Code.of(SchemaFetchException.Reason)` are same-package calls, where filing each exception with the package
it is thrown by would have the centre depend on two of its own subpackages. Sorting the eight by "is a
`Throwable`" would be sorting by Java mechanism in any case; this library files by subject, which is why
`LexException` sits in `lexer`. **The prefix is dropped here and only here**: `Tson` earns its keep
disambiguating a consumer's own `Schema` from `TsonSchema`, and in this module the competing name is
another *encoding's* type in this same library — `ReadException` beside `JsonValueException` reads right
where `TsonReadException` beside it implies the first belongs to the text encoding, which is exactly what
nothing here does. The argument outlived its first example: `ParseException` is now *shared* rather than
one encoding's, which is the same conclusion reached from the other end. A **true pure leaf** — depends on
nothing, and nothing in it knows what a TSON document or a JSON one looks like. It is a module rather
than a package because [TSON-JSON] §9.4 makes the JSON encoding report in [TSON-DATA] §8.1's four
categories and add none of its own: the vocabulary is one vocabulary across both encodings *by
specification*, so leaving it in `tson-compiler` would make every other encoding depend on the TSON text
engine to say "this field is required", or mint a second vocabulary for one fact. **What deliberately
stayed behind is the classifying half**: the `of*` factories that classify an exception all switch on an exception
type an encoding declares, so each encoding owns its own (`TsonDiagnostics` here, `JsonDiagnostics` in
the JSON stack) — which is also what closes the old "`ofBaseSyntaxError` cannot classify another
encoding's syntax failure" gap, since there is no longer one switch responsible for exceptions it cannot
name. `SourcePosition` moved here from `schema.meta` so the base need not require `tson-schema`; the
bonus is that any encoding's own position type can implement it and reach a `Diagnostic` with no
conversion — `JsonPosition` does. **`LimitsPolicy` and `LimitExceededException` are here on the
same argument**: [TSON-JSON] §10.1 makes the bound §9.1's policy "in JSON clothing, and the same policy
applies with the same defaults", so one record and one refusal serve both encodings and a deployment that
raises the bound raises it once. `Diagnostic.ofLimitExceeded` follows them, and is the one factory that
stayed on the record — its classifying siblings switch on an encoding's own exception type where it classifies
nothing at all.
**`io.ltr8.tson.base.policy`** is what this processor will admit and spend — `ProcessorPolicy` and the two
it composes, `UnicodePolicy` (§8.2's levels) and `LimitsPolicy` (§9.1's bounds), plus `FetchPolicy`, the
same statement about *obtaining a schema* (document cap, cache cap, whether a `?sha256=` pin is required)
— one package because a deployment states one set of constraints, and §8.2 requires a relaxation be code
rather than ambient: this is where that code points. **`FetchPolicy` is `ProcessorPolicy`'s sibling, not
its component**: a `ProcessorPolicy` is threaded into every reader and every stream, none of which fetch,
so a fetch bound riding the read path would be carried everywhere and used nowhere. A fetch *timeout*
stays `HttpSchemaSource`'s own — a directory has none, and a component one implementation silently ignores
is what makes a shared policy value untrustworthy.
**`io.ltr8.tson.base.source`** is where a schema comes from — `SchemaSource` and the two
implementations that ship, a directory and an HTTPS host allow-list, both denying by default, with
`SchemaReference` (§2.2.1's rules on what an identity may be) package-private among them, and
`SchemaAccess` collecting a source with the `FetchPolicy` governing it. [TSON-JSON]
§10.4 names that as the restriction an application processing untrusted input sets, which makes it
configuration like the policies rather than machinery like an encoding's reader.
**`ProcessorConfig` sits at the root**, beside the values it holds: one immutable value naming what a
deployment states -- the policy, the schema access, the bind context, and the one seam into the meta
vocabulary -- with every setting returning a new instance, so a configuration may be handed out and
derived from without the holder losing what they stated. Construction is not here and cannot be: it names
the compiler's registry, which is why `Tson.of(config)` lives with the engine.
**`io.ltr8.tson.base.atom`** is the host values the built-in atoms read to — `Rational`, `Complex`,
`CidrInet4Network`/`CidrInet6Network`, `InternetAddress` — the question a consumer arrives with rather than
part of §8's model, and
pure values depending on nothing. **`io.ltr8.tson.base.bind`** is what a deployment binds with:
`AtomContext` registers those host values, and the JDK ones beside them, with a `DataBindContext`, so a
class binds the same under every encoding ([TSON-JSON] §5.1). **That is why this module requires
`tson-bind`, and why doing so costs it nothing**: `tson-bind` is a general engine that binds a `DataValue`
to a Java object and has never heard of a schema — system-library standing, like the `java.net.http` this
module already rests on. The property that matters is unchanged: nothing here knows what a TSON document or
a JSON one looks like.
**`io.ltr8.tson.base.diagnostics`** is what a **rule** says when a document breaks one — `Refusal`, the four
`Diagnostic` components a rule determines (code, message, `expected`, `actual`), and a class per family
stating them once for every encoding. §9.4 makes that an obligation rather than a tidiness: one vocabulary
across both encodings, so a document wrong in one is wrong in the other for the same stated reason, and the
`code` and `expected` a consumer routes on cannot be left to two readers agreeing by having been copied.
**The prose is the schema's vernacular** — a record has *fields* in both encodings, absence is *absent*
rather than `_` or `null` — because it is the schema that refused the document; the encoding's own spelling
rides in `actual`, which is data. That split is what the parity test compares: code, path, `expected` and
`message`, never `actual`. `RecordDiagnostics` is the family that proves the shape; the rest follow —
`SubsumptionDiagnostics` sits beside it rather than inside it because §7.2's rule governs every atom and
product position and not records alone. What
stays with each reader is any rule the other encoding has no counterpart for.
**`io.ltr8.tson.base.io`** is where a document's bytes come from and go — `ByteSource` and `ByteSink`,
one pair for both encodings because [TSON-JSON] §3.1 makes the JSON lexer decode UTF-8 from bytes exactly
as [TSON-DATA] §9.1 makes the TSON one. **Bytes, never characters**: §7.1 forbids substituting on
malformed UTF-8 and §8.1 requires a byte offset in every diagnostic, so a `Reader` could satisfy neither
— the substitution would already have happened under someone else's rules — while a `String` is admitted
because it re-encodes as a value and the offset stays exact. **`resident()` is the zero-copy path**: a
source already in memory hands back the whole input as a `MemorySegment` (a `byte[]`, a heap or direct
`ByteBuffer`, a mapped file) and a lexer indexes it, allocating no block at all — asked once, at
construction, off a final field; a streaming source keeps the block, and **the block is the source's to
size** (`block()`, defaulting to 512), which is where a pool would go and the knob a throughput
measurement turns. **Closing releases what a source acquired and nothing it was handed**, so
`of(InputStream)` closes nothing and `of(Path)` closes the stream it opened — and whoever *creates* a
source closes it, which is why a reader given one through `read(ByteSource)` does not.
**`ByteSink` is the write-side counterpart and carries the same two rules**: the block is the sink's to
size (`block()`), and closing releases what it acquired and nothing it was handed — `of(OutputStream)`
closes nothing, `of(Path)` closes the stream it opened. **Closing is not flushing**, and for output that
distinction is load-bearing: bytes sit in a block until pushed, so a document never flushed is a document
never written, and a sink cannot tell a caller who finished from one who abandoned the write. Every writer
flushes explicitly. What stays smaller is the *target* set, not the contract: `Appendable` is a genuinely
different target rather than one spelled twice, so `toTson`'s char path is untouched.
**`io.ltr8.tson.base.unicode`** is the UCD 16.0 tables: `Xid`,
`IdentifierStatus`, `Confusables`, `ConfusableNames`, `JoiningControls`, `Nfc` — and the
UTS #39 rules over them, read by two engines and knowing nothing about either format. `UnicodePolicy` is
in `policy` rather than beside the tables it reads, because the line between the two Unicode packages is
**who touches them**: a consumer names `policy` to configure a processor and never names `unicode`; the
engines read `unicode` and never name `policy`.
**`IdentifierProfile` is here too**, beside the tables it reads: [TSON-DATA] §7.7's grammar (`validate`)
and §8.2's restricted-character rule (`hygiene`), both **reporting** a violation rather than throwing one.
That is what lets one check serve a caller that owes a parse error and one that owes a diagnostic — the
identical violation is a `ParseException` from the lexer and a refusal from the linker — where a signature
that threw forced the lexer's answer on everyone. It is not a parser: nothing here turns a token into a
host value, and the `identifier` atom is a wrapper over `validate` living with the rest of the vocabulary
(`atom.parser.IdentifierAtom`). A side effect worth having: `lexer` is now exactly `Lexer`, `LexException`,
`Token` and `TokenType`.
