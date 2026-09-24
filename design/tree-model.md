# Tree model: `TsonValue`, `TsonDocument` and `TsonObjectDocument`

Design notes for the `tson-tree` module — the sealed `TsonValue` node model every tree read hands back — and
for the two document wrappers, `TsonDocument` and the object side's `TsonObjectDocument<T>`. Current form
only; history lives in git.

**Invariants**

- `tson-tree` requires nothing; `TsonValue` stays a pure value and the header lives on a wrapper.
- `TsonScopedValue` is transparent to navigation, and only a genuine scope push produces one.
- `TsonAtom.toString()` renders its value alone — it reaches a `Diagnostic`'s `expected`/`actual`.
- `get`/`at` never throw; a `TsonMissing` carries the pointer of the step that failed, and the first failure
  sticks. "Missing" and "absent" stay distinct kinds.
- There is one no-value node, `TsonAbsent`; `null` is a `TsonAtom` holding the string `null`, and a `void`
  position admits `_` and nothing else.
- `as(Class)`/`asString`/… cast, `asInt`/`asLong`/`asDouble` convert — a test asserting which host type a
  reader produced must use `as(Class)`.
- `TsonDocument` has no `meta` component; `TsonObjectDocument<T>` is a distinct type carrying `rootType`.
- No node carries a `Node` suffix, and the model is read-side only: no builders, no transforms.

Related: `design/facades-and-tree.md` (the readers producing a tree), `design/writers-and-document-header.md`
(`TsonTreeWriter`, `TsonDocumentHeader`), `design/front-door-and-config.md`,
`design/class2-compilation.md` (the compiled tree readers), `design/readers-and-diagnostics.md`.

## Tree model: `TsonValue` (`tson-tree` module)

What every tree read hands back — the compiled tree readers (`design/class2-compilation.md`) and the
schemaless `TsonTreeReader` alike. A sealed `TsonValue` over eight pure immutable node types (`TsonRecord`/
`TsonMap`/`TsonArray`/`TsonTuple`/`TsonAtom`/`TsonAbsent`/`TsonMissing`/`TsonScopedValue`),
**structure-preserving** — TSON's
record-vs-map and array-vs-tuple distinctions survive into the model, where JSON's would collapse — and
annotation-aware, every node carrying its own `typeRef()` and `annotations()`.

