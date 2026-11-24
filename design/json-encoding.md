# The JSON encoding

Why `tson-json` ([TSON-JSON]) is a stack of its own, what holds it to the TSON stack's verdicts, and the on-ramp it is
for. The layers are in the sibling notes; the packages in `io.ltr8.tson.json`'s `package-info`.

**Invariants**

- `tson-json` does not build on `tson-compiler`'s `TsonEventSource`: it has its own lexer, structural layer, tree and
  readers, because a pull-only event source has no channel for the position to say record-or-map or what `null` is.
- Compiling JSON readers from a `TsonLinkedSchema` adds no dependency on `tson-compiler`: the linked schema is a
  `tson-schema` value, only its production the TSON engine's. That keeps the deferral below free.
- One `ProcessorConfig` value serves both front doors, so a deployment states its settings once; a diagnostics receiver
  belongs to a read, not a deployment, so neither front door has `withDiagnostics` (each reader has).
- JSON has no schema documents of its own (§3.4 binds out of band): `Json` has a way to *name* a schema
  (`withSchemas`), never a way to author one.
- A schema-directed tree read produces a `JsonValue`, never a `TsonValue`; `ValueReaderFactoryRegistry.atoms()` stays
  as what the parsing contract is asserted against.
- `CrossEncodingParityTest` asserts the same `Diagnostic.Code` and RFC 6901 pointer, never the message, over the rules
  that are actually written twice; a legitimate divergence is asserted as a divergence, and a defect is never pinned
  as agreed.
- The target is existing JSON validating **unchanged**; a converted schema outside the profile below breaks that.

Related: `design/json-lexer-stream-tree.md`, `design/json-schema-directed-reading.md`,
`design/json-facades-binding-writing.md`, `design/json-unicode-policies.md`, `design/modules.md`,
`design/cli-config-hashing.md`, `design/readers-and-diagnostics.md`.

## A stack of its own

Building on `TsonEventSource` would reuse resolution, linking and every compiled reader unchanged. Two disagreements
defeat it, both the shape of the JSON-superset claim [TSON-DATA] §1.1 withdrew: reuse paid for wherever the designs
differ.

- **A brace does not say what it is.** TSON text tells a record from a map syntactically, and each compiled reader
  asserts which event it got. JSON's `{"a": 1}` is one syntax for both and §4.1 makes the *position* decide. Every way
  of giving a pull-only source that channel — a hint call, a schema-walking stream, readers accepting either shape —
  puts the JSON encoding's problem inside the TSON reader stack.
