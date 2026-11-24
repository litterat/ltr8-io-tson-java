# Read facades: `TsonObjectReader` and `TsonTreeReader`

The two consumer read front doors (`tson-compiler` root package): their two modes, their derivations, and the rules a
read applies around the value. The class Javadoc states each mode and control; this note holds what is easy to break.

**Invariants**

- Each reader is dual-mode, fixed at construction: standalone is schemaless, obtained from a `Tson` it is
  schema-aware; a derived reader shares the original's compiled-schema registry, never rebuilding it.
- A failure reaching or resolving the schema is a diagnostic, not an exception — and a schema's own diagnostics go to
  the facade's receiver, never through `ctx.report`, which would rebuild them from the *data* cursor, stamping a data
  position on a problem in a schema and discarding its `schemaPointer`. A schema that resolves badly reports every
  declaration at fault; one that cannot be reached is one diagnostic, there being nothing to enumerate.
- A root value a read abandons — its schema not obtained, not loaded, or declaring no type the root names — is
  skipped unjudged (`DefaultTsonReadContext.discardUnjudged`): parsed, so a syntax error still reports, and held to
  no name rule, since no schema is judging it and the schemaless rule would refuse names the schema may admit.
- A `TsonReadContext` always carries an identifier policy: `of(events, receiver)` defaults it to Highly Restrictive
  and `of(events, receiver, policy)` refuses `null`, so the low-level API cannot skip the policy by saying nothing.
  The token policy is the stream's, not the context's, because the context rewinds and a check there would report one
  token twice (`design/name-hygiene-read-path.md`).
- A lookup of the root type-ref, and every dispatcher, looks ahead by rewinding (`EventSkip.typeRefAhead` over
  `TsonReadContext.lookingAhead`) and never consumes the framing — consuming it silently strips the value's
  annotations. In bind mode that is a variant's `Annotations` carrier arriving empty while the same class read where
  nothing dispatched gets the annotation: a document's prose surviving or not according to how deep the value sat.
  Rewinding makes both modes agree by construction rather than by two implementations staying in step.
- The whole-document entry points own framing, including the `requireDocumentEnd` pull that makes the lazy stream
  check for trailing content; the low-level `read(TsonReadContext, …)` is frame-free and always schemaless. Every
  whole-document read but `readAs` refuses a schema document (`requireDataDocument`).
- A schemaless read checks type-refs by `TypeTagCheck`'s three rules. Rule 2 (a tag naming the bound class or a union
  member) applies only under `withHostTypes()`; `preservingUnknownTypeRefs()` opts out of rule 3 only; an atom
  position takes `declares` (`@Typename` only), never the loose `names` match.
- Reporting never abandons the value: a reported type-ref still yields its node and its children are read.
- An annotation is checked wherever it is written and kept only where there is room (`capture()` vs `validating()`),
  so the bound class's carrier never decides the verdict; a field the target class does not declare is
  `UNRECOGNIZED_FIELD`, with `ignoringUnknownFields()` the derived opt-out.
- A schema-driven tree reader captures annotations by hoisting (`AnnotationCapture`), which is a no-op for the
  delegate only because every reader it wraps **discards** the framing result rather than using it.

Related: `design/writers-and-document-header.md` (writers, `TsonDocumentHeader`, `TsonDocumentPeek`),
`design/tree-model.md` (`TsonValue`, `TsonDocument`, what a name reads to), `design/front-door-and-config.md`
(`Tson`, `ProcessorConfig`, schema sources), `design/processor-policy.md` and `design/name-hygiene-read-path.md`
(`withIdentifierPolicy`, `withTokenPolicy`, `withLimits`), `design/readers-and-diagnostics.md`,
`design/linking-and-compilation.md`.

## Decisions that lost

- **Parameters instead of derivations.** Each control (`withDiagnostics`, `withHostTypes`,
  `preservingUnknownTypeRefs`, `withTokenPolicy`, `withIdentifierPolicy`, `withLimits`, `withSchema`/`withImports`
  with `readAs`) derives a new reader, Jackson-`ObjectReader`-style, so source form, error policy, schema selection,
  Unicode policy and resource limits stay orthogonal instead of multiplying overloads.
- **The Unicode policies as registry settings.** They are reader axes, because the surface they guard includes the
  standalone schemaless constructors, which hold no registry at all — and a Class 1 read is exactly where a value
  arrives least constrained.
- **Finding the root type-ref at the first event.** `data-value = *annotation [type-ref] core-value`, so
  `@doc:"…" !api { … }` annotates and types one value, and its root type-ref is `!api`. TSON has no comment syntax
  ([TSON-DATA] §2.4), so an annotation is the only way a document says what it is for, and a root that cannot carry
  one leaves configuration, fixtures and API descriptions unable to.
- **Rule 2 by default.** With it on, a schemaless bind accepts what the vocabulary alone refuses, and `tson validate`
  would disagree with it on the same document. `withHostTypes()` is the caller supplying the schema the document does
  not bind, here as classes — the same move as `withSchema`.
