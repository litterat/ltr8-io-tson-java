# Schema-side diagnostics

How `TsonSchemaParser`, `SchemaDesugarer`, `SchemaResolver`, `TsonSchemaLinker` and `Tson.validateSchema` report every
independent problem with a schema in one pass, how failures are classified, and the schema/bound-class agreement check.
Current form only; history lives in git.

**Invariants**

- A parse that reported anything hands back no document at all; a declaration start is `name =>` back at schema-map
  depth, with depth the cursor's (`TsonDataStream.nesting()`).
- The fail-fast paths do not route through `DiagnosticsReceiver.throwing()`; they rethrow the original untouched.
- The resolver catches inside its memoized namespace getter (`SchemaResolver.OnDemand`), not around the driving loop.
- A failed declaration leaves an empty-record placeholder that keeps the declaration's own type parameters; a
  desugar-reported declaration is replaced with an `absorbed` stand-in, never passed through.
- `Tson.validateSchema` owns the phase boundary, and a schema that reported anything is never registered.
- A gap is a diagnostic under its own code (`NOT_IMPLEMENTED`), a bind mismatch — an unbindable target class
  included — `BIND_MISMATCH`; every
  `SchemaFailure` branch is a positive verdict and the default rethrows.
- A schema and its bound class must agree about a type's fields, at bind-mode compile; FIXED fields are exempt,
  optional ones are not.