- **`null` is two things**: a value in a plain JSON tree (JEP 540's `JsonNull`), the void sentinel under a schema (§7).
  Mapping it to `VoidEvent` settles that one layer too early and makes the schemaless reading inherit a schema's answer.

**Above the schema the argument does not reach, so sharing there is deferred, not refused.** A compiled reader *is*
the position, so one compiled schema over an encoding-neutral read context, with the encoding pushed into a source the
reader drives, is a real design (TSON text's `EmptyBraceEvent` is the precedent, [TSON-DATA] §2.8). It is not taken
because it would be designed from one implementation: the seam is found from two working stacks, and merging two
passing implementations is cheap where unpicking a wrong shared contract is not (`BACKLOG.md`).

**Tree mode hands back JSON.** Tree mode answers *does this document conform*, bind mode *give me the value*;
converting an encoding is a third operation, which a `TsonValue`-producing read would disguise as the first. The read
runs each family's parser (§5.1) and discards the host value, so a clean tree read cannot show what `date` parsed;
`atoms()`, §5's vocabulary with no mode over it, is what `JsonAtomReadTest` compiles against to see it.

## The parity guard

Two copies of the field rules can drift, and §9.4 makes one verdict across encodings an obligation. That needs
checking, not shared code (`CrossEncodingParityTest`'s Javadoc); a local test, because the corpus cannot state a fact
about two encodings.

- **Scope is what is written twice**: closure, duplicate members, §5.2's field states, injection, the FIXED check,
  group multiplicity, container size and arity. Not the atom vocabulary — `tson-atom` is one implementation both call,
  and asserting it here would test shared code twice.
- **The codes most easily given differently** are what it pins: a size facet is a constraint code, never
  `TYPE_MISMATCH`; an absent element or tuple slot is `FIELD_REQUIRED`. Where the two disagree the JSON reader moves:
  the TSON side is the incumbent of one closed vocabulary.
- **Both ways**: a disagreement may be the incumbent's defect (value identity was: both stacks' `ValueIdentity` keep
  scale outside the value, [TSON-SCHEMA] §5.5, §5.3). A defect is fixed where it is; pinning it as agreed makes it
  permanent.
- **One divergence is by specification and asserted as one**: `name: 42` at a `text` field is a token `text` accepts
  in TSON text, a number kind §5.6 refuses in JSON. §5.1 makes which kinds reach a parser each encoding's own (all of
  `AtomForm`); pinning it stops a later change "fixing" it into agreement.

## One configuration, one atom vocabulary

The configuration is a `tson-base` value and construction is not: nothing about the *settings* is an encoding's.
Its schema access does not reach `Json`, by division of labour: resolving schema text is the TSON engine's, so `Json`
takes resolved schemas through `withSchemas(TsonSchemaLoader)`. `Json` keeps JEP 540's static `parse`/`toDisplayString`
because a consumer arriving from the JDK's API should find them; `Tson` keeps `resolve`, `validate` and the registries.

Both front doors' defaults start from `AtomContext`, because §5.1 hands a string's content to the atom's own parser as
a TSON quoted token's text would be: which families a reader binds is the type system's property, not the encoding's,
and a `UUID` component must not be a scalar at one door and taken apart at the other. The conversion is the family's,
through `HostAtoms` (`design/json-facades-binding-writing.md`).

## The on-ramp, and what it constrains

The target: a JSON Schema or OpenAPI contract converted to a TSON schema, against which the documents already in
flight validate byte for byte (§1.3's principle 2) — a format that asks the producer to change first has lost the
audience it was converting. So the goal decides what a converted schema may contain.

**What a converted schema will not contain**, each because JSON Schema has no source for it or reaching it breaks
documents on the wire:

- **Non-text map keys** — JSON Schema keys are strings, so §6.4's pairs form is unreachable.
- **Annotations on data values** — no JSON carrier (§4.3); `JsonObjectWriter` refuses them unless
  `strippingAnnotations()`.
- **Approximate atoms admitting the specials** — no JSON document carries `.nan`; a converted `type: number` narrows
  `allow_nan`/`allow_infinity`, keeping the number class so a choice listing it can stay disjoint (§8.3).
- **Declared positions** — `declared` needs an annotation object (§8.5); `additionalProperties: true` converts to §5.7's
  tag-free `json` choice.
- **In-band root binding** — existing JSON has no `$import`/`$type`; the root binds out of band (§3.4), through
  `Json.withSchemas` and `tson validate --schema --type`.

Not on it: tuples (plain arrays), defaults (§6.1.3), and a member name that is no identifier (`@type`, `$ref`), a
field name under a declared name type ([TSON-SCHEMA] §5.2).

**Where the on-ramp breaks**, costing a producer a change, most frequent first:

1. **Records are closed, JSON Schema's open** (`additionalProperties` defaults true): the first extra member fails
   §6.1.1, and open-ended data must nest under a map-typed field. The largest producer-visible cost.
2. **An untagged `oneOf` over objects**: all brace class, so not disjoint, and §8.2 forbids trying branches. The
   converter finds a discriminator (a sealed family, §6.1.5) or reports.
3. **Enum members must be identifiers**: `"not found"` falls back to a patterned `text`, losing a discrimination class.
4. **A pin beside null has no spelling**: `const` or a one-member `enum` beside `"null"`; required-but-nullable
   converts (`x: X?`, §7.3).
5. **`format` becomes binding**: advisory in JSON Schema, an atom here, so a converter should say so.
