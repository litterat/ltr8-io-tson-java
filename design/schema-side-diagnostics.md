# Schema-side diagnostics

How `TsonSchemaParser`, `SchemaDesugarer`, `SchemaResolver`, `TsonSchemaLinker` and `Tson.validateSchema` report every
independent problem with a schema in one pass, how each failure is classified, and the schema/bound-class agreement
check.

**Invariants**

- A parse that reported anything hands back no document at all; a declaration start is `name =>` back at schema-map
  depth, with depth the cursor's (`TsonDataStream.nesting()`).
- The fail-fast paths do not route through `DiagnosticsReceiver.throwing()`; they rethrow the original untouched.
- The resolver catches inside its memoized namespace getter (`SchemaResolver.OnDemand`), not around the driving loop.
- A failed declaration leaves an empty-record placeholder that keeps the declaration's own type parameters; a
  desugar-reported declaration is replaced with an `absorbed` stand-in, never passed through.
- A phase runs only if the one before it reported nothing, and a schema that reported anything is never registered —
  in `Tson.validateSchema` and in `TsonCompiledMetaRegistry.resolveLinked(uri, receiver)` alike.
- The exception's class picks the code: a gap is `NOT_IMPLEMENTED`, a bind mismatch (an unbindable target class
  included) `BIND_MISMATCH`, a refusal its §8.2 code; every `SchemaFailure` branch is a positive verdict and the default
  rethrows.
- A schema and its bound class must agree about a type's fields, at bind-mode compile; FIXED fields are exempt,
  optional ones are not.
