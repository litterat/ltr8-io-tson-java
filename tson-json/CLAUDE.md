# tson-json — the JSON encoding ([TSON-JSON], `spec/tson-part3-json.md`)

A stack of its own — lexer, stream, tree, readers, writers, schema-directed readers — with **no dependency on
`tson-compiler`**. Start with `design/json-encoding.md` for why; then the layer you are in:

| Working in | Read (`design/…`) |
|---|---|
| `lexer/`, `stream/`, `tree/` | `json-lexer-stream-tree.md` |
| `reader/` schema-directed (`Tree*Reader`, `JsonSchemaCompiler`, `JsonTypeReader`) | `json-schema-directed-reading.md` |
| `Json`, the two facades, `reader/DataClassObjectReader`, `writer/` | `json-facades-binding-writing.md` |
| Identifier or token policy, `NameHygiene` | `json-unicode-policies.md` |

Rules that bite here:

- **Part 3 is drafted in this repo.** A finding is an edit to `spec/tson-part3-json.md` in the same session, stated as
  a rule, never mentioning this codebase. A section you build against and leave unedited is one you assert is right.
- The position decides record-versus-map (§4.1), chosen at compile time from the key type — never by inspecting the value.
- JEP 540 alignment is the `tree` package's value model and spelling, and nothing else. Reading, writing and exceptions
  follow the TSON side: `ReadException` with a `Diagnostic`, one closed `Code` vocabulary (§9.4). There is no
  `JsonParseException`.
- A schema-directed read returns a `JsonValue`, never a `TsonValue`.
- The field-state rules exist twice; `CrossEncodingParityTest` is the guard. A rule changed here or in
  `tson-compiler`'s readers gets a parity case. Shared refusals are stated once in `tson-base`'s `base.diagnostics`.
- `Json` prefixes exported types only; `reader` types are bare and named mode first (`TreeRecordReader`).
- The look-alike (confusable) rule reaches no JSON position — settled, not owed.
