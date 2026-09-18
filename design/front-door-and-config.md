# Front door: `Tson`, `ProcessorConfig`, and schema sources

Design notes for the consumer entry point — `Tson` (`tson` module) over `ProcessorConfig` (`tson-base`): the
binding seams, `DataBindContext` construction, the shipped `SchemaSource`s with `SchemaAccess`/`FetchPolicy`,
the registries a `Tson` holds, and `validate`. Current form only; history lives in git.

**Invariants**

- The schema-to-class agreement check has no opt-out; `@Unbound` and a `@Profile` constructor are the two
  narrow answers.
- A `DataBindContext`'s configuration closes when it is built; `registerAtom(Class)` asks
  `DefaultClassBinder.atomForm` first and falls back to a bare `DataClassAtom` — the order is the point.
- Both schema sources deny by default and match a host exactly; the file source checks containment after
  `toRealPath`, the HTTP one never follows redirects and caps bytes delivered.
- A source signals "cannot supply this" with `SchemaFetchException` and nothing else; a `null` return is a
  fault (`IllegalStateException`), never a miss.
- The loader, not a source, verifies the `?sha256=` pin and the fetched `!!id`; caching is by canonical
  identity, and policy is re-checked on every reference, cached or not.
- The read mode is which registry you hold; `resolve` takes no mode, and every reader a `Tson` makes shares
  its one compiled-schema cache.
- `validate` never throws for a bad input document, and `TsonDiagnostics.ofBaseSyntaxError` rethrows
  anything that is not a base-syntax failure.
- Two binding seams, never merged: `withDataBindContext` binds data, `withMetaNameBinder` binds a meta's own
  vocabulary, composed over `SchemaMetaNameBinder.INSTANCE`.

Related: `design/facades-and-tree.md` (the readers a `Tson` hands out), `design/writers-and-document-header.md`
(`TsonDocumentPeek`, the writers), `design/tree-model.md`, `design/linking-and-compilation.md` (registries, the
`data` base kind), `design/cli-config-hashing.md`.

## Front door: `Tson` (`tson` module) and `ProcessorConfig` (`tson-base`)

A small module over `tson-compiler`, the consumer entry point. `Tson.standard()` bootstraps
meta-kernel/meta.tn/core.tn into a governed environment and returns an immutable `Tson`.

```java
Tson tson = Tson.standard();
tson.resolve(schemaText);                      // registers the schema by its own !!id
TsonValue value = tson.treeReader().withSchema(schemaId).readAs(dataText, "my_type");
```

`ProcessorConfig.withDataBindContext` says which Java classes the schema's types bind to. **The vocabulary
for building a context is `tson-bind`'s, not `ProcessorConfig`'s** — `DataNameBinder.ofMap(map)` over
`DataBindContext.builder().registerAtoms(AtomContext.hostTypes())`, with `orElse` composing a caller's
names over the kernel's own — so the front door has no binding or profile setters restating it.
**Strictness is configuration and not a reader derivation**, which is a fact about when
the check can run rather than a preference: it compares a *compiled* schema against a class, so a
reader derived afterwards has no answer left to give. Which field a *document* may carry beyond its
class is the different question `ignoringUnknownFields` asks, per reader, at read time.

- **The schema-to-class agreement check has no opt-out, and that is the point.** What a blanket opt-out
  allows is the defect: a v1 class reading a v2 schema keeps `sku` and `quantity` and drops `currency`
  **without naming it** — accepting fewer fields while saying only how many, which is exactly what
  [TSON-SCHEMA] §7.2 refuses on the wire and for the same reason, since a field you cannot name may change
  what the fields you do read mean.
  - **The two narrow answers say which.** `@Unbound` marks the one component the class owns rather than the
    wire's; a `@Profile` constructor states the shape a class takes for one version of a schema while
    another is current. Both name what they are doing; a blanket flag cannot.
  - The check is unconditional, so no strictness setting threads through `ValueReaderFactoryRegistry.bind`,
    `RecordBindReader.Factory`, the reader's constructor or `TsonCompiledSchemaRegistry`.

