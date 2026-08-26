# Backlog

The actively-tracked engineering backlog for this implementation. Same convention as
`SPEC-FEEDBACK.md` (a versioned, checked-in list) but for project work rather than spec
ambiguities. Grouped by theme, not priority — reorder/prioritize as needed. See `STRUCTURED-OUTPUT.md`
for the target-use-case plan (LLM structured output validation, JSON compatibility) — that's tracked
separately since it's a vision/plan document, not a plain punch list — and `CLAUDE.md`'s own "Not
yet implemented" section for the technical detail behind several of these items.

---

## Resolution & linking generality

Every real schema resolved so far (meta-kernel, meta.tn, core.tn, and hand-built test fixtures)
happens to fit a narrow shape this pipeline already handles — declared in dependency order, with
callers hand-sequencing registration themselves. These items are what's missing for the *general*,
spec-required case, found by re-auditing Part 2 against the current source rather than CLAUDE.md's
own prose (which had gone stale on at least one of them):

- [ ] **Automatic reference-closure resolution** ([TSON-DATA] §2.2.3, [TSON-SCHEMA] §3.4.1) — no code
  collects a schema's transitive `!!meta`/`!!import` closure, topologically orders it, and resolves it
  dependencies-first; every caller (including this session's own `TinySchemaImportsCoreTn1Test`) has to
  already know and hand-sequence the correct registration order itself. Distinct from what
  `TsonCompiledMetaRegistry.withStandardLibrary` already does, which is scoped to just the three bundled
  schemas in a known order, not a general algorithm.
    - **Cycle detection is done** and was the more urgent half: a cycle is not the "opaque *not registered*
      error" this entry used to claim — it was a `StackOverflowError`, since a schema is registered only
      once it has linked and so is invisible to every cache while it resolves. `resolveLinked` now holds a
      per-thread in-flight set and reports §2.2.3's cycle naming the path that closes it. The ordering work
      still needs it (a topological sort has to detect cycles to terminate), so that dependency is met.
- [ ] **The rest of §8.2's deferred value-level checks.** Materialisation "runs the value-level checks that
  open bounds deferred: family coherence rules whose operands were parameters". The array family's
  `min_items <= max_items` is one rule over the binding pair for arrays *and maps*: a resolver error where
  the bounds are literal at schema load, at materialisation where parameter-bound. The literal half is done
  for both tiers (`SchemaDesugarer.checkBounds`); the parameter-bound half now has a home too — a
  parameter-bound `min_items` reaches the target constructor's own reader when the template closes, which is
  where `<"two">` is rejected, though nothing yet compares a *pair* of bounds once both go concrete. The
  kin §8.2 gestures at — "bounds within a width-derived range, and their kin" — belong with the constraint
  families that own them, next to `AtomNarrowing`, not in a syntax rewrite. Doing that properly probably
  means the check moves out of the desugarer entirely and `checkBounds` goes with it. Distinct from the
  atom-body self-coherence item below, which shares that destination but has no parameter or
  materialisation dimension at all.
- [ ] **Resolved-form ingest** ([TSON-SCHEMA] §8.1/§10.1) — bringing an already-resolved
  `!type_definition` document into the library (not source text), with its own integrity checks:
  `subtypes`/`disjoint` recomputed and verified, the closed-entry parameter-free rule reverified, an
  instantiation entry checked against its own `source` by recomputation, a construction's binding
  record checked for parameter-slot agreement with its `source` application. Entirely unimplemented
  — "ingest" doesn't appear anywhere in the codebase. Note `spec/tson-cr-structure-templates.md` §4.7
  extends what ingest must reverify: the closed-entry rule gains "carries no `instance_template` body", the
  invariant that makes open and closed entries tell apart by inspection, and synthetic entries must pass
  under the existing integrity checks. Note it would introduce a *second* way to build a
  `TsonSchema` — bound from a document rather than resolved from source — and the two would have to agree,
  including on where a declaration's annotations land (the name's on the map key, the definition's on the
  entry). Lower priority than the rest of this section: the spec marks this path explicitly **optional**
  ("MAY implement ingest"), not a MUST.

## Remaining Part 2 resolution gaps

One is left. `DefinitionResolver.resolveTypeRef`'s catch-all, which used to head this section, is now
unreachable from a desugared document — every shape it named resolves or is refused where it is written —
and survives only as a guard against a caller resolving raw AST with the desugar phase skipped.

