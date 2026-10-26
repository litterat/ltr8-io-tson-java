# tson-schema — the resolved-schema value model and the registry

`schema.meta` (pure records, sealed interfaces, enums — §8's model), `TsonSchemaRegistry`/`TsonLinkedSchema`/
`TsonSchemaLoader`, and `TsonBundledSchemas`. Read `design/linking-and-compilation.md` for the
registry and identity, `design/meta-layer-namespaces.md` for `Namespace`, `design/held-template-bodies.md` for
`TemplateBody`.

- Names no `tson-compiler` type — the engine depends on this module, not the reverse. Where a compiler type is needed
  structurally, declare a local stand-in (`schema.meta.Token`, `SourcePosition`).
- `Top` is sealed all the way down; a meta layer's own vocabulary reaches a schema map as a `Namespace` value, never as
  a `Top` branch of its own.
- An atom body's components mirror its constructor's *resolved*, flattened shape — one component per schema field name.
  A nested or missing component binds `null` silently.
- A bind target with more than one public constructor needs `@Record` on the canonical one.
- `TypeArgument` stays a sealed interface. `RecordField.position` is `@Unbound`; an entry's position, annotations
  and constructor tag live beside the map in `TsonSchema`'s per-entry facts, never in a body.
- The registry never overwrites an identity. Bundled schema text comes from `spec/m/` at build time and is verified
  against `TsonBundledSchemas`' digests on every load: after editing one, run `scripts/restamp-bundled-schemas.sh`.