- Namespace-level failures, compilation, and the lexer under everything still throw.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/scope-push.md`,
`design/record-dispatch.md`, `design/name-hygiene-read-path.md`, `design/diagnostic-model.md`,
`design/diagnostic-rules-and-messages.md`, `design/processor-policy.md`.

## Schema-side diagnostics (`TsonSchemaParser`, `SchemaResolver`, `TsonSchemaLinker`, `Tson.validateSchema`)

A broken *schema* reports every independent problem in one pass, through the same
`DiagnosticsReceiver` the read path uses. §8.1 asks for both halves of this: implementations MUST carry
source position in **all** error reports, and SHOULD "continue processing after an error to report multiple
issues in a single pass" — and it explicitly puts schema resolution/compilation failures in the *resolver
error* category, so this is the same layer, not a new one.

**Nothing is outstanding here; what follows is the boundary.** Parsing, desugaring, resolution and linking all report
through a `DiagnosticsReceiver`, and read- and schema-side diagnostics populate the same four location components.
Throw-site classification is done across the whole schema pipeline. The lexer stays fail-fast on purpose and is the
floor under schema-parse recovery — not a tracked gap; `STRUCTURED-OUTPUT.md` holds the open question.


- **Parsing reports too, per declaration** — `TsonSchemaParser.parseSchemaDocument(receiver)` is the
  recovering entry point beside the fail-fast `parseSchemaDocument()`; without a receiver nothing changes.
  Panic-mode recovery: a failed declaration is reported, its wreckage is skipped, and parsing resumes at the
  next declaration.
  - **A declaration start is `name =>` back at schema-map depth, and nothing looser.** Two tokens decide it
    unambiguously, which is exactly the lookahead `TsonDataStream` keeps. A bare name is most of a broken
    declaration's own wreckage and a leading `@` is equally a field annotation, so neither resyncs. The cost
    is that an annotated declaration resyncs at its *name* and the recovered node loses its annotations —
    harmless, since the document is discarded whole.
  - **Depth is the cursor's (`TsonDataStream.nesting()`), not a counter the recovery keeps.** A declaration
    failing inside a record body leaves the cursor on *that record's* closing brace; a local counter starting
    at zero reads it as the schema map's own and stops one declaration in. The stream counts bracket pairs as
    tokens are consumed, being the one place every token goes through. `<`/`>` are not counted — a stray one
    is skipped harmlessly where a miscount would not be.
  - **A parse that reported anything hands back no document at all** (`Optional.empty()`), even though the
    declarations around the broken ones did parse. Resolving a half-document reports every reference to a
    dropped declaration as unresolved, on top of the syntax error that is the real problem; §8.1's categories
    are per layer precisely so a layer's verdict isn't second-guessed by the next. The surviving nodes exist
    only to keep parsing going.
  - **Two failures stay fail-fast.** A malformed *header* has no following construct to resync on — the
    schema map hasn't started. And the **lexer is the floor**: a token that won't lex raises `LexException`
    from underneath the recovery, since resynchronising means reading the very tokens that don't exist
    (`STRUCTURED-OUTPUT.md` tracks that layer).
- **A schema syntax error locates itself at the schema end** (`TsonDiagnostics.ofSchemaSyntaxError`), the
  schema-side peer of `ofBaseSyntaxError`: `path`/`dataPosition` empty, the token's position in
  `schemaPosition` beside a `/name` pointer, so a syntax error and a resolution error against the same
  declaration render identically. The code stays `VALIDATION_ERROR` — *where* the problem is found is what
  the four location components are for. The `schemaId` is the document's own `!!id` canonicalized so it
  matches every later phase's, **falling back to the id as written** when it doesn't canonicalize: that is a
  real error but the resolver's to report, and raising it from the grammar layer would swap a syntax
  diagnostic the author can act on for a different complaint about a different line.
- **A parse failure names the construct the position admits, not the token class.** `TsonDataStream.expect`
  takes that construct in the author's voice (`"a record field's ':'"`), and `describe` prints the written
  token without its `TokenType` — `expected UNQUOTED (a type reference), found '!' (BANG)` spent both halves
  on parser vocabulary. The construct and the written token also become the diagnostic's `expected`/`actual`
  (via `ParseException`) in place of a constant pair that says nothing; a throw site stating a *rule* rather than a
  substitution — an adjacency violation, a trailing separator — leaves both `""` and nothing invents a pair. **One position
  names the fix outright:** `!` at a type-ref position (`quantity: !integer ^ { min: 1 }`, the natural first attempt) is
  rejected by name with the hoist-and-reference correction, the same shape as the size-spec and element-`?` rejections beside
  it.
- **Both callers parse this way**, so `tson validate` and `tson compile` give the same account of the same
  broken schema: `Tson.validateSchema` and `TsonCompiledMetaRegistry.resolveLinked(uri, receiver)` — the
  latter being how a *data* read reports on the schema its `!!schema` names.
- **Two reporting overloads, `SchemaResolver.resolveSchema(document, positions, receiver)` and
  `TsonSchemaLinker.link(schema, loader, receiver)`.** The receiver-less overloads throw at the first
  problem. **The fail-fast paths deliberately do not route through
  `DiagnosticsReceiver.throwing()`** — that raises `ReadException`, and a schema that fails to
  resolve is not a read failure; the CLI's exit 1 against exit 70 turns on the distinction. They rethrow the
  original untouched.
- **The resolver catches inside its memoized namespace getter (`SchemaResolver.OnDemand`), not around the
  driving loop.** Resolution follows dependencies, not source order, so a failure usually happens inside a
  *nested* resolve; catching at the loop would attribute it to whichever declaration triggered it and then
  report the real one a second time. The memo makes it exactly once, against itself. Same shape as
  `TsonSchemaCompiler.Compilation.resolve` substituting an `ErrorReader` one phase later.
- **A failed declaration leaves an empty-record placeholder**, so its dependents still resolve. That is
  javac's error-type contract (it answers every question) rather than Swift's (every questioner must check
  first), and the choice is load-bearing: a `Sum`-bodied placeholder makes `parent => child & { ... }` fail
  *because* `child` did, reporting a consequence beside its cause. Swift's other half is kept — producing one
  means a diagnostic was already reported. It never escapes a reporting resolve, so it needs no `TypeKind`
  of its own.
    - **It keeps the declaration's own type parameters**, which is the one declaration-specific thing it
      carries. Answering every question is not answering them all with nothing: with the arity dropped, an
      application `bl<int32>` of a broken template `bl => <T> …` is told that `bl` "declares no type
      parameters … drop the argument list" — a fix that would break the schema further, the real one being
      upstream. With the arity intact the application closes against the empty body and says nothing.
- **A template condemned by `TemplateRegularity` is replaced before materialisation**, on the same terms.
  `check` hands its caller the names it rejected and `SchemaResolver` substitutes a placeholder in both the
  entry map and the namespace (the two are read by different halves — `materialise` walks the first, an
  application's head resolves through the second). Left in place, an application of one runs to
  `MAX_CLOSING_DEPTH` and reports the same defect a second time, against whichever entry applied it and
  carrying a 64-link chain of synthetic names the author never wrote. **The depth guard itself does not
  stand down**: what it guards is a hole in the static check, not a template the check already condemned.
- **A defect a held body deferred is reported against the declaration whose text wrote it**
  (`TsonSchemaLinker.heldDeclarationNaming`). A template's references cannot be settled until an application
  supplies arguments, so nothing checks them at the declaration; the verdict arrives on the entry
  materialisation minted, and the walk to a positioned entry finds the *applier*. So
  `box => <T> { v: T  w: no_such_type }` would be reported against `holder => { b: box<text> }` — a line that is
  not wrong and does not contain the name — once per applier, each naming a different application. Deferred
  checking is what holding buys, and it is survivable only if the author is sent to the line they can edit.
    - **The offending name is the evidence, not the entry.** Walking a derived entry's own lineage cannot
      answer this: a sugar lift's `source` is the bare constructor it applies, and an alias composes its
      argument into someone else's application (`half => <B> pair<no_such_type, B>` closes to a `pair`
      instantiation, and `pair` is faultless). Asking which *held body mentions the name* reaches `half`
      directly — and reaches nothing when the name came from the argument list, so `box<3>` and
      `box<some_typo>` keep their verdict at the application, which is where those two mistakes are.
    - **The subject moves with the location**, which is why `UnresolvedReference` carries the sentence in
      parts (subject, trail, name) rather than finished: `'box<text>' field 'w'` states the mistake against
      an application that is itself correct. It is linker-internal and never escapes — re-stated as a
      `SchemaValidationException`, whose classification it shares — because that type is deliberately
      `final` and lives in `tson-base`, which holds no pipeline machinery.
    - **Both filters are load-bearing.** Only a *derived* entry is retargeted, or a closed declaration's own
      typo would be blamed on any template that happens to name it; and only a `TemplateBody` declaration is
      a candidate, since a defect no held body deferred is already located correctly. `HeldBody.names()`
      cannot tell a type reference from a field name, which is why it is asked only about a name already
      known to resolve to nothing — a field of that name is the one remaining way to mislead it, and it
      misleads no worse than naming the applier does.
    - **One mistake gets one diagnostic**, however many declarations apply the template: each application
      mints its own entry and each fails identically, so the count would otherwise be a property of the
      schema's callers rather than of the defect.
- **`Tson.validateSchema(schemaText)` is the front door and owns the phase boundary** — the schema-side peer
  of `validate`, and the only caller that composes all three phases. Every declaration parses before a
  verdict and a document that didn't parse whole is not resolved at all; every declaration then resolves
  before a verdict, and linking runs only if resolution was clean, so a schema with a broken declaration
  *and* an unresolved reference reports the declaration alone (the reference may well resolve once the
  declaration does). This is where javac and Swift both draw it: javac attributes every entry before
  `shouldStopPolicyIfError` blocks the next phase, Swift never reaches SILGen after a Sema error. **A schema
  that reported anything is never registered.**
- **A schema and the class bound to it must agree about a type's fields**, and the check is where they meet
  rather than where a document is read — both halves are fixed by the time a reader is built, so a mismatch
  is a `BindMismatchException` at bind-mode compile, which is startup for anything compiling its schemas
  once. **One rule: the class must be able to hold what the schema declares**, with a single exemption:
    - **Any non-FIXED field with no component** → refused at compile, optional ones included. Leaving
      OPTIONAL to the read that writes one is the tempting split and the worse trade: an optional field is
      exactly the one that works in development and fails the first time a caller sends it, so deferring it
      reports the hardest mismatch to find at the moment it has already gone wrong.
    - **A FIXED field** → exempt. The schema settles the value, so a component would hold
      a constant. **This exemption is what makes strictness possible at all**: 21 of the mismatches in this
      library's own bundled binding are FIXED fields (`access_pattern`, `size_type`, an atom's `spec`).
    - The rule bites the library first, which is the point: `datetime_type` declares `precision`, so
      `DateTimeType`/`TimeType` carry it and their parsers enforce it — §5.5 makes it an upper bound on the
      fractional-second digits of the token *as written*, checked textually because the atoms are exact and
      nothing is ever truncated to satisfy a facet (`FractionalSeconds`).
    - The converse — a component no field fills — is refused at compile too: it reaches the constructor as
      `null` on every document. `@Unbound` is how a class says a component is its own and not the wire's,
      needed exactly once here (`TypeDefinition.position`, this implementation's own addition for
      diagnostics).
    - **The check is raised at bind-mode compile — startup, not first read — except for a type with no class at
      all.** That is the `MissingBindingException` subclass, deferred to the first read of that type, since a schema
      legitimately declares types a consumer never binds.
    - **There is no wholesale opt-out**, and that is deliberate: accepting fewer fields without saying *which* is the
      defect §7.2 refuses on the wire, and a class that means to read one version of a schema while another is current
      declares a `@Profile` constructor for it. Reaching a read as a diagnostic instead (a schema compiled on demand),
      it keeps its own code, `Diagnostic.Code.BIND_MISMATCH` — a misconfiguration in the reading application is no more
      a verdict on the document than a gap is.
    - **Strict is the only mode because the two ways of being wrong are not symmetric.** A strict reader that
      is wrong says so at startup, once, naming both sides; a lenient one that is wrong drops a value from
      every document and surfaces later as a field mysteriously holding its default. A lenient mode could not
      report what it dropped either: reporting abandons the construction (`ConstructionGuard`), so a lenient
      reader that reported would return `null` for exactly the documents it exists to accept — and a
      diagnostic the guard is told to ignore is a severity axis under another name, and [TSON-DATA] §8.1
      states there is no such axis: a conforming processor has one severity.
    - **An unbindable target class is `BIND_MISMATCH`, not `SCHEMA_ERROR`.** A class `tson-bind` cannot analyse
      is a misconfiguration in the reading application and says nothing about the document — the distinction
      `Code.verdict()` exists to carry, and the same line `BindMismatchException` draws at compile time.
      Reporting it as `SCHEMA_ERROR` would tell a caller routing on the answer that the document is wrong when
      nothing has looked at it. Both encodings' class-driven readers report it the same way.
- **A gap becomes a diagnostic too, under its own code.** Both `SchemaValidationException` and
  `UnsupportedOperationException` are reported per declaration; the code is what tells them apart —
  `SCHEMA_ERROR` for the author's mistake, `NOT_IMPLEMENTED` for a construct beyond this library. The test
  for which is which is Swift's treatment of `expression_too_complex`: *a schema
  error's verdict doesn't change when this library improves; a gap's does.*
    - **Why the code and not the channel is the distinction.** Throwing a gap out of a phase that reports per
      declaration takes every other declaration's verdict with it: one unimplemented construct, and a
      document with three ordinary mistakes reports none of them, so the author fixes one thing per run.
      The policy's substance is that a gap is not a verdict on the author's schema, and a code carries that
      as well as a channel would — while letting the pass stay single, which is the property the whole
      schema-diagnostics design exists for. `SchemaResolver.Problems` is where the schema pipeline
      classifies, and `TsonCli.exitCodeFor` is what the CLI's exit code rides on.
    - **It classifies three ways, not two.** A `BindMismatchException` is neither an author error nor a
      gap, and reaches `ofSchemaBindMismatch` — the same answer `SchemaFailure` gives a read, for the same
      reason. Both throw sites keep it clear of their catch-alls (`bindAtomInstance` and
      `bindAnnotationValue`, which carry the same arm): relabelling it `UnsupportedOperationException`
      rebuilds the shape `MissingBindingException` exists to retire, a missing line of wiring reading as
      *this library cannot do that*. An annotation naming a type the consumer never bound — the kernel's own
      `data` among them — is the reachable case, and `BindMismatchClassificationTest` pins it in both §6
      positions plus the constructor case beside them.
    - A gap that escapes some *other* way still throws and still exits 70 unchanged — compilation and the
      lexer are fail-fast, and `TsonCli.notImplemented` remains for anything that reaches it.
- **A read that cannot get its schema classifies the failure the same way (`SchemaFailure`).** Both facades
  reach their schema through one call that resolves, links *and* compiles, so every way any of those can
  fail arrives at a single `catch` — and coding them all `SCHEMA_ERROR` says "the author's schema is wrong"
  about two failures that are nothing of the kind. `BindMismatchException` (its
  `MissingBindingException` subclass included) is `BIND_MISMATCH`: the schema is fine, the class is
  fine, and the reading application pointed them at each other by mistake, so the message names one of
  *its* classes and the document may be perfectly valid. An `UnsupportedOperationException` is
  `NOT_IMPLEMENTED`, the same code a gap gets everywhere else. A `SchemaFetchException` is one of the
  five `SCHEMA_*` codes, by its own `Reason`: no source would supply the schema, so it was never read, and
  whether it would even have resolved is unknown — `SCHEMA_ERROR` would claim a verdict on a document
  nothing here has seen. A
  `ContentHashMismatchException` *is* `SCHEMA_ERROR`, and the pair marks the line: something arrived,
  and it is not what the reference named. Each branch carries the `expected` that matches its code ("a
  schema that can be obtained", "a schema matching its `?sha256=` pin", "a resolvable schema"), and the
  fetch branch's code is the exception's own `Reason`, mapped by `Code.of` — the classification is the last
  place that still holds the exception, so what it drops is dropped for every collecting read.
    - **This is `NOT_IMPLEMENTED`'s argument one step further out**: a bind mismatch is no more a verdict on
      the document than a gap is, and once the failure arrives as a `Diagnostic` there is no exception type
      left for a consumer to classify on — only the code. A consumer choosing an HTTP status wants the three
      apart (the sender's problem, its own wiring, this library); one code gives it none of that, and
      matching on message text is the alternative it should not be pushed to.
    - **Every branch is a positive verdict and the default rethrows**, the same rule `ofBaseSyntaxError`
      ends on: a library fault propagates as itself. What makes that possible is `SchemaSource.fetch`
      naming `SchemaFetchException` as the one way a source says "cannot supply this" — with no mandated
      type, an `IllegalStateException` arriving here is equally a source's miss or a broken invariant, and
      either every fault reads as a bad schema or every source that spells a miss that way crashes the read.
      A source failing any other way is that source malfunctioning, and surfaces as the exception it threw:
      `Tson.validate` promises a bad *document* never throws, and a bad *source* is not a document.
    - **Eight codes are not a verdict on the document** (`Code.verdict()`), and they differ in *who* could
      not give one: `NOT_IMPLEMENTED` (this library), `BIND_MISMATCH` (the reading application), the five
      `SCHEMA_*` codes (whoever was to serve the schema), and `LIMIT_EXCEEDED` (this reader's own §9.1 bound,
      `design/processor-policy.md`). The CLI's exit codes follow — 70, 78, and 69 or 75
      by whether a rerun could help, a limit refusal exiting 1 because the runner can act — and a mixed run ranks by who must
      act first, permanence breaking the tie between ranks where nobody present can act: 70 > 78 > 69 > 75 > 1.
- **What still throws even with a receiver:** an `!!import` that won't load, a `!!meta` that may not
  govern, or a reference whose target owns a different `!!id` than it was fetched under (§2.2.1's
  cross-check, `TsonCompiledMetaRegistry.crossCheckId`). Those make the namespace itself unusable rather
  than one entry wrong, and continuing would report a page of unresolved references that are all
  consequences of the one real problem. Each is a `SchemaValidationException` — an authoring or
  publishing error, not a library fault, which is what lets `Tson.validateSchema` catch them and report
  against RFC 6901's root pointer (`""`), since they concern the document rather than any declaration, and
  what keeps the CLI's exit 1 apart from exit 70.
- **Desugaring reports too, and needs no gate of its own.** `SchemaDesugarer.desugar` takes a
  `DesugarFailureReporter` — a `(Declaration, SchemaValidationException)` callback rather than a receiver,
  keeping the diagnostics vocabulary out of a phase whose whole shape is AST-in/AST-out, and keeping
  `TsonDiagnostics.ofSchemaError` construction in `SchemaResolver`, which alone holds the canonical id and the
  identity-keyed position table. It needs no phase boundary because it runs *inside* `resolveSchema`, so
  whatever it reports is already behind the gate the caller checks. A reported declaration is replaced with
  an `absorbed` stand-in (a zero-field record keeping the declaration's type parameters, the AST-level
  twin of `SchemaResolver.unresolved`) — **not passed
  through**, which would hand `DefinitionResolver` the very `ContainerTypeDef` the phase exists to remove and
  draw an `UnsupportedOperationException` the resolver deliberately doesn't catch, turning a reported author
  error into an unreported abort. Injected declarations are never rolled back: names are derived from the
  application, so §8.2's structural sharing means a later declaration may already reference one.
- **Still fail-fast:** compilation, and the lexer under everything. Compilation already keeps going via
  `ErrorReader`, but that marks a *library gap* (an unregistered atom factory), which is a different question
  from an author error.
