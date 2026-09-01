# Object binding

Reading straight into your own classes goes through **`tson-bind`** (`io.ltr8.bind`), a generic
`DataValue`↔Java-object binding engine that depends only on `tson-annotation` and knows nothing about
TSON. A `DataBindContext` holds the descriptors; `TsonObjectReader`/`TsonObjectWriter` drive it.

Binding is **reflective** — descriptors are derived from a class under analysis, never authored by
hand — so **the target class must be `public`**, since the library reaches it across a module boundary.

## What binds with no custom code

| Java                                        | TSON                                                             |
| ------------------------------------------- | ------------------------------------------------------------------ |
| a `record`                                  | a `record` — named fields, bound by component name                |
| a hand-written immutable class with `@Record` on its canonical constructor | the same                          |
| a `record` carrying `@Tuple`                | a `tuple` — positional, by constructor-argument order             |
| `List<E>`                                   | an array                                                          |
| `Map<K, V>`                                 | a map                                                             |
| a plain `enum`                              | an enum                                                           |
| a sealed interface, or one with `@Union`    | a choice                                                          |
| the built-in atom vocabulary                | the `java.base` types in the table below                          |

A cyclic type graph resolves: `getDescriptor` hands a re-entrant call a deferred supplier and each
holder keeps it in a final `Memoized`, so laziness is confined to the cyclic edge and every other
component still resolves eagerly.

## The built-in atom vocabulary → Java

Verified by reading each form through `TsonTreeReader` and asking `as(Object.class)` for its class.

| TSON                                            | Java                                            |
| ----------------------------------------------- | ------------------------------------------------- |
| `int8`                                          | `Byte`                                          |
| `uint8`, `int16`                                | `Short`                                         |
| `uint16`, `int32`                               | `Integer`                                       |
| `uint32`, `int64`                               | `Long`                                          |
| `uint64`, `int128`/`uint128`, `int256`/`uint256`| `BigInteger`                                    |
| `positive_integer`, `non_negative_integer`, `negative_integer`, `non_positive_integer` | `BigInteger`  |
| `number`                                        | `BigDecimal`                                    |
| `float32` / `float64`                           | `Float` / `Double`                              |
| `rational`                                      | `io.ltr8.tson.schema.atom.Rational`             |
| `complex`                                       | `io.ltr8.tson.schema.atom.Complex`              |
| `text`, `mac`, `cidr4`, `cidr6`, `email`, `regex` | `String`                                      |
| `uuid`                                          | `UUID`                                          |
| `date` / `time` / `datetime`                    | `LocalDate` / `OffsetTime` / `OffsetDateTime`   |
| `duration`                                      | `io.ltr8.tson.schema.atom.IsoDuration`          |
| `uri`                                           | `URI`                                           |
| `ipv4` / `ipv6`                                 | `Inet4Address` / `Inet6Address`                 |
| `base64`, `base64url`, `base32`, `hex`          | `byte[]`                                        |
| an untyped token (§4 base resolution)           | `Boolean`, `BigInteger`, `BigDecimal`, `String` |
| `_`, and `null` where §4 applies                | a `TsonAbsent` node / `null`                    |

An integer's host type is the **narrowest** that holds its declared range, so `int8` never hands back a
`BigInteger` for a value that fits a `Byte`. `unknown` and `extern` have no parser — a schema declaring
one compiles, and the first read of one reports `NOT_IMPLEMENTED`.

`TsonAtomContext.registerDefaults(context)` is the step that registers these on a `DataBindContext`, and
it is the one nothing reminds you of when building a context by hand. `TsonConfig.bindings(Map)` does it
for you.

## Naming the classes for a schema's types

```java
Tson tson = Tson.builder()
        .bindings(Map.of("order", Order.class, "customer", Customer.class))
        .build();
```

`bindings(Map)` is the short form of the long way, whose three steps include two that are invisible: a
caller who builds only a `DataNameBinder` gets atoms unbound, and one who maps their own names without
chaining loses the kernel's vocabulary. **A name outside the map is an error naming the map**, not a
class-not-found from whatever was consulted last.

`bindings` binds the **data** a schema describes. `metaNameBinder` is a separate namespace and binds the
**schema vocabulary** a governing *meta* describes — a consumer's own meta-schema declaring
`search => !operation { … }` needs its `operation` class there. It composes over the library's binder
rather than replacing it, so the kernel's names still resolve first.

`bindings`/`profile` are mutually exclusive with `dataBindContext(…)` — a context is built or given, not
both, and a profile is fixed when a context is built.

**Writing a binder rather than a map.** `DataNameBinder` is a one-method interface, and the implementation
a consumer usually wants is already there — mangle the schema's `snake_case` name to `PascalCase` and try
`Class.forName` against each package in turn, first match wins:

```java
new DataNameBinder.DefaultDataNameBinder(Set.of("com.example.api"), Map.of())
```

The second argument is aliases, applied *before* the mangling, for the names that do not follow the
convention. This is what `metaNameBinder(…)` usually receives.

**Components bind by exact name — there is no `snake_case` ↔ `camelCase` conversion.** A `request_stream`
field needs a `request_stream` component, or `@Field("request_stream")` on whatever the component is really
called. The mangling above is for **type** names only, which is the trap: a schema's `order_line` finds
`OrderLine` on its own, and that same schema's `order_line` *field* will not find an `orderLine`
component.

