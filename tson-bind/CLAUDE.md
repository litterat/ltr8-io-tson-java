# tson-bind — the generic binding engine

Binds a `DataValue` to a Java object. Depends only on `tson-annotation`.

- Knows nothing of schemas. A binding profile name is opaque — matched by equality, never interpreted.
- Constructor selection is by `@Profile`, never by matching a field set.
- Registration is `DataBindContext.Builder`'s and closes at `build()`.
- A cyclic type graph resolves through a deferred supplier held in `Memoized` (`RecursiveModelTest`); laziness stays
  confined to the cyclic edge.
- A name index is built once per class (`DataClassRecordFieldIndexTest`).
