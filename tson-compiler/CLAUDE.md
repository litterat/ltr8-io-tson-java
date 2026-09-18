# tson-compiler — the TSON text engine

Lexer, both grammars, resolver, linker, compiler, compiled readers, writers and the read facades. Exports the packages
with cross-module callers; `reader`, `atom`, `base` and `lexer` stay internal. Read the note for the package you are in
before editing — each opens with its invariants.

| Working in | Read (`design/…`) |
|---|---|
| `lexer/`, `stream/`, `TsonDataParser`, `ast/` | `lexer-and-data-parsing.md` |
| `base/`, `atom/` | `base-types-and-atom-vocabulary.md` |
| `TsonSchemaParser`, `ast/schema/` | `schema-grammar.md` |
| `resolver/SchemaDesugarer` | `schema-grammar-and-desugaring.md`, `desugaring-open-forms-and-templates.md` |
| `resolver/DefinitionResolver`, `SchemaResolver` | `schema-resolution.md`, `constructor-application.md`, `atom-refinement-and-coherence.md` |
| `resolver/WireForm`, `MetaRefs`, `DerivedName`, `MetaKernelBootstrapResolver` | `resolver-vocabulary-and-bootstrap.md` |
| `resolver/TemplateMaterialiser`, `SyntheticMerge`, `ParameterKinds`, `HeldBody` | `held-template-bodies.md`, `template-materialisation.md` |
| `TsonSchemaLinker`, `ChoiceDisjointness`, `TypeInhabitance` | `linking-and-compilation.md`, `choice-disjointness.md`, `name-hygiene-and-minted-names.md` |
| `TsonSchemaCompiler`, the registries | `class2-compilation.md`, `compiled-registries.md` |
| `reader/` | `readers-and-diagnostics.md`, then `record-dispatch.md`, `scope-push.md`, `reader-naming-and-schema-location.md`, `name-hygiene-read-path.md` as the class requires |
| A diagnostic's code, message or location | `diagnostic-model.md`, `diagnostic-rules-and-messages.md`, `schema-side-diagnostics.md` |
| `TsonTreeReader`, `TsonObjectReader` | `facades-and-tree.md` |
| `writer/`, `TsonTreeWriter`, `TsonObjectWriter`, `TsonDocumentPeek` | `writers-and-document-header.md` |

Rules that bite here:

- No reader requires a materialised tree, and `TsonReadContext` holds no error policy — report through the receiver.
- A schema-author error is `TsonSchemaValidationException`; a library gap is `UnsupportedOperationException` and travels
  as `NOT_IMPLEMENTED`; a broken invariant is `IllegalStateException`. `DefinitionResolver`'s Javadoc is the boundary.
- Every open entry's body is held text; there is one opinion about its wire form, and it is `WireForm`'s.
- The grammar is checked where a name is read; §8.2 hygiene runs once per layer over scopes, never at a reading position.
- The resolver names engines (`writer.DataClassObjectWriter`), never facades.
- Lexer/parser/resolver behaviour changes get conformance vectors in the same session (`design/conformance-suite.md`).
- The Traps list in the root `CLAUDE.md` is mostly about classes in this module.
