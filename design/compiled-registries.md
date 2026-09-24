# The compiled registries

Design notes for `TsonCompiledMetaRegistry` and `TsonCompiledSchemaRegistry`: the shared meta/resolution core, the
per-mode read registries, the import-cycle guard, content-hash verification, and the read path's concurrency. Current
form only; history lives in git.

**Invariants**

- The core compiles and caches only meta-layer schemas; core.tn is resolve-only there and compiled inline in a read
  registry.
- The read mode is which registry you hold, not a compile parameter; resolution is always bind-anchored and delegated to
  the core.
- An import cycle is caught by a per-thread in-flight set, not a cache lookup and not a registry-wide set; it throws even
  with a receiver in play.
- Content-hash verification is per identity, not per route, and verify-before-record; `verifyPin` runs on every
  reference, cached or not.
- A single-line schema records `UNADDRESSABLE` rather than failing; what gets refused is the pin.
- On a race, what is duplicated is work, never state; explicit `register` on an identity already present is still an
  error.
- A cache hit takes no lock; a read canonicalizes its schema URI once.
- A compile with no registry behind it passes `ForeignSchemas.none()`, whose every lookup is `SCHEMA_NOT_PERMITTED`.

Related: `design/linking-and-compilation.md`, `design/class2-compilation.md`, `design/meta-layer-data-kind.md`,
`design/cli-config-hashing.md`, `design/facades-and-tree.md`.

## The registries (`tson-compiler/{TsonCompiledMetaRegistry,TsonCompiledSchemaRegistry}.java`)

Two registries over one shared resolution core, the compiled-side counterparts to `tson-schema`'s
`TsonSchemaRegistry`.

- **`TsonCompiledMetaRegistry`** is the shared **meta/resolution core**, and *is* the on-demand
  `TsonCompiledSchemaLoader`. It owns the paired `TsonSchemaRegistry`, a bind-mode resolver, a
  `SchemaSource`, content-hash verification, and the meta-kernel bootstrap. It compiles and caches
  **only meta-layer schemas** (meta-kernel, meta.tn — the name is literally accurate). Its loader
  interface is two honest methods: `loadMeta(uri) → TsonCompiledMetaSchema` (a governing meta, which must
  be compiled — its `!enum`/`!integer` instances are read into `schema.meta` objects during a governed
  schema's resolution) and `resolveLinked(uri) → TsonLinkedSchema` (an `!!import` target or a user schema
  — fetched/resolved/linked/registered but **never compiled** here). `withStandardLibrary(context,
  source)` builds a core with the three bundled schemas loaded; **core.tn is not a meta** (its `!!meta` is
  meta.tn) so it is resolve-only here — its readers are compiled per mode in a read registry when a user
  schema importing it is read, never standalone in the core.
- **`TsonCompiledSchemaRegistry`** is a **per-mode registry of compiled user schemas** over a core, built
  via `TsonCompiledSchemaRegistry.tree(core)` / `bind(core, context)`. **The read mode is which registry
  you hold**, not a compile parameter. `get(uri)` resolves through the core (`resolveLinked`) and compiles
  the linked form standalone in its own mode, cached by identity; `compile(linked)` is the uncached
  primitive.
  - **It also hands its own `get` to every compile it performs** (`ForeignSchemas`, threaded through
    `TsonSchemaCompiler` into `ValueReaderContext`). [TSON-SCHEMA] §7.8's scope push resolves a schema the
    *document* names, not one the schema being compiled does, so `ScopedReader` cannot be wired to an answer
    the way every other reader's children are — it is wired to where to go and ask. Passing `this::get`
    mid-construction is safe because nothing calls it until a read, long after every compile it could
    re-enter has finished, and it is what makes a pushed schema share this cache, this loader and this read
    mode with the schema that admitted it. A compile with no registry behind it — the bootstrap, a
    standalone compile in a test — passes `ForeignSchemas.none()`, whose every lookup is
    `SCHEMA_NOT_PERMITTED`: nothing was configured to supply a foreign schema, which is a fact about the
    deployment and not a verdict on the document.
- **Resolution is always bind-anchored, so it is delegated to the core regardless of read mode.** A
  schema's own `!enum`/`!integer` instances bind to `schema.meta.Top` objects — a tree reader's `TsonValue`
  can't stand in — so every read registry shares the one bind-mode core for resolution; only the final compile
  runs in the registry's mode (standalone: the schema's constructor usage was already validated at link
  time). The bind read registry takes the *caller's own* `DataBindContext` (their user-class name binder),
  deliberately distinct from the core's internal `SchemaMetaNameBinder`-based resolution context. A user
  schema importing core.tn gets core.tn's entries merged into its own linked form (by `link`) and
  compiled inline, which is why the core never needs core.tn compiled.
- **An import cycle is caught by what is *in flight*, not by a cache lookup** (§2.2.3). A schema is
  registered only once it has linked, so while `a.tn` is resolving it is in no registry at all and `b.tn`
  importing it back re-enters `resolveLinked` for the same identity and fetches it again — unguarded, that
  is unbounded recursion ending in a `StackOverflowError`: an `Error` raised by ordinary author input, which
  no `Diagnostic` ever sees and which the exception policy cannot classify. `resolving` holds the identities
  this thread is part-way through, and the chain closing the cycle is what the message names, so any one of
  its links is the edge to break. The same guard covers a `!!meta` chain, every link of which is reached
  through `resolveLinked`.
    - **Per thread, not per registry**, and that is not incidental: concurrent resolution of one identity by
      two threads is safe here by design (below), and a registry-wide set would make the second thread's
      ordinary in-flight entry look like a cycle to the first. Recursion through `!!import`/`!!meta` is
      strictly within one thread, which is exactly the scope of the question.
    - It **throws even with a receiver in play**, on the same footing as an unloadable `!!import` or an
      ineligible `!!meta`: what fails is the namespace itself, so carrying on would report every reference
      into the unresolvable half as a second problem. What is *not* here is the ordering half — collecting a
      schema's transitive closure and resolving it dependencies-first, so callers stop hand-sequencing
      registration (`BACKLOG.md`).
