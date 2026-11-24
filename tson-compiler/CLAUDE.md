# tson-compiler — the TSON text engine

Lexer, both grammars, resolver, linker, compiler, compiled readers, writers and the read facades. Exports the packages
with cross-module callers; `resolver`, `reader`, `writer`, `atom`, `base` and `lexer` stay internal. Read the note
for the package you are in before editing — each opens with its invariants.

| Working in | Read (`design/…`) |
|---|---|
| `lexer/`, `stream/`, `TsonDataParser`, `ast/` | `lexer-and-data-parsing.md` |
| `base/`, `atom/` | `base-types-and-atom-vocabulary.md` |
| `TsonSchemaParser`, `ast/schema/` | `schema-grammar.md` |
| `resolver/SchemaDesugarer` | `schema-grammar-and-desugaring.md`, `desugaring-forms-over-parameters.md` |
| `resolver/DefinitionResolver`, `SchemaResolver`, `ConstructionSugar` | `schema-resolution.md`, `constructor-application.md`, `atom-refinement-and-coherence.md` |
| `resolver/WireForm`, `CanonicalApplication`, `KernelBootstrapResolver`, the model's `TypeSlots` | `resolver-vocabulary-and-bootstrap.md` |
| `resolver/ParametricMaterialiser`, `Substitution`, `ParameterTypes` | `parametric-records.md`, `parametric-materialisation.md` |
| `TsonSchemaLinker`, `ChoiceDisjointness`, `ValuesByName` | `linking-and-compilation.md`, `choice-disjointness.md`, `name-hygiene.md` |
| `TypeInhabitance`, `RecordExtension` | `inhabitance-and-record-extension.md` |
| `EnumLabels`, `FieldNames`, `resolver/KindMembers`, `resolver/NameType` | `kinds-and-name-types.md` |
| `TsonSchemaCompiler`, the registries | `class2-compilation.md`, `compiled-registries.md` |
| `reader/` | `readers-and-diagnostics.md`, then `record-dispatch.md`, `declared-positions.md`, `reader-naming-and-schema-location.md`, `name-hygiene-read-path.md` as the class requires |
| A diagnostic's code, message or location | `diagnostic-model.md`, `diagnostic-rules-and-messages.md`, `schema-side-diagnostics.md` |
| `TsonTreeReader`, `TsonObjectReader` | `facades-and-tree.md` |
| `writer/`, `TsonTreeWriter`, `TsonObjectWriter`, `TsonDocumentPeek` | `writers-and-document-header.md` |

Rules that bite here:

- No reader requires a materialised tree, and `TsonReadContext` holds no error policy — report through the receiver.
- A schema-author error is `SchemaValidationException`; a library gap is `UnsupportedOperationException` and travels
  as `NOT_IMPLEMENTED`; a broken invariant is `IllegalStateException`. `DefinitionResolver`'s Javadoc is the boundary.
- A parametric record is a `RecordBody` with `parameters`, built directly and substituted on the model; a
  parameter in it is a `ParamRef`, at any depth of a type written in place, and a closed entry holds none. Anything
  that builds a reader or a writer from a `RecordBody`, or treats one as a type, asks `isParametric()` first. There is one
  opinion about a binding's wire form, and it is `WireForm`'s.
- The grammar is checked where a name is read; §8.2 hygiene runs once per layer over scopes, never at a reading position.
- Lexer/parser/resolver behaviour changes get conformance vectors in the same session (`design/conformance-suite.md`).

Traps, one line each; the class Javadoc and the note carry the why:

- A constructor's application is no `ApplyBody`: it is rewritten to its construction before anything reads it
  (`ConstructionRewrite`).
- `SchemaDesugarer` returns un-rewritten nodes **by identity** — positions live in an `IdentityHashMap`.
- A desugar-reported declaration is replaced with an absorbing stand-in that **keeps its type parameters**.
- The resolver names engines, never facades: atom refinement merges through `writer.DataClassObjectWriter` on the wire
  record before binding — never through `TsonObjectWriter`, and there is no cheaper substitute.
- `requireDocumentEnd`: the pull past the root value is the point, not the assertion after it.
- Lexer multi-line close detection strips leading whitespace *before* comparing against `"""`.
- `Position` must keep the record's default `toString()` — the `tson` module's `ResolvedForm` normalises positions by
  regex over it.
- `CompiledReaders` is rebound exactly once, from the `Compilation` to the finished `TsonCompiledSchema`.
- `verifyFixed` compares with the pre-rebind parser (`FixedCheck`).
