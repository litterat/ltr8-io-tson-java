# CLAUDE.md

Orientation for Claude Code sessions in this repo. It describes the code **as it stands** — current
form, present tense. How it got here lives in git history and `BACKLOG.md`, not here; when a design
choice has a non-obvious *why*, the current rationale is stated directly rather than the sequence of
edits that produced it.

**This file is deliberately an overview.** The full per-phase design detail lives in `docs/` (map below)
and in class Javadoc. **Before working in an area, read its `docs/` note** — each note carries the
invariants, spec-feedback citations, and deliberate divergences for that area at the depth this file used
to. Trust but verify: the code is the source of truth if a note has drifted.

| Area | Design note |
|---|---|
| Lexer, Tier 2/3 data parsing, base type resolution, atom vocabulary | `docs/lexer-and-data-parsing.md` |
| Schema grammar, desugaring | `docs/schema-grammar-and-desugaring.md` |
| Schema resolution, template materialisation, meta-kernel bootstrap | `docs/schema-resolution.md` |
| Identity, linking, registry, Class 2 compilation, compiled registries | `docs/linking-and-compilation.md` |
| Streaming readers, read context, diagnostics (data- and schema-side) | `docs/readers-and-diagnostics.md` |
| Read facades, writers, tree model, `Tson` front door | `docs/facades-and-tree.md` |
| CLI, config package, bundled schemas, content hashing | `docs/cli-config-hashing.md` |

## Project

A from-scratch Java implementation of TSON (Typed Schema Object Notation), built directly against the TSON
spec series (2026 revision):

- Part 1 — lexer, structural grammar, base type resolution, built-in type vocabulary:
  https://tson.io/raw/2026/34/tson-part1-data.md
- Part 2 — schema grammar, type system, resolution, linking, compilation:
  https://tson.io/raw/2026/34/tson-part2-schema.md

