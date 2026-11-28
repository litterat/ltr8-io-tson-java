# ltr8-bind — the generic binding engine

Binds a `DataValue` to a Java object. Depends only on `ltr8-annotation`.

- Knows nothing of schemas. A binding profile name is opaque — matched by equality, never interpreted.
- Constructor selection is by `@Profile`, never by matching a field set.
- Registration is `DataBindContext.Builder`'s and closes at `build()`.
- A cyclic type graph resolves through a deferred supplier held in `Memoized` (`RecursiveModelTest`); laziness stays
  confined to the cyclic edge. A descriptor is cached only together with every descriptor its deferred edges reach,
  so a refusal leaves nothing behind that reaches it (`RecordRefusalTest`).
- A class joins its open unions when its component is cached, never while it resolves
  (`OpenUnionMembershipTest`).
- A name index is built once per class (`DataClassRecordFieldIndexTest`). A class's component names are distinct
  across every finder, `@Unbound` ones included and the `Annotations` carrier not (`RecordRefusalTest`).
- A refusal's own `getMessage()` is the fact: a `DataBindException` raised while describing a class passes up
  unwrapped, however deep the class sits (`RecordRefusalTest`).
- A bridged class is concrete, declares `ToData<S>` itself and has a public constructor taking exactly `S`
  (`ToDataBridgeTest`).
- An atom says its way in, `@Atom` on one public constructor or static method of its own, and its way out, `@Atom` on
  one public instance method or `ToData<S>`, possibly inherited; nothing is found by name, and the halves carry one
  value up to boxing. A `ToData` class marking no `@Atom` is a bridged class (`AtomFormTest`). `@Atom` on a class
  described as anything else, or on a super class's static method, refuses the class (`UnreadAtomRefusalTest`), save
  on a record, where only `registerAtom` reads it, and on an instance method a class described as an atom may inherit.
- A binding annotation is never silently ignored: `@Field`, `@Union` or `@Unbound` is applied, or the class is
  refused (`IgnoredAnnotationRefusalTest`); a component's members merge `@Field` by attribute and must agree
  (`ComponentAnnotationPrecedenceTest`).
- What each kind reads of a class, its class-level marks and the walks over its members, is one table, `KindMarks`,
  applied for every kind of either entry point; a binder runs no walk of its own.
- A `@Field` bridge or a registered atom's, unlike a `ToData` class, may inherit `DataBridge<D, O>`; both arguments
  must resolve to classes, and a registered atom's `O` is the atom's class (`BridgedFieldTest`, `RecordRefusalTest`,
  `AtomBridgeRegistrationTest`).
- Also describes an interface (`getProtocol`); an exchange's invoker catches and wraps no throwable (`ExchangeHandlerTest`).