- [ ] **Composing or refining against a template application that is still open** (`vip => <T> customer &
  box<T>`, §5.8's "Parameterized references"). The *fully-bound* case closes on demand now, at both
  absorbing positions. What is left is the case where the application names the enclosing declaration's own
  parameters, which cannot close until that declaration itself materialises — so composition would have to
  be deferred to materialisation too, absorbing fields into an entry that does not exist yet. A different
  feature from closing an application, and the diagnostic now says so rather than blaming substitution.
Only genuine gaps are listed here — a throw that means "your schema is wrong" is not one. Classifying the
throw sites by that test is done across the whole schema pipeline (issue #26); if a census is ever wanted
again, take it fresh rather than trusting a recorded one, since the last recorded numbers had gone stale
by a factor of six.

- [ ] **Atom-body coherence, the parts that need a parser this module doesn't have.** `Atom.coherenceCheck`
  (issue #50) now rejects an atom body whose own facets admit nothing, but three gaps are left, each
  matching that family's existing *narrowing* gap and each blocked on the same thing — `tson-schema` has no
  dependency on a parser for the values involved:
  - `duration_type`'s bounds are unparsed ISO 8601 text. `"P1M"` vs `"P30D"` does not order lexically, so
    judging them as strings would call a coherent body empty. Needs `DurationParser`/`IsoDuration`, which
    live in `tson-compiler`.
  - `pattern` emptiness — a regex matching no string at all, or none of a permitted length. Needs
    `tson-regex`, the same boundary the narrowing check's containment gap sits behind.
  - CIDR `within`/`excluding` admitting no network between them. Needs real containment arithmetic; the
    family has no CIDR parser.
  - The natural fix for all three is the same one the narrowing check would want: an injected oracle, rather
    than moving the value model's dependencies.

## Open form: hold the template body (Revision 34 proof)

`SPEC-FEEDBACK.md` #5 has the argument; this is the plan. Today an open entry's body is a typed quotation
(`instance_template` + `template_argument` for instances, `record_field.value_param` for records), which is
why a parameter in a collection-valued slot has nowhere to go and is refused at the declaration. The
replacement: an entry with type parameters **holds its body in the form it was written** — sugar expanded,
nothing lifted — and materialisation substitutes into it and then runs the ordinary desugar-and-resolve path.
Substitution becomes one rule at any depth, the collection boundary dissolves rather than being widened, and
the quotation vocabulary deletes rather than growing a collection case per slot kind.

Two things this deliberately does **not** touch. The bundled schemas stay byte-identical: meta-kernel keeps
declaring `instance_template`/`template_argument` even though nothing produces them, because an open entry
never serialises as a `type_definition` under this design, so no digest and no `*-resolved.tn` fixture moves
for the kernel. And nothing about closed entries changes — identity, instantiation `source`, flattening,
knot-tying, and every reader all stay as they are.

Steps are ordered so each lands with the suite green.

- [x] **1. Decide what is held, and get it across the module boundary.** Done. `schema.meta.TemplateBody` is a
  `non-sealed Top` branch declaring the seat; `tson-compiler`'s `resolver.HeldBody` is its one implementation,
  wrapping the post-desugar `ast.schema.TypeDef`. That is the same arrangement `SourcePosition` has with
  `Position` — the depended-on module declares an interface its dependent satisfies — so `schema.meta` still
  names no compiler type. Three things settled along the way:
    - **`DataValue`, because every held body is an application.** Three normalisations get it there: a bare
      record body is rewritten to the `!record { fields: [...] }` it denotes (§5.2) where it is written; a
      composition is flattened against its supertypes first and the flattened form held — the same
      resolve-then-round-trip merge atom refinement already performs, which is why a composition template
      normalises in the resolver where a plain record normalises at desugar; and a parameterized atom
      refinement is not a form at all, §12.1 giving `atom-refinement` no parameter list. Materialisation then
      hands the substituted value straight to `DefinitionResolver.bindAtomInstance`, which already takes a
      `DataValue` and dispatches through the constructor's own compiled reader.
    - **A reference template holds nothing.** `<B> pair<uuid, B>` keeps the `type_ref` with arguments it
      already resolves to (`resolveTypeDef` threads it through today), a parameter in an argument being an
      ordinary name on the reference channel. So the implication runs one way only: a held body means a
      template, not the reverse. Normalising this away too would mean widening the kernel's `reference` from
      `target: type_name` to a `type_ref` so an alias can carry arguments — worth proposing for Revision 34
      alongside the rest of #5, since it would make *every* template body an application, but not needed to
      land any of this.
    - **A wrapper, rather than `TypeDef` implementing `TemplateBody` directly.** The AST models surface syntax
      and `schema.meta` models resolved bodies; a node is a body only in that role, and saying so once keeps
      the grammar types out of the value model's root hierarchy.
    - **`Top` keeps `body` REQUIRED**, since the held body occupies the seat `InstanceTemplate` already held
      for the same "never describes a value" reason — no `Optional<Top>`, no second body component. `Top` now
      has two non-sealed branches, and `TemplateBody`'s Javadoc says why it is not an extension point the way
      `Data` is.
    - `HeldBodyTest` pins the part that is a decision rather than a mechanism: a held body **participates in
      `TypeDefinition.equals`**, the opposite of `position` beside it. Verified to fail with `body` dropped
      from the hand-written `equals`.
    - `TsonSchemaLinker.validateBody` gained its `TemplateBody` case early, because the exhaustive switch forced
      it. The case is empty and carries step 5's reasoning: a held body is opaque at link time, and what
      moves to materialisation moves with it.

- [x] **2-4. The representation swap: an instance template holds its wire record.** Done, as one change --
  the three steps could not land apart, since the desugarer's output type, the resolver's dispatch and the
  materialiser's input are the same handover. §12.1's `instance` production reinstates `[type-params]`, so
  `Instance` carries a parameter list and `ast.schema.InstanceTemplate`/`TemplateBinding` and
  `schema.meta.InstanceTemplate`/`TemplateArgument` all delete. `SchemaDesugarer.instanceTemplate()` collapses
  into `instance()` — one binding record serves open and closed alike — taking the collection refusal with it.
  `DefinitionResolver` wraps a parameterized `Instance` in a `HeldBody` rather than resolving it, and
  `TemplateMaterialiser` substitutes over the held tree: one walk now does what three steps used to, since a
  parameter in a slot, inside an application, and inside a collection are all just tokens.
    - **The naming order is load-bearing, and not the one that looks right.** Applications inside a held body
      must close *before* `internalName` runs. Desugar lifts innermost-first, so a form it writes already
      names the entry its inner form became; naming from the unclosed shape puts a generated head inside
      another entry's name and splits `[[pixel; 3]; 3]` from `grid<pixel, 3>` into two entries for one type.
      `ApplicationInContainerPositionTest.closingAGeneratedSyntheticRecordsNoApplication` is the guard, and it
      caught exactly this.
    - **Two declaration-time checks survive and should**: that each binding names a field the constructor
      declares, and that every REQUIRED-without-default field is bound. Both read field *names* off the held
      record, so they need no stand-in values and cannot fabricate a verdict — the hazard that rules out
      checking the value side. So "an unapplied template gets no verdict" is narrower than #5 states, in the
      direction of more checking.
    - **`TemplateBody.names()`** is how §5.10's unreferenced-parameter rule survives: the linker asks which
      unquoted tokens the held body mentions, declared on the interface the way `Data.references()` is. It
      must return exactly what substitution would rewrite, or the check and the rewrite disagree about what a
      parameter reference is.
    - **What an explicit template payload loses**: `<N> !array { element_type: box<text> ... }` no longer
      parses, `box<text>` being schema grammar where §12.1's `instance` takes a `core-value`. An application
      reaches a slot in `type_ref`'s record form, which is what the sugar `[box<text>; N..]` expands to —
      the same rule that has always governed a closed instance's payload.
    - Four tests inverted, each a deliberate behaviour change: the two collection refusals
      (`TsonValidateSchemaTest`, `ContainerSugarEndToEndTest`), the CLI's exit-1 fixture (which used the
      refusal as its example of a spec-refused construct), and `TypeInhabitanceTest` — where an uninhabited
      template is now caught when it closes rather than where it is written, verified both ways.

- [ ] **3. Records and compositions onto held bodies, retiring `value_param`.** Attempted and **parked**;
  the working patch is in this session's scratchpad (`step3-wip.patch`). The shape is right and most of it
  works — a structural template resolves as today and the resolved `RecordBody` is written back to wire form
  and held, so composition flattens and refinement merges before holding, and the writer handles states,
  groups, annotations and defaults with no shape remembered twice. `resolveRecordBody` puts a parametric
  modifier's token in `value` rather than `value_param`, and §5.7's fixation (REQUIRED + a value present →
  `REQUIRED_FIXED`) moves to where the substituted body is read back. A record template's closure stays the
  instantiation entry itself rather than gaining a synthetic hop (§8.2). Three findings stopped it:
    - **A held body has no slot types, and two checks depend on them.** This is the finding, and it is a
      property of holding rather than a defect in the patch. §5.10's argument-kind rule — "a reference
      argument binds a type parameter, a literal binds a value parameter" — was enforced by `value_param`
      saying *this slot expects a value*; with the parameter standing in the ordinary `value` slot, a type
      name substituted there is just a token and `box => <V> { v: int32 = V }` applied as `box<text>` passes
      silently. The same absence has a second face: `type_ref` and `type_argument` both spell their first
      member `name`, so a walk over an uninterpreted tree cannot tell a referenced type's name from an
      argument's name channel, and the `name`→`value` move a literal argument needs fires in both. **Both are
      accepted losses** — the argument check goes, and the member collision is logged as not implemented
      rather than worked around.
    - **`<N> { v: [text; N..] }` throws `NoSuchElementException`** on the lifted-synthetic path — an ordinary
      bug in the parked patch, undiagnosed, and the one thing here that is not a design consequence.
    - Two tests invert with it and are already written in the patch: the open template's parametric fixed
      field is read off the held wire record rather than off a `RecordField`, and §5.10.1's regularity check
      moves from `TemplateRegularity` at the declaration to the depth guard at materialisation.
    - **`TemplateRegularity` becomes unreachable** once every template body is held, and wants either
      deletion or an `applications()` on `TemplateBody` — the same shape as `names()`, returning the
      applications the held tree holds — which would put §5.10.1 back at the declaration and give the
      author a diagnostic naming their own template instead of a chain of synthetic names.

- [x] **5. The linker stops walking into a held body — but keeps the entry.** Done, mostly as fallout from
  step 2. A template entry is retained through linking and compilation exactly as before; what changed is
  that `TsonSchemaLinker.validateBody` and `TypeInhabitance` no longer walk *inside* a held one, its
  references being tokens that mean nothing until substitution supplies the arguments. §5.10's
  unreferenced-parameter rule survives through `TemplateBody.names()`; §5.10.1's regularity check still runs
  at the declaration for record templates (which still resolve) and would move to the depth guard only when
  step 3 lands. Each is pinned by a test naming where the error now comes from.
    - **The real content turned out to be the diagnostics.** An audit of the schema- and data-side messages
      found one leak: the inhabitance chain named derived entries by their content-derived names — `use needs
      tree_text_a7f070f6 needs array_tree_text_a7f070f6_1_f3d1a035` — about a recursion the author wrote as
      `tree<text>` and `[tree<T>; 1..]`. §8.2 makes those names non-normative, so the message named two
      entries nobody wrote. It now reads `use needs tree<text> needs [tree<text>; 1..] needs tree<text>`.
    - `EntryDisplayName` is the fix and was already the rule on the read side; it becomes `public` within its
      unexported package so the linker can share it, and gains a namespace-aware overload so a form built over
      another derived entry renders whole rather than one level deep. Everything else surveyed was already
      clean — the regularity message, the substitution-failure message, and the read-side size and atom
      messages all name what the author wrote.

- [x] **6. A held body writes as the application it holds.** Done, and smaller than this item claimed: there
  is no resolved-output emitter to teach, since nothing in main code writes a schema out — `ResolvedFixtureTest`
  only reads the spec's fixtures in, and `tson compile` prints diagnostics. What existed to break was
  `TsonObjectWriter`, which a template made throw outright.
    - **`AstWriter`** is the missing return leg: `TsonDataParser` went text → AST and nothing came back. It
      joins `TsonObjectWriter` (bound object) and `TsonTreeWriter` (`TsonValue` tree) as the third writer, and
      the only one that writes *syntax* — an AST records what an author wrote, token forms and field order
      included, so it puts them back rather than deciding them again. Package-private in the root `compiler`
      package rather than in `ast`, which would have meant a package cycle (`ast` → `compiler` on top of the
      existing `compiler` → `ast`) and a public class, its caller being in another package.
    - **The writer's rule is about the AST, not about templates**: a `DataValue` is source, so it is written
      rather than bound. Anything carrying a parsed value writes correctly, and `TsonObjectWriter` needs no
      knowledge of `HeldBody`. It joins the existing category — `writeAnnotationValue` already writes a
      structurally-kept annotation through the tree writer for the same reason.
    - **`@Typename("template")` sits on `HeldBody`, not on `TemplateBody`**: `tson-bind` reads the annotation
      off the concrete class, so on the interface it is inert. A body now writes `!template { application:
      !choice { variants: [T error] } }`. Whether it should instead be the application *unwrapped* — which is
      what #5's rule 2 asks for — or a `template` the kernel really declares, is left open there.
    - Still open, and worth naming: the entry around it still writes `position`, which is `@Unbound` and has
      no kernel field, so a written `type_definition` carries something §7.2's closure rule would refuse on
      read-back. Pre-existing and independent of templates.

- [x] **7. A derived entry's failure is reported against the declaration that caused it.** Done, and the
  defect turned out to be older and wider than this item assumed. The case it described — a bad reference in
  a template body — already reported at the declaration, since step 3 is parked and record templates still
  resolve there. What was broken was any failure inside an entry the resolver *derived*, template or not:
  `use => { u: [some_typo] }` reported against `array_some_typo_95c9a10f` with **no position at all**, where
  the same mistake spelled `u: some_typo` landed on `/use` with its line. Every sugar form lifts an entry, so
  this was every schema's problem rather than the open form's.
    - Two halves, both using machinery that already existed. The **name** in the message comes from
      `EntryDisplayName`, so it is the form the author wrote (`[some_typo]`) rather than a content-derived
      name §8.2 makes non-normative. The **location** walks back to the first entry that references it and
      has a line of its own, using the linker's own `collectBodyNames`. It follows references rather than
      `source`, since a lifted form's `source` is the bare constructor it applies and leads away from the
      author rather than back.
    - Runs only when something has already failed, so a clean link pays nothing.
    - What it does not do: land on the *template* for a defect inside a held body. `<T> !array
      { element_type: some_typo }` applied by `use` reports at `/use`, because the walk finds the application
      before the declaration. Correct in the sense that `use` is on the path, and better than the derived
      name, but the declaration is the more useful answer — reaching it needs the minting phase to record
      which declaration each derived entry came from, which nothing carries today.

- [ ] **8. Delete the quotation vocabulary.** `TemplateArgument`, the `TemplateBinding` carrier, and
  `RecordField.valueParam` — plus the `@Typename` binding on the repurposed `InstanceTemplate`, which no
  longer binds against anything, since it is never serialised. `RecordField` returns to a plain
  `Optional<Token> value`.

- [ ] **9. Tests.** The flagship first: `result => <T> ( T | error )` closed via `result<text>`, which should
  yield one instantiation entry with body `!choice { variants: [text error] }` and `disjoint` derived by the
  ordinary machinery. Then `<T> [T, text]`, nested sized forms over a parameter, parametric enum members, and
  `<N> !integer ^ { min: N }` — a form §12.1 currently has to forbid at the grammar and which becomes
  ordinary once a refinement is held like anything else. Three existing tests assert the current refusal and
  must invert: `TsonValidateSchemaTest`, `TsonCliTest`, `ContainerSugarEndToEndTest`. Note honestly that the
  sibling conformance suite has no Part 2 layer, so there is nowhere to put a vector for any of this.

- [ ] **10. Docs, in the same session as the change.** `docs/schema-grammar-and-desugaring.md` (the desugarer
  stops being parameter-aware), `docs/schema-resolution.md` (held bodies, one substitution rule, the deferred
  checking and where its diagnostics land), `docs/linking-and-compilation.md` (what the linker no longer
  sees), and `CLAUDE.md`'s pipeline and "Not yet implemented" sections. Fold in the housekeeping while there:
  `spec/tson-cr-structure-templates.md` is no longer in the repo, but `CLAUDE.md`, `BACKLOG.md` (the
  resolved-form-ingest item above) and two `docs/` notes still cite it by path — re-point them at the
  Revision 33 sections that executed it, or at the addendum that supersedes it.

## Miscellaneous

- [ ] **General resolver-layer structural rules as reusable primitives**, rather than binding-time-only
  behaviour — empty-brace resolution, the absent-vs-missing distinction. §2.8's "the empty container of that
  type" is still a rule each container reader applies for itself: the map reader's own zero-entry case was
  silently exempt from `min_items` until it was fixed one reader at a time, and nothing structural stops the
  next container from repeating it. What a primitive would buy is the rule stated once, where "how many
  entries does this value have" has one answer whatever spelled it.

## Binding strictness

A schema and the Java class bound to it must agree about a type's fields, checked when the schema is compiled
in bind mode — startup, for anything compiling its schemas once. `docs/readers-and-diagnostics.md` has the
rules and `CLAUDE.md` the summary; what is left here is one modelling gap the check exposed.

- [ ] **`precision` and `require_timezone` are carried but not enforced** (`datetime`/`time`). The bodies
  declare them — a field with no component is one this model silently loses — and the parsers *refuse* a
  schema that sets either, so the facet is a stated gap rather than a constraint quietly not applied. What
  remains is enforcement, and both halves need a decision before code: `precision`'s required semantics
  (exact vs. maximum fractional-digit count) are not settled by the spec and want a `SPEC-FEEDBACK.md` entry,
  and `require_timezone: false` needs an offset-less parse path neither parser has (`true` is already the
  behaviour, RFC 3339 requiring an offset on every value these atoms accept).

## Binding profiles

`DataBindContext.Builder.profile` plus `@Profile` on a constructor lets one class bind several versions of a
schema, and `TsonConfig.bindings`/`profile` configure it in one call. Selection is by an opaque label, never
by matching the schema's field set — no serialization library does that, and the parameter names it would
need are not retained for a secondary constructor.

- **Deriving the profile from the schema being read is deliberately not done.** A `Tson` is one profile, and
  routing a document to the right one stays the application's job. The alternative — the schema declaring its
  own profile through a meta-layer annotation — links a *coding* decision to a *format* one and buys less
  flexibility than it costs. Recorded so it is not re-opened as an oversight; reconsider only if something
  needs to re-derive the binding without the application in between.

## Remaining built-in types


- [ ] `unknown` — no compiled-parser factory (`ValueReaderFactoryRegistry` registers it, and `extern`, to
  `ErrorReader`), pinned down exactly by
  `CoreSchemaImportTest.exactlyTheUnknownAtomConstructorCompilesToAnErrorReader`. Not an unwritten atom
  grammar: `unknown` accepts any well-formed value of any type, so what it needs is a reader deferring to
  the document's own type-ref (or to schemaless base-type resolution when there is none) — a design
  question about where that dispatch lives.
- [ ] `extern` ([TSON-SCHEMA] §7.8) — materially bigger than the item above, and a different kind of gap
  again. `Extern` (`schema.meta`) is a record-only placeholder with no
  parsing/validation behavior at all (its own Javadoc says so explicitly: "not to add real
  cross-schema reference resolution"); the real mechanism — a value at an extern-matched position
  carrying its own scoped `!!schema` plus a mandatory `!type` tag, switching schema scope
  mid-document — doesn't exist anywhere in the reader stack.

# Lower Priority

## Atom constraint slots

- [ ] **A quoted numeric is accepted where an integer is declared.** `xs => !array { element_type: float32
  min_items: "3" }` resolves with `min_items` 3, and so does every other integer-typed constraint slot: the
  family's parser reads the token's text and never consults its form, where §4 base resolution makes a quoted
  token a *string* whatever it spells. Pre-existing and unrelated to templates -- found while checking that a
  value type-argument keeps its form, which it does; identity keeps `<3>` and `<"3">` apart, and it is the
  constraint slot underneath that then accepts both. The fix belongs with the atom families, next to
  `AtomNarrowing`: a parser that takes the whole `TokenValue` can reject a quoted token at a numeric slot,
  which is the same shape the width-derived-range checks want.

## Synthetic entry identity

- [ ] **Key-position annotations are lost on the resolved-form round trip.** A schema *source* carries them
  through now: §6's name-position channel — `@doc` before a declared name, and the resolver's own derived
  `@alias`/`@synthetic` — reaches `TsonSchema.entries()` as key annotations (`AnnotatedMap`) and survives
  linking and the import merge. The *document* round trip is what does not: reading a resolved-form
  `{type_name => type_definition}` document back binds the map with no key annotations at all, and nothing
  writes them. `ResolvedFixtureTest` therefore cannot compare the marker the way it compares everything else
  — the Revision 33 fixtures carry `@synthetic` on nine keys and `@doc` on many more, and the bound side
  renders none of them, so the entries would compare equal for the wrong reason;
  `theSameEntriesAreMarkedSyntheticOnBothSides` scans the fixture text instead. Fixing the read side is what
  lets that test read those keys like anything else, and the emit side has the same blind spot waiting behind
  it — [TSON-SCHEMA] §8.1's ingest rule is the consumer of both halves: derived markers are discarded and
  recomputed, author-written key annotations are preserved as data.

- [ ] **Two entries for one type, where the argument is one number spelled two ways.** `vector<float32, 255>`
  and `vector<float32, 0xFF>` produce entries with byte-identical bodies, because identity derives from the
  argument's token text where §4 makes the two one number. Blocked on `SPEC-FEEDBACK.md` #4 rather than on
  effort: normalising numeric tokens before hashing is a three-line change, and doing it now would be this
  implementation inventing an identity rule the spec does not state, disagreeing with any implementation that
  read §5.10's "bare token" literally. The entry offers three resolutions and names the one that keeps both
  the written spelling and §4's equivalence.
- [ ] **Two entries for one type, where both lift channels produce the same form.** A closed lift hashes the
  *unclosed* binding record at desugar; the open lift hashes the *closed* one at materialisation — so
  `[box<text>]` written directly and `[box<T>]` closed with `T := text` land on different names. D6
  anticipates exactly this ("identity is settled after Pass 2 ... eagerly-lifted synthetics that become
  structurally identical under resolution merge into one entry") and that merge pass is not implemented; it
  never had to be, because every form lifted before `[box<text>]` was already concrete at desugar. Down here
  on the spec author's own call: two entries are easier to debug than a merge firing at the wrong moment, and
  it is reachable only when both spellings appear in one schema. Doing it properly means a pass at the end of
  resolution that re-derives each synthetic's name from its resolved record and merges collisions — not a
  patch to naming.

## Schema-side diagnostics

Parsing, desugaring, resolution and linking all report every independent problem in one pass through a
`TsonDiagnosticsReceiver` (issues #3/#28/#29), whether reached by `tson compile`/`Tson.validateSchema` or by
a *data* read whose `!!schema` names a schema that doesn't resolve — both give the same account of the same
broken schema. `docs/readers-and-diagnostics.md`'s "Schema-side diagnostics" section describes the shape and
the decisions behind it. **What has shipped is good enough for now**, which is why this whole section sits
here: the two items below are refinements of a working two-ended diagnostic — how finely it locates itself,
and whether it should carry more than one location at all — not gaps in what it reports. The *floor* under
all of it — the lexer being fail-fast, so a token that will not lex aborts the pass that would have reported
past it — is deliberately not an item in this list, because there is no work to do until someone decides
whether lexer errors feed the `Diagnostic` model at all. `STRUCTURED-OUTPUT.md` holds that question.

- [ ] **`schemaPosition` is one level coarser than the pointer beside it.** A read diagnostic locates
  `/person/age` but positions it at `person`'s own declaration line, because positions are per declaration
  (from the declaration's own name token) and `RecordField` carries none. Closing it means giving
  `TsonSchemaParser` per-field positions and threading them onto `RecordField` — a `schema.meta` bind target,
  so the `@Record` constructor-selection trap and the hand-written `equals` both apply. Nothing in the reader
  stack changes: `SchemaLocation` already carries the pointer that names the field. Same gap for a supertype
  or a choice variant. A *syntax* error is the one exception — it has the failing token's own position, since
  the parser reports it where it stands rather than looking it up per declaration afterwards.
- [ ] **A `caused by` frame, for when the author's location is not the whole story.** A read diagnostic now
  locates the rule where the author can act on it (`/person/age` in their own schema) rather than at the leaf
  the constraint came from (`/int32` in core.tn). That is the right primary frame, but the leaf is genuinely
  informative for a *confusing* error — a deep composition, a refinement chain, a type whose bound is not
  obvious from the field's own line — and it is currently recoverable only from `message`/`expected` prose.
  - The shape to explore is a chain rather than a second flat pair: the primary location, then zero or more
    `caused by` frames each carrying the same four location components, the way rustc's `MultiSpan` and JSON
    Schema's nested `errors` both do. `Diagnostic`'s own Javadoc already cites the first of those as the
    model this type follows.
  - **Which suggests an extended output mode**, rather than making every diagnostic bigger: the default stays
    one frame, and a caller that finds an error confusing asks for the chain. That is a CLI surface question
    (`--explain`? a verbosity flag?) as much as a model one, and it interacts with `diagnostics.tn` being a
    versioned schema — a new frame list is a shape change, so §10's immutability rule means `diagnostics-2.tn`.
  - **The input already exists and is deliberately kept for this.** `TsonLinkedSchema.entryOrigins` answers
    "which document declared this entry", and every reader is already handed its own declaration's location
    (`ValueReaderContext.locationOf`) — today only used as the seed for a value nothing encloses. A caused-by
    frame is what would consume it in the ordinary nested case.
- [ ] **`TsonSchemaSource.fetch` mandates no exception type, which costs a read one distinction.**
  `SchemaFailure` classifies a failure to obtain a compiled schema — `BIND_MISMATCH` for a schema and the
  reading application's classes that disagree, `NOT_IMPLEMENTED` for a construct beyond this library,
  `SCHEMA_ERROR` for everything else. That last branch is a default rather than a positive verdict: a
  source is free to signal an unfetchable schema with any `RuntimeException`, so an unfetchable schema and
  a broken invariant are indistinguishable by type at that boundary, and a real fault in a resolve or a
  compile reads to a consumer as a problem with the schema. Both other classifications in the codebase
  (`Diagnostic.ofBaseSyntaxError`/`ofSchemaSyntaxError`) end `default -> throw e` on the rule that a fault
  propagates as itself; this is the one place that cannot.
  - The fix is at the `fetch` contract, not at the classification. Either the interface names the exception
    a source must throw for "cannot supply this" (`TsonSchemaValidationException` — which is already what
    the shipped `registeredOnly()` throws, and its Javadoc already argues the case, in exactly these terms),
    or `resolveUncached` wraps whatever `fetch` throws in one. The second is compatible with sources that
    already exist and is probably the answer; the first is cleaner and is a breaking change to a public
    functional interface.
  - Low priority while `registeredOnly()` is the only implementation and a real disk/HTTP-backed
    `TsonSchemaSource` is itself unbuilt — the two should be decided together, since a fetching source is
    what makes the failure modes here plural.

## Write side

The read/write matrix in the README makes the asymmetry plain: the read side has a schemaless→object
reader, a schemaless→tree reader, a schema-driven *validating* reader, a pull-event stream, and both
fail-fast and collecting/diagnostics modes; the write side has only the two schemaless writers and is
missing most of the mirror. Two pieces of it are now there — both writers take an `OutputStream`/`Appendable`
sink (`toTson` being the wrapper), and both can emit a document header (`describing(…)`), so what they write
can say what governs it. What is left below is the schema-aware writer, diagnostics, and a public event
surface.

- [ ] **No schema-aware (Class 2) writer — `TsonValueWriter`.** Only the schemaless `TsonObjectWriter`
  (object → TSON) and `TsonTreeWriter` (`TsonValue` → TSON) exist, both with documented lossy spots
  (integer width, tuple-ness). A writer symmetric to the
  compiled reader stack (`TsonSchemaCompiler`/`TsonTypeReader`) — checking output against a TSON schema
  and reporting what's wrong — is a whole missing half of the pipeline, and the natural home for
  round-tripping or producing guaranteed-conformant documents.
    - It is also where `describing(schemaUri, rootType)` stops needing its arguments. A bind-mode registry
      already holds the compiled schema and the class→type binding, so a schema-aware writer could derive
      both facts instead of having the caller name what the library already knows. The explicit form stays
      either way — a caller writing against a schema it did not compile here has nothing to derive from.
- [ ] **Writers are fail-fast only, no diagnostics.** They throw `TsonWriteException` at the first
  problem, with nothing symmetric to the read side's `TsonDiagnosticsReceiver`. The `TsonValueWriter`
  above especially needs it, to report every schema violation in one pass the way the reader does — and
  the seam already exists and is write-direction-agnostic (`Diagnostic` carries a data path and both
  positions; nothing about `void report(Diagnostic)` assumes reading), so this is a matter of threading a
  receiver through the emitter, not designing a second error model.
- [ ] **No public push/event writer.** The read side exposes a pull `TsonDataStream` (→ `TsonEvent`);
  the only emitter, `TsonDataEmitter`, is internal. A public event-driven writer would let a caller emit
  TSON without first building a whole tree or object — the write-direction peer of `TsonDataStream`. Closer
  than it was: the emitter now writes into any `Appendable`, so what is missing is the decision to make it
  (or an event-shaped facade over it) public API, not the streaming underneath.
- [ ] A JSON writer (TSON data → valid JSON text) — the write-direction companion to
  `STRUCTURED-OUTPUT.md`'s "JSON compatibility" section, tracked here alongside the general writer
  since it's the same underlying gap (no schema-aware writer exists at all yet).

## Conformance test suite

- [ ] Build out `ltr8-io-tson-test-suite` well beyond its current 127 vectors, spread across four buckets
  (`lexer`/`parser`/`resolver`/`vocabulary`). Still Part 1 (lexer/parser/§5 vocabulary) only — Part 2
  (resolution, linking, compilation) has no conformance-suite coverage at all yet, only this repo's own
  unit/integration tests.
- [ ] **Run the JSON front-end against the established JSON Parsing Test Suite** when it lands (the
  `TsonJsonParser` tracked in `STRUCTURED-OUTPUT.md`). JEP 540 commits to exactly this for the JDK's own
  parser — its own unit tests *plus* that external corpus, "which contains numerous edge-case inputs" —
  and the reasoning is the same one this repo's sibling suite exists for: an external, language-agnostic
  fixture set catches drift a self-authored suite agrees with. Cheap, since the corpus is pass/fail on
  parse and the front-end's whole job is RFC 8259 conformance.

- [ ] **Retrofit the ~110 existing sidecars with a real `!!schema` directive** pointing at the new
  per-layer schemas above, and fix whatever real shape mismatches that validation surfaces —
  explicitly *not* done in the same pass that wrote the schemas (see the sibling README's own note
  on this), so today the schemas are resolver-verified documentation, not live validation of the
  actual fixtures. **Worth doing sooner rather than later, per the user's own explicit reasoning**
  (2026-07-29): only ~110 files exist today, cheap to migrate; that stops being true once the suite
  grows into the thousands, so if the wire format itself is going to change (see the next item), now
  is the cheap moment to do it, not after the corpus is large.
- [ ] **A precise, `outcome`/`kind`-correlated sidecar shape needs a real design decision first,
  not just a retrofit** — the flat, all-optional modeling these schemas use today (`core_value`'s
  `kind` plus every variant's fields all `OPTIONAL` side by side) doesn't enforce that `kind: token`
  implies `form`/`text` present and `fields`/`entries`/`elements` absent, and so on. Three ways to
  actually get that enforcement, discussed with the user 2026-07-29, in order of how much the real
  wire format would have to change:
  - `!choice { variants: [...] }` — no longer blocked: choice resolves, links, compiles and reads. But the
    reservation that always sat behind it stands and is the deciding factor here — a schema built on it
    needs a `!typeName` tag unless every variant occupies a distinct base-type class, and `core_value`'s
    variants are all records, so every one of them would be tagged on the wire.
  - **Field groups** (§5.11) — confirmed empirically to work today, including with a record-typed
    member (`core_value => { ( token: core_value_token | record: core_value_record | ... ) }`,
    resolves cleanly, each member individually `OPTIONAL`, mutual exclusivity captured in the
    entry's own `groups`). The real cost: the group member's own *name* is what discriminates, so
    each variant's fields would have to move under a keyed sub-object (`{ token: { form: ...
    text: ... } }`) instead of today's flat `{ kind: token form: ... text: ... }` — a genuine wire
    format break for every existing sidecar, not just a schema change.
  - **Dependent typing at the meta-schema level — the user's own proposed direction, already on
    their own "not yet" list before this discussion, and the one that best fits this specific case**:
    `kind` stays exactly as it is today (a plain enum field, flat, no wire change at all) — the new
    mechanism is a meta-schema-level association from an enum's own *member* to a type, so a
    companion field's actual type is resolved from whichever member `kind` currently holds, rather
    than from the field's own static declared type or its own name. Lets a schema designer decouple
    "which type validates this value" from "what this field happens to be named" — relevant well
    beyond this one schema, including for JSON-facing discriminated unions (see
    `STRUCTURED-OUTPUT.md`'s own "The real sharp edge: untagged unions" item, extended with this
    same mechanism). This is real, unstarted design work at the meta-kernel/meta.tn vocabulary level,
    not a `DefinitionResolver` bug fix — tracked here and in `STRUCTURED-OUTPUT.md` so it isn't lost,
    not scoped or planned yet.
- [ ] **`vocabulary-sidecar.tn`'s own `value` field is a known simplification** — typed as plain
  optional `text`, which doesn't capture the two atom families (`complex`, `duration`) that actually
  write `value` as a small nested record on the wire. `!choice` now resolves, but this was never the
  item waiting on it: a precise per-family shape needs a discriminator on `type-ref` itself — an open
  ~30-name vocabulary, not a small closed enum like `outcome`/`kind` — so it isn't the free win the
  `outcome`-discriminated shapes are.

## Documentation

- [ ] User-facing documentation on how to use the library — today only `CLAUDE.md`'s own dense,
  session-oriented internal narrative exists.
- [ ] AI skills for using the library.
- [ ] `@doc`-driven documentation generation (render a schema's own `@doc` annotations). **Unblocked**:
  every `@doc` string across the three bundled schemas survives resolution and linking, reachable as
  `schema.entries().getAnnotations(name).value("doc", String.class)`, and core.tn documents every one of its
  declarations. What's missing is the renderer, not the data.

## Front door / ergonomics

- [ ] A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy — today the only
  `TsonSchemaSource` is `TsonSchemaSource.registeredOnly()` (nothing fetched); the bundled standard
  library is served internally by `TsonCompiledSchemaRegistry` from `TsonBundledSchemas`, not through
  a source.

## Tree model (`TsonValue`)

The tree model itself is built and described in `docs/facades-and-tree.md`'s "Tree model" section. What's left:

- [ ] **A `TsonValue` does not carry the schema its document named.** A schema-driven read records each
  node's *type* — which is what lets `treeWriter().describing(uri)` write the root's `!typeName` back — but
  the document's `!!schema` is consumed by the reader and kept nowhere, so a caller round-tripping a tree has
  to have held onto the URI themselves. The residue of the write-side header work (issue #104): a document
  this library reads can now be reproduced, but only by a caller who still remembers what governed it.
    - The decision is *where* it lives, and both answers cost something. On the root node makes `TsonValue`
      document-aware, which it deliberately is not — every node type is a pure value, and a schema reference
      is a property of the document (§2.2 says so of the directive itself). In a document wrapper the readers
      return instead, which keeps the tree pure but changes what `read` hands back, and every caller with it.
    - Low, because the workaround is to keep the URI you already had to have in order to read the document.
      It stops being low if a *reader* ever needs to hand a tree to something that must re-derive the schema
      without the caller in between.
- [ ] **Copy-on-write transforms + builders (parked).** The "new tree from old" editing half —
  `TsonRecord.with(name, value)`/`without(name)`, `TsonArray.with(i, value)`/`plus(value)`/`without(i)`,
  `TsonRecord.builder()`, and a pointer-based `set("/a/b", value) → new tree`. All pure `tson-tree`
  operations (no compiler dependency), so they belong in that module. Deferred until there's a concrete
  produce/edit use case: `TsonTreeWriter` already closes the read→edit→write loop, so these have a real
  payoff when wanted, but block nothing now.
    - **Nothing to copy from JEP 540, and that is the useful part.** It ships no transformation API and no
      builders at all — construction is static `of(...)` factories, which `tson-tree` already matches — and
      its Risks section defers the area outright: "During the incubation period, we will gather more
      information about use cases involving generating and transforming JSON documents, in order to evolve
      these areas of the API." The JDK reached the same "wait for real use cases" conclusion independently,
      which turns this item's deferral from a shrug into a decision.

## Miscellaneous

- [ ] Thread-safety **outside a read**. Concurrent *reads* through one `Tson` are safe and tested
  (`ReadPathConcurrencyTest`): the compiled readers are immutable, a `Lexer`/`TsonDataStream` is per read,
  and the two on-demand caches — the schema registry and `DataBindContext`'s descriptors — now settle a race
  by keeping one entry instead of failing the loser. A read now also takes **no lock on the caches it hits**
  (`TsonSchemaRegistry` is a `ConcurrentHashMap` read without a monitor; the compiled cache does a `get`
  before `computeIfAbsent`), which measured small here — ~6% at 32 threads on 16 CPUs, nothing below that —
  and matters at a core count this machine does not have. What is still open is deliberate mutation while others
  read: `Tson.resolve`/`TsonSchemaRegistry.register`/`registerAtom` stay strict about duplicates (right for
  a caller error, wrong if two threads are legitimately warming the same registry), nothing defines whether
  a `DataBindContext` may be extended after first use, and a future fetching `TsonSchemaSource` will have
  its own story. None of it is hypothetical-only: the read-path half was two real defects, found by
  auditing and reproduced first try on 8 threads.
- [x] **Read-path garbage, profiled and attributed** — three of the five items below are done, taking a
  346-character bind read from **61.8 KB to 47.8 KB allocated (-23%)** and 19.5 to 15.4 µs. None of it ever
  survived a collection (retention is a measured 0 bytes per read, and a JFR recording of 300,000 reads
  holds one `OldObjectSample`), so this is throughput and GC pressure, not a leak. Done, each measured on
  its own by reverting it:
    - **Read in blocks, not one character at a time** — **12.1 KB/read**, the largest single item on the
      path. `Lexer` called `Reader.read()` per character and `InputStreamReader` allocates a `char[]` plus a
      `CharBuffer` on every call. Guarded by `AllocationHarnessTest.lexingDoesNotAllocatePerCharacterOfInput`
      (50.9 → 11.0 bytes per character of input).
    - **One URI canonicalization per read** — **1.1 KB/read**. It was three: the compiled-schema cache's
      key, the resolution cache's key, and the schema registry's own lookup, each a `new URI(...)` parse.
    - **Precomputed integer bounds** — **0.5 KB/read**. `BigInteger.TWO.pow(bits)` was rebuilt per value
      validated for a bound fixed by the type.
    - **A caveat on the method, worth keeping:** JFR's per-site attribution put the last two at ~3 KB each,
      about 3x and 6x their measured worth, while its *total* matched the harness within 1%. At that sample
      density the aggregate is trustworthy and an individual small site is not — take a site as a pointer to
      where to look, then measure the change by reverting it.

  What is left, in the same order:
    - [x] **Decode UTF-8 in `Lexer` itself** — done. The lexer reads 512-byte blocks off the stream and
      decodes them, with no `InputStreamReader` between: **-9.2 KB per read** (35,320 → 26,152 bytes; the
      token stream alone 23,264 → 13,955). The reason to do it before a port was that a port writes this
      loop anyway, and that §8.1's byte offset was being re-derived from the decoded character rather than
      counted from the input. It also settles a question the spec leaves open: malformed UTF-8 is now a
      lexer error rather than a U+FFFD substitution, overlong forms and encoded surrogates included
      ([TSON-DATA] §7.1).
    - [x] **Build the diagnostic path lazily** — done. A step of the descent is a linked node and both RFC
      6901 pointers render only when a diagnostic is built: **-1.7 KB per read** (26,152 → 24,488 bytes).
      The part that matters for a port is not the bytes: concatenating a step onto the last is quadratic in
      nesting depth, and every read of a valid document — nearly all of them — threw the result away
      unbuilt. `AllocationHarnessTest.nestingCostsTheSameAtEveryDepth` prices a level of nesting shallow and
      deep and requires the two to agree, which is the property rather than a byte count.
    - [x] **An escape-free quoted token is not copied twice** — done. **-1.0 KB per read** (24,488 →
      23,464 bytes), and half the per-character cost of a long quoted token (10.5 → 5.8 bytes per character
      of input). The scanner already knows whether it consumed a backslash, so the decode pass runs only
      when there is something to decode.
    - [x] **The `Optional` per `peek`/`next` is gone** — the cursor holds the event's own `SourcePosition`
      and `position()` wraps it when asked, which is once per diagnostic rather than once per pull:
      **-2.6 KB per read** (23,464 → 20,840) for four lines in one file, the best ratio in the exercise.
    - [ ] Smaller sites the same profile named, none yet measured on its own: `Token` snapshots per token
      (a two-slot lookahead paying per token), the `Position` inside every event (structural — it makes
      `TsonEventSource` a cursor), and `DateTimeParser` building a `HashMap` per value read.

  **Where the read path stands after all four** (346-character self-describing document, bind read):
  61,824 → **23,464 bytes** and 1,213 → ~800 objects per read, 19.5 → ~13 µs, with retention still a
  measured 0. Two conformance fixes came out of the work and matter more than the bytes for something about
  to be ported: a zero-led complex magnitude is accepted (§7.6), and malformed UTF-8 is refused rather than
  silently replaced ([TSON-DATA] §7.1).
- [ ] Confusable-character and bidi-formatting-character checks (§9.4-adjacent security hardening;
  opt-in, and reported as ordinary errors when enabled — §8.1 gives a conforming processor one severity) —
  the sibling gap to the numeric-literal length limit tracked in `STRUCTURED-OUTPUT.md`'s Tier 1 section;
  neither is enforced anywhere yet. `SPEC-FEEDBACK.md` #3 is the fuller treatment: which UTS #39 mechanism
  applies where, the comparison scopes TSON can actually name, and why a normative requirement would oblige
  every implementation to ship UCD data the JDK does not expose.
