# References: the forms a name takes, and the two facts a reference carries

Every position in a schema that holds a name: which are references, what a reference's store and judgement are, and
what an application is ([TSON-SCHEMA] §3.3, §4.1, §4.2, §5.3, §5.10, §5.12). Inside a parametric record a reference
may be a parameter (`ParamRef`, `design/parametric-records.md`).

**Invariants**

- **What `of` names decides what the slot holds, never the spelling** (`KindMembers.namesTypes`). Over a constructor
  (`meta.top` or IS-A it) the slot holds a type's name, judged a member (`KindMembers`) and delivered as a `TypeRef`;
  over any other type, a value's qualified name, judged by `ValuesByName` and delivered as a `ValueRef<T>`. The spelling is
  one; a body is a reference by its constructor's chain, never its name (`RefBody`). A reference over a constructor is
  a *kind* (§4.1).
- **Every name resolves from the root** (§3.3, §5.12): no lexical scope, so no shadowing rule; a bare `address`
  inside `geo` is the root's or unresolved.
- **The index is derived, never written.** `RefBody.index` is `@Unbound`, excluded from equality and `toString`, and a
  written `members:` is an unknown field. The linker re-derives the verdict from `of` over the final namespace
  whatever the read guessed: `TsonSchema.SlotType`'s `KIND`/`REFERENCE` role records only how the read read the name.
- **`of` is a name.** An application there is refused, to be declared and named (`TypeSlots`,
  `ParametricMaterialiser.nameRequired`); an unresolved name, a parametric record and a value entry are refused
  (`KindMembers.ofRefusal`). A reference is not a bound: `meta.param.bound` still refuses a constructor
  (`ParameterTypes.requireType`), a bound being IS-A, which no argument is of a constructor.
- **A reference is a token's text, whatever its form** ([TSON-DATA] §2.4, §5.12): `ValueRefSlotReader.nameIn` is the
  one predicate, and `RecordAbstractReader` asks it too so the readers cannot disagree. The text splits at `.` before
  any family is consulted (`ReferenceGrammar`), each further segment matched in the form of the family it enters
  (`ValuesByName`'s `Family`), so no `key_type` or `name_type` may admit U+002E (`NameType.dotRefusal`). Positions the
  schema grammar reads (a field's type, a parent, a bound, an argument) refuse a quoted token. *Lost:* a quoted token
  as text at a reference, which kept a second spelling rule and left a spaced key unreachable; and exact-spelling
  matching under a folding family, which makes `get` and `GET` one key to the document and two to a reference.
- **In a data document a reference is a name** (§4.2, [TSON-DATA] §7.2.5). `TypeRefParser` refuses an application
  (`ATOM_CONSTRAINT_VIOLATION`) even where the closure closes it; the index holds no application map and no alias map,
  so nothing in data resolves against a schema's applications. Which mode a read is in is the reader's, never the
  document's: a schema document and a resolved form read through `AtomParsers.inSchemaDocument` (`readsSchemaNames`).
- **An application is `meta.apply` written in place** (`ApplyBody`), the model no wider than the kernel types it. A
  wider one is a shape the kernel's reader cannot produce, and carrying it means two opinions of what an application
  holds. It stays in resolved output only where still open, and the linker refuses one anywhere else
  (`TsonSchemaLinker.validateBody`, `requireOpen`): that refusal is what holds a resolved form read as output to §5.10.
  Identity is structural (`ApplyBody.equals`), so `CanonicalApplication` and the applied-parent fold never compare text.
- **A constructor's application constructs** (`ConstructionSugar`, §5.3) and a parametric record's substitutes; a
  parametric record is never constructed, whatever its chain reaches. `ConstructionSugar` judges only arity and the
  argument grammar (`requireArity`); every other verdict is the construction's own.
- **A dependent field's type is no reference** (§5.2): no reference production, data or JSON, reads `kind!`.
- **A declaration read as data is judged by its record shape alone** (`ConstructorAsRecordTypeTest`): a field typed
  `spec: net.uuid_type` holds a body bound to `UuidType`, unresolved, so a derived field such as `chain` is admitted as
  written. A parametric record is built with `meta.record` and no kind holds it (`KindMembers.isMember`).
- **Two positions single out a constructor, and keep an IS-A `meta.top` check in Java**: a `!C` head and a composition
  operand. No reference covers "a constructor" alone: its value is a record, as a record type's is, and the series has
  no classifier of constructors. `meta.top` is a member of `meta.ref<meta.record>` by the one predicate;
  `TypeChain.isConstructor` answers applicability, a different question, and is not consulted for membership.
- **A recorded name carries its slot's declared type from the reader that read it** (`TsonReadContext.heldByName`,
  `TsonSchema.SlotType`), so the linker never walks the path back through the meta's declarations, and a slot only a
  tagged subtype declares is judged however deep the value sits.
- **Whether a name is judged is the slot's, never the host's.** A `String` host receives what the slot's atom reads
  through a `RecordedNameReader`, and the atom reader records the name either way, so `method: nobody.place` is refused
  under a `String` as under a `TypeRef`.
- **One host shape per slot**, tree and bind alike: `TypeRef` at a kind, `ValueRef` at a reference naming values, each
  the other's bind mismatch, and a `String` takes the name at either. `ValueRef` shares no parent with `TypeRef`, a
  value being no type.
- **A reference read in data finds what it names**: `TypeRef.referent()` through `RefBody.closure()`, a `ValueRef`
  through `RefBody.Values`, the governing schema's own value, never a copy read again. The closure is no part of
  identity; read in a schema document or built by hand it is `TypeRef.Closure.NONE`.
- **One walk judges a value's name wherever it is written**: a value body's at link, a field's default or pin at link
  (`checkFieldValue` through `ValuesByName.reach`), and a data document's at the read through the lookup the link left
  on the reference, so one rule decides what a name reaches.

Related: `design/linking-and-compilation.md` (the judgements), `design/parametric-records.md` (parameter positions),
`design/kinds-and-name-types.md` (membership, `name_type`), `design/namespace-types-and-value-entries.md` (the walk
into a value), `design/constructor-application.md`, `design/record-dispatch.md` (the positional form).

## Decisions

**Store and judgement, the store positional.** A reference is a typed pointer: the store maps a name to an address
`(namespace, key)`, the judgement names what the address must hold. Xtext, Smithy, LinkML, TypeSpec and Protobuf all
grew the same three constraints (token grammar, store, referent predicate). Two lessons taken:

- The predicate is named on a reusable type, never per slot. LinkML and Xtext repeat it at every slot; a per-slot
  annotation was this design's own `bound` mistake.
- The store is positional, not selected from the referent's kind as in a symbol-space system (XSD, LinkML, Xtext),
  which needs a closed metamodel; a meta-declared namespace has no kind to key on.

**A kind is a set of names, not a sum of types.** The sum reading (`meta.ref<net.uuid_type>` as a computed choice)
admitted an identifier token where a family's name was wanted, and the two are indistinguishable on the wire.

**IS-A and instance-of stay distinct.** IS-A is `supertypes`/`subtypes`; instance-of is the body's tag, its inverse
derived at link and never written. Folding instance-of into the chain would claim a value of `uuid` is a value of
`net.uuid_type`, whose values are types. The indexes (`supertypes`, `subtypes`, the `application` annotation) are
written out and never read as references on ingest.

**Deferred, not decided against:** a `scope` facet stating a reference's store (no kernel row wants one); a named
value of atom type (`max_items: default_limit`), its token ambiguous between literal and name.
