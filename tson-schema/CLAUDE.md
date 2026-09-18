# tson-schema — the resolved-schema value model and the registry

`schema.meta` (pure records, sealed interfaces, enums — §8's model), `TsonSchemaRegistry`/`TsonLinkedSchema`/
`TsonSchemaLoader`, and `TsonBundledSchemas`. Read `design/linking-and-compilation.md` for the
registry and identity, `design/meta-layer-data-kind.md` for `Data`, `design/held-template-bodies.md` for `TemplateBody`.

- Names no `tson-compiler` type — the engine depends on this module, not the reverse. Where a compiler type is needed
  structurally, declare a local stand-in (`schema.meta.Token`, `SourcePosition`).
- `Top` is sealed except for `Data`, the one deliberately open branch.
- An atom body's components mirror its constructor's *resolved*, flattened shape — one component per schema field name.
  A nested or missing component binds `null` silently.
- A bind target with more than one public constructor needs `@Record` on the canonical one.
- `TypeArgument` stays a sealed interface. `TypeDefinition.kind` and the `position` components are `@Unbound`.
- The registry never overwrites an identity. Bundled schema text comes from `spec/m/` at build time and is verified
  against `TsonBundledSchemas`' digests on every load: after editing one, run `scripts/restamp-bundled-schemas.sh`.
