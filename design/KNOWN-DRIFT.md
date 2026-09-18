# Known drift in the design notes

A worklist, not a note: statements in `design/` (and a few in Javadoc) that contradict the code or each other, found
while the notes were split by subject. Each is a claim to check against the code and then fix *in the note it names*;
delete the entry when it is fixed, and delete this file when it is empty. Where an entry says "`CLAUDE.md` says", that
text now lives in the area's note, usually beside the statement it contradicts.

Two kinds recur. **Stale facts** — a class renamed, a gap since closed, a mechanism since removed (§8.3 use-site
flattening is the commonest: references are hops now). **Change-history narrative** — "used to", "now", "was removed",
PR numbers — which the current-form rule forbids and the verbatim split deliberately carried over.

Also: `TsonValidateTest`'s Javadoc (near "`regex_type` object-binding") cites a gap and a `CLAUDE.md` section that no
longer exists.


## Facades, writers, tree, front door

- **Wrong reason in CLAUDE.md.** Its sentence "These live in `tson-compiler`'s root package because `DefinitionResolver`
  depends on `TsonObjectWriter`" contradicts the note and CLAUDE.md's own Traps section, which say the resolver uses the
  `DataClassObjectWriter` engine and never the facade. I did not copy that sentence into a note.
- **`ProcessorConfig` setters contradict each other.** One front-door bullet says "`ProcessorConfig` … has one setter,
  `withSchemaAccess`". The `SchemaAccess` bullet beside it says
  "`schemaSource`/`httpSchemas`/`fileSchemas`/`fetchPolicy` are the short forms that assemble one, and naming both is
  refused", and also refers to "the precedent `bindings`/`dataBindContext` already set". `ProcessorConfig.java` has no
  such setters; they exist only on `SchemaAccess`. `bindings` is described elsewhere as removed.
