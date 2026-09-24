# What a diagnostic says: shared rules, atom refusals, messages

Where a rule's code, message, `expected` and `actual` are stated — `base.diagnostics` for the rules both encodings
share, `AtomRefusal` for the atom vocabulary — and how base-syntax failures reach a receiver. Current form only;
history lives in git.

**Invariants**

- A `Diagnostic`'s four rule components come from `base.diagnostics` (`Refusal`); every reader supplies only where it
  happened.
- The prose is the schema's vernacular, not the format's; the encoding's own spelling rides in `actual`, which the
  parity test never compares.
- An atom refuses in two categories with two codes: `ATOM_FORM_INVALID` for an `AtomParseException`,
  `ATOM_CONSTRAINT_VIOLATION` for an `AtomValidationException` — mapped in `AtomRefusal` and nowhere else.
- A broken FIXED field is `FIELD_FIXED`, not an atom code.
- `expected` carries the constraint that failed, in `AtomTypeException`'s six shapes, never the type's name; the name
  leads `message`.
- There is deliberately no message-synthesis layer; every report site states a complete structured half.
- A base-syntax exception keeps its position out of `getMessage()`, and a base-syntax failure goes to the receiver —
  a collecting read never throws for a bad document.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/scope-push.md`,
`design/record-dispatch.md`, `design/name-hygiene-read-path.md`, `design/diagnostic-model.md`, `design/processor-policy.md`,
`design/schema-side-diagnostics.md`.

## Where a rule is stated: `base.diagnostics`

A `Diagnostic` has nine components and they come from two places. Four are the **rule's** — which rule fired,
what it says, the constraint that was not met, and what the document held instead. Five are the **read's** —
the RFC 6901 path, the schema identity and pointer, and both positions. No rule knows the second set and no
read knows the first, which is why `io.ltr8.tson.base.diagnostics` holds the rule half as a value (`Refusal`)
and every reader supplies where it happened.

**Why it is shared, and why that is an obligation rather than a tidiness.** [TSON-JSON] §9.4 gives both
encodings one diagnostic vocabulary and adds no category of its own, so a document wrong in one encoding is
wrong in the other for the same stated reason. The `code` and the machine-readable `expected` are what a
consumer routes on — and two independently written record readers agree about them only if something holds
them there. Stating each rule once, here, is what does.

**The prose is the schema's vernacular, not the format's.** A record has *fields* in both encodings, even
though JSON's own word for what carries one is a member; absence is *absent* rather than `_` or `null`. The
reason is consistency about the thing being described: it is the **schema** that refused the document, so the
schema's nouns explain it, and a reader who moves between encodings learns one vocabulary. The encoding's own
spelling is not lost — it rides in `actual`, which echoes what the document literally held and is data rather
than prose.

That split tells the parity test exactly what to compare: **code, path, `expected` and `message`** for a
shared rule, and never `actual`, where `_` on one side and `null` on the other is correct.

**Absence written at a REQUIRED field is its own rule, not the missing-field one.** Both readers say
"'name' on 'person' admits no absence" and neither says "missing required field 'name'": the two share a code,
a pointer and an `expected`, and the second's prose tells an author they have forgotten a field they can see
themselves writing. §5.2's rule is that `_` asserts absence at a position the schema always fills, so the
document stated something and is not missing it, and the message reports the rule the document actually
broke. Sharing the prose is what keeps the two readers from choosing different rules for one document.

**Five families state their rules here**: records, arrays and sets (one class, a set being an array that
refuses a repeat), tuples, and maps. Choices wait for [TSON-JSON] §8.5 — `tson-compiler` states its dispatch
diagnostics parameterised over a "candidate noun" so one class serves a choice *and* a scoped position, and
aligning before the JSON side has the second position would be aligning against a shape about to change.

**Two boundary calls are finer than "the schema's nouns":**

- **"The absent sentinel" is the schema's noun and stays in the prose**; only the *spelling* is the format's.
  [TSON-DATA] §2.9 names the concept, and a message
  that will not say it loses the word the spec uses for the thing it is refusing.
- **A message about a value renders the value, not the wire form.** A set duplicate reads `'a'` and not
  `'"a"'`. That is harder on the JSON side than it sounds, because tree mode discards the host value by
  design — so `Nodes.rendered` answers from the node instead, which is the same question the TSON reader's
  `Rendered.value` answers from the host value.

**A type names itself by what the author wrote, in both encodings.** `EntryDisplayName` lives in
`schema.meta` — beside the model it renders, and depending on nothing else — so both stacks reach it. A
resolver-minted entry shows as the sugar or application that produced it (`[text]`, `box<text>`), told apart
from an authored one by having no source position. Kept in `tson-compiler`, it would leave a JSON diagnostic
naming the synthetic entry by its content-derived hash — `'array_text_4cc4a482'`, a name in neither the
author's schema nor the sender's document. The record reader carries the display name *beside* its own
name rather than instead of it, because a `$type` resolves against the entry while a message names the
author's spelling — one place the two genuinely differ.

**What is deliberately not shared** is any rule one encoding has and the other has not: JSON's reserved member
namespace (§3.2) and TSON's positional record form have no counterpart across the wire, so each stays with the
reader that owns it. A shared class that grew those would be a second switch responsible for rules it cannot
name — the same failure `TsonDiagnostics`/`JsonDiagnostics` were split to avoid.

## Atom refusals, `expected`, and `message`

**An atom refuses in two categories, and carries two codes.** [TSON-DATA] §5.2 splits the refusal — "a token
the atom's grammar rejects is a parse error; a parsed value violating the atom's range is a validation
error" — and §8.1 files the halves apart, a contract rejection being a *resolver* error ("the structural
parser has already accepted the document before an atom contract is consulted, so contract failures resolve,
they do not parse") where a range violation is a *validation* error. So `ATOM_FORM_INVALID` is what an
`AtomParseException` becomes and `ATOM_CONSTRAINT_VIOLATION` what an `AtomValidationException` does. One code
for both put a resolver error in the validation category, which is not something a consumer could correct
for: the two messages are equally "the atom said no", and the code is the only thing carrying which rule
fired.

**`AtomRefusal` is where that mapping lives, and it is the one place.** `AtomType`'s own signature is
untouched — it is shared with two schemaless binders that have no read context, and with the JSON stack,
none of which can be handed a `Diagnostic`. What `AtomRefusal` carries is the four non-locational components
(code, message, `expected`, `actual`); the reader adds the location, since only it has one. It lives in
`tson-atom` rather than on `Diagnostic` for the reason the rest of the classifying half sits with each
encoding: the `of*` factories each switch on an exception an *encoding* declares, and
`AtomTypeException` is the vocabulary's own and neither encoding's — so a factory for it on `Diagnostic`
would make the base depend on the vocabulary, while a copy per reader is how two encodings come to disagree
about one token — a target that cannot represent a family's value being a bind problem on one path and a
type mismatch on the other, with nothing saying which is right. Four readers go
through it — `AtomTypeReader`, `TypeRefCheck`, `DataClassObjectReader` and the JSON stack's
`JsonAtoms` — and `AtomTypeException` is sealed to exactly two subtypes, so the switch is exhaustive rather
than a guess. Per-field schema positions are a separate matter
(`design/reader-naming-and-schema-location.md`). (Message synthesis from code + params
is not a gap but a decision — see below.)

**A broken FIXED field is `FIELD_FIXED`, not an atom code.** `field: type = value` (§5.2) is a field-state
rule, so a value contradicting it has satisfied its atom's grammar and every facet — it is simply not the
one value permitted. `FIELD_FIXED` sits beside `FIELD_REQUIRED` for that reason: the two §5.2 field-state
rules a document can break, neither of them about the field's type. Both ways to break one report it
(`RecordAbstractReader.verifyFixed`): a stated value contradicting `= value`, and a pinned field written
`_`. The contradiction message also names the fix — `=` reads as "default" to anyone arriving from JSON
Schema, so `priority?: priority = medium` is a plausible mis-spelling of `~ medium`, and without the hint
the author discovers it only by watching every differing document get rejected.

**`expected` carries the constraint that failed, never the type's name.** `AtomTypeException` holds an
`expected` alongside its message, filled at each throw site from the facet that rejected the value, and all
three atom report sites (`AtomTypeReader`, `TypeRefCheck.violation`, `DataClassObjectReader.bindBuiltin`)
pass it straight through. Naming the type there — `a value satisfying quantity_t` against a message
reading `'99999' is greater than the maximum 100` — would make the structured half carry strictly *less* than
the prose, so a consumer wanting the bound would have to regex the sentence. That exception's own Javadoc fixes the
vocabulary at six shapes and no site invents a seventh:

| shape | example |
|---|---|
| an ordering bound | `<= 100`, `> 1`, `>= -128 and <= 127` |
| a membership | `one of (PENDING, SHIPPED, DELIVERED)` |
| a length | `exactly 4 characters`, `at most 10 bytes` |
| a pattern | `matching [A-Z]{3}` |
| a grammar (parse failures only) | `an RFC 3339 date-time`, `an integer or based-integer form` |
| a prohibition | `not NaN`, `a finite value` |

The declaring type name leads the *message* instead (`'my_percentage': '500' is greater than the maximum
100`) — it is what an author wrote and can act on, so giving up `expected` must not drop it from the
diagnostic entirely. `AtomTypeExceptionTest` pins all six shapes against the real parsers, because the
field's value is that it is one vocabulary across atoms, not a per-parser phrasing.

**`message` and the structured fields do different jobs, and neither is derived from the other.** The
structured half — `code`, `path`, `expected`, `actual`, the positions — carries the *facts*, and is what a
machine consumer acts on; it must be complete at every report site, including the facade-level ones
(`TsonObjectReader`'s `abandon`, which offers no overload that omits them, because
such an overload is how a diagnostic ends up with a blank structured half). `message` is for a person, and
is free to do what a template could not: cite the spec, or name the fix.

```
annotation '@since' is written bare, which §6 treats as '@since:_', but 'since' does not admit the absent sentinel
'contact' has no variant matching this untagged value -- expected a value of one of
    (email, phone), or an explicit type annotation