- **A `DataBindContext`'s configuration closes when it is built.** `registerAtom` is a
  `DataBindContext.Builder` method, applied once inside the constructor on the thread that builds. A
  registration method on the built context would admit two hazards: a registration racing another, and a
  registration arriving after `getDescriptor` had already handed out a descriptor for that class, which no
  check can undo. Neither needs a guard, because the API cannot express the call. What is a race, and is
  tested, is memoization: `getDescriptor` resolving one class on two threads does the work twice and
  `putIfAbsent` settles which answer everyone sees. Duplicated work, never duplicated state.
  - **What makes an atom an atom is a bridge to a type the wire can carry**, and `registerAtom(Class)` finds
    the one the class declares rather than replacing it: it asks `DefaultClassBinder.atomForm` first —
    `@Atom` on a constructor or static factory, or a plain enum's own `name()` crossing — and falls back to
    a bare `DataClassAtom` only for a class that declares none. The bare form asserts "one scalar" and
    supplies nothing that makes it one, so it is right exactly for a class the *encoding* already knows: the
    JDK scalars, and the vocabulary's host types (`UUID`, `LocalDate`, `Inet4Address`, …). `tson-bind` cannot
    check which those are, knowing nothing of any encoding, so a class that is neither is accepted here and
    refused by the reader or writer that meets it. **The order is the point**: writing the bare form
    unconditionally would settle the class in the descriptor cache, short-circuiting analysis permanently, so
    an explicit registration would be strictly worse than none. A component type is admitted when both encodings
    carry it directly — the primitives and boxes, `String`, and `BigInteger`/`BigDecimal`, §5.3's own exact
    tiers.
  - **`registerAtoms(List)` and `AtomContext.hostTypes()` are why this reads well.** The vocabulary is a
    named list rather than a chain of eleven calls, so a caller adds it to their own builder in the order
    things happen — `DataBindContext.builder().nameBinder(binder).registerAtoms(AtomContext.hostTypes())` —
    instead of wrapping their builder in a helper that returns it. And a test asserting a context carries
    the vocabulary asserts against that list rather than a second copy of it.
  - **`DataNameBinder.ofMap` and `orElse`** are the same shape one layer along. `ofMap` is the map lookup
    done to contract, where `map::get` returns `null` for whichever name the *document* chose and a `null`
    carries no account of why — `SchemaSource.ofMap`'s own reason. `orElse` composes a caller's names over
    the kernel's vocabulary and settles which binder authors the failure: the caller's, because a name
    neither knows is a missing line of *their* configuration, where "not kernel vocabulary" is the
    backstop's answer and no help at all.