- Namespace-level failures, the lexer, and a `BindMismatchException` at compile still throw.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/declared-positions.md`,
`design/record-dispatch.md`, `design/name-hygiene-read-path.md`, `design/diagnostic-model.md` (the codes,
`Code.verdict()`, `Code.isRefusal()`), `design/diagnostic-rules-and-messages.md`, `design/processor-policy.md`,
`design/cli-config-hashing.md` (exit codes).

## Schema-side diagnostics

A broken *schema* reports every independent problem in one pass, through the same `DiagnosticsReceiver` the read path
uses, populating the same four location components. [TSON-DATA] §8.1 asks for both halves: source position in **all**
error reports, and SHOULD "continue processing after an error to report multiple issues in a single pass" — and it puts
schema resolution and compilation failures in the *resolver error* category, so this is the same layer, not a new one.
The lexer stays fail-fast and is the floor under schema-parse recovery; `STRUCTURED-OUTPUT.md` holds that question.

### Parsing

`TsonSchemaParser.parseSchemaDocument(receiver)` is the recovering entry point beside the fail-fast
`parseSchemaDocument()`. Panic-mode recovery: a failed declaration is reported, its wreckage skipped, and parsing
resumes at the next declaration.

- **A declaration start is `name =>` back at schema-map depth, and nothing looser.** Two tokens decide it, which is
  exactly the lookahead `TsonDataStream` keeps. A bare name is most of a broken declaration's own wreckage and a leading
  `@` is equally a field annotation, so neither resyncs. An annotated declaration therefore resyncs at its *name* and
  the recovered node loses its annotations — harmless, since the document is discarded whole.
- **Depth is the cursor's (`TsonDataStream.nesting()`), not a counter the recovery keeps.** A declaration failing
  inside a record body leaves the cursor on *that record's* closing brace; a local counter starting at zero reads it as
  the schema map's own and stops one declaration in. The stream counts bracket pairs because every token passes
  through it. `<`/`>` are not counted — a stray one is skipped harmlessly where a miscount would not be.
- **A parse that reported anything hands back no document** (`Optional.empty()`), though the declarations around the
  broken ones parsed. Resolving a half-document reports every reference to a dropped declaration as unresolved, on top
  of the syntax error that is the real problem; §8.1's categories are per layer so that a layer's verdict is not
  second-guessed by the next. The surviving nodes exist only to keep parsing going.
- **What lies outside the declarations stays fail-fast**: the header, an empty map `{}`, content after the map's
  closing brace — there is no following declaration to resync on. And a token that will not lex raises `LexException`
  from underneath the recovery, since resynchronising means reading the very tokens that do not exist.
- **A schema syntax error locates itself at the schema end** (`TsonDiagnostics.ofSchemaSyntaxError`, the schema-side
  peer of `ofBaseSyntaxError`): `path`/`dataPosition` empty, the token's position in `schemaPosition` beside a `/name`
  pointer, so a syntax error and a resolution error against one declaration render alike. The code stays
  `VALIDATION_ERROR`. The `schemaId` is the document's `!!id` canonicalized to match every later phase's, **falling
  back to the id as written** when it does not canonicalize: that is a real error but the resolver's, and raising it
  from the grammar layer would swap a syntax diagnostic the author can act on for a complaint about a different line.
- **A parse failure names the construct the position admits, not the token class.** `TsonDataStream.expect` takes the
  construct in the author's voice (`"a record field's ':'"`) and `describe` prints the written token without its
  `TokenType`. The construct and the written token become the diagnostic's `expected`/`actual` (via `ParseException`);
  a throw site stating a *rule* — an adjacency violation, a trailing separator — leaves both `""`. `!` at a type-ref
  position (`quantity: !integer ^ { min: 1 }`) is rejected by name with the hoist-and-reference correction, like the
  size-spec and element-`?` rejections beside it.

### Resolving and linking

The reporting overloads are `SchemaResolver.resolveSchema(document, positions, receiver)` and
`TsonSchemaLinker.link(schema, loader, receiver[, identifiers[, values]])`; the receiver-less overloads throw at the
first problem. **The fail-fast paths do not route through `DiagnosticsReceiver.throwing()`** — that raises
`ReadException`, and a schema that fails to resolve is not a read failure; the CLI's exit 1 against 70 turns on the
distinction. They rethrow the original untouched (the linker re-states only an `UnresolvedReference`, below).

- **The resolver catches inside `SchemaResolver.OnDemand`, not around the driving loop.** Resolution follows
  dependencies, not source order, so a failure usually happens inside a *nested* resolve; catching at the loop would
  attribute it to whichever declaration triggered it and then report the real one a second time. The memo makes it
  exactly once, against itself — the shape `TsonSchemaCompiler.Compilation.resolve` has substituting an `ErrorReader`.
- **A failed declaration leaves an empty-record placeholder** (`SchemaResolver.unresolved`), so its dependents still
  resolve. That is javac's error-type contract (it answers every question) rather than Swift's (every questioner must
  check first), and the choice is load-bearing: a `Sum`-bodied placeholder makes `parent => child & { ... }` fail
  *because* `child` did, reporting a consequence beside its cause. Swift's other half is kept — producing one means a
  diagnostic was already reported, so it never escapes a reporting resolve and needs no `EntryKind` of its own.
  - **It keeps the declaration's own type parameters**, the one declaration-specific thing it carries. With the arity
    dropped, an application `bl<int32>` of a broken template `bl => <T> …` is told that `bl` "declares no type
    parameters … drop the argument list" — a fix that breaks the schema further, the real one being upstream. With the
    arity intact the application closes against the empty body and says nothing.
- **A template condemned by `TemplateRegularity` is replaced before materialisation**, on the same terms.
  `TemplateRegularity.check` returns the names it rejected and `SchemaResolver.condemn` puts a placeholder in both the
  entry map and the namespace (`materialise` walks the first, an application's head resolves through the second).
  Left in place, an application of one runs to `MAX_CLOSING_DEPTH` and reports the same defect again, against
  whichever entry applied it, with a 64-link chain of synthetic names. **The depth guard does not stand down**: it
  guards a hole in the static check, not a template the check already condemned.
- **A defect a held body deferred is reported against the declaration whose text wrote it**
  (`TsonSchemaLinker.heldDeclarationNaming`). A template's references cannot be settled until an application supplies
  arguments, so the verdict arrives on the entry materialisation minted, and the walk to a positioned entry
  (`reportedAgainst`) finds the *applier*: `box => <T> { v: T  w: no_such_type }` would be reported against
  `holder => { b: box<text> }`, a line that is not wrong, once per applier. Deferred checking is survivable only if the
  author is sent to the line they can edit.
  - **The offending name is the evidence, not the entry.** A derived entry's provenance cannot answer this: a sugar
    lift records only the constructor it applies, and an alias composes its argument into someone else's application
    (`half => <B> pair<no_such_type, B>` closes to a `pair` instantiation, and `pair` is faultless). Asking which held
    body *mentions the name* (`HeldBody.names()`) reaches `half` directly — and reaches nothing when the name came from
    the argument list, so `box<3>` and `box<some_typo>` keep their verdict at the application, where those mistakes are.
  - **Both filters are load-bearing.** Only a *derived* entry (no position of its own) is retargeted, or a closed
    declaration's own typo would be blamed on any template that names it; and only an authored open declaration
    (`OpenEntry.isOpen`) is a candidate. `HeldBody.names()` cannot tell a type reference from a field name, so it is
    asked only about a name already known to resolve to nothing; a field of that name misleads no worse than naming
    the applier does.
  - **The subject moves with the location**, which is why `UnresolvedReference` carries the sentence in parts
    (subject, trail, name): `'box<text>' field 'w'` states the mistake against an application that is itself correct.
    It is linker-internal and re-stated as a `SchemaValidationException` — not a subclass of it, since that type is
    sealed and lives in `tson-base`, which holds no pipeline machinery.
  - **One mistake gets one diagnostic** (`blamedOnce`), however many declarations apply the template; otherwise the
    count would be a property of the template's callers rather than of the defect.

### Desugaring

`SchemaDesugarer.desugar` takes a `DesugarFailureReporter` — a `(Declaration, RuntimeException)` callback rather than
a receiver, keeping the diagnostics vocabulary out of an AST-in/AST-out phase and diagnostic construction in
`SchemaResolver.Problems`, which alone holds the canonical id and the identity-keyed position table. It needs no
phase boundary of its own: it runs *inside* `resolveSchema`, so whatever it reports is already behind the caller's gate.

- **A reported declaration is replaced with an `absorbed` stand-in**, a zero-field record keeping the declaration's
  type parameters (the AST-level twin of `SchemaResolver.unresolved`). Passed through instead, it would hand
  `DefinitionResolver` the `ContainerTypeDef` the phase exists to remove, which it answers with an
  `UnsupportedOperationException` — reported as a second, `NOT_IMPLEMENTED` diagnostic claiming a gap where the author
  made an error.
- **A gap is reported and absorbed the same way**, as `NOT_IMPLEMENTED`.
- **Injected declarations are never rolled back.** Their names derive from the application, so [TSON-SCHEMA]
  §8.2's structural sharing means a later declaration may already reference one.

### The phase boundary

**`Tson.validateSchema(schemaText)` is the front door** — the schema-side peer of `validate`. Every declaration parses
before a verdict, and a document that did not parse whole is not resolved; every declaration then resolves, and
linking runs only if resolution was clean, so a schema with a broken declaration *and* an unresolved reference reports
the declaration alone (the reference may resolve once the declaration does). Then it registers and compiles in tree
mode. javac draws the boundary there (every entry is attributed before `shouldStopPolicyIfError` blocks the next
phase), and Swift never reaches SILGen after a Sema error. **A schema that reported anything is never registered.**

`TsonCompiledMetaRegistry.resolveLinked(uri, receiver)` composes the same three phases behind the same gates — it is
how a *data* read reports on the schema its `!!import` names — so `tson validate` and `tson compile` give the same
account of the same broken schema. It registers only a clean schema and otherwise returns `null`.

**What still throws even with a receiver** makes the namespace itself unusable rather than one entry wrong: an
`!!import` that will not load, a local declaration colliding with an imported name, a reference whose target owns a different
`!!id` than it was fetched under (§2.2.1's cross-check, `TsonCompiledMetaRegistry.crossCheckId`). Continuing would
report a page of unresolved references that are all consequences of the one problem. `Tson.validateSchema` catches
these and reports them against RFC 6901's root pointer (`""`), since they concern the document rather than a
declaration: a `SchemaValidationException` or `ContentHashMismatchException` as `SCHEMA_ERROR`, a
`SchemaFetchException` under its fetch code, a `BindMismatchException` as `BIND_MISMATCH`, a lex or parse failure
through `ofSchemaSyntaxError`; anything else rethrows as the library fault it is.

**Compilation reports nothing schema-side.** A reader that cannot be built — a constructor with no factory, a factory
that rejects the entry, a type with no bound class — becomes an `ErrorReader`, so the schema compiles and only a read
of that type fails (`NOT_IMPLEMENTED`, or the `MissingBindingException` itself). A `BindMismatchException` is
deliberately not deferred and throws at compile.

## Classification

**The code, not the channel, carries the exception policy.** `SchemaResolver.Problems.report` picks it by the
exception's class: `SchemaValidationException` → `SCHEMA_ERROR` (the author's mistake), `UnsupportedOperationException`
→ `NOT_IMPLEMENTED` (a construct beyond this library), `BindMismatchException` → `BIND_MISMATCH` (the reading
application's wiring, `ofSchemaBindMismatch`), and `SchemaRefusalException` — a `SchemaValidationException` subtype —
keeps its [TSON-DATA] §8.2 refusal code (`ofSchemaRefusal`). The test for author error against gap: *a schema
error's verdict doesn't change when this library improves; a gap's does.*

- **Why not the channel.** Throwing a gap out of a phase that reports per declaration takes every other declaration's
  verdict with it: one unimplemented construct and a document with three ordinary mistakes reports none of them. A
  code says "not a verdict on your schema" as well as a channel would, and lets the pass stay single. The CLI's exit
  code rides on the codes alone (`TsonCli.exitCodeFor`); a gap that escapes some other way still exits 70 through
  `TsonCli.notImplemented`.
- **A bind mismatch is neither an author error nor a gap.** `DefinitionResolver.bindAtomInstance` and
  `bindAnnotationValue` keep it clear of their catch-alls; relabelled `UnsupportedOperationException`, a missing line
  of wiring would read as *this library cannot do that*. The reachable case is a meta layer's own record the consumer
  bound no class for (`pings => !notifications { ping => … }` with none for `notification`), reported at the entry;
  `BindMismatchClassificationTest` pins it beside an annotation typed by an abstract base kind (`@sum:_`), which is
  the author's error in both §6 positions.
- **A read that cannot get its schema classifies the same way (`SchemaFailure`).** Both facades reach their schema
  through one call that resolves, links *and* compiles, so every failure arrives at one `catch`:
  `BindMismatchException` (with its `MissingBindingException` subclass) is `BIND_MISMATCH`,
  `UnsupportedOperationException` `NOT_IMPLEMENTED`, `SchemaFetchException` its fetch code via `Code.of`, and
  `SchemaValidationException` and `ContentHashMismatchException` `SCHEMA_ERROR` — the last because something arrived
  and is not what the reference pinned. Each branch carries the `expected` matching its code. Once the failure is a
  `Diagnostic` there is no exception type left to classify on, only the code — and a consumer choosing an HTTP status
  wants the sender's problem, its own wiring and this library apart.
- **Every branch is a positive verdict and the default rethrows**, the rule `ofBaseSyntaxError` ends on: a library
  fault propagates as itself. `SchemaSource.fetch` naming `SchemaFetchException` as the one way a source says "cannot
  supply this" is what makes that possible; a source failing any other way is malfunctioning, and `Tson.validate`
  promises only that a bad *document* never throws.
- **An unbindable target class is `BIND_MISMATCH`, not `SCHEMA_ERROR`.** A class `ltr8-bind` cannot analyse is the
  reading application's misconfiguration and says nothing about the document; both encodings' class-driven readers
  (`DataClassObjectReader`) report it so.

## Bind agreement

**A schema and the class bound to it must agree about a type's fields**, and the check is where they meet rather than
where a document is read: both halves are fixed when a reader is built, so a mismatch is a `BindMismatchException` at
bind-mode compile — startup, for anything compiling its schemas once. **The class must be able to hold what the schema
declares.** `RecordBindReader` refuses four disagreements; `BindTargets` carries the rule below a field.

- **Any non-FIXED field with no component** is refused, optional ones included. Leaving OPTIONAL to the read that
  writes one is the tempting split and the worse trade: an optional field is exactly the one that works in development
  and fails the first time a caller sends it.
- **A FIXED field is exempt**: the schema settles the value, so a component would hold a constant. The exemption is
  what makes strictness possible at all: the bundled binding's own classes leave FIXED fields such as
  `access_pattern`, `size_type` and an atom's `spec` without components, by design.
- **A component no field fills** is refused too: it reaches the constructor as `null` on every document. `@Unbound`
  says a component is the class's own and not the wire's (`RecordField.position`, this implementation's addition for
  diagnostics).
- **An atom field** is refused when its component binds structurally, or when its family cannot produce the class the
  component binds.
- **The rule bites the library first**: `datetime_type` and `time_type` declare `precision`, so `DateTimeType` and
  `TimeType` carry it and their parsers enforce it. §5.5 makes it a constraint on the value — a whole number of 10⁻ᴺ
  seconds — not on the spelling, so `12:00:00.500` is admitted at `precision: 1`; nothing is truncated to satisfy it
  (`FractionalSeconds`).
- **The class is the target at every depth.** A collection's element, a map's key and value and a tuple's position are
  bound to what the component's class declares there, recursively (`reader.BindTargets`, the rule `tson-json`'s class
  of that name states too): an atom narrowed to the element's wire class, a nested container rebuilt for its declared
  class, a record checked against it. Without it a `List<Long>` would hold the `Integer`s an `int32` element reads to,
  which erasure lets through until a consumer unboxes one. A container with no component above it is built from what
  its schema type binds and is never checked against that guess.
- **A position that can deliver no value cannot bind to a primitive.** A voidable field, tuple position or array
  element may be void, and a field that yields nothing when left out (optional with no default, or a field group's
  member — `RecordField.omitted`) may be missing; either reaches the constructor or array as `null`. A defaulted field
  binds to a primitive, since leaving it out injects the default. A collection that refuses `null`
  (`ConcurrentHashMap`, `ArrayDeque`) is not refused here, since no JDK type says so; the read that meets one reports it
  (`design/readers-and-diagnostics.md`).
- **A tuple field binds to its component's own `@Tuple` class**, which must have the tuple's arity
  (`TupleBindReader.disagreements`). An inline tuple's minted entry has no author binding and no natural Java form, so
  its own reader is unbound (`TupleBindReader.unbound`, raising `MissingBindingException` if read directly) and
  `BindTargets` builds it into the component's class.
- **An ordered map needs a component that keeps insertion order** (`DataClassMap.ordered()`: a `SequencedMap` that is
  not a `SortedMap`, or `AnnotatedMap`); a bare `Map` is built as a `HashMap` and does not. One way only: an unordered
  map binds to either kind. Both encodings check it at every depth (`BindTargets`; `BindMapBuilder.forTarget` in
  `tson-json`), and a map read with no component to declare a class is built as a `LinkedHashMap` when ordered.
- **A `Namespace` component binds a namespace type's map, and nothing else**: the map is read for the component's
  `entries` and delivered as an instance of the constructor the position names. A map that is no namespace has no
  constructor to name; a `Map` component at a namespace type receives the map.
- **A type with no class to build is deferred to its first read** — the `MissingBindingException` subclass — since a
  schema legitimately declares types a consumer never binds. It covers a name the binder resolves to no class
  (`ltr8-bind`'s `UnboundNameException`) and a class that cannot be analysed (the kernel's base kinds map to sealed
  marker interfaces nothing builds); the message tells them apart.
- **There is no wholesale opt-out.** Accepting fewer fields without saying *which* is the defect [TSON-SCHEMA] §7.2
  refuses on the wire (an unrecognised field); a class meant to read one version of a schema while another is
  current declares a `@Profile` constructor. Reaching a read as a diagnostic (a schema compiled on demand), it keeps
  `BIND_MISMATCH`.
- **Strict is the only mode because the two ways of being wrong are not symmetric.** A strict reader that is wrong says
  so at startup, once, naming both sides; a lenient one drops a value from every document and surfaces later as a
  field mysteriously holding its default. Nor could a lenient mode report what it dropped: reporting abandons the
  construction (`ConstructionGuard`), and a diagnostic the guard is told to ignore is a severity axis, which
  [TSON-DATA] §8.1 does not have.