- **Content-hash verification is per identity** (§10.2): the core records an identity's content hash on
  first resolution and checks every reference's `?sha256=` pin against it, on both fetch and cache-hit
  paths, so a conflicting pin errors rather than silently resolving to the cached instance. Verify-before-
  record, so a rejected fetch can't poison a later valid one. **Per identity means per identity and not per
  route**, which is why registering a schema from text is one call (`TsonCompiledMetaRegistry.register(linked,
  sourceText)`, what `Tson.resolve`/`Tson.validateSchema` reach) rather than a registration a caller may
  remember to pair with a hash: a hash recorded only where a schema was *fetched* leaves `verifyPin` nothing
  to compare against, and its silence there is indistinguishable from a verified pin — a wrong `?sha256=` on
  a schema the process registered itself validated clean. The same call verifies the document's own `!!id`
  pin against its bytes, §2.2.1's id line being excluded from the hash input precisely so a document can
  carry its own hash.
- **A document with no line terminator after its id line records that it has no hash, rather than failing.**
  §2.2.1 asks for the terminator of a *content-addressed* document, so a single-line schema is one no
  reference may pin — not one that fails to load. Hashing every schema on load (to have the answer ready for
  a later pinned reference) must not turn that into a refusal of the document, so `TsonContentHash`
  has `sha256IfAddressable` beside `sha256` and the registry records `UNADDRESSABLE` for the empty answer.
  What gets refused is the pin: a hashed reference whose target carries no id line is
  a `ContentHashMismatchException` naming that, which §2.2.1 requires ("the target of a hashed reference
  MUST carry an id line"). Eagerly hashing with `sha256` instead would make an unpinned single-line schema an
  `IllegalArgumentException` escaping the read as a fault.
- **Concurrent first use of one identity is safe, and deliberately not serialized.** `loadMeta`/
  `resolveLinked` recurse into themselves and hold no lock across a fetch, so two threads reaching the same
  cold identity both do the work; the caches settle it, keeping the first entry and handing it to both
  (`TsonSchemaRegistry.registerIfAbsent`, `compileAndCache`). **What is duplicated on a race is work, never
  state** — one linked form and one compiled meta per identity, always. The stake is real rather than
  hypothetical: a check-then-`register` shape fails the *loser*, and on a read that surfaces
  not as a crash but as a `SCHEMA_ERROR` against a document with nothing wrong with it, on the first
  concurrent requests a process ever serves (`ReadPathConcurrencyTest` pins both halves). Explicit
  registration stays strict — `register` on an identity already present is an error, since doing that
  on purpose is a caller mistake however many threads are involved. `DataBindContext.getDescriptor`, the
  other read-path cache, has the same shape and settles a race the same way.
- **A hit takes no lock either.** Every data read reaches two caches — `TsonSchemaRegistry`'s identity map
  (through `resolveLinked`) and `TsonCompiledSchemaRegistry`'s compiled map — and in a process that
  registered its schemas at startup, which is what this design asks for, both hit essentially every time.
  So neither hit is allowed to serialize: `TsonSchemaRegistry` holds a `ConcurrentHashMap` and its lookups
  are plain reads rather than `synchronized` methods, and the compiled cache does a `get` before
  `computeIfAbsent`, which takes a bin lock only for a key sitting behind the first node. The
  no-overwrite rule is unaffected — it is `putIfAbsent` rather than "check and put under a monitor", which
  is the same guarantee stated atomically, and `register` refuses a second registration of one
  identity. **Measured against a monitor, the gain is small on a 16-CPU machine** (~6% at 32 threads, nothing
  below that): the critical section is a map lookup, and a JVM absorbs an uncontended monitor well. It is
  lock-free because a monitor on the read path is a ceiling that arrives with the core count rather than a
  cost that shows up in a profile, and because the section can only grow.
- **A read canonicalizes its schema URI once.** `CanonicalIdentity.canonicalize` is a `new URI(...)`
  parse, and three places want its result for one document — the compiled-schema cache's key, the resolution
  cache's key, and the schema registry's own lookup. The identity is computed at the top and passed
  down (`resolveLinked(uri, identity, receiver)`, `TsonSchemaRegistry.getByCanonicalIdentity`), with the
  single-argument forms kept as the door for anyone holding a URI as written. **Not a shortcut past the pin
  check**: `verifyPin` runs on every reference, and `BundledSchemaPinTest` pins that a wrong pin
  is rejected once the schema is compiled and cached.
- **The rest of the read path needs no locking at all.** A `Lexer`/`TsonDataStream` is built per read and
  shared with nothing, and every compiled reader is immutable — the whole `reader` package holds exactly one
  non-final instance field (`CompiledReaders.delegate`, `volatile`, rebound once at the end of a compile).
  So a `TsonCompiledSchema` is safe to share across threads, which is what makes a `Tson` worth sharing.
- **Both halves of the guarantee are stated on `Tson` and `ProcessorConfig` themselves** rather than only in
  the design notes — a consumer reads the front door, not `design/`, and that guarantee is what decides
  between one instance and one per request (`SharedInstanceConcurrencyTest` pins it at that surface). What is
  still open is everything *outside* a read: registering schemas concurrently. **Mutating a `DataBindContext`
  after use is not one of them** — registration is `DataBindContext.Builder`'s and closes when the context is
  built, so the API cannot express a registration arriving after `getDescriptor` has handed out a descriptor
  for that class.