- **Two schema sources ship, and `SchemaAccess` carries the short form of each.** `HttpSchemaSource`
  fetches over HTTPS under a host allow-list; `FileSchemaSource` reads from a directory.
  `SchemaAccess.httpSchemas(…)` and `SchemaAccess.fileSchemas(host, dir)` are the one-call forms, with
  `SchemaAccess.builder()`'s repeatable counterparts accumulating into one source; `SchemaAccess.of(source)`
  stays the general seam and the three are mutually exclusive — each builds one source, so mixing them would
  drop one rather than compose it. A deployment
  needing both writes the composition itself, where the order it tries them in is stated rather than
  assumed. **`ProcessorConfig` carries none of that vocabulary**: its one schema setter is `withSchemaAccess`, so the
  ways of naming a source are learnt once and the exclusion rules among them are stated once — a second copy
  at the front door would be a second surface to keep in step.
  - **Identity is not location, and that is what makes two sources one design** ([TSON-DATA] §2.2.1). A
    reference's identity is its lowercase host plus path — the scheme "a transport hint, not part of the
    name", no port, no userinfo, no fragment — so `https://schemas.example.com/order-1.tn` may legitimately
    be served from a directory, and moving a schema between the two renames nothing. `SchemaReference`
    holds those rules once, for both: two sources enforcing them separately is two places for one to drift
    lenient, and this is a security check.
  - **The reference is attacker-controlled**, since a document names its own schema and in a server that
    string came out of a request body. Both deny by default and match a host exactly (a suffix test for
    `.example.com` also matches `evil-example.com`). Beyond that they guard different primitives: the HTTP
    one is an SSRF risk, so it never follows redirects and caps size against bytes delivered rather than
    `Content-Length`; the file one is an arbitrary-read risk, so containment is checked **after**
    `toRealPath`, which settles `..` and symlink escape together — checking the unresolved path is the usual
    way that control is defeated.
  - **And the pair is one value, `SchemaAccess`** (`base.source`): the source plus the `FetchPolicy`
    governing it, answering "where may this deployment obtain a schema, and under what constraints". The two
    facts always travel together and are meaningless apart — a source with no policy has unstated bounds, a
    policy with no source governs nothing — so handing them separately makes every caller reassemble the
    pair, and one that reassembles it wrongly is a deployment whose bounds silently do not apply.
    `ProcessorConfig.withSchemaAccess` takes one; `SchemaAccess.Builder`'s `source`/`httpSchemas`/
    `fileSchemas`/`fetchPolicy` assemble one, and a `source` named beside any of the other three is refused
    (as is `httpSchemas` beside `fileSchemas`). Those mutual-exclusion rules
    live on `SchemaAccess.Builder` rather than on `ProcessorConfig`, so they are stated where the access is built
    instead of once per front door — which is what the JSON encoding's schema-directed decode needs, it
    being the next caller and one that would otherwise grow its own copy of the host list and the caps.
    **The name is not `SchemaLibrary`**: [TSON-SCHEMA] §10's library is the *store* mapping identities to
    content, which here is `TsonSchemaRegistry`, and fetching is "a permitted but opt-in way to populate" it
    — this is that opt-in, not the store.
  - **What they will admit and spend is one value, `FetchPolicy`** (`base.policy`, beside `ProcessorPolicy`):
    how large a document may be, how many may be cached, and whether a reference must carry a `?sha256=`
    pin. The three are the same question whichever source answers it, and holding them as loose fields per
    source is two places for a default or a bounds check to drift. `fetchPolicy(...)` is the setter to reach
    for on either builder, with the three component setters folding into it — the shape
    `ProcessorConfig.withProcessorPolicy` takes, so the library has one convention for stating a policy. A `timeout`
    is deliberately **not** a component: a directory has none, and a component one implementation silently
    ignores is what makes a shared policy value untrustworthy. Nor is the host map, which is where *this*
    deployment can reach rather than what any deployment will admit, and is differently typed on each source.
    It is `ProcessorPolicy`'s **sibling rather than its component** for the same reason it is not the host
    map's peer: a `ProcessorPolicy` is threaded into every reader and every stream, none of which fetch, so a
    fetch bound riding the read path would be carried everywhere and used nowhere. Stated in one breath,
    consumed by two subsystems, so two values.
  - **`SchemaFetchException` is the contract, and it lives in `tson-base`** — at the module's root, with the
    interface that names it (`SchemaSource`) and the two sources that throw it in `base.source`.
    `tson-compiler`'s `SchemaFailure` routes on it, and a schema is obtained the same way whichever encoding
    named it: its `Reason` is what `Diagnostic.Code.of` maps to the five `SCHEMA_*` codes, a same-package
    call, and that mapping is one answer for every encoding. A source signals "cannot supply this" with
    that and nothing else, so a read can tell an unfetchable schema from a broken invariant by type; anything
    else out of a source is that source malfunctioning and propagates as itself. `Reason` is the part worth
    acting on: `NOT_PERMITTED` is policy and no retry helps, where `TIMEOUT`/`TRANSPORT` say the reference
    was fine and the world was not.
  - **A `null` return is not a second way to say it, and is refused where the loader calls a source.** It
    carries no `Reason`, so a deployment refusing a reference and a host that did not answer would arrive
    indistinguishable — and unguarded it would surface as a `NullPointerException` several frames inside the
    registry, nowhere near the source that caused it. `TsonCompiledMetaRegistry.fetch` raises an
    `IllegalStateException` naming the source and the rule, which keeps it a *fault*: a broken source is the
    deployment's bug, not a verdict on the document that happened to name the schema, so `SchemaFailure`'s
    default rethrows it. Treating `null` as a miss instead would make the wrong spelling work and hide every
    later one.
  - **`SchemaSource.ofMap` is the third shipped source, and exists because the trap above has one
    author.** `schemaSource(schemas::get)` is the natural first implementation — it compiles, serves every
    identity in the map, and returns `null` for the rest, which the document chooses. `ofMap` is that lookup
    done to contract: a miss is `NOT_FOUND` (this source had somewhere to look, where `registeredOnly`'s
    `NOT_PERMITTED` means nothing was looked for), and lookup is **by canonical identity**, so a reference
    carrying a `?sha256=` pin finds the entry registered without one. That last part is the half a raw map
    lookup gets wrong silently: it fails only for documents that pin, which are the ones written where
    integrity is taken seriously. Two keys canonicalizing alike are refused rather than collapsed, and the map
    is copied.
  - **Neither verifies the `?sha256=` pin or the fetched document's `!!id`** — the loader does both, after a
    source returns, and a second implementation would only drift from it. What the loader cannot express is
    *requiring* a pin, since it verifies only one that is present; `FetchPolicy.requireContentHashPin` is
    that.
  - **Caching is by canonical identity and never re-checked**, which rests on §10's immutability rule rather
    than on the transport: a file edited in place is not seen, and under §10 editing it was the mistake. A
    cached entry survives its file being removed, which is why the file source's policy check touches no
    filesystem. Policy is re-checked on every reference, cached or not — a hit skips the fetch, never the
    allow-list.
