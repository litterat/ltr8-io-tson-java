# Record dispatch: `RecordDispatch` and the discriminated-family readers

How a position typed by a record is read according to `record.extension` ([TSON-SCHEMA] §5.2), and the
`UNKNOWN_TYPE_REF`/`TYPE_MISMATCH` line the dispatchers share with the rest of the vocabulary. Current form only;
history lives in git.

**Invariants**

- The reader is decided once, when the schema compiles: `RecordDispatch.over` decorates the mode's record factory.
- ABSTRACT requires the `!name` tag and fails before the record's shape is consulted; SEALED reads the discriminator
  fields and looks the value up.
- Both dispatchers are `Subsumption.Applied`; without the marker `Subsumption.guard` puts a second dispatcher in front,
  which wins.
- Both compare a written tag against flattened names (`Subsumption.admitting`).
- A family's verdicts are `base.diagnostics`' `RecordExtensionDiagnostics`, §7.2's refusal is `SubsumptionDiagnostics`,
  and the refusal is located at the value, not at the tag.
- `UNKNOWN_TYPE_REF` means the written name denotes nothing; a name that resolves and is not admissible, or a required
  selector that is absent, is `TYPE_MISMATCH`.
- The pin table is keyed by `ValueIdentity`, and the selector scan is a `lookingAhead` that rewinds.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/scope-push.md`,
`design/name-hygiene-read-path.md`, `design/diagnostic-model.md`, `design/diagnostic-rules-and-messages.md`,
`design/processor-policy.md`, `design/schema-side-diagnostics.md`. §7.2's subsumption guard is
`design/class2-compilation.md`.

## A record position gets the reader its extension fact earns (`RecordDispatch`)

`record.extension` ([TSON-SCHEMA] §5.2) decides how a position typed by a record is read, and it is decided
**once, when the schema compiles** — `RecordDispatch.over` decorates whichever mode's record factory is in
play, so ABSTRACT and SEALED positions never reach it.

- **OPEN and FINAL** are the mode's own reader, unchanged. They read identically; the difference is which
  names a tag may carry, and a FINAL record's subtype set is empty by construction rather than by a check.
- **ABSTRACT** is `RecordTagDispatchReader`: the `!name` annotation is the only selector and is required, and
  the failure lands before the record's shape is consulted.
- **SEALED** is `RecordMemberDispatchReader`: the discriminator fields are read and the value looked up.

**One reader for both modes**, on `ChoiceReader`'s reasoning — dispatch reads the type-ref without consuming
it, so the member's own reader takes the whole data-value and does with it whatever that mode does everywhere
else. Neither dispatcher carries a field list, a group list or a default table, because neither decodes a
field.

**Named for the dispatch rather than the member**, because `*AbstractReader` is already this package's name
for the shared base of a kind's two mode readers, four classes deep; `*DispatchReader` is the established
name for a reader that resolves another and hands the value on (`NamedDispatchReader`).

**Both are `Subsumption.Applied`, and that is load-bearing.** `Subsumption.guard` wraps every Atom or Product
reader in a `VariantSchemaReader` so §7.2 reaches every position — and wrapping a dispatcher puts a second
dispatcher in front of it, which wins. A sealed position read through the guard takes `!cat` and hands the
value to `cat` before the members are consulted; the marker is what stops it. The family's rule is *stricter*
than §7.2 in any case: a sibling's tag is admissible under §7.2 and still wrong, the members having already
said which member this is.

**Both compare a written tag against a name and every alias whose chain ends at it**
(`Subsumption.admitting`), §7.2 comparing "after following both reference chains to their terminal entries":
the base's own set, which decides the `tagNamesTheBase` refusal, and each
member's, which decides selection. Skipping it is not a lost nicety but a family nothing can name — a family
whose base is a **template** has a minted entry for the base and for every member, and §8.2 makes a minted
name non-normative, so an alias is the only spelling either end has. The dispatchers apply it on the same
terms as the concrete record readers, or a subtype-template family would read in one encoding and be
refused in the other — the same schema, the same document, two answers. `CrossEncodingParityTest` carries both
ends of it.

**`NamedDispatchReader` is not reused**, close as the shape is. Its verdicts are a choice's, and
[TSON-JSON] §9.4 binds a family's to the ones the JSON stack gives — which is what `base.diagnostics`'
`RecordExtensionDiagnostics` holds, and what `CrossEncodingParityTest` compares.

**§7.2's own refusal is shared on the same terms** (`base.diagnostics`' `SubsumptionDiagnostics`), and it has
to be: two encodings giving one rule two codes — `UNKNOWN_TYPE_REF` against `TYPE_MISMATCH` — put it in two
of §8.1's categories, `resolver` against `validation`, for one verdict, and `CrossEncodingParityTest` carries
the parity case that keeps them from drifting. It sits beside `RecordDiagnostics` rather than
inside it because the rule governs every atom and product position — an array, a map and a tuple refuse a
wrong annotation on these same terms. **The refusal is located at the value, not at the tag**: TSON's
annotation has no pointer step of its own, and §9.4 wants one pointer for a rule they share, so JSON reports
at the value too.

**Which is one instance of a line that runs through the whole vocabulary.** `UNKNOWN_TYPE_REF` means the
written name *denotes nothing* — the schemaless reader's own check (`TypeRefCheck`) and an annotation naming
no type the governing schema declares (`AnnotationCapture`) — two classes, and the only ones that report it. Everything
where a name resolves and is merely not admissible is `TYPE_MISMATCH`: §7.2 subsumption, a choice's variant
membership, a union's member test, a type-ref naming a template. So is a position where a **required**
selector is absent, since no type is established either way — which is what the family readers already gave
`tagRequired`, and what makes "a required tag is missing" need no member of its own. The distinction is what
a consumer routes on: one says *correct the name*, the other says *this name means nothing here*. It also
decides §8.1's category, `validation` against `resolver`, so getting it wrong misfiles the verdict as well as
misnaming it. Two readers that each agree internally can still disagree with each other, which is why
the line is stated once, here.

**The pin table is derived at construction**, keyed by what the pins compare as (`ValueIdentity`), and both
sides go through one parser: a schema pinning `= "dog"` matches an unquoted `dog`, and `= 0xFF` matches `255`.
The scan for the selectors is a `lookingAhead` and rewinds, because a record's fields have no significant
order — the selector may arrive after the fields it selects.