- **`TsonScopedValue` is a wrapper because the directive belongs to the position, not to the value.**
  [TSON-DATA] §2.3's grammar is `scoped-value = [ schema-directive ws ] data-value`: the same record means
  the same thing with or without one, so a nested `!!schema` ([TSON-SCHEMA] §7.8's scope push) attaches
  *around* the value rather than as a ninth component on each of the other seven. That is the argument
  `TsonDocument` makes at document level, and the reason the two are separate types rather than one — a
  document also carries `!!id`, is not itself a value, and cannot stand at a field position.
  - **Transparent to navigation.** Every kind predicate, accessor and step delegates to the value it
    governs, so `tree.at("/attachments/0/claim_id")` reads the same whether or not a scope was pushed, and
    a consumer that does not care about scopes never unwraps one. Asking for the scope is what surfaces it:
    `v instanceof TsonScopedValue s` then `s.schema()`.
  - **Only a genuine push produces one.** A value whose type came from the governing namespace carries no
    directive and is read as its own node. So a tree round-trips through `TsonTreeWriter` with its
    directives exactly where the author put them — the writer emits the scope first and then the value it
    governs, §2.3's own order, which is also why the scoped case sits ahead of `writeNode`'s switch rather
    than in it: every branch of that switch is already past the annotations, and a directive precedes them.
  - **Bind mode has no counterpart**, deliberately: a bound object has nowhere to carry a URI and inventing
    somewhere would change what a consumer's own class means. `TsonAbsent` makes the same asymmetry for
    §2.9.

- **`TsonDocument` is the model's document, and `TsonValue` stays a pure value.** [TSON-DATA] §2.2 —
  "Header directives are properties of the document, not of the body's root value" — is why the header is a
  wrapper rather than two more components on every node, and it makes the tree model the counterpart of the
  parser's own `ast.Document(id, schema, root)` rather than a value model with a hole where the document
  should be. It needs no dependency, so `tson-tree` still requires nothing.
  - **No `meta` component**, deliberately: a document carrying `!!meta` is a *schema* document, whose value
    model is `schema.meta`. `TsonDocumentHeader` is the type that holds all three, and it answers a different
    question — classifying a document from its opening bytes (`isSchemaDocument()`) before deciding how to
    read it. Same reason `ast.Document` carries only the two.
  - **`readDocument` sits beside `read`, which still hands back the root value.** Changing what a read
    hands back, and every caller with it, would be a cost of *replacing* `read`, not of the wrapper.
    `TsonTreeWriter.toTson(TsonDocument)` closes the loop from the other end, the document's own directives
    winning over the writer's component by component and only where it has one — so reproducing a document
    reproduces it, while a writer configured for something the document does not state still contributes it.
  - **`TsonObjectDocument<T>`** (in `tson-compiler`, beside the facades) **is the object side's own**, and
    deliberately not the same type: it needs a
    fourth component, `rootType`, because a `TsonValue` carries its own `typeRef()` and a bound object
    carries nothing. Two arities are not siblings, so they are not named as such.
    - **What it carries is what the *read* established.** The class plus its bind context already fix which
      schema governs an object — one context per schema version is the design — so `schema` is the weakest
      of the three. The other two are not recoverable from anything the caller holds: `!!id` is
      per-document data (§2.2 makes it a property of the document, so a class modelling it as a field would
      misstate its own shape), and `rootType` is a name a `DataNameBinder` cannot hand back, mapping name to
      class where a profile lets one class serve several shapes.
    - **Which is why `describing(schemaUri, rootTypeName)` takes two arguments** where the tree writer's
      takes one. That is not residue of the reader dropping something — the name genuinely cannot be
      derived — but a document carries both, so `writer.toTson(document)` replaces restating at the call
      site what the read had just worked out.
    - A schemaless read leaves `rootType` empty rather than guessing from a wire type-ref it never checked,
      and a hand-assembled document naming a schema with no type is refused at write: the pair is what makes
      a document self-describing, and the directive alone leaves a reader with a schema and no way to pick a
      type from it.
- **`TsonAtom.toString()` renders its value alone, and that is load-bearing.** A reader reporting on a
  decoded value stringifies whatever it decoded, and in tree mode that is a `TsonAtom` — so the record's own
  default rendering would reach a `Diagnostic`'s `expected`/`actual`, the two fields that exist precisely so
  a consumer needn't parse the message, and the message itself wherever a reader interpolates a value
  (`RecordAbstractReader.verifyFixed`, `ArrayAbstractReader`'s `unique_items`). The type-ref and annotations
  stay reachable through the accessors. Composites keep the record default: rendering one as TSON text is
  `TsonTreeWriter`'s job, encoding and lossy spots and all.
- **The names are chosen against Jackson, not in a vacuum.** No node carries a `Node` suffix, because
  Jackson ships `ArrayNode`, `NullNode` and `MissingNode` — a consumer using both libraries in one file
  would otherwise fully qualify every one. The sealed shape independently matches JEP 540's
  `JsonValue`/`JsonObject`/`JsonArray`/`JsonNull` (Simple JSON API, incubating in JDK 28), with
  `TsonRecord` + `TsonMap` staying *more* precise than `JsonObject`, which cannot distinguish the two.
- **Navigation is lenient but not silent.** `get`/`at` never throw, and the `TsonMissing` they return
  carries `path()` — the RFC 6901 pointer of the step that *failed*, relative to the node navigation
  started from — so `at("/a/b/c")` distinguishes "no `b`" (`/a/b`) from "`b` had no `c`" (`/a/b/c`). Every
  missing comes from a navigation step, so there is no singleton and equality is by path; read it without a
  cast via `TsonValue.missingPath()`. The first failure sticks — stepping on past a missing returns the
  same node rather than extending its pointer. "Missing" (not in the tree) and "absent" (written, but
  holding no value) stay distinct kinds.
- **There is one no-value node, `TsonAbsent`, because there is one no-value spelling**: the `_` sentinel. A read that
  failed leaves no node at all — every read is all-or-nothing — so a `TsonAbsent` in a tree always means the document
  wrote `_`. `null` is not one — §4 resolves boolean, number and string, so the unquoted token is a `TsonAtom` holding
  the string `null`, schemaless and under a schema alike, and it round-trips through `TsonTreeWriter` as the string it
  is. A JSON document's `null` reaches absence through a JSON reader, which maps it in the model, where the position's
  own state decides whether absence is admitted at all.
- **A `void` position admits `_` and nothing else** (`VoidReader`), which is where a second spelling would
  be cheapest to admit — the type has one inhabitant, so conceding loses no distinction — and it is refused
  there too. Conceding would make absence's spelling depend on the position's type, a rule an author
  computes rather than remembers.
- **Two families of value accessor, and the split is the point.** `as(Class)`/`asString`/`asNumber`/
  `asBigInteger`/`asBigDecimal` only ever **cast** (`isInstance`), so they answer "what host type did the
  read produce?" — an `int32` field holding an `Integer` gives empty from `asBigInteger()`. `asInt`/
  `asLong`/`asDouble` (`OptionalInt`/`OptionalLong`/`OptionalDouble`, so a hot path doesn't box)
  **convert**, and answer "what number is this?" regardless of host type. Conversion is exact for the
  integral pair — an integral fractional part converts (`123.0`, `234.56E2`), a real one doesn't, and
  out-of-range yields empty rather than wrapping — while `asDouble` accepts nearest-double rounding
  (demanding exactness would reject `0.1`) but rejects a magnitude that can't be finite, so nothing ever
  reads back as `Infinity`. Text is never parsed: `"42"` is a string per §4.4. A test asserting *which*
  host type a reader produced must therefore use `as(Class)`, not `asInt()`.
- **Read-side only, deliberately, and deferred until required.** There are no copy-on-write transforms and
  no builders — `TsonRecord.with`/`without`, `TsonArray.with`/`plus`/`without`, `TsonRecord.builder()`, a
  pointer-based `set("/a/b", value)` — and construction is the static `of(...)` factories. All of it would be
  pure `tson-tree` work with no compiler dependency, so the module is ready for it; what is missing is a
  concrete produce/edit use case, and `TsonTreeWriter` already closes the read→edit→write loop without one.
  **JEP 540 reached the same conclusion independently**, which is what makes this a decision rather than a
  shrug: it ships no transformation API and no builders at all, construction being static `of(...)` factories
  as here, and its Risks section defers the area outright — "During the incubation period, we will gather
  more information about use cases involving generating and transforming JSON documents, in order to evolve
  these areas of the API."

