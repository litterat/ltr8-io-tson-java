# tson-tree — the `TsonValue` tree model

Read `design/tree-model.md`. Depends on nothing, and names no `tson-compiler` type.

- Pure immutable nodes, read-side only: no builders or transforms until a concrete use case exists.
- `get`/`at` never throw; a `TsonMissing` carries the RFC 6901 pointer of the failed step.
- One no-value node: `TsonAbsent` is `_` (or a collecting-mode failure). `null` is a `TsonAtom` holding a string.
- `TsonScopedValue` wraps and is transparent to navigation.
- `as(Class)` casts; `asInt`/`asLong`/`asDouble` convert. A test about host type uses `as(Class)`.
