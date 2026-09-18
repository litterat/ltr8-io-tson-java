# tson-base — what every encoding shares

Diagnostics, policies, schema sources, host atom values, byte I/O, UCD tables, `ProcessorConfig`. Read
`design/tson-base.md`; for the `Diagnostic` record and the rule classes, `design/diagnostic-model.md` and
`design/diagnostic-rules-and-messages.md`; for policies, `design/processor-policy.md`.

- Nothing here knows what a TSON or a JSON document looks like. If a change needs to, it belongs in an encoding.
- The root package names none of its subpackages; dependencies run inward. Exceptions stay at the root for that reason.
- No `Tson` prefix in this module (`ReadException`, `ParseException`).
- `Diagnostic` components are locations. A new component must be a fact not recoverable from document plus schema and not
  one a consumer routes on — what a consumer routes on is the `Code`.
- Each encoding owns the classifier over its own exceptions; only `Diagnostic.ofLimitExceeded` lives on the record.
- `base.diagnostics` prose is the schema's vernacular (fields, *absent*); the encoding's spelling rides in `actual`.
- `ByteSource`/`ByteSink`: bytes never characters; close releases only what was acquired; closing is not flushing.
- `IdentifierProfile.validate`/`hygiene` report and never throw. `ProcessorPolicy` refuses a per-segment token policy in
  its compact constructor.
