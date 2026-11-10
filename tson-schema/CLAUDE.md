# tson-schema — the resolved-schema value model and the registry

The resolved-schema model (pure records, sealed interfaces, enums — §8's model), one package per namespace its entries
are declared in (`schema.meta`, `unicode`, `numeric`, `net`, `binary`, `temporal`; the unexported `schema.atom` holds the
families' shared facet mechanics), `TsonSchemaRegistry`/`TsonLinkedSchema`/`TsonSchemaLoader`, and
`TsonBundledSchemas`. Read `design/linking-and-compilation.md` for the
registry and identity, `design/namespace-types-and-value-entries.md` for `NamespaceBody`, `Namespace` and `ValueEntry`,
`design/held-template-bodies.md` for `TemplateBody`.

- Names no `tson-compiler` type — the engine depends on this module, not the reverse. Where a compiler type is needed
  structurally, declare a local stand-in (`schema.meta.Token`, `SourcePosition`).
- A class lives in the package of the namespace its entry is declared in, so the binder reaches it by path
  (`numeric.integer_type` is `schema.numeric.IntegerType`); a class no entry declares stays in `schema.meta`.
- `Top` is sealed all the way down: `Type`, `Alias`, `TemplateBody`, `ValueEntry`. An entry whose tag names an
  ordinary type is a `ValueEntry` wrapping the value (a `Namespace`, a bound object, or a tree); `Namespace` is the
  value of a namespace type and no `Top`.
- An atom body's components mirror its constructor's *resolved*, flattened shape — one component per schema field name.
  A nested or missing component binds `null` silently.
- A bind target with more than one public constructor needs `@Record` on the canonical one.
- A reference's argument is itself a `TypeRef`, a name or an application, never a literal. `RecordField.position`
  is `@Unbound`; an entry's position, annotations and constructor tag live beside the map in `TsonSchema`'s per-entry
  facts, never in a body.
- The registry never overwrites an identity. Bundled schema text comes from `spec/m/` at build time and is verified
  against `TsonBundledSchemas`' digests on every load: after editing one, run `scripts/restamp-bundled-schemas.sh`.