**This direction is for reading.** Writing resolves a value's type name from its class's own
`@Typename`, so a class mapped here without one reads but cannot be written.

## Strictness: the schema and the class must agree

Checked at **bind-mode compile** — startup, not first read — raising `TsonBindMismatchException`:

> `'order' and Wrong do not agree: no component for field 'placed'; no component for field 'total'.
> Bind the class the schema describes, or read leniently (TsonConfig.lenientBinding) if dropping this
> is deliberate`

Any non-FIXED field with no component, or a component no field fills, is refused. **Optional fields are
not exempt** — those are precisely the ones that work in development and fail on the first caller who
sends them. A FIXED field is exempt, the schema settling its value.

- `@Unbound` on a component marks it as the class's own — a source position kept for diagnostics, a
  cache, anything derived — so binding leaves it alone instead of reporting a mismatch. Without it, a
  component no schema field fills reaches the constructor as `null` however careful the class is.
- `TsonConfig.lenientBinding()` opts out wholesale, and is silent.
- `TsonMissingBindingException` (a subclass) covers a schema type with **no** class at all, and is
  deferred to the first read of that type — a schema legitimately declares types a consumer never binds.

**A bound class guards its own optional lists.** An omitted optional field arrives as `null` and the binder
does not normalise it to an empty list. A *required* one never reaches the constructor at all — the read
reports `FIELD_REQUIRED` and abandons construction first — so a null guard on a required component is
guarding against something that cannot happen, and defaulting one is masking a violation the read already
caught.

The place this bites is `Data.references()`, whose default returns `List.of()`: an implementation that
returns an optional component instead, unguarded, hands the linker a `null` it iterates — and the resulting
`NullPointerException` comes out of `Tson.resolve` looking like a fault in the library rather than a bug in
the calling class. Return `list == null ? List.of() : list`.

## Binding profiles

One class binds several schema shapes by offering a constructor per shape, selected by an **opaque
profile name** matched by equality — never by matching the schema's field set:

```java
public record Order(String sku, int quantity, String currency) {
    @Profile(value = "api-3", fields = {"sku", "quantity"})
    public Order(String sku, int quantity) { this(sku, quantity, "AUD"); }
}

Tson v3 = Tson.builder().bindings(…).profile("api-3").build();
```

A `Tson` is one profile: a server speaking two schema versions builds one instance per version and
routes a document to the right one. Nothing derives the profile from the schema a document names — that
mapping is the application's, and it is the one thing the application knows better than this library.
Pointing a profile at the wrong version does not bind quietly: the constructor it selects is checked
against that schema's fields, and a disagreement is a `TsonBindMismatchException`.

## The annotations (`io.ltr8.annotation`)

| Annotation      | On                                  | Does                                                                  |
| --------------- | ----------------------------------- | ----------------------------------------------------------------------- |
| `@Typename`     | type, field, method, parameter      | the schema type name this class writes itself as                      |
| `@Field("name")`| field, method, parameter            | override or supply the wire field name                                |
| `@Record`       | constructor, type                   | marks the canonical constructor of a hand-written immutable class     |
| `@Tuple`        | a genuine `record`                  | bind positionally (array-shaped) instead of by field name             |
| `@Union`        | type, field, method, parameter      | a choice whose variants are not discoverable as a sealed hierarchy    |
| `@Atom`         | constructor, type, method           | a class that is one scalar on the wire, with a `ToData` of that type  |
| `@Transparent`  | type                                | a one-component wrapper that is framing, not shape — unwrapped both directions |
| `@Profile`      | constructor                         | which binding profiles this constructor serves                        |
| `@Unbound`      | record component, field, method, parameter | this component is the class's own; no schema field fills it    |
| `@FieldOrder`   | type                                | the wire order, when the constructor's own is not what you want       |
| `@Namespace`    | type, package                       | an external schema name for the class or package                      |

**A `schema.meta` bind target with more than one public constructor needs `@Record` on the canonical
one**, or `DefaultRecordBinder` throws.

### Receiving wire annotations

Declaring a component of type `Annotations` **is** the whole opt-in — no marker annotation, no
registration:

```java
public record Person(Annotations annotations, String name) {}
```

That component is not bound from an authored field of the same name and takes no part in field
matching. `Annotation` is one `@name` / `@name:value`; an empty `value` is the valueless form.

`Annotation.value` is `Object` because its Java form depends on how the document was read: where the
name resolves to a type in the governing schema it is that type's own bound object, and where it
resolves to nothing it is a structural node preserving what the reader could not interpret.

**Whether a class has an `Annotations` carrier is no part of whether the document conforms** — a
schema-driven read type-checks annotation *names* against the governing schema wherever they are
written, not only where the reader keeps them.

## Known rough edges

- **A class mapped by `bindings` with no `@Typename` reads but cannot be written.**
- **`DataBindException` is checked**, on `DataBindContext`'s own descriptor API. The facade readers do
  not surface it; a hand-written binder call must handle it.
- **Mutating a `DataBindContext` after use is not thread-safe.** Concurrent *reads* through one `Tson`
  are; registering schemas and mutating a context after use are the two things still open.