```

Neither of those is a restatement of `expected`/`actual`, and synthesizing them from `code` plus parameters
would make them worse. **So there is deliberately no message-synthesis layer here**, and one should not be
added: `code` does not determine the sentence (`TYPE_MISMATCH` alone covers a wrong shape, a wrong token, a
wrong cardinality, a bare annotation, an unmatched variant and a host-binding failure), and the sentences
differ because the situations do. The failure mode worth guarding is a site that forgets `expected` — which
the missing overload makes hard — not a site that writes a sentence a template wouldn't have.

## Base-syntax failures

**A base-syntax diagnostic states its position once, structurally.** `ParseException`, `LexException`
and `TsonUnsupportedDocumentException` keep the location in `position()` and out of `getMessage()`;
`toString()` appends it, so a stack trace still says where while the `Diagnostic` built from one carries
`dataPosition` as the single copy. Repeating it in the message would make every renderer print the location
twice, in two formats, the second without a byte offset. `ReadException.toString()` does the same from its own
diagnostic, which is what keeps a stack trace informative, a base-syntax failure reaching a fail-fast
caller through *it* rather than as the parse exception itself.

**A base-syntax failure goes to the receiver, like every other problem with the document.** Both facades'
whole-document entry points catch it and report `TsonDiagnostics.ofBaseSyntaxError(e)`, so a collecting read
never throws for a bad *document* — it hands back nothing (no tree, `null` bind) and the collector holds why.
Three reasons this is the receiver's business rather than the caller's:

- **The stream is lazy**, so a base-syntax failure surfaces *mid-read*, after any earlier value-level
  problem has already been reported. Throwing past the receiver would leave a caller holding a populated
  collector *and* an exception, with nothing saying the two belong to one document, and `Tson.validate`
  either discarding the collector or losing the syntax error.
- **It is the same shape the facade uses** for an unreachable `!!schema` (`readAgainstSchema`):
  report once, abandon the value. "Nothing can continue past a document that will not parse" is an argument
  for not continuing, not for not reporting.
- **A caller could not classify it themselves**: `LexException` is in the unexported `lexer` package, so
  a caller left to write the `catch` cannot name what it catches.

**Fail-fast throws one type.** `throwing()` throws at the first problem, and a
base-syntax failure arrives as `ReadException` (carrying the diagnostic, position included) rather
than as `ParseException`, which is what lets `Tson.validate`
be a plain call with no catch at all. Only a fault in *this library* still propagates as itself:
`ofBaseSyntaxError` rethrows anything that is not one of §8.1's three.