- **The read mode is which registry you hold:** `treeRegistry()` (an immutable, queryable `TsonValue` tree)
  and `bindRegistry()` (real Java objects, bound via `dataBindContext()`), both over one shared bind-mode
  resolution core.
  `resolve(schemaText)` resolves/links/registers and takes *no* mode — resolution is always object-binding
  internally (it binds meta instances to `schema.meta.Top`), and only a registry's own `compile`/`get`
  picks a mode.
- **`validate(String|InputStream)` *is* `treeReader()` with a collecting receiver**, both halves of it —
  one call with no `catch` around it, no second implementation. The reader already works out whether a schema
  applies (a `!!schema` directive selects the schema through `ProcessorConfig.withSchemaAccess`, compiled once in
  tree mode, and the root type-ref selects the type; with no `!!schema` it reads schemalessly, checking
  the wire's own type-refs), and reports every failure around all of that through the receiver. Validating
  is that read with the tree thrown away. Returns every problem as a `List<Diagnostic>` (empty means valid)
  and **never throws for a bad input document** — malformed syntax, a schema document handed in where data
  was expected, an unresolvable schema, an unknown type all come back as diagnostics.
  - **The `InputStream` overload is the body; the `String` one delegates into it.** The reader underneath
    decodes UTF-8 bytes, and the CLI already holds a stream.
  - Base-syntax failures are converted by **`TsonDiagnostics.ofBaseSyntaxError`** (`tson-compiler`'s root
    package), which the read facades call and route through the read's receiver — so `validate` itself
    catches nothing. It is public because one of the three exception types it classifies, `LexException`,
    lives in the unexported `lexer` package, so a caller in another module driving the stream or parser
    directly can't name it in a `catch`. It returns a `Diagnostic` and **rethrows anything else**: "never
    throws for a bad *document*" is not "never throws", and laundering a library fault into a diagnostic
    would report a false verdict and bury the stack trace. `tson validate` holds to the same rule: a fault
    propagates to `TsonCli`'s fault handler rather than becoming a per-file verdict.
- `objectReader()`/`treeReader()` return **schema-aware** `TsonObjectReader`/`TsonTreeReader` over this
  instance — the value-returning read peers of `validate`: a self-describing document is validated against
  its declared `!!schema` (schemaless when it declares none), the object form checking the target class up
  front. **Both are built over this instance's own `treeRegistry()`/`bindRegistry()`, so every reader shares
  one compiled-schema cache** — a schema compiles once per `Tson`, not once per reader. The readers take a
  `TsonCompiledSchemaRegistry` rather than a `TsonCompiledMetaRegistry` for exactly that reason; since the
  read mode isn't visible in the registry's type, each constructor checks a package-private `mode()` and
  rejects the wrong one up front instead of failing on a cast at the first value.
  `objectReader()`/`objectWriter()` bind to this instance's `dataBindContext` (configurable via
  `ProcessorConfig.withDataBindContext`, default `AtomContext.defaultContext()`). `schemaRegistry()`/`loader()`
  reach the underlying machinery.
- **A `Tson` is one profile, and the schema being read never picks it.** Routing a document to the right
  profile stays the application's job. The alternative — the schema declaring its own profile through a
  meta-layer annotation — links a *coding* decision to a *format* one and buys less flexibility than it
  costs, since the application then cannot bind one schema two ways. Selection is by an opaque label for the
  same reason it is not by matching the schema's field set: no serialization library does that, and the
  parameter names it would need are not retained for a secondary constructor. Reconsider only if something
  needs to re-derive the binding without the application in between.
- **Two binding seams, never merged.** `ProcessorConfig.withDataBindContext` binds the *data* a schema
  describes (`order` → `Order`); `ProcessorConfig.withMetaNameBinder` binds a governing meta's own *vocabulary*
  (`operation` → `Operation`, the `data` base kind's case — `design/meta-layer-data-kind.md`). One
  namespace holding both would collide the first time a schema type and a meta-layer constructor shared a
  name. The meta binder is composed over `SchemaMetaNameBinder.INSTANCE` rather than replacing it, so what a
  consumer supplies adds names and gives up nothing: the standard library still compiles in object-binding
  mode, which is the thing the internal context is fixed to protect, and every kernel name still wins.