- **Change-history narration**, which breaks the current-form rule. I left these alone because the task required
  verbatim moves.
  - `front-door-and-config.md`: the agreement-check bullet ("ran under a `lenientBinding` flag … the flag is gone",
    "each lose a parameter", "loses an overload"), the `DataBindContext` bullet ("It used to be a method on the built
    context … which is what closes `BACKLOG.md`'s standing question"), and the `validate` sub-bullet ("The old direction
    buffered…").
  - `facades-and-tree.md`: the dispatcher bullet ("Consuming the framing was the whole problem … Tree mode papered over
    it"), "`AnnotationTypes` now separates them", and "indistinguishable from the old behaviour".
  - `writers-and-document-header.md`: "`TsonDataEmitter` gained …", "`typeRef` now refuses", and "which is what used to
    pin the facades to this module".
- **Sink bullet contradicts itself.** One sentence says `TsonDataEmitter` "holds an `Appendable` rather than its own
  `StringBuilder`" and mentions "the encoder's own buffer". The byte-path bullet says the emitter takes a `ByteSink` and
  encodes through `Utf8Sink`, not a JDK encoder. The first reads as written before the byte path existed.
- **`SchemaSource` module is misstated.** The `SchemaFetchException` bullet places the `SchemaSource` interface "in
  `tson-compiler`". CLAUDE.md and the same note's `base.source` wording put it in `tson-base`
  (`io.ltr8.tson.base.source`).
- **Wrong class and package in a `validate` sub-bullet.** It says `Diagnostic.ofBaseSyntaxError` is "in the root package
  because two of the three exception types live in the unexported `lexer` package". CLAUDE.md says the classifying
  factories moved to `TsonDiagnostics`, and that `ParseException` now sits in `tson-base`. The same sub-bullet points at
  a `BACKLOG.md` item about `tson validate` that may no longer exist.

## Linking, compilation, registries

- a. The hygiene text opened with "**And** the restriction level is refused per name, in the same pass", with no
  antecedent. I moved the "All three of §8.2's rules run in one walk" paragraph ahead of it so the "And" has something
  to follow. The text is unchanged.
- b. `class2-compilation.md` still says "An open entry compiles to `OpenTemplateReader`, before its body is looked at at
  all … Reaching it is **always** a data error", which contradicts the `AbstractTemplateReader` fact I added.
- c. `MissingBindingException` is described as "the one cause that still throws"; CLAUDE.md names two deliberate
  exceptions.
- d. The `ErrorReader` bullet gives as a real cause "a constructor with no registered factory (the undocumented atom
  families)". CLAUDE.md and the `Data` note both say the atom vocabulary is complete and that a meta-schema's unknown
  constructor is the only route to an `ErrorReader`.
- e. Change-history phrasing that breaks the current-form-only rule appears in all six files. Examples:
  - "was built and discarded during PR #36's review"
  - "a general `tson-bind` fix (#121)"
  - "which `TsonCompiledMetaSchema` used to do"
  - "That last used to be unguarded"
  - "Its old converse"
  - "which it did, until the first end-to-end test"
- f. The linking bullet says variants are checked "*after* §8.3 flattening". The class table and the `void` rule use the
  same wording. CLAUDE.md says §8.3's use-site flattening is gone and that "a reference is a hop, not a rewrite"; the
  behaviour described is a reference-chain walk, so only the wording is stale.
- g. The `spec/m` hash-pin paragraph sits at the end of the `Data` section and has nothing to do with `Data`. I left it
  there; it belongs in `design/cli-config-hashing.md`.
- h. The `Data` section describes `ProcessorConfig.withMetaNameBinder`, while CLAUDE.md writes
  `ProcessorConfig.metaNameBinder`. The setter is `withMetaNameBinder`, so the note is right and CLAUDE.md is loose.
- i. "Scope is structural … that is its constraint family's question (`BACKLOG.md`)" reads as owed work. CLAUDE.md says
  `Atom.coherenceCheck` already does it.

## JSON encoding

- **Lexer and stream entry point.** The Lexer section says `JsonLexer` reads "from an `InputStream`", and the Structural
  section gives `JsonStream(InputStream, ProcessorPolicy, DiagnosticsReceiver)`. The code is `JsonLexer(ByteSource)` and
  `JsonStream(ByteSource, …)`. The front-door section already says `ByteSource`.
- **Nesting bound.** The Structural section says the bound "comes off the policy rather than as a number", and two
  paragraphs later says it "arrives as an `int`".
- **Policy derivations on `JsonObjectReader`.** The Structural section says `withProcessorPolicy` is its only policy
  derivation (true in code). The token-policy section says `JsonObjectReader.withTokenPolicy` is the surface; no such
  method exists.
- **`Json.withProcessorPolicy`.** CLAUDE.md's diagnostics section says it "takes the same value". The note says that
  method was removed, and `Json.java` has none.
- **Schema access.** "One configuration" says the schema access "waits on §5–§8's schema-directed decode". "The front
  door" says `Json` "holds no schema registry because there is nothing yet to register". `Json.withSchemas` and
  `JsonCompiledSchemaRegistry` exist.
- **Annotation object.** "Binding a document" says the in-band route needs §3.3's annotation object, "which arrives with
  §8". The annotation object is described as built a few paragraphs later.
- **When the policies arrive.** "The Unicode policies" says both "arrive with the schema-directed decode". `NameHygiene`
  exists and the token policy is already in `JsonStream`.
- **Sealed record families.** "Discrimination" says sealed-family member dispatch "is unbuilt — so a record family
  declaring a discriminator currently reads as an ordinary record". That contradicts `TreeRecordSealedReader` described
  in the section above it. "All the way up" likewise says `@discriminator` stays unbuilt.
- **`JsonReadContext`.** "Binding: a facade" says it has "no lookahead or rewind" and "no name policy yet". That
  contradicts `JsonReadContext.lookingAhead` and `checkNameHygiene`.
- **Misplaced paragraphs.** Under `## Naming inside reader`, the paragraphs from "Recognising one needs a rewindable
  lookahead" through the `CompiledReaders` paragraph belong to the preceding `### The annotation object` subsection;
  "one" refers to an annotation object. I left them where they were, since the move had to be verbatim.
- **`AtomContext` location.** The atom-vocabulary section says it lives in `tson-atom`; CLAUDE.md puts it in
  `tson-base`'s `bind` package.
- **Packages.** The `reader` row omits the schema-directed readers, there is no row for the `writer` package or the
  writers, and "`atom` ... will grow" reads as future tense.
- **History narrative.** Several passages break the current-form-only convention: "What was removed to get here",
  "`Json` used to reduce events…", "became a diagnostic in the move", "It earned its place on the first run", "used to
  be one message", "One thing the CLI surfaced that the library owed … It now leads", and "The TSON facades carry all
  four for history".
- **CLAUDE.md "Not yet implemented" JSON bullet.** "The lexical layer is built; everything above it is owed" is stale
  against the note and the code.

## Lexer, grammar, desugaring, CLI

- **`base-types-and-atom-vocabulary.md`**
  - The heading "Built-in atom vocabulary (`tson-compiler/.../atom/`)", "a same-named `*Parser` in `atom`" and
    "`read(TokenValue)`" contradict the same file's "`tson-atom`, not `tson-compiler`" and "`AtomType` takes a
    `String`". CLAUDE.md says the parsers are in the unexported `atom.parser`.
  - The note lists `unit`'s three instances as `value`/`token`/`void`, quoting §4.2. CLAUDE.md says `AtomParsers`
    answers for `identifier` and declines `value` and `void`. The added paragraph now sits beside that discrepancy.
  - "`SPEC-FEEDBACK.md` #8, a second departure beside `email`" conflicts with CLAUDE.md's "`email` is a built-in of §5.5
    like its siblings".
  - The CIDR text places the network types in `base.atom`, which is `tson-base`. The absorbed CLAUDE.md bullet says the
    network value lives in `tson-schema`. CLAUDE.md is inconsistent with itself on this.
  - "§9.1's numeric-literal length limit… is not enforced" should be checked against §9.1's table and the BACKLOG.
- **`lexer-and-data-parsing.md`**
  - The note says §7.1's "prose excludes them by name" for ZWNJ/ZWJ; CLAUDE.md says "§7.1 admitting them on that basis".
  - "`!!meta` in the header throws `TsonUnsupportedDocumentException`, not `TsonParseException`" uses the pre-rename
    name. CLAUDE.md's module section says the shared type is now `ParseException`, though its own pipeline paragraph
    still says `TsonParseException`.
  - CLAUDE.md's lexer paragraph has a garbled sentence: "§7.2 rule 1 folds them into horizontal space would be what
    let…".
- **`schema-grammar.md`**
  - "One production per container… The grammar used to spell each twice" is followed by "The map sugar is parsed twice
    for the same reason — `MapContainerDef`… `InlineMapRef`", which also mentions the removed `ElementType.Expr`.
    CLAUDE.md still says "the bracket form is parsed twice… and the map sugar twice". One of the two is stale.
  - The key-type sentence ("A map key stays `type-name […]`…") appears in two adjacent bullets.
  - "Three ABNF defects were implemented… before the spec caught up", "`atom-refinement` followed late", "It used to
    have two companions" and "The resolved form does not exist yet, so `DefinitionResolver` refuses one by name" are
    history or likely stale, given that every template now holds its body.
- **`schema-grammar-and-desugaring.md`**
  - The choice/tuple bullet cites "§8.3 flattening… (`ReferenceFlattener`)". CLAUDE.md says §8.3 use-site flattening is
    gone.
  - CLAUDE.md says applying a template "is rejected at the site that writes it, an imported head included". The note
    says `checkTemplateApplication` refuses exactly one thing, a local head with no parameters, and that applications
    pass through. I did not add the CLAUDE.md sentence.
  - The "structure-templates CR / D3 / D5 / D7 / D8" passage, "The phase used to read that routing off the governing
    meta" and "the `hoistNested`/`exprRef` pair this replaces" are change-history framing.
  - The size-specifier bullet has a duplicated clause: "§5.3 calls the form vacuous and rejects it: §5.3 makes `0..` a
    resolver error".
- **`cli-config-hashing.md`**
  - "Fully self-describing: no `--type`" sits beside the new JSON section, where `--type` exists for JSON inputs.
    CLAUDE.md has the same tension.
  - The configuration section says `tson-compiler/.../config/` holds `AtomContext` and mentions `Tson.dataBindContext`.
    CLAUDE.md places `AtomContext` in `io.ltr8.tson.base.bind`.
  - "`fetch` doesn't implement `SchemaSource` (that would need a `tson-compiler` dependency)" is stale: `SchemaSource`
    is in `tson-base`.
  - CLAUDE.md's exit-code summary omits 75 and 78, which the note has.



## Readers and diagnostics


Contradictions with `CLAUDE.md`:
- `schema-side-diagnostics.md` says `DataBinding.lenient()` is the opt-out from the agreement check. `CLAUDE.md` says
  there is no wholesale opt-out, no `DataBinding` class or `lenient()` method exists in the source, and the statement
  now sits directly after the added "no wholesale opt-out" sub-bullet.
- `schema-side-diagnostics.md` says "Seven codes are not a verdict". `Code.verdict()` also excludes `LIMIT_EXCEEDED`,
  which makes eight.
- The "Records are closed" bullet in the core file says closure is "Not configurable" and that schemaless records "never
  reach this code". The schemaless bind reader reports `UNRECOGNIZED_FIELD`, and `ignoringUnknownFields()` exists.
- `processor-policy.md` says `LimitsPolicy` sits "Beside the Unicode policy, not inside it", which conflicts with the
  "It carries three settings" paragraph in the same file and with `CLAUDE.md`. The same paragraph also reads "while it
  was named `ProcessorPolicy`", which garbles a rename and is change-history narrative.

Stale names and claims in the notes:
- Class names that no longer exist:
  - `TsonBindMismatchException` in `name-hygiene-read-path.md`.
  - `TsonSchemaSource.fetch` in `schema-side-diagnostics.md`.
  - `TsonParseException` and `TsonSchemaValidationException` in several places. Base now has `ParseException` and
    `SchemaValidationException`.
- "What a read leaves behind" blames "one `InputStreamReader` per read" for the fixed cost. The lexer decodes UTF-8
  itself, and no `InputStreamReader` appears in any main Java source; only a test file mentions it.
- `name-hygiene-read-path.md`:
  - It calls the token policy "that decorator" right after saying it stopped being a decorator.
  - It opens with the "unbindable target class is `BIND_MISMATCH`" paragraph, which is not about name hygiene.
- `diagnostic-model.md`:
  - The `Code` list leaves out `FIELD_FIXED`, `LIMIT_EXCEEDED`, the three hygiene codes and the `ATOM_FORM_INVALID` /
    `ATOM_CONSTRAINT_VIOLATION` split.
  - It says `TsonReadContext.report` has a five-argument form "existing only to carry" the fetch reason and also that
    the overload is gone. I did not check which is true.
- `reader-naming-and-schema-location.md` says "§8.3's use-site flattening rebuilds every `RecordField`". `CLAUDE.md`
  says that flattening is gone.
- The map-value `_` bullet in the core file says §7.6 and §5.3 "still" contradict and cites "#12" as built ahead of the
  revision. `CLAUDE.md` says §5.3 carries that row now.

Change-history narrative that the no-history convention forbids:
- Core file: "the old behaviour", "no pre-seeding pass any more", "What was removed instead".
- `processor-policy.md`: "What it replaced".
- `diagnostic-rules-and-messages.md`: "What the sharing found, on the first run".
- `record-dispatch.md`: "It was skipped…".

Other:
- Line 50 of `processor-policy.md` starts with `#39's`. It is not a CommonMark heading, but a `^#` grep matches it.

## Resolution and templates

- a. In `template-materialisation.md`, the original sub-bullet under "Kind checking falls out of substitution" says:
  - a literal at a type position is refused "as an unresolved reference";
  - a type name routed into a field's value "is accepted", deferred to `BACKLOG.md`'s FIXED/DEFAULT validation.
  
  CLAUDE.md, and the paragraph now added directly beneath it, say both are refused: the first by the `identifier` typing
    of `type_ref.name`, the second by `TsonSchemaLinker.checkFieldValue`. The older bullet is stale.
- b. In `schema-resolution.md`, the marks bullet says a template "takes exactly one of them" and that `@sealed` and
  `@final` on a template are "a schema error". The very next bullet, and CLAUDE.md, say `@sealed` on a template is
  accepted and only `@final` is refused.
- c. In `schema-resolution.md`, the "Two exception types" bullet still lists "a generic type-ref with a nested or value
  argument, a parameterized supertype" as `UnsupportedOperationException` gaps. Both resolve now. The same bullet
  mentions a `constructor: true` entry, although the constructor-application text says "there is no marker".
- d. In `schema-resolution.md`, the `TypeArgument` bullet says `tson-bind` has "no cycle protection" and that the sealed
  shape "is the one shape that binds at all". CLAUDE.md's Traps section says `DataBindContext` has a cycle guard
  (`Memoized`) and the shape now rests on the modelling argument alone.
- e. Flattening is described as gone in "References are hops", but is still described as live in two places. CLAUDE.md's
  SyntheticMerge paragraph says the pass runs "between materialisation and flattening".
  `resolver-vocabulary-and-bootstrap.md`'s `MetaRefs` paragraph says "§8.3 flattening rewrites a use site".
  `schema-grammar-and-desugaring.md` line 211 (not mine) cites a `ReferenceFlattener`.
- f. CLAUDE.md says non-regular recursion "is caught by a depth guard". The note says `TemplateRegularity` rejects it
  statically at the declaration and the depth guard is only a backstop.
- h. In `resolver-vocabulary-and-bootstrap.md`, the bootstrap section says meta-kernel has "eight sugar forms" while the
  `@synthetic` section says "nine synthetics" and "nine keys".
- i. `resolver-vocabulary-and-bootstrap.md` says substitution keys on a token being unquoted; `held-template-bodies.md`,
  under "Substitution is one rule, at every depth", says "Quoting does not enter into it".
- j. The temporal-bounds sub-bullet in `atom-refinement-and-coherence.md` describes `!date ^ { min: … }` failing with a
  `ClassCastException`, surfaced as `UnsupportedOperationException` at exit 70, "no temporal bound can be written at all
  today". That sits against the statement that no `NOT_IMPLEMENTED` is reachable from a schema, so one of the two is
  stale.
- k. Several passages carry change-history narrative against the no-history convention ("used to", "the old
  `constructor` flag", "There were three", "~75 lines"). They were left verbatim, as instructed.
