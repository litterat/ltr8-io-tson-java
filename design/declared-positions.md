# Declared positions: `DeclaredReader` and `DispatchDeclaredReader`

How a `declared` position ([TSON-SCHEMA] §7.8) is read in both encodings, and where a schema reference at a value
position is refused. Current form only; history lives in git.

**Invariants**

- The kernel's `declared_type => sum & {}` has one instance, the kernel's `declared`, and one reader per encoding:
  `DeclaredReader` (TSON text) and `DispatchDeclaredReader` (JSON). Both select a reader and build nothing, so one
  factory serves every mode.
- The schema map's values are `declared` (`schema => !namespace { key_type: type_name  value_type: declared }`): a
  type entry's tag names its constructor, a value entry's its type. Where the tag selects a namespace type, the TSON
  reader delivers the map as the `Namespace` it is (`NamespaceInstanceReader`, `design/meta-layer-namespaces.md`).
- The closure is fixed at compile time: the entry belongs to one schema, and every name it holds is resolved through
  the same reader resolver every other dispatch uses.
- A value naming no type is a **validation** error (`VALIDATION_ERROR`) in every mode; a tag naming no type of the
  closure is an unresolved reference (`UNKNOWN_TYPE`).
- A directive stands only in the header. In TSON text an `!!import` at a value position is a **parse** error, raised by
  `TsonDataStream` like any directive out of place; in JSON an `$import` at any value position is a resolver error,
  `SCOPE_NOT_ADMITTED`, reported by `Tags` wherever the leading members are read.

Related: `design/readers-and-diagnostics.md`, `design/record-dispatch.md`, `design/json-schema-directed-reading.md`,
`design/lexer-and-data-parsing.md`, `design/diagnostic-model.md`.

## The open sum over the closure

`declared_type` is the open sum: where a `choice` enumerates its variants, a `declared` position draws them from the
closure of the schema that declares it — its own declarations and its imports — and the value names which one by its
tag. The value is then read in full by that type's own reader, so everything below it is validated exactly as at a
position typed by the named type, and every mode hands back what that reader built. The body carries no field: what a
position admits is the closure, which the entry's schema fixes, so the constructor alone tells `declared` apart from
every other sum. `disjoint` is absent, as on every non-choice sum (§8.1), and the discrimination predicate gives a
`declared` instance no class, so a choice listing one is not disjoint and its values reach it only tagged.

**The tag is the whole of what the value says about its type.** A position that is open has nothing to infer a type
from, so an untagged value is refused rather than read by base type resolution: nothing failed to resolve, which is
why the verdict is validation and not resolver. A tag the closure does not hold did fail to resolve, and is
`UNKNOWN_TYPE`. In JSON the value is an annotation object led by `$type` — inline when the named type reads the value
as a record, wrapper otherwise (§3.3) — and any other value, or an annotation object with no `$type`, names no type.

## A schema reference at a value position

A value names its type, never its schema. [TSON-DATA] §3.3's closed positional set admits `!!import` in the header and
nowhere else, so the data stream refuses one prefixing a field value, a map entry value or an array element as it
refuses any directive at a value position (`TsonDataStream.notAValue`): the grammar has no production for it, and the
ast carries no slot for one — a record field, a map entry value and an array element are plain `DataValue`s. JSON has
no grammar for the member, so the refusal is the reader's: `$import` leads only the root's annotation object (§3.3,
§3.4), and an `$import` anywhere else is refused by `Tags.refusesScope`, at a record, a choice and a declared position
alike, as `SCOPE_NOT_ADMITTED` against the `$import` member's own path, whatever its value.