The spec is a *working revision* that changes between revisions without compatibility guarantees. When in
doubt, **re-fetch the current URL** and check the revision number at the top rather than trusting a cached
copy. `spec/` holds local snapshots of the current revision for quick reference: `spec/tson-part1-data.md`,
`spec/tson-part2-schema.md`, and `spec/m/{meta-kernel,meta,core}.tn` (the spec's own bundled schema
documents — the meta-kernel bootstrap layer, the meta-schema built on it, and the core type library built
on that) plus their non-normative `*-resolved.tn` resolver-output fixtures. Treat `spec/` as a cache, not
a source of truth — with one standing exception: the three `.tn` schemas are **packaged from here at build
time**, so they are the live copies rather than a snapshot. As of Revision 34 they **are** the published
artifacts, digests included: Part 2 §13.2 lists these three identities with these three `?sha256=` values,
so a diff against the published draft is empty. The divergences earlier revisions carried are all in the
spec now — `reference.target` typed `type_ref`, no `instance_template`/`template_argument`/`value_param`
(§5.10's held bodies replaced the quoted open-body vocabulary), and `map`'s `state` field behind
`{K => V?}` (§5.3).
**Changing them means re-stamping all three digests bottom-up** (`tson hash`, kernel first), moving the
matching `*-resolved.tn` entries, and updating `TsonBundledSchemas`, `InitCommand` and `README.md`, which
carry the published values.

**The `*-resolved.tn` fixtures are checked, not decoration.** They carry the instruction in their own
`@doc` — "Parse the source schema, run the resolver, canonicalise, compare" — and `ResolvedFixtureTest`
does it: every entry must read back into `schema.meta` and have a counterpart here, and what may still
differ is pinned per schema. They are the only external statement of what a conforming resolver produces,
so a change that moves those counts wants looking at rather than renumbering. Keep them in step with the
`.tn` beside them; both have drifted before.

**Status:** Part 1 is complete and frozen. Part 2's grammar, resolution, linking, and Class 2 compilation
all work: the three bundled schemas resolve/register/compile in full, user schemas governed by them
validate and read, and a `tson` CLI drives it end to end. Known gaps are listed under "Not yet
implemented".

**Hard constraints:**
- Java 25 only.
- No external runtime dependencies in main code. JUnit (Jupiter) is permitted for tests only.

## Spec feedback — this is the first implementation

This is the spec's first implementation, which makes it the first real test of whether the prose resolves
unambiguously to one behavior — valuable to the spec author precisely because it's still a draft. Actively
watch for and flag:

- **Ambiguity** — wording a careful reader could reasonably implement two ways.
- **Internal inconsistency** — two sections (or a grammar production and its prose) that disagree.
- **Underspecification** — a case the grammar/prose doesn't address where an implementation must still
  pick something.
- **Errors** — plain mistakes (wrong cross-reference, grammar that doesn't parse its own examples).

When you find one: say so in conversation, and record it in `SPEC-FEEDBACK.md` (spec section, concrete
description, the interpretation this implementation chose and why, suggested resolution). Don't silently
pick an interpretation — a resolved ambiguity is invisible again three sessions later unless written down.
A finding still open is cited by number (`SPEC-FEEDBACK.md` #N); once the spec carries the rule, the
citations name the section instead.

**The register holds what is open against the current revision, and renumbers from #1 when a revision
closes.** It is an input to the next revision's adjudication, so its numbering is what that revision's
change log will answer against. The evidence beside it is this implementation itself — an entry proposing a
design states what is running — which is why the shared corpus's `proposed/` bucket stays empty here: the
proposal is the code, not a vector another implementation is asked to fail. Entries whose resolution
landed are deleted; the closing revision's change log in `spec/` keeps all of them under *their* numbers.
**Cite the spec, not the argument that got it there.** Prose and Javadoc state the rule as built and name
the current section that requires it; a `SPEC-FEEDBACK.md #N` citation is for an entry still open, where
there is no section to point at yet. When
an entry closes, the citations to it become spec citations — the reasoning has served its purpose and the
spec now carries the rule.

**The register is the as-built record, and it is self-contained.** It is what goes to the spec reviewer, so
an entry proposing a design this implementation has built states the design, what is running, and what is
not, rather than pointing at a design document beside it. Where an entry's recommendation is a proposal
rather than a report, it says so at the point it makes it — a reviewer adopting a rule needs to know which
claims are running code. Working design documents are not kept in `spec/`: once a design lands, the entry
absorbs what survives of the argument and the document goes, git history keeping it.

## Conventions

**Javadoc documents current contract only, no change history.** Java source Javadoc describes an element's
*current* behavior — never dates, "renamed from X", "used to do Y, now does Z", "on the user's direction",
or similar changelog framing. If a design needs a WHY, state the current invariant and its rationale
directly. When you edit a class, clean up its Javadoc in the same edit — remove stale narrative (even if
you didn't write it), fix anything that no longer matches the code, tighten what's left. The `docs/` notes
and this file follow the same no-history rule; the dated log lives in git.

**`BACKLOG.md` is a clean list of outstanding work and nothing else.** Every entry names something someone
could pick up and do. Three things are therefore not entries, however true: **what was done** (an item that
ships comes out entirely — not annotated as complete, not kept as a record of how it was solved), **what was
decided against** (a won't-do is not work), and **what might become work later** (a standing note to revisit
something if conditions change is not actionable today, and sits in the list forever looking like a task).
Prose inside a live entry follows the same rule — say what is left and what constrains it; recounting which
halves already work turns an item into a status report that goes stale silently. Where one of those facts has
to survive its entry, it belongs in the `docs/` note, the Javadoc, or the test that owns the area, where the
person who trips over it will be looking. Git history is the log. Same rule for the "Not yet implemented"
section of this file.

**Keep the `docs/` note current in the same session as the change.** When work alters behavior an area's
design note describes, update that note the way you'd update the class's Javadoc — same edit, not a
follow-up. A note that silently drifts is worse than no note.

**`Tson` is a prefix, never an infix.** A class name containing `Tson` must lead with it (`TsonSchema`,
`TsonDataParser`, `TsonCompiledSchema`) — never buried (`CompiledTsonSchema` is wrong). The prefix is
**not** applied to every class: most internal machinery is deliberately bare (`Lexer`,
`RecordAbstractReader`, `DeferredTypeReader`, `ChoiceDisjointness`, `SchemaResolver`,
`DefinitionResolver`). Reserve `Tson` for types a *consumer of this library* names in their own code — its
value is disambiguation at the call site (`TsonSchema` vs. a domain `Schema`). When adding a new public,
developer-facing type, ask "would a consumer plausibly have their own class with this bare name?" — if yes
and it's consumer-facing, prefix it; if it's internal machinery, leave it bare.

**A fixed or default value is available on a scalar-typed field and nowhere else.** §5.2 makes a `~`/`=`
value a value of the field's declared type, and §12.1 admits only a bare token there; `TsonSchemaLinker`
resolves the field's type and parses the token with that type's own reader parser, so a default is accepted
exactly when a read would accept the same token in the same position. A field typed by anything but an
**atom or an enum** is refused whatever token stands beside it — records and choices included, though §5.6's
positional form and an atom-typed variant mean a read would accept some. Admitting those would make "may
this field have a default?" depend on another declaration's field count or variant list, a rule an author
computes rather than remembers, and one that breaks silently when that other declaration gains a field.
§5.2's "Which fields may carry a value" states exactly this rule; `void`/`unknown`/`extern` fall out of the
same line.

**A schema and its bound class must agree about a type's fields** (`TsonBindMismatchException`, raised at
bind-mode compile — startup, not first read; its subclass `TsonMissingBindingException` covers a type with
*no* class at all and is deferred to the first read of that type, since a schema legitimately declares types
a consumer never binds). Any non-FIXED field with no component, or a component no
field fills, is refused — optional fields included, since those are the ones that work in development and
fail on the first caller who sends them. A FIXED field is exempt, the schema settling its value. `@Unbound` marks a component as the
class's own, `TsonConfig.lenientBinding` opts out wholesale and is silent. Reaching a read as a diagnostic
instead (a schema compiled on demand), it keeps its own code, `Diagnostic.Code.BIND_MISMATCH` — a
misconfiguration in the reading application is no more a verdict on the document than a gap is.
`docs/readers-and-diagnostics.md` has the why.

**Exception classification is a policy, not a style choice.** Across the schema pipeline:
`TsonSchemaValidationException` means *the author's schema is wrong and the spec says so*;
`UnsupportedOperationException` means *this library hasn't implemented that yet*; `IllegalStateException`
means an internal invariant broke. The classification test: **a schema error's verdict doesn't change when
this library improves; a gap's does.** A gap is not a verdict on the author's schema, and the CLI's exit 1
vs. exit 70 rides on that distinction — **carried by `Diagnostic.Code.NOT_IMPLEMENTED`, not by the channel**.
Both kinds are collected: a gap thrown out of a phase that reports per declaration took every other
declaration's verdict with it, so the schema pipeline reports it beside the ordinary problems and the code
keeps it apart. The exception classification itself is unchanged and is what picks the code.
`DefinitionResolver`'s Javadoc lists the exact current boundary.

**Project-owned schema `!!id`:** a schema this project authors (not the spec's own bundled artifacts) gets
`https://tson.io/2026/34/ltr8/<group>/<name>-<version>.tn` — `/2026/34` is the spec revision, `ltr8` the
publishing org, `<group>` the subsystem (`cli`), `<name>-<version>` the schema name with a trailing
integer version. **The version is bumped on a release, not on a change.** §10's immutability rule binds a
*published* identity: once a release ships carrying the schema, the document under that `!!id` is fixed and
a later shape change mints the next version (`diagnostics-12.tn`) rather than editing it. Between releases
— while the build version carries `-SNAPSHOT`, so nothing has published the identity — the schema is in
development and is edited in place. Bumping per change instead mints versions nobody ever consumed, one for
every field added during a development cycle. **Use `.tn`, not `.tn1`** — `.tn1` is a stability claim §7.1
reserves for the eventual frozen "TSON version 1", which hasn't happened.

**Line wrapping:** wrap both comments and code to 125 characters.

## Modules and dependency direction

Package group is `io.ltr8` (reverse-DNS identifies who *publishes* the artifact — this is one
implementation of the spec published under the `ltr8.io` banner, not *the* tson.io-blessed one). Every
module has a real `module-info.java`; module names mirror each module's root exported package.

- **`tson-annotation`** — `@Typename`/`@Field`/`@Record`, the binding annotations, plus `Annotations`/
  `Annotation`, the wire-annotation carrier a bound class declares a component of. The carrier lives here
  rather than with the engine because it is the one module `tson-bind` (which analyses classes),
  `tson-schema` (whose `schema.meta` model is itself a bind target) and consumer code all see.
- **`tson-bind`** — the generic `DataValue`↔Java-object binding engine (`DataBindContext`, `DataClass`
  descriptors, `DataNameBinder`, bridges). Depends only on `tson-annotation`, whose annotations and carrier
  types it reads off a class under analysis. A context may name a **binding profile**
  (`DataBindContext.Builder.profile`), selecting among a class's `@Profile` constructors so one class binds
  several shapes — one context per schema version, descriptors still cached per context. The name is opaque
  here: matched by equality, with nothing in the module knowing what it stands for, which is what keeps
  selection out of the schema layer. **A cyclic type graph resolves** — a record reaching itself, directly or
  through others: `getDescriptor` hands a re-entrant call a deferred supplier and each holder keeps it in a
  final `Memoized`, so laziness is confined to the cyclic edge and every other component still resolves
  eagerly. The AST is the case that needs it (`DataValue` → `CoreValue` → `RecordValue` → `ScopedValue` →
  `DataValue`), which is what lets a held template body be written at all.
- **`tson-schema`** — **only** `io.ltr8.tson.schema.meta` (the resolved-schema *value* model — pure
  records/sealed interfaces/enums, §8's `TypeDefinition` et al.; `Top` is sealed except for its one
  deliberately open branch, `Data`, which a consumer's own class implements — see below) plus the schema
  registry (`TsonSchemaRegistry`/`TsonLinkedSchema`/`TsonSchemaLoader`/`TsonCanonicalIdentity`) and
  `TsonBundledSchemas`. **The linker is not here** — it is an engine, not a value model, so
  `TsonSchemaLinker`/`ChoiceDisjointness` live in `tson-compiler` with the rest of the pipeline; what
  stays is storage and the identity algorithm lookups
  compare by. Depends only on `tson-annotation`. **`tson-compiler` depends on `tson-schema`, not
  the reverse** — the opposite of what the names suggest, deliberately so the compiler's resolver can hold
  and consult `schema.meta` types directly. `schema.meta` names no `tson-compiler` type; where it needs
  one structurally it declares a local stand-in (`schema.meta.Token` mirrors `ast.TokenValue`/`TokenForm`;
  `schema.meta.SourcePosition` is an interface `tson-compiler`'s `Position` implements), converted at the
  one spot that needs it.
- **`tson-tree`** — **only** `io.ltr8.tson.tree` (the data-document *value* model — `TsonValue` and its
  pure immutable node types, structure-preserving and query-ergonomic, the read output of tree mode). A
  true leaf: depends on **nothing** (not even `tson-annotation` — the nodes aren't bind targets, they're
  assembled by hand-written readers). The data-tree counterpart to `tson-schema`'s `schema.meta`: same
  "pure value model in its own module, engine depends on it not the reverse" shape, so JPMS keeps the tree
  from ever coupling to compiler internals. `tson-compiler` depends on it; it names no `tson-compiler` type.
- **`tson-regex`** — **only** `io.ltr8.tson.regex`: a native RFC 9485 I-Regexp engine — `TsonRegex.parse`
  builds a `RegexNode` AST (or `TsonRegexSyntaxException`), `TsonRegex.matches` runs a Thompson-NFA/Pike-VM
  simulation (linear-time, no backtracking → ReDoS-safe; `\p{…}` via JDK `Character.getType`), and
  `TsonRegex.isDisjointFrom` decides whether two patterns share any string (exact — a symbolic product-NFA
  emptiness check over a `CodePointSet` interval algebra, the building block for §5.4 pattern disjointness).
  A true leaf — depends on **nothing**, I-Regexp being an external standard, not TSON-specific. The
  *engine* counterpart to `tson-bind` (a general dependency-free engine), not a value model like
  `tson-tree`; TSON pins its `regex` atom to I-Regexp (`regex_type`'s `REQUIRED_FIXED spec = rfc9485`), so
  this owns I-Regexp semantics rather than delegating to `java.util.regex` (a laxer superset).
  `tson-compiler`'s atom vocabulary depends on it; it names no `tson-compiler` type.
- **`tson-compiler`** — the engine: lexer, both grammars, base type resolution, the atom vocabulary,
  schema resolution, Class 2 compilation, the compiled reader stack, the schema-aware read facades
  (`TsonTreeReader`/`TsonObjectReader`) over their schemaless `reader`-package engines
  (`SchemalessTreeReader`/`SchemalessObjectReader`), the `TsonTreeWriter`/`TsonObjectWriter` writers, and
  config/wiring. Everything here is tightly coupled to the shared lexer/token-stream machinery, so it's
  one module. Root package `io.ltr8.tson.compiler`; exports the packages with real cross-module callers
  and keeps `reader`/`atom`/`base`/`lexer` internal.
- **`tson`** — the small front-door module (`Tson`/`TsonConfig`) over `tson-compiler`, the way Retrofit
  sits on OkHttp. Declares `tson-compiler`/`tson-schema`/`tson-bind`/`tson-tree` as `api` so a caller sees
  the real classes underneath.
- **`tson-cli`** — the `tson` command-line application. Depends on nothing depending on it (exports
  nothing).

**JPMS enforcement is real, not just convention.** An unexported package is genuinely unreachable from
other modules (verified by scratch-importing across a boundary and watching it fail). Internal dispatch
types kept in unexported packages but referenced by a public method signature produce an accepted
`-Xlint:exports` warning (e.g. `ValueReaderFactoryResolver`); this is deliberate, not a defect. No `opens`
directives — binding only ever touches public constructors/methods.

## Pipeline

The schema pipeline is **parse → desugar → resolve → link → register → compile → read**; the class
vocabulary follows it (`TsonSchemaParser`, `SchemaDesugarer`, `TsonSchemaResolver`, `TsonSchemaLinker`,
`TsonSchemaRegistry`, `TsonSchemaCompiler`, `TsonTypeReader`). Data documents (Class 1, no schema) run the
shorter lex → parse → base-type-resolve path. One paragraph per phase below; the depth is in the `docs/`
note named at the head of each.

### Lexer (`tson-compiler/.../lexer/`) — `docs/lexer-and-data-parsing.md`

`Lexer` is a single hand-written scanner producing `Token`s off `nextToken()` (never a batch), **complete
and frozen for the whole series** (§1.3). Constructed from an `InputStream` whose **UTF-8 it decodes
itself** (§9.1), code-point
addressed (never char-addressed), with `Position` tracking line / code-point column / UTF-8 byte offset —
counted from the input rather than re-derived from the decoded character, and malformed UTF-8 is a
`LexException` rather than a U+FFFD substitution (§7.1: a decoder MUST NOT substitute). NFC normalization
applies to *unquoted* tokens only; Pattern_White_Space is the spec's fixed 11-character set, hardcoded — but
**not one set doing one job**: UAX31-R3a-1 splits it into line terminators, *ignorable format controls*
(U+200E/U+200F, which it names) and horizontal space, so an LRM/RLM is consumed, contributes nothing, and is
refused where it stands inside a token rather than at a boundary — §7.2 rule 1 folds them into horizontal
space would be what let `[1<LRM>2]` read as two elements, which is why §7.2 rule 1 sorts them apart and
§9.5 rests on it.
§7.1's UAX #31 profile is implemented exactly, not approximated: the JDK's identifier predicates are
`ID_*` unioned with the identifier-ignorable set (all `Cf`, plus non-whitespace controls), so `Lexer`
subtracts that set and two literal `ID_ \ XID_` tables — verified zero-over/zero-under against Unicode
16.0, which `Lexer.UNICODE_VERSION` declares. ZWNJ/ZWJ continue a token, `XID_Continue` containing both and
§7.1 admitting them on that basis; what constrains them is a *name* rule (§7.7 rule 2), applied by
`IdentifierParser` through `JoiningControls` (UTS #39 §3.1.1.1's contexts A1/A2/B).
Errors are fail-fast (`LexException`); multi-error recovery is deferred.

### Structural parsing: Tier 2 stream + Tier 3 AST — `docs/lexer-and-data-parsing.md`

One implementation of the data grammar, split by role: **`TsonDataStream`** (Tier 2) is the only thing that
walks source text — a lazy pull-based `TsonEventSource` over a sealed `TsonEvent` hierarchy, frame-stacked,
at most two tokens of lookahead; **`TsonDataParser`** (Tier 3) reduces the event sequence into the sealed
`CoreValue` AST and holds no grammar logic of its own. Whitespace is gone by token time — adjacency (§7.5)
and separators (§2.4) are checked via `Position` gaps. The layering is deliberately incomplete per §1.2:
neither tier dedupes fields/keys, resolves `EmptyBrace`, or interprets token text — those belong to later
layers. **A name is the one exception, and §7.6 is the precedent**: `type-ref = "!" identifier` and
`annotation = "@" identifier`, so `TsonDataStream` matches each name's decoded text against
`IdentifierParser` the way a number's text is matched against the number grammar — a production that is no
part of the token-stream grammar, over a token the lexer has already produced. `field-name` stays lexical
(`unquoted-token / single-line-token`, where a map key keeps all three forms), the identifier contract being
stated once on declarations so Class 1 data keeps JSON compatibility and conforms by construction.
`!!meta` in the header throws `TsonUnsupportedDocumentException`, not `TsonParseException` (a
schema document is unsupported, not malformed).

### Base type resolution (`.../base/`) — `docs/lexer-and-data-parsing.md`

`BaseTypeResolver.resolve(TokenValue)` implements §4's fixed order (null → boolean → number → string) for
untyped tokens; `NumberGrammar.tryParse` recognizes the number production and extracts structure into
`NumberForm` **without** converting to a host type — over a hand-written `NumberScanner`, one method per
ABNF rule, because a reference implementation should not state the grammar in a host regex dialect no port
shares (`NumberScannerEquivalenceTest` fuzzes it against the patterns it replaced) — binding decides the
host type and enforces the
`255`/`0xFF` equivalences. Quoted tokens always resolve to `StringValue` (§4.4); form is consulted once,
here.

### Built-in atom vocabulary (`.../atom/`) — `docs/lexer-and-data-parsing.md`

`AtomType<T>` is a built-in atom's parsing contract; `BuiltinTypeVocabulary` is the fixed name→`AtomType`
table (§5). Each constructor splits into a constraint-values record in `schema.meta` (`IntegerType`, …)
plus a same-named `*Parser` in `atom` that holds one and does the work. Pattern facets stay `String`, not
`Pattern` — validated and matched via `tson-regex` (I-Regexp, ReDoS-safe), never `java.util.regex`.
`unit`'s three instances are three separate parsers dispatched on the declaration's own name — §4.2 makes
that dispatch normative, the resolved shapes being identical and deliberately uninformative.

### Schema grammar (`TsonSchemaParser`, `.../ast/schema/`) — `docs/schema-grammar-and-desugaring.md`

Parses a schema document body (Part 2 §12.1) into a `SchemaDocument`, grammar-only — no resolution, no
validation. `extends TsonDataParser` (same package) because §12.1 imports Part 1's grammar directly.
`SchemaMap.declarations` is a `LinkedHashMap` and duplicate names overwrite (grammar layer doesn't dedupe).
Two entry points: `parseSchemaDocument()` is fail-fast, `parseSchemaDocument(receiver)` reports each
declaration's syntax error and resyncs to the next.
§12.1's productions are implemented as written — `instance` takes a `core-value`, `atom-refinement` a
braced `record-def`, `field-modifier` a bare token or the absent sentinel. The bracket
form is parsed twice per the spec's own overlapping productions, and the `{K => V}` map sugar twice
alongside it. A `{` at a type position dispatches by consuming one token and inspecting — Part 1 §2.8's
record/map idiom, imported wholesale — and `{` is a map and only a map everywhere except type-def position,
since a bare record body is not spellable at a type position (§5.2). §12.2 states the dispatch's own
lookahead budget — one consumed token plus one of lookahead — and §5.3 the key-`?` rule it makes
unreachable for the common spelling. **`type-name = identifier`** — every declared name, type parameter,
referenced name and `!` constructor head matches the profile, which *replaces* §12.1's separate "numbers are
not declarable names" rather than joining it: identifier-Start is `XID_Start`, so the one rule answers both
and also catches the names that merely begin like a number (`42x`, `-foo`).

### Desugaring (`.../resolver/SchemaDesugarer.java`) — `docs/schema-grammar-and-desugaring.md`

An AST→AST rewrite between parsing and resolution: every sugar form — `[T]` and the sized forms, `[T, U]`,
`{K => V}`, `(A | B)` — becomes the `!C value` construction it denotes, at declaration position simply *being*
one and anywhere else becoming an injected declaration plus a bare reference — **which is now the spec's own
rule**, not a divergence: §4.2 de-parameterises `array`/`set`/`map` so a container at a use site
cannot be an application at all, and §5.3 states one lift rule — every sugar form lifts at desugar, a concrete
form to a closed synthetic entry. The rule this settles on: **`TypeRef.arguments` non-empty means an open
form — a template application — and everything closed is an entry referenced by a bare name.** So
`DefinitionResolver` only ever sees a bare
reference or `!C value`. **The phase is purely syntactic and consults no governing meta**: the sugar set is
closed, so the head each form desugars to and the vocabulary field each argument fills are a fixed table —
which is also why meta-kernel's bootstrap needs no hand-written routing of its own. §5.3's element/position
`?` binds `state` directly (`[T?; 3]` puts a state and both bounds on one binding record) — **and so does a
map's value**, `{K => V?}`, against the `state` field the kernel gives `map` (§5.3's own row); a map
*key* takes no `?`, §2.9 forbidding an absent key outright. The size
specifier binds the `min_items`/`max_items` pair directly for arrays and maps alike, with no size template in
between. §5.3's declaration-level container syntax is complete. Bottom-up, so nesting needs no special case;
an injected name derives from the *resolved binding record*, so `[T; 3]` and `[T; 3..3]` land on one entry. A
generic application can only be a §5.10 user-template application (§3.3.1 resolves heads in the type-name
namespace only), and applying one is rejected at the site that writes it, an imported head included. Invalid
sugar forms report per declaration via `DesugarFailureReporter` rather than throwing.

### Schema resolution (`.../resolver/`) — `docs/schema-resolution.md`

`DefinitionResolver` (package-private) turns one declaration into a resolved `schema.meta.TypeDefinition`;
`TsonSchemaResolver` (public) resolves a whole `SchemaDocument`, merging `!!import` entries into the
namespace first. Namespace dependencies are constructor-fixed functional interfaces. Everything §5 defines
resolves — composition, refinement (`^`), constructor application (bound generically via the compiled meta
reader, no name→class table), atom refinement (which **merges with its source** via a `TsonObjectWriter`
round-trip and is checked to genuinely narrow), subtraction (which empties `type_definition.supertypes` on
purpose), group restatement, all six field-state spellings. An annotation on a declaration resolves **one hop
against the governing meta** and nowhere else (§3.3.3): a name the schema declares itself or `!!import`s is
usable by the schema's *data* documents but not within the schema document, and writing one is an error, not
an annotation that keeps its name and drops its value (§6: an annotation whose name does not resolve is a
resolver error, the valueless form included). Every atom body is checked twice over, by two
per-family rules asking different questions: `Atom.constraintsCheck` (over `AtomNarrowing`) that a refinement
tightens its source, and `Atom.coherenceCheck` (over `AtomCoherence`) that a single body's own facets admit
anything at all — `{ min: 10 max: 3 }` is the second one's, and meta.tn's own `@doc` calls it "a schema-load
check". **`Product.coherenceCheck` is that second rule's structural twin**, asking it of a container's
`min_items`/`max_items` pair over the same `AtomCoherence` comparison; it lives on the family rather than
with any one spelling, so `[text; 5..3]` and the `!array { … }` body it denotes — one type — get one answer,
and `TsonSchemaLinker` asks **every** family the same question again for the entries materialisation mints,
which is how §8.2's "every family coherence rule ... asked once more of the closed record" is met without a
list — "a resolver needs no list", as it puts it.
The exception-classification policy under Conventions governs every rejection here;
`DefinitionResolver`'s Javadoc lists the exact boundary.

**Materialisation (`TemplateMaterialiser`)** closes a §5.10 template application, running over the
*resolved* form after every declaration has resolved — an application arrives as a `TypeRef` carrying
arguments, so substitution is a walk over `schema.meta` values and the entry it mints can record its own
`source`, which §8.2 keys identity on. Two `box<text>` anywhere share one entry and a declaration naming the
application aliases it; arguments close innermost-first; the memo is registered before the body is
substituted, so regular recursion ties the knot on the entry under construction. Non-regular recursion —
where the argument grows every level and the memo never fires — is caught by a depth guard rather than run
into a `StackOverflowError`. Three template shapes close, by three paths: a **record** template is
substituted and kept; an **open instance** (a container sugar form over a parameter) stops being a template
and binds through its constructor's own reader; a **reference** template — §5.10's partial application,
`uuid_pair => <B> pair<uuid, B>` — composes its argument list into the application it names and mints no
entry of its own, so a chain of aliases collapses to the one type at the end of it.

**Use-site flattening (`ReferenceFlattener`)** is §8.3 and the last thing resolution does: a type position
naming a `REFERENCE` entry is rewritten to the end of its chain and keeps the name the author wrote as
`@alias` (`type: @alias:field_name token`), which is what makes §8.2's instantiation identity a single-level
comparison. `TypeRef` carries an `Annotations` component for it, excluded from `equals` — identity is where
a reference *points*, an alias records where it *came from*. An alias entry keeps its own hop (the chain
must stay walkable) and the walk stops at a materialised instantiation (this model gives one an extra
`REFERENCE` hop the spec's does not, and that entry is what identity keys on). Runs on the bootstrap route
too, whose output governs anything whose `!!meta` is meta-kernel.

**The `@synthetic` marker** is `@alias`'s derived sibling (§8.1): §8.2 puts the bare marker on the schema-map
**key** of every entry the resolver materialised from a sugar form, and on no other — an instantiation entry
deliberately carries none, its `source` being an application where a synthetic's is a bare constructor, and a
declaration's own sugar body never lifts at all. Its two mint sites are the desugar lift
(`SchemaDesugarer.lifted`, the document's own set difference) and materialisation closing an open synthetic
(`TemplateMaterialiser.syntheticNames`); `SchemaResolver` attaches it where it assembles the entry map. Key
position, never the `TypeDefinition` value — §6 forbids hoisting between the two — so `AnnotatedMap` carries
it and the linker re-attaches it, imports included. The bootstrap route attaches none (it is informational,
where `@alias` changes what identity compares), and meta-kernel's own nine are marked anyway, by the ordinary
resolution everything but the transient governing-meta stand-in comes from.

### Meta-kernel bootstrap (`MetaKernelBootstrapResolver`) — `docs/schema-resolution.md`

Meta-kernel's `!!meta` names itself (§1.5's one deliberate circularity), so ordinary resolution can't
bootstrap it. `getMetaKernelSchema()` resolves it in **two passes** (non-`Instance` declarations first,
deferred `Instance` declarations second) with a closed `instanceBody` switch instead of a compiled reader.
Desugaring needs no special case: the table is fixed by the sugar forms, so the phase consults no governing
meta — which here would have been the very entries this class is producing. The payoff: meta-kernel's linked
form needs no materialization.

### Registry and linking (`TsonSchemaLinker`, `tson-schema/.../registry/`) — `docs/linking-and-compilation.md`

`TsonCanonicalIdentity.canonicalize` is §2.2.1's algorithm (exactly two reductions — strip scheme, strip
query — everything else must already be canonical), public API because `TsonSchemaLoader` keys on it.
`TsonSchemaLinker.link(schema, loader)` merges `!!import`s — an import's **whole namespace**, its own imports
included (§2.2.3: "an `!!import` contributes the imported schema's entire namespace"), with
**collisions decided by entry identity rather than name occurrence**: one schema reached by several routes
unifies (so the core.tn diamond is ordinary), two different schemas declaring one name is an error, and
nothing may shadow a name the closure already binds (recording each merged entry's origin schema id in
`TsonLinkedSchema.entryOrigins`, transitively — so a declaration's identity and its line always come from the
same document, whichever schema flattened it in, and so the identity comparison has a key). It populates
`subtypes`, rejects an entry no finite document can
satisfy (`TypeInhabitance` — a least fixed point over the entry graph, exact and total; §5.10.1's
productivity rule), derives choice
`disjoint` (`ChoiceDisjointness` — total and two-valued: `true` iff every variant occupies a distinct
discrimination class, the same `DiscriminationClass` untagged reading dispatches on; §5.4), and validates
every reference — refusing one that names a **DATA-kinded entry** (an entry describing
something other than a data value is declared by its schema but is not a type; without this the misuse
resolves, links *and* compiles and fails only at read), including choice-variant
distinctness after §8.3 flattening, rejection of a variant resolving to `void` (optionality is not choice,
§5.4), the author's `@disjoint` marker against the derived fact (`false` is
an error; no third outcome exists), and
constructor eligibility from both ends (§2.2.2/§4.2). The linker materializes nothing —
desugaring already did. `TsonSchemaRegistry.register` rejects duplicate identities (no overwrite: that plus
unmodifiable `entries()` *is* the "locked" guarantee). The linker lives in `tson-compiler` (a pipeline
stage, next to `Diagnostic` and `tson-regex`); the registry stays in `tson-schema` (storage over the value
model).

### Meta-layer vocabulary: `Data` and the `data` base kind — `docs/linking-and-compilation.md`

§2.2.2 makes the meta layer the format's extension point, and §4.1's fourth base kind — **`data => top & {}`**,
with `DATA` in `type_kind` — is where an instance of a meta-schema's own constructor lives when the thing it
describes is not a data type; `schema.meta.Data` is the matching **`non-sealed`** branch of `Top` — the one open
point in the body model, because the constructors reaching it are declared by meta-schemas this library has
never seen. A consumer registers a class by carrying `@Typename` and being findable by the
`DataNameBinder` (`TsonConfig.metaNameBinder` through the front door, composed over
`SchemaMetaNameBinder.INSTANCE` — the resolution core's *mode* is fixed, the names it knows are not); there
is no reader family and no factory entry, the ordinary record reader binding the payload and validating it
in full, and an unresolvable class is an error where the constructor is applied.
`Data.references()` is how a body's own type references reach the linker, declared rather than discovered.
§9's guidance for extension meta-schemas is the other half: a slot holding a type reference MUST be typed
`type_ref`, which is what makes it participate in flattening and identity.

### Class 2 compilation (`TsonSchemaCompiler`, `.../reader/`) — `docs/linking-and-compilation.md`

`compile` turns a `TsonLinkedSchema` into a `TsonCompiledSchema` — one `TsonTypeReader` per entry, wired as
real Java references, **eager** so a broken entry surfaces at compile time. `TsonTypeReader<T>` is strictly
one method, `T read(TsonReadContext)`; framing and error policy live elsewhere. A `RuntimeException` while
building one entry becomes an `ErrorReader` (the schema compiles; reading that entry reports
`NOT_IMPLEMENTED` and skips the value, so a gap costs that value a verdict and nothing else's — the code,
not the channel, being what keeps it apart from an author error), with two deliberate exceptions: a `TsonBindMismatchException` is
rethrown so a schema and a class that disagree fail the compile rather than the first read, and its
`TsonMissingBindingException` subclass rides an `ErrorReader` but is thrown from it **unwrapped**, being a
misconfiguration rather than a gap. An entry declaring type parameters becomes an
`OpenTemplateReader` before its body is looked at at all: a template is not a type, so naming one in *data*
is an ordinary data diagnostic (a schema naming one unapplied was already refused at link time).
`TsonCompiledSchema` is `sealed permits TsonCompiledMetaSchema` (a
meta-layer schema can govern others). Two compile modes (governed / standalone) share one walk; two output
modes (tree / bind) share each `*AbstractReader` family, selected by which factory registry you hold.

### The compiled registries — `docs/linking-and-compilation.md`

`TsonCompiledMetaRegistry` is the shared meta/resolution core: compiles and caches **only** meta-layer
schemas, resolves/links/registers everything else (`resolveLinked`) without compiling it, owns content-hash
verification, the bootstrap, and §2.2.3's import-cycle guard (a per-thread in-flight set — a schema is
registered only once linked, so a cycle is invisible to every cache and was a `StackOverflowError`). `TsonCompiledSchemaRegistry` (`dom(core)` / `bind(core, context)`) is a
per-mode registry of compiled user schemas — **the read mode is which registry you hold**, not a compile
parameter. Resolution is always bind-anchored (meta instances bind to `schema.meta.Top`), so every read
registry shares the one bind-mode core; core.tn is never compiled in the core, only inline in a read
registry.

### Streaming readers and read context — `docs/readers-and-diagnostics.md`

Every compiled reader pulls `TsonEvent`s through `TsonReadContext` — no reader requires a materialized
tree. The context holds **no error policy**: `report(...)` hands a `Diagnostic` to the read's
`TsonDiagnosticsReceiver` (`throwing()` / `collecting()` / caller's own), and readers ask `reported()` (a
count) when they need to know whether children complained. **A receiver sees every problem with the
document, base syntax included** — both facades catch a document that will not lex or parse and report
`Diagnostic.ofBaseSyntaxError(e)`, so a collecting read never throws for a bad document (it returns nothing
and the collector says why) while fail-fast still throws, as `TsonReadException` rather than
`TsonParseException`. A fault in the library propagates as itself. Load-bearing read rules, each detailed in the
note: a stated FIXED value is checked, not obeyed; an omitted `OPTIONAL_FIXED` field stays absent where
`REQUIRED_FIXED` injects (§5.2); collecting mode always keeps reading; **bind mode is all-or-nothing
(`ConstructionGuard`) while tree mode keeps everything it built** — deliberate asymmetry, not
inconsistency; records are closed under their type (§7.2, `UNRECOGNIZED_FIELD` — the same line polices
schema authoring through the meta's compiled reader); repeated fields/map keys are errors (§2.5/§2.6) with
last-value-wins recovery underneath; map-key identity is the decoded host value, type-ref and annotations
stripped (§2.6); a written `_` at `REQUIRED_DEFAULT` is an error where omission injects silently; `{}` is
the empty container of the position's own type (§2.8), so a zero-entry map faces `min_items` like any
other value; and a reader names itself in a message by what the author wrote, never by a
content-derived entry name — `EntryDisplayName` renders a minted entry as the sugar or application that
produced it (told apart by having no source position), and `UseSite` names a *position* as that position
wrote it, following §8.3's `@alias` where flattening left one and the referring entry's own `source`
where it did not. Both run where a composite reader wires its children, so neither costs a read anything.

### Diagnostics — `docs/readers-and-diagnostics.md`

`Diagnostic` (root package) is one record for both data- and schema-side problems — the variation is
locational, not categorical: a closed `Code` enum, `message`, `expected`/`actual`, four location
components matching JSON Schema 2020-12 §12's output unit (`path`, `schemaId`+`schemaPointer`, plus
`dataPosition`/`schemaPosition`), and the **one component that is not a location**, carrying a
distinction the closed `Code` cannot (it sorts by *who* could not check) and `message` must not (that
means parsing prose). `fetchReason`: `SCHEMA_UNAVAILABLE` says a schema was not obtained and
`TsonSchemaFetchException.Reason` says by whose doing. It is what makes the thrown and the collected
channel answer one fetch failure alike, the collecting one being where almost every read now hears about
it. **A §8.2 name-hygiene refusal is a diagnostic like any other and carries nothing extra**: which rule
refused is the `Code` — `CONFUSABLE_NAMES`/`RESTRICTED_CHARACTER`/`RESTRICTED_SCRIPT`, one each, since the
three want three different remedies and the code is what a consumer routes on — and the Unicode data
version §8.2 requires a refusal to name is a fact about the *processor*, so it is stated once beside the
diagnostics rather than N times inside them (`TsonUnicodeProcessorPolicy`, below).
**What earns a component at all is one rule** — *a fact not recoverable from the
document plus the schema* — which is why `fetchReason` is the only non-location component and why an atom's
failed bound (in the schema), a duplicate key (in the document) and the rule that fired (the code) get none;
`tson-cli`'s wire shape applies a second filter, whether the recipient can act on it. Both RFC 6901 pointers are
`Optional<String>` because `""` is the *root*,
a location this really emits, not an absence; the three components where `""` really is absence
(`schemaId`/`expected`/`actual`) offer `schemaIdIfKnown()`/`expectedIfStated()`/`actualIfStated()`, so a
renderer asks rather than remembering which convention each component uses. `expected` carries the
**constraint that failed** — `<= 100`, `one of (A, B, C)` — from `AtomTypeException`'s six-shape
vocabulary, never the type's name; the name leads
`message` instead. The base-syntax exceptions keep their position out of `getMessage()` (it is in
`position()`, and in `toString()` for a stack trace) so a diagnostic states it once. A read's schema end is
one `SchemaLocation` (id + pointer + position) **accumulated as the read descends**, not claimed by whichever
reader is innermost: the pointer is the path taken (`/person/age`), never the leaf it resolves to (`/int32` in
core.tn), because the leaf names a file the author didn't write and never mentions the field they can edit.
`schemaField` steps data and schema together where `field`/`index` step data alone — each step a linked
node, both pointers rendered only when a diagnostic is built, since concatenating per step is quadratic in
depth and thrown away by every read that reports nothing; a record re-anchors
id+position on itself (but a declaration with no line of its own contributes none, leaving the enclosing
one's), everything else offers its own declaration only as a seed for a value nothing encloses — and the
**facade** seeds the root from the name the read entered through, so a pointer into a template-derived type
names the author's alias rather than the entry the resolver minted.
Schema-side reporting runs through the same receiver: `TsonSchemaParser`,
`SchemaResolver` and `TsonSchemaLinker` have reporting overloads that collect every independent problem in
one pass (a failed declaration leaves an answer-everything placeholder, javac-style), while
`Tson.validateSchema` owns the phase boundary — resolution runs only if the document parsed whole, linking
only if resolution was clean, and a schema that reported anything is never registered. A schema *syntax*
error reports per declaration too (`Diagnostic.ofSchemaSyntaxError`, located at the schema end, resyncing on
`name =>` at schema-map depth), naming the **construct** the position admits rather than the token class —
and the recovering parse hands back no document at all, since resolving a half-document reports every
reference to a dropped declaration on top of the real error. Namespace-level failures (unloadable
`!!import`, ineligible `!!meta`, `!!id` cross-check) still throw even with a receiver. Compilation, and the
lexer under everything, are still fail-fast.

**`TsonUnicodeProcessorPolicy` is the configuration a report is read against, and it is stated once.** The two §8.2
policies (`identifierPolicy`, `tokenPolicy`, under `TsonConfig`'s own names — level, whole-name or
per-segment unit, and any `permitting` relaxations) plus
the UCD version, reachable as `Tson.processorPolicy()`, either facade's `processorPolicy()` (read off the
reader that judged, since a derived reader is where the two can differ), and `tson policy` on the command
line. It is what makes a §8.2 divergence explainable: the same bytes may be refused here and accepted
elsewhere, and the reason is in neither the document nor the schema. It is deliberately **not** a diagnostic
component — the fact is constant for a run, so a per-refusal copy is N copies of one string; it arrives only
on failure, where what a sender needs is the rule *before* it writes; and a version says what refused you
where a level says what would be accepted. That last is why the standalone surface matters more than the
envelope one: a generator that reads the policy first never writes the name that would be refused, which is
the round trip the format exists to avoid. `SPEC-FEEDBACK.md` #14 proposes §8.2 ask for this shape.

### Read facades and writers — `docs/facades-and-tree.md`

`TsonObjectReader` (bound Java object) and `TsonTreeReader` (`TsonValue` tree) are the whole
document-reading surface, dual-mode fixed at construction: standalone = schemaless (Class 1,
Jackson-style); from a `Tson` facade = schema-aware (a self-describing document validates against its
`!!schema` as it's read). Jackson-`ObjectReader`-style derivation (`withDiagnostics`, `withSchema(uri)`,
`preservingUnknownTypeRefs`, `withTokenPolicy`) keeps source form / error policy / schema selection / Unicode
policy orthogonal; derived
readers share the original's compiled-schema registry. Failures reaching or resolving the schema are
diagnostics, not exceptions. A schemaless read still checks type-refs (`TypeRefCheck`: built-in name →
must satisfy the atom; names-the-target → accepted, bind only; else `UNKNOWN_TYPE_REF` — a reader policy,
a reader policy where §7.1 asks only that an unresolved annotation be treated as informational). Both tree
paths capture wire annotations; a schema-driven read also type-checks
annotation *names* against the governing schema (§1.3's Class 2 bullet) — **wherever they are written, not only where the
reader keeps them**, since whether a bound class has an `Annotations` carrier is no part of whether the
document conforms. `TsonTreeWriter`/`TsonObjectWriter` re-emit
annotations in §7.4 order; `toTson` is mainly a debugging tool with documented losses. Both writers also
take a sink — `write(value, OutputStream|Appendable)`, UTF-8, flushed and not closed — so a document never
has to exist as a `String`; `TsonDataEmitter` holds an `Appendable`, and `toTson` is that method over a
`StringBuilder`. Both can also emit a document header (`describing(schemaUri[, rootType])`/`identifiedBy`),
**off by default** so existing output is unchanged — the object writer needs the root type too, a bound
object carrying neither fact, where a tree already names its own; `TsonDataEmitter.typeRef` refuses a second
type-ref on one value, which is what keeps a declared root type from writing an unparseable document. The
same `TsonDocumentHeader` carrier reads: `peek(String|InputStream)` is §7.1's classification from the opening
bytes — `!!id` plus `!!schema`, or `!!meta` and it is a schema document — for a caller that must route on
what a document names before reading it, **total** (a header it cannot read yields nothing rather than
throwing, never a schema the document does not name), and `peekResumable` hands a one-shot stream back whole
(`TsonDocumentPeek`) so an HTTP body can be routed and then read.
These live in `tson-compiler`'s root package because `DefinitionResolver` depends on
`TsonObjectWriter`.

### Tree model: `TsonValue` (`tson-tree`) — `docs/facades-and-tree.md`

A sealed `TsonValue` over seven pure immutable node types (`TsonRecord`/`TsonMap`/`TsonArray`/`TsonTuple`/
`TsonAtom`/`TsonAbsent`/`TsonMissing`), structure-preserving and annotation-aware. No `Node`
suffix (deliberate, against Jackson's names). `get`/`at` never throw — a `TsonMissing` carries the RFC 6901
pointer of the step that failed. **One no-value node, no separate null node**: `TsonAbsent` carries `_`, the
`null` token where §4 base resolution applies (schemaless data and `value` positions), and a collecting-mode
read failure. Under a schema `null` stays ordinary text — §7.3's concession is local to `void`, and lives in
`VoidReader`, never in the lexer or `TsonDataStream`.
Two accessor families with different questions: `as(Class)`/`asString`/…
**cast** ("what host type did the read produce?"), `asInt`/`asLong`/`asDouble` **convert** ("what number is
this?") — a test asserting which host type a reader produced must use `as(Class)`. Read-side only; no
builders or transforms yet.
**`TsonDocument(id, schema, root)` is the model's document** — the counterpart of `ast.Document`, since §2.2
makes a header a property of the document and not of its root value. No `meta` component: that would be a
*schema* document, whose model is `schema.meta`, and `TsonDocumentHeader` (which carries all three) answers
the different question of classifying a document before reading it. `treeReader().readDocument(...)` returns
one and `read` is unchanged beside it; `TsonTreeWriter.toTson(TsonDocument)` writes it back, the document's
own directives beating the writer's where it has them. **`TsonObjectDocument<T>`** (in `tson-compiler`, beside
the facades) is the object side's, and a distinct type rather than the same one: it needs a fourth component,
`rootType`, a `TsonValue` naming its own type where a bound object names nothing. What it carries is what the
*read* established — the class and its context already fix the schema, but `!!id` is per-document data and
`rootType` is a name a `DataNameBinder` cannot invert, which is also why `TsonObjectWriter.describing` takes
two arguments where the tree writer's takes one.

### Front door: `Tson`/`TsonConfig` (`tson` module) — `docs/facades-and-tree.md`

`Tson.builder().build()` bootstraps meta-kernel/meta.tn/core.tn and returns an immutable `Tson`.
`bindings(Map)`/`profile(String)` are the short form of the bind context — the map as a name binder chained
over the kernel's vocabulary, plus `TsonAtomContext.registerDefaults` — and are mutually exclusive with
`dataBindContext`, a profile being fixed when a context is built.
`resolve(schemaText)` registers a schema by its own `!!id` (no mode — resolution is always bind-anchored);
`treeRegistry()`/`bindRegistry()` pick the read mode; `objectReader()`/`treeReader()` return schema-aware
facades sharing this instance's registries, so a schema compiles once per `Tson`.
`validate(String|InputStream)` *is* `treeReader()` with a collecting receiver — returns `List<Diagnostic>`
(empty = valid) and never throws for a bad input document (a library fault still throws, deliberately).
**Two fetching schema sources ship** — `TsonHttpSchemaSource` (HTTPS, host allow-list) and
`TsonFileSchemaSource` (a directory) — with `httpSchemas(…)`/`fileSchemas(host, dir)` as their one-call
forms, repeatable and mutually exclusive with each other and with `schemaSource(…)`, the general seam.
`TsonSchemaSource.ofMap(Map)` is the non-fetching third, for schemas a caller already holds: it exists
because `schemaSource(schemas::get)` is the natural first source and returns `null` for the identity the
*document* chose, which the contract does not permit — a `null` carries no `Reason`. That is refused where
the loader calls a source (`IllegalStateException`, so it stays a fault and `SchemaFailure` rethrows it),
and `ofMap` is the same lookup done right: a miss is `NOT_FOUND`, and matching is by canonical identity, so
a `?sha256=`-pinned reference finds the unpinned entry. Both **deny by default**, match a host
exactly, and share `SchemaReference` for §2.2.1's rules on what an identity may be, since the reference comes
out of a document and in a server that means a request body: the HTTP one guards SSRF (no redirects ever, size
capped against bytes delivered), the file one arbitrary reads (containment checked *after* `toRealPath`, so
`..` and symlink escape fall together). Neither verifies the `?sha256=` pin or the fetched `!!id` — the loader
does both; `requireContentHashPin` adds the one thing it cannot, that a pin be present. **`TsonSchemaSource`
names its own failure exception** — a source says "cannot supply this" with `TsonSchemaFetchException` and
nothing else, which is what lets `SchemaFailure` classify every branch positively and rethrow a fault as
itself; the exception lives in `tson-compiler` beside the interface, since the classification cannot see a
type declared in `tson`. A schema no source would supply is `SCHEMA_UNAVAILABLE`, never `SCHEMA_ERROR`: it
was never read, so nothing about it has been judged.

```java
Tson tson = Tson.builder().build();
tson.resolve(schemaText);                      // registers the schema by its own !!id
TsonValue value = tson.treeReader().withSchema(schemaId).readAs(dataText, "my_type");
```

### CLI, config, bundled schemas, hashing — `docs/cli-config-hashing.md`

`tson validate [--output text|json|tson] <file|->...` auto-classifies a flat file list into schemas (by
embedded `!!id`, never filename) and data, and validates each data document via `Tson.validate` — fully
self-describing, no `--type`; `-` is stdin, at most once, always data. One `ValidationRun` envelope per
invocation. **Exit codes: 0 all valid, 1 any data file invalid, 2 usage/classification, 69 a schema nothing
would supply, 70 a library gap or fault** — the split is load-bearing and rides on the exception-classification
policy. 1 is a verdict on the document; **69 and 70 are the absence of one**, naming who could not give it
(whoever was to serve the schema; this library), and `TsonCli.exitCodeFor` lifts a run to whichever of the
three is most permanent — 70 over 69 over 1, since retrying reaches a gap again. Both non-verdicts ride in
the report as codes (`NOT_IMPLEMENTED`, `SCHEMA_UNAVAILABLE`) with a stderr note, the report on stdout
unchanged. 70's halves print differently: a gap that escapes as an exception prints
`not implemented yet: <message>`, whose text usually names the workaround; a fault gets the please-report-it
banner and its stack trace. Also `tson compile`, `tson hash` (stamps a
`?sha256=` pin idempotently), `tson init-example`, and `tson policy` — the §8.2 `TsonUnicodeProcessorPolicy`
with no document in hand, the same record every `validate`/`compile` envelope carries in its `policy` field.
**Those three commands also take the policy flags** (`PolicyOptions`, which consumes them so each subcommand's
own loop still sees only `--output` and positionals): `--identifier-policy`/`--token-policy` take a level in
either spelling the CLI prints or a person types, `--identifier-per-segment` the unit, and
`--identifier-scripts`/`--token-scripts` a `Latin+Cyrillic` combination, repeatable. Two rules keep a flag from
meaning nothing: **`--token-scripts` alone raises the token level** to `SINGLE_SCRIPT` (its `UNRESTRICTED`
default scans nothing, so the list would be inert), and a relaxation named against a *stated* level that scans
nothing is a usage error rather than a no-op — `withTokenPolicy`'s own habit of refusing a policy that cannot
mean what it says. There is no `--token-per-segment`; the library refuses one. In `--output text` a run prints
the policy when it refused something **or** when it configured one (§8.2 requires a relaxation not be silent);
the machine formats always carry it, a consumer wanting one shape.
`TsonBundledSchemas` serves the three bundled schemas' identities, text (copied from `spec/m/` at build
time) and published digests. `TsonContentHash` hashes every byte past the `!!id` line; pins are
verification metadata, not identity, checked through the loader on every fetched pinned reference. The
`config` package holds the two default bind contexts (consumer vs. internal `schema.meta` resolution),
differing by exactly the name binder.

## Traps — read before touching the class involved

Hard-won invariants that look like cleanup targets or are easy to break silently. Each is documented at
the class and (where noted) pinned by a test; the `docs/` notes carry the full why.

- **`TypeArgument` is a sealed interface (`Ref`/`Value`), never a plain record.** It is the labelled choice
  the kernel declares, and a plain record with two `Optional`s would be a worse model: nothing in the type
  would say exactly one is present. It used to be the only shape that *worked* as well — `TypeRef`/
  `TypeArgument` are mutually recursive and the record binder had no cycle protection — but
  `DataBindContext` carries a cycle guard now (`Memoized`, `RecursiveModelTest`), so that half is history
  and the shape rests on the modelling argument alone.
- **`SchemaDesugarer` returns un-rewritten nodes by identity** — `declarationPositions()` is an
  `IdentityHashMap`, so an equal-but-rebuilt `Declaration` silently loses its source position and its
  diagnostics. `SchemaDesugarerTest` asserts `assertSame`.
- **`requireDocumentEnd`: the pull is the point, not the assertion after it.** Nothing fails if you simply
  stop reading a lazy `TsonDataStream`; pulling past the root value is what makes trailing content get
  rejected. Javadoc on both facades.
- **Lexer multi-line closing-delimiter detection strips leading whitespace *before* comparing against
  `"""`.** Backwards, every multi-line token is spuriously "unterminated". Happened once; guarded by
  `LexerTest`.
- **Never put literal BOM/NEL/LS/PS characters in source or tests** — use `\uXXXX` escapes; the invisible
  character is an editing hazard (§9.4's confusable risk).
- **`CompiledReaders` is rebound exactly once, from the in-progress `Compilation` to the finished
  `TsonCompiledSchema`** — handing readers `Compilation::resolve` would leak its mutable state past the
  compile. `CompiledReadersTest` pins the handover.
- **`verifyFixed` compares with the pre-rebind parser (`FixedCheck`)** — bind mode narrows
  `precomputedValue` in place, and comparing across that narrowing flags every conforming document.
- **A `schema.meta` bind target with more than one public constructor needs `@Record` on the canonical
  one**, or `DefaultRecordBinder` throws (`IntegerType`/`IntegerSize` hit this).
- **An atom body's components must mirror its constructor's *resolved* shape, not the composition that
  produced it** — every field flat, one component per schema field name. Composition flattens (§5.8) and a
  compiled `Record*Reader` fills a field, including a `REQUIRED_FIXED` field's schema-composed default,
  under its own schema field name, so a component nesting one (`specification: AtomSpecification` for
  `spec`) or omitting one silently binds `null` rather than failing. `UriType`/`RegexType` did both for a
  long time, invisibly, because their tests asserted against hand-written `UNCONSTRAINED` constants and
  `MetaKernelBootstrapResolver` hands those same constants back — only a schema resolved through the
  compiled meta reader shows it. `DefinitionResolverTest.resolvesRegexAndUriInstancesWithEveryComposedFieldBound`
  is the guard.
- **A desugar-reported declaration is replaced with an absorbing stand-in, never passed through** — passing
  it through hands `DefinitionResolver` the very node the phase removes and turns a reported author error
  into an unreported abort. Injected declarations are never rolled back (later declarations may already
  reference them). **Both placeholders keep the failed declaration's own type parameters** (`absorbed`, and
  `SchemaResolver.unresolved` one phase later): answering "how many type parameters?" with zero tells a
  downstream `bl<text>` to "drop the argument list", which is a wrong fix for someone else's error.
- **Atom refinement's `TsonObjectWriter` round-trip has no cheaper substitute** — the merge must run on the
  wire record before binding, or `REQUIRED`-no-default constructor fields fail `FIELD_REQUIRED`
  (`DefinitionResolverTest.atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed`). This dependency is
  also why the writers can't move out of `tson-compiler`.

## Conformance suite (`ConformanceSuiteTest`, `Class2ConformanceSuiteTest`)

Separate from the fine-grained unit tests, these run every vector in the sibling
[ltr8-io-tson-test-suite](https://github.com/litterat/ltr8-io-tson-test-suite) repo as JUnit 5 dynamic
tests — a conformance/integration check against an external, language-agnostic, spec-derived fixture set,
to catch drift against the spec.

**Two runners, split by conformance class and therefore by module.** `ConformanceSuiteTest`
(`tson-compiler`) runs `class1/` against the real `Lexer`/`TsonDataParser`/`BaseTypeResolver`/
`BuiltinTypeVocabulary`. `Class2ConformanceSuiteTest` (`tson`) runs `class2/` against the `Tson` front
door, because a Class 2 vector is about a phase boundary — did this schema resolve, did it link, does this
document validate against it — and those boundaries are `Tson.validateSchema`/`Tson.validate`'s to own,
not a test's to reassemble. What the two share lives in `tson-compiler/src/testShared`, added to both test
source sets: `SuiteCheckout` (finding the corpus), `Sidecar` (reading a sidecar and splicing a subject's
header) and `Vectors` (walking the tree). One statement of the corpus's contract rather than two, which is
what `RUNNER.md` exists to keep from drifting — the first two runners written against its prose had already
disagreed about what a subject even is.

**The corpus states its own contract, and this runner obeys it rather than inferring it.**
`schemas/<layer>-sidecar.tn` gives each layer's sidecar shape and every sidecar names one with
`!!schema`; `RUNNER.md` is normative for the runner. Three rules bind here: a subject reaches the lexer
as the **bytes on disk**, never a decoded and re-encoded string; an `error` vector's §8.1 **category is
asserted at every layer**, not only the vocabulary one (the layers are pipeline stages and cross the
categories — the vocabulary layer raises `resolver` and `validation` errors and never a "vocabulary"
one); and **position is never asserted**, implementations legitimately failing at different points
depending on lookahead. A sidecar carries its outcome as a **field group member** (§5.11), so exactly
one of `valid`/`error`/`schema-document`/`refused` is present and the payload cannot be separated from it;
`absent`, `empty-brace` and `schema-document` carry nothing and are typed `void`, written `_`.

**`refused` is §8.1's fifth outcome and is not a verdict on the document or the schema.** §8.2's name-hygiene
rules refuse without making a document invalid — each reads data the UCD does not freeze, so none of
them may decide validity — and §8.2 says the refusal MUST NOT be reported in any of the four categories.
`checkRefusedVector` therefore asserts both halves: that something was refused, and that *nothing* was
reported as invalid, `CONFUSABLE_NAMES`/`RESTRICTED_CHARACTER`/`RESTRICTED_SCRIPT` being the three codes that mean
policy, one per rule. A vector
names the rule it exercises and the UTS #39 data version it was computed against, and a version this
implementation does not carry is `RUNNER.md` rule 5's fourth legitimate skip — the only one that is about
the vector rather than the conformance class. It has two homes: `class1/reader/refused/` for Part 1's one
scope, and `class2/schema/refused/` for §11.4's, where the enum-member and group-member-label vectors are
the ones that catch a processor checking each name where it is *read* rather than where a scope is
*walked* — the failure this implementation had. Template parameters stay out of the corpus: §11.4 does not
list them as a scope, so a vector asserting the refusal would fail a conforming implementation
(`SPEC-FEEDBACK.md` #5), and `ConfusableNameScopesTest` carries those cases instead.

**The grammar runs where a name is read; the policy runs once per layer, over scopes.** That split is
§8.2's own — §7.7 is validity, stable across Unicode versions, and a failure is a parse error; §8.2's three
name-hygiene rules are policy over *named scopes*, read unstable data, and a failure is a refusal. So
`IdentifierParser.validate` is the grammar and throws, `IdentifierParser.hygiene` is the restricted-character
rule and
returns, and **no position that reads a name applies a policy**. The joiners belong to the grammar despite
being `Identifier_Status=Restricted` — §7.7 rule 2 makes their admission a question of form.

Each layer has exactly one place that walks its scopes, and all three rules run there — names that read
alike (`CONFUSABLE_NAMES`), a character outside the identifier profile (`RESTRICTED_CHARACTER`), a script
script the restriction level does not admit (`RESTRICTED_SCRIPT`, wider than a mix — at `ASCII_ONLY` a
single-script name is refused with nothing mixed):

| Layer | Walk | Scopes |
|---|---|---|
| Schema | `TsonSchemaLinker.checkNames` | §11.4's four, plus a template's parameters (`SPEC-FEEDBACK.md` #5) |
| Data | `DefaultTsonReadContext` + `SchemalessTreeReader` | a type-ref/annotation name; one record's field names |

**A minted name is judged by neither walk.** §11.4's scopes are authored and §8.2 puts internal names
outside the conformance surface, so a refusal on a name the resolver derived is one nobody can act on — and
since a derived name is a Latin constructor head spliced with the author's own content, it would fire on any
schema written outside Latin script. What binds a minted name instead is §8.2's freshness MUST, that it be a
valid `identifier`, met by construction where it is minted (`InternalName`); `docs/linking-and-compilation.md`
has both halves.

**One place is the point, not a tidiness.** The restricted-character rule used to run at the reading
positions instead — spread over the schema parser, the definition resolver and the atom vocabulary — and had
holes at exactly the positions only some of them reached: an enum member and a group's member labels were
checked for reading alike and for script mixing, and never for a restricted character. A scope list can be
reviewed; three call sites cannot. Class 1 *field* names see only the look-alike rule, being lexical rather
than names (§2.5, §7.7). The identifier policy defaults to Highly Restrictive
whole-name (§8.2's SHOULD) and relaxes through `withIdentifierPolicy`, which §8.2 requires be code rather
than ambient; `withTokenPolicy` is the other surface and defaults to `unrestricted()`, a value being data that
may legitimately be anything.

`SidecarSchemaReadTest` is the other half and is what makes `schemas/` validation rather than
documentation: every sidecar read against the schema it declares, plus the negatives the groups exist
for. `SidecarSchemasTest` checks the schemas themselves resolve — every `.tn` in `schemas/`, listed rather
than named in a constant, so a layer schema added upstream is one this has to resolve — serving the suite's
own identities beside the bundled ones since the layer schemas `!!import` `sidecar-common.tn`.

**The corpus is a declared input of every `Test` task** (root `build.gradle.kts`). It lives outside this
build, so Gradle would otherwise report the previous run as up to date over an edited vector — a stale green
over a changed corpus, which is the one thing a conformance signal must not do.

**A skip is not a pass.** `SuiteCheckout` finds the corpus — a sibling working copy first (a developer
editing vectors must see their own edits), then the pinned copy `scripts/fetch-references.sh` fetches
into `.references/`, with `-Dtson.testSuite.dir` overriding both authoritatively. An absent corpus
aborts through `Assumptions` so a bare clone stays green, **except where `TSON_REQUIRE_TEST_SUITE` is
set — CI sets it — where it fails instead**. CI used not to check the corpus out at all, so every vector
aborted and the build went green while measuring nothing; that is what the variable exists to stop.
**The pin is a commit, never a branch**: an upstream vector must not be able to turn this repo red with
no change here.

**The `reader` layer is where a Class 1 document gets its verdict**, and the rules §1.2 leaves to no tier
live there and nowhere else — §2.5's unique field names, §2.6's key identity (including the decoded-value
rule a parser cannot apply), §2.8's empty brace, §2.9's absent-key restriction. A `parser/invalid/` vector
cannot fail on `{ a: 1  a: 2 }`; the parser accepts it by design. An error vector there states
`category: resolver` and its subject must parse, which `checkReaderVector` asserts before asking the reader
for a verdict.

**The `class2/` layers are the three answers a Class 2 processor gives.** `schema/` needs no invented
expectation format: §1.3 makes producing a resolved schema value a MUST and §8 fixes its serialization, so a
valid vector's subject is a schema document and its expected side is that document's resolved output in §8's
own form, read back through `ResolvedForm` (shared with `ResolvedFixtureTest`, which asks the same question
of `spec/m/*-resolved.tn`) and compared entry for entry, `@synthetic` key markers included. `link/` states
individual facts about the linked namespace — §2.2.3's import closure, §5.4's derived disjointness, §8.2's
`subtypes` index. `validate/` is a data document against a schema that loaded, where the expected side of a
failure is §8.1's category plus the RFC 6901 pointer into the data and nothing else.

**At the schema and link layers the category is the phase's, not the diagnostic code's.** §8.1 says every
error that makes a schema fail to load or ingest is a resolver error "however value-like the violated rule",
so a schema-authoring mistake this library catches through the meta's own compiled reader arrives carrying a
record-shaped code and is still a resolver error. What is checked per diagnostic instead is that each one is
a **verdict**: `NOT_IMPLEMENTED`/`BIND_MISMATCH`/`SCHEMA_UNAVAILABLE` say the vector could not be judged, and
letting one satisfy an error vector is how a corpus comes to pass on the strength of not having been run.

**No `class2/schema/` subject declares a template**, and the reason is where this layer compares rather
than what §8 admits. The comparison is over the resolver's own value, and an open entry's body is a
`HeldBody` — the application as written — where the same text read back as a `type_definition` binds an
ordinary `RecordBody`: the two sides agree as §8 text and differ as values, and nothing here serializes the
resolver's value to close the gap (§1.3 makes producing output OPTIONAL, and this doesn't). §8.1 is
self-contradictory about whether a `type_definition` may carry a parameter reference at all
(`SPEC-FEEDBACK.md` #4), which is why the answer isn't simply to write the vector. Templates are covered at
the `link/` layer instead, over the entries they mint.

**Add test-suite vectors in the same session as any lexer/parser/resolver work**, not after a nudge —
with one standing exception: the corpus's `resolver` layer is Part 1 *base-type* resolution, so a Part 1
vector about schema resolution has nowhere to go and the honest move is to say so rather than wedge one into
the wrong bucket.
A vector whose sidecar carries `encoding` is fed the file's bytes unchanged (`checkEncodingVector`),
because the ordinary string round-trip would re-encode exactly the bytes such a vector exists to test;
an encoding this implementation does not read is skipped, not failed, which `RUNNER.md` admits as one of
its three legitimate grounds.

## Build and test

No system Gradle — always use the wrapper:

```
./gradlew build                   # also builds the javadoc/sources jars, so doclint runs under `build`
./gradlew test
./gradlew publishToMavenLocal     # installs every module into ~/.m2 as io.ltr8:<module>:0.34.0-SNAPSHOT
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.lexer.LexerTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonDataParserTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.ConformanceSuiteTest"  # class1; skipped unless ../../ltr8-io-tson-test-suite exists
./gradlew :tson:test --tests "io.ltr8.tson.Class2ConformanceSuiteTest"                 # class2, same corpus
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonSchemaLinkerTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.TsonCompiledSchemaRegistryTest"
./gradlew :tson-compiler:test --tests "io.ltr8.tson.compiler.resolver.DefinitionResolverTest"
./gradlew :tson-cli:installDist   # then tson-cli/build/install/tson/bin/tson validate ...
./gradlew :tson:allocationReport  # the allocation harness alone, numbers on stdout
```

**Allocation is measured, not assumed** (`AllocationHarnessTest`, `tson/src/test/.../perf/`). Two separate
questions over the bind read path: **retention** — settled heap across 20,000 reads of one schema, plus a
weak-reference check that no read output stays reachable, both currently a flat **0 bytes per read**, which
is what the "resolve every schema at startup, then read" design claims and nothing else asserts — and
**transient bytes**, reported per read with a ceiling loose enough to survive a JDK upgrade and tight enough
to catch a 50x mistake (a `Pattern` per character was one, at 188 bytes per character written). Numbers move
with the JDK and the machine; treat the *shape* as the signal — `whereAReadsBytesGo` splits a read into
stream/tree/bind so a change says which stage moved. `AllocationProbe`'s Javadoc has the Flight Recorder
flags for when the next question is "where".

**Publishing is packaging, not release.** Every subproject applies `maven-publish` with a `mavenJava`
publication (the `java` component plus sources and javadoc jars) and a POM carrying name/description/
url/licence, so `publishToMavenLocal` gives another project on the same machine an ordinary
`io.ltr8:tson:0.34.0-SNAPSHOT` dependency instead of an included build. **No remote repository is
configured, deliberately** — Maven Central needs signed artifacts and a POM with scm/developers, and
publishing under a name is not a decision the build should make quietly. The jars carry real
`module-info.class`es, so a consumer works on the class path or the module path; `tson-annotation` and
`tson-regex` land in a consumer's POM at runtime scope (they are `implementation` dependencies of the
modules that use them), which is enough for both, verified end to end against a real consuming build.

`BACKLOG.md` tracks the actively-maintained engineering backlog; `SPEC-FEEDBACK.md` records spec issues;
`STRUCTURED-OUTPUT.md` holds the target-use-case plan (LLM structured-output validation, JSON
compatibility).

## Not yet implemented

- **Part 2 resolution gaps** — **none: no
  `NOT_IMPLEMENTED` is reachable from a schema any more**, the pipeline reporting `SCHEMA_ERROR` for
  everything it refuses. A parameterized supertype resolves (`vip => <T> customer & box<T>` absorbs the
  operand's fields while the application is open, the operand contributing its own supertypes but not its
  name, a template being no type), and so does an argument that is itself an application (`box<inner<T>>` —
  substitution writes a bound reference through `SchemaDesugarer.refValue`, which spells one carrying
  arguments in `type_ref`'s record form). `OpenOperandCompositionTest` pins both, including the two IS-A
  edges an open operand does and does not give. `DefinitionResolver`'s Javadoc is the exact current boundary.
  Only about half the `UnsupportedOperationException` sites in the pipeline are gaps at all; the rest are
  schema-author errors or internal faults wearing the wrong exception type, and the classification is done.
  **Gaps reaching a read still exist**, two of them, both through `ErrorReader` and both on a schema that
  loaded clean: `unknown` and `extern` (below). Each **rides in the report as `NOT_IMPLEMENTED`**, located
  at the value it could not read, and costs that value a verdict and nothing else's — so a gap and an
  ordinary error in one document both get reported, and `TsonCli.exitCodeFor` lifts the run to 70.
- **A container position that is an application, and what a held open body still cannot say.** §5.10
  substitution works for both template shapes: a **record** template (parameters occupying field types and
  values) and an **open instance** — `<T> { v: [T] }`, or the explicit `<T, N> !array { element_type: T
  min_items: N }`. An open instance's body is **held** rather than quoted — the application as written, unread
  until materialisation substitutes its parameters away (`TemplateBody`/`HeldBody`, `docs/schema-resolution.md`)
  — which is what makes §5.10's "collection-valued slots are parameterizable" work: `result => <T>
  ( T | error )` (the spec's own example), `<T> [T, text]` and `<T> { v: (T | text) }` all resolve. A
  container position holding an
  application works too: the binding keeps the `type_ref` whole, so `tree => <T> { value: T  children:
  [tree<T>; 1..] }` ties its knot through the lifted synthetic. A *closed* container position takes one as
  well (`[box<text>]`, nested arguments included): the slot is written in `type_ref`'s record form and
  materialisation rewrites it to the instantiation entry one pass later, which needed `type_argument` — an
  untagged labelled choice — to become readable (`GroupUnionBindReader`). **A collection-valued position is
  no different** — `( box<text> | int32 )` and `[text, box<text>]` write the same record form into
  `variants`/`elements`, closed or open, because a `[type_ref]` holds what a `type_ref` holds. What remains is narrower: a *value*
  argument keeps its token, so `[vector<float32, 3>]` closes to a nested array with both bounds at 3
  (`RawTokenParser`); §4.3's equivalence is applied where identity is derived (`NumericIdentity`), so `<255>`
  and `<0xFF>` are one application while `1` and `1.0` stay two, §4 resolving them to different base types —
  §8.2's rule exactly, recorded as written and compared as the value denoted. **Every template holds its
  body**, so one process closes them all.
  §5.2 says `{ x: T }` denotes `!record { fields: [ { name: x  type: T } ] }`, and `SchemaDesugarer` rewrites
  it there, where the body is written; a **composition or refinement** template is held one phase later
  (`DefinitionResolver.holdIfOpen`), because both absorb fields from a source and the form to hold is the
  *flattened* one — but through `SchemaDesugarer.heldRecord`, so two producers of the wire form share one
  spelling. **An alias is written the same way**, and is: `uuid_pair => <B> pair<text, B>` leaves the desugar
  phase as `<B> !reference { target: pair<text, B> }`, spellable because the kernel's `reference.target` is a
  `type_ref`. So **every** open entry's body is held, with no exception — which is what lets materialisation
  dispatch on the constructor head (`record` closes to the instantiation, `reference` to a name, everything
  else to a synthetic) rather than on what shape the body arrived in.
  `record_field.value_param`, `instance_template` and `template_argument` are **gone from the kernel**: a
  routed parameter rides `value` with §8.1's shadowing rule to tell it from a literal, and §5.7's fixation
  moves to materialisation. What a held body cannot enforce is half of §5.10's argument-kind rule — see
  "Not yet implemented".
- **Undocumented atom constructors** — `unknown` (and `extern`, which has no core.tn declaration, so it is
  spellable only as `!extern { schema: … }` and never as a bare name) has no compiled-parser factory, so it
  compiles to `ErrorReader` (a schema merely *declaring* one still compiles; the first read of one fails).
  Neither is an ordinary missing parser waiting to be written: `extern` is a whole absent mechanism and `unknown`
  is the universe of types, not a token shape. `complex`/`ipv4`/`ipv6`/`cidr4`/`cidr6`/`mac`/`email` do have
  parsers — the CIDR pair reusing the two address grammars, and validating §5.5's family-range and
  host-bits-zero rules on top. **`email` is
  a built-in of §5.5 like its siblings**, and its format check is the subset §5.5 pins: the
  `dot-atom "@" dot-atom` core, without quoted local parts, domain literals or comments.
- **Schema-side diagnostics** — parsing, desugaring, resolution and linking all report through a
  `TsonDiagnosticsReceiver` (see `docs/readers-and-diagnostics.md`), and read- and schema-side diagnostics
  now populate the same four location components. Throw-site classification is done across the whole schema
  pipeline. The lexer stays fail-fast on purpose and is the floor under schema-parse recovery — not a tracked
  gap; `STRUCTURED-OUTPUT.md` holds the open question. **`schemaPosition` descends with the pointer** —
  `/person/age` carries `age`'s own line, `RecordField` holding an `@Unbound` position beside
  `TypeDefinition`'s and one `SchemaPositions` carrier threading both from the parser. What remains is the
  same gap for a **supertype and a choice variant**, both bare names in a list with nowhere to hang one, and
  a `caused by` frame chaining the author's location to the leaf constraint's (`BACKLOG.md`).
- **§5.10's argument-kind rule is answered by two other rules, not by the kind rule.** A held body has no
  slot types — that is what it is for — so it can never say *this slot expected a value*. Neither half needs
  it to: a literal applied where the body uses the parameter as a **type** is refused because `3` is not an
  identifier at all — `type_ref.name` is typed `identifier`, so it fails where the substituted body is read
  against the kernel's own vocabulary, which is sharper than the unresolved-reference verdict it used to get
  (that one implied an author could go and declare a type called `3`) — and a type name routed into a
  field's **value** is
  refused because §5.2 makes `record_field.value` a value of the field's declared type — which catches
  `int32 ~ text` whether a parameter put it there or the author wrote it literally (`TsonSchemaLinker`'s
  `checkFieldValue`, `FieldValueConformanceTest`). §5.10 states the same division — an argument is "read by
  the position it lands in" — and §5.2's value conformance is the half named there. What is left is that
  check's own boundary, below.
- **Deferred design questions** — the identity-diagonal FIXED-value invariant. **Routed-value
  substitution is
  no longer one of them**: an argument bound into a routed `=` fixes the field (`REQUIRED` →
  `REQUIRED_FIXED`, `~` staying a default), which is §5.7's "fixation happens downstream" applied to the
  downstream §5.7 now names ("fixation happens at materialisation"). **Thread-safety is no longer wholly
  open**: concurrent reads through one `Tson` are safe (the
  readers are immutable, the lexer/stream are per-read, both on-demand caches settle a race by keeping
  one entry, and a cache *hit* — which is every read, in a process that resolved its schemas at startup —
  takes no lock at all; `docs/linking-and-compilation.md`), and **both halves are now stated on `Tson` and
  `TsonConfig` themselves** rather than only in the design notes — a consumer reads the front door, not
  `docs/`, and that guarantee is what decides between one instance and one per request
  (`SharedInstanceConcurrencyTest` pins it at that surface). What is still open is everything *outside* a
  read: registering schemas concurrently, and mutating a `DataBindContext` after use.
- **§9.1's resource limits** (SHOULD, DoS-hardening) — none of them enforced: not nesting depth, token
  length, document size, or numeric-literal length. Depth is the one that bites, a document a few
  thousand containers deep overflowing the stack as an `Error` that no `catch (RuntimeException)` in
  the reader stack or the CLI sees; `BACKLOG.md` has what enforcing them needs.
- **JSON** — a future JSON reader is a whole separate stack (its own `JsonEventStream` and its own readers,
  deliberately not reusing the TSON readers). Not started, not backlogged.
