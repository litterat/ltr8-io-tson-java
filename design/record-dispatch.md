# Record dispatch: `RecordDispatch` and the discriminated-family readers

How a position typed by a record is read according to `record.extension` ([TSON-SCHEMA] §5.2), the
`UNKNOWN_TYPE_REF`/`TYPE_MISMATCH` line the dispatchers share with the rest of the vocabulary, and what a bare token
at a record position is: §5.6's positional form, or a value by name. Current form only; history lives in git.

**Invariants**

- The reader is decided once, when the schema compiles: `RecordDispatch.over` decorates the mode's record factory.
- ABSTRACT with no selector requires the `!name` tag and fails before the record's shape is consulted; a base
  naming selectors reads the discriminator
  fields and looks the value up.
- Both dispatchers are `Subsumption.Applied`; without the marker `Subsumption.guard` puts a second dispatcher in front,
  which wins.
- Both compare a written tag against flattened names (`Subsumption.admitting`).
- A family's verdicts are `base.diagnostics`' `RecordExtensionDiagnostics`, §7.2's refusal is `SubsumptionDiagnostics`,
  and the refusal is located at the value, not at the tag.
- `UNKNOWN_TYPE_REF` means the written name denotes nothing; a name that resolves and is not admissible, or a required
  selector that is absent, is `TYPE_MISMATCH`.
- The pin table is keyed by `ValueIdentity`, and the selector scan is a `lookingAhead` that rewinds.
- A record template IS-A a base is no member of it, even where it pins the selector: it has no values, and its
  applications are the members. Both member dispatchers skip it, so a pin only the template states selects nothing
  and the refusal offers only pins a document could write (`CrossEncodingParityTest`).
- In a data document a bare token fills a record's one positional field (§5.6) whatever its type. In a schema
  document (`TsonReadContext.readsSchemaDocument()`) it does so only where that field is atom-typed; an unquoted
  token is otherwise a value by name, read only by a `TypeRef` component at a record-typed slot.
  `ReferenceNameReader.nameIn` is the one statement of which tokens are names; their segment grammar is the linker's.

Related: `design/readers-and-diagnostics.md`, `design/reader-naming-and-schema-location.md`, `design/scope-push.md`,
`design/name-hygiene-read-path.md`, `design/diagnostic-model.md`, `design/diagnostic-rules-and-messages.md`,
`design/processor-policy.md`, `design/schema-side-diagnostics.md`. §7.2's subsumption guard is
`design/class2-compilation.md`.

## A record position gets the reader its extension fact earns (`RecordDispatch`)

`record.extension` ([TSON-SCHEMA] §5.2) decides how a position typed by a record is read, and it is decided
**once, when the schema compiles** — `RecordDispatch.over` decorates whichever mode's record factory is in
play, so no family-base position reaches it.

- **OPEN and FINAL** are the mode's own reader, unchanged. They read identically; the difference is which
  names a tag may carry, and a FINAL record's subtype set is empty by construction rather than by a check.
- **ABSTRACT** is `RecordTagDispatchReader`: the `!name` annotation is the only selector and is required, and
  the failure lands before the record's shape is consulted. A tag selecting a namespace type (resolved output's
  `myapi => !api { … }` read at `top`) reads the map that type reads as and delivers it as an instance of the
  constructor, a `Namespace` (`RecordTagDispatchReader.namespaces`, `NamespaceInstanceReader.instance`); a position
  typed by the namespace type itself reads the map, delivered as a `Namespace` only where its host component is one
  (`BindTargets`, `design/meta-layer-namespaces.md`).
- **ABSTRACT naming selectors** is `RecordMemberDispatchReader`: those fields are read and the value looked up.

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

## A bare token at a record position: positional, or a value by name

**In a schema document, §5.6's positional form keeps a bare token only where it would fill an atom-typed field**
(§5.6). The rule belongs to the read, not to the readers: a value inside a schema document — a
constructor's binding record, a namespace entry's values — reaches the governing meta's compiled readers as a replay from
`SchemaResolver.read`, whose context answers `readsSchemaDocument()` true (`TsonReadContext.schemaDocument`), and only
a schema has a namespace a name could resolve in. There a record whose one non-optional field is atom-typed takes a
bare value as that field — `type_ref`'s `name` is a `type_name`, which is how `request: search_request` at a
`type_ref` slot stays a type reference, and `record_field`'s `type` is `type_kind`, an atom whose value is a type's
name (`KindSlotReader`, which also admits the application a sugar lift writes there before the closer mints its
entry) — and where the field is a product or a sum, an unquoted token
(`ReferenceNameReader.nameIn`) is a **value by name** the record reader does not read as the field (`record_field`'s
own positional field being an atom, `fields: { p => x }` fills its `type`, as the sugar `p: x` does). Whether the token is
a well-formed qualified name — each `.`-separated segment a [TSON-DATA] §7.7 identifier — is the linker's to judge,
where the name is resolved (`ValuesByName.segments`).
**Every data document reads as it did before the rule**, tree mode and bind mode alike: `p: x` at a `record_field`
position fills `type`. Whether the positional field is atom-typed is read off the field's own compiled reader,
captured before a bind reader rebinds it (`RecordAbstractReader.bareTokenFillsPositionally`), looking through a
subsumption guard and a deferred reader; nothing about it is threaded into the record readers' constructors. JSON
has no positional form, so the rule has no JSON side.

**The slot declares the spelling, never the host.** A value by name stands at a reference-typed slot (`ref<T>`) and a
type's name at a kind-typed one; a record-typed slot holds the record's value inline, and a `TypeRef` or `Ref` host
there is a bind mismatch (`BindTargets`). Every reference has one host shape: a `type_ref` slot binds a `TypeRef`, so
does a kind-typed slot, and a reference-typed slot a `TypeRef` or a `Ref`, whose `ReferenceNameReader` (set in place of
the slot's atom reader by `BindTargets`) keeps the token as written in the `TypeRef`'s `name`; a host holding such a
slot as anything else (a `String`) receives what the atom reads, and the name is recorded all the same
(`RecordedNameReader`). At `type_ref`, whose bare token fills `name`, the component binds by the record reader. The
reader resolves nothing — the linker does (`design/linking-and-compilation.md`). At a reference-typed slot a quoted
token is text, and a framed (`@a x`, `!t x`) or braced value is refused as `expected the name of a 'method' value`,
whatever the host.
