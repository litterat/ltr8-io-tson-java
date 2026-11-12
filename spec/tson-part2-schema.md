---
title: "TSON Part 2: Type System and Schema"
draft: "2026"
status: "Working Draft"
part: 2
description: >
  The centre of the TSON series: the schema grammar, the type system and its operations,
  the import chain and its resolution model, schema compilation and resolver output, the
  text encoding rules, and the operation of the import directive.
---

# TSON Part 2: Type System and Schema

## 2026 Revision 40

**Status:** Working revision. The 2026 revision series is subject to change without compatibility guarantees. When finalised, this specification will be published as **TSON version 1** and frozen; until then, revisions are released under the 2026 series.

**This revision:** a template is an open record. `meta.record` carries `parameters`, each a type standing at a field's type or as an argument of an application there or in a parent; an application of a constructor constructs it (`set<text>` is `!meta.set_type { element_type: text }`); a bound is written and never inherited; no template is a type a position names; a template's body is a record body; and in a data document a reference is a name, an application there being a validation error (§4.2, §5.3, §5.10). The kernel declares no template: `meta.template`, `meta.template_param`, `meta.record_template` and its field records, `meta.tuple1` and `meta.voidable_tuple1` are gone, `meta.param` is new, `meta.ref` is a refined constructor and the root's `set` an alias of `meta.set_type` (§9). A type slot holds a name or a type written in place: container sugar, a constructor applied at a use site and an application of an open record at a slot stay where they are written, held in a `meta.type_inline`, so nothing is lifted to an entry, no entry is named by its content, and `@meta.synthetic` is gone (§4.2, §5.3, §8.2).

**Series:** TSON Specification, Part 2 of 3

**Copyright:** © 2026 Litterat Pty Ltd. This document is licensed under the Creative Commons Attribution-ShareAlike 4.0 International License (CC BY-SA 4.0): https://creativecommons.org/licenses/by-sa/4.0/


## 1. Introduction

TSON is a schema system, and this document is its centre: a type system of immutable, hash-pinned schemas whose definitions are themselves data, resolving down a verified chain of imports — document → schema → the schemas it imports → the pre-loaded kernel — so that one hash authenticates a document together with its entire contract. The text format of [TSON-DATA] is this system's notation and its reference encoding: schemas are written in it, resolver output is serialized in it, and §7 states its encoding rules — the first encoding to carry the type system's values, with others defined against the same encoding-independent model.

This document defines the TSON **type system and schema layer**: the schema grammar and its type-definition forms, the type system and its operations, the import chain and its resolution model, the resolver output contract, the text encoding rules, and the schema-layer operation of the `import` directive.

[TSON-DATA] defines the lexer, the data grammar, base type resolution, and the built-in type vocabulary. This document introduces no lexical changes: a schema document is parsed by a second body grammar over the same lexer, selected by the root value's tag ([TSON-DATA] §2.2), and every operator it uses is a token that lexer already emits — the reserved special tokens of [TSON-DATA] §7.2.5 receive their meaning here. The schema grammar imports [TSON-DATA]'s value grammar at exactly two points — the constructor-application payload, a `core-value`, and the refinement body, its braced subset (§5.5, §5.6, §12.1), the second desugaring into the first; every other value position is deliberately narrower (a record-refinement body is a braced `record-def`, §5.7; a field-modifier value is a bare token, or the selector `=?` with none, §5.2) — and the coupling is one-directional: nothing in the data grammar depends on this document. One production runs the other way: a reference carrying an argument list is [TSON-DATA] §7.4's, and the schema grammar's type expressions reuse it (§12.1).


### 1.1 The TSON Specification Series

- **Part 1: Text Data Format** [TSON-DATA] — the notation and reference encoding: the lexer, the data grammar, base type resolution, and the built-in type vocabulary.
- **Part 2: Type System and Schema** (this document) — the centre of the series: the schema grammar, the type system, the import chain, and the operation of the `import` directive.
- **Part 3: JSON Encoding** [TSON-JSON] — the JSON encoding of the same model: the second encoding to carry the type system's values, with its own encoding rules stated against §7's shape, and the `TSON-Schema` transport field.

[TSON-DATA] §1.3 gives the series architecture: the import chain — the type system — on one axis, and the notation and encodings that carry it on the other.


### 1.2 Design Principles

In addition to the principles of [TSON-DATA] §1.2:

1. **Schema-value separation** — A data document has no definitions of its own: every tag (`!name`) in it resolves against the namespace its `!!import` directives bind (§3), and a schema document's declarations exist only for the documents that import it.

2. **Purpose-built representation** — A schema document shares the data format's lexicon — the lexer, its tokens, annotations, and directives — but has its own body grammar, selected by the root's `!schema` tag (§12.1). The semantics remain shared: a schema document resolves to an ordinary TSON value (a schema value, §2.1), and resolver output is an ordinary data document (§8).

3. **Permanent stability** — The schema grammar, the kernel, the bundled libraries, and the resolver output contract are frozen once published, on the same terms as [TSON-DATA] §1.2 principle 6. New types are added through new type libraries, not through changes to this document.


### 1.3 Conformance

[TSON-DATA] §1.5 defines the series' two conformance classes. **Class 1** (data-format processor) is defined there in full; its sole schema-layer obligation is to reject schema documents with a categorized diagnostic. This document defines **Class 2**.

A **Class 2 processor** (schema-aware processor) conforms to [TSON-DATA] and additionally implements the schema grammar (§4–§5, §12), the directive operation (§2.2), name resolution and the schema library (§3, §10), schema compilation and resolver output (§8), atom token parsing (§7.4), and validation. Such a processor:

- MUST pre-load the kernel (§3.4, §10.1) and SHOULD pre-load the bundled libraries;
- MUST resolve every name a document carries — a tag, a constructor head, a type position, a reference's `of`, a parameter's bound, an annotation — in the one namespace §3.3 defines for that document, and MUST NOT apply the [TSON-DATA] §5 built-in vocabulary or [TSON-DATA] §4 base type resolution in schema scope: under a schema every value is typed by its position or by its tag (§7.1, §7.3);
- MUST resolve each schema in the wait order of §3.4.1, consulting the order its declarations are written in for nothing, and MUST refuse a wait cycle as a resolver error;
- MUST produce, for every valid schema document, a resolved schema value — a value of the kernel's `meta.schema`, a map from each name to the value its entry resolves to (§8) — with every type's `chain` computed (§8.1). Serializing the resolved schema value as a data document is OPTIONAL; output, when produced, MUST conform to §8's serialization contract;
- MAY read a resolved form as data (§8.1, §10.1); an implementation that does MUST apply §8.1's derived-field treatment and §10.1's source rules;
- MUST resolve annotations (`@name`, `@name:value`) in the annotating document's namespace (§3.3.3) and validate each annotation's value against the named type's contract (§6); an annotation whose name does not resolve is a resolver error (§6), and annotations are preserved in resolver output (§8.1);
- MUST enforce the identity-agreement rule (§10.1), verify hash-pinned references per [TSON-DATA] §2.2.1 (§10.2), and refuse two pins of one identity in one closure (§2.2.3);
- MUST match every declared type name, parameter name, constructor head and each segment of every referenced name against the identifier grammar ([TSON-DATA] §7.7) and reject a failure as a parse error (§12.1); and MUST hold every declared field name and enum member to its record's or enum's name type, rejecting a failure as a resolver error (§5.2, §7.4);
- MUST implement the name-hygiene checks of [TSON-DATA] §8.2 over the schema-layer scopes (§11.4), each schema map among them, enforce them by default, and report a refusal as [TSON-DATA] §8.1's refused outcome — distinguishable from a verdict, in the same report; and MUST report a schema it could not obtain as the undetermined outcome, and one it will not obtain as a refusal (§10.1, §11.2);
- MUST enforce the schema-side resource limits of §11.5 on [TSON-DATA] §9.1's terms;
- MUST report errors in the categories and phrasings of [TSON-DATA] §8.1.

**Resolved-output consumers.** A consumer that reads only resolved schema values (§8, §10.1) — never schema source — is fully conforming with no support for parameters or substitution (§5.10). An open record appears in output as a `!meta.record` carrying `parameters`, its fields' types and its `chain.parents` holding the parameters as written (§8.1). No position, reference or `subtypes` index names an open record bare, so every entry a data document's type can reach is closed, and a consumer building readers or writers skips every record that carries `parameters`. A type slot holds a name or a type written in place (§4.2), and a consumer builds the reader of the second from the construction where it stands. Each parameter's `bound`, where it has one, says what an argument must IS-A (§5.10).


### 1.4 Terminology

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in RFC 2119.

- A **schema document** is the document kind whose root value is tagged `!schema` ([TSON-DATA] §2.2) and whose body is a schema map (§2.1, §12.1) — the source artifact that is authored, published, hash-pinned, and resolved.
- A **schema** is what a schema document defines: a named, immutable collection of declarations, identified by URL and referenced by the `!!import` directive.
- A **schema value** is the resolved form of a schema: a value of the kernel's `meta.schema` type, the map from `meta.type_name` to `meta.declared` (§4.1, §9) — each entry mapped to the value it resolves to — produced by resolution and optionally serialized as a data document (§8). Schema values are derived artifacts, never schema sources (§10.1).
- A **namespace**, of a document, is the set of names it can resolve: its own entries and every entry of its import closure (§3.3). `meta.namespace` is the kernel's constructor of the map types keyed by names (§4.1); which is meant is always clear from the spelling.
- A **kind** is, of an entry, ATOM, PRODUCT, SUM or ALIAS, derived from its value (§4.1, §8.1); of a reference over a constructor, the set of types it admits (§4.2): `meta.type_ref` names every type a value can have, `meta.identifier_ref` every identifier family, `meta.ref<meta.record>` every record type.
- A **reference** is a name held in a slot: a type's name, a value's qualified name, an application, a sibling reference, or, inside an open record, one of its parameters (§4.2, §5.10, §5.12). An entry whose value points to another entry is an **alias** (§8.3), not a reference.
- The **kernel** is the pre-loaded schema document `meta.tn` (§3.4, §9): the type system's vocabulary and the atoms it needs.
- The **schema grammar** is the body grammar of schema documents (§12.1); the **type-definition grammar** is its declaration right-hand side — the `type-def` production that each `name => type-def` declaration activates.

This document names a kernel entry by its path from the kernel's root, as a schema writes it (§3.3): `meta.record`, `unicode.text_type`, `numeric.count`. The kernel's root entries — `void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`, `iri_reference`, `regex`, `doc`, `enum`, `set` and `schema` — are written as they are declared. Where a sentence names a kernel constructor by its last segment alone — `record`, `array`, `map`, `choice`, `alias` — it abbreviates the path. A declaration written `meta.x => …` in prose stands for the member `x => …` of the map `meta`; no grammar declares a dotted name (§2.2.3).


### 1.5 Companion Artifacts

This document is published with eleven companion artifacts. The normative artifacts are pinned by content hash at publication (§13.2). Per §3.4, implementations pre-load the kernel as an in-memory structure; the artifact document is a description of that structure, and the in-memory model is authoritative.

| Artifact | Status | Content |
|----------|--------|---------|
| `meta.tn` | Normative | The kernel: the type system, pre-loaded, and the atoms it needs (§3.4, §9) |
| `numeric.tn` | Normative | The numeric library: the exact and approximate tiers and the sized integers (§9) |
| `temporal.tn` | Normative | The temporal library: the five RFC 3339 families (§9) |
| `net.tn` | Normative | The network and identifier library: the address, network, host-name, host, media-type, URI, UUID and email families (§9, [TSON-DATA] §5.5) |
| `core.tn` | Normative | The bundle: imports the three libraries, and declares the author annotations (§6, §9) |
| `policy.tn` | Normative | The processor policy's vocabulary: the identifier and token policies and the resource limits ([TSON-DATA] §8.2, §9.1; §11.5) |
| `meta-resolved.tn` | Non-normative | Resolver-output fixture for the kernel (§8) |
| `numeric-resolved.tn` | Non-normative | Resolver-output fixture for the numeric library (§8) |
| `temporal-resolved.tn` | Non-normative | Resolver-output fixture for the temporal library (§8) |
| `net-resolved.tn` | Non-normative | Resolver-output fixture for the network library (§8) |
| `core-resolved.tn` | Non-normative | Resolver-output fixture for core (§8) |


### 1.6 A Complete Example

A schema document declaring four types, importing `core.tn`:

```
!!id:"https://example.com/task.tn"
!!import:"https://tson.io/2026/40/m/core.tn"
@doc:"Task-tracking example schema."
!schema {
  priority   => !integer ^ { min: 1  max: 5 }
  title_text => !text ^ { min_length: 1 }
  status     => !enum [OPEN ACTIVE DONE]
  flagged    => <T> { entry: T  priority?: priority }
  task => {
    id:        uuid
    title:     title_text
    priority?: priority ~ 3
    status?:   status ~ OPEN
    due?:      date
    tags?:     [text]
    history?:  [flagged<status>]
  }
}
```

`priority` and `title_text` refine the kernel's `integer` and `text` instances (§5.5) — no bundled library declares a bounded refinement of either, a bound being a line the schema that wants it writes for itself (§9); `status` applies the `enum` constructor, the kernel's root entry, reached through the core import's closure (§3.3); `task` is a fresh record whose field types resolve in the same namespace — `uuid` and `date` from `net.tn` and `temporal.tn` through core, `priority`, `title_text` and `status` from the local declarations — and `id` and `title` must be written, `priority` and `status` may be omitted and are then injected, `due`, `tags` and `history` may be omitted and are then missing (§5.2). `flagged` is a **template** — an open record, a `meta.record` with one parameter, `T`, standing at `entry`'s type (§5.10). `flagged<status>` is its application, which closes where it is written to a record with no name whose `entry` is a `status` (§5.10); `history` wraps it in the plain array sugar, and the array is written in place too (§5.3). A data document imports the schema with `!!import` (§7.1) and instantiates its types:

```
!!import:"https://example.com/task.tn"
!task {
  id:       550e8400-e29b-41d4-a716-446655440000
  title:    "Ship revision 39"
  priority: 3
  status:   OPEN
  due:      2026-08-01
  tags:     [spec editorial]
  history:  [{ entry: OPEN }  { entry: ACTIVE  priority: 4 }]
}
```

Resolution derives a schema value from the schema document, optionally serialized as resolver output — a data document with the source's header, in which every entry is its value, desugared to the canonical `!C { bindings }` form with its `chain` computed and its record fields a map keyed by name (§5.6, §8): `priority => !numeric.integer_type { chain: { supertypes: [integer] }  min: 1  max: 5 }`, `task => !meta.record { fields: { id => !meta.record_field { type: uuid } … tags => !meta.record_field { type: { of: !meta.array { element_type: text } }  optional: true } … } }`, `flagged => !meta.record { parameters: [!meta.param { name: T }] … }`. The companion fixtures (§1.5) and [TSON-GUIDE] give a full worked resolver-output example.


## 2. Schema Documents

A schema document is the source artifact of the schema layer (§1.4). This section defines the document's structure and the operation of its header directives. How a data document's `!!import`s bind it is defined in §7.1.


### 2.1 Schema Document Structure

A schema document is a header followed by a braced map of declarations — the **schema map** — tagged `!schema`. The header carries the schema's identity (`!!id`, first line; optional in the grammar, required for publication and hash-pinning, §2.2.1) and its dependencies (`!!import`, repeatable, declaration order carrying nothing); annotations that bind to the schema sit after the header, immediately before the tag. Header order and cardinality are grammar productions, not conventions ([TSON-DATA] §2.2, §3.3; §12.1):

```
!!id:"https://example.com/people.tn"
!!import:"https://tson.io/2026/40/m/core.tn"
@doc:"Minimal example schema."
!schema {
  person => { name: text  age: integer }
  employee => person & { department: text }
  status => !enum [ACTIVE INACTIVE SUSPENDED]
}
```

Entries are separated like data-map entries — whitespace or a comma, and a comma may follow the last ([TSON-DATA] §2.4) — and the map MUST contain at least one entry (§12.1). Each declaration binds a type name to a type definition with the `=>` operator — a compound token the lexer already emits; the schema grammar introduces no reserved words — its operators are the reserved special tokens of the shared lexer ([TSON-DATA] §7.2.5).

**The root tag classifies the document and names the kernel's `schema`.** A document is a schema document exactly when its root value is tagged `!schema` ([TSON-DATA] §2.2): classification reads the header, the root's leading annotations and its tag, never the root's value, so the schema map's own grammar is never parsed to classify it. The tag names the pre-loaded kernel's root entry `schema`, an alias of `meta.schema` (§4.1, §9), *whatever the document imports or declares* — classification and the choice of the payload's grammar precede reading any import, and the pre-loaded kernel gives the one answer a lookup would. The tag is read as a spelling; a nested map may also be tagged `!meta.schema` (§4.1). A schema parser refuses an untagged root, and a Class 1 processor rejects a tagged one with a categorized diagnostic ([TSON-DATA] §1.5, §8.1).

**The body is a map, in syntax and in semantics.** The document resolves to a value of the kernel's `meta.schema` type — the map from `meta.type_name` to `meta.declared`, each entry mapped to the value it resolves to (§4.1, §8, §9) — and the braces carry the document's annotation anchor: annotations precede the value they bind to ([TSON-DATA] §3.1), and the schema map is the value the document-level annotations bind to. A schema map may be **nested** as an entry: `geo => !schema { address => { … } }` declares its members as entries of the document, each named by its path from the root, `geo.address` (§4.1, §5.12). Resolver output writes the root `!schema` and every nested map `!meta.schema` (§8.1).

**Annotation binding.** Annotations immediately before the `!schema` tag bind to the schema — the header directives carry no annotations ([TSON-DATA] §7.4 gives them no annotation slot). Inside the braces the data-map convention applies unchanged ([TSON-DATA] §3.1): an annotation immediately preceding a declaration's name binds to the key — the name itself (§6) — and annotations after `=>` and before the type-def bind to the type definition. Resolver output preserves the placement (§8).


### 2.2 Directives

[TSON-DATA] §3.3 defines the directive set: two names — `id` and `import` — each legal only at fixed positions in the header, with order and cardinality enforced by the grammar. The set is closed: any other directive name, or a legal name outside its position, is a parse error. There is no directive registry and no unknown-directive category.

This document defines the directive *operations* — what each directive means to the resolver. Every directive value is an IRI-reference ([TSON-DATA] §3.3); these are logical identifiers resolved through the schema library (§10), never fetch instructions.

| Operation | Directive | Placement ([TSON-DATA] §3.3) | Defined in |
|-----------|-----------|------------------------------|------------|
| Identity declaration | `!!id` | first header line, either kind; optional in the grammar — required for published schemas | §2.2.1 |
| Import | `!!import` | header, after the optional `!!id`; repeatable; either kind | §2.2.3 (schema documents), §7.1 (data documents) |


#### 2.2.1 The `!!id` Directive

`!!id` declares the authoritative identity of a document — the name other documents use to reference it, compared as a canonical identity ([TSON-DATA] §2.2.1). It connects the file's content to its logical name in the schema library (§10) and anchors content addressing: the hash input for a document's content hash is every byte after the id line ([TSON-DATA] §2.2.1; §10.2).

`!!id` is optional in the grammar and, when present, must be the first line. For schema documents it is required by policy, not grammar: **publishing a schema — registering it under its own name for reference by other documents, or pinning it by content hash — REQUIRES `!!id`.** An id-less schema is a development artifact; it may be registered under an application-supplied URL (§10.1) but has no published identity and cannot be hash-pinned.


#### 2.2.2 Retired: the governing relation

Revisions before 39 bound a schema document to a governing *meta-schema* with a `!!meta` directive, and the kernel to itself; constructors could be declared only in a document so governed, and a document's names resolved in two namespaces by position. The relation is gone: a schema document imports the schemas whose entries it names, the kernel among them, in the one namespace §3.3 defines, and any schema may declare a constructor (§4.2). `!!meta` is a parse error ([TSON-DATA] §3.3). This number is kept so that citations into §2.2.3 stand.


#### 2.2.3 The `!!import` Directive

`!!import` imports a schema's entries into the importing document. The directive value is an IRI-reference identifying a published schema document; every target MUST be a schema document — one whose root is tagged `!schema` — and a target that is not fails to parse as one, so the importer fails to load. The directive loads the referenced schema and makes its entries available as if they were declared in the importing document: imported entries are available to all local declarations (including for recursive references), and local declarations may refine, compose with, apply or instantiate imported entries.

**Imports are transitive.** An `!!import` contributes the imported schema's entire namespace — the entries it declares and the entries it imported. A document's namespace is therefore flat: one name denotes one entry throughout a resolution, and every name in the import closure is reachable by the data documents that import the schema, so the types §7.2's subsumption admits at a position are always nameable where the position is used.

Multiple `!!import` directives are permitted. Collisions are decided by **entry identity**, not by name occurrence. A declared entry's identity is its declaring document and its path from that document's root (§4.1, §8.2); a type written in place is no entry and takes no part (§8.2). One schema reached through more than one route contributes its entries once — re-arrival unifies, so the diamond (two imports that each import `meta.tn`) is ordinary rather than an error, and naming one schema twice, or under two spellings of one canonical identity ([TSON-DATA] §2.2.1), is redundant rather than a conflict. A `?sha256=` pin is verification metadata ([TSON-DATA] §2.2.1, §10.2), never part of identity: routes that disagree about pinning still name one schema, and a pin disagreeing with that schema's content is a verification failure, not a second identity; two *distinct* pins for one identity in one closure are a resolver error, since at most one matches the content. Order carries nothing: the collision rule leaves no name for an earlier import to win. It is a resolver error for two *different* schemas to declare the same name anywhere in the import closure, or for a local declaration to reuse a name the closure already binds — a schema map excepted, which is open (below), though a map's name collides with any entry of that name that is no schema map — no hiding and no redefinition: the schema fails to load, and the diagnostic names both declaring schemas.

**A nested schema map is open.** A schema map of one path declared by several documents of a closure (`geo => !schema { … }` in two imports) is one map, the union of their members: each document contributes the members it declares, and a member path two of them declare collides as any name does, by identity. The annotations written on a map's entry are its declaring document's. A document contributes by declaring the map itself; no grammar declares a dotted name. The kernel's own maps are open on the same terms: `numeric.tn` adds its members to the kernel's `numeric` and `net.tn` to the kernel's `net`, and an importer of either sees one map holding both documents' members (§9).

Two costs of the flat namespace are deliberate and worth knowing in advance. Every name in the closure is reserved — a schema importing `core.tn` reserves the kernel's thirteen root names, the libraries' and `core.tn`'s roots and the map names `meta`, `atom`, `numeric`, `unicode`, `net`, `binary` and `temporal`, with no aliasing to escape with; that is the price of a bare name meaning one thing everywhere, and it is why the bundled documents keep their roots to the vocabulary an author writes and their machinery under those maps (§9). And some conflicts are unfixable by the importer: if two imported schemas transitively reach different schemas that both declare `id`, the importing schema is rejected over names it never wrote and can only be repaired upstream. One consequence follows without a separate rule: schema identities carry the specification revision, so an import closure reaching two revisions of one library is rejected at namespace construction where the two declare a common name — the collision rule firing on genuinely different documents — rather than allowing two identically-spelled types to coexist and surface later as an inexplicable field conflict; nothing defines a revision for an arbitrary identity, so no rule refuses two revisions otherwise.

**Merged entries keep their home resolution.** An imported entry's internal references — alias targets, field types, the parents in its chain, its `name_type` — are names in its *defining* schema's namespace, fixed when that schema was resolved; the merge does not re-resolve them against the importer. Operationally, the library holds one resolved namespace per schema, and a consumer following an imported entry's references follows them in the defining schema's resolution (§3.4.1).

**Import references MUST be acyclic.** A schema's reference closure — every document reachable through `!!import` — is resolved dependencies-first (§3.4.1), and a cycle admits no such order: an `!!import` reaching, directly or transitively, a schema whose resolution is in progress is a resolver error — *import cycle* — reported with the cycle path. The prohibition costs nothing in practice: a hash-pinned cycle is unauthorable, since each document's pin would have to be computed over bytes that already contain the other's, so a cycle can arise only among unpinned development references. Mutual coupling has one sanctioned route: shared types belong in a third schema both partners import.

The imported schema MUST itself be a valid schema document, resolved independently.


## 3. Schemas and Namespaces

A data document never resolves tags against definitions of its own: it has none, and every tag (`!name`) in it resolves against the namespace its `!!import` directives bind. A schema document resolves every name it carries — the constructor heads it applies, the types it names at type positions, the sources it refines and the operands it composes, a reference's `of`, a parameter's bound, an annotation — in **one namespace**: its own entries and every entry of its import closure (§2.2.3). There is no second store consulted by grammar position, and no document on a rung above another: the kernel is a schema document like any other, reached by import, and pre-loaded only because its own declarations wait on each other (§3.4).


### 3.1 The Import Chain

Every TSON document that uses tags sits on a chain of imports. Each rung binds the same relation — *resolve my names against X* — with the one directive:

```
data document
  └─ !!import → schema
                  └─ !!import → library (numeric.tn, temporal.tn, net.tn)
                                  └─ !!import → kernel (meta.tn, pre-loaded)
```

A data document's `!!import`s name the schemas its tags resolve against. A schema's `!!import`s bring in the entries its declarations name — a library's, or the kernel's directly. The bundled libraries each import the kernel alone; `core.tn` imports the three libraries so that one import brings every bundled type (§9). The kernel imports nothing and resolves to a pre-loaded schema object (§3.4).


### 3.2 Schema-Value Separation

A published schema is an immutable artifact: once published at a URL with a content hash, its declarations do not change (§3.5). Because a data document's tags always resolve against imported schemas, `!text` in a given document means exactly what the imported closure declares it to mean — two documents importing the same schemas have identical type vocabularies. A document with no `!!import` directive has no type vocabulary: base type resolution and the built-in annotations of [TSON-DATA] apply there, and only there (§7.1).


### 3.3 One Namespace

The **namespace of a document** is its own entries and the entries of its import closure (§2.2.3) — nothing more, and one set. Every name a document carries resolves there, and the only lookup that precedes it is an open record's own parameter list (§3.3.2). A name resolves **from the root**: a one-segment name names an entry of the root namespace, and each further segment of a qualified name selects a member of the nested schema map reached so far (§5.12). Inside a nested map a sibling is written by its path, `geo.address`, and a bare `address` names the root's `address` or fails to resolve; there is no relative lookup, so there is no shadowing rule.


#### 3.3.1 Constructor Roles

Some grammar positions are **constructor roles**: the name there MUST resolve to a constructor or an ordinary type (§4.1), and nothing else is admitted.

- **Constructor-application heads** — the name after `!` when no `^` follows (`!enum [...]`, `!numeric.integer_type {}`, `!net.uuid_type {}`), resolved in the document's namespace: a miss is an unresolved-constructor error, and **the found entry MUST be `meta.top` or IS-A it, or an ordinary type** (§4.1) — a hit whose entry is any other type builds a *value* of that type (§4.1, §5.5), which is an entry no type position names — a braced payload at an atom instance, no value of it, being a resolver error naming the refinement form, `!name ^ { ... }`. IS-A stops at construction, so the predicate separates a constructor from a *part* of one: `meta.top`, `meta.type`, the base kinds, every constructor beneath them, `meta.namespace`, and the kernel's `meta.alias` are constructors by that measure and may be applied — `!meta.alias { target: T }` is the explicit spelling of the alias `T` (§8.3); `!schema { … }` and `!meta.schema { … }` are the application of a namespace type that declares (§2.1, §4.1); `meta.top`, `meta.type` and the base kinds are `abstract` (§4.1), so a direct application of one is refused as any abstract record's is, naming the subtypes that would satisfy it — while `meta.record_field`, `meta.param`, `meta.tuple_element`, `meta.field_group`, `numeric.integer_size`, `atom.specification` and `meta.type_chain` are records that are no constructor, and an application of one is a value (§4.1).
- **An alias of a constructor is applied as its target is**: the resolver's read of a declaration follows its head's alias chain and reads the value against the constructor at the end, and an application of the alias constructs that constructor (§5.3): `!my_enum [a b]` over `my_enum => enum` builds an enumeration, and `set<text>` over the root's `set => meta.set_type` builds a set.
- **The implicit desugar targets of the sugar forms** — `[T]` and the sized forms to `meta.array`, `[T, U]` to `meta.tuple`, `(A | B)` to `meta.choice`, `{K => V}` to `meta.map`, `{ … }` to `meta.record` (§5.3, §5.6) — are the kernel's entries by path, grammar-supplied and never author-written, and are targets whatever the document imports: a document importing nothing may write `{ a: b }` and apply nothing else.
- **A refinement source** — `I` in `!I ^ { values }` — resolves in the document's namespace and MUST be a *value whose type states refinement rules* (§5.5, §5.7): an atom-family instance (`integer`, carrying `!numeric.integer_type {}`), the kernel's reference instances, and nothing whose type states none.

Local and imported declarations participate alike at every role: a document may apply a constructor it declares itself, in the wait order of §3.4.1. An application head (`name<args>`) resolves in the same namespace to an open record or a constructor (§5.3, §5.10); an unresolved head is an unresolved-type error, and a head resolving to a *parameter* is a resolver error (§5.10).


#### 3.3.2 Lookup Order

A name used at a type position — a field type, a type argument, a choice variant, a composition target, a refinement source, a reference's `of`, a parameter's bound — and an application head resolve by walking, in order:

1. Parameters of the enclosing open record (§5.10).
2. The document's namespace: its own entries and its import closure, one flat set (§2.2.3), a qualified name walked from the root (§3.3, §5.12).

Name collisions across the import closure and with local entries are decided by entry identity (§2.2.3): one schema reached twice contributes once; two different schemas binding one name reject the load. A kernel name is reachable only where the closure holds the kernel: an import-free schema (`!schema { a => { b: b }  b => {} }`) is valid, sees its own names, and may declare `int64` or `schema` as entries of its own, though its root tag still names the pre-loaded kernel's (§2.1).


#### 3.3.3 Annotation Resolution

An annotation `@name` or `@name:value` resolves in the **annotating document's namespace** — for a data document, the closure its `!!import`s bind; for a schema document, its own entries and its import closure — resolved from the root as any name is (`@doc` the kernel's root entry, `@atom.ordering` a member of the kernel's `atom` map, `@title` core's root entry). An annotation declared locally in a schema document is usable in that schema document and in the data documents that import it alike: there is one namespace, and no rung for the one to be on that the other is not.

Where annotation types live follows: the kernel declares `doc`, `meta.annotation`, `meta.application`, `meta.disjoint` and the classifying `atom.ordering`, `atom.bounded`, `atom.exact` and `atom.numeric`; `core.tn` declares the author annotations `deprecated`, `title`, `comment`, `examples`, `read_only` and `write_only`; a schema declares any other it wants, under a name its closure leaves free (§6, §9).


#### 3.3.4 Data Documents and Namespaces

A data document's `!!import`s bind one namespace — the union of the imported schemas' closures, collisions decided as §2.2.3 decides them — and that namespace is the document's entire vocabulary. Tags and annotations resolve against it; nothing else is reachable. A reference the document writes is a name: an application is a schema's spelling and a validation error in data, so a generic type is reached through the name a declaration gives its application (§4.2). If a data document needs a kernel type like `text`, or `uuid` from `net.tn`, some schema it imports must have those in its closure.

A consequence: the kernel's vocabulary is visible from data exactly where it is imported. A data document importing a schema that imports the kernel may write `!meta.record { … }` — and resolver output is exactly such a document (§8), read under the kernel it was resolved against. A data document importing a schema whose closure lacks the kernel cannot.

**A family is open across schemas, and is judged over the closure that holds it.** A schema's namespace is its declarations plus its imports, so a record a schema imports may gain subtypes there: the `subtypes` index is computed over the namespace of the schema being resolved (§8.1), so an importing schema that composes onto an imported record declares a further subtype of it, a further member of its family where it is the base of one (§5.2). Nothing closes a family at its declaration. What a family *is* is therefore relative to a closure, and its checks run over every member the closure holds: two members brought together only by an import merge — each from a schema that linked cleanly — are judged where they meet, and a pair that collides is the **importing** schema's resolver error, reported with both origins named. Judging only the families with a member declared locally would let that schema load and dispatch on a mapping that is not a function.


#### 3.3.5 Retired: duplicate names across layers

Revision 38 admitted a type name declared in both the meta layer and a type library, the two being consulted at different grammar positions, and `core.tn` declared same-named copies of kernel entries. With one namespace (§3.3) there is no position at which two entries of one name are told apart: two same-named entries in one closure are §2.2.3's collision, and the bundled documents declare no name twice (§9). This number is kept so that citations stand.


### 3.4 The Kernel and the Resolution Order

The chain of imports grounds in the **kernel**, `meta.tn`: one schema document whose header is its `!!id` alone, which imports nothing, and whose declarations reference each other — `meta.record` is declared as a record. Its entries are pre-loaded into the schema library by the implementation: they exist as in-memory structures before any document is parsed, and the kernel is never resolved from a document. A document claiming the kernel's identity is checked against the bytes the implementation ships (§10.2), and the kernel a processor pre-loads MUST be the revision it was built for. When the kernel document is read, its tags resolve against those pre-loaded structures through the normal library lookup, and the document's description must match the implementation's in-memory model; if they disagree, the document is invalid — the in-memory model is authoritative.

The kernel's names reach a schema by import only: a schema whose closure holds the kernel writes `text`, `enum`, `meta.record` and `unicode.text_type` as any other imported names (§3.3), and an import-free schema sees none of them.

**What is built by hand is the kernel's one wait cycle.** Under the order below, a constructor whose declaration applies a type of its own document, and a type whose declaration reads a value of that constructor, wait on each other; the kernel holds exactly one such cycle, the entries around `meta.record`, `meta.map`, `meta.field_map`, `meta.record_field`, `enum`, `boolean`, `integer`, `text`, `numeric.count` and the records, sets and references their declarations read (§9). The hand-built set is that cycle; every other kernel entry is read by the rules below, and the kernel's resolved form MUST come out of them unchanged.


#### 3.4.1 Resolution Order

Schema resolution proceeds per schema as **Parse → Desugar → Pass 1 → Pass 2**, with imports fully resolved before any phase runs on the importing schema.

**Desugar** is purely syntactic and per-declaration: sugar forms rewrite to canonical constructor applications, a nested form written in place at the slot that holds it (§5.3), and nothing is lifted to an entry; a nested `!schema` map's members enter as entries named by path (§4.1).

**Pass 1 — Name population.** The resolver collects all declaration names in the schema document. The schema's namespace is populated with skeleton entries keyed by path. Bodies are not yet validated.

**Pass 2 — Body resolution and validation.** The resolver resolves each entry's body against the populated namespace, in the **wait order** below. It validates that references resolve, that composition and refinement rules hold, that every application supplies its head's arguments, each admitted by its parameter's bound or its slot's `of` (§5.3, §5.10), that every record's and enum's names are values of its name type (§5.2, §7.4), and that every name at a reference-typed slot is admitted by the reference (§4.2). It computes each type's transitive IS-A chain (`chain.supertypes`) and derives the inverse (`chain.subtypes`) (§8.1).

**The wait order.** Within a schema, the order in which entries are read is fixed by what each needs, and never by the order its declarations are written in. Terms: a **form** is a structure serialized data takes, read by an algorithm the processor owns — an atom (one token), a record or tuple, an array or map, a choice; a **constructor** links a name to the data a form needs to be read; a **declaration** is an entry's value, the data its constructor describes; an entry is **ready** when its declaration has been read, and a type can be read against exactly when it is ready. Seven rules, the first the rule and the rest its consequences:

1. **The rule.** Reading a value against a type `T` waits on `T` being ready, and on each type a part of the value is read against as it is reached: a field's type, an element's, a nested `!` tag's, an annotation's. An entry's declaration is read against its head (`C` in `!C { … }`, the source's constructor in `!I ^ { … }`) and waits as well on each refinement source and `&` operand, since it starts from their declarations. An alias is ready when its target is; a closed application when its substituted record is.
2. **A reference orders nothing.** A name at a slot typed by a reference (§4.2) — a field's type, an argument, a reference's `of` — is read by the atom's grammar alone; what it names is resolved and judged once every entry is ready (§4.2, §8.3). So `tree => { t: tree }`, mutual recursion, and a constructor whose facet is typed by its own instance (`numeric.decimal_type => meta.atom & { min?: number … }` beside `number => !numeric.decimal_type {}`) are sound.
3. **A held value orders nothing.** A field's default or pin and a dependent field's value (§5.2) hold what a schemaless read gives ([TSON-DATA] §4), and bind as written: a pin or default is not decoded through its type when the reader carrying it is built, but judged against its type once every entry is ready, still at load (rule 6). Where a declaration names the type a form reads against, a read reaches that type by rule 1, the name itself ordering nothing: a value of `meta.field_map` reaches `meta.record_field`, which `field_map` pins as its `value_type`.
4. **A cycle is refused.** Entries waiting on each other through rule 1 are a resolver error naming the entries on the cycle in wait order (`'a' waits on 'b' waits on 'a'`). A composition cycle (`a => b & {}`, `b => a & {}`), a constructor applying a constructor that applies it (`c1 => !c2 {}`, `c2 => !c1 {}`) and a type annotated with itself on its value (`note => @note:"x" text`) are each this rule. A loop of aliases is not a cycle under this rule; §8.3 refuses an alias chain with no terminal. The entries built by hand (§3.4) are ready before any declaration is read.
5. **Order independence.** Every order consistent with rule 1 gives one verdict and one resolved output, and the order entries are written in is not consulted. Forward references within a schema need no rule: a definition may reference any other declaration in the same schema, declared earlier or later.
6. **Everything closes at load.** Every entry is ready, every reference and held value judged and every reader built before the schema is used. No wait is left for a data read to discharge, and every type is known open or closed and usable in data once the schema has loaded. A judgement that walks a name to what it names — §5.8's one name type over a composition's supertypes, a reference's membership — waits for every entry to be ready, so walking it never makes an entry wait on one that waits on it.
7. **Limits.** The wait depth of an entry is none where it waits on nothing of its own document and one more than the deepest entry it waits on otherwise, an import being ready before the resolution starts; §11.5's wait-depth row bounds the longest chain in one document, default 64, a refusal and not a verdict. Closing an application needs no bound of its own: closing in place holds each open record of the closure at most once on its path, a repeat tying to the declaration being closed or refused (§5.10.1).

With `count => !integer ^ { min: 0 }`:

| declaration | waits | verdict |
|---|---|---|
| `tree => { t: tree }` | `meta.record`; `t`'s type is a reference | sound |
| `uint8 => !integer ^ { min: 0  max: 255 }` | `integer` (source); `min` and `max` read on `integer` | sound |
| `point => { x: int32  next?: point ~ { x: 1 } }` | the default is held | sound |
| `integer_type.multiple_of?: integer` | a step read on `integer` | sound: the kernel |
| `integer_type.min?: count` | `count`'s `min: 0` read on `count`, on `integer`, on `integer_type` | cycle: refused |
| `a => b & {}`, `b => a & {}` | `a` on `b` on `a` | cycle: refused |
| `c1 => !c2 {}`, `c2 => !c1 {}` | `c1` on `c2` on `c1` | cycle: refused |
| `a => b`, `b => a` | none | no cycle; refused by §8.3 |

**Imports run first.** Resolution is defined over the schema's **reference closure**: the set of schema documents reachable through `!!import` references, collected transitively. The order is:

1. **Collect the closure.** Starting from the schema under resolution, follow every `!!import` reference, deduplicating by canonical identity ([TSON-DATA] §2.2.1): each canonical identity names one schema, loaded once, however many references reach it.
2. **Verify per identity.** Verification attaches to canonical identities, not to individual references: for each identity in the closure, collect the set of digests declared across all references reaching it. An empty set resolves without verification. A single digest is verified against the identity's content per §10.2 — once, at this step, before any schema in the closure resolves — and every reference to the identity, pinned and plain alike, thereafter resolves to the verified instance; a verification failure is a resolver error for the identity across the whole resolution, and no reference falls back to the rejected content. Two or more distinct digests for one identity is a resolver error (§10.2) — at most one can describe the real bytes.
3. **Order the closure.** The reference relation over the deduplicated closure MUST be acyclic (§2.2.3); the resolver computes a topological order — dependencies first — and reports the cycle path as a resolver error where none exists.
4. **Resolve in order.** Each schema in the closure is resolved exactly once, in topological order, by its own passes; when a schema's turn arrives, every schema it references is already resolved. For each schema: merge each `!!import`'s entries into the accumulating namespace — merged entries stay bound to their defining schema's namespace (§2.2.3), and collisions between imports are resolver errors (§2.2.3); then Desugar rewrites sugar, then Pass 1 collects local entry names, a collision between a local name and an already-merged import being a resolver error; then Pass 2 resolves bodies in wait order.

The pre-loaded kernel (§3.4) enters the closure already resolved and occupies the leaf of every order. Collection follows references only into schemas not yet resolved: a reference to an already-resolved schema terminates there. Digest sets (step 2) are collected over the references observed during this collection: a terminating reference still contributes its digest to its target identity's set, while an already-resolved schema contributes no outgoing references — its dependencies were verified when it was resolved (or shipped, for the kernel, whose pin is checked against the implementation-held digest per §10.2). Whether a dependency happens to be pre-loaded therefore never changes whether a closure loads.


### 3.5 Schema Evolution

Each published schema version is immutable; a schema's identity is its exact byte content. Version N and version N+1 are independent artifacts with different content hashes and different URLs. TSON defines no version negotiation, migration rules, or compatibility checks; accepting data validated against multiple schema versions is a deployment concern. See §7.2 for the records-are-closed rule.


## 4. The Type System

The type system is defined by the kernel's vocabulary — `meta.top`, `meta.type` and the base kinds, the constructors, and the supporting records — and every construct of the type-definition grammar (§5) is notation over it: each declaration form resolves to its value, the canonical constructor form `!C { bindings }` (§5.6, §8), and a schema map is a map from each name to the value its entry resolves to.


### 4.1 Kinds

The kernel defines `meta.top` as the structural root, `meta.type` as the kind of the kinds that describe data values, three **base kinds** — `meta.atom`, `meta.product` and `meta.sum` — each composing with `meta.type`, one constructor that composes `meta.top` directly, and one refinement of `meta.map`:

```
top       => abstract {}
type      => abstract meta.top & { chain?: meta.type_chain }
atom      => abstract meta.type & {}
product   => abstract meta.type & { access_pattern: meta.product_access_type  size_type: meta.product_size_type }
sum       => abstract meta.type & {}
alias     => meta.top & { target: meta.type_ref }
namespace => meta.map ^ { key_type: meta.identifier_ref  voidable?: = false }
```

**An entry is a type exactly when its value is built by a constructor IS-A `meta.type`.** Every constructor of a data value's shape composes, directly or through another constructor, with one base kind, and each base kind IS-A `meta.type` IS-A `meta.top` — so every such constructor transitively IS-A `meta.type` and IS-A `meta.top`. One kernel constructor composes `meta.top` directly and not `meta.type`, so its instances are not types: `meta.alias`, whose instances are hops (§8.3). An open record (§5.10) is built by `meta.record` like any record; what keeps it from every position is that a reference names it only as the head of an application (§4.2). IS-A does not extend below construction: `!T {}` transfers kind, not supertypes (§5.5), so constructor instances (`integer`, `meta.declared`) and fresh records (`person`) carry no supertypes and are not IS-A `meta.top`. That boundary is what makes **IS-A `meta.top`, or being `meta.top`, the predicate for being a constructor**: an entry that is `meta.top` or IS-A it describes a type and may be applied with `!` unless it is abstract (§3.3.1, §4.2). **Any other entry's value is a value**, and the entry is a value entry: `name => !T { … }` with `T` a constructor IS-A `meta.type` builds a type, with `T` `meta.alias` an alias (§8.3), and with `T` an ordinary type — a record, a namespace type, any type of the closure — builds a value of `T`, read against `T` as a data value is and marked by nothing but its tag. `orders => !api { … }` and `create_order => !operation { … }` are value entries; a value entry is admitted by no reference over a constructor (`meta.type_ref` excludes it unmarked), nothing is typed by it, and a reference over `T` names it where its type IS-A `T` (§4.2, §5.12). A reference-typed slot in a value entry's payload holds one bare name, and an application there is refused, a declaration of the application named (§5.6).

**A constructor is an ordinary record type.** A constructor's declaration is a record — `net.uuid_type => meta.atom & atom.specification & { … }` is a value of `meta.record` whose instances are declarations — so at a slot that names a type nothing sets it apart: `meta.type_ref`, `meta.ref<meta.record>` and `meta.ref<meta.product>` admit `net.uuid_type`, and `meta.record`, `meta.top` and `meta.alias` with it (§4.2). A field typed by a constructor holds a declaration's body as data, judged by its record shape alone — it is not resolved, so a derived field such as `chain` is admitted as written and no coherence rule runs.

**`meta.top`, `meta.type` and the base kinds are `abstract`.** None has instances of its own, so a direct application of one — `!meta.top {}`, `!meta.atom {}` — is refused as any abstract record's is (§5.2, §12.1), the diagnostic naming the instantiable subtypes that would satisfy the position. Constructors beneath them stay OPEN: `!meta.record { … }` and `!numeric.integer_type {}` are direct instances, as is `!meta.alias { … }`.

**`meta.namespace` is a map constructor.** A namespace type is an instance of it, as a map type is one of `meta.map`: `meta.schema => !meta.namespace { key_type: meta.type_name  value_type: meta.declared }`, or a schema's own `api => !meta.namespace { key_type: meta.identifier  value_type: method }`. A value of one is the map its facets describe — every key a name of the identifier family `key_type` states, judged a member of `meta.identifier_ref` at link, no value void, `ordered` and the size bounds `meta.map`'s — and never a record of those facets. `key_type` is required: no host supplies a namespace's key family. A namespace type is a product like any map type, admitted by `meta.type_ref`, `meta.ref<meta.product>`, `meta.ref<meta.map>` and `meta.ref<meta.namespace>`; and a qualified name enters a value of a namespace type and of no other map (§5.12). The kernel's one is `meta.schema`.

**Every value of `schema` declares.** The rules for a schema document's root map apply to every value of `meta.schema`: `geo => !schema { address => { … } }` is a schema map nested as an entry, whose members are declarations that resolve, link and compile exactly as root entries do — types, constructors, values, aliases, templates — each named by its path from the document's root, `geo.address`. It declares at least one member and takes no parameters; its tag is `!schema` or `!meta.schema`, read as a spelling (§2.1). The map's own entry is a value of `meta.schema` keyed by its members' own names; a map of one path declared by several documents is the union of their members (§2.2.3). A value of any other namespace type (`!api { … }`) is a value map, whose members are values and declare nothing.

**A record's fields are a plain map.** `meta.field_map => !meta.map { key_type: text  value_type: meta.record_field  ordered: true }`: a field name is text within its own record, judged by the record's `name_type` as `discriminators` and a group's `members` are (§5.2), and the map is no namespace — no qualified name selects a field declaration (§5.12).

**A type carries its IS-A chain.** `meta.type`'s one field is `chain`, a `meta.type_chain`:

```
type_chain => {
  parents?:    [meta.type_ref]   -- the parents as written; an open record's, one of which may be an application
  supertypes?: [meta.type_ref]   -- the closed chain: every entry this one IS-A, complete, each a name without arguments
  subtypes?:   [meta.type_ref]   -- the inverse, over the namespace of the schema being resolved (§3.3.4)
}
```

`parents` is what was written and is the only list that ever holds an application: it is an open record's slot (§5.10), kept on the open record once its parents resolve — a closed instantiation keeps none — since `<T> result<T> & { … }` reaches an IS-A edge to `result<text>` and not to `result<int32>`. `supertypes` is the closed chain, complete to a fixed point through applied parents (§5.8), sorted, each a path without arguments, and empty for a fresh record, an instance and a subtracted record alike. `subtypes` is the inverse, over the entries of the schema being resolved. A declaration never writes `chain` — `!meta.record { chain: { … } }` in source is a resolver error, as writing `meta.choice.disjoint` is — and a constructor never declares a field of that name: a schema's `widget => meta.atom & { chain?: … }` is refused where it is declared, the name being `meta.type`'s. The resolver computes the chain from composition and refinement and the linker inverts it; on reading a resolved form it is read as stated and never recomputed, because a flat body and its chain cannot be recomputed from each other (§8.1, §10.1). Provenance is not recorded: which operator a refinement used, and what a subtracted record was subtracted from, are what was written, nothing reads them after resolution, and the source document has them.

What is universal is *kind* membership — every closed type or alias entry has exactly one kind, derived from where its value's constructor reaches the kernel's order:

- **Atom** — scalar types. An atom constructor composes with `meta.atom`; its record of constraint fields describes the narrowable vocabulary available to instances. The kernel and the libraries define the series' atom constructors (§9); `meta.void_type` is the atom with no constraint vocabulary, and `meta.reference_type` the atom whose values are names (§4.2). Atom instances are produced by constructor application — `!<ctor> {}` (empty) or `!<ctor> { values }` — and refined with `!<instance> ^ { values }` (§5.5).
- **Product** — structural types. `meta.record`, `meta.array`, `meta.set_type`, `meta.map`, `meta.namespace` and `meta.tuple` compose with `meta.product`, fixing `access_pattern` and `size_type`; the container constructors expose their type slots as required fields holding a name or a type written in place (§4.2). Bare `{...}` definitions without explicit composition resolve to PRODUCT by structural default, and so does an entry composing with `meta.type` or `meta.top` alone.
- **Sum** — types in which the value names its own type. `meta.choice` is the *closed* sum, enumerating its variants; `meta.declared_type` is the *open* sum, whose one instance `meta.declared` admits every type of the reading document's closure (§7.8).
- **Alias** — an entry whose value is a pointer to another entry: `!meta.alias { target: T }`, where `target` names an entry (§8.3). Aliases are aliasing relationships, not IS-A: an alias is a hop, stated as written in resolver output and collapsed only when a processor compiles for reading (§8.3). `meta.alias` composes with `meta.top` directly and is applicable (§3.3.1); its entries take the ALIAS kind from the alias form rather than from a base kind, and carry no chain.

**A kind is derived, never recorded.** An entry's kind is its value's type's kind — a fact the value's tag and chain already determine — and resolver output does not write it (§8.1): a consumer derives it by the rule stated there, reading `meta.atom`, `meta.product` or `meta.sum` off the chain by which entry of the *kernel* it reaches, each counted only as the kernel's own entry, so that a schema's own record named `product`, composed into `payload => product & { … }`, is PRODUCT by structural default — a value conforms to where it is defined. An open record (§5.10) is PRODUCT-kinded as every record is, and so is the record an application of one closes to. A value entry takes no kind of its own: it is a value, and the question a kind answers is asked of its type.


### 4.2 Type Construction

Type constructors are factories that produce types. **A constructor is `meta.top` or an entry that IS-A it** (§4.1): the root, or one that composes with `meta.type`, a base kind or another constructor, or refines one — the position in the IS-A chain is what makes it applicable with `!`, and nothing is asserted by an author beyond the composition itself. There is no marker and no `constructor` field: constructorness is inherited through composition, derivable from a type's `chain` wherever it is wanted (§8.1), and never a property a declaration states apart from its chain. **Any schema may declare a constructor** — a document whose closure holds the kernel composes `meta.atom`, `meta.product` or `meta.sum`, or refines or composes a constructor it imports, and the entry is a constructor by its chain; a document whose closure lacks the kernel has nothing to compose and declares none. There is no construction in data: `!C { bindings }` produces a new type only in the schema grammar (§7.2). The kernel's container constructors are parameterless: a type slot (`meta.array`'s `element_type`, `meta.map`'s `key_type` and `value_type`) is an ordinary required field, typed by the type-slot choice or, for a map's key, `meta.type_ref` (below), filled by the construction — or by the sugar's desugaring (§5.3) — the way data supplies any required field, so a construction's binding record is ordinary data of the constructor's vocabulary (§7.2). A constructor declares no parameters: `C<args>` over one is construction sugar over its required reference fields (§5.3), and only an open record carries parameters (§5.10).

**References: one atom whose values are names.** The kernel declares one atom whose values are names, and every slot that names a type or a value by name is typed by an instance of it, a type slot through the choice below:

```
reference_type => meta.atom & { of: meta.type_ref  allow_sibling?: boolean ~ true }
ref            => meta.reference_type ^ { allow_sibling?: = false }          -- a refined constructor: meta.ref<method> is !meta.ref { of: method }
field_type_ref => !meta.reference_type { of: meta.type }                     -- a record field's name: a type's, or a sibling reference
type_ref       => !meta.field_type_ref ^ { allow_sibling: false }            -- the names of the types a value can have
identifier_ref => !meta.type_ref ^ { of: unicode.identifier_type }           -- the names of the identifier families
```

- **The predicate.** A value of a reference whose `of` is `T` is a name, and conforms exactly when the entry it names, past the entry's alias hops (§8.3), holds a value whose type is `T` or IS-A it. Every entry is a name and a value, and the value has a type, its tag: `order => { … }` holds a value of `meta.record`, `int32 => !integer ^ { … }` a value of `numeric.integer_type`, `orders => !api { … }` a value of `api`. A field's type position, a `meta.ref<meta.record>` slot and a `meta.ref<method>` slot all admit a name by that one predicate, and differ only in `of`.
- **The slot's meaning comes from `of`, never from a spelling.** Where `of` is a constructor — `meta.top` or IS-A it — the values it admits are types, and the slot holds a *type's name*: judged a member over the reading document's closure, and taking part in structural identity (§8.2). Such a slot's admitted set is a **kind**: `meta.type_ref` is the kind of the types a value can have, `meta.identifier_ref` the kind of the identifier families, `meta.ref<net.uuid_type>` the types built with `uuid_type`. A record written in braces is built with `meta.record`, and so is `meta.top` itself and every constructor whose declaration is a record, so `meta.ref<meta.record>` holds all of them; an open record is built with `meta.record` too, and no reference names it bare (§5.10); a declared application of one is the entry that declares it (§5.10); an alias of a member is admitted beside it. Where `of` is any other type, the values it admits are *values*, and the slot holds a value's **qualified name** (§5.12): the first segment resolves in the writer's namespace and names an entry, and each further segment selects a key of a namespace value or a field of a record value; a map that is no namespace is not entered. So `meta.ref<bar>` over a record type names values of `bar`, `meta.ref<meta.record>` names types, and `meta.ref<api>` names `myapi`. A value is the name, never a value of the type it names: `meta.ref<net.uuid_type>` takes `stock_id` and refuses a UUID, and `meta.ref<order>` takes `o1` and refuses a copy of the order.
- **`of` names any type or constructor** of the writer's namespace, resolved from the root — `meta.ref<geo.address>` — and never a value entry, an open record, or a name that resolves to nothing; each is a resolver error at the declaration. `meta.ref<meta.top>` holds every type; `meta.ref<meta.alias>` holds nothing, no entry past its hops being an alias, and a required field typed by it is refused by §5.10.1's productivity rule. A refinement narrows `of`: the refined `of` is the source's or IS-A it, so every name the refinement admits the source admits too, and the refinement is IS-A its source (§5.7) — `meta.identifier_ref` is `meta.type_ref` narrowed to the identifier families, and a record refinement may narrow a `meta.type_ref` field to `meta.identifier_ref` by that edge (§5.8).
- **A value may be an application.** `geo.address<T>`, `pair<text integer>`: after the qualified name, an adjacent `<`, one or more arguments, and `>` — the production of [TSON-DATA] §7.4, written unquoted, a quoted token never being a reference. An argument is a reference, positional — of an open record's application, a name or a parameter (§5.3) — and the list supplies every one: against the head's `parameters` where the head is an open record, each judged by the parameter's `bound`, and against the head's argument slots where it is a constructor, each judged by the slot's `of`. The first substitutes and the second constructs (§5.3, §5.10). The canonical text has no whitespace but one space between arguments; identity is the parsed form (§8.2), so whitespace inside the list is free. A closed application at a type slot is a type written in place: a constructor's is its construction (§5.3), and an open record's the record it closes to, which has no name (§5.10). So a value carries arguments only where an application is still open — an open record's `parents`, its field types and the constructions written in them — and in the `meta.application` annotation (§8.1). **In a data document a reference is a name**: an application there is a validation error whether or not the schema's closure closes it, and the name to write is the one a declaration gives the application (`order_page => page<order>`).
- **A value may be a sibling reference.** `kind!` — one identifier with an adjacent `!` followed by whitespace ([TSON-DATA] §7.4) — names the field `kind` of the same record instance, whose value names the type (§5.2). `allow_sibling` says whether a reference admits one: a permission (§5.7), true by default, which a refinement may withdraw and never grant back. It is true at one type, `meta.field_type_ref`, the reference variant of `meta.field_type_expr`, which types `meta.record_field.type` and nothing else; `meta.type_ref`, `meta.identifier_ref` and `meta.ref<T>` set it false and refuse a sibling reference, as a validation error in data and a resolver error in a schema, and a reference over a type that is no constructor holds none whatever its facet says, a sibling naming a field and no value. Resolved output writes a sibling reference as written, and the facet where it is false.
- **Where a name is judged.** In a schema document the grammar is read where the name is written, and the name is judged when that schema links; the admitted set is the closure's, derived and never written — a resolved form states `of` alone, and an author writing a `members` list is refused as an unknown field. In a data document a type's name is judged by membership in the reading document's closure, and a value's qualified name by what it reaches there, §5.12's walk over the namespace the document's imports bind, the referent's type IS-A `of`; a name reaching nothing, a type, or a value of another type is a validation error. A host receives the referent with the name. A name in a schema document — a construction's or a value entry's payload, or a resolved form read as data — resolves in that schema, which a reading closure may not hold: its grammar is read, and its membership is judged where that schema links, or when the resolved form is read (§8.1). A field's default or pin at a reference naming values is a value's qualified name, judged at link by the same walk, a refusal being the declaring entry's resolver error (§5.2).
- **Every slot that names the type of a value holds a name or a type written in place** (below): `meta.record_field.type`, `meta.array.element_type`, `meta.map.value_type`, `meta.tuple_element.element_type` and each of `meta.choice.variants`. A record field's `type` admits a sibling reference too, the one slot that does. Every other slot naming a type is `meta.type_ref` and holds a name: `meta.map.key_type`, `meta.param.bound`, `meta.alias.target`, `meta.type_chain`'s three lists, `meta.reference_type.of` and `meta.application`'s value. Where only a name may stand an application closes nowhere: one written there — a map's key, a reference's `of` (`meta.ref<box<text>>`, `!meta.reference_type { of: box<text> }`), a parameter's bound, an alias's target — is a resolver error, and the form is declared and named. Only a slot typed by the type-slot choice closes an application in place (§5.10), and a composition operand absorbs one by value (§5.8). `meta.enum_type.name_type`, `meta.record.name_type` and `meta.namespace.key_type` are `meta.identifier_ref`. `meta.map.key_type` is not an atom kind: a map's keys may be records, tuples or arrays, each declared and named (§5.3). Inside an open record a field's `type`, a type slot of a construction written there, and an argument of an application there or in `chain.parents`, may be one of the record's parameters, and no other slot holds one (§5.10). An ordinary schema reaches `meta.ref<T>` through any closure holding the kernel, and types a field `meta.ref<net.uuid_type>` to hold a type's name there — `stock_id`, never a UUID — or `meta.ref<method>` to hold a method's qualified name. The value-level case an author may want — a slot taking a value of *any* type built with `uuid_type` — is a choice whose variant set is a kind, not a reference standing as a type, and the series does not yet define it.
- **A bound is not a kind.** `<T: text>` names a type and walks supertypes (`meta.param.bound`, §5.10); a reference over a constructor names a constructor and walks construction. Two mechanisms, and a bound refuses a constructor.

**A type slot holds a name or a type written in place.** The kernel types each slot that names the type of a value by a choice, disjoint by form:

```
type_inline     => { of: meta.type }                                         -- a type written where it is used
type_expr       => @meta.disjoint ( meta.type_ref | meta.type_inline )       -- a type slot
field_type_expr => @meta.disjoint ( meta.field_type_ref | meta.type_inline ) -- a record field's type
```

`tags: [text]` holds `type: { of: !meta.array { element_type: text } }`: a `meta.type_inline` whose `of` is the construction, tagged by its constructor — a refined constructor's own tag, `!meta.set_type` for `set<text>` and `!meta.ref` for `meta.ref<method>` — and a name inside it is a reference as at any slot. A name is string-class and a `meta.type_inline` brace-class (§5.4), so a slot's value is written untagged; `meta.type_inline` is no type, so `of` never holds another directly. What `of` holds is narrower than its type: a container, a tuple, a choice or a reference, a construction of a constructor refining one other than a namespace type, or the record an application of an open record closes to (§5.2, §5.10). A type written in place is **no entry**: it has no name, carries no `chain`, is IS-A nothing, is named by no tag, and is a member of no kind, a kind's members being entries. Its identity is structural (§8.2).

The `~` character has one use in the type-definition grammar: the **default-value modifier** in a field definition after a type expression (`port?: integer ~ 8080`, §5.2), beside `=` for a fixed value and `=?` for a selector the members pin (§5.2). Constructor refinement — a constructor refining another, as `meta.set_type` refines `meta.array` — is written with `^` like any refinement and follows §5.7's rules (§5.7).

**Constraint-vocabulary atom pairs.** Atom families whose instances can be narrowed with constraint values are defined as pairs: a constructor carrying the constraint vocabulary (`meta.atom & {...}` listing the family's narrowable fields) and a canonical empty instance (`!<constructor> {}`) that carries the constructor as its tag but establishes no IS-A. Refinements use the `^` operator — `age => !integer ^ { min: 0  max: 150 }` — which DOES establish IS-A with the refined instance (§5.5). Spec-bound constructors additionally compose with the kernel's `atom.specification` mixin and pin their `spec` field. §9 inventories the families; each constructor's facets holding a value of the constrained family are typed by that family's own instance, declared beside it (§5.5, §7.4).

**The atom with no constraint vocabulary.** One kernel atom has nothing to narrow, and it has a constructor of its own with an empty vocabulary — `meta.void_type => meta.atom & {}`. Every atom in the series is thereby a constructor/instance pair, and a processor recognises `void` as it recognises every other family: by the constructor the resolved body names, never by the entry's name.

- `void => !meta.void_type {}` — the type of a slot that holds no value: its sole value is the void sentinel `_`, and nothing else is admitted at a `void`-typed position (§7.3). `void` is the target type for bare annotations (§6). As a field type it means "no value", and the field's own marks say the rest (§5.2): `a?: void?` may be missing or written `_`, `a: void?` must be written and as `_`, `a?: void` may only be missing, and `a: void` admits no document at all and is refused (§5.10.1). It is not a valid choice variant (§5.4): whether a slot may be missing or void is a property of the position, not a type.

A schema that wants a void type of its own writes `!meta.void_type {}` and has the kernel's contract; there is no atom a schema can construct without a contract behind it. The escape hatch of earlier revisions, `value`, is gone: a field's default or pin is read against the field's own type (§5.2), and every facet is typed by its family (§7.4).

**`meta.identifier` is a text family.** The type of every naming position the grammar itself reads — type names and parameter names, through the roles `meta.type_name` and `meta.param_name` — is `meta.identifier => !unicode.identifier_type { continue_add: "-" }`: an instance of a constructor that composes `unicode.text_type` and states a UAX #31 identifier profile as data (§7.4). It is declared the way `iri` and `regex` are, so a rule stated on it reaches every naming position, it carries the text facets, and a schema may construct another instance to type names of its own. A record's field names and an enum's members are values of a **name type** — `meta.record.name_type` and `meta.enum_type.name_type`, each a `meta.identifier_ref` — which is `meta.field_name`, the kernel's identifier, unless a record or enum states another (§5.2, §7.4): `!unicode.identifier_type { start_add: "@$" }` for a vocabulary translated from JSON-LD or JSON Schema, `!unicode.identifier_type { medial: " " }` for words. No bundled library declares an `identifier` of its own; the name is a schema's to take.


### 4.3 Operations

TSON's type operations fall into two families. **Construction** operations compute a new value and declare their own contract: a bare record claims none, constructor application transfers kind only, composition grants IS-A per parent, and subtraction revokes IS-A for every parent, leaving no supertypes (§5.9 states why the break is total). **Refinement** inherits an existing value and tightens within it, under the rules its type states, always preserving IS-A.

**Both families consume values a type states rules for.** The source of a refinement MUST, after following its alias chain (§8.3), be a value whose type states refinement rules (§5.5, §5.7): a record (`meta.record` states the field rules of §5.7), an atom instance (its constructor states the facet rules of §5.5), the kernel's reference instances. Every operand of a composition or subtraction MUST be a definition whose body is a `!meta.record` — a shape with fields to merge. A value whose type states no rule for a field — a container instance, a choice, a namespace value — is *finished* and admits neither operator. The test is on the value and on nothing else: an open record's application (§5.10) closes to a `!meta.record` with fields and composes and refines like any record, however it was spelled, while `meta.array<text>` constructs a `!meta.array` and is refused for having none. A FINAL record (§5.2) has fields and is refused all the same, by a rule about the IS-A edge rather than the body: composition and refinement both mint one, and FINAL forbids it. §5.7 and §5.8 apply these rules to their own operand forms.

Each operation is defined with its grammar form in §5:

| Operation | Syntax | Section | IS-A | Adds fields | Removes fields | Tightens fields |
|---|---|---|---|---|---|---|
| Record construction | `{ ... }` | §5.2 | none | yes | n/a | n/a |
| Constructor application | `!C value` | §5.5 | none (kind transfers) | n/a | n/a | n/a |
| Composition | `A & B & { ... }` | §5.8 | preserved (each parent); refused on a FINAL parent | yes | no | yes |
| Subtraction | `head - { fields }` | §5.9 | broken (no supertypes); admissible on a FINAL head | via the `&` body | yes | via the `&` body |
| Refinement | `T ^ { ... }` | §5.7 | preserved (source); refused on a FINAL source | no | no | yes |
| Value refinement | `!I ^ { values }` | §5.5, §5.7 | preserved (source) | no | no | yes, under the type's rules |
| Instantiation | `!T value` in data | §7.2 | n/a (data) | n/a | n/a | n/a |

Instantiation is data-level and terminal. A refined definition remains a definition: it can be refined further or instantiated. An open record is instantiated only through an application, which binds every parameter (§5.10).


## 5. The Type Definition Grammar

Each declaration in a schema document binds a type name to a type definition: `name => type-def` (§2.1). Everything to the right of `=>` is parsed by the type-definition grammar; the complete ABNF is in §12.1. Each construct is defined by its syntax, its rules, and the canonical value it resolves to — a form's meaning is its resolution (§8).

Inside the type-definition grammar, type positions are determined by grammar context — the `!` prefix marks constructor application and value refinement (§5.5), not a reference to a type. Type expressions resolve against the document's namespace (§3.3.2).


### 5.1 Declarations

The right-hand side of a declaration takes one of the following forms; each resolves to the entry's value (§8) as defined in the sections below.

```
person => { name: text  age: integer }                    ; record construction (§5.2, §5.3)
employee => person & contact & { department: text }        ; supertype composition (§5.8)
account_public => account - { password }                   ; subtraction (§5.9)
production => config ^ { host: = "prod.example.com" }      ; record refinement (§5.7)
pet     => abstract { pet_type: text =?  name: text }     ; abstract base with a selector (§5.2)
dog     => pet & { pet_type: = "dog"  breed: text }       ; a member, pinning the selector (§5.2, §5.8)
status  => !enum [ACTIVE INACTIVE SUSPENDED]               ; constructor application (§5.5)
age     => !integer ^ { min: 0  max: 150 }                 ; atom refinement (§5.5)
set_type => meta.array ^ { ordered?: = false }             ; constructor refinement (§5.7), in the kernel
id      => uuid                                            ; alias (§8.3)
scores  => [integer; 1..]                                  ; array type (§5.3)
point   => [number, number]                                ; tuple type (§5.3)
contact_method => (email | phone | address)                ; choice type (§5.4)
translations   => {text => text}                           ; map type sugar (§5.3)
geo     => !schema { address => { city: text } }          ; a nested schema map (§4.1)
orders  => !api { place_order => { … } }                   ; a value entry (§4.1)
```


### 5.2 Field Definitions

A field in a record definition answers three independent questions, and the spelling has one slot for each:

```
name?: type? ~ default
name?: type? = fixed
name:  type  =?
```

1. **May the key be omitted?** `?` on the **name**. Unmarked, the key MUST be written; marked, it MAY be omitted. This is the input question and nothing else — what omission then *yields* is the modifier's business (below). Required by default is the right choice for a typed notation, and the one structured-output validation wants.
2. **May a written value be `_`?** `?` on the **type**. It means at a field exactly what it means at every other position that admits it — an array element, a map entry value, a tuple position (§5.3, §7.6): the void sentinel is admitted here. A field whose type carries `?` is **voidable**. The record field is the one position where an earlier revision's `?` also answered the omission question, and that overload was the source of four states no spelling could reach.
3. **What may a written value be?** The **modifier**. No modifier — the value is **free**; `~ token` — the value is a **default**, supplied when the key is omitted and overridable by a written value; `= token` — the value is **fixed**, and a written value MUST equal it; `=?` — the value is **pinned by the members** of a discriminated family, a selector, and is discussed below. Whitespace around `~`, `=` and `=?` is optional.

```
config => {
  host:     text                  ; key written, _ refused, any value
  port?:    integer ~ 8080        ; key may be omitted; omission yields 8080
  debug?:   boolean = false       ; key may be omitted; omission yields false; a written value must be false
  label?:   text                  ; key may be omitted; _ refused
  timeout:  duration?             ; key written; _ admitted — "no timeout" is a thing this document can say
  proxy?:   uri? ~ "http://gw"    ; omitted: the default; _: none; a value: the value
  version:  text = "2.0"          ; a marker the document must write, and must write as "2.0"
}
```

**What omission yields is derived, never stored.** A field whose name is unmarked MUST have its key written — a missing key there is a validation error. A field whose name is marked yields, where its key is missing, its value if it has one (`~ v` or `= v`, injected) and nothing otherwise: the field stays missing. So the three slots read as one table:

| Spelling | Key in the data | `_` in the data | Value in the data | A missing key yields |
|---|---|---|---|---|
| `a: T` | written | refused | any | — (an error) |
| `a?: T` | may be omitted | refused | any | nothing |
| `a: T?` | written | admitted | any | — (an error) |
| `a?: T?` | may be omitted | admitted | any | nothing |
| `a?: T ~ v` | may be omitted | refused | any | `v` |
| `a?: T? ~ v` | may be omitted | admitted | any | `v` |
| `a?: T = v` | may be omitted | refused | `v` only | `v` |
| `a: T = v` | written | refused | `v` only | — (an error) |
| `a?: void?` | may be omitted | admitted | none | nothing |
| `a: void?` | written | admitted | none | — (an error) |
| `a?: void` | may be omitted | refused | none | nothing |

The last three are the `void` type in the type slot rather than field states of their own: `void` admits nothing but `_` (§4.2, §7.3), so a `void`-typed field is one that may carry no value. `a?: void?` is a field that may be missing or written as `_` and nothing else — the spelling an earlier revision wrote `= _`. `a?: void` is a field that may be omitted and never written, which empties the field and leaves the record inhabitable; it is legal, since a declared field still participates in closure (§7.2) and a removal clause can name it (§5.9). `a: void` (key written, `_` refused, no value admitted) empties the *record* — no document satisfies it — and is a resolver error under §5.10.1's productivity rule.

**Four spellings are refused, as rules rather than omissions:**

- **A default on an unmarked name** — `a: T ~ v`, `a: T? ~ v`. An unmarked name says the key is always written, and `~ v` is a value only omission can reach; the diagnostic SHOULD suggest `a?: T ~ v`.
- **A pin on a voidable type** — `a: T? = v`, `a?: T? = v`. A pin names the only value the field admits, and `_` is not it: the `_` question is settled before the pin is consulted, and admitting the pair would let question 2 admit a value question 3 refuses. Nothing in any measured corpus asks for the cell.
- **A modifier on a `void`-typed field**, and the sentinel as a modifier value (`~ _`, `= _`). `void` has nothing to default or pin, and `_` is what the type slot spells.
- **The selector `=?` on anything but an unmarked name and a non-voidable type** (below).

**Which fields may carry a value.** A `~` or `=` value is admitted only on a field whose declared type is an **atom-family instance or an enum**, after following its alias chain (§8.3) — the types a single scalar token denotes directly, a reference among them, named or written in place (`meta.ref<method>`, §4.2), since a reference is an atom and a value is read against one written in place as against a declared one. On a field of any other declared type — a record, container, tuple, choice, `void` or `meta.declared`, named or written in place — a value modifier is a resolver error at the declaration, whatever token stands beside it. The positional form (§5.6) is a spelling rule for data values and does not make a record a token: admitting `p?: point ~ 3` would make a field's eligibility for a default depend on another declaration's field count, so that adding a second field to `point` silently invalidated a default written elsewhere. The refusal costs two spellings and buys a rule that fits in one line. Value modifiers are restricted to scalar tokens — quoted or unquoted — covering strings, numbers, booleans, enum members and names; complex modifier values (arrays, records, maps, applications) are not supported in v1 (§12.1).

**Value conformance.** A fixed or default value MUST conform to the field's declared type: the check is the field's own parsing contract (§7.4), and a value is accepted exactly when a read would accept the same token at that position. `{ first?: int32 ~ "nope" }` is refused at the declaration. A value is always a literal and never a parameter (§5.10). At a field typed by a reference naming values (`handler?: meta.ref<method> ~ orders.place_order`), the token is a qualified name, judged at link by §5.12's walk, and a refusal is the declaring entry's resolver error; at one typed by a reference over a constructor, it is a type's name, judged a member.

**Eager resolution.** Default and fixed value tokens are resolved and validated at schema-load time, in the wait order of §3.4.1: the token is held as written while the reader carrying it is built, and judged against the field's type once every entry is ready — still at load, never per validation. A default or fixed value that fails parsing or the type's constraints is a resolver error, reported at schema load.

**Injection.** When an optional field with a value — `a?: T ~ v` or `a?: T = v` — is omitted, the decoder injects the value into the output: decoded values are fully populated, and consumers do not consult the schema to retrieve defaults. **A pin on an unmarked name is never injected**: `a: T = v` is a marker a document states itself — a `"jsonrpc": "2.0"`, an `apiVersion`, a format `version` — and exists for a reader that does *not* hold the schema, which is the reader injection cannot serve; omitting it is the missing-field error. A pin whose only information is presence — present and pinned, missing and not injected — is spelled where §5.11 says that information earns its keep: a restated member of a field group, which the group governs and never injects. A written `_` at a field that is not voidable is a validation error whatever the modifier; at `a?: T ~ v` the fix is to omit the field, and omission remains the injection route (§7.6). At `a?: T? ~ v` — the one declaration where omission, `_` and a value are three different outcomes — a written `_` is void and a missing key is `v`, so `timeout: _` means *no timeout* and an omitted `timeout` means *the default*.

**Encoders.** Encoders SHOULD write values for defaulted fields — a document that states its defaults reads without its schema; omitting a field whose value equals its default is a wire-size optimisation an encoder MAY offer, lossless only because the decoder injects the value back on read. Resolver output is exempt from this SHOULD: it omits fields at their default values (§8.1). Two declarations bind an encoder that has the schema, because a field with no value has one spelling to choose and the right one is the declaration's: at `a: T?` a void value MUST be written as `_`, omission being the missing-field error; at `a?: T? ~ v` a void value MUST be written as `_`, omission decoding to the default. An encoder without the schema leaves out a field that has no value, which is right wherever the two spellings mean the same and is why those two declarations need the schema to be written at all ([TSON-DATA] §2.9).

In data, a FIXED field may be provided with a value matching the fixed value, or — where its name is marked — omitted. **A written value at a FIXED field MUST be verified against the fixed value**, as values (§5.5): a contradicting value is a validation error, never a value the decoder silently overwrites — an implementation that seeds fixed fields and skips the check produces decoded output that differs from the bytes with no diagnostic.

**Delivery is the mode's, and only admission is the field's.** The three slots decide what a document may say and what the schema supplies. What a reader then hands over is the mode's: a bound value has one null, so `a: T?`, `a?: T` and `a?: T?` deliver alike and differ only in what they refuse; a tree keeps which spelling arrived — a field written `_` is present with a void value, a field never written is missing ([TSON-DATA] §2.9) — in every encoding. Patch semantics (set, clear, leave unchanged) need the three outcomes a tree has and a bound value does not, and are a capability of the mode, not of any declaration.

**Positional form.** The single-required-field positional form of §5.6 counts fields whose **name is unmarked**, whatever their modifier: a voidable field must still be written, and so must a marker. A record has a positional form exactly when one field's name is unmarked.

**Dependent fields: a type chosen by a sibling.** A field's type may be a **sibling reference**, `kind!` — one identifier with an adjacent `!` followed by whitespace ([TSON-DATA] §7.4) — naming a field of the same record whose *value* names the field's type: each instance's dependent field is read against the type that instance's sibling names. The kernel's own `meta.record_field.value?: type!` is one — a default or pin is read against the type the field's own `type` names — and a schema writes the same spelling for a tagged payload, `setting => { kind: meta.type_ref  value?: kind! }`, or a code type chosen per row, `cell => { of: meta.ref<meta.atom>  v: of! }`.

- **The position rule.** A sibling reference stands only at a record field's type — `meta.record_field.type`, the slot typed `meta.field_type_expr`, whose reference variant `meta.field_type_ref` keeps `allow_sibling` at its default (§4.2). Anywhere else it is refused, naming the slot: in sugar where it is written (an alias target, a refinement source, a composition operand, an array's element type, a map's key or value type, a tuple element, a choice variant, an application's argument); in an explicit instance at the slot (`!meta.array { element_type: kind! }`, a resolver error naming `element_type`); inside a data argument list, as a parse error; and in data at a field typed `meta.type_ref`, where the facet refuses it as a validation error — the value is well formed and breaks the facet, as NaN breaks a `numeric.float_type` withdrawing `allow_nan`. A field whose name needs quoting cannot be a sibling, `"name"!` being no sibling reference.
- **The sibling's rules**, checked at the declaration against the flattened record, each a resolver error:
  - the sibling is a field of the record declared **before** the dependent field — the fields are a telescope, so no cycle can form; flattening puts inherited fields first (§5.8), so a base's sibling precedes a subtype's dependent field;
  - it is typed by a reference whose `of` is a constructor or `meta.top`, past its alias hops — `meta.type_ref`, `meta.field_type_ref`, a `meta.ref<C>` over a constructor, a refinement of one — or by the type-slot choice, `meta.type_expr` or `meta.field_type_expr`, whose names are such a reference's and whose other variant is a type written in place, against which the dependent field is read as against a named one (§4.2); `int32`, a reference naming values (`meta.ref<method>`) and a dependent field are refused. A value names no type, so a reference over values cannot type a dependent field, and **reaching a type through a value is deliberately absent**, in any spelling: `{ exchange: meta.ref<exchange>  body: exchange!.request }` would read `body` against a field of the exchange the value addresses, evaluating a path through a value at read time, so that a read would wait on value entries being resolved. The schema's vocabulary stays names and closed forms; a body tied to its exchange is written as an envelope template applied once per exchange, each declared application pinning the exchange where it is declared (§5.10);
  - **it is there to read whenever the dependent field is**: it is required, carries a default, is pinned, or is a member of a field group with the dependent field optional — a member of a group being declared optional, so a dependent field that is itself a member counts as optional. A sibling that is optional with no default, or voidable, is a resolver error, and so is a group member beside a required dependent field, and a refinement making such a field required; the rule is asked of the flattened record;
  - its pin or default names a type a value can be read against: an open record is refused, and so is a sibling reference; an application cannot be written as a default or pin (the modifier grammar admits a bare token), so only a name reaches this check;
  - the dependent field is never voidable and carries no default or pin;
  - it is restated only as itself: a refinement may make it required, and `v?: int32` over `v?: kind!` is refused, as is a typed field restated as a dependent one. Pinning the sibling is what fixes the type, and the materialised body keeps `type: kind!` beside the pin (§5.7);
  - a sibling may be a discriminator, the family rules asking of it what they ask of any required atom-typed field (§5.8).
- **Reading.** A dependent field is read against the type its sibling's value names, or holds written in place, in the same instance, through that type's reader. The type is known when the reader reaches the field in one of three ways: a **pinned** sibling's pin, wherever the sibling is written or whether it is; a **discriminator**'s, member dispatch selecting a member that pins it before any field is read; and a **free** sibling's value, written before the field, or its default where it is not yet written. A dependent field written before a free sibling with no default is a validation error at the field naming both, and so is a defaulted free sibling written after a field read against its default, found when the sibling arrives; no lookahead is added, which is why [TSON-DATA] §2.5 asks a free sibling to be written first. A free sibling that never arrives is no order broken, so the two are told apart once the record closes: a required one is the missing-field error and nothing more, and a field group's member, absent because another option is chosen or none, leaves a dependent field written without it a validation error at that field, saying the sibling is absent. A dependent field absent beside an absent sibling is checked for nothing. A sibling naming no type a value can be read against — a sibling reference, an open record — is a validation error at the sibling, and the value is not read; a sibling whose own read failed leaves the value unread with nothing more reported.
- **In a schema document** a dependent field holds its value as written, whatever its form — a token, a record, a list, a map, an annotated or tagged value — and in either order of its fields, so the kernel's `meta.record_field.value`, typed `type!`, waits on no entry (§3.4.1). The type a sibling names is one of the schema's own namespace, so a value entry's held value is judged when the schema links, read through the reader of that type — the sibling's pin, the value it holds, or its default — each refusal a resolver error naming the entry, the slot, the dependent field and its sibling: `d => !settings { s => { kind: int32  value: abc } }` (with `settings => !meta.namespace { key_type: meta.identifier  value_type: setting }`) is refused, and `value: { port: 8080 }` under `kind: server` is read by `server`'s reader. A dependent field written without its group-member sibling is refused in a schema document as in data. A resolved form read as data holds the value as written too, and judges it when the form is read (§8.1).
- **Values by name.** A qualified name's segment selecting a dependent field reaches a value of the type its sibling names in the same record value (§5.12).

**Field names: `name_type`.** A record's field names are values of its **name type**, the kernel's `meta.record.name_type?: meta.identifier_ref` — an identifier family (§7.4), and the same mechanism an enum's members are held to by `meta.enum_type.name_type`. The three rules are one on both constructors: the type is an identifier family, every declared name is a value of it, and no two are one value under its equality. The default is the series' own `meta.field_name`, the kernel's identifier, so nothing changes unless a record or a supertype says so; a schema translated from JSON-LD, OpenAPI or JSON Schema — whose objects are records with members such as `@context`, `@id` or `$ref` — names a relaxed type the translation declares for itself, and the series ships none:

```
json_name   => !unicode.identifier_type { start_add: "@$" }
json_record => !meta.record { name_type: json_name  extension: ABSTRACT  fields: {} }
node        => json_record & { "@id": text  label: text }
```

- **The brace form states a type by composition, and has no syntax of its own.** A relaxed name type is a translation's tool, not the go-to form for a TSON record, so there is no brace-form spelling for it: a translation declares its type once, on a fieldless abstract base, and its records compose it. The cost is an IS-A edge to the base, which asks nothing of a position typed by a member — `node` reads untagged. A declared field name is any single-line token (§12.1), quoted where it is no identifier, and is judged at link by the record's name type: a brace-form `{ "first name": text }` under the default is a resolver error, not a parse error.
- **Inheritance keeps one name type, as the constructor's rule.** A record's `name_type` is set where it is declared and a subtype cannot change it: a record with supertypes takes their one type when it states none; one that states a type beside supertypes MUST state theirs; and supertypes with different name types — `json_record & address`, where `address` says nothing and so is `meta.field_name`'s — do not compose and are a resolver error (§5.8). The rule is the constructor's and not `&`'s: the kernel's `name_type?: meta.identifier_ref` carries no `~ meta.field_name` default, since a default cannot depend on supertypes, so the explicit `!meta.record { … }` and a template's closed application are held to it exactly as `json_record & { … }` is. Taking the non-default type instead would re-judge `address`'s names by a type they were never declared under; the record to compose is one declared over the same type, `address => json_record & { city: text }`. A refinement keeps its source's, so the brace form can restate an inherited relaxed name (`tightened => node ^ { "$ref": text }`). Two name types are one by the entry they resolve to, not by spelling: `jn => json_name` is `json_name`, and the kernel's `meta.field_name` is its `meta.identifier`, so a record stating `meta.identifier` composes with one stating nothing. A name type a record states resolves in the author's own namespace, as an enum's does (§7.4), and resolved output writes `name_type` exactly where it was stated.
- **A name type's form implies NFC.** A reader matches a document's name against the declared ones in the type's `normalization` (§5.5), as an enum matches its members: under a case-folding name type `NAME` is the field `name`, the two spellings in one record are a duplicate field, and a family's selector is found however its name is cased. Under the default, NFC, nothing changes. So `NONE` is refused at load — two canonically equivalent spellings would be two names that render alike — and `ASCII_CASEFOLD`, which leaves text beyond ASCII as written, is admitted only for a type whose every character is ASCII; `NFKC_CASEFOLD` is the fold for any other (§7.4).
- **Hygiene follows the type, over a floor no profile lifts.** Every field name and enum member meets [TSON-DATA] §8.2's rules under its type's own profile, whose added characters are its own (`json_name`'s `@` and `$`, a `medial: " "` for words), and the collision relation over the scope (§11.4). A `unicode.identifier_type` MUST NOT add a Default_Ignorable_Code_Point, a control, or a space other than U+0020 (§7.4): without the floor a soft hyphen in `continue_add` makes two names no rule tells apart, and a profile derived from a hostile source's names would carry it in.
- **A schema's own references to its fields match exactly.** A group's members, a refinement's or removal's restated field, a sibling reference and a `discriminators` entry name a declared field by its declared spelling, under a folding name type too: the schema is one author's text, so a reference spelled differently is a mistake to report, and only a document's names, which another party writes, are matched in the type's form. `meta.record.discriminators` is therefore `[text; 1..]` and `meta.field_group`'s member slots are `text`, each judged by the record's name type (§5.11).
- **A document's field names are judged by the record that reads them.** A name the record does not declare is an unrecognised field (§7.2), at its own pointer, after the name type has judged it: one that is a value of the name type is a name, and meets the identifier policy ([TSON-DATA] §8.2) under the type's profile before it is reported as unrecognised, so a look-alike of a declared name is refused and never reported as an unknown field; one that is not can be no declared field, since every declared name is one, and is unrecognised without reaching the policy. Both encodings report an unrecognised field with the same code. Under the default the message is the plain one any undeclared name draws; under a declared name type it names the type and its reason, since those are the rules the author has to look up. A field that is no name meets no hygiene rule, and an unrecognised field's value is skipped unjudged — no type governs it, so the one problem is the field itself, reported once. A record nested in a relaxed one judges its own names, so the relaxation does not leak into a value it holds. A lookahead does not judge a name: a sealed family's discriminator scan crosses a member's names before the member is chosen, and only the member knows its type, so a name the scan crosses is judged when the member reads it. A document that imports a schema has its field names judged by that schema and only by it; a processor reading it without the schema holds them to no name rule ([TSON-DATA] §1.5, §2.5), and one that cannot obtain the schema leaves the document undetermined rather than judging it by the schemaless rule.
- **A name binds and writes as the record states it.** A host component binds a relaxed name through the name it states, as it binds any other, and a writer quotes a name that is no identifier, so the document reads back; an open record's field names are judged at its declaration, its `name_type` naming no parameter (§5.10).

**Definition marks: how a record may be realised.** A record states, by one of two words written between `=>` and its type definition — before the parameter list where there is one (§5.10) — which values it has and whether others may build on it. The fact is the kernel's `meta.record.extension`, over `meta.record_extension_type => !enum [ABSTRACT FINAL OPEN]`:

```
pet     => abstract { name: text  age: integer }
dog     => pet & { breed: text }
leaf    => final { id: uuid  payload: bytes }
```

- **OPEN** — the record has direct instances, and any schema in the closure may compose onto or refine it. Every record with no mark, and the default.
- **ABSTRACT** — no direct instances: no value's effective type is this record, and a position typed by it admits exactly its subtypes (§7.2). The tag is REQUIRED at such a position in every encoding unless the record names discriminators (below), and a tag naming the base itself is refused, since no value satisfies it.
- **FINAL** — direct instances, and no subtype: composition or refinement naming it as a source is a resolver error, in the declaring schema and in any schema that imports it. Subtraction stays admissible against a FINAL record: §5.9 empties the chain and mints no IS-A edge, and the IS-A set is the only thing FINAL constrains. An open record composing or refining a FINAL record is refused at its own declaration (§5.10).

Two marks on one declaration are ungrammatical — the slot holds one word — and a mark on a non-record definition is a resolver error. The member is **never inherited**: `dog` above is OPEN whether `pet` is ABSTRACT or OPEN, and must be, or no concrete subtype of an abstract base could exist. There is no transition table for it; the whole of the rule is that nothing may compose or refine onto a FINAL record, and every other declaration states its own member. Inhabitance gains no case: an ABSTRACT record with no subtype in its own closure is the ordinary shape of a library schema whose importers supply the members (§3.3.4 makes `subtypes` open across schemas), and refusing it at load would make the library unpublishable for having deferred exactly what it meant to defer. A document reaching such a position gets a validation error naming the remedy — no schema in this closure declares a subtype of the base, so the schema that does is missing from the imports. `extension` participates in a declared entry's meaning as any body field does, is omitted from output at its default (§8.1), and the two words are ordinary identifiers everywhere but this one slot ([TSON-DATA] §7.7): `abstract => { … }` declares a type of that name, and `f: abstract` references it; what cannot be written is a declaration whose whole body is the bare word, which is a declaration missing its definition. The mark is grammar rather than an annotation because it changes which values conform, which §6 says no annotation may do.

**The selector `=?`: a discriminated family.** A field written `=?` reads *pinned, but not here — the members pin it*: the record is the base of a family whose members each fix the field to a distinct value, and a value at a position typed by the base is placed by reading the field rather than by a tag.

```
pet => abstract { pet_type: text =?  name: text }
dog => pet & { pet_type: = "dog"  breed: text }
cat => pet & { pet_type: = "cat"  indoor: boolean }
```

The fact is the kernel's `meta.record.discriminators`, the base's own statement of which fields select its members, in declaration order; **whether the list is present is what says how a subtype is selected** — by reading those fields where it is, by the tag where it is not — and that reading is derived from the body where it is needed, in the manner of `meta.choice.disjoint` (§5.4), never a member of the extension enum. `=?` **implies ABSTRACT**: a record its members are selected from has no values of its own. Writing `abstract` beside a selector asserts what the body already says and is admitted, on `@meta.disjoint`'s terms; `final` beside one is refused, the members it selects being subtypes that could then never exist.

The base cannot pin the field itself: §5.7's identity rule forbids a FIXED restatement from changing the value, so were `pet` to write `pet_type: text = "pet"` no subtype could pin its own. The base therefore declares the field free and unpinned, each subtype tightens it to FIXED — the ordinary FREE → FIXED move — and there is no other arrangement. What the arrangement buys is that the selectors' declared types are the base's, uniform across the family by construction, so a decoder parses them with the one set of types it knows before dispatch, and the check list is short. **At the base:** the marked field's declared type resolves, after its alias chain, to an atom-family instance or an enum; its name is unmarked and its type non-voidable — `a?: T =?` is a selector that may be missing, selecting nothing, and `a: T? =?` one that may be `_`, likewise; it is not a member of a field group (a member reachable by refinement included, §5.11); it carries no value, `=?` taking none; it is no dependent field, though it may be a dependent field's sibling. **Over the family:** every entry in the base's `subtypes`, transitively, restates each marked field FIXED — `a: = v`, the marker that must be written, or `a?: = v`, the injected one — and the pins are **pairwise distinct as values** under the field type's own equality (§5.5): `= 255` and `= 0xFF` collide, `= 1` and `= 1.0` collide, and text pins compare in the field type's normalization form, so two that fold to one value collide. A base may mark several fields; the pins are then distinct **as tuples**, taken in the base's declaration order, and a combination no member pins is the ordinary unmatched-value error, per document. The family is judged over the closure that holds it (§3.3.4): an importing schema that adds a member which fails to pin, or whose pin collides with an imported one, is refused — in the importing schema, which is the schema that broke it — and so is one that declares no member and merely imports two schemas whose members collide. A family discriminates **one level**: a subtype of a member inherits the member's pin and §5.7 forbids changing it, so it dispatches to its parent and relies on §7.2 and the encoding's tag below that; an intermediate type meaning to dispatch its own members declares a selector of its own. A selector written on an open record is each application's (§5.10).

**A family is open across schemas.** §3.3.4 makes `subtypes` open, so an importing schema may declare a further member under the pinning obligation above, and the family is judged over the closure that holds it: two members an import merge brings together are the importing schema's error (§3.3.4). A host language's closed-set construct generated from a family — a sealed interface over records, a discriminated union — is relative to one closure and not a promise the schema makes. The word "discriminator" names the marked field in every diagnostic and index, since a symbol is not searchable; what it names is written `=?`.

**A family member is declared; a record written in place is a member of nothing.** An application of an open record at a type slot closes in place to a record with no name (§5.10), and one whose chain would name a declared type is a resolver error at the declaration that wrote the application: over `dog_of => <T> pet & { pet_type?: = "dog"  breed: T }`, a field written `k: dog_of<text>` would put in place a member of `pet` that no declaration names, and so would an application whose applied parent is declared. The diagnostic is located at the declaration that wrote the application — or, where the application is held in an open record, at the declaration whose application closed it, `kt` for `kt => kennel_of<text>` over `kennel_of => <T> { d: dog_of<T> }` — and SHOULD name the fix: `dogs => dog_of<text>`, then `k: dogs`. A member changes what every position typed by its base does, in every schema that imports it, and is read, tagged and bound by name, so a member with no name could be selected and then neither reported, tagged nor mapped. The rule is drawn at membership rather than at member dispatch — a nameless member of a tag-dispatched family is as unreachable as one a selector picks, an untagged value at its base needing a tag it cannot carry — and it leaves every other application at a slot alone: one composing no declared type closes in place (§5.10).

**Resolution.** A record definition resolves to `!meta.record { fields: { … } }` (PRODUCT-kinded by derivation, §8.1), its `fields` a `meta.field_map` — an ordered map from each field's name to its `meta.record_field`, in declaration order, which is part of the value (§4.1). A field's name is written once, by its spelling, as the key, and the name carries no slot of its own in the `meta.record_field`: `{ type  optional?  voidable?  role?  value? }` is the field's type and the four facts a reader consults (§8.1): `type` is a `meta.field_type_expr` value — the bare name as written (an alias is a hop, §8.3), a sibling reference, or a type written in place (§4.2); `optional` is the name's `?`, `voidable` the type's, `role` one of `FREE`, `DEFAULT`, `FIXED` for the modifier, and `value` the eagerly-resolved default or fixed value, typed `type!` — read against the type the field's own `type` names — and present exactly when `role` is not `FREE`. The defaults — `false`, `false`, `FREE` — are omitted in output. A duplicate is refused by the layer that can see it: a spelling written twice is the map's duplicate key ([TSON-DATA] §2.6), refused where the map is read and before any name type is consulted; two spellings that are one value of the record's name type are the linker's resolver error. A definition mark lowers into the body's `extension` (omitted at `OPEN`); the selector fields lower into `discriminators`, the marked fields themselves staying FREE, unmarked and unpinned; a stated name type lowers into `name_type`, written exactly where it was stated. An empty record `{}` is the zero-field case, `!meta.record { fields: {} }` — the shape of the kernel's `meta.top`, abstract.

**Type-name resolution.** Names used at type expression positions (field positions, arguments, choice variants, composition targets, refinement sources) and application heads resolve against the document's namespace per §3.3.2; constructor-application heads and the desugar targets of the sugar forms resolve per §3.3.1. Bare names always refer to entries — there is no field-name shadowing of type names.

**What may be written in place.** A type slot holds a name or a type written in place (§4.2), and what may be written in place is a container, a tuple, a choice or a reference, or a construction of a constructor refining one (`set<text>`, `meta.ref<method>`, a schema's own `nonempty<uuid>`) — the structural types, a value at the slot that uses them. A record, an enum, every other atom and a namespace type are nominal, a namespace value carrying its type's name as its tag, which a type written in place has none of: value refinements (`!number ^ { min: -273.15 max: 10000 }`), atom and enum constructions (`!enum [A B]`), namespace types (`api => !meta.namespace { key_type: meta.identifier  value_type: method }`, never `f: meta.namespace<meta.identifier method>`) and bare records (`{ name: text }`) MUST be introduced via named declarations and referenced by name; they MAY NOT appear inline in field-type, field-group-member, tuple-element, array-element, map-value, choice-variant, type-argument, or composition positions. The one record in place is the one an application of an open record closes to (§5.10). The kernel types `meta.type_inline.of` by `meta.type`, so this line is the resolver's rule, a resolver error at the declaration that wrote the form. The container sugar forms carry no positional restriction (§5.3): a size specifier or an element/position `?` is legal wherever the bracket or map form is, a map's key excepted. Implementations MAY enforce a configurable nesting-depth limit on inline forms as a resource bound in the manner of [TSON-DATA] §9.1; where such a limit is enabled, exceeding it is an error.


### 5.3 Type Expressions

The type-definition grammar has **one form per container, legal at every type expression position** — field types, group members, tuple elements, array elements, choice variants, type arguments, and declaration bodies alike. There is one bracket form and one map form, each admitting a size specifier after `;` and an element/position `?` wherever the form appears; nesting is the recursion already present in the element position (§12.1). Whether a form sits as a declaration's own body or inline at a use site changes where its construction stands — as the entry's value, or in place at the slot that holds it (below) — never what may be written.

```
config => {
  tags:     [text]                 ; plain array
  meta:     {text => integer}      ; map sugar
  point:    [number, number]       ; tuple, no position voidable
  contact:  (email | phone)        ; choice
  index:    {text => [order]}      ; nested forms
  aliases?: [text]                 ; the ? on the name is FIELD omission (§5.2)
  sparse2:  [text]?                ; the ? on the type admits _ at the field (§5.2)
  scores:   [integer; 1..]         ; size specifier, legal at a field
  sparse:   [text, text?]          ; voidable tuple position, legal at a field
  batch:    order_batch            ; a named declaration remains good style
}

order_batch => [order; 1..100]     ; bounded range as a declaration body
matrix9     => [number; 9]         ; exact size
opt_items   => [text?]             ; voidable elements
```

**Array types** use `[type]` with an optional size specifier after `;`. The size specifier is a grammar production over the range token ([TSON-DATA] §7.2.4): a bound, optionally followed by `..` and an optional upper bound, or `..` followed by a bound (§12.1). Each bound is an unquoted token whose text MUST match the `decimal-natural` production of [TSON-DATA] §7.6 — a non-negative decimal integer without leading zeros; a bound naming a parameter is a resolver error, a parameter being a type and never a count (§5.10). Five forms result: `N` (exactly N elements), `N..M` (bounded range), `N..` (at least N), `..M` (at most M), and absent (unconstrained). `[T; N..N]` and `[T; N]` are two spellings of the same binding and one type (§8.2); the `N` form is RECOMMENDED. A lower bound of `0` with no upper bound (`0..`) is a resolver error — every array satisfies it, and because identity is structural (§8.2) the spelling would be a type distinct from `[T]` that means the same thing; the diagnostic SHOULD say that the unconstrained array is written `[T]`.

**Bound coherence.** A size specifier desugars to the `min_items`/`max_items` binding pair (the table below), and one rule governs the pair wherever it arises, arrays and maps identically: when both bounds are present, `min_items` MUST be less than or equal to `max_items`, a resolver error at schema load. **A count facet admits any value of `numeric.count`** — 0 to 2⁶³−1 (§9) — and a bound past what a processor's values can reach is no schema error: a `max_*` past the most a host's collections or strings hold is never reached and admits every value, and a `min_*` past it is met by no value, so every value fails validation. A count outside the type — `max_length: 9223372036854775808` — is a resolver error at the declaration, as any value outside its field's type is.

The element position accepts an optional `?` suffix, producing `voidable: true` on the resolved `meta.array`. Under `[T?]`, an element at any position MAY be the void sentinel `_` (§7.6); void elements occupy positional slots — `[a _ c]` has three elements and satisfies a `[T?; 3]` size constraint. Without the suffix `voidable` is `false`, and a void element is a validation error when a schema is in scope. An element is never *missing*: a container's part is a value or it is void, so the container constructors carry `voidable` and no `optional` (§5.2, §8.1).

**Order is a facet.** `meta.array` carries `ordered`, `true` unless a body says otherwise, and `meta.map` carries it too, `false` unless a body says otherwise (below). The facet says whether two values differing only in order are one value. It never changes what a document may write, and output keeps the order written either way (§7.5).

**Sets.** The kernel's `meta.set_type` constructor refines `meta.array` and pins three facets: `ordered: = false`, `unique_items: = true`, and `voidable: = false` — a void member has no meaning in an unordered collection of unique members. Its bounds are `meta.array`'s: the empty set is a set, and a set that must be non-empty says `min_items: 1`. Sets have no bracket sugar of their own; the spelling an author writes is `set<T>`, over the kernel's root entry `set => meta.set_type`, an alias of the constructor (§9): `set<text>` is construction sugar for `!meta.set_type { element_type: text }` (below), admitted at a field position where a `!` construction is not (§5.2). An application sets no bound, so a *bounded* set at a field is a named entry — `tag_set => !meta.set_type { element_type: text  min_items: 1 }`, then `tags: tag_set` — which is how the kernel and the libraries state every set they rely on being non-empty: `meta.enum_set`, `numeric.integer_member_set`, `unicode.text_member_set`, `net.scheme_set` and `numeric.decimal_member_set`.

**Map types** use `{key => value}`, mirroring the data notation, with an optional size specifier after `;` (`{text => order; 1..}`) desugaring to `min_items`/`max_items` under the same grammar, coherence, and diagnostic rules as arrays. The sugar's key position accepts a name — a qualified name, never an application, a paren or a bracket form: `meta.map.key_type` is `meta.type_ref`, so a key is a name in the explicit `!meta.map { key_type: … }` form too, and a composite or applied key type is declared and named (`method_ref => meta.ref<method>`, then `{method_ref => V}`). The sugar takes exactly one `key => value` entry — a map *type* has one key type and one value type; the sugar mirrors the data's shape, not its arity, and the diagnostic for a second entry SHOULD say so. The value side admits `?`, marking the value voidable exactly as `[T?]` marks an array element: `{text => order?}` produces `voidable: true` on the resolved `meta.map`, under which an entry's value MAY be the void sentinel `_` (§7.6) — the entry is present with a void value ([TSON-DATA] §2.9) and counts toward the size bounds like any other. Without the suffix a void entry value is a validation error. A map is unordered unless it says otherwise, as a JSON object is: two maps holding the same entries in different orders are one value. `!meta.map { key_type: K  value_type: V  ordered: true }` declares one whose entry order is part of its value, so those two are distinct, and a host binds it to a map that keeps its order; the sugar has no spelling for it. The key side does not admit `?`: a void key is a resolver error ([TSON-DATA] §2.9), and `map-key` has no `?` to write (§12.1). Annotations inside the sugar braces are a parse error; the declaration is the annotation anchor. Brace dispatch between the record and map readings is by one consumed token plus one of lookahead, reusing [TSON-DATA] §2.8's machinery (§12.2).

**Tuple types** use `[type, type, ...]` with comma or whitespace separation between individually typed positions, and a comma may follow the last ([TSON-DATA] §2.4). A tuple requires at least two element type expressions: two or more type expressions separated by whitespace or comma inside brackets is always a tuple; a semicolon after a single type expression introduces an array size specifier; a single type expression with no semicolon is an unconstrained array — never a one-element tuple. `[text,]` is therefore an unconstrained array of `text`, the trailing comma following a value and changing nothing. A tuple of exactly one position has no bracket spelling and is declared: `one_text => !meta.tuple [{ element_type: text }]` admits `[a]` and refuses `[]`, `[a b]` and `[_]`, and `voidable: true` on the element admits `[_]`.

A tuple position answers one question — may its value be void? — recorded as `meta.tuple_element.voidable`. A position, like an array's element, is never missing, so it has no `optional`; a record field answers both questions (§5.2). Tuples are fixed-length: every position MUST be present in the data. A voidable position may carry the void sentinel `_`, but the slot itself MUST appear — a tuple value with fewer elements than the type's position count is a validation error however many trailing positions are voidable. Given `sparse_pair => [text, text?]`: `[a, b]` and `[a, _]` are valid; `[a]` is a validation error. Authors wanting a tail that may be left off should use an array (`[text; 1..]`).

**Choice types** use `(type | type | ...)` — see §5.4.

**Arguments** use `name<arg, arg>`: positional, each a name or, inside an open record, one of its parameters. A sugar form, an applied constructor or an application of another open record written as an argument of an open record's application is refused, and the form is declared first: `orders => [order]`, then `page<orders>`; `text_box => box<text>`, then `pair<text_box, int32>`. Inside an open record an argument of a *constructor's* application is the exception, since it fills a type slot and closes there when the record is applied: a form over a parameter is held as the application it spells (`meta.map<text meta.array<T>>` for `{text => [T]}`, `meta.array<box<T>>` for `[box<T>]`, §5.10). The head resolves per §3.3.2 to an open record, whose parameters the arguments bind (§5.10), or to a constructor, which the application constructs (below), and the list supplies every argument. An argument is a type and never a literal: the grammar admits no number and no quoted token there (§12.1). In resolved output the application is the reference value [TSON-DATA] §7.4 reads, its arguments references and never sugar; in a data document it is a validation error (§4.2).

**An application of a constructor constructs it.** Where the head resolves, past its alias hops, to a constructor — a closed record IS-A `meta.type` — `C<a₁ … aₙ>` is the construction `!C { f₁: a₁ … fₙ: aₙ }`, where `f₁ … fₙ` are `C`'s **argument slots**: its required fields with no default and no pin that hold a type — typed by the type-slot choice `meta.type_expr` (§4.2), or by a reference naming types (`meta.type_ref`, `meta.identifier_ref`, a `meta.ref<C>` over a constructor) — in declaration order. `meta.array<text>` is the entry `[text]` is, `meta.map<text int32>` the one `{text => int32}` is, and `set<text>` is `!meta.set_type { element_type: text }`. Each argument is judged by its slot's `of` — `meta.namespace<int32 text>` is refused, `key_type` admitting identifier families alone — and is not followed through an alias, so `meta.array<user_id>` is `[user_id]` and not `[uuid]`. A constructor with no argument slot takes no arguments, and a construction leaving another required field unwritten is refused as any incomplete construction is (`meta.enum_type<meta.identifier>`, `members` unwritten). At a type slot the construction is written in place, as `[text]` is, and is never looked up to a declaration of the same construction (§8.2); a refined constructor applied, `nonempty<uuid>` over `nonempty => meta.array ^ { min_items?: = 1 }`, is tagged `!nonempty`, a type apart from `[uuid; 1..]`. A declaration whose body is a construction is that construction in place (§5.6).

**Desugaring.** Every sugar form desugars to a canonical constructor application (§5.6) — a bracket, brace or paren form's target one of the kernel's entries by path, whatever the document imports (§3.3.1) — except the application of an open record, which substitutes:

| Source form                  | Desugaring                                                                     |
|------------------------------|--------------------------------------------------------------------------------|
| `{ fields }`                 | `!meta.record { fields: { … } }`                                               |
| `[T]`                        | `!meta.array { element_type: T }`                                              |
| `[T; N]`                     | `!meta.array { element_type: T  min_items: N  max_items: N }`                  |
| `[T; N..]`                   | `!meta.array { element_type: T  min_items: N }`                                |
| `[T; ..M]`                   | `!meta.array { element_type: T  max_items: M }`                                |
| `[T; N..M]`                  | `!meta.array { element_type: T  min_items: N  max_items: M }`                  |
| `[T?]`, `[T?; ...]`          | the corresponding form with `voidable: true` bound directly                    |
| `[T, U, ...]`                | `!meta.tuple { elements: [...] }`                                              |
| `(A \| B)`                   | `!meta.choice { variants: [A B] }`                                              |
| `{K => V}`                   | `!meta.map { key_type: K  value_type: V }`                                     |
| `{K => V ; spec}`            | `!meta.map { key_type: K  value_type: V  min_items/max_items: … }`             |
| `{K => V?}`, `{K => V?; …}`  | the corresponding map form with `voidable: true` bound directly                 |
| `C<a₁ … aₙ>`, `C` a constructor | `!C { f₁: a₁ … fₙ: aₙ }` over its argument slots (above): `set<T>` is `!meta.set_type { element_type: T }` |
| `H<args>`, `H` an open record | a substitution (§5.10, §8.2)                                                   |
| `name => T`                  | `!meta.alias { target: T }` (§8.3)                                             |

Array forms record `element_type`, `voidable` from the element's `?` suffix, and `min_items`/`max_items` from the size specifier; tuple forms record `elements` with a `meta.tuple_element` per position, each carrying its own `voidable`; map forms record `key_type`, `value_type`, `voidable` from the value's `?` suffix, and the bounds.

**Nested forms are written in place.** A sugar form or an applied constructor at a type slot is its construction, held at the slot in a `meta.type_inline` (§4.2): `tags: [text]` is `type: { of: !meta.array { element_type: text } }`, `{text => [money]}` holds one construction inside the other's `value_type`, and `set<text>` is `{ of: !meta.set_type { element_type: text } }`. A declaration's own body is the construction itself, the entry's value with no wrapper (`ids => [order]`, §5.6). Nothing is lifted to an entry and nothing is named for its content, so desugaring is a local rewrite and nesting needs no special channel: an inner form's marks, sizes and bindings live in its own construction, and a size specifier or `?` is legal wherever the form is, except over a parameter (§5.10). Inside an open record a form over a parameter is held as the application it spells, `[T]` as `meta.array<T>` and `{K => V}` as `meta.map<K V>`, and any other form over a parameter is a resolver error (§5.10); a form over no parameter there, `[order]` in `<T> { a: T  b: [order] }`, is written in place like any other. An application of an open record at a slot closes in place too (§5.10). An application with arguments therefore appears in resolver output only where it is still *open* — in an open record's field types, the constructions written there, and `chain.parents` (§4.1, §5.10) — and in the `meta.application` annotation on a declaration that names one (§8.2), and means "an application", nothing else.


### 5.4 Choice Types

A choice type declares that a value may conform to any one of a set of alternative types:

```
contact_method => (email | phone | address)
```

A choice MUST contain at least two variants; each variant is a type expression. The choice name is then used like any other type name in field definitions, and choices MAY also appear inline in type expression positions: `contact: (email | phone)`.

**A variant MUST NOT resolve to `void`** — judged after following the alias chain (§8.3), so an alias of `void` is caught too; the declaration is a resolver error, and the diagnostic SHOULD say why: optionality is not choice — whether a slot may hold no value is the position's own fact, so the author marks the position (§5.2's `?`), or types the field `void` outright where a slot that only ever holds the void value is genuinely meant. `(T | void)` would create a second, worse spelling of `T?`: void is the one thing the format spells one way ([TSON-DATA] §2.9), and it is a fact of a position, never a variant.

**Disjointness** is a total, two-valued fact of the declarations: **discrimination-class distinctness**. Every type has at most one **discrimination class**, derived after following its alias chain (§8.3):

| Class | Types |
|---|---|
| `boolean` | the boolean family |
| `number` | every numeric family — integer, decimal, and float tiers alike |
| `string` | every text-form family — `text`, the identifier families, `uuid`, `uri` and `uri_reference`, `iri` and `iri_reference`, `regex`, `email`, the temporal families, `bytes`, the network-address families, the references, and their refinements |
| `brace` | records and maps (both `{...}`) |
| `bracket` | arrays and tuples (both `[...]`) |

An enum's class is read off its members under its `name_type` (§7.4), always an identifier family — `enum` itself, and an enum over a profile a schema declares: it is its members' shared class read off each member's own token (`[true false]` is boolean-class; a name of the series' own profile never begins with a digit, so no member of an `enum` is number-class, and mixed members yield none). A closed set of text that is no name is the text family's own `members` facet, `ports => !text ^ { members: ["80" "443"] }`, string-class as every text atom is, so `( ports | integer )` is disjoint where `( ports | text )` is not. A reference (§4.2) is string-class, its values being names. A map keyed by an identifier family is brace-class like any map keyed by an atom. A type with no single class — `rational` and `complex` (whose typed forms straddle classes), an approximate atom whose `allow_nan` or `allow_infinity` is true as written, whatever its bounds admit (`float32` and `float64` as declared: a NaN or an infinity has no number spelling in some encodings and a string one, so the family's forms straddle classes exactly as `rational`'s do), a map whose key type, after following its alias chain, is not a type a single scalar token denotes by its content — an atom-family instance or an enum, `void` excepted, having no key at all (no single scalar token denotes a compound key, so an encoding without a delimiter pair for such a map spells it in the bracket class, [TSON-JSON] §6.4), a nested choice, `meta.declared` (§7.8), or an unresolvable or cyclic alias — has none, and a classless variant makes its choice not disjoint. `meta.identifier` is not among them: it is a text family and string-class, as every identifier profile is, so `( meta.identifier | int32 )` is disjoint, and `( meta.identifier | text )`, or a choice over two profiles, is not. The two straddling kinds are what make `disjoint` **class-stable**: a choice's untagged values are the same set in every encoding, at the price that `( float64 | text )` carries a tag in text too unless the float narrows `allow_nan` and `allow_infinity` to false. **The rule:** a choice is `disjoint: true` if and only if every variant has a class and no class appears twice; `false` otherwise. The procedure is closed and normative: a resolver MUST record exactly this — it MUST NOT prove more (value-set separation such as disjoint numeric bounds or disjoint patterns does not make a choice disjoint) or less. The classes are [TSON-DATA] §4's own host-type partition plus the two delimiter forms — three scalar classes and no more, deliberately ([TSON-DATA] §4.5): a class for a text-form family such as `date` would narrow what a `text` variant admits untagged in every schema that never mentions a date — and every text-class encoding shares them, so the fact is portable across encodings by construction. Every declared choice records `disjoint`; there is no absent state for a choice.

**A narrower constructor leaves values unreachable.** Discrimination recovers the variant from the value's class and nothing else, so a value dispatched to a variant that then refuses it is refused outright — there is no second attempt at another variant. A constructor narrower than its base-type class therefore leaves values of that class unreachable in any untagged choice it appears in: `number` is decimal-only where [TSON-DATA] §4's `number` production admits `0xFF`, `0b1010`, `0o377`, `.inf` and `.nan`, so `( number | text )` classifies `0xFF` as number-class, dispatches it to `number`, and fails, never reaching `text`; `( int32 | text )` refuses `99999999999999` the same way, and a pattern-refined `text` beside another string-class variant refuses everything the pattern excludes. This is a consequence to know rather than a rule to add, and it is one more reason the labelled form (below) is the shape for alternatives an author expects to overlap.

**Tagging.** At the data level, a value matching a choice type carries a type annotation (`!variant`) selecting the variant. The tag is REQUIRED when the choice is not disjoint, and MAY be omitted when it is: `disjoint` means precisely that the encoding's own form resolution — for TSON text, the single base-type-resolution pass of [TSON-DATA] §4, plus the brace/bracket delimiter — recovers the variant, so an omitted tag is recovered from the value's class, never by a second, type-directed inspection of the value's form; the once-only reading of form ([TSON-DATA] §2.4) is preserved. A value omitting the tag at a non-disjoint choice is a validation error. For an emitter the rule is two sentences: *if two variants share a class, tag every value of the choice; a tag is never wrong.* And: **at a disjoint choice an emitter writes each untagged value in the form of its variant's class** — a text value whose content would resolve to another class (`42`, `true`) is written quoted, and a value of a boolean or number variant is written bare — since there the one consultation of form [TSON-DATA] §2.4 allows is the one that picks the variant, and the form an emitter chooses is its tag. The reader does exactly what the predicate says: an untagged value goes to the variant of its token's class and is validated as that variant, with no fallback, so a hand-written `42` at `( int8 | text )` reads as the number and a `"42"` as the text.

**The `@meta.disjoint` assertion.** An author MAY annotate a choice definition with `@meta.disjoint` (the kernel's `void`-targeted marker, written bare per §6 — presence is the assertion) to assert that its variants are **distinguishable by the encoding's form resolution** — the property the derivation checks, and a weaker and more useful one than mutual exclusion of inhabitance. Overlap in inhabitance is ordinary and expected: [TSON-DATA] §2.4's *form is not meaning* makes `text` admit every token, so `42` inhabits both variants of `( int32 | text )`, and the choice is disjoint all the same because the token's class recovers the variant. The annotation carries no decode force — the resolver computes the choice body's `disjoint` whether or not it is present — and is a **checked annotation** (§6) with exactly two outcomes against the derived fact: **verified** (`disjoint: true`; silent), or a **resolver error** (`disjoint: false`): the author asserted a property the declarations lack, in the manner of `N > M` in a size range (§5.3). The derivation is total, so there is no unprovable state and no warning tier. A choice whose variants are separated only by value sets — bounded numerics, disjoint patterns, disjoint enum member sets — derives `false` and rejects the assertion; nothing operational is lost, since no encoding could drop those tags, and the labelled form (below) is the recommended model for such alternatives. A resolver never diagnoses a choice merely for being non-disjoint — overlap is often intended, and the tag serves it.

**Resolution.** `(A | B | ...)` desugars to `!meta.choice { variants: [A B ...]  disjoint: … }` — a SUM-kinded entry when declared, and a type written in place when inline (§5.3), so the body carries the `disjoint` fact wherever the choice appears. The fact sits **in the choice body**, beside the variant list it is derived over (§8.1): `meta.choice` is the only body that has one, so "recorded on every choice and on nothing else" is structural rather than a rule a document could break. Each variant is a `meta.type_expr` value (§4.2, §8.1), a type's name or a type written in place, `( [text] | integer )` holding `{ of: !meta.array { element_type: text } }` beside `integer`. The resolver validates that each variant resolves to a distinct type (§8.2). At a choice a tag names a declared variant by its name, and a variant written in place has none, so it is selected untagged alone: **a choice that is not disjoint names every variant**, and one holding a variant written in place — `( [text] | [int32] )` — is a resolver error at the declaration, naming the variant to declare. A disjoint choice may hold variants written in place.

A choice discriminates by variant *type name*; for labelled disjunction — mutually exclusive alternatives distinguished by field label, including alternatives of the same underlying type — see field groups (§5.11). A choice has no discriminator field: an author reaching for member dispatch — the value placed by a field it carries rather than by a tag — wants the composed family of §5.2, an abstract base whose members pin a field written `=?`, and a position typed by the base rather than by a choice over the members. The labelled form is the recommended resolution wherever the tag would otherwise be mandatory: a choice whose variants share a base-type class, or whose disjointness is unprovable, is often better written as a single-group record (§5.11), which discriminates by label and needs no derived disjointness.


### 5.5 Constructor Application and Value Refinement

Within the type-definition grammar, `!C { … }` **is an instance of the named type `C`** — in a schema as in data and in resolved output. Whether the entry it declares is a *type* follows from `C`'s chain and not from the grammar: `!numeric.integer_type {}` is a value of `numeric.integer_type`, and a type because `numeric.integer_type` IS-A `meta.type`; `!operation { … }` is a value of the record `operation`, and a value entry (§4.1). The invariant the data format teaches therefore holds in every grammar of the series: `!T x` describes a value shaped by `T`. Two forms follow the prefix, distinguished by the `^` operator; the name after `!` resolves per §3.3.1.

**Constructor application — `!C value`.** Produces a constructor instance filled with specific values. The core value after `!C` (§12.1) is a record of bindings interpreted against the constructor's record shape — the field list `C` declared as its narrowable vocabulary — or the positional form of §5.6. This form does NOT establish IS-A: construction transfers only the constructor's kind; the result carries `C` as its tag and no supertypes. Applying an entry that is neither `meta.top` nor IS-A it nor an ordinary type after a bare `!` is a resolver error (§3.3.1), and so is applying one that is `abstract` — `meta.top`, `meta.type` and the base kinds (§4.1). The kernel's `meta.alias` is applicable like any other: `!meta.alias { target: X }` denotes the alias `X` — ALIAS-kinded, carrying no chain — and not a value of the head's own kind. An application of a **namespace type** builds a map value: `orders => !api { … }` is read as the map `api` describes, and the entry is a value entry (§4.1); an application of `schema` declares (§4.1).

```
integer => !numeric.integer_type {}
boolean => !enum [true false]
bytes   => !binary.bytes_type {}
```

**Kind determination.** A constructor's kind is settled at definition time by the **base kind** — `meta.atom`, `meta.product` or `meta.sum`, excluding `meta.top` and `meta.type` (§4.1) — reachable through its transitive chain, each counted only as the kernel's own entry. Zero base kinds in the chain → PRODUCT by structural default; exactly one → that kind; two or more → resolver error, since the kinds are categorically distinct. `!C {}` simply inherits `C`'s settled kind.

**Bodies are closed.** A construction or refinement body is a record whose type is known — the constructor's own constraint-field vocabulary — and is validated as an ordinary closed record (§7.2): each member binds against a declared field, and **a member the vocabulary does not declare is a resolver error** at the declaration, naming the member and the constructor's real fields. This is stated here, where bodies are written, because the silent alternative is the dangerous one: an implementation that binds field-by-field and ignores unmatched members reports success while discarding the constraint the author wrote (`!integer ^ { minimum: 1  maximum: 100 }` — JSON Schema's spellings — would compile clean and constrain nothing).

**Value refinement — `!I ^ { values }`.** Refines a value by merging the body over it, under the refinement rules the value's *type* states for its fields (§5.7). `I` MUST resolve, past its alias chain, to a value whose type states refinement rules — an atom-family instance, an entry whose body *is* an atom application (`integer` carrying `!numeric.integer_type {}`), the kernel's reference instances (`meta.type_ref => !meta.field_type_ref ^ { allow_sibling: false }`) — not a constructor, whose body is the vocabulary record *describing* one (`numeric.integer_type` carries `!meta.record { … }`, and is refined by the record form, §5.7). The test is on the value and on nothing else: an instance and its constructor are both ATOM-kinded, so kind does not separate them, "IS-A `meta.atom`" says the opposite of what a reader expects (true of the constructor, false of every instance, IS-A stopping at construction), and a plain record has no supertypes either — where `integer` carrying `!numeric.integer_type {}` against `numeric.integer_type` carrying `!meta.record { … }` is one test that neither of those makes alone. The body is the braced subset of the payload a constructor application takes — a data-grammar `record` or `empty-brace` (§12.1) read against the constructor's vocabulary: a bare value, a second type annotation, an annotation, or a map in body position is a parse error, and the positional form of §5.6 does not apply. The body is data, not a record of field declarations: `size: { bits: 8  signed: true }` binds a nested value, and `min: 1` binds a number, exactly as they would after `!numeric.integer_type`. This form DOES establish IS-A: the new value carries `I`'s constructor as its tag, `chain.supertypes` holding `I` and everything `I` IS-A, and a body in the constructor's canonical form (§5.6). A value whose type states no refinement rule for a field the body names is refused at `^`, naming the type: a container instance (`names => [text]`) and a namespace value (`!orders ^ { … }`) are finished, the rule being total. A refinement head admits no removal clause (§5.9).

```
age              => !integer ^ { min: 0  max: 150 }
non_empty_text   => !text ^ { min_length: 1 }
positive_integer => !integer ^ { min: 1 }
```

`age` resolves to `!numeric.integer_type { chain: { supertypes: [integer] }  min: 0  max: 150 }` and can be refined further; a refinement of a refinement carries the complete chain (`small => !int32 ^ { … }` carries `[int32 integer]`). Founding and refining are distinguished at the head, by the operator: `!numeric.integer_type {}` applies the constructor (fresh family, no IS-A); `!integer ^ { min: 0 }` refines the instance (IS-A `integer`). Record refinement, `T ^ { … }`, is the same rule with `meta.record` as the type: a record type is a value of `meta.record`, its field-declaration body is sugar for that value, and §5.7's rules are `meta.record`'s. The two forms differ only in the body's grammar: `!` before the operand means the body is data, and its absence means field declarations.

**Construction creates peers, not subtypes.** One constructor may found any number of nominally distinct families: `dogs => !numeric.integer_type {}` is a fresh atom family with the same body as `integer` and no relation to it, and `small_dog_count => !dogs ^ { min: 0  max: 5 }` refines `dogs`, not `integer`. Identity is nominal for a declaration (§8.2): `stock_id => !net.uuid_type {}` and `other_id => !net.uuid_type {}` have byte-identical resolved bodies and are two types, and `!other_id` is refused at a `stock_id` position, as is `!uuid`. The only IS-A the `!` forms ever create is the refinement's hop to its source — recorded in the type's `chain` and deliberately nowhere else in the body: the canonical form (§5.6) erases the surface distinction, so `chain.supertypes` is the sole carrier of the atom family's IS-A fact (§8.1).

**Single-required-field positional form.** When a constructor has exactly one field whose name is unmarked, the core value after `!C` fills that field directly; see §5.6. The positional form applies to constructor application only — a refinement body is always a braced record.

**Facets with a stated meaning.** Each constraint family's facets are defined by the constructor's documentation in the kernel and the libraries (§9), with the tightening rules of §5.7 applying per facet kind. A facet holding a value of the constrained family — a bound, a step, a member — is typed by that family's own instance, a float's bounds by `number` (below), and a network list by the family's network instance, `[cidr4]` or `[cidr6]`, each declared in the same document (`numeric.decimal_type`'s bounds are `number`, `temporal.date_type`'s are `date`, `net.ipv4_type.within` is `[cidr4]`), and is read by that type at schema load like any other field, a value the type refuses being a resolver error (§7.4). What every family's facets rest on, and what the artifacts state per family, is one clause:

> **A type denotes a value space. An encoding defines a lexical space over it and one canonical form per value. Equality, ordering, refinement, disjointness and content addressing are defined over value spaces only, never over spellings.**

Every rule in the series that compares two decoded values — the set duplicate rule (§7.5), map-key identity ([TSON-DATA] §2.6), the check of a written value against a FIXED one (§5.2), and the digest a pinned reference verifies (§10.2) — delegates to the element type's equality, and this clause is what that delegation reaches. For the numeric families [TSON-DATA] §4.3 already states it (`255` and `0xFF` are one value); for every family whose value space differs from its lexical space the companion artifacts state it in the family's own `@doc`, and the consequences that decide interoperation are these:

- **`bytes`** is an octet sequence. The RFC 4648 alphabet a text encoding spells it in is a `binary.bytes_type` *selector* (`encoding`, defaulting to `BASE64`), which picks a spelling and can never change what two values compare as: case in hex and padding in base64 are lexical, `"abcd"` and `"ABCD"` under `HEX` are one value, and the length facets count octets — `length: 32` is a 32-byte digest whether it arrives as 64 hex characters, 44 base64 characters, or 32 raw bytes. An encoding whose values are octets ignores the selector and writes them raw, and a round trip through it is value-preserving: the alphabet is re-spelled from the schema, not recovered from the bytes. The selector is a facet rather than an annotation because a container element has no annotation position — `[hexdigest]` works only if the element's own type carries the alphabet — and it is **not refinable**: an alphabet narrows nothing, since every octet string is writable in every one of them, so `hexbytes => !bytes ^ { encoding: HEX }` would claim an IS-A carrying no narrowing at positions no base64 reader could honour, and is a resolver error (§5.7). Another alphabet is another **instance**, `hexdigest => !binary.bytes_type { encoding: HEX  length: 4 }`; refining for length inherits the alphabet, `sha256 => !bytes ^ { length: 32 }` being a base64 sha256. `binary.bytes_type` carries no `spec`: RFC 4648 governs spellings, not octets. The kernel declares one `bytes` and no spelled siblings, and [TSON-DATA] §5.3's `!bytes` is base64, so the name denotes one type in both conformance classes.
- **`number`** and the exact numeric tiers are exact values with no scale: `1`, `1.0` and `1.00` are one value, and whether a spelling's trailing zeros survive a round trip is an encoding's promise, not the type's. `rational` is the fraction — `2/4` and `1/2` are one value. A bound on `numeric.decimal_type` is a `number`, and is spelled as one: `!number ^ { min: 0x10 }` is refused, as `!number 0x10` in data is, the family admitting no based-integer spelling.
- **A float's bounds are exact.** `numeric.float_type`'s bounds are `number`s, validated against the value as written, before rounding, so typing them by the grid would round a bound the family says is unrounded. A bound admits a value inside its interval: NaN is inside none, so a float body with any bound admits no NaN, and `allow_nan` decides NaN only where no bound is written; ±infinity are ordered values a bound judges as any other — `max: 100` refuses `+.inf`, and `min: 0` admits it where `allow_infinity` does (§5.7).
- **A text value is its token's text put into the type's form.** `unicode.text_type` carries `normalization`, over `unicode.normalization => !enum [NONE NFC NFKC NFKC_CASEFOLD ASCII_CASEFOLD]`: a value is the token's text, unquoted, unescaped, and then *put into* that form — rewritten, never refused for not already being in it — and every other facet judges the value and never the spelling (lengths, `pattern`, `members`, an identifier's profile), as does every comparison of two values: pins, map keys, set uniqueness, the duplicate and look-alike rules. This is the series' own posture, stated for text: an atom maps a spelling to a value (`0x10` is 16), and a quoted token at an identifier position is normalised before it is matched ([TSON-DATA] §7.2.1). **No comparison goes below NFC.** Two text values are one value when, each in its type's form, they are NFC-equal, so a composed and a decomposed `É` are one value under every member — `NONE` and `NFC` admit the same equality and differ in what the value is, the text as written or its composed form, which is what the other facets judge and a round trip writes. NFC can carry identity where name hygiene cannot ([TSON-DATA] §8.2): Unicode's normalization stability policy keeps two NFC-equal texts NFC-equal under every later version, so the verdict never moves, and an identifier or token policy relaxed to nothing leaves it standing. `NONE` keeps the text as written. `ASCII_CASEFOLD` maps `A`–`Z` to `a`–`z` and changes nothing else — it runs no Unicode normalisation on the value — and is the comparison URI schemes, HTTP field names and DNS names state for themselves. `NFC` and `NFKC` are Unicode's canonical and compatibility forms. `NFKC_CASEFOLD` is `toNFKC_Casefold`, UAX #31's form for names compared without case: a full case fold and not lowercasing (`ß` folds to `ss`, so a length changes), which also folds compatibility characters *into* ASCII and removes the default ignorables, the join controls among them, so [TSON-DATA] §7.7's context rule has nothing left to judge under it. NFD and NFKD are not members: each is its composed twin's equivalence stored decomposed, and would change a spelling and no verdict. The default is `NONE` on `unicode.text_type`, where quoted text is otherwise kept as written, and `NFC` on `unicode.identifier_type`; `unicode.regex_type`, `net.uri_type`, `net.iri_type` and `net.email_type` fix `NONE`, since a pattern put into another form matches something else (`[A-Z]` folds to `[a-z]`), and past the scheme and host a URI compares with case, as an IRI does and as a mailbox's local part does. `normalization` is **fixed where the type is constructed** (§5.7). Two consequences reach an encoding. **A round trip writes the value**: `Content-Type` under a folding type is written back as `content-type`, as `0x10` is written `16`. And a refusal SHOULD quote the token as written and then the value it was judged as — `'PUT' (read as 'put' under ASCII_CASEFOLD)` — the written spelling being what a reader, or a repair of a generated document, has to find. A schema's own members and pins are values too, decoded as data is.
- **A URI is RFC 3986's URI, and an IRI RFC 3987's.** `net.uri_type` states RFC 3986's distinctions as facets. `allow_relative` admits a relative reference (RFC 3986 §4.2) beside a URI (§3), so its default is the URI-reference (§4.1) and withdrawing it requires a scheme. `allow_fragment` admits a fragment (§3.5); withdrawn beside `allow_relative` it leaves the absolute-URI (§4.3). `schemes` lists the admitted schemes as a set of the kernel's `net.scheme_name`, an `ASCII_CASEFOLD` identifier, so a scheme is held folded as RFC 3986 §3.1 compares it, `[HTTP http]` is a duplicate, and a value with no scheme is outside every set. `net.uri_type`, in `net.tn`, has `uri_reference => !net.uri_type {}` and `uri => !uri_reference ^ { allow_relative: false }` as its pair, so `uri` IS-A `uri_reference`, every URI being a URI-reference. A URI is US-ASCII (RFC 3986 §2). `net.iri_type` has the same three facets with the same meanings over RFC 3987's grammar — `ucschar` beyond US-ASCII, and `iprivate` within the query — and the kernel's `iri_reference => !net.iri_type {}` and `iri => !iri_reference ^ { allow_relative: false }` are its pair, so `iri` IS-A `iri_reference`; an IRI is judged through the URI it maps to (RFC 3987 §3.1). **`net.iri_type` is the kernel's** because the kernel types its own references by it: `iri` types `atom.specification.spec` and every schema identity ([TSON-DATA] §2.2.1, §3.3) — an IRI-reference whose host or path may reach beyond US-ASCII, whose remaining form rules are identity's own and are refused where an identity is formed. `uri` is not IS-A `iri`: families do not relate, and §5.4 already treats every text-form family as overlapping. A relative reference where `allow_relative` is withdrawn, a scheme outside `schemes`, and a fragment where `allow_fragment` is withdrawn are validation errors — the token is inside the family's lexical space and outside the type's value space — and a character outside the family's grammar is refused by the family's parser (§7.4). The value of each is its text as written.
- **`hostname` is a domain name, and the value is the name** ([TSON-DATA] §5.5): RFC 5890's labels, LDH labels, A-labels or U-labels in any mix. ASCII case is lexical, and a U-label and its A-label are two spellings of one label, each recovered from the other by Punycode (RFC 3492), so `Example.COM` and `example.com` are one value and so are `xn--bcher-kva.example` and `bücher.example`. The canonical text is the lowercase U-label form — what a writer emits, and the form an identity's host takes ([TSON-DATA] §2.2.1). `net.hostname_type` carries one facet beside its `spec`: `allow_idn`, a **permission** (§5.7) granted by default, which withdrawn admits only a name none of whose labels is a U-label or an A-label. It judges the value and not a spelling, so `!hostname ^ { allow_idn: false }` refuses `bücher.example` and `xn--bcher-kva.example` alike, as a validation error — the token is inside the family's lexical space and outside the type's value space — while a token outside RFC 5890's grammar (uppercase beyond ASCII, a trailing dot, a last label beginning with a digit) is refused by the family's parser (§7.4).
- **`host` is a host name or an IP address, never both** ([TSON-DATA] §5.5): RFC 3987's `ihost` profiled to DNS, whose lexical space is the union of `hostname`'s, `ipv4`'s and `ipv6`'s plus an IPv6 address in brackets. Each member keeps its own equality — a name compares as a `hostname` value, an address numerically — and a name never equals an address, nor an IPv4 address an IPv6 one, its IPv4-mapped form included. The brackets are a spelling and not a value, so `[::1]` and `::1` are one host. The canonical text is the member's: lowercase U-labels, a dotted quad, or RFC 5952 text unbracketed. `net.host_type` has no facets beyond its `spec`: a position that wants only names, or only addresses, is typed by the member, `hostname`, `ipv4` or `ipv6`.
- **`media_type` is a type, a subtype and a map of parameters** ([TSON-DATA] §5.5): an RFC 6838 media type, its parameters RFC 9110 §8.3.1's. The type, subtype and parameter names compare without ASCII case, parameter order carries no meaning, a quoted parameter value equals its token form, and a parameter value keeps its case except `charset`'s, which folds. The canonical text is the lowercase type, subtype and parameter names with the `charset` value folded, parameters sorted by name, each value a token where it is one and quoted otherwise, joined by `;` with no whitespace: `text/html;charset=utf-8`. `net.media_type_type` carries three facets beside its `spec`. `allow_parameters` is a **permission** (§5.7), withheld by default; withheld, a value carrying a parameter is a validation error. Net's `media_type` admits none, and since a refinement may withdraw a permission and never grant one, a position that takes a `Content-Type` value declares its own instance, `content_type => !net.media_type_type { allow_parameters: true }`. `types` lists the admitted top-level types, and `suffixes` RFC 6839's structured-syntax suffixes, a suffix `s` admitting a subtype ending `+s` and the subtype `s` itself; each list, where it is given, must admit the value, and a value outside one is a validation error. Both are **member sets** (§5.7) whose entries are media-type names and compare as the type and subtype do, without ASCII case — so `suffixes: [JSON]` admits `application/ld+json` and `application/json`, and `types: [Text]` refines `types: [text image]`: the family's own value identity that §5.7's subset rule compares by is the media type's, not that of the lists' element type `text`. Each entry MUST be an RFC 6838 `restricted-name`, a suffix holding no `+`, and a list MUST NOT be empty, an empty one admitting nothing; a body breaking either is a schema-load coherence error.
- **`time` and `datetime` are instants.** RFC 3339 makes the offset mandatory, and the offset is a *spelling*: a `datetime` is the instant on the UTC timeline and a `time` is the time of day in UTC on `[00:00:00, 24:00:00)`, so `2026-01-01T10:00:00+01:00` and `2026-01-01T09:00:00Z` are one value, `-00:00` (offset unknown, RFC 3339 §4.3) is the same instant as `Z`, and `23:30:00-02:00` is `01:30:00Z`. A leap second — second 60, which RFC 3339's grammar admits — is neither a time of day on that interval nor an instant on that timeline, and both families' parsers refuse it ([TSON-DATA] §5.4). Both families are totally ordered — the mandatory offset is what makes them so; XSD's partial order is the cost of an optional one. A wall clock with no offset, and an appointment for which the zone is data, are other value spaces and not these types. TSON text preserves the offset as written; an encoding that stores the instant may drop it. A bound on `temporal.time_type` is a `time`, on `temporal.datetime_type` a `datetime`.
- **`duration` is a signed exact decimal number of seconds, and `period` a signed integer number of months.** The split is what gives each a total order: a month has no fixed length beside a second that has one, so the two are two value spaces rather than one partially ordered one, and every ordered-bound facet on them is enforceable (§5.7). `duration`'s lexical form is RFC 3339 Appendix A's `duration` production with an optional leading `-`, a fraction on the seconds component only, any subset of `D`/`H`/`M`/`S` in order with at least one present, and no `Y` or month-`M`; `PnW` stands alone, a week is 7 days and a day 86400 s, so `PT90M`, `PT1H30M` and `P0DT5400S` are one value and `PT0S`, `P0D` and `-PT0S` are one value. `period`'s is `P` with a `Y` component, an `M` component, or both, and nothing else. Both ends of `duration`'s space are fixed at a signed 64-bit count of nanoseconds: **`time-secfrac` is `"." 1*9DIGIT`** — stated once, for `duration`, `time` and `datetime` alike, since no host runtime represents finer than a nanosecond and a value below that floor is not exactness anyone can use — and **a `duration`'s magnitude does not exceed 2⁶³ − 1 ns** (about 292 years), as a magnitude so that negating an admitted value yields an admitted one; a processor MUST represent every value in that range and MUST reject one outside it whether or not its own representation could hold it. A span of centuries is a calendar span and is a `period`; a quantity finer or wider than the range is a `number` in the unit the schema names. The floor is lexical (a fraction reaches the value through one component and is capped at the token) and the ceiling is a value rule (the seconds count is summed from `D`, `H`, `M` and `S`, so `P400000D` overflows with no single component long enough to catch). A bound or step on `temporal.duration_type` is a `duration`, on `temporal.period_type` a `period`.
- **`precision`** on `temporal.time_type`, `temporal.datetime_type` and `temporal.duration_type` is a constraint on the *value*, not on a spelling: `precision: N` admits a value that is a whole number of 10⁻ᴺ seconds — `fraction_digits` on the seconds count — so `precision: 3` is millisecond resolution, `precision: 0` whole seconds, and `precision: 9` the nanosecond grid; reading admits any spelling of an admitted value, trailing zeros included, so `12:00:00.500` is admitted under `precision: 1`, and a text encoding writing the value writes at most N fractional digits, `12:00:00.5`. The atom is exact — nothing is truncated; a value off the grid is rejected. `precision` may not exceed 9, which falls out of the fraction production rather than being stated twice; a larger value is a schema-load coherence error. The temporal families carry no timezone facet: RFC 3339 `full-time` and `date-time`, which the atoms' `spec` pins, already make the offset mandatory, so a facet requiring it would be vacuous and one relaxing it would widen the atom against its own pin. All five temporal families state their bounds as §5.11 groups, `( min | exclusive_min )?` and `( max | exclusive_max )?`, so a half-open interval is spellable on a date exactly as on an integer.
- **The network families' `within`/`excluding` pair MUST admit a value**, and the rule is exact. `net.ipv4_type`, `net.ipv6_type`, `net.cidr4_type` and `net.cidr6_type` each carry `within` (a value must lie inside some listed network; a network must be a subnet of one) and `excluding` (an address must lie inside none; a network must not *overlap* any — overlap, not containment, so a wider value cannot smuggle an excluded block), each list typed by the family's network instance, `[cidr4]` or `[cidr6]`, so a malformed entry or one with nonzero host bits is refused at schema load by that reader, as any value of it would be. A body whose two lists between them admit nothing is invalid at the schema level, in the voice the family already uses for a prefix outside its range: `{ within: ["10.0.0.0/8"]  excluding: ["10.0.0.0/8"] }` is `{ min: 10  max: 3 }` in another spelling, and one an author reaches by editing. The check is decidable exactly — CIDR blocks are nested or disjoint and never partly overlap, so an exclusion meeting a permitted block either contains it or lies wholly inside one of its halves, and the walk terminates at the address width — and a resolver MUST implement it exactly rather than pairwise, since `10.0.0.0/9` and `10.128.0.0/9` cover `10.0.0.0/8` by counting and only the tiling case arises from a real edit. **For a network family the prefix bounds participate**: a value must be a subnet of a `within` block, so its prefix is at least that block's, and `max_prefix` caps it from above; `{ within: ["10.0.0.0/24"]  excluding: ["10.0.0.5/32"]  max_prefix: 24 }` admits almost every *address* and no *network* at all, since the one block the prefix bounds leave overlaps the hole. The diagnostic SHOULD say which cause it found — an exclusion covering everything permitted, or the largest block the bounds leave — since the two want different edits.


### 5.6 Canonical Form and Desugaring

All type-definition bodies ultimately take a single canonical form:

```
!C { bindings }
```

where `C` names a constructor and `bindings` is a record literal filling the constructor's fields. Every other form — inline type expressions, positional constructor forms, value refinements — is syntactic sugar that desugars to this form during resolution; resolver output always records the fully expanded canonical form as the entry's value.

**Positional form.** When a constructor has exactly one field whose name is unmarked — a field the document must write, whatever its modifier (§5.2) — the core value after `!C` may be that field's value directly:

```
!enum [true false]       →  !enum { members: [true false] }
!meta.array text         →  !meta.array { element_type: text }
!meta.choice [a b]       →  !meta.choice { variants: [a b] }
```

Fields whose name carries `?` do not count, whether or not they carry a value. The positional form is invalid when the type has zero, two, or more unmarked names — the resolver MUST reject such uses with a clear error. This restriction applies to the positional form only. Where the one field is an argument slot (§5.3), the positional, braced and applied spellings — `!meta.set_type text`, `!meta.set_type { element_type: text }`, `set<text>` — are one construction.

**The positional form is general over schema-backed data.** At any data position whose declared type is a record with exactly one unmarked-name field, a non-brace-delimited value fills that field directly; a braced value is always the explicit record (so record- and map-valued fills cannot go positional — the same exclusion as above). The two spellings denote the same value; which one canonical output uses is a per-type convention (§8.1). Type-definition bodies canonicalise the other way — toward the explicit bindings record, per the end state below — a deliberate pair of conventions: bodies uniform, references minimal. **In a schema document the positional form keeps a bare token where the positional field is atom-typed or typed by the type-slot choice** — a type's name at a `meta.type_ref` slot or at a `meta.type_expr` or `meta.field_type_expr` one, whose name variant the token is (§4.2), an enum member, a literal — **and loses it where that field is otherwise product- or sum-typed**: there the explicit record is the only spelling, since a bare token at a record- or choice-typed position in a schema document would otherwise be indistinguishable from a name, and a value written by name stands at a slot typed by a reference naming values and nowhere else (§4.2, §5.12); a bare token at a record-typed slot whose positional field is product- or sum-typed, or at one whose record has no positional form, is refused as no record. So `fields: { p => text }` fills `meta.record_field.type`, a positional field typed by the type-slot choice, in a schema document as in a data document (§8.1). A data document reads every bare token at a record-typed slot positionally, whatever the positional field's type, a value written by name standing only at a reference-typed slot (§5.12), so a data document's positional fills read as they always have. **An application is a schema's.** In resolver output — a `parents` list, say — `result<T>` is the reference value [TSON-DATA] §7.4 reads, written unquoted; in a data document an application is a validation error (§4.2); and at a reference-typed slot of a *value body* — a field of a namespace entry's map (§4.1) — an application is refused outright: a reference-typed slot holds a name, and an application is none. The spelling that gives an application a name is a declaration, `order_page => page<order>` (§5.10).

**Record-bindings form.** `!C { ... }` is the explicit form, valid for any constructor as long as the bindings cover every field whose name is unmarked. Empty bindings `!C {}` are valid whenever the constructor has no unmarked-name field.

**Value refinement.** `!I ^ { values }` desugars by retargeting to the instance's constructor, **with `values` merged over `I`'s own already-bound field values**: a field named in `values` overrides `I`'s value for it, and every field `I` itself bound that `values` does not mention keeps `I`'s value — the atom counterpart of §5.7's body-materialisation rule, under which inherited constraints survive a refinement that does not mention them:

```
!integer ^ { min: 0  max: 150 }   →  !numeric.integer_type { min: 0  max: 150 }
!text ^ { min_length: 1 }         →  !unicode.text_type { min_length: 1 }
```

For a fresh instance (`integer => !numeric.integer_type {}`) the merge is a no-op — every inherited field is absent — so the single-hop desugar above is the general rule's degenerate case. Chained refinement is where the merge matters:

```
int8      => !integer ^ { size: { bits: 8  signed: true } }
bigNumber => !int8 ^ { min: -500  max: 5000 }
          →  !numeric.integer_type { size: { bits: 8  signed: true }  min: -500  max: 5000 }
```

`bigNumber` retargets to `numeric.integer_type` (an instance's tag is always the base constructor) and keeps `int8`'s `size` binding, which nothing in its own body mentions — replacement rather than merge would silently discard the width and yield an unconstrained-width integer with bounds. Recognition is syntactic — the `^` declares the intent — and the resolver verifies it: the target MUST resolve to a value whose type states refinement rules, and the retarget follows the value's tag. The result carries `I`'s constructor as its tag and `I`, with everything `I` IS-A, in `chain.supertypes` — `[int8 integer]` for `bigNumber`.

**End state.** After desugaring, every *closed* non-alias type-def body in resolver output is a binding record `!C { bindings }`, where `C` is the constructor the form applies — with the container constructors parameterless (§4.2), the nearest constructor in the source chain for every container closure is the container constructor itself: `!meta.array` for every array form, `!meta.map` for the map sugar, `!meta.set_type` for an application of `set<T>` — and `bindings` supplies values for the vocabulary's unmarked-name fields, plus the fields the application or refinement bound. A pin or default whose value is *concrete in the head's own declaration* (`meta.set_type`'s `ordered: = false`) comes from the vocabulary and does not appear in the binding record, so a validator reads any closed body with a one-hop lookup of the head, never substitution. A type slot is an ordinary required field typed by the type-slot choice (§4.2), and holds a bare token where it names a type and a `{ of: … }` record where a type is written in place (§5.3). A binding record never holds a parameter: the one open body is an open record, a `!meta.record` carrying `parameters` (§5.10). So constructors and records carry `!meta.record` bodies, and every other non-alias entry carries a binding record — or, for a value entry, the value as read against its type (§4.1). The surface abbreviations exist only in source text — except the one that names an alias: a use site names whatever entry the author named, an alias included, and output states the chain as written (§8.3).

**Top-level constructor applications are constructions.** A declaration whose body applies a constructor — the explicit form (`lookup => !meta.map { key_type: text  value_type: integer }`) or its sugar spelling (`lookup => {text => integer}`) — resolves as a construction: kind from the constructor's family, the constructor as the value's tag (§8.1), the binding record as the value, and no supertypes (construction transfers kind, not IS-A). Two such declarations are nominally distinct entries with structurally equal bodies, and neither is the same type as that construction written in place (§8.2). An applied constructor is one more spelling of the construction: `text_list => meta.array<text>` is `text_list => [text]` (§5.3). A declaration whose body is an application of an open record resolves on the same footing: the declaration **is** the entry — the substituted record its value, closed in place, the application recorded as `@meta.application` on its key — and nothing stands beside it (§5.10, §8.2). Two such declarations are two entries, as two hand-written records with one field list are.

**Named definitions required.** The refinement form (`!I ^ { values }`) and constructor application (`!C value`) are valid only as the top-level body of a declaration — the inline prohibition of §5.2. A constrained atom must be introduced with its own declaration and referenced by name.


### 5.7 Refinement

Refinement copies an existing value and tightens it — binding values, fixing defaults, restricting ranges — producing a new definition with its own identity that IS-A the source. It never changes the field set: no field is added and none removed. It is expressed with the `^` operator between a source and a body, and **a value is refined under the rules its type states** (§5.5): a record under `meta.record`'s rules, which this section states; an atom instance under its constructor's facet rules (below); the kernel's reference instances under theirs. A type that states no rule for a field the body names refuses the refinement at `^`, naming the type, so the rule is total.

```
config => { host: text  port?: integer ~ 8080  debug: boolean }
production => config ^ { host?: = "prod.example.com"  port?: = 9090 }
```

The operator carries the operation: `^` always means *refine, preserving IS-A*, at every level — record types (`config ^ { ... }`), constructors (`meta.array ^ { ... }`, a constructor being a record, §4.1), and atom and other instances (`!integer ^ { ... }`, `!meta.type_ref ^ { … }`, §5.5). A source type name followed directly by a braced body, with no operator, is a parse error; the diagnostic SHOULD suggest `^` (refinement) or `&` (composition). A refinement head admits no removal clause: `T ^ { ... } - { ... }` is a parse error — an operator that promises IS-A cannot host the operation that revokes it (§5.9).

In a refinement, only existing fields may be modified: fields in the body MUST exist in the source definition, and adding fields is a resolver error. The guiding rule is that refinement can only restrict, never expand. The source name resolves in the document's namespace; the same syntax refines local and imported types. A refinement whose source is a FINAL record is a resolver error (§5.2).

**Field facts move along three orders, and never backwards.** A field's three questions (§5.2) each carry an order from least to most determined, and a restatement refines its source exactly when no question moves backwards:

- **omission** — *nothing* → *required* → *injected*: what a missing key yields, read off the declaration as `?` without a value, an unmarked name, and `?` with a value;
- **voidable** — *true* → *false*;
- **role** — `FREE` → `DEFAULT` → `FIXED`, a default overridable until it is fixed.

So `a: T` → `a?: T ~ v` (required → injected, FREE → DEFAULT) is allowed; `a?: T ~ v` → `a: T` is refused, a default not being removable; `a?: T ~ v` → `a?: T = v` is allowed; `a?: T?` → `a?: T` (voidable true → false) and `a?: T` → `a: T` (nothing → required) are allowed, and each reverse is refused. The third of §5.2's invariants shapes one move: `a?: T? ~ v` reaches a pin only as `a?: T = v`, voidable and role moving forward together, since a pin on a voidable type is refused. One consequence is worth stating: a marker written in a base, `a: T = v`, may be relaxed by a subtype to `a?: T = v` — required → injected — exactly as `a: T` may be relaxed to `a?: T ~ v`; a family that needs its marker written in every member restates it unmarked in each. Nothing stores the omission answer, and nothing needs to: the order is checked on the derived answer, which is `optional` and `value` read together (§8.1). These orders are `meta.record`'s rules and hold for every record, a constructor among them: a constructor refinement (`meta.set_type => meta.array ^ { ordered?: = false }`) moves a default to a pin or a required field to an injected one as any refinement does, and never replaces a pin.

**A restated field's type narrows by IS-A.** A restated field's type MUST be the inherited type, or a type IS-A it (§7.2) — alias hops followed on both sides, judged at link where every name both types hold resolves — or `void` at a voidable position; any other restatement is a resolver error against the restating declaration, in refinement and composition alike (§5.8's tightening entries follow the same rule). The relation is nominal because it is the one every typed position already admits by, so a value of the refinement read at the source's position meets the field's type by the rule that reads it, and because it is total and decidable from names, where value-set inclusion would need a containment judgement for every pair of families that the series states nowhere. So `[int8]` restating `[int32]`, a variant restating its choice, and a fresh `!numeric.integer_type { size: { bits: 8 } }` restating `int32` each narrow a value set and are refused, no IS-A edge joining any of the three pairs; the kernel's `meta.namespace => meta.map ^ { key_type: meta.identifier_ref … }` passes by `meta.identifier_ref`'s refinement of `meta.type_ref`. A type written in place is IS-A nothing but itself, so where either side is one the restatement narrows exactly when the two are one type (§8.2): `[text]` restates `[text]` and refuses `[int32]`, and since a name and a type written in place are never one type, over `text_list => [text]` a field inherited as `tags: text_list` is not restated as `tags: [text]`, nor the reverse. A structural narrowing between containers is a rule of its own for each container constructor to state.

**Restatement.** Each field may be restated as itself. A restatement may change a **default** (defaults are overridable, §5.2) and MUST NOT change a **pin** — the identity case exists so a body may restate a fixed field without error, not so fixed values can be replaced. This is what forces a discriminated family's arrangement (§5.2): the base cannot pin a selector, since no member could then pin its own. A dependent field (§5.2) is restated only as itself: a refinement may make it required, and `v?: int32` over `v?: kind!` is refused, as is a typed field restated as a dependent one; pinning its sibling is what fixes its type, and the materialised body keeps `type: kind!` beside the pin.

**Value tightening is per facet kind.** "Refinement can only restrict" is checkable per constraint field, and each field's rule follows its facet's kind, declared once here for every family: an **ordered bound** (`min`, `max`, `min_length`, `max_items`, exclusive bounds, and kin) may move only inward — a lower bound may rise, an upper bound may fall, never the reverse — and requires its family's value space to be **ordered**, which every family carrying one is (§5.5; the temporal split is what made `duration`'s so, and a float's bounds judge the ordered values, the infinities among them, and exclude NaN, which no interval contains); a **step** (`multiple_of`, wherever it appears) is strictly positive, tests the value's magnitude with the sign ignored, and may tighten only to an integer multiple of the inherited step (`15` under `5` tightens; `10` under `15` is an error); a **permission** facet (a boolean granting latitude: `numeric.float_type`'s `allow_nan` and its three companions, the `allow_relative` and `allow_fragment` of `net.uri_type` and `net.iri_type`, `net.hostname_type`'s `allow_idn`, `net.media_type_type`'s `allow_parameters`, and `meta.reference_type`'s `allow_sibling`) may go from granted to withdrawn, never the reverse; a **member set** (an enum's `members`, the `members` of the exact numeric tiers, the `schemes` of a URI or IRI family, the `types` and `suffixes` of a media-type family) may shrink to a subset, never grow or replace, compared by the family's own value identity (§5.5, §7.4); a **settable-once** facet (`unicode.text_type.pattern` and `unicode.text_type.members`) may be set by a refinement where the source left it unset, restated verbatim, or left alone, and never changed — the two text facets occupy one logical position, both specifying the admitted value set, and `pattern` cannot be narrowed without a regular-language containment oracle the series decides nowhere, so giving the pair two rules by which spelling the author reached for would make the narrowing relation an artifact of notation; that `unicode.text_type.members` is settable-once where the numeric tiers' member sets shrink is the one departure from the member-set rule, stated so that a reader does not infer it from a diagnostic; a facet **fixed at construction** is pinned where it is constructed — a refinement of the *constructor* may pin it, which is how `enum` states its `name_type` — and a refinement of an *instance* restates it verbatim or leaves it alone, and never sets or moves it, because moving it would change what a token *means* rather than narrow which values are admitted — `unicode.text_type.normalization` (turning a fold on in a refinement re-reads every token the source admitted); the profile facets of `unicode.identifier_type`, `start`, `continue`, `start_add`, `continue_add`, `medial` and `exclude`, for which settable-once would be unsound, since setting `start_add` where the source left it unset *widens* the profile (`!meta.identifier ^ { start_add: "_" }` would admit `_x` without `_x` being an identifier); and an enum's or a record's `name_type` (§5.2, §7.4) — so that a different form, profile or name type is a fresh construction and never a refinement; a **selector** facet — a discriminant among members of a closed vocabulary (`numeric.integer_type.size`, `numeric.complex_type.component`, `binary.bytes_type.encoding`, `numeric.float_type.format`) — may move under refinement **only along the narrowing relation its members carry**, which each family states: a width chain for `size` (`int8` is inside `int16`, so `!int8 ^ { size: { bits: 16 } }` widens and fails, whatever the arithmetic direction); a partial order for `component` (`INTEGER ⊂ NUMBER ⊂ RATIONAL` and `FLOAT32 ⊂ FLOAT64`, the exact and approximate families incomparable since binary64 carries ±inf and NaN that no exact decimal represents, so `!complex ^ { component: INTEGER }` narrows and `!complex ^ { component: FLOAT64 }` is refused, a float complex being its own instance); and **no relation at all** for `encoding`, which a refinement may neither set nor change — an alphabet is a spelling of one value space, so a change would produce an IS-A carrying no narrowing, and another alphabet is another instance of the constructor (§5.5); a **reference's `of`** narrows to a type IS-A the source's, so every name the refinement admits the source admits too (§4.2). A selector at the constructor's default is not thereby free: the relation is over effective values, so an unwritten default and a written one refine identically, and there is no "set-from-default" permission apart from the relation. A **fixed** value follows §5.2's rules for a pin, and a written value is compared against it as a value (§5.5). A refinement value violating its facet's rule is a resolver error at schema load, in record refinement and value refinement alike (§5.5, §5.6).

**Three ways to name a type after another, and what each buys.** An author writing `user_id => uuid` and expecting a `stock_id` to be refused at a `user_id` position gets no protection, and nothing else tells them the other two forms exist:

| Spelling | Relation to `uuid` | `!uuid` at its position | a peer's tag |
|---|---|---|---|
| `user_id => uuid` — **alias** (§8.3) | the same type, under another name | accepted | accepted |
| `user_id => !uuid ^ {}` — **refinement** | IS-A `uuid`; usable at a `uuid` position | refused | refused |
| `user_id => !net.uuid_type {}` — **fresh instance** (§5.5) | unrelated in either direction | refused | refused |

Only the first is a rename, and it is what identity dereferences (§8.2): `box<user_id>` and `box<uuid>` are one application. The **empty refinement is legal** — `!uuid ^ {}` narrows nothing and is the language's one nominal-subtype spelling, which is why "a refinement tightens" is a description of what a refinement may do and not a MUST that a body change something.

**Values in a template.** A `=` or `~` value is a literal and never a parameter; a value that differs per application is a restatement where the application is declared, `created => response<order> & { status?: = 201 }` (§5.10).

**Elided type expressions.** In a refinement or supertype-composition body, the type expression in a field definition MAY be elided: when only a modifier is present (`field: = value` or `field: ~ value`), the field's type is inherited from the source declaration and only the value state changes. A modifier-only entry is always a tightening — it names no type, so it cannot declare a new field — and a modifier-only entry whose name matches no inherited field is a resolver error. Restating the type expression remains necessary when the tightening also narrows the field's type. In a fresh record definition there is no inherited declaration to elide toward: every field MUST have an explicit type expression, and the resolver MUST reject modifier-only entries there.

**Refinement requires a value its type states rules for** (§4.3 states the rule for both operator families). The source of `^` — after following its alias chain (§8.3) — MUST be a record (a fresh or refined record, a composition, a constructor, or an application of an open record — an open record named bare is a resolver error, §5.10), or an instance whose constructor states facet rules. A container instance (`lookup => {text => integer}`) is **finished**: its bindings are set, `meta.map` states no tightening rule for them, and `^` on it is a resolver error; a choice is likewise inadmissible (§4.3). A record instantiation is not finished: it closes to a `!meta.record` with fields. An application at a refinement source is absorbed as one at a composition operand is (§5.8): `x => box<text> ^ { … }` refines the substituted record, and is IS-A its template's written parents and, where a declaration names `box<text>`, that entry, in either declaration order. There is no refinement of an application head: `{text => text} ^ { min_items: 1 }` has no head to refine, and the use case is the size specifier on the sugar itself (`{text => text; 1..}`, §5.3). Record refinement chains remain open because record refinement re-emits a `!meta.record` body; a declared construction (`lookup => {text => integer}`) is terminal — authors wanting a narrower relative write the bounds on the form (`strict_lookup => {text => integer; 1..}`). The `refined-def` grammar's optional argument list serves an open record's application (`x => box<text> ^ { … }`, §5.10). Value refinement (`!I ^`, §5.5) is a distinct form with its own body grammar and the same rule.

A refined record definition remains a definition: it can be refined further or instantiated. A refinement that narrows a field's type to `void` — `a?: void?`, or `a: void?` at a restated group member (§5.11) — forbids the field's value in the refined type while keeping the field in the contract, the IS-A-preserving counterpart of removal (§5.9); `void` refines every type at a voidable position, the value set `{_}` being a subset of every voidable position's. Individual map entries cannot be refined because map keys are data, not definition fields.

**Body materialisation.** The refined body re-emits the complete inherited field set in source order; each field carries either its inherited state and value or the tightened ones. The materialised body is self-describing — consumers of resolver output do not walk the supertype chain to learn the field set. Inherited defaults and pins appear with their values even when the refinement did not refer to them.

**Resolution.** The refined entry's `chain.supertypes` records the source and the IS-A chain through it (§8.1), its value carries the source's constructor as its tag, and tightened fields appear in the materialised body. A refinement keeps its source's `name_type` (§5.2).


### 5.8 Supertype Composition

Supertype composition (`&`) is a construction tool: it combines one or more parent types with new fields into a new definition, declaring an IS-A relationship with each listed supertype. New fields are permitted; existing fields may be tightened.

```
address => { street: text  city: text  postcode: text }
contact => { name: text  email: text }
customer => address & contact & { loyalty_tier: text }
```

**Supertype field conflicts.** The supertypes MUST contribute disjoint field sets — a field name appearing in more than one supertype path is a resolver error, including diamond cases where the field traces to the same originating type through both paths. Two names are one field under the composed record's name type (§5.2), and **supertypes with different name types do not compose**: `json_record & address`, where `address` states nothing and so is `meta.field_name`'s, is a resolver error, and the record to compose is one declared over the same type.

**The trailing body.** The trailing `& { ... }` body is optional (`customer => address & contact` is valid). Body fields that match an inherited field are tightening entries and follow the refinement rules of §5.7 (including elided type expressions and the IS-A narrowing of a restated type); body fields that match no inherited field are new fields.

**Field ordering.** Supertypes contribute fields in left-to-right order as listed; each supertype's fields appear in their declared order. Tightening entries replace inherited fields in place; new fields are appended after all inherited fields. This is what lets a base's sibling precede a subtype's dependent field (§5.2), and a sibling may be a discriminator: the family rules ask of it what they ask of any required atom-typed field.

**Constructorness is inherited through composition.** Composition propagates the supertype chain, so an entry composing with something IS-A `meta.top` is IS-A `meta.top` and is therefore a constructor (§4.2): `net.iri_type => unicode.text_type & atom.specification & { ... }` is a constructor because `unicode.text_type` is. A record mixin such as `atom.specification` lends vocabulary without conferring the level, and a composition reaching no base kind — `box => <T> base & { value: T }` over a plain record `base` — is an open record, not a constructor, whatever its author intended.

```
atom.specification => { spec: iri }
net.iri_type => unicode.text_type & atom.specification & {
  spec?:           = "https://www.rfc-editor.org/rfc/rfc3987"
  schemes?:        net.scheme_set
  allow_relative?: boolean ~ true
  allow_fragment?: boolean ~ true
  normalization?:  = NONE
}
```

`net.iri_type` is a constructor, IS-A `unicode.text_type` and `atom.specification` directly. Its fields, in order: `unicode.text_type`'s six constraint fields, the last of them — `normalization` — tightened in place from a default to the pin `NONE`; `atom.specification`'s `spec`, tightened in place to an injected pin via an elided-type modifier; and the three new fields. Composition onto a FINAL record is a resolver error (§5.2), and a supertype that is ABSTRACT confers nothing on the composed entry, which states its own extension and is OPEN unless it says otherwise. A restated field that is a **group member** stays a member, in its own option: a `?` on its name is the in-option mark, which a restatement may drop and may not add (§5.11). A declared construction (`text_list => meta.array<text>`) is a binding record and has no vocabulary body to compose with; an open record's declared application is a `!meta.record`, which composes like any other (§4.3, §5.7).

**A restated field's annotations are the restatement's own followed by the inherited ones.** §6 puts an annotation on a `meta.record_field` and §8.1 preserves it; composition copies inherited fields whole and §5.7 lets a body entry restate one, and the two paths must agree. The rule: a restated field — in a composition body or a refinement body, including §5.7's modifier-only entry — carries **the restatement's own annotations, in source order, followed by the inherited field's, in source order**. Nothing is dropped and no name is treated as a key, because [TSON-DATA] §3.1 already makes an annotation name repeatable on one value and annotations are a list; and the restatement leads because order is already the precedence mechanism — every first-occurrence lookup reads it that way, so the nearer declaration wins at every such site without any of them knowing about composition. A restatement adds and never removes: a subtype rewriting an inherited `@doc` leaves the field carrying both, which is what "annotations are not removable" buys. Per-name replacement would need a cardinality on the declared annotation, which v1 does not have. The rule is what makes a count along a composed chain checkable, and it has read-side force wherever an annotation directs reading: a field that tightens presence and writes no annotation of its own inherits its source's, rather than silently losing it. It also fixes which fields carry a selector over a chain (§5.2): `meta.record.discriminators` is the base's statement and the member restates the field pinned, so no per-field mark travels and none has to be cleared.

**Parameterized operands.** Both `&` composition and `^` refinement operate on type references, which may carry arguments: an operand may be an application of an open record, its arguments naming the declaration's own parameters, `ok => <T> result<T> & { note: text }`. **An operand that is itself a parameter is a resolver error** — `<T> T & { name: text }` and `<T> T ^ { … }` — since a parameter has no field set to flatten until the record is applied; so is a constructor applied at an operand, `<T> customer & meta.array<T>`, a construction having no fields to contribute. An open record's `chain.parents` is a list of `meta.type_ref` values, which may carry arguments, because a parent may be written as an application: while the composing declaration is open, `result<T>` stands there with its arguments, and is substituted and closed with every other reference the record holds (§5.10) — so `ok<text>` reaches an IS-A edge to `result<text>` and not to `result<int32>`. The closed chain `supertypes` records names without arguments, being computed once every parent is a type. A closed type keeps no `parents` and carries no arguments in its chain, so a schema with no open parent produces a chain of bare names.

**An application at an operand is subsumed where it stands.** An application standing at a composition operand is absorbed by value — its record's fields, substituted, are the composing declaration's own — and is no entry: `dog => pet_of<dog_type> & { kind?: = "dog" }` over `pet_of => <T> pet & { pet: T }` is the hand-written `dog => pet & { pet: dog_type  kind?: = "dog" }`. Its IS-A edges are its template's written parents' — each closed parent, and for an applied parent the entry a declaration gives that application where one exists — and never the template's own name (§5.10): `dog` is IS-A `pet`. An operand, with a refinement source (§5.7), is the one place a declaration is found by its application. Joining a family through those parents brings its obligation: the member must pin the family's discriminators. A parent application nothing declares gives no chain edge, and an open record named *without* arguments at an operand is a resolver error, having fields only once its parameters are bound (§5.10).

**Resolution.** The composed entry's `chain.supertypes` records the listed parents and, transitively, their own chains (§8.1), complete to a fixed point; inherited fields are copied into the body's `fields` map in the order above, so the body is flat and a consumer never repeats the merge. Which operands were written is not recorded for a closed record: direct parents are the chain's minimal members, and operand order is in the flattened `fields`. §5.8's one name type over a composition's supertypes is judged at link, once every entry is ready (§3.4.1).


### 5.9 Subtraction

Subtraction removes fields from a construction and deliberately breaks IS-A — the resulting type is no longer source-compatible. Taxonomically it is a construction operation, not a refinement: like composition, it computes a new field set; unlike composition, it disclaims the contract. It is expressed as a trailing **removal clause** on a construction head:

```
removal-set = "-" ws "{" ws field-name *( separator field-name ) ws "}"
```

```
account => { name: text  email: text  password: text }
account_public => account - { password }
account_view   => account & { email?: text ~ "n/a" } - { password }
staff_public   => account & user & { badge: text } - { password  ssn }
```

The clause is head-level: a reader of the declaration line knows the contract is broken without scanning the body. It attaches only to construction heads — a bare source, or an `&` chain with or without a trailing body; a refinement head admits no removal clause (§5.7, §12.1).

Rules:

1. **Resolution order.** Supertypes merge first — §5.8's disjointness rule fires here unchanged, so subtraction cannot be used to resolve diamond conflicts — the body applies second, and removals apply last.
2. **Removing a nonexistent field is a resolver error** — symmetric with refinement's existing-fields-only rule.
3. **Source path does not restrict removal.** Removal operates on the merged field set, not on field provenance; since IS-A is already broken, there is no contract to violate.
4. **The body and the removal set are disjoint.** A removal naming a field the body itself introduces is a resolver error — adding and removing a field in one declaration is incoherent — and a body entry tightening a removed field is a resolver error. Body entries tightening the remaining inherited fields follow §5.7's rules.
5. **Removal and narrowing to `void` are distinct.** `- { field }` removes the field from the contract and breaks IS-A; `field?: void?` (§5.2, §5.7) keeps the field in the contract, forbids its value, and preserves IS-A.
6. **Empty subtraction does not exist**, by grammar: the removal set requires at least one name, and `source & {}` is composition-with-no-additions and preserves IS-A.
7. **Groups.** A removal may name a field-group member; the member leaves its option, an emptied option leaves the group, and a group the declaration rules would then refuse is rewritten as the spelling it equals (§5.11). A removal naming a dependent field's sibling removes the dependency's ground: the removal names the dependent field too, or the declaration is a resolver error.
8. **Constructors and parameters.** A subtracted construction whose head is a constructor is not one — it has no supertypes, so it is IS-A nothing and may not be applied — and a parameterised subtraction declares an open record whose field set is the merged set minus the removals, with no parents.
9. **FINAL does not reach subtraction.** A removal off a FINAL record is admissible: FINAL constrains the IS-A set (§5.2), which subtraction never joins, so the result is an unrelated type that happens to share a field list. Composition and refinement, which both mint an IS-A edge, are the two that FINAL refuses.

**Resolution.** A subtracted record is a record with no supertypes: `chain.supertypes` is empty — the subtracted type is not source-compatible — and nothing records what it was subtracted from, provenance being what was written and the source document having it (§4.1). A removed field's annotations are lost with the field. When a resolved form is read (§8.1), the empty `supertypes` is read as stated rather than inferred by diffing field sets against the parents.


### 5.10 Templates and Parameters

A record definition may declare parameters with `<>` after `=>`, following the definition mark where there is one (§5.2). A record with parameters is an **open record**, a **template**: `meta.record` carries `parameters?: [meta.param; 1..]`, and the kernel declares no other open form and no template. A reference names an open record only as the head of an application, which supplies an argument for every parameter:

```
pair     => <A, B> { first: A  second: B }
labelled => <T: text> { label: T  tags?: [T] }
box      => <T> { v: T }
```

`pair<uuid, text>` and `labelled<text>` are types; bare `pair` is a resolver error at every position (below).

**Where a parameter stands.** A parameter is a type, written where it stands as its name: a reference value, looked up in the record's `parameters` before the namespace (§3.3.2). It stands at a field's `type`, or as an argument of an application there or in a parent (`items: box<T>`, `<T> box<T> & { … }`), and nowhere else. A parameter anywhere else — a field's default or fixed value, a facet, an enum member, a size bound, a field name, a group member, `optional`, `voidable`, `role`, `extension`, `discriminators`, `name_type` — is a resolver error at the declaration, naming the slot. A field whose type names a parameter, whole or as an argument, carries no `~` or `=` value and is no selector, its type differing per application; a value that differs per application is a restatement where the application is declared, `created => response<order> & { status?: = 201 }`. The rest of the body is judged at the declaration, as any record's is: its field names under its `name_type`, its groups, its marks and its literal values.

**The body is a record body.** `<…>` precedes braces, a composition or a refinement — the explicit `<T> !meta.record { … }` among them — and before anything else is a resolver error at the declaration, whether or not anything applies it, the diagnostic naming the remedy: before container sugar or a container construction (`<T> [T]`, `<T> !meta.array { element_type: T  min_items: 1 }`), the application at the use site, or a refined constructor applied (`nonempty => meta.array ^ { min_items?: = 1 }`, then `nonempty<text>`); before a choice or a tuple, a discriminated record family (below) or a field group; before an instance of an ordinary type (`<T> !api { … }`), one value per instantiation; before a bare reference (`<B> pair<uuid, B>`, `<T> T`), a record restating the body, or the application written at each use — there is no alias template and no partial application. `parameters` is the list before the body: a construction of `meta.record` writing it as a binding is a resolver error.

**Forms over a parameter.** Inside an open record a form over a parameter is admitted only where it is a reference: `[T]` is `meta.array<T>` and `{K => V}` is `meta.map<K V>`, held as written until the record is applied and then constructed in place (§5.3). A size or a `?` (`[T; 1..]`, `[T?]`, `{K => V; ..10}`) sets a field no argument list carries, a tuple (`[T, text]`) is no construction over reference fields, and an untagged choice over a parameter (`( T | error )`) cannot be judged disjoint until it is applied (§5.4): each is a resolver error at the declaration, naming the form. The remedy is a refined constructor applied, the container declared and passed as the argument (`uuids => [uuid; 1..]`, then `page<uuids>` over `page => <T> { items: T }`), or, for a labelled alternative, a field group (`( body: T | problem: problem )`).

**Application.** At a reference slot of a schema document `H<args>` is a reference. Where `H` is an open record, resolving it **substitutes**: each parameter is replaced by its argument wherever it stands, and the result is a closed `!meta.record` without `parameters`, its chain computed from its substituted parents (§5.8), no field fact changing since no value is a parameter. Where `H` is a constructor, it **constructs** (§5.3). An argument may be any type, `void` among them, and a field typed by its parameter is then `void`-typed, admitting the void sentinel or nothing as its own marks say (§5.2). In a data document a reference is a name, and an application is a validation error (§4.2).

**Where an application closes.** Declared, `page_order => page<order>`, an application of an open record is the substituted record under that name, `@meta.application` on its key (§5.6, §8.2). At a composition operand or a refinement source it is absorbed by value (§5.7, §5.8). **At a type slot it closes in place**: `f: page<order>` holds `{ of: !meta.record { … } }`, the substituted record with no name — structural, a member of nothing, never tagged and carrying no chain — and one whose chain would name a declared type is refused (§5.2). An application at a slot is never looked up to a declaration, so `f: page<order>` and `f: page_order` are two types of one shape, the first structural and the second nominal. With a parameter among its arguments an application is held as written, whichever its head (`meta.array<T>`, `box<T>`), until the open record holding it is applied, and then closes where it stands by the same rules: a constructor's to its construction in place, one nested in another constructor's arguments included (`meta.map<text meta.array<T>>` closes to a map whose `value_type` holds the array in place), and an open record's to its record in place, `cursor: box<T>` in `page => <T> { cursor: box<T> }` holding `{ of: !meta.record { … } }` once `page` is applied. Only an application produces a record in place: an author's bare record at a slot stays refused (§5.2). A recursive application closes by §5.10.1, which refuses at its declaration an open record that could never be closed.

**A bound is written.** `<T: text>` writes a bound, the kernel's `meta.param.bound`; an unwritten one is absent, and nothing about a parameter is derived from where it stands, so a parameter is known in full at its declaration. An argument MUST name a type that IS-A the bound, past its alias hops (§8.3): `labelled<int32>` is refused at the application. A bound follows IS-A edges only — `date`, which has a text form without being `text`, fails `<T: text>` — and names a type, never a constructor: `<T: unicode.text_type>` admits nothing and is refused at the declaration, construction creating peers and not subtypes (§5.5). A parameter passed as an argument to a bounded parameter MUST be written with a bound that IS-A it, wherever it is that argument — a field's type, inside sugar or a constructor's application (`[labelled<T>]`), an applied parent: `rebox => <T: text> { b: labelled<T> }` resolves, and `rebox => <T> { b: labelled<T> }` is a resolver error at `rebox`, naming `labelled`'s `T: text`. An imported template's recorded bound binds the same way. A parameter passed to a constructor's argument slot needs no bound: the slot's `of` judges the argument that arrives when the record is applied (`<K> { m: {K => text} }`). An argument of an application closed while declarations are still being made ready is judged once every declaration is, an argument being a name at a type position, which orders nothing (§3.4.1), and a refusal is reported at the declaration that wrote the argument.

**No template is a type a position names.** No position names an open record bare: not a field, element, map key or value, tuple position, choice variant or argument, not a slot typed by a reference — `meta.type_ref` and `meta.ref<meta.record>` alike — not an operand of `&` or `^`, and not the record's own body. Each is a resolver error at the declaration that wrote it, and a data tag naming an open record is a validation error. An application is a type: the declaration naming it, or the record it closes to in place (above). Its IS-A edges are its template's written parents' — each closed parent, and for an applied parent the entry a declaration gives that application where one exists — and never the template's own name: no application is IS-A its template, an open record is in no `subtypes` index, and no reference holds one. `abstract` and the selectors on a template are each application's `extension` and `discriminators`, so `outcome => abstract <T> { ok: boolean =?  value?: T }` makes `outcome<text>` an abstract record dispatching over the declarations composing it, a selector making an application abstract as it makes a record so (§5.2); `final` on a template is a resolver error. A member reaches a family through an applied parent's edge, which names the entry a declaration gives the application (§5.8), so an abstract template applied in place — `s: shape<circle>` over an abstract `shape` — closes to an abstract record that nothing is a member of, and a family over a template is used through its declaration, `circle_shape => shape<circle>`. **A family is declared with records**: an abstract base states the selectors and the fields every member shares, a template composes it, and each member composes an application and pins the selectors:

```
pet    => abstract { kind: text =?  name: text }
pet_of => <T> pet & { pet: T }
dog    => pet_of<dog_type> & { kind?: = "dog" }
cat    => pet_of<cat_type> & { kind?: = "cat" }
holder => { p: pet }
```

`dog` and `cat` are IS-A `pet` through `pet_of`'s written parent, and a value at `p` is placed by its `kind`. An application of `pet_of` written at a use site would be an unnameable member of `pet`, and is refused (§5.2).

**Scoping.** Parameters are local to the declaring record; they do not escape and do not compose across `&`, and sharing a name is how two positions agree (`homogeneous_pair => <T> { first: T  second: T }`). **A parameter that shadows a schema entry — a type or a value entry, the document's own or an import's — is a resolver error**, so a name inside an open record is a parameter exactly when its `parameters` declare it, and a closed record holds none. A declared parameter the body never references is a resolver error. A template's parameter list is not one of §11.4's hygiene scopes: one author writes it whole on one line.

**What a parameter does not carry.** A parameter is never a head: `<T> { v: T<text> }` is a resolver error, so generic derivation over arbitrary heads is not expressible (§3.3.1). It carries no default argument, and no bound on the constructor an argument's type was built with: a bound is a type, and "any text-valued atom" is not one, construction creating peers and not subtypes (§5.5), so an author who wants a type admitted by `<T: text>` refines into the family (`stock_code => !text ^ { pattern: … }`) rather than constructing beside it. What a slot can say of a name it holds is which constructor built the type (`meta.ref<meta.record>`, §4.2), which type the value named is of (`meta.ref<method>`, §5.12), or which sibling's value names it (`kind!`, §5.2).

**In output.** An open record's resolved form is `!meta.record { parameters: [!meta.param { name: T }] … }`, each parameter its name and its written `bound`, its fields' types and `chain.parents` holding the parameters and the applications over them as written, and its `extension`, `discriminators`, `groups` and `name_type` each application's (§8.1). It is the one place a parameter appears.

The `_` token is not valid in field-type positions (§7.6); authors expressing "type to be filled later" use parameters.


#### 5.10.1 Self-Referential Types

Types may reference themselves:

```
node => { value: text  children: [node] }
linked_list => <T> { value: T  next?: linked_list<T> }
tree => <T> { value: T  children?: [tree<T>] }
```

A name at a type position orders nothing (§3.4.1, rule 2), so a self-reference is sound: the cycle a resolver refuses is a *wait* cycle (§3.4.1, rule 4), never a reference cycle. In an open record a recursive reference is a held application like any other — `tree`'s `children` holds `meta.array<tree<T>>` as written (§5.10) — and needs no special representation.

**Regular recursion.** Within a template body, a recursive application — direct or mutual — MUST pass each parameter through **unchanged**: `tree<T>` inside `tree`'s own body is well-formed; `weird => <A, B> { next?: weird<B, A> }`, whose recursive application passes its parameters in another order, is a resolver error at the declaration. This is the standard regularity restriction of recursive-type theory, and it belongs beside this section's other v1 boundaries (no head abstraction, no constructor bounds): without it, one application could demand an unbounded family of distinct instantiations, and no portable depth limit distinguishes that from legitimate depth. The check is static, at declaration time.

**Productivity.** A type MUST admit at least one finite value; a definition with no finite member — a required direct or mutual self-reference with no terminating alternative — is a **resolver error** at schema load, not a warning: `item => { inner: item }` describes no data, and validation against it can only ever fail, so rejecting it at the schema is the earlier, better diagnostic. A recursive reference is **guarded**, and the definition productive, when every cycle through it passes at least one position that can terminate: a field whose key may be omitted or whose type admits `_` (`?` on the name or on the type, §5.2), a voidable tuple position, an array or map position whose floor admits emptiness or whose elements may be void, or a choice with at least one non-recursive variant. `a: void` — a field that must be written and admits nothing — is the one declaration this rule refuses on its own, the record having no member at all (§5.2); `a?: void` is legal, emptying only the field. A required field typed by a reference that admits no name — `meta.ref<meta.alias>`, no entry past its hops being an alias (§4.2) — is refused here too. An ABSTRACT record with no subtype in the declaring schema's closure is **not** a productivity error (§5.2): its members are its importers' to supply. A group that must be chosen (§5.11) terminates when at least one of its options can be chosen without recursion — one option is chosen, so one terminating option suffices — and an option terminates when every member it requires does. Every entry is judged, and every type written in place where it stands; a template is judged with its parameters assumed inhabited (a bound names a type that is itself judged, §5.10, so an argument can always be chosen productive), and its applications need no re-check the entry did not already pass. Productivity is structural: whether an atom's *constraints* admit any value (a `min > max` interval) is a separate, value-level question (§5.3, §7.4).

**Recursion ties to a declaration.** Closing an application substitutes into the applications its record holds, so a recursive one would reach itself without end. A nested application equal to the one a declaration is closing — compared as canonical applications (§8.2) — names that declaration: over `node => <T> { value: T  next?: node<T> }`, `node_text => node<text>` holds `next: node_text`, and `tree_text => tree<text>` holds `children?: { of: !meta.array { element_type: tree_text } }`. Mutual recursion ends the same way, at the declaration being closed. A recursive application reached at a slot with no declaration closing it — `f: node<text>` in a closed record — has nothing to tie to and is a resolver error at the declaration that wrote it, naming the fix, `node_text => node<text>` then `f: node_text`: it is the one application an author must name.

**An open record that could never be closed is a resolver error at its declaration**, whether or not anything applies it. From an open record `R`, follow each application of an open record it holds where that application would close in place — at a field's type and every type slot inside it, one held in a constructor's application (`meta.array<node<T>>`) included, the fields `R` absorbs through an applied parent or a refinement source being its own. Where that reaches a recursive open record, one that reaches itself, on a cycle `R` is not on, closing any application of `R` would close that record in place with no declaration to tie to: `wrap => <T> { label: text  n: node<T> }` is refused at `wrap`, applied or not, and so is `wrap => <T> { v: T  n: node<text> }`, while `node` and two mutually recursive records are each on their own cycle. The error is the declaration's that holds the recursive record's application; one reaching it only through another (`outer => <T> { w: wrap<T> }`) is refused with that other. What closes takes the closed type as a parameter and is applied to a declared one: `wrap => <N> { label: text  n: N }`, then `wrap_text => wrap<node_text>`. Regularity (above) refuses the rest.


### 5.11 Field Groups

A record body may declare a **field group**: a parenthesised presence rule over fields of the record. A group lists **options** separated by `|`, each holding one or more labelled members separated as fields are, and at most one option may be chosen in conforming data. The field names are the discriminator; the wire form of instances is unchanged by grouping.

```
integer_type => meta.atom & {
  size?: numeric.integer_size
  ( min: integer | exclusive_min: integer )?
  ( max: integer | exclusive_max: integer )?
  multiple_of?: integer
  members?:     numeric.integer_member_set
}

endpoint => { ( host: text  port: uint16 | socket: text ) }
contact  => { name: text  ( email: email | phone: text )+ }
```

Each member is a `field-name`/`type-expr` pair, the type optionally voidable (`?` on the type, §5.2) and the name optionally marked (`?` on the name, below); a member's type may be a sibling reference, a member being a field (§5.2). Member labels share the enclosing record's field namespace and its name type (§5.2): a label MUST be unique across the record's plain fields and all groups' members, including fields contributed by supertypes (§5.8's disjointness rule extends to member labels) — so options are disjoint, and a field sits in one option of one group.

**Three rules decide validity.**

- An option is **chosen** when any of its members is present.
- A chosen option MUST contain every member not marked `?`. A `?` on a member's *name* marks it optional **within its option**: it may be missing once its option is chosen.
- A bare group admits **exactly one** chosen option. A `?` after the closing parenthesis makes the group **optional** — it may be missing as a whole, as an optional field's key may — and it then admits at most one. A group has no default or fixed form.

`( include: I | name?: N  type?: T )` therefore admits `include`, `name`, `type`, and `name` with `type`; it refuses an empty record, and `include` beside either of the others.

**`+` is sugar for "at least one of".** `( email: E | phone: P )+` desugars to `( email?: E  phone?: P )` — a group of one option, not optional, whose members are all marked. That option must be chosen, and it is chosen when any of its members is present, so the group admits every non-empty subset of them. `+` is allowed only where every option is one unmarked member; the desugared form is the kernel's, and `+` is the only way to write it. Like `?`, it is adjacent to the `)` it marks, and it is the one use the series has for a bare `+` ([TSON-DATA] §7.2.5).

| Rule | Spelling |
|---|---|
| exactly one of `a` and `b` | `( a: A \| b: B )` |
| at most one of `a` and `b` | `( a: A \| b: B )?` |
| at least one of `email` and `phone` | `( email: E \| phone: P )+` |
| `include` alone, or at least one of `name` and `type` | `( include: I \| name?: N  type?: T )` |
| both `a` and `b`, or neither | `( a: A  b: B )?` |
| `a` requires `b`; `b` may appear alone | `( b: B  a?: A )?` |
| `a` and `c` each require `b` | `( b: B  a?: A  c?: C )?` |
| either `host` and `port`, or `socket` | `( host: H  port: P \| socket: S )` |

**Each presence rule has one spelling.** A group is refused, at schema load, wherever it restates what plain fields or another group already state, and the diagnostic SHOULD name the spelling it restates:

- **The only member of an option takes no `?`.** It is present exactly when its option is chosen, so the mark changes nothing: `( a?: A | b: B )` is `( a: A | b: B )`.
- **A written group of one option is optional, with at least two members and one of them unmarked** — `( b: B  a?: A )?`. Every other group of one option restates something. Bare with an unmarked member, it is plain fields: `( b: B  a?: A )` is `b: B  a?: A`. Bare with every member marked, it is what `+` desugars to, and is written that way. Optional with every member marked, it is optional fields: `( a?: A  b?: B )?` is `a?: A  b?: B`, and `( a: A )?` is `a?: A`.
- **`+` takes options of one unmarked member each.** `( a?: A | b: B )+` and `( host: H  port: P | socket: S )+` are refused.

A declared group therefore has at least two members, and every option can be chosen on its own, so a declared group is never unsatisfiable and loading needs no search. There is no form for any number of options: options free to be chosen together are independent, and such a group is always its options written as separate groups or plain fields. Groups do not nest, and a field under two presence rules has no spelling — it sits in one option of one group.

**Member positions are deliberately bare.** A member takes a name, optionally marked, and a type expression, optionally voidable, and nothing else: the `~`, `=` and `=?` modifiers are parse errors on a member — selection belongs to the label, presence belongs to the group, and a group never injects. A voidable member written as `_` is **present** and chooses its option; at a member that is not voidable a written `_` is the ordinary refusal (§7.6). A group is not a type expression: it cannot appear in field-type, element, argument, or variant positions, so multiplicity around a group (`[( a: T | b: U )]`) is not expressible; repetition of alternatives is written as an array of a named choice type (§5.4). A dependent field whose sibling is a group member is checked against the member's presence as §5.2 states: absent because another option is chosen or none, the sibling leaves a dependent field written beside it a validation error.

**Resolution.** Groups flatten. Each member becomes an ordinary `meta.record_field` in the body's `fields` map — in source order, contiguous with the group's other members — with `optional: true` and `role: FREE` whatever the group says, and `voidable` as written. The grouping is recorded in the body's `groups` list as a `meta.field_group`:

```
field_group => {
  members:           [[text; 1..]; 1..]
  optional_members?: [text; 1..]
  optional?:         boolean ~ false
}
```

`members` holds one list per option, options and their members in source order; each slot is `text` because a member names a declared field by its declared spelling, judged by the record's name type and never a different one (§5.2). `optional_members` names the members marked `?`, and is absent rather than empty where none is marked, so a group has one spelling. `optional` is the group's own `?`, omitted in output at its default (§8.1). So `( min: integer | exclusive_min: integer )?` resolves to `!meta.field_group { members: [[min] [exclusive_min]]  optional: true }`, and `( email: E | phone: P )+` to `!meta.field_group { members: [[email phone]]  optional_members: [email phone] }`. The in-option mark is recorded on the group and not on the field, for three reasons: `meta.record_field.optional` keeps meaning "the key may be missing", which stops being true of a member the moment its option is chosen; a consumer that ignores groups reads every member as optional, which is loose but never refuses a valid document, where the alternative would have it refuse one; and a member's presence stays the group's alone to state. The flattened fields-plus-groups form is canonical: group membership is fully derivable from `groups` in one local pass, and implementations SHOULD compile it into a per-record lookup at schema-load time, per the eager-resolution convention of §5.2 and §7.4 — the output form is canonical, not operational.

**Validation.** For each group of a record type, after ordinary field validation, the validator finds the chosen options, reports each chosen option's missing members, and then counts: a group that is not optional errs with no option chosen or with two or more; an optional group errs only on two or more. A present member is validated as an ordinary field of its declared type. Diagnostics stay local, and SHOULD read in the group's own terms: "`port` chose (host, port), which needs `host`"; "`include` and `type` choose two options; exactly one is allowed"; "none of (email, phone) is present; at least one is required".

**Refinement and composition.** In a refinement or composition body, a restated member **stays a member**, in its own option. It may drop its `?`, which narrows, and may not add one; a member of a `+` group is restated as it is written there, without a `?`, and that changes nothing. Its type slot may move voidable true → false or narrow the type (§5.7), as at any field. A restated member narrowed to `void` and not voidable (`a: void`, which admits no value and refuses `_`) can no longer be present: an *unmarked* one makes its option unchoosable, and a *marked* one drops out of its option. A group left with no choosable option is unsatisfiable, as a group that must be chosen with every member so narrowed always was. Narrowed to `void?` a member can still be written, as `_`, and written so it is present and chooses its option — a member that carries presence and nothing else. A restated member may take `= v`, which is checked when written and **never injected** — the one pin that does not inject, because an injected member would be present, and presence is what chooses the group's option. `~ v` stays refused on a member: a default is a value only a missing key reaches, and whether a member's key may be missing is the group's to say. A member reachable by refinement may not acquire a selector (§5.2). A body entry may also restate a group: the restated group MUST have the same options, with the same member labels in the same order (member type expressions restated verbatim), and may tighten it from optional to not optional; the reverse is a resolver error, and changing membership is a resolver error. Supertypes contribute their groups whole; the composed entry's `groups` lists inherited groups in supertype order followed by the body's own. A field belongs to at most one group — guaranteed by label disjointness.

**Removal.** A removal clause naming a member (§5.9) removes it from `fields` and from its option; an emptied option leaves the group, and a group with no option left is removed outright, with nothing left to occupy its logical position. A group the declaration rules above would then refuse is **rewritten as the spelling it equals** — plain fields or an accepted group, one of which always exists. The simplest case is a group reduced to a single one-member option: it is dissolved, the surviving field taking the group's `optional` as its own (an unmarked name where the group had to be chosen, `?` on the name where it was optional; the field's own voidability is unchanged). The declaration rules are thus an invariant of resolved output as well as of source: no resolved record carries a group they refuse.

**The labelled-sum pattern** (informative). A record whose entire body is a single group that must be chosen, each option one field, admits exactly one field — a labelled sum in record clothing:

```
timestamps => { ( created: timestamp | modified: timestamp | accessed: timestamp ) }
```

An instance is `{ modified: "2026-05-21T13:05:00Z" }` — quoted, since a datetime contains colons ([TSON-DATA] §7.1); the label is both the semantic role and the discriminator. Where `meta.choice` (§5.4) discriminates by variant type name — via a `!variant` annotation, required except where the choice is disjoint under the encoding's discrimination — and requires distinct variant types, a single-group record discriminates by label and permits variants of the same underlying type, needing no disjointness and no tag. The pattern's kind is PRODUCT; host bindings MAY recognise the shape (one such group, no other fields) and lower it to a native sum, and where an option holds several fields the variant it lowers to is record-shaped. [TSON-GUIDE] discusses the pattern and the design history.


### 5.12 Values by Name

A schema may hold a value declared elsewhere and write it by its name. The slot that holds such a name is typed by a reference over a type that is no constructor, `meta.ref<T>` (§4.2), and the name is a **qualified name**: segments joined by `.`, the first naming an entry and each further one a selection inside what the name has reached so far. Every schema may declare and hold values (§4.1), so the need arises wherever one entry addresses another — an HTTP binding naming the method it serves, a configuration naming a profile:

```
!!id:"https://example.com/service.tn"
!!import:"https://tson.io/2026/40/m/meta.tn"
!schema {
  method_name => meta.identifier
  method      => { request?: meta.ref<meta.record>  response?: meta.type_ref  errors?: [meta.ref<meta.record>] }
  interface   => !meta.namespace { key_type: method_name  value_type: method }
  http_verb   => !enum [GET POST]
  binding     => { method: meta.ref<method>  verb: http_verb  route: text }
  bindings    => !meta.namespace { key_type: meta.identifier  value_type: binding }
}
```

```
orders => !interface { place_order => { request: place_order_request  response: order } }
routes => !bindings { post_orders => { method: orders.place_order  verb: POST  route: "/orders" } }
```

**The qualified name.** A qualified name is one unquoted token by [TSON-DATA] §7.1's token profile — `.` is a token character and the lexer emits `orders.place_order` as one token — parsed into segments by the position that reads it, each segment a [TSON-DATA] §7.7 identifier. **Every name resolves from the root** (§3.3): the first segment names an entry of the document's root namespace, locally or through imports, alias hops followed (§8.3); each further segment selects, in order, **a member of a nested `!schema` map** (`geo.address`, §4.1), **a key of a namespace value** (`orders.place_order`), or **a field of a record value** (`geo.home.at`, where `geo.home` is a value entry) — a value of a named product, the record being a namespace whose keys its type fixes. A map that is no namespace is not entered (`field_map`'s among them, so no qualified name selects a field *declaration*: `foo.bar` and `foo.fields.bar` for `foo => { bar: text }` are refused), and nothing else is. Every hop is written — nothing is skipped and nothing is looked up lexically, so a name means the same wherever it stands in a document — and the grammar is names only: no `[n]` (an element of a list is not a thing a schema declares, and `[` is structural in the lexer), no quoted segment (`api."/x".request` is three tokens, and a text key is data, declared nowhere), and no empty segment (`orders..place_order` is three tokens under the range rule, and a leading or trailing `.` is refused by the segment grammar). A name that wants a position rather than a named thing is a JSON Pointer in a quoted string, not a widening of this grammar. A map is entered only where it is a value of a namespace type, whose `key_type` is an identifier family, so a key outside §7.7 under a wider key profile cannot be written as a segment. A qualified name carries no argument list: a value is named and never applied.

**The judgement.** The slot declares the type: a value of `meta.ref<T>`, `T` no constructor, names a value whose type is `T` or IS-A it, judged after following alias chains and compared by canonical identity — declaring schema and entry — each type named in the namespace of the schema that declares it. The referent is **found first and judged after**, never chosen among candidates, and its type is judged before its value, so a name wrong by type is refused for its type whatever the value holds. A **type entry admits no further segment**: projecting into a record type's fields (`order.sku`) is a different capability and is not this one. An open record is no referent, at a reference over a constructor as at one over values (`request: box` over `box => <T> { v: T }` is refused, a template denoting no value until applied); what a document names there is the application a declaration gives it, `bt => box<int32>` then `request: bt`. A framed name (`@doc:"x" orders`, `!meta.record orders`) is refused: a name's type is its referent's, and an annotation on it would have nowhere to go. A quoted token is text, never a name. A segment selecting a **dependent field** (§5.2) reaches a value of the type its sibling names in the same record value — the sibling's pin, else what the value holds there, else its default — and the walk goes on with that type; a sibling naming no type a value can be read against is a resolver error at the slot holding the name, and so is a sibling the value holds nothing at, with no pin or default. Each refusal names its own step:

| Written | Result |
|---|---|
| `method: orders.place_order` | accepted: `orders` is an `interface` entry, `place_order` a key of its map, the value a `method` |
| `method: orders` | refused: an `interface` value, where a `method` is wanted |
| `method: orders.cancel_order` | refused: `orders` has no key `cancel_order`; the keys are listed |
| `method: orders.place_order.nope` | refused: a `method` value has no field `nope` |
| `method: place_order` | refused: no entry `place_order`; a member is reached through its entry |
| `method: place_order_request` | refused: a record type, where a `method` value is wanted |
| `method: hs.h.headers.accept`, `headers: {text => method}` | refused: a map that is no namespace is not entered |
| `method: shop.reads.fallback`, `service.fallback?: method` absent in the value under `reads` | refused: a field the value does not state names nothing, as a key the map lacks does |
| `response: orders` | refused: a value entry, where a type is expected (§4.1) |
| `request: { … }` at `request?: meta.ref<meta.record>` | refused: a reference-typed slot of a value body holds one bare name (§5.6) |
| `request: box<text>` at the same slot | refused: a reference-typed slot holds a name, and an application is none; name a declaration of it (§5.6) |

**A field of a record value reached past a key** selects in that value as a key does in a map: the name reaches what the field holds, and is judged by the field's declared type. `shop.orders.methods.place_order`, where `service.methods` is typed by `interface`, reaches the method the interface value holds under that key.

**Where the rule stands.** A value by name stands at a slot typed by a reference naming values and nowhere else: a slot typed by a record holds the record's value inline (`method: { response: order }` is plain data of `method`), and a bare token at a record-typed slot in a schema document fills the record's positional field where that field is atom-typed, and is refused as no record otherwise (§5.6). **The rule reaches a data document.** A data document has a namespace — the one its `!!import`s bind (§3.3.4) — so a name written at a `meta.ref<T>` slot in data is judged by the same walk over it: `which: o2` at `meta.ref<order>` reads where `o2` is a value entry of `order` or of a subtype in the closure, and a name reaching nothing, a type, or a value of another type is a validation error; a host receives the referent with the name, the referent being the schema's own value, which the document only names. A field's default or pin at such a slot is a qualified name judged at link by the same walk (§5.2). An imported entry's names are judged once, when its own schema links, in its own namespace. Resolved output writes a name **as written** (§8.1): the referent is judged at link and delivered to a host with the name, and the two readings a later revision might give a name — substitution, where the value replaces the name and nothing downstream can tell the spellings apart, and identity, where the name's referent is the value's identity and what a coverage check reads — both stay available. Neither is decided here: this section is the lookup, the judgement and the grammar, which both share.

**What it is, against what it replaces.** A type's name at a `meta.type_ref` slot, a name at a `meta.ref<C>` slot over a constructor, and a qualified name at a `meta.ref<T>` slot over values are one thing — a value of the reference atom, a name that must resolve (§4.2) — and differ only in the judgement: over a constructor the name must resolve to an entry whose value's type is the constructor or IS-A it, and it may carry an argument list where an application is open; over any other type it may carry segments and must resolve to a value whose type is `T` or IS-A it. Types live only in schema maps, so a type's name is a path of map members; a value may live in collections, so a value's name may go on into them. The dotted-names family this spelling belongs to is the **qualified name** — Java's, XML's QName, Python's dotted name, protobuf's fully-qualified name, TOML's dotted keys — where each segment is a name and nothing is a position; the address family (RFC 6901 JSON Pointer, RFC 9535 JSONPath) answers *which position* and is the one the series' diagnostics already use.


## 6. Annotations as Types

[TSON-DATA] §3.1 defines annotation syntax and preservation. This section defines annotation semantics: an annotation is a typed metadata attachment, resolved and validated against a type of the annotating document's namespace.

Annotation values are always data values — concrete values, not type definitions — both in data values and within the type-definition grammar. Placement follows [TSON-DATA] §3.1; the resolver preserves annotations in their authored positions. The type-definition grammar adds one position: in `field-def` (§12.1), annotations precede the field name and annotate the field itself, mapping to the `meta.record_field` in resolver output.

In schema declarations, an annotation may stand at two positions with two meanings, and the resolver does not hoist between them. An annotation immediately preceding the declared name binds to the name — the schema-map key — and is **metadata about the declaration**: documentation of the entry, provenance, lifecycle (`@deprecated name => …`), and the resolver's own derived marker (`@meta.application`, §8.1, §8.2) lives here. An annotation after `=>` binds to the entry's value and is metadata *inside* the definition. Both positions are preserved in resolver output at their authored places (§8.1): a key annotation on the output schema-map key, a value annotation on the definition value. A **checked** annotation (below) is an assertion in either position and is honoured at both: a processor MUST consult both spellings, and MUST NOT ignore one with no diagnostic.

**Annotations are types.** An annotation `@T` (or `@T:value`) names a type `T` and attaches it as metadata to the surrounding value. `T` is a qualified name ([TSON-DATA] §3.1), resolved from the root in the **annotating document's namespace** (§3.3.3): for a data document the closure its `!!import`s bind, for a schema document its own entries and its import closure — `@doc` the kernel's root `doc`, `@atom.ordering:TOTAL` the kernel's `atom.ordering`, `@title:"…"` core's `title`, `@expires:"…"` a type the schema declares itself. The value is validated against `T`'s contract:

- For `void`-targeted `T` (a type whose resolved body, after following its alias chain, is `void` — such as `meta.annotation`, `atom.numeric` or `deprecated`), the annotation form is `@T` with no colon and no value. Bare `@T` is shorthand for `@T:_`; the resolver fills the implicit `_` and validates against `void`'s contract (§4.2) — presence is the information.
- For any non-`void` `T`, the form is `@T:value`, where `value` is a single data-value conforming to `T`: `@doc:"User's full name"`, `@atom.ordering:TOTAL`, `@title:"Order"`.

Any type in the annotating document's namespace can be used as an annotation — there is no separate annotation namespace. **An annotation whose name does not resolve in that namespace is a resolver error**, for the valueless form as much as the valued one: `@doc` in a document whose closure lacks the kernel fails exactly as `@doc:"..."` does — the bare form is shorthand for `@T:_`, and `_` still needs a `void`-targeted `T` to validate against. Annotations are not free-form markers under a schema; the preserved-uninterpreted treatment belongs to schemaless processing alone ([TSON-DATA] §3.1). An annotation typed by an abstract entry — `meta.top`, `meta.type` or a base kind, or any other abstract record — is a schema error in either position, a value at an abstract position naming no subtype. A type annotated with itself on its value (`note => @note:"x" text`) is a wait cycle (§3.4.1); the kernel's own `meta.annotation` is an alias of `void` and annotates nothing.

**What an annotation may do.** An annotation never changes a value, its type, or whether it is valid: validation is over values, and a schema with every annotation erased admits exactly the same values. That is the criterion for the home of a fact — *an annotation is the right home exactly when the mark changes no value's validity* — and it is what keeps an encoding's projection concern out of every processor's type model. Within it an annotation may do two further things; the series has one of the first kind and none of the second:

- It may carry **load-time force**: a *checked* annotation is verified against a fact the resolver derives, with two outcomes and no third — verified, or a resolver error at schema load — and the section defining it states the check. `@meta.disjoint` (§5.4) is checked against derived disjointness, and is the category's one member.
- It may be a **representation directive**: it directs how a *class* of encodings represents a value, and force is confined to the encodings that claim it — in every other encoding, and in the model, it changes nothing. A directive's **class** is named, not inferred: it is the set of encodings whose encoding-rules documents declare, by the directive's name, that they consume it, and each such document MUST say so where it states its rules. A class is never defined by the behaviour the directive directs — "the encodings that flatten" names no class — and no encoding is privileged, TSON text being one encoding beside [TSON-JSON]. An encoding-rules document MAY bind projection behaviour to a schema-side annotation declared for it on these terms; no such annotation is declared in this revision, and the category stands empty.

**Two facts that are not annotations, and why.** How a record may be realised (`abstract`, `final`) and which fields its members are selected by (`=?`) each change which values conform — erase the first and a direct instance becomes readable at every position typed by the record; erase the second and every untagged document at a member-dispatched position stops validating, in every encoding, so there is no other encoding for the mark to change nothing in. Both are therefore §12.1 syntax lowering into the kernel's own `meta.record.extension` and `meta.record.discriminators` (§5.2), never annotations: the resolver consumes them into the body, they appear in no output annotation channel, and their names are reserved at exactly one position — a definition mark between `=>` and the type definition — and are ordinary identifiers everywhere else ([TSON-DATA] §7.7). No bundled document declares `abstract`, `final`, `sealed` or `discriminator` as a type, so `@abstract` written as an annotation is the ordinary unresolved-name error above.

Everything else an annotation can be is *advisory*, and the bundled documents declare these (§9):

- **The kernel's `doc`**, whose text is CommonMark (version 0.31.2), with no extensions. CommonMark is versioned where "markdown" has no specification, so two renderers agree on what a doc says, and a doc that uses one vendor's tables, task lists or autolinks reads as their literal text. Raw HTML in a doc is never executed, and a renderer need not render it: a doc is written by whoever published the schema, and rendering one fetched from elsewhere must not become a way to run its author's code. The rule refuses nothing — every string is valid CommonMark, so no schema stops loading and no processor checks anything; it says how the text is displayed, which is all an advisory annotation may do.
- **The kernel's classifying markers**, under `atom`: `@atom.ordering` over `!enum [NONE PARTIAL TOTAL]`, `@atom.bounded` and `@atom.exact` over `boolean`, and the void marker `@atom.numeric` — the ISO/IEC 11404 datatype properties the libraries state their atoms with.
- **The kernel's `meta.annotation`**, an alias of `void`, which signals "intended as annotation metadata" so tools can surface available annotations, carries no runtime force, and leaves a type without it no less usable as an annotation.
- **Core's author annotations**: `@title`, plain text, being a name; `@comment`, a note for a schema's maintainers rather than its readers (JSON Schema's `$comment`) — where `@doc` is what a documentation tool shows, `@comment` is what it leaves out, its format left unstated; `@examples`, a list of *text*, each an example as a reader is shown it, conventionally the value written in TSON notation (nothing parses an example or checks it against the type it illustrates — an annotation's position hands its value no type to be read by); and the void presence markers `@deprecated`, `@read_only` and `@write_only` (a bare mark is the whole statement — absence is the default, so there is no `false` to write; `@deprecated`'s reason, where there is one, is `@doc`'s to give). A schema importing the libraries without `core.tn` may declare annotations of those names itself.

**The resolver-attached annotation.** One annotation is attached by the resolver rather than written by the author, and an author's spelling of it is a resolver error. A declaration naming an application of an open record carries `@meta.application:box<int32>` on its key — the application the entry is the instantiation of, written as the reference it is (§8.2) — since its value, a record, states its fields and its chain and not the head it was substituted from; no other entry carries it, and a construction carries none, its value stating its constructor and its arguments. It is derived and never written by a declaration, and is **read as stated** when a resolved form is read, being the one fact the entry's value does not state (§8.1, §10.1). It is defined in the kernel and follows the same resolution rules as user-written annotations. No annotation records an alias: a use site names what the author named, and the chain is stated as written (§8.3).


## 7. Text Encoding Rules: Data Values Under a Schema

This section is the text format's **encoding rules**: how the type system's values are carried in TSON text. Every encoding of the type system owns a section shaped like this one — its atom lexical forms, its container syntax, its treatment of void, and its discrimination predicate over the derived disjointness fact (§5.4). This section states TSON text's rules; every other encoding states its own against the same encoding-independent model in its own encoding-rules document — [TSON-JSON] is the first, and the two agree on every rule the model states, differing in spelling alone.

[TSON-DATA] defines the syntax of data values and their schemaless interpretation. This section defines how a data document's `!!import`s bind it (§7.1) and how an active namespace changes interpretation: tag resolution (§7.2), atom parsing in place of base type resolution (§7.3, §7.4), sets (§7.5), the void sentinel (§7.6), resolver behaviours at typed positions (§7.7), and declared positions (§7.8).


### 7.1 Data Documents and `!!import`

A data document's `!!import` directives name the schemas whose types are available for `!name` tags in it. Each directive value is an IRI-reference identifying a published schema document, resolved through the schema library (§10).

```
!!import:"https://example.com/people.tn?sha256=c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5"
!person { name: Alice age: 30 }
```

**The referent is a schema document.** An `!!import` URL resolves to a schema document — never to a resolved form, whose resolver-derived fields cannot be verified from the document alone. Resolved forms are read only as the caller's explicit choice (§8.1, §10.1).

**Several imports are one namespace.** A data document imports as a schema does: its imports are read under one namespace over their closures, a name two of them declare differently being §2.2.3's collision, and the document's values are read in that one namespace, which no value opens, widens or replaces. A read under several imports is linked as a schema with no entries of its own importing each, and MAY be compiled once per set.

**The directives name a namespace, not a root contract.** `!!import` supplies the vocabulary against which tags resolve; it does not assert the document's root type, and a schema has no privileged entry point. The value names its own type by annotation, and **under `!!import` the root MUST name its type**: an unannotated root under a bound namespace is a validation error in every mode, since under a schema every value is typed by its position or by its tag (§7.3) and a root has no position to be typed by. There is no "vocabulary-only" reading of a document under `!!import` — a value read by base type resolution is a schemaless value, and a document is one thing or the other from its header. The same holds at every position whose type is `meta.declared`: a value there names its type or is invalid (§7.8).

A document with no `!!import` directive has no type vocabulary: base type resolution ([TSON-DATA] §4) applies, type annotations are limited to the built-in vocabulary of [TSON-DATA] §5, and any other type annotation is a resolver error ([TSON-DATA] §3.2). The built-in names denote the types the kernel and the libraries declare — the kernel's for `!text`, `!integer`, `!bytes` and the rest of its root, `numeric.tn`'s, `temporal.tn`'s and `net.tn`'s for their families ([TSON-DATA] §5.5, §5.6) — so a schema that wants them imports the document that declares them, or `core.tn` for all, and a schemaless `!mac` keeps its meaning when its document moves under a schema whose closure holds `net.tn`.


### 7.2 Tag Resolution

In data values, a type annotation — a **tag**, `!name` — marks **instantiation**: the value is concrete data conforming to the named type. The name is a qualified name ([TSON-DATA] §3.2), resolved from the root in the namespace the document's `!!import`s bind — never against definitions within the same document (§3) — `!order` naming a root entry and `!geo.address` the member `address` of the nested map `geo` (§4.1). A tag carries no argument list; a generic type reaches a tag through a name some declaration gave it (§5.10).

**The built-in vocabulary does not apply in schema scope.** When a namespace is bound, all tags MUST resolve through it; a built-in annotation name no imported schema's closure declares is an unresolved-type error. Schemas wanting `uuid`, `bytes`, `datetime`, and the other built-in names import the document that declares them — the kernel, a library, or `core.tn` ([TSON-DATA] §5.5, §5.6) — or define them locally. This is the normative statement of the scoping rule; [TSON-DATA] §5.1 restates it for schemaless processors. A document with no `!!import` falls back to schemaless processing (§7.1).

**Records are closed under their type, with no exception.** When a schema is in scope and a record's type is known, the record MUST contain only fields defined by its type; fields not present in the type definition are validation errors — a field name that is no value of the record's name type among them, since every declared name is one (§5.2), the diagnostic under a declared name type naming the type and its reason. A document's field names are matched against the declared ones in the name type's form (§5.5, §7.7), and the record that reads a value judges its names: a record nested in one with a relaxed name type judges its own. This applies to directly-typed records (`!person { ... }`) and structurally-positioned records (a record at a record-typed field position). Nothing in a schema makes a record absorb fields its type does not declare: open-ended data is carried by a declared map-typed field (`extras: {text => text}`), whose entries are keys and not names ([TSON-DATA] §2.5, §2.6) and are written the same way in every encoding. Schemaless records have no closure rule. Closure is what makes schema evolution a discrete operation: every published schema version is a precise, immutable contract about what fields exist (§3.5).

**Type expression syntax is not available in data values** ([TSON-DATA] §3.2). To annotate an array or map value with a named type, declare a named type in the schema (`int_list => [integer]`) and write `!int_list [1 2 4 8 32]`. A reference carrying arguments is never a tag, and in a data document no value either: an application there is a validation error (§4.2).

**A type written in place is named by no tag.** A value at a position whose type is written in place (§4.2) — `tags: [text]`, `f: page<order>` — carries no tag naming it and admits none: the type has no name to write and nothing is IS-A it, so a tag there is a validation error, and a reader recovers the value by the type's own form. At a choice a tag still names a variant that is a name (§5.4). A diagnostic locates such a type by where it is written, a field path, and shows it as its form, never by a name it does not have (§8.2).

**Validation follows what the name is.** `!T value` asserts that the value conforms to `T`, and conformance is determined by `T`'s own definition. An **atom instance** validates a single token by its parsing contract (§7.4): `!age 42`, never `!age { ... }`. A **product** validates a record against its field list; a **choice** validates any conforming variant. A **constructor** is a record-shaped type, so it validates a record against its constraint-field vocabulary: `!numeric.integer_type { min: 0  max: 255 }` is a record conforming to `numeric.integer_type`'s fields, receiving ordinary record validation; family coherence between bindings (e.g. `min ≤ max`) is a compilation concern (§8), not data validation. A tag naming a **value entry** (`!orders`) is refused: a value entry is no type, and a position cannot be typed by it (§4.1).

**Subsumption at typed positions.** At a position whose declared type is `T`, a value annotated `!S` is valid if and only if, after following both alias chains to their terminal entries (§8.3), `S` is `T` or `T` appears in `S`'s `chain.supertypes` (§4.1, §8.1). A subtracted type has no supertypes and does not stand where its source is expected. An admitted value validates against `S` in full: closure, constraints, and defaults are `S`'s; the position's `T` contributed admission alone. **What an unannotated value at such a position means is `T`'s own extension fact** (§5.2), and there are three readings. Where `T` is OPEN or FINAL, the value is exactly `T`: there is no structural recovery of `S`, per the once-only reading of form ([TSON-DATA] §2.4), so the annotation carries information precisely when `S` is a proper subtype — a field typed `person` admits `!employee` where the schema declares `employee => person & { ... }`, and without the annotation the value is a `person`, full stop. Where `T` is ABSTRACT and names no discriminators, `T` has no direct instances, so the tag is REQUIRED: an untagged value is a validation error naming `T`, whose diagnostic SHOULD name the subtypes, and a tag naming `T` itself is refused, no value satisfying it. Where `T` is ABSTRACT and names discriminators, the value is placed by **reading the marked fields** at the types `T` declares them — the one set of types known before dispatch — and matching the value, or the tuple of values in declaration order, against the pins its members state; the matched member becomes the effective type and the whole value validates against it, which re-verifies each pin as an ordinary FIXED check and makes the dispatch read and the validation read agree by construction. A missing selector, or a value no member pins, is a validation error, the latter SHOULD naming the pinned alternatives; the tag is then optional and MUST name the dispatched member or a subtype of it where written — one selector per position, and the FIXED check is what forces the two to agree. This notation reads a member-dispatched position on those terms and so does every other encoding ([TSON-JSON] §6.1.5); the two differ in spelling alone, since the selectors are the model's facts and not any encoding's. A record's fields have no significant order in this notation but for a dependent field's free sibling ([TSON-DATA] §2.5), so a reader locates the selectors by lookahead over the record; an encoding may require them to lead. Choice-typed positions discriminate by variant membership (§5.4) and declared positions by the closure (§7.8) — the same surface gesture, an annotation selecting a more specific type than the position promises, under their own membership relations; this rule governs every other typed position. It is also what admits resolver output at the `meta.declared`-typed values of `meta.schema`: every entry names its type by its tag (§7.8, §8.1).

**There is no construction in data.** `!C { bindings }` produces a new type only in the schema grammar (§5.5); the same surface shape in a data document is a record that *describes* constraints — which is precisely what resolver output stores as an entry's value (§8). The two category errors are symmetric, in data as in schema: a constructor never types its family's atom values (`!numeric.integer_type 42` is a type error — the value type is the instance, `!integer 42`), and an instance never types records (`!age { min: 0 }` is a type error — the constraint vocabulary belongs to the constructor).

**No parameterized annotations.** Resolver-output bodies (`!meta.array { element_type: person }`, `!meta.map { key_type: … }`, §8.1) are annotations by ordinary parameterless constructors (§4.2), validated by ordinary record validation: each field against its declared type, a type slot against the type-slot choice — a name judged a member in the reading closure (§4.2), or, for a value of `meta.schema` read as a resolved form, where that schema links (§8.1), or a `{ of: … }` record read as the construction it holds. No special rule remains — a tag naming an open record is a validation error, without exception (§5.10).

**Schema values.** The type annotation `!schema` marks a map as a schema value — the kernel's root `schema`, an alias of `meta.schema`, the namespace from `meta.type_name` to `meta.declared` (§4.1), whose value is read as the map it describes and whose members declare (§4.1). It appears on schema documents (§2.1) and on documents that carry resolved schema structure, most notably resolver output (§8).


### 7.3 Atom Parsing Replaces Base Type Resolution

When a schema is in scope, base type resolution ([TSON-DATA] §4) does not apply at all: every value is typed by its position or by its tag, each declared atom type owns its own parsing contract (§7.4), and there is no third way to read a token. The tokens `true` and `false` have no special status when a schema is in scope — their meaning is determined entirely by the position's type, and at the kernel's `boolean` they are enum members like any other (§7.4). There is no `null` in the notation ([TSON-DATA] §4.4): the token `null` at a `text` position is the string `null`, at an `int32` position a parse error, and at a `void`-typed position — any instance of `meta.void_type`: the kernel's `void`, or one a schema constructs for itself (§4.2) — a validation error, since `void` admits the void sentinel `_` and nothing else. A JSON document's `null` reaches the model as void through the JSON reader ([TSON-DATA] §6), where the position decides whether a void value is admitted (§7.6); no keyword in this notation is needed to carry it.


### 7.4 Atom Token Parsing

Each atom type owns its parsing contract. When a token appears at a position whose type is an atom, the atom's parser takes the token and produces either a typed host value or a parse error; the atom's constraint record is applied as validation after parsing succeeds.

**Parsing and validation are distinct.** Parsing takes a token to a host value; validation checks the host value against the constraint record. `twelve` at an `integer`-typed field is a parse error; `300` at a field typed `age` (refining `integer` with `{min: 0 max: 150}`) parses as an integer, then fails validation. Implementations SHOULD distinguish these in error reporting ([TSON-DATA] §8.1).

**Enum member semantics.** An enum is a closed set of **names**, and the identifier family they are drawn from — the same two facts a record states of its field names through `meta.record.name_type` (§5.2), under the same name. The kernel's constructor states both, and one refinement of it, the kernel's root `enum`, pins the family for the common case:

```
enum_type => meta.atom & {
  name_type: meta.identifier_ref
  members:   meta.enum_set
}
enum      => meta.enum_type ^ { name_type?: = meta.identifier }
```

`!enum [OPEN ACTIVE DONE]` is a *vocabulary*: its members are names, written unquoted, and a named scope for name hygiene (§11.4). It is the positional form (§5.6) — `name_type` is pinned and injected, so `members` is the one unmarked field. `!meta.enum_type { name_type: kebab  members: [make-tea drink-tea] }` is the general form, and any schema may write it: `name_type` is data, so a schema enumerates names of a naming vocabulary it declares itself, with no new constructor; `!meta.enum_type { name_type: words  members: ["lightly active" "sedentary"] }` over `words => !unicode.identifier_type { medial: " " }` enumerates names with spaces in them, with full hygiene, `medial` placing a character only between two others and never at an edge or doubled. `members` is typed by the kernel's `meta.enum_set` — `!meta.set_type { element_type: text  min_items: 1 }` — so members are held as text whatever `name_type` is, every enum has `meta.enum_type`'s shape, and one reader and one host class serve them all: a processor binds a constructor that refines another as the one it refines. **A closed set of text that is no name is no enum**: it is the text family's own `members` facet, `!text ^ { members: ["80" "443"] }` (below), string-class as every text atom is.

**`name_type` names an identifier family** — typed `meta.identifier_ref` (§4.2), the reference over the types built with `unicode.identifier_type`, so a value naming any other type is refused as admitted by no such reference, at the declaration. Everything that distinguishes one enum from another is a property of that type, not of `enum`:

| | derived from `name_type` |
|---|---|
| members | each MUST be a value of the type, by the family's own parsing and facets — so a name under its profile — and no two may be one value under its equality; a member the type refuses is a schema-load error ("is not an identifier" under the default, "is not a value of its name_type 'words'" under an author's) |
| hygiene | §11.4's scope and all three of [TSON-DATA] §8.2's mechanisms, under the type's own profile, whose added characters are its own |
| discrimination class (§5.4) | the members' shared class, read off each member's own token |
| binding | a host enum by a mapping the binder owns (below) |

The first row is a pair of rules and not structure: members are held as `text` and are unique as text by the set's own contract, and a type whose equality is coarser than text — one that folds case (§5.5) — makes two of them one name, which is refused. The class row stays with the enum rather than following the type: it reads the members' tokens (`boolean => !enum [true false]` is boolean-class, where the type `meta.identifier` is string-class). **A name type's `normalization` implies NFC**, on an enum as on a record (§5.2): `NONE` is refused at load, and `ASCII_CASEFOLD` is admitted only for a type whose every character is ASCII, `NFKC_CASEFOLD` being the fold for any other, since a document's name is matched against the members in the type's form and no reader could match one under a form the type never stated.

**Where a `name_type` resolves.** A `name_type` an author writes names a type in the author's own namespace. A value a constructor pinned was written in the constructor's declaring document and resolves **there**: `!enum [A B]` names the kernel's `meta.identifier` in any schema, however the enum is reached, and an `identifier` the schema declares for itself is a different type that does not displace it. This is §2.2.3's rule that a merged entry's own references stay in its defining schema's namespace, stated for a pinned value; the same holds of a record's `name_type` (§5.2). **`name_type` is fixed at construction** (§5.7): a refinement narrows the member set and never changes the type, since narrowing it would re-judge members already admitted and widening it would admit names the source's type refuses. A `~`/`=` value at the slot is judged admitted by `meta.identifier_ref` where it is written, so a constructor pinning a non-member fails at its own document's link rather than at the first schema applying it.

**Numbers are never enums.** An enum's members are names, equal only to themselves under their type's equality; an integer's equality is a value equality (`80` and `0x50` are one value, [TSON-DATA] §4.3), and a numeric value set is the numeric tier's own facet — a contiguous range is `!integer ^ { min: 1  max: 3 }`, an arithmetic progression is `multiple_of`, a **sparse set is `members`** (`!integer ^ { members: [80 443 8080] }`, the `numeric.integer_member_set`-typed facet `numeric.integer_type` carries and the `numeric.decimal_member_set`-typed one `numeric.decimal_type` carries, §9) — and a choice is for alternatives that are not one family. A mixed source enum (`["AUTO", "MANUAL", 80, 443]`) becomes a choice over an identifier enum and a member-set integer, which is disjoint by class and tag-free. The split, in one sentence each: *a vocabulary of names* is `!enum [OPEN ACTIVE DONE]`, or a `meta.enum_type` over the author's own identifier family where the names need a wider profile; *a value set on a type needed for its other facets* is that family's `members` — `!text ^ { length: 2  members: ["AU" "NZ"] }`, `!int16 ^ { members: [80 443] }` — where the family's own parsing still applies. What an enum has that a member set has not is an unquoted spelling, a class of its own, name hygiene, and name equality in place of value equality.

An enum MUST have at least one member (`!enum []` fails `meta.enum_set`'s `min_items` at schema load), and its members are unique (`!enum [OPEN OPEN]` is a schema-load error). There is no carve-out for `true` and `false`: the kernel defines `boolean` as `!enum [true false]`, an ordinary enum of names whose class is read off its members' tokens by [TSON-DATA] §4, so `boolean` stays boolean-class and `[INDEX NAMED]` string-class. Parsing at an enum-typed position puts the token's decoded text into the label type's normalization form (§5.5) and matches it against the members — a typed position never consults the form, so `"true"` and `true` are one value at `boolean` (as [TSON-DATA] §5.5's `!boolean` states for the schemaless case). **How an enum binds:** to the host type of the natural parse of its members — a host boolean at `boolean`, host text everywhere else — with a host-language enum reached through a mapping the binder owns. Under an identifier family the members are names; that they are *host-safe* names is true of no host — [TSON-DATA] §7.7 admits `in-progress`, and the kernel's own `boolean` has `true` and `false` for members — so binding is a mapping in every case (`in-progress` to `IN_PROGRESS`, `"new york"` to `NEW_YORK`), and the binder reports one that collides or yields no legal constant. A host language is what a schema maps *to*, never a constraint on it. `members` describes the permitted values, not the resolved representation.

**Text member sets.** `unicode.text_type` carries `members?: unicode.text_member_set` beside `min_length`, `max_length`, `length`, `pattern` and `normalization`, and `unicode.identifier_type`, `net.uri_type`, `net.iri_type`, `unicode.regex_type` and `net.email_type` inherit it through the composition they already use (§9): a value set on a constrained text family, where the family's own parsing contract still applies — `!uri ^ { members: ["https://a.example/" "https://b.example/"] }` is two admitted URIs that are still parsed as URIs, exactly as `!int16 ^ { members: [80 443] }` is a sixteen-bit integer that happens to be sparse. It is a set, not an array, for three reasons — uniqueness comes from the set's own contract, non-emptiness from its `min_items`, and member identity is the value's, so two spellings of one member are a duplicate rather than two — and a plain field beside `pattern` rather than a group excluding it, because a pattern is two things nothing can tell apart — `a|b|c` is a value set and beside `members` is the same statement twice, `[A-Z]{2}` is a shape and beside `members` composes as a numeric range does. A member is a value: each is put into the type's normalization form (§5.5), and two that are one value there are a duplicate and are refused, the set's own uniqueness having compared only the text as written. **The pattern MUST admit every member**: the libraries' uniform `members` rule, applied, and the one member-coherence check in the series that needs a regex match rather than a comparison. `pattern` and `members` are each settable once under refinement (§5.7).

**Identifier families.** `unicode.identifier_type` is the constraint vocabulary of names: a UAX #31 identifier profile, stated as data, with the text facets applied inside it.

```
identifier_type => unicode.text_type & atom.specification & {
  spec?:          = "https://www.unicode.org/reports/tr31/"
  start?:         unicode.identifier_base ~ XID
  continue?:      unicode.identifier_base ~ XID
  start_add?:     text
  continue_add?:  text
  medial?:        text
  exclude?:       text
  normalization?: unicode.normalization ~ NFC
}
identifier => !unicode.identifier_type { continue_add: "-" }     -- meta.identifier
```

The profile is UAX #31's R1 default identifier syntax — `Start Continue* (Medial Continue+)*`, with `Start = (start ∪ start_add) − exclude`, `Continue = (continue ∪ continue_add) − exclude` and `Medial = medial` — where `start` and `continue` name the base each set is drawn from (`unicode.identifier_base => !enum [XID ID ASCII NONE]`: `XID_Start`/`XID_Continue`, `ID_Start`/`ID_Continue`, XID's ASCII members — Start `A`–`Z` `a`–`z`, Continue adding `0`–`9` and `_` — or the empty set) and each of the four text facets is read as the set of code points it holds. A medial character stands only between two others and never beside another medial. **A floor no profile lifts:** a `unicode.identifier_type` MUST NOT add, in `start_add`, `continue_add` or `medial`, a character that renders as nothing or as another character — a `Default_Ignorable_Code_Point` (so every `Bidi_Control`), a control, or a space other than U+0020 — and a profile that does fails to load ([TSON-DATA] §8.2): an added character meets no restricted-character rule, so without the floor a soft hyphen in `continue_add` would make two names no rule tells apart. U+0020 stays admissible, a medial space being how a name type holds words. Where a type is a **name type** — a record's or an enum's (§5.2) — its `normalization` MUST imply NFC: `NONE` is refused, and `ASCII_CASEFOLD` is admitted only over a profile whose every character is ASCII. The profile judges the *value* — the text put into the type's `normalization` form, `NFC` unless the instance says otherwise (§5.5) — so a quoted decomposed spelling at an identifier-typed position reads as its composed value and is not refused for having been written decomposed, and a profile that folds case lists lowercase letters only and admits `Content-Type`. A join control (U+200C, U+200D) is admitted only in the contexts UTS #39 §3.1.1.1 permits, whatever the profile ([TSON-DATA] §7.7, rule 2). Because the family composes `unicode.text_type`, it takes lengths, a `pattern` — a naming convention such as snake_case, inside the profile — and `members`, a closed vocabulary of names; the member-coherence rule below counts the profile among the facets a member must satisfy, so `members: [north "2nd"]` under `meta.identifier`'s profile fails to load.

`meta.identifier` is [TSON-DATA] §7.7's grammar exactly: `XID_Start`-initial, `XID_Continue ∪ { - }` thereafter, in NFC. Two identifiers are equal iff their NFC texts are byte-identical; identity is case-sensitive. It is the type of every naming position in the series — `meta.type_name`, `meta.field_name` and `meta.param_name` alias it, `enum` pins it as its name type, and a record whose chain states no other has `meta.field_name` as its (§5.2) — so a rule stated on it reaches all of them, and because every identifier lies inside the unquoted-token profile ([TSON-DATA] §7.1), the positions that admit no quoted form (`!` targets, `@` names, and every schema-grammar name) lose nothing. A lexer and a schema parser hold that profile directly, the kernel's own names being read before the kernel exists, and a kernel whose `meta.identifier` states any other is refused at bootstrap (§3.4). What follows from the family being a type:

- **A value typed by an identifier family is a name.** [TSON-DATA] §8.2's per-name mechanisms reach it under the identifier policy, at a map key and at a field's value alike, and the keys of a map keyed by one and the elements of a set of one are look-alike scopes (§11.4). The line between a name and data runs through the type.
- **A schema declares its own.** `slug => !unicode.identifier_type { continue_add: "-" }` is one line, after which a schema writes `{slug => handler}` and refines `!slug ^ { … }`. No bundled library declares `identifier`, which stays a schema's to take.
- **Each profile is its own type.** Every `!unicode.identifier_type { … }` is a distinct type entity: IS-A between two identifier types comes from refinement alone, never from one profile admitting a subset of another's names. All are string-class (§5.4).
- **The profile facets and `normalization` are fixed where the profile is constructed** (§5.7): a refinement narrows only the text facets, and a different profile is a fresh `!unicode.identifier_type`.
- **Only `meta.identifier`'s profile is known to lie inside the unquoted-token profile.** A value under another profile may need quoting, which costs nothing: the positions that admit no quoted form are typed by the kernel's roles, which stay on `meta.identifier`, and a field name or enum member under another profile is written quoted where it must be (`"@id"`, `"lightly active"`).
- **A profile's own additions are exempt from the `Identifier_Status` rule**, and a per-segment restriction level divides a name at its profile's own separators ([TSON-DATA] §8.2).
- **A record's field names and an enum's members are values of an identifier family**, their `name_type` (§5.2), so a schema translated from a JSON vocabulary declares the profile its names need — `json_name => !unicode.identifier_type { start_add: "@$" }` — and judges `@context` and `$ref` as names under it, with full hygiene.

The kernel's second instance is `net.scheme_name`, a URI scheme (RFC 3986 §3.1) — a letter, then letters, digits, `+`, `-` and `.`, compared without case, written over the `ASCII` base: `!unicode.identifier_type { start: ASCII  continue: ASCII  continue_add: "+-."  exclude: "_"  normalization: ASCII_CASEFOLD }`. The `ASCII` base is what lets an ASCII protocol's names be stated in one line — an HTTP field name is `!unicode.identifier_type { start: ASCII  continue: ASCII  medial: "-"  normalization: ASCII_CASEFOLD }` — where a `NONE` base would list the alphabet twice.

**Number-grammar reuse.** Atoms that parse numeric values SHOULD use the number grammar of [TSON-DATA] §7.6 for the relevant numeric form; a single shared number parser dispatching on the atom's declared form is the expected pattern.

**A facet is read by its type, at load.** Every facet of a bundled constructor holding a value of the constrained family is typed by that family's own instance — a float's bounds by `number`, a network list by `[cidr4]` or `[cidr6]` — declared in the same document (§5.5, §9): `numeric.decimal_type`'s bounds and `multiple_of` are `number` and its `members` a set of `number`; `numeric.rational_type`'s bounds and step are `rational`; `temporal.date_type`'s bounds are `date`, and so on through the temporal families; `net.ipv4_type`'s network lists are `[cidr4]`; a count — a length, an item count, a digit count, a prefix, a precision, a UUID version — is a `numeric.count`. The ordinary field reader does the rest: a facet is read at schema load by its type, in the wait order of §3.4.1, a value the type refuses being a resolver error with the family's own diagnostic (`'P1Y' is not a valid duration … a month is a period`), a set's members compared as values of the family (`[1 1.0]` on `numeric.decimal_type.members` is a duplicate), and a bound spelled as a value of its family (`!number ^ { min: 0x10 }` refused, the family admitting no based spelling). A refinement of a refinement reads its bound as the base instance: `positive => !number ^ { min: 0 }` then `!positive ^ { min: -5 }` reads `-5` as a `number`, which it is, and §5.7's tightening rule refuses the widening. Nothing in the series types a facet by an untyped escape hatch.

**Coherence of a body's facets.** A body's facets MUST between them admit a value: the lower bound not exceeding the upper (across whichever inclusive or exclusive forms are present), a `multiple_of` strictly positive, an `integer_size.bits` above zero, a `precision` at most 9, a network family's `within`/`excluding` pair and prefix bounds admitting a value (§5.5), an identifier profile whose Start set is not empty and whose `medial` characters are disjoint from its Start and Continue sets, and — where `members` is present — **every member satisfying the body's other facets**, the range a family derives rather than stores included (`{ members: [443]  size: { bits: 8 } }` fails the way a stated bound outside the width does). Each is a resolver error at schema load.

**A position typed by a reference over a constructor.** In a data document a name at such a position — `kind: stock_id` at `meta.ref<net.uuid_type>`, `type: int32` at a `meta.record_field` read as data — is judged by membership in the document's closure, the one its imports bind, and an application there is a validation error (§4.2); a sibling reference is refused by the facet where `allow_sibling` is false. A value of `meta.schema` read as a resolved form resolves its names in the schema it describes, and is judged where that schema links (§8.1).

**Lexical classes vs type names.** The categories of [TSON-DATA] §4 base type resolution (boolean, integer, float, string) are lexical classifications of tokens a schemaless read produces, not type names: the lexical class and the type `number` are distinct namespaces that share a word. A schema cannot reference a lexical class, and base resolution never produces a declared type; when a schema is in scope, the schemaless dispatch does not apply (§7.3).


### 7.5 Sets

A **set** is an unordered collection of unique values. Sets share array syntax `[ ... ]` at the data level ([TSON-DATA] §2.7); set-ness is a schema property declared via the kernel's `meta.set_type` constructor, most often applied through the root's `set`, `set<T>` (§5.3). Without a schema, every `[ ... ]` is an array.

**Duplicate handling.** When source data contains a value more than once at a set-typed position, the duplicate is a **validation error** at the repeated occurrence — a set-typed position asserts uniqueness, and silently dropping a member the author wrote would decode the document to something other than what it says (the same posture as duplicate map keys, [TSON-DATA] §2.6). Two values are duplicates if the element type's equality contract considers them equal, and for an **atom** that contract is the type's **value space** (§5.5): name identity for a set of `meta.identifier`, value equality for a set of `integer` or `number` (`1` and `1.0` are one member), octet equality for a set of `bytes` whatever the spelling, instant equality for a set of `datetime`, and equality in the type's normalization form for a set of a text family (`Content-Type` and `content-type` are one member under a type that folds case). For an element type that is not an atom — a record, array, map or choice — two elements are duplicates if the processor's **host representation** of them is equal, and which pairs that relates is implementation-defined: a processor detects at least the textual identity of [TSON-DATA] §2.6 in a tree it builds itself, and where elements are bound to host types, those types' equality decides. The series can reduce to a value space only the atoms it implements on the host; past an atom, identity belongs to the host, and a schema that needs portable duplicate detection over a compound value keys it by an atom — a derived identifier or a canonical string — which is the only identity every encoding and every host can agree on.

**Element order.** Order is a facet a container states (§5.3): `ordered` says whether two values differing only in order are one value — `true` for an array unless it says otherwise, `false` for a set by `meta.set_type`'s pin, and `false` for a map unless it says otherwise. It never changes what a document may write. A set is thus unordered as a *value*: uniqueness and set equality are what the type states, and element order carries no meaning at a set-typed position. **Output keeps the order written either way.** In resolved output a set's elements, like an unordered map's entries, appear in source declaration order — for `meta.enum_type.members`, `numeric.integer_type.members`, `numeric.decimal_type.members`, `unicode.text_type.members` and `net.uri_type.schemes` alike — so that two resolved documents are compared as §8 writes them, with no table of unordered positions to consult and no canonicalisation step; a reader keeps the author's order, which an ordinal-assigning encoding also needs. A refinement that shrinks a member set keeps the surviving members in their inherited order.

These rules apply uniformly to every set-typed position: the kernel's `meta.enum_set`, the exact tiers' `members`, user-defined set-typed fields, and set values produced by the positional fill rule (§5.6). A void element at a set-typed position is rejected — `meta.set_type` pins `voidable: = false` (§5.3) — and a set may be empty unless its type states `min_items: 1`, as `set<T>` does not and every member set of the kernel and the libraries does.


### 7.6 The Void Sentinel Under a Schema

[TSON-DATA] §2.9 defines the void sentinel and its data-value positions. `_` is not itself a type; the type whose sole conforming value is `_` is `void` (§4.2). It is the one spelling of void: the token `null` is a string, or a parse error, or a validation error, by the position's type (§7.3).

This document extends the position rules of [TSON-DATA] §2.9:

| Position                                  | `_` permitted? | Meaning                                                                                          |
|-------------------------------------------|----------------|--------------------------------------------------------------------------------------------------|
| Array element (schema in scope)           | conditional    | Permitted only when the array type is `voidable`, written `[T?]` (§5.3)                           |
| Tuple element (schema in scope)           | conditional    | Permitted only when the position is `voidable` (§5.3); the void element occupies its slot        |
| Record field value (schema in scope)      | conditional    | Permitted when the field is **voidable** — its type carries `?` (§5.2) — whatever its name's mark; the field is then present with a void value. At a field that is not voidable, a validation error, whatever the modifier — at a defaulted field the fix is omission, which injects the default (§5.2). At a voidable group member, `_` is present and chooses its option (§5.11). A dependent field and its sibling are never voidable (§5.2) |
| Map key (schema in scope)                 | no             | Resolver error — restates [TSON-DATA] §2.9's resolver-layer rule; keys define membership and are always required |
| Map entry value (schema in scope)         | conditional    | Permitted only when the map type is `voidable`, written `{K => V?}` (§5.3); the entry is then present with a void value ([TSON-DATA] §2.9) and counts toward the size bounds. A namespace value's entries are never void (§4.1) |
| Field-type position (type-definition grammar) | no         | Parse error — use parameters (§5.10)                                                             |
| Type expression position (type-definition grammar) | no    | Parse error                                                                                      |
| Type-def body (declaration right-hand side) | no             | Parse error — use `{}` for an empty record                                                       |
| Field modifier value (`~`/`=`)            | no             | Resolver error — `~ _` and `= _` are refused; "may be missing or written `_`, and nothing else" is the type slot's to say, `a?: void?` (§5.2) |

When a schema is in scope, a void element requires the governing container type to permit it. Void elements occupy positional slots, and size constraints count all slots. A tuple's voidable position requires an explicit `_` — the tuple's length is fixed by its type, and short tuples are validation errors (§5.3).


### 7.7 Resolver Behaviours at Typed Positions

**Field names in the name type's form.** A document's field name is matched against the record's declared names in the record's name type's `normalization` form (§5.2, §5.5): under a case-folding name type `NAME` is the field `name`, two spellings of one name in one record are a duplicate field, and a name that is no value of the name type can match no declared field and is unrecognised (§7.2). The schema's own references to its fields match the declared spelling exactly; only a document's names are matched in the type's form.

**Typed key equality.** [TSON-DATA] §2.6 defines layered duplicate-key identity: textual identity as the parser's minimum, decoded-value identity for any processor that decodes, and declared-type identity — which can only make *more* keys equal — on top. A schema-aware processor decodes keys by the declared key type's contract (§7.4), so two keys equal under that contract are one key, and a duplicate that only the declared key type relates (`1` and `1.0` under a `number`-keyed map, two spellings of one octet string under a `bytes`-keyed one, `Content-Type` and `content-type` under a text key type whose `normalization` folds case — the textual and decoded-value duplicates of [TSON-DATA] §2.6 are already resolver errors below this layer) is a **Class 2 validation error** at the repeated occurrence, consistent with the set rule (§7.5): identity under a declared *atom* key type is over the key type's value space (§5.5), and under a compound key type — a record, array, map or choice — it is the processor's host equality over what it built, at least the textual identity of [TSON-DATA] §2.6 in a tree the processor builds itself, on §7.5's terms. Which host representation the decoded keys take afterwards is the implementation's concern; the identity contract for an atom key is not. A key typed by an identifier family is a name as well as a key, and the keys of one such map are a look-alike scope (§11.4). A namespace value's keys are names of the family its type's `key_type` states, judged at link (§4.1).

**Empty braces.** [TSON-DATA] §2.8 defers empty-brace disambiguation to declared type information. Under a schema, the expected type at the position supplies it: the resolver transforms an empty-brace value into an empty record or an empty map per the expected type, defaulting to an empty record when the position is untyped. The rule covers exactly the two containers that share the brace form and cannot be told apart when empty; an empty map then faces the map's size bounds like any other. At a position whose expected type is anything else — an array, a tuple, an atom, a choice with no brace-class variant — an empty brace is a **validation error**, reported as a value of the wrong form: an array or tuple has its own empty spelling (`[]`), and admitting `{}` there would let a brace-class value conform to a bracket-class type (§5.4).


### 7.8 Declared Positions: the Open Sum

Where `meta.choice` is the *closed* sum, enumerating its variants, the kernel's `meta.declared_type` is the *open* sum: a value at a position typed by its one instance, `meta.declared`, names its own type by its tag, among the types of the governing closure — the schema's own declarations and its imports (§2.2.3) — and is validated in full against it.

```
declared_type => meta.sum & {}
declared      => !meta.declared_type {}
```

**The data rule.** At a position whose type resolves, past its alias chain, to `meta.declared`:

- a value carrying a tag names its type: the name resolves in the reading document's namespace exactly as at any other position (§7.2), a tag naming no type of the closure is a resolver error, and the value validates in full against the type it names;
- a value carrying no tag is a **validation error** — *a value at a declared position must name its type* — in every mode, since the position promises no type for an untagged value to be read as (§7.1).

`meta.declared` is SUM-kinded and has no discrimination class (§5.4): it is never a variant of a disjoint choice, and a value at a declared position inside a choice carries the choice's tag and its own. It is what the schema map's entries are: `meta.schema`'s `value_type` is `meta.declared`, so every entry of a schema map names its type by its tag — a constructor for a type, an ordinary type for a value — which is how a resolved form reads (§8.1), and how a data document of `meta.schema` is read at all.

A position admits a value of a foreign schema by importing it: a data document's namespace is the union of its imports' closures (§3.3.4), and no value opens a schema of its own — a directive at a value position is a parse error ([TSON-DATA] §3.3). A schema that means to admit a type from a document it does not import has no way to say so, by design: a foreign schema that kept its own names would put two namespaces in one document, so either a tag's meaning would depend on its position or the data would carry a prefix, and neither is taken.


## 8. Resolver Output

The resolver's output for a schema is a value of the kernel's `meta.schema` — a map from each name to the value its entry resolves to (§4.1): the output records and their conventions (§8.1), template instantiation and identity (§8.2), and aliases (§8.3). Each construct's mapping to its value is defined with the construct in §4–§5. Resolved output and source are one grammar: output is source with sugar expanded, bodies flattened and chains computed, and an entry written as a constructor application resolves to itself.


### 8.1 Output Records

**An entry is its value.** A schema map's entry resolves to the value its declaration denotes, with no wrapper around it: `person => !meta.record { fields: { … } }`, `integer => !numeric.integer_type {}`, `age => !numeric.integer_type { chain: { supertypes: [integer] }  min: 0 }`, `meta.type_name => !meta.alias { target: meta.identifier }`, `box => !meta.record { parameters: [ … ] … }`, a value entry `orders => !api { … }`, and a nested map `geo => !meta.schema { … }`. Values are annotated with the structurally appropriate constructor: `!meta.record` for vocabulary bodies (constructors, §4.2) and for every record, open or closed; a binding record headed by the applied constructor for every other non-alias definition (§5.6); `!meta.alias { target: T }` for alias-kind entries; an ordinary type's tag for a value entry (§4.1); and `!meta.schema` for a nested map. `meta.schema`'s `value_type` is `meta.declared`, and every entry names its type by its tag (§7.8); a constructor's path is written as the document that declared it declares it (`!meta.record`, `!numeric.decimal_type`, `!net.uuid_type`, `!enum`), and a name at every slot as the described schema writes it.

**A resolved form's header is its source's**, and its root is `!schema`: it carries the source's `!!id` (under a fixture identity where one is published) and the source's `!!import`s, so that the namespace in which its names resolve is the one the source resolved in; nothing in the document marks it resolved, and a processor reads it as a resolved form because its caller says so (§10.1). The kernel's own resolved form carries its `!!id` alone and is read under the pre-loaded kernel. Nothing is supplied beside the document.

Four things a reader might expect are deliberately not on the entry. There is no `constructor` field: whether an entry is a constructor is whether it is `meta.top` or IS-A it, derivable from its chain (§4.2). There is no `kind` field: an entry's kind is derived (below). There is no `source` field: a construction's constructor is the value's tag, a refinement's origin is the chain's nearest member, and the one fact a wrapper once held that nothing else states — the application an entry is the instantiation of — rides on the entry's key as `@meta.application` (§8.2). And there is no `members` on a reference and no `disjoint` on the entry: the one derived index a body carries lives in the body it is derived over, and a reference's admitted set is derived and never written (§4.2).

**A type carries its chain.** Every type body — every value whose constructor IS-A `meta.type` — carries `meta.type`'s one field, `chain`, a `meta.type_chain` (§4.1): `supertypes`, the closed IS-A chain, every element a path without arguments, complete to a fixed point through applied parents (§5.8) and sorted; `subtypes`, the inverse over the namespace of the schema being resolved (§3.3.4); and, on an open record alone, `parents`, the parents as written, one of which may be an application (§5.10). An empty chain is omitted. A composition's fields are flattened and its `chain` stands among them first. `meta.alias` and a value entry carry no chain — a hop and a value carry no IS-A, which is the rule. A declaration never writes `chain` and a constructor never declares a field of that name (§4.1).

**Open records.** An open record is a `!meta.record` carrying `parameters`, a list of `meta.param` records in declaration order — `param => { name: meta.param_name  bound?: meta.type_ref }`, the arity, order and written bounds an application binds against; a list of records rather than a map, which would lose the order arguments are positional in, or two parallel lists, which would be free to drift. A parameter stands as its name, a reference, at a field's `type` or among the arguments of an application there or in `chain.parents` (§5.10): `box => !meta.record { parameters: [!meta.param { name: T }]  fields: { v => !meta.record_field { type: T } } }`. Its `extension`, `discriminators`, `groups` and `name_type` are each application's. A consumer of closed entries never builds a reader for one (§1.3), and a source declaration writes its parameters as the list before its body, never as a binding (§5.10).

**Kind is derived.** A consumer that needs an entry's kind (§4.1) derives it from the entry's value, in this order, and every conforming resolver derives the same answer: **(1)** the value's constructor head is `meta.alias` → ALIAS — the head lookup would say PRODUCT, since the kernel's `meta.alias` is itself a product, and §4.1 makes ALIAS a kind the alias form confers rather than a base kind; **(2)** else the entry is `meta.top` or IS-A it — it is a constructor — → the base-kind name among its own `chain.supertypes` (`meta.atom`, `meta.sum`), each counted only as the kernel's own entry, or PRODUCT if none: a constructor's kind states what its *instances* are, not what its own body is (`numeric.integer_type` has a `!meta.record` body and is ATOM-kinded); **(3)** else the value's constructor head is a constructor → the kind of that constructor, ATOM, PRODUCT or SUM; **(4)** else the head is an ordinary type → the entry is a value entry, and takes no kind of its own (§4.1). A kind is judged by origin, not spelling: a schema's own record named `product` reaches none of the kernel's entries, so a composition onto it is PRODUCT by structural default.

**The `meta.alias`, `meta.record_field` and `meta.field_map` records.** An alias body is `!meta.alias { target: T }`, where `target` is a bare name: a declaration naming an application is that application's entry, never an alias (§8.3). An alias is a hop: `target` names the immediate referent, and a use site elsewhere in the output that names the alias names the alias (§8.3). A record's `fields` is a **`meta.field_map`** — an ordered `meta.map` from each field's name, as text, to its `meta.record_field` (§4.1, §5.2): the name is the key, written once by its spelling, and each `meta.record_field` carries its `type` (a `meta.field_type_expr` value — a type's name, a sibling reference `kind!` written as written, or a type written in place, `{ of: … }`) and the four facts a reader consults — `optional` (the name's `?`, default `false`), `voidable` (the type's `?`, default `false`), `role` (`FREE`, `DEFAULT` or `FIXED`, default `FREE`) and `value`, typed `type!` and so read against the field's own `type` — each default omitted in output, and `value` present exactly when `role` is not `FREE`. Three invariants hold over them: `value` is present exactly when `role` is not `FREE`; `DEFAULT` requires `optional: true`, a default on a key that is always written being a value nothing can reach; and `FIXED` requires `voidable: false`, since the `_` decision is taken before the pin is consulted. A record field's `type` holds a type's name, a type written in place, a sibling, or a parameter of the enclosing open record, and no label says which name it is; its `value` is a literal, never a parameter. There is no stored omission answer: a reader derives it — a field that is not optional is missing, an optional one with a value injects it, except at a field group's member, which the group governs and never supplies (§5.2, §5.11). A `meta.record` body also carries `extension` (omitted at `OPEN`), `discriminators` (present where the record is a member-dispatched base), `name_type` (present exactly where it was stated, §5.2), `groups`, and on an open record `parameters`. The `meta.field_map`'s keys are text judged by the record's name type — `meta.field_name` where neither the record nor a supertype states one — and two keys that are one value under it are a resolver error (§5.2).

**`optional` and `voidable` in output.** The series' two kinds of nothing have one name each wherever a record states them (§5.2). `optional` — the slot may be missing — is a field of `meta.record_field` and of `meta.field_group`, where it is the group's own `?`, beside `optional_members` for the members that may be missing once their option is chosen (§5.11). `voidable` — the slot's value may be void — is a field of `meta.record_field`, `meta.tuple_element`, `meta.array` and `meta.map`, where it speaks of the field's value, the position, the element and the entry's value. A container's part is never missing, so a container has no `optional`; a map's key is never void, so `meta.map.voidable` is its value's alone; both are structural, and no rule has to refuse them. Each is a boolean defaulting to `false` and omitted there. There is no `element_state` and no `state` field: a binding record carrying one is refused as a member its vocabulary does not declare (§5.5), never read as the other fact.

**Positional form and references.** A slot typed by a reference holds a bare name — `type: text`, `element_type: meta.record_field`, `target: meta.identifier` — or, where an application is open, the reference with its arguments, written unquoted ([TSON-DATA] §7.4), and a sibling reference where `allow_sibling` admits it. Canonical output writes an application with no whitespace but one space between arguments and no comma; identity is the parsed form (§8.2). `meta.record_field.type` is typed by the type-slot choice, whose bare token is a name (§5.6), so a `meta.record_field` has a positional form and a document may write `fields: { x => text }` for `fields: { x => { type: text } }`. Canonical output writes the explicit record, tagged: `fields: { x => !meta.record_field { type: text } }`. A slot holding a type written in place writes `{ of: !C { … } }`, the wrapper untagged and the construction tagged by its constructor (§4.2): `tags => !meta.record_field { type: { of: !meta.array { element_type: text } } }`. The untagged record and the positional form are read as the same value, `meta.field_map`'s `value_type` typing the position.

**Reading parameter references.** **A parameter appears only inside an open record** — at a field's `type`, or among an application's arguments there or in `chain.parents` — and every reference outside one names an entry and is read directly against the schema. Within an open record a name is read against its `parameters` first, then the schema's namespace, the precedence of source (§3.3.2). A name in a closed body that would resolve only as a parameter is an unresolved reference, the verdict every other reference form gives it.

**`@meta.application` is the one fact the value does not state.** An entry that closes an application of an open record carries, on its schema-map key, `@meta.application:box<int32>` — the application as written, the reference with its argument list, each argument a name past its alias chain (§8.2, §8.3). It is the entry's identity as an instantiation, carried by a declaration naming an application and by no other entry (§8.2); a construction carries none, its value stating its constructor and its arguments, and a record closed in place carries none, having no key. It lives on the entry's key, is derived — an author's `@meta.application` is a resolver error, as a written `chain` is — and is **read as stated** when a resolved form is read, not recomputed (§6, §10.1), which is what an applied parent's IS-A edge consults (§5.8). Nothing else records provenance: which operator a refinement used, and what a subtracted record was subtracted from, are what was written, nothing reads them after resolution, and the source document has them.

**The derived indexes: `subtypes` and `disjoint`.** Two indexes are computed by the resolver and never set by a declaration, each living in the body it is derived over. `chain.subtypes` is the transitive inverse of `supertypes` across the schema's namespace — "what is in family X?" — and `supertypes` answers "is this in family X?" by name; a parent written as an application contributes, once the composing entry closes, the entry a declaration gives that application where one exists, and never its head (§5.8); an open record is in no index (§5.10). `supertypes`' standing differs from `subtypes`': `subtypes` is a cache, fully derivable and never trusted, while `supertypes` is part of a type's serialized meaning — the chain and a flat body cannot be recomputed from each other, and for the atom family the hop lives only there, since desugaring erases the surface distinction between refining an instance (`age => !integer ^ { … }`, IS-A `integer`) and constructing a fresh instance (`port => !numeric.integer_type { … }`, IS-A nothing), both serializing under the `!numeric.integer_type` tag with an identical body shape. Construction and application are not IS-A: a sugar construction (`ids => [order]`) carries no supertypes (§5.6), a declared application carries the chain its template's written parents give it and never an edge to the template (§5.10), and a type written in place carries no chain and is a member of nothing (§4.2). **`disjoint`** is `meta.choice`'s: `choice => meta.sum & { variants: [meta.type_expr]  disjoint?: boolean }`, and for every choice, declared or written in place, the resolver writes `true` or `false` beside the variants it is derived over — discrimination-class distinctness, a total, two-valued derivation (§5.4). It sits there rather than on the entry because a variant list is the only thing it is a fact *about*, and §7.2's closed-record rule refuses `disjoint` on any other body, so `meta.declared_type` needs no carve-out; the `@meta.disjoint` annotation (§5.4), when present, is the author's assertion checked against this derived value at schema load, and does not appear in the value itself. A reference's admitted set is derived at link and never written (§4.2). Like every name in resolver output, the names in these indexes are tokens in the described schema's own namespace (§2.2.3): an index never reaches outside the resolution that wrote it, and a consumer follows the entries in the defining schema's output.

**Reading a resolved form.** A resolved form is a schema document in canonical form, read as a value of `meta.schema`; reading one as the resolver's output rather than as a source schema to resolve is the caller's explicit choice (§10.1), as the form's header carries no mark. The reader is told the root's type and reads under the form's own imports — the kernel's own form, which imports nothing, under the pre-loaded kernel — and nothing is supplied beside the document. Then: `chain.subtypes` and `disjoint` in every choice body, a choice written in place included, MUST be discarded and recomputed, so none can be forged into a document to change how it reads; an entry's kind is derived on the same terms and was never in the document to trust (above). `chain.supertypes` and `chain.parents` are **read as stated**, never recomputed, with integrity verified — every listed supertype must exist, the list must be closed to a fixed point, an atom's supertypes must share the entry's constructor tag, and a record's must be consistent with the flattened `fields` — because a flat body and its chain cannot be recomputed from each other (§4.1). `@meta.application` is read as stated (above). Author-written annotations, key- and value-position alike (§6), are preserved as data, a restated field's in the order §5.8 fixes. No name inside a closed record may resolve only as a parameter, and an open record's `parameters` are read as stated, its rules of §5.10 checked as at its declaration: a bound is what its author wrote, so nothing is derived to check it against. Every entry carrying `@meta.application` MUST equal the recomputation by substitution from it, and every type written in place MUST be one §5.2 admits there and carry the body its constructor's vocabulary admits — the self-describing checks. A checked annotation (§6) is re-checked as it was at load: `@meta.disjoint` against the recomputed `disjoint`. Every record's and enum's names are re-judged against its name type, every reference-typed slot's name against its reference (§4.2, §5.2), and a dependent field's held value against the type its sibling names (§5.2). The family checks of §5.2 — every member of a member-dispatched base pinning each selector, pins distinct as values, nothing composed onto a FINAL record, no record written in place a member of anything — are re-run over the form's map and the closure that holds it (§3.3.4), the declaration rules of §5.11 are verified over every `meta.field_group`, and the three `meta.record_field` invariants above are verified per field. A nested map's members are keyed under their paths, and a value entry's payload is read against its type as data. Canonical-form violations (a positional `meta.record_field`, a chain written where none is admitted, a `members` list on a reference) are resolver errors. Within-family retargeting — a document claiming an entry refines a different instance of the same constructor — is internally consistent and undetectable from the document alone; this residual gap is one reason resolved forms are never schema sources (§10.1) and reading one is an explicit act.

**Body patterns** (informative — the construct sections govern):

| Source form | Resolved value | Examples |
|---|---|---|
| Root record `abstract {}` | PRODUCT; `!meta.record { chain: { subtypes: [...] }  fields: {}  extension: ABSTRACT }`; no supertypes | `meta.top` |
| `meta.type`, a base kind | PRODUCT; `!meta.record { chain: { supertypes: [meta.top …] … }  …  extension: ABSTRACT }` | `meta.type`, `meta.atom`, `meta.sum` |
| Fresh record `{ fields }` | PRODUCT; `!meta.record { fields: { … } }`; no supertypes | `person`, `meta.record_field` |
| Composition `A & B & { ... }` | `!meta.record { chain: { supertypes: [A B …transitive] }  fields: { …flattened } }`; kind per §5.5 | `employee` |
| Refinement `T ^ { ... }` | `chain.supertypes: [T …]`; materialised body | `production` |
| Subtraction `T - { f }` | `!meta.record { fields: { … } }`; no supertypes (§5.9) | `account_public` |
| Atom constructor `meta.atom & { ... }` | ATOM; IS-A `meta.top`; `chain.supertypes: [meta.atom meta.top meta.type]` | `numeric.integer_type`, `meta.enum_type`, `meta.reference_type` |
| Product constructor `meta.product & { ... }` | PRODUCT; IS-A `meta.top`; `chain.supertypes: [meta.product meta.top meta.type]` | `meta.record`, `meta.array`, `meta.map` |
| Sum constructor `meta.sum & { ... }` | SUM; IS-A `meta.top`; `chain.supertypes: [meta.sum meta.top meta.type]` | `meta.choice`, `meta.declared_type` |
| Constructor refinement `T ^ { ... }` | IS-A `meta.top` through `T`; `chain.supertypes: [T …]` | `meta.set_type`, `meta.namespace`, `meta.ref`, `enum` |
| Constructor instance `!T {}` | kind from `T`'s family; `!T {}`; no supertypes | `integer`, `meta.declared`, `uuid` |
| Value refinement `!I ^ { v }` | `!ctor { chain: { supertypes: [I …] }  v }` | `age`, `numeric.count`, `meta.type_ref` |
| Namespace type `!meta.namespace { key_type: K  value_type: V }` | PRODUCT; `!meta.namespace { … }`; no supertypes | `meta.schema`, `api` |
| Sugar, constructor-application or applied-constructor body | construction in place; binding-record value; no supertypes (§5.6) | `lookup => {text => integer}`, `meta.field_map`, `tags => set<text>` |
| Open record `<T> { ... }` | PRODUCT; `!meta.record { parameters: [!meta.param { name: T }]  fields: { … } }`, named only as an application's head, `chain.parents` as written (§5.10) | `pair`, `box` |
| Sugar form, construction or application of an open record at a type slot | written in place: `{ of: !C { … } }` at the slot, with no chain and no entry; over a parameter, the application held in the open record until it is applied (§5.3, §5.10) | `[text]`, `{text => integer}`, `set<text>`, `f: page<order>`, `[T]` in an open record |
| Declared application of an open record | the substituted `!meta.record` under the declared name, `@meta.application` on its key (§8.2) | `text_pair => pair<text, text>` |
| Value entry `!T { … }`, `T` an ordinary type | the value as read against `T`, `T`'s tag on it; no chain (§4.1) | `orders => !api { … }`, `create_order => !operation { … }` |
| Nested map `!schema { … }` | `!meta.schema { … }`, its members resolved and keyed by name, each an entry of the document by path (§4.1) | `geo => !schema { … }` |
| Choice `(A \| B)` | SUM; `!meta.choice { variants: [...]  disjoint: … }`, the fact always recorded in the body (§5.4) | `contact_method` |
| Field group `( a: T \| b: U )` | members flattened into `fields` as `optional: true`; grouping in `groups`, one list of members per option (§5.11) | `numeric.integer_type`, `endpoint` |
| Abstract base with selectors `abstract { a: T =?  … }` | PRODUCT; `!meta.record { … extension: ABSTRACT  discriminators: [a] }`; members in `subtypes` pin `a` (§5.2) | `pet` |
| Alias `name` | ALIAS; `!meta.alias { target: name }`; no chain — a bare name, never an application, which is an instantiation or a construction in place | `id => uuid` (§8.3) |

The `meta.schema` type is a map from type names to values; schema lookup is by path. Declared entries are keyed by their declared names, nested maps nested, and the map holds no other entry.


### 8.2 Instantiation and Identity

Resolver output holds the declared entries and nothing else. A type written where it is used stays where it is written (§4.2): a sugar form or a construction at a type slot is its construction in place (§5.3), an application of an open record at one is the record it closes to in place (§5.10), and a declaration's own body is the entry's value (§5.6). No entry is lifted, minted, named for its content or merged, within a document or across imports, and nothing an author did not write has a name.

*A declared application.* A declaration whose body is an application of an open record is the instantiation in place: its value the substituted `!meta.record`, without `parameters`, its `chain.supertypes` what its template's written parents reach once substituted (§5.8), and its `extension`, `discriminators`, `groups` and `name_type` the template's as written, so an application of an abstract template or of one carrying a selector is an abstract base (§5.10); `@meta.application` on its key records the application as written (§8.1). Substitution changes no field fact, no value being a parameter, so closing re-judges only what an argument decides: each argument against its parameter's bound, and each construction the substitution completes against its argument slots' `of` (§5.3, §5.10), a refusal located at the declaration that wrote the offending name, with the closing application as context. With `pair => <A, B> { first: A  second: B }`, a declaration `text_pair => pair<text, text>` resolves to

```
@meta.application:pair<text text>
text_pair => !meta.record { fields: { first => !meta.record_field { type: text }  second => !meta.record_field { type: text } } }
```

and `pair<text, text>` written at a field's type closes in place there, `type: { of: !meta.record { fields: { first => … second => … } } }`, the same body under no name. Two declarations naming one application are two entries, neither privileged and neither collapsed — what `a => { v: text }` beside `b => { v: text }` already is; where the pair is a discriminated family §5.2's pin-distinctness rule refuses it, as it refuses the hand-written pair. **An application is looked up to a declaration in two places only**: an applied parent of a composition or a refinement source, whose IS-A edge is the entry a declaration gives that application (§5.7, §5.8), and §5.10.1's recursion, which ties a nested application to the declaration closing it.

*Two identities.* **Bytes identify a document, for distribution and pinning; names and parsed structure identify a type, for typing.** Neither stands in for the other: a whitespace edit changes a document's digest (§10.2) and none of its types.

- **A declared entry's identity is its declaring document and its name**, its path from that document's root (§2.2.3, §4.1): two declarations are two types however alike their bodies — `stock_id => !net.uuid_type {}` and `other_id => !net.uuid_type {}` are two atoms, and a purely structural rule would collapse them and destroy a distinction §7.2 enforces (§5.5).
- **When two types are one.** A name is the same type as a name naming the same entry, past both alias hops (§8.3). A type written in place is the same type as an equal type written in place: the same constructor and the same fields, compared recursively, with names as the leaves, each compared as the entry it names and not followed through an alias, so `[user_id]` and `[uuid]` are two types; annotations and `@doc` take no part, an annotation never changing which values conform (§6). **A name and a type written in place are never the same type**, even where the name's declaration is an identical construction: `text_list => [text]` and an inline `[text]` are two types, and so are `page_order => page<order>` and a `page<order>` closed in place, of one shape (§5.10). The rule decides a restated field's narrowing (§5.7) and the distinctness of a choice's variants (§5.4).
- **Canonical applications.** Two applications of an open record are one when their **canonical applications are structurally equal**: the application with every argument's alias chain followed to its terminal entry, compared member-by-member — heads, then arguments in order, an argument being a name (§5.3). Following the chain is what makes a rename transparent: `box<user_id>` over `user_id => uuid` is `box<uuid>`, an alias being the same type under another name (§5.7's table), while a refinement or a fresh instance of `uuid` as the argument is an application of its own. `@meta.application` records the application as written and the comparison is over canonical applications, so spelling never forks one. Following alias chains is a substitution's rule: a construction's arguments are compared as written, `meta.array<user_id>` being `[user_id]` and not `[uuid]` (§5.3). An applied parent's edge (§5.8) and §5.10.1's recursion compare by it.
- **Identity is the parsed form.** A reference is compared as its head and its arguments — never as text: `box< text, >` and `box<text>` are one application.

*Comparing resolved forms.* Two resolved forms of one schema are compared as values, set-typed fields as written in source declaration order (§7.5): there is no internal name to rename, and two conforming resolvers produce equal values. Under the transitive import merge (§2.2.3) a type written in place travels inside the entry that holds it, and two documents writing one form hold two equal constructions, one type by the identity above, with nothing to unify.

*Diagnostics and consumers.* A diagnostic names what the author wrote and nothing that has no spelling: a type written in place is located by where it is written, a field path, and shown as its form as written (`[text; 1..]`, `page<order>`), and an entry by its name. No consumer contract may name a type written in place: a binding map, a generated class table or a configuration file keys on a name a declaration gives the type, and a generator that needs a host name for one derives it from where the type stands. The declared applications of one template are recoverable from `@meta.application`, so the family a host language spells `Result<T>` is a walk over entries and needs no index. The spelling that gives an application a name is a declaration — `order_page => page<order>` — which gives it an identity, a tag and a diagnostic located at the author's own declaration, and is RECOMMENDED wherever the application is reused.


### 8.3 Aliases

A type-def body that is purely a type *reference* — a bare name, with no arguments, no body record, no `&`, and no construction — produces an ALIAS-kind entry whose value is `!meta.alias { target: T }`, where `target` names an entry (§8.1):

- **Simple leaf** (`id => uuid`): `id => !meta.alias { target: uuid }`. The `target` is the immediate referent, not the ultimate type — alias chains (`user_id → id → uuid`, over `id => uuid` and `user_id => id`) appear as distinct entries, each pointing one hop forward. A `target` may be a qualified name, `geo.address`.
- **An application** is **not** an alias: `text_pair => pair<text, text>` is the declared instantiation itself, the substituted record under its name with the application as `@meta.application` on its key (§5.10, §8.2), and `text_list => meta.array<text>` the construction in place, exactly as a sugar-form body — `text_list => [text]` — is (§5.3, §5.6). A `!meta.alias` body is a bare name.
- **An alias of a constructor** (`my_record => meta.record`): `target` names a constructor, and the alias is applied as its target is, by `!` and by an application alike (§3.3.1, §5.3); the kernel's root `set => meta.set_type` is one.

A top-level *constructor* application is not an alias — it resolves as a construction in place (§5.6). `!meta.alias { target: X }` is the explicit spelling of the alias `X` and denotes exactly that (§5.5).

The correction is worth its sentence: an earlier revision filed a declared application by its syntax — a parameterised *reference* — and made the declaration a hop to something else's identity, so that `pet.subtypes` read a name the author never wrote where the author had written `dogs`, and every surface that showed a name had to follow the hop back. Filed by what it is — a construction — the declared name is the entry, which is what a family's `subtypes` index, a tag, and a diagnostic all key on.

**An alias is a hop, not a rewrite.** Resolved output states an alias chain **as the author wrote it**: a use site — a field type, tuple element, choice variant, argument, or binding-record type slot — that names an ALIAS entry names that entry, and `target` names the next. Nothing is flattened, and no annotation records a source-level name, because the chain itself is the record: an earlier revision rewrote each use site to the chain's terminal entry and attached an annotation naming the first hop, which was a second, lossy representation of a walk that happens anyway — a chain of two hops kept the name of the one that carried nothing — and every pass that needs the terminal entry (reader compilation, discrimination-class classification, inhabitance, variant distinctness, reference membership) walks the chain regardless. A processor MAY collapse a chain **after linking, when it compiles for reading** — an ALIAS entry's reader is its target's, named for the entry doing the referring, which is what gives a diagnostic the alias the author wrote for free — and MUST NOT collapse it in resolved output, which is what two conforming processors compare. A chain with no terminal — `a => b`, `b => a` — is a resolver error (§3.4.1), and §11.5's alias-chain limit bounds a long one.

**Where a chain is followed, and where it is not.** Identity of an open record's application follows its arguments' chains to their terminal entries, because an alias is the same type under another name, and a construction's does not (§8.2); subsumption at a typed position follows both chains (§7.2); the source of a refinement or composition, a choice variant judged for `void`, a discrimination class, a reference's membership and a qualified name's first segment are all read after following the chain (§4.2, §4.3, §5.4, §5.7, §5.12). What the chain *never* does is carry meaning of its own: nothing that changes how a value reads may live on an alias, and no bundled document declares an annotation that does — a fact that decides reading, such as an alphabet, lives in the type (§5.5), where every position already names it. That is what makes a rename safe to see through.

**Aliases are not supertypes.** ALIAS-kind entries carry no chain and do not contribute to the `supertypes`/`subtypes` graph: `uuid.subtypes` does not list `id`; `id` has no `supertypes`. The three ways to name a type after another, and what each buys, are tabled in §5.7.

**Declared instantiations are not aliases.** A declared instantiation (§8.2) is PRODUCT-kinded with a `!meta.record` value, not a `!meta.alias` record; an ALIAS entry may *point at* one by name (`alias => text_pair`), and the instantiation itself is a closed, concrete definition. No `!meta.alias` entry carries `@meta.application`.


## 9. The Kernel and the Libraries

Six bundled documents carry the series' vocabulary, and one of them is pre-loaded. Implementations MUST pre-load the kernel (§3.4, §10.1) and SHOULD pre-load the libraries. The normative source for each is the companion artifact (§1.5), pinned by content hash at publication; the kernel's pre-loaded in-memory model is authoritative and its document a description of it.

**The placement convention.** A bundled document's root holds the vocabulary an author writes, and its machinery sits under schema maps named for concepts, related concepts kept together — a library's under one map of its own name, the kernel's under six, two of which `numeric.tn` and `net.tn` add to (§2.2.3). The list is curated, entry by entry, so that an author writes `text`, `!enum [A B]`, `set<text>`, `@doc`, `bytes`, `int32`, `date` and `uuid` bare, and `meta.record`, `meta.identifier`, `meta.type_ref`, `meta.ref<meta.record>`, `meta.ref<method>`, `@atom.ordering:TOTAL`, `@meta.disjoint`, `!numeric.decimal_type {}`, `!temporal.date_type {}` and `!net.uuid_type {}` by path:

| Document | Imports | At the root | Under its maps |
|---|---|---|---|
| `meta.tn`, the kernel | nothing (pre-loaded) | `void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`, `iri_reference`, `regex`, `doc`, `enum`, `set`, `schema` | `meta`, the type system: `meta.top`, `meta.type`, the base kinds `meta.atom`/`meta.product`/`meta.sum`, `meta.alias`, the constructors `meta.record`, `meta.array`, `meta.set_type`, `meta.map`, `meta.namespace`, `meta.tuple`, `meta.choice`, `meta.enum_type` and `meta.declared_type`, the supporting records `meta.type_chain`, `meta.param`, `meta.record_field`, `meta.field_group` and `meta.tuple_element`, the maps `meta.field_map` and `meta.schema`, the enums `meta.product_access_type`, `meta.product_size_type`, `meta.field_role` and `meta.record_extension_type`, the set `meta.enum_set`, the references `meta.reference_type`, `meta.ref`, `meta.field_type_ref`, `meta.type_ref` and `meta.identifier_ref`, the type slots `meta.type_inline`, `meta.type_expr` and `meta.field_type_expr`, `meta.identifier` and its aliases `meta.type_name`, `meta.field_name` and `meta.param_name`, `meta.void_type`, `meta.declared`, the annotation machinery `meta.annotation` and `meta.application`, and the checked assertion `meta.disjoint`. `atom`: the ISO/IEC 11404 datatype properties `atom.ordering`, `atom.bounded`, `atom.exact` and `atom.numeric`, and the mixin `atom.specification`. `numeric`: `numeric.integer_type`, `numeric.integer_size`, `numeric.integer_member_set` and `numeric.count`. `unicode`: `unicode.text_type`, `unicode.text_member_set`, `unicode.normalization`, `unicode.identifier_type`, `unicode.identifier_base` and `unicode.regex_type`. `net`: `net.iri_type`, `net.scheme_set` and `net.scheme_name`. `binary`: `binary.bytes_type` and `binary.bytes_encoding`. |
| `numeric.tn` | the kernel | `number`, `rational`, `complex`, `int8`, `int16`, `int32`, `int128`, `int256`, `uint8`–`uint256`, `float32`, `float64` | `numeric`, beside the kernel's members: `numeric.decimal_type`, `numeric.float_type`, `numeric.rational_type`, `numeric.complex_type`, `numeric.ieee_format`, `numeric.complex_component`, `numeric.decimal_member_set` |
| `temporal.tn` | the kernel | `date`, `time`, `datetime`, `duration`, `period` | `temporal`: `temporal.date_type`, `temporal.time_type`, `temporal.datetime_type`, `temporal.duration_type`, `temporal.period_type` |
| `net.tn` | the kernel | `email`, `ipv4`, `ipv6`, `cidr4`, `cidr6`, `mac`, `hostname`, `host`, `media_type`, `uri_reference`, `uri`, `uuid` | `net`, beside the kernel's members: `net.email_type`, `net.ipv4_type`, `net.ipv6_type`, `net.cidr4_type`, `net.cidr6_type`, `net.mac_type`, `net.hostname_type`, `net.host_type`, `net.media_type_type`, `net.uri_type`, `net.uuid_type` |
| `core.tn` | `numeric.tn`, `temporal.tn`, `net.tn` | the author annotations `deprecated`, `title`, `comment`, `examples`, `read_only`, `write_only` | nothing: it declares no map of its own |
| `policy.tn` | the kernel | nothing | `policy`: `policy.processor` and its parts `policy.identifier_policy`, `policy.script_policy` and `policy.limits`, `policy.restriction_level` and `policy.limit` |

**A library imports the kernel alone; `core.tn` is the bundle.** A schema imports the libraries it uses: one importing `temporal.tn` alone has `date` and the kernel's vocabulary, does not see `int32`, and may declare `int32` as an entry of its own, though not `numeric`, which the kernel declares; one importing `numeric.tn` and `net.tn` has both, their closures meeting at the kernel by identity. Every schema whose closure holds the kernel reserves the kernel's thirteen root names and the map names `meta`, `atom`, `numeric`, `unicode`, `net` and `binary` (§2.2.3). `core.tn` imports the three, so a schema wanting every bundled type imports `core.tn` and reserves those, `temporal` and every root above; `record`, `identifier`, `type_ref`, `field_name`, `bounded`, `value`, `kind` and `core` stay free. No two of the bundled documents declare one root name or one member path, and the maps two of them declare, `numeric` and `net`, are open and the union of their members, so no closure of them collides with itself. `policy.tn` imports the kernel alone; a closure holding it reserves `policy`.

**The kernel, `meta.tn`.** One document, header `!!id` alone and pre-loaded (§3.4): its one wait cycle — the entries around `meta.record`, `meta.array`, `meta.set_type`, `enum`, `meta.map`, `meta.field_map`, `meta.reference_type`, `numeric.integer_type`, `unicode.text_type`, `boolean`, `integer`, `text`, `numeric.count`, `numeric.integer_size`, `meta.field_type_ref`, `meta.type_ref`, `meta.type_chain`, `meta.record_field`, `meta.field_group`, `meta.field_role`, `meta.enum_set`, the type slots `meta.type_expr`, `meta.field_type_expr` and `meta.type_inline` and the constructions their declarations write in place — every name its declarations reference, and the concepts that belong with them. The kernel's tag `!schema` names its own root `schema => meta.schema`, an alias, and resolved output writes every nested map `!meta.schema`. A kind and a constructor are the kernel's by declaring document, which is `meta.tn` alone; the libraries' constructors are ordinary constructors of the documents that declare them, and a schema may contribute a member to any kernel map on §2.2.3's terms (`meta => !schema { code_type => unicode.text_type ^ { max_length?: = 4 } }` beside `code => !meta.code_type {}`).

**Every name resolves from the root, inside the bundled documents too.** An entry names a member of a map by its path: `integer => !numeric.integer_type {}`, `record => meta.product & { fields: meta.field_map … }`, `format: numeric.ieee_format`, `@atom.ordering:TOTAL`. There is no lexical scope.

Each atom family is a constructor/instance pair (§4.2): the constructor lists the family's constraint fields, and the canonical instance lives in the same document — `void`, `integer`, `int64`, `text`, `meta.identifier`, `iri_reference`, `iri`, `regex` and `bytes` in the kernel; `number`, `rational`, `complex`, the sized integers and the floats in `numeric.tn`; `date`, `time`, `datetime`, `duration` and `period` in `temporal.tn`; `email`, `ipv4`, `ipv6`, `cidr4`, `cidr6`, `mac`, `hostname`, `host`, `media_type`, `uri_reference`, `uri` and `uuid` in `net.tn`. Every facet holding a value of the constrained family is typed by that instance — a float's bounds by `number`, a network list by `[cidr4]` or `[cidr6]` (§5.5, §7.4), and every count by `numeric.count` (§9's `numeric` map), the non-negative half of `int64`. Two rules hold across every family and are stated once in `numeric.tn`'s header: `multiple_of`, wherever it appears, is strictly positive, tests the value's magnitude with the sign ignored, tightens only to an integer multiple, and composes with every other facet; and `members`, wherever it appears, has every member satisfying the body's other facets — `unicode.text_type`'s pattern included — and a refinement shrinks the set on the numeric tiers and sets it once on `unicode.text_type` (§5.7, §7.4). Every set-typed field is emitted in source declaration order (§7.5). The classifying annotations the libraries state their atoms with are the kernel's `atom.ordering`, `atom.bounded`, `atom.exact` and `atom.numeric` (§6).

**What the kernel holds, and what the libraries do.** The kernel holds what the type system needs to describe itself — its one wait cycle, every name the cycle's declarations reference (`integer`, `text`, `regex`, `iri`, `boolean`, the references), and the concepts that belong with them (`meta.tuple`, `meta.choice`, `meta.param`, `bytes`, `doc`, `set`) — and the atoms every schema writes. A data-format family most schemas never use belongs in a library outside the kernel, where its names cost only the schemas that import it: the numeric tiers beyond `integer` and `int64`, the temporal families, the network and identifier families. `core.tn` holds the author annotations and nothing else, so that a schema importing a library alone may declare `title` or `deprecated` for itself.

**JSON.** A JSON document is read through the JSON encoding of [TSON-JSON], a second encoding of this model and not a mode of the TSON notation ([TSON-DATA] §6): it maps a JSON number to `number`, JSON `null` to void — admitted where the position is voidable (§7.6) — and a JSON object to a record where its keys are names its record admits, identifiers by default (§5.2). Every type doc in the artifacts describes its value space without reference to any encoding and names TSON text where it means it; the family facts of §5.2 and the `disjoint` fact of §5.4 are the model's, and both encodings read them.

**Guidance for schemas that extend the vocabulary.** Any schema may declare a constructor (§4.2), and three rules keep an extended vocabulary coherent with the kernel's. First, **a slot that names a type is typed by a reference over a constructor, a slot that names a value by a reference over its type, and a slot that holds a sibling reference by `meta.field_type_ref`** (§4.2, §5.2, §5.12): a field whose value is the name of a type a value can have is `meta.type_ref`, or a narrower reference — `request?: meta.ref<meta.record>` admits the names of the record types and refuses `uuid`'s — which is what makes the slot judged at link and visible to every reference mechanism (§8.1, §8.3); a field whose value is a value declared elsewhere, written by its name, is `meta.ref<T>`, which declares the spelling and the judgement at once; a field read against the type a sibling names is `kind!`. The kernel's own `meta.enum_type.name_type`, `meta.record.name_type` and `meta.namespace.key_type` are `meta.identifier_ref`, and `meta.reference_type.of`, `meta.alias.target`, `meta.type_chain`'s lists and `meta.application` are `meta.type_ref`. Second, a schema's own vocabulary — the records its constructors' instances hold, the namespace types it declares — is declared beside its constructors, under a map of its own where it is machinery (`api => !meta.namespace { key_type: method_name  value_type: method }`, §4.1), and reserves what it declares in every importer. Third, the kernel's `regex` atom is pinned to a specification (`atom.specification`, §4.2), and the pin is a strict gate: an implementation MUST implement the pinned dialect as specified — not a host library's near-relative — and MUST document any divergence it cannot avoid; a pattern using a feature outside the pinned dialect is a resolver error at schema load, not a silently host-dependent behaviour. The same discipline applies to every spec-pinned atom an extension declares: the `spec` pin is the contract, and "whatever the platform regex engine does" is never a conforming reading. A constructor composing a base kind directly declares a new *form*, which a processor backs with readers and writers of its own; a schema that wants its own atom family composes a constructor the processor already backs, or declares a form and ships the processor that reads it.


## 10. Schema Resolution Model

Schema URLs are **logical identifiers first**: a URL names a schema, and resolving it does not by itself require a network request. Implementations resolve schema references through a **schema library** — a local store mapping canonical identities ([TSON-DATA] §2.2.1) to schema content. Fetching is a permitted but opt-in way to *populate* the library (§10.1, §11.2), not the meaning of a reference.


### 10.1 The Schema Library

When the resolver encounters an `!!import` directive, it looks the reference up in the library — the reference read to its canonical identity in any spelling ([TSON-DATA] §2.2.1); if not found, the resolver does not attempt to fetch unless fetching is enabled (§11.2), and reports one of two outcomes, neither a finding about validity ([TSON-DATA] §8.1). A schema this processor **will not** supply — one it does not hold and will not fetch, a host off its allowlist, an illegal identity, a missing required pin, or one past its size limit (§11.2) — is a **refusal**: a decision of this deployment's configuration, as deployment-specific as an identifier policy, which a processor configured otherwise does not make and no rerun changes, so the document is rejected *here* and the sender's fix is to name a schema this deployment will supply. A closed library's miss is a refusal, never "not found": the library is the deployment's whole configuration, so a schema outside it is one this deployment will not supply. A schema this processor **could not** obtain — a source that looks beyond its configuration (an origin, a directory) and found nothing at the reference, a host that did not answer, a fetch that timed out — leaves the document **undetermined**: nothing has read the schema, so nothing is known about whether the document conforms, and whoever holds the fix, for nothing at the reference the sender most likely does, a reference with nothing behind it being most often a typo. Both are distinguishable from the four categories, in the same report, located at the reference, and name their reason (not held, not permitted, too large; not found, unreachable, timed out), since a sender acting on a report needs to tell "your document is wrong" from "I will not look here" from "I could not look". A document with both an undetermined import and an ordinary error reports both, and a rejection settles the summary whatever else went unjudged ([TSON-DATA] §8.1). The library is populated through three mechanisms, in order of precedence:

- **Pre-loaded schemas.** The implementation ships the kernel as a pre-loaded entry and SHOULD ship the bundled libraries. Pre-loaded schemas are authoritative — their in-memory representation takes precedence over any external source (§3.4).
- **Registered schemas.** Applications register schemas before parsing documents that reference them, from local files, embedded resources, or any application-specific source.
- **Fetched schemas (optional).** Implementations MAY support fetching by URL; fetching MUST be explicitly enabled by the application — never the default — and is subject to §11.2. Production systems SHOULD register all required schemas at startup rather than fetch at runtime.

**Identity agreement.** Registration whose target identity differs from the canonical identity of the content's declared `!!id` is an error — the library MUST reject it as identity confusion. The comparison is over canonical identities, so scheme variance agrees; a differing host or path does not. Content with no `!!id` MAY be registered under an application-supplied identity (a development-mode convenience, §2.2.1); content with an `!!id` is registered under that identity and no other.

**Schema sources are schema documents.** Whenever the library is populated from a document — registered or fetched, for every target of `!!import` — that document MUST classify as a schema document ([TSON-DATA] §2.2): its root tagged `!schema`. A resolved form (resolver output, §8) is a schema document by that test and is nonetheless not a schema *source*: its derived fields cannot be verified from the document alone, it is non-canonical and not hash-pinnable under a source's identity, and reading it as the resolver's output is the caller's explicit choice (§8.1), never the library's inference. An implementation MUST NOT silently resolve an `!!import` to a document the caller has identified as a resolved form, and a resolved form read as output does not take derived indexes on trust: `subtypes` and `disjoint` are discarded and recomputed; `chain.supertypes`, `chain.parents` and `@meta.application` are read as stated and verified; author-written annotations are preserved as data; and a value entry (§4.1) reads like any other entry — its value the data its type describes, validated against it, with the rule that no type position names it re-run on the map.


### 10.2 Hash-Pinned References

The hash-parameter URI convention — the algorithm-named query parameter, lowercase full-length hex, the query-is-hash-parameters-only rule, canonical identity, and the mismatch rule — is defined in [TSON-DATA] §2.2.1. This section defines how the library applies it. **Bytes identify a document, for distribution and pinning; names and parsed structure identify an entry, for typing** (§8.2): a whitespace edit to a schema changes its digest and every pin that names it, and none of its entries.

Library keys are canonical identities: a plain reference, its `http`/`https` variant, and its hash-pinned form all name the same library entry — the pinned form additionally demands verification. When a hashed reference resolves, the implementation MUST verify the library's content against the declared hash before use; a mismatch is a **resolver error** — a verdict, unlike a refused or undetermined schema (§10.1), because the schema *was* obtained, its bytes were read, and they are not the bytes the document's reference commits to, which is a finding about the reference the document carries — and the library MUST NOT silently substitute mismatched content. When registration supplies a document whose declared `!!id` itself carries a hash, the implementation SHOULD verify at registration time.

**Hashes attach to the canonical entry.** Verification is a property of the canonical identity, not of each reference. Within a single resolution's reference closure (§3.4.1), the digests declared across all references to one identity form a set, and the set determines the identity's treatment. An empty set resolves unverified. A single digest is verified once against the library entry's content, before any reference to the identity resolves; every reference — pinned and plain alike — then resolves to the verified entry. A plain reference alongside a pinned one is therefore not a conflict but an inheritance: it rides the pin, and it inherits the pin's failure — a verification failure applies to the identity for the whole resolution, and a plain reference MUST NOT proceed with content a declared digest has rejected. Two distinct digests for one identity is a resolver error: at most one describes the real bytes, and the library MUST NOT choose between them. A verified entry, once cached under its canonical identity, is immutable. A *failed* verification SHOULD NOT be cached under the canonical identity; an implementation MAY maintain a separate negative cache keyed on the full reference string.

**Pins to the pre-loaded kernel.** The kernel resolves to in-memory structures (§3.4, §10.1), but a pinned reference to its identity is an ordinary pinned reference and MUST be verifiable: an implementation MUST hold the published content digest (or the canonical document bytes) of the kernel its structures implement, and MUST verify declared pins against it — a mismatch is the ordinary verification error. The kernel's header is its `!!id` alone and carries no reference of its own, so every reference to it may be pinned and the chain grounds in bytes the implementation ships; the libraries pin the kernel and `core.tn` pins the libraries (§13.2).

An implementation MAY offer a strict resolution mode that rejects any closure containing an identity whose digest set is empty (the pre-loaded kernel excepted), for deployments requiring a fully verified chain; the per-identity rule above is the default and the interchange baseline.

When no digest is declared for an identity anywhere in the closure, it resolves without integrity verification — appropriate for development, NOT RECOMMENDED for production interchange. References that cross a trust boundary SHOULD be hash-pinned: a schema's `!!import` values pin its dependencies; a data document's `!!import`s pin its vocabulary. Because pinning is per-identity, one pinned reference raises the guarantee for every reference to that identity in the closure, and a published plain reference never prevents downstream pinning. Pinning composes into the verification chain of [TSON-DATA] §2.2.1 — a consumer holding a single hashed reference can verify a document together with its schema, the libraries it imports, and the kernel, provided each rung's own references are pinned; published schemas SHOULD pin every reference they carry.

```
!!import:"https://example.com/people.tn?sha256=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
```


### 10.3 Canonical Identity

Reference identity is defined by [TSON-DATA] §2.2.1: two references name the same document if and only if their canonical identities — the host's canonical text plus the path, scheme and query removed — are byte-for-byte identical. The library applies this as its key rule:

- `http://tson.io/2026/40/m/meta.tn` and `https://tson.io/2026/40/m/meta.tn` — **same** entry; the scheme is a transport hint.
- `https://tson.io/2026/40/m/meta.tn` and the same URL with `?sha256=…` — same entry; the pinned form additionally requires verification (§10.2).
- `https://tson.io/2026/40/m/meta.tn` and `https://elsewhere.example/2026/40/m/meta.tn` — **different** entries; the host is load-bearing, so a fetch-endpoint change cannot silently redirect a name.
- `https://xn--bcher-kva.example/sch%c3%a9mas/x.tn` and `https://bücher.example/schémas/x.tn` — **same** entry; a reference in any spelling of an identity is read to the canonical form, the host as a `host` value and the path's percent-encoded UTF-8 read back ([TSON-DATA] §2.2.1).

The identity profile holds a document's own `!!id` to one spelling — the canonical form, with no userinfo, port, percent-encoded unreserved character, dot-segment or fragment — and reads a *reference* in any spelling of the same identity to that form before comparison; what has no IRI form is an error, never a candidate for normalization ([TSON-DATA] §2.2.1). A fetcher that reaches a host beyond US-ASCII does so in the URI spelling, and a report of such a fetch SHOULD name both the identity and the URI requested. The pin rides in the query so that it reaches the origin and the cache, and the fragment is reserved for a later intra-document reference ([TSON-DATA] §2.2.1). Content addressing is over bytes, and every identity rule above a digest is over value spaces (§5.5): two spellings of one octet string hash the same document to one identity and are one map key, and a content-addressed reference is never encoding-dependent.

**Confusable identities.** Because identity selects a document's entire type vocabulary, a look-alike identity is a higher-value spoof than a confusable field name. Implementations processing untrusted references SHOULD surface, and MAY reject, two registered identities that differ only in ways the profile does not already forbid — e.g. visually confusable host labels (UTS #39; [TSON-DATA] §9.4).


## 11. Security Considerations

The security considerations of [TSON-DATA] §9 apply; this section adds the schema layer's.


### 11.1 Schema Validation

Documents without `!!import` carry no type guarantees — only base type resolution applies. Applications processing untrusted TSON input SHOULD validate against a schema before use.


### 11.2 External References

Schema URLs are logical identifiers resolved through the schema library (§10), not fetch instructions; no network access occurs unless the application explicitly enables it. Implementations that support optional fetching MUST treat it as opt-in, disabled by default, and when enabled SHOULD enforce:

- **Transport security.** Fetched references MUST use an authenticated, encrypted transport (in practice, `https`); a fetcher MUST NOT downgrade to a cleartext scheme for an unpinned reference.
- **Allowlists.** Restrict fetchable references to approved hosts or path prefixes, matched on canonical identity (§10.3) so scheme variance cannot evade the list; a host is matched as a `host` value ([TSON-DATA] §5.5), so an allowlist entry in either label form admits an identity's host in either, and a look-alike host (`schemаs.example.com` with a Cyrillic `а`) is refused unless the operator listed it, the comparison being by name. `!!import` references are the one channel through which a document selects its vocabulary, so a malicious reference could load an untrusted schema that redefines expected types. A reference the allowlist refuses is reported as a refusal ([TSON-DATA] §8.1; §10.1), and the report SHOULD NOT disclose the allowlist: telling an untrusted sender which hosts this processor will fetch from is reconnaissance for request forgery, where a name policy's statement serves the sender and costs nothing.
- **Content hash verification.** Require hashes on fetched references and reject mismatches — preventing tampering and silent schema drift; a reference whose pin is required and missing is a refusal.
- **Size limits** on fetched content; a schema past the limit is a refusal.
- **No recursive fetching.** A fetched schema's own `!!import` directives MUST NOT trigger further fetches; transitive dependencies must be pre-registered or pre-fetched.
- **Caching.** Cache verified schemas locally, keyed on canonical identity; a verified hash-matching schema is immutable. A failed verification MUST NOT overwrite or poison the canonical-identity entry (§10.2).

Production systems SHOULD pre-register all required schemas at startup and disable runtime fetching entirely.


### 11.3 Directive Security

Directives are a control channel that affects interpretation. `!!import` is the one directive that selects and extends a document's vocabulary; applications processing untrusted input SHOULD restrict which import URLs are permitted, for data documents and schema documents alike. See [TSON-DATA] §9.3.


### 11.4 Name Hygiene at the Schema Layer

[TSON-DATA] §8.2 defines the name-hygiene mechanisms — skeleton distinctness within a scope, `Identifier_Status`, and a restriction level — as policy that every conforming processor implements and enforces by default, never as validity. This section supplies the schema layer's **scopes** and the one rule the scope structure adds.

The named scopes at this layer are: the members of one enum (§7.4), under its `name_type`'s profile — all three mechanisms, every enum's name type being an identifier family; the field names of one record definition (member labels of its groups included, §5.11), under the record's `name_type`'s profile (§5.2), which is the same scope the data layer applies to a schemaless record ([TSON-DATA] §2.5, §8.2) — a profile's own added characters (`json_name`'s `@` and `$`, a medial space) are exempt from mechanism 2 and read by mechanisms 1 and 3, and the floor on what a profile may add is §7.4's; the declared names of one schema map — the document's root, and each nested `!schema` map as a scope of its own, the per-name rules judging each member's own name (§4.1); and **the merged namespace at `!!import`** — the sharpest, because §2.2.3 decides collisions by exact identity, which a confusable pair passes by construction: two entries a reviewer reads as one name are, to the resolver, two names. Choice variants are not a scope: a variant is a reference to a declared name, so two confusable variants are two confusable entries in the namespace and are reported there. A template's parameter list is not a scope either (§5.10): one author writes it whole on one line, and the list stays short so that it stays reviewable. Mechanisms 2 and 3 are per-name and reach every identifier position — each segment of a qualified name among them — without a scope ([TSON-DATA] §8.2); only mechanism 1 needs the list above.

**Values that are names.** A value whose declared type is an identifier family (§7.4) is a name wherever it stands, and the schema layer is what makes it one. Mechanisms 2 and 3 reach every such value, in every encoding, under the identifier policy and under the family's own profile: a character the profile adds meets no `Identifier_Status` rule, and a per-segment level divides the value at the profile's own separators ([TSON-DATA] §8.2). Mechanism 1 gains two **data scopes**, each a set the type itself delimits: the key set of one map whose key type is an identifier family — a namespace value's keys among them (§4.1) — and the element set of one array whose elements are unique — a `meta.set_type` instance, or any array stating `unique_items` — and whose element type is one. An array that admits repetition is not a scope, since nothing says two of its elements name two things. A pair is refused at the second key or element, as a pair of field names is, and like every other scope these are skipped where the identifier policy switches skeleton distinctness off. A value a *schema* writes is judged the same way, under the same policy: a field's default or fixed value of such a type when the schema loads, since a default reaches every document that omits the field, and a value in a payload the scopes above do not cover — the keys of a value entry's map (`orders => !api { place_order => … }`, whose `key_type` is an identifier family), a qualified name's segments (§5.12), or an annotation's value. A reference's value is a name of a declared entry and is judged where the entry is declared. A map keyed by `text` remains data: `admin` and `аdmin` may be two legitimately distinct keys, and nothing about a map position says they are names — the key's type is the schema saying so.

Because identity selects a document's entire type vocabulary, the import merge is exactly where the compositional hazard bites — two independently published, independently pinned schemas, each clean alone, can collide on a skeleton when one imports the other (`list_item` beside `Iist_item`), and the fix is a rename in a document the importing author may neither control nor republish. That is the strongest single reason the mechanism is policy rather than validity: as a validity rule it would make such an import impossible to write; as a policy rule the operator relaxes the check, visibly and in code, and proceeds. A refusal at the merge is reported at the second arrival with both declaring schemas named, in the manner of §2.2.3's collision diagnostic, as [TSON-DATA] §8.1's refused outcome — in the same report as the four categories, distinguishable from them — and the report carries the UCD version and the identifier and token policies it was judged under ([TSON-DATA] §8.2). The policy is not a property of a schema and no schema carries one: the merged namespace is what the check runs over, and only the importing site knows it.

A data document's *field names* need no scope of their own under a schema: a data field name is valid only if it matches a declared one, matched in the record's name type's form (§5.2, §7.2), so it inherits the declaration's verdict; a name the record does not declare is judged by the name type first: a value of it is a name, and meets the identifier policy under the type's profile before closure reports it as unrecognised, and one that is not is unrecognised and meets no name rule. Its values typed by an identifier family are the data scopes above. The confusable-identity concern for schema references themselves is §10.3's.


### 11.5 Resource Limits for Schemas

[TSON-DATA] §9.1 bounds what a processor will spend on a *document* under one limits policy — every limit with a default, a positive `int64` (`policy.limit`), configurable or documented, its threshold reported on refusal, and a refusal that is not a verdict. A schema is untrusted input too wherever it is accepted over the wire or reached through `!!import`, and a schema document is a document, so §9.1's counters apply to it as written. What §9.1 cannot see is the work resolution adds, and this section names those limits on the same terms, as part of the same policy and reported through the same surfaces; `policy.limits` carries each (§9):

| Limit | Applies to | Default |
|---|---|---|
| import closure (`max_import_closure`) | schema documents reachable from one header through `!!import` (§3.4.1) | 64 |
| entries (`max_schema_entries`) | declarations in one schema map, nested maps' members included | 65,536 |
| alias chain (`max_alias_chain`) | hops from a use site to a terminal entry (§8.3) | 64 |
| supertype chain (`max_supertype_chain`) | length of one type's `chain.supertypes` (§8.1) | 64 |
| wait depth (`max_wait_depth`) | the longest chain of rule-1 waits in one document's resolution, an entry waiting on the next being ready (§3.4.1) | 64 |

Exceeding one is a limit refusal, not a resolver error: the schema may be well-formed, load cleanly on the next processor along, and be refused here because *this* deployment declined to spend the resources, and the diagnostic names the limit and the configured threshold. The defaults are set at the tight end on [TSON-DATA] §9.1's argument — a schema that fits them loads everywhere — and a deployment raises them; the bundled documents resolve under the defaults whatever a deployment sets. A processor holding a limit in a narrower type than `int64` caps a larger configured value at the widest it holds ([TSON-DATA] §9.1).


## 12. ABNF: The Schema Grammar

This section defines the schema grammar — the schema document's body grammar, the second of the two body grammars behind the shared document header ([TSON-DATA] §2.2, §7.4). This grammar adds no token of its own ([TSON-DATA] §7.3): the productions below consume the same token stream, and the reserved special tokens of [TSON-DATA] §7.2.5 receive their meaning here — `+`, which that section emits as a special token from Revision 37, among them. The reference production — a qualified name with an argument list, and the sibling reference — is [TSON-DATA] §7.4's; this grammar's `sibling-ref` and `type-ref` reuse it, `type-ref` with a wider argument.


### 12.1 The Schema Grammar

A schema document is the header [TSON-DATA]'s grammar defines, followed by the root value: its annotations, the tag `!schema`, and the **schema map**, the annotated, braced declaration map. `ws`, `ws1`, `separator`, `token`, `unquoted-token`, `annotation`, `field-name`, `record`, `empty-brace`, `identifier`, `qualified-name`, `sibling-ref`, `id-directive`, `import-directive` and `core-value` are imported from [TSON-DATA] §7.3, §7.4, and §7.7; the data grammar's value productions appear at exactly two points — the full `core-value` as the constructor-application payload (`instance`, §5.6), and its braced subset (`record` / `empty-brace`) as the value-refinement body (`value-refinement`, §5.5), the same text under two heads, which is the desugar §5.6 states. No production of this grammar uses the full `data-value`: a record-refinement body is a braced `record-def` (§5.7), and a field-modifier value is restricted to a bare token — or, for the selector `=?`, nothing at all — (§5.2), never the void sentinel, annotations, a tag, or a container.

A `schema-map` copies the shape of [TSON-DATA]'s `map` production but requires at least one entry — `{}` at schema-body position is a parse error. An entry is called a **declaration**. Annotations before the tag bind to the schema; annotations at the head of an entry bind to the key; annotations after `=>` bind to the type definition (§2.1, §6).

```
; ── Schema document ───────────────────────────────────────

schema-document  = [ id-directive ] ws *( import-directive ws )
                   *( annotation ws ) "!schema" ws schema-map ws
                 ; the root's tag, "!schema", names the pre-loaded kernel's
                 ; root `schema` (§2.1); it is what classifies the document
                 ; ([TSON-DATA] §2.2). A root with any other tag —
                 ; `!meta.schema` included — or none, is a data document.

; ── Schema Map (schema body) ──────────────────────────────

schema-map       = "{" ws schema-map-entry
                   *( separator schema-map-entry ) [ ws "," ] ws "}"
                 ; a comma may follow a value ([TSON-DATA] §2.4);
                 ; every list in this grammar admits one after its
                 ; last element

schema-map-entry = *( annotation ws ) type-name ws "=>" ws
                   *( annotation ws )
                   ( [ definition-mark ws ] type-def
                   / nested-map )

nested-map       = ( "!schema" / "!meta.schema" ) ws schema-map
                 ; a schema map nested as an entry (§4.1): its members
                 ; are declarations of the document, named by path

definition-mark  = "abstract" / "final"
                 ; how a record may be realised (§5.2): one word,
                 ; read unconditionally at this one position and an
                 ; ordinary identifier everywhere else; a bare mark
                 ; with no type-def is a declaration missing its
                 ; definition; a mark on a non-record definition is
                 ; a resolver error; written before the parameter
                 ; list of an open record (§5.10)

; ── Type Definition (declaration right-hand side) ─────────

type-def = value-refinement              ; never parameterised
         / instance
         / [type-params] structural-def
         / [type-params] type-expr
         ; there is no constructor marker: an entry is a
         ; constructor by being IS-A `meta.top` (§4.2), and `~` is
         ; a special token with no role at type-def position.
         ; type-params parse before any body; the resolver admits
         ; them before a record body alone — braces, a
         ; composition, a refinement or `!meta.record` (§5.10)

type-params = "<" ws type-param *( separator type-param ) [ ws "," ] ws ">"
type-param  = param-name [ ws ":" ws type-expr ]
            ; the written type is the parameter's bound (§5.10)
param-name  = type-name   ; same lexical class as type-name

structural-def = refined-def
               / construction-def
               / record-def

refined-def  = ( sibling-ref / type-ref ) ws "^" ws record-def
             ; record refinement, and constructor refinement (§5.7);
             ; the head's optional arguments apply an open record
             ; (§5.7, §5.10). No removal clause.

construction-def = supertype-ref 1*(ws "&" ws supertype-ref)
                   [ws record-def] [ws removal-set]
                 / supertype-ref ws "&" ws record-def [ws removal-set]
                 / supertype-ref ws removal-set

supertype-ref = sibling-ref / type-ref
              ; composition and subtraction operands are named
              ; type references, optionally with arguments —
              ; never paren, bracket or map forms (§4.3, §5.8); a
              ; sibling reference parses here, as at every reference
              ; position, and the resolver refuses it (§5.2)

removal-set  = "-" ws "{" ws field-name
               *( separator field-name ) [ ws "," ] ws "}"

record-def   = "{" ws [record-entry *(separator record-entry)
               [ ws "," ]] ws "}"
record-entry = field-def / group-def

value-refinement = "!" qualified-name ws "^" ws ( record / empty-brace )
                 ; value refinement (§5.5): the type's own field
                 ; bindings — the braced subset of the core-value
                 ; payload `instance` takes, read by the same data
                 ; grammar; the target MUST resolve to a value whose
                 ; type states refinement rules (§3.3.1, §5.7)

instance     = [type-params ws] "!" qualified-name ws core-value
             ; constructor application (§5.5): the target resolves
             ; in the document's namespace (§3.3.1) to a constructor,
             ; `meta.top` or IS-A it — `meta.alias` included (§8.3)
             ; — or to an ordinary type, which makes the entry a
             ; value entry (§4.1). The payload is a core value —
             ; braced bindings or the positional form (§5.6) — never
             ; annotations or directives; an application inside it
             ; is the reference production of [TSON-DATA] §7.4,
             ; written unquoted. A parameter list is admitted before
             ; `!meta.record` alone, which declares an open record
             ; (§5.10).

; ── Field Definitions ─────────────────────────────────────

field-def      = *annotation field-name ["?"] ws ":" ws
                 ( field-type field-modifier
                 / field-type
                 / field-modifier )
               ; the "?" on the name answers omission (§5.2);
               ; "a?:" lexes as name, "?", ":" with no lexer change

field-type     = type-expr ["?"]
               ; the "?" on the type answers a written "_" — the
               ; field is voidable (§5.2); a sibling-ref here is a
               ; dependent field (§5.2)

field-modifier = ws ("~" / "=") ws token
               / ws "=" ws "?"
               ; "~" default, "=" fixed: the value is a single
               ; scalar token, never a compound value, an
               ; application or the void sentinel (§5.2); "=?" is
               ; the selector a discriminated family's members pin
               ; (§5.2) — two special tokens, no value

; ── Field Groups (§5.11) ──────────────────────────────────

group-def    = *annotation "(" ws group-option
               *( ws "|" ws group-option ) ws ")" [ "?" / "+" ]
group-option = group-member *( separator group-member )
group-member = *annotation field-name ["?"] ws ":" ws type-expr ["?"]
               ; "|" separates options and a separator the members
               ; of one option. A "?" on a member's name marks it
               ; optional within its option; the "?" on its type
               ; makes it voidable; no modifier is admitted, a
               ; group never injecting. The group's own "?" makes
               ; it optional; "+" is sugar for at-least-one and is
               ; admitted only where every option is one unmarked
               ; member. Which groups restate plain fields or
               ; another group, and are refused for it, is the
               ; resolver's check (§5.11).

; ── Type Expressions (any type position) ──────────────────

type-expr = paren-type
          / bracket-type
          / map-type
          / sibling-ref
          / type-ref
          ; one token of lookahead after a name ("!", "<", or
          ; neither) picks sibling-ref, an application, or a name

type-ref  = qualified-name [ "<" ws type-args ws ">" ]
          ; a reference to a type: a name, or an application —
          ; [TSON-DATA] §7.4's production with the wider argument
          ; below.

; ── Compound Type Expressions ─────────────────────────────

paren-type = "(" type-expr "|" type-expr *("|" type-expr) ")"   ; choice, 2+ variants

bracket-type = "[" element-type [ ws ";" ws size-spec ] ws "]"           ; array
             / "[" element-type 1*(separator element-type) "]"           ; tuple

map-type     = "{" ws map-key ws "=>" ws element-type
               [ ws ";" ws size-spec ] ws "}"                            ; map sugar (§5.3)

map-key      = sibling-ref / type-ref
             ; a name: an application parses and the resolver
             ; refuses it, a key being declared and named, and
             ; paren, bracket and map forms do not parse (§5.3);
             ; a sibling reference parses and the resolver
             ; refuses it (§5.2)

element-type = type-expr [ "?" ]
             ; nesting is this recursion: [[T; N]; N] and
             ; {text => [order; 1..]} work by the same rule
             ; that makes [[T]] work, each form written in place
             ; (§5.3); a sibling-ref is refused here by the
             ; resolver (§5.2)

size-spec    = size-bound [ ws ".." ws [ size-bound ] ]
             / ".." ws size-bound
             ; a size-bound is decimal-natural (§5.3)

; One production per container, at every position: an
; element's "?" belongs to element-type inside the brackets,
; and a field's own "?" to field-type, so "xs: [T?]?" is
; unambiguous — element voidable, field voidable.

; ── Terminals ─────────────────────────────────────────────

type-args  = type-arg *(separator type-arg) [ ws "," ]
           ; separator = ws "," ws / ws1; a trailing comma
           ; follows a value and changes nothing
type-arg   = type-expr
           ; wider than the data grammar's in what parses: of
           ; an open record's application, an argument is a name
           ; or a parameter, and a sugar form or an application
           ; parses and the resolver refuses it, the form being
           ; declared first; of a constructor's, inside an open
           ; record, a form over a parameter is held (§5.3); a
           ; sibling-ref is refused here by the resolver, and
           ; a number or a quoted string is no type-expr, an
           ; argument being a type (§5.3)
size-bound = unquoted-token
           ; text MUST match the decimal-natural production
           ; of [TSON-DATA] §7.6; a parameter's name here is a
           ; resolver error (§5.3)
type-name  = identifier
           ; [TSON-DATA] §7.7: a declared key is one segment, an
           ; unquoted token whose decoded text matches the
           ; identifier grammar — XID_Start-initial, so no name
           ; begins with a digit, a sign, or a dot, which is what
           ; makes numbers undeclarable (param-name shares the
           ; rule). Every referenced name and every `!` head is a
           ; qualified-name, each segment held to the same grammar.
           ; A token in name position that fails the grammar is a
           ; parse error. field-name is any unquoted or single-line
           ; token ([TSON-DATA] §2.5, §7.4) — in field-def,
           ; group-member and removal-set as in data — and is
           ; judged not by this grammar but at link, by the
           ; record's name type (§5.2): a name that is no value of
           ; it is a resolver error, and under the default name
           ; type that is the identifier grammar. The schema
           ; grammar and the data grammar share the production.
```

Notes:

- The `type-params` slot declares an open record's parameters (§5.10), each optionally followed by `:` and its bound, decided on the one token after the name; parameters take precedence over namespace lookup, and a reference to an open record MUST supply every argument, at every position (§5.10).
- `definition-mark` is read unconditionally: `pet => abstract base & { … }` is a marked composition, and a type named `abstract` cannot stand alone as a declaration's whole body, which is the sole reservation the two words carry (§5.2, [TSON-DATA] §7.7). ABSTRACT's rule — no direct instances, a value at a position typed by it a value of some subtype, a tag naming the base itself refused — is the rule the kernel's own `meta.top`, `meta.type`, `meta.atom`, `meta.product` and `meta.sum` are read by, each marked `abstract` (§4.1), so a direct application of one is refused as any abstract record's is.
- `=?` is two tokens the lexer already emits — `=` then `?` — admitted by `field-modifier` only with no value; `a: T =? v` is a parse error. It is refused by the resolver on a name carrying `?`, on a voidable type, on a group member, on a dependent field, and on a type that is not an atom-family instance or an enum (§5.2).
- `sibling-ref` is [TSON-DATA] §7.4's — an unquoted token of one segment with an adjacent `!` followed by whitespace — and is a `type-expr` alternative picked by one token of lookahead after the name; `[ kind! ]`, `box< kind! >`, `( kind! | text )` and `x => kind!` parse, `[kind!]` does not, and every position but a record field's type refuses one at the resolver (§5.2).
- `paren-type` produces choice types; choices require at least two variants — `(T)` is a parse error.
- `group-def` produces field groups (§5.11): one or more options separated by `|`, each one or more members. That a group has at least two members, and the rules giving each presence rule one spelling, are the resolver's (§5.11). Inside a record body, `(` at entry position (after any leading annotations) opens a group; `(` after a `field-name ":"` opens a `paren-type`. The two never collide — a group is an entry, a choice is a type expression. A `?` or a `+` after the closing `)` marks the group; member positions reject modifiers by grammar.
- A `?` after a field's *name* records `optional: true` on the `meta.record_field`; a `?` after a *type* at a field, tuple position, array element, map value or group member records that the position admits `_` (`voidable: true` on a `meta.record_field`, `meta.tuple_element`, `meta.array` or `meta.map`); a `?` after a group member's *name* lists it in the group's `optional_members`; a `?` after a group's closing `)` records `optional: true` on the `meta.field_group`, and a `+` there lowers to the one-option form (§5.11). Those are the only positions, and there is no generic "optional type" in TSON.
- `type-def` reaches the bracket and map forms through `type-expr` like any other position; there is no separate declaration-level container production, and no positional restriction on size specifiers or element/position `?` (§5.3).
- `instance` is decidable on one token after the optional parameter list: `!` opens an `instance`, with or without a preceding `<…>`; `<` only ever starts `type-params`, so consuming it first costs no lookahead. Inside the `!` branch a following `^` separates `value-refinement` from `instance`; `value-refinement` admits no parameter list — a parameterised refinement of an instance is no form, and `<…> ! name ^` is a parse error.
- A `!` head naming an ordinary type declares a value entry (§4.1); one naming `schema` or `meta.schema` is `nested-map`, and the payload is the schema grammar.
- The trailing record-def in `construction-def` is optional (`customer => address & contact` is valid). When a `{` follows a `&`-chain, it always belongs to the construction's record-def.
- The refined-def target and every `supertype-ref` operand are restricted to a named reference — a `type-ref`, a name optionally with type-args, or a `sibling-ref`; inline structural forms cannot precede `^`, `&`, or `-` by grammar (§4.3); a sibling reference parses there, as at every reference position, and the resolver refuses it (§5.2). A `^` whose resolved target states no refinement rule for a field the body names (a container instance, for example) is a resolver error reported with the target's type.
- The removal clause attaches to construction heads only; a refinement head admits none — `T ^ { ... } - { ... }` is a parse error (§5.7, §5.9).
- After a bare type-ref in type-def position, `{` is a parse error; the diagnostic SHOULD suggest `^` (refinement) or `&` (composition).
- Parameters and type arguments inside `<>` alike separate by comma or whitespace — the general separator convention ([TSON-DATA] §7.4): `<T, MIN>` and `<T MIN>`, `meta.map<text, integer>` and `meta.map<text integer>` are all valid. Canonical resolved output writes one space and no comma.
- `_` is not valid in type-expr, type-def body or field-modifier positions (§5.2, §7.6); empty records use `{}`, and a field that may only be omitted or `_` is spelled `a?: void?`.
- A comma may follow the last element of every list in this grammar — declarations, fields, groups, removal sets, parameters, arguments, tuple elements ([TSON-DATA] §2.4); `[text, int32, ]` and `pair<uuid, B, >` are legal. A comma following nothing, or another comma, is a parse error, as in data.


### 12.2 Disambiguation Summary

This section is informative.

```
; document (after the header):
;   @              → annotation; before the tag it binds to the schema
;   !schema {      → schema document; the schema map opens
;   ! other        → data document; a Class 1 read, or a Class 2 read
;                    of a data value under the header's imports
;
; schema map:
;   @              → annotation; binds to the entry key (name) or,
;                    after "=>", to the type definition
;   name =>        → declaration (two-token lookahead)
;   }              → schema map closes
;   anything else  → parse error
;
; type-def position (after =>):
;   abstract / final → definition-mark (§5.2); consumed, then
;                    dispatch continues on what follows
;   <              → type-params (each name optionally followed
;                    by ":" and a type-expr); then dispatch continues:
;     !              → instance; the resolver admits
;                      `!meta.record` alone (§5.10)
;     otherwise      → structural-def / type-expr; the
;                      resolver admits a record body alone
;   !schema {      → nested-map (§4.1); likewise !meta.schema
;   ! name ^       → value refinement (§5.5)
;   ! name         → constructor application, or a value entry (§5.5)
;   name ^         → refined-def (§5.7)
;   name &         → construction-def (composition, §5.8)
;   name -         → construction-def (subtraction, §5.9)
;   name {         → parse error (write ^ or &)
;   name!          → sibling-ref: refused at the resolver as a body
;   {              → brace form; consume "{" and dispatch on
;                    content (reusing [TSON-DATA] §2.8's
;                    consume-one-then-inspect machinery):
;     "}"            → empty record ({}, meta.top's shape)
;     "("            → record-def (leading field group)
;     "@"            → record-def (annotations precede field
;                      names, §6; the map sugar admits no
;                      interior annotations, §5.3, so "@"
;                      commits to a record)
;     name ":"       → record-def (field)
;     name "?"       → record-def (field with an omittable key)
;     name "=>"      → map-type
;     name "<"       → map-type (generic key; consume the
;                      arguments, expect "=>"; the resolver
;                      refuses the key, a key being a name)
;     name (other)   → parse error
;   (              → paren-type (choice)
;   [              → bracket-type (array or tuple, full syntax)
;   name < / name  → type-ref
;
; type-expr position (field types, array elements, etc.):
;   (              → paren-type (choice)
;   [              → bracket-type (full syntax at every position)
;   {              → map-type: "{" name … "=>" required;
;                    "{" name ":" remains a parse error — bare
;                    records must be declared (§5.2); the
;                    diagnostic SHOULD say so and distinguish
;                    the two brace meanings
;   name <         → application ("<" adjacent, [TSON-DATA] §7.5)
;   name !         → sibling-ref ("!" adjacent, whitespace after)
;   name ? / name  → simple ref
;
; record-def entry position (after leading annotations):
;   (              → group-def (field group, §5.11)
;   name ":"       → field-def
;   name "?" ":"   → field-def, key omittable (§5.2)
;
; inside a group-def (§5.11):
;   name ":"       → member
;   name "?" ":"   → member, optional within its option
;   "|"            → next option
;   ")"            → group closes; an adjacent "?" or "+" marks it
;
; after a field's type-expr:
;   ?              → voidable (§5.2)
;   ~ token        → default
;   = token        → fixed
;   = ?            → selector (§5.2); no value follows
;   name "=>"      → parse error ("record body expected; =>
;                    begins a map type only at type positions")
;
; map-type internal rules:
;   exactly one key => value entry ("a map type is a single
;   key => value entry" — the data grammar's multi-entry habit
;   does not carry over, §5.3); a "?" on the key side of "=>"
;   is a parse error, and the value side admits it (§5.3); the
;   key "<" case is the one place the
;   generic-key rule is reachable — after the arguments close,
;   ":" here is a parse error whose diagnostic SHOULD read
;   "expected =>; if you meant a record field, a generic key
;   cannot name one"
;
; bracket-form internal disambiguation:
;   [type sep type  → tuple (whitespace or comma)
;   [type ; spec    → array with size constraint
;   [type ]         → unconstrained array
;   [type ? ...     → element "?"
;   [[ ...          → nested bracket-type (full syntax)
;   [{ ...          → nested map-type
;
; after a construction body "}":
;   -              → removal clause (§5.9)
;   otherwise      → declaration boundary rules below
;
; declaration boundary (resync): after a bare type-ref in
; type-def position, one/two-token lookahead decides:
;   ^              → refinement body of the current type-def
;   <              → type arguments of the current type-ref
;   &              → composition continues the current type-def
;   -              → removal clause of the current type-def
;   ","            → current declaration complete
;   name "=>"      → current declaration complete; a new one begins
;   "}"            → current declaration complete; map closes
;   "{"            → parse error (write ^ or &)
;   name (other)   → parse error
```

Each case in the type-def block is decided by at most two tokens of lookahead at the start of the production — the brace form by one consumed token plus one token of lookahead, the same budget in the same sense as the data grammar's §2.8 dispatch; inside a bracket form, the choice between tuple, sized array, and unconstrained array is made by one-token lookahead after the complete preceding element type. A `field-type` can itself be nested (`(email | [phone])?`) and parses without backtracking via the disambiguation above; the outer `?` there makes the field voidable (§5.2). The schema body requires at most two tokens of lookahead to detect a declaration boundary. The parser never backtracks at any level.


### 12.3 Adjacency Rules

The following rows extend the adjacency table of [TSON-DATA] §7.5 for the operators of the type-definition grammar; as there, the rules are enforced by the parser via source-position comparison.

| Operator | Type | Context | Rule |
|---|---|---|---|
| `!` | prefix | type-def body (constructor application, value refinement) | MUST be adjacent to the following unquoted-token (constructor or instance name) |
| `?` | suffix | field type, tuple position, array/map element (element-type, §5.3), field group | MUST be adjacent to the preceding token (type name or closing bracket/paren) |
| `&` | binary | composition | whitespace on either side optional |
| `^` | binary | refinement (§5.5, §5.7) | whitespace on either side optional |
| `-` | prefix | removal clause (§5.9) | at least one whitespace character MUST separate the preceding token from `-`; whitespace optional before the following `{` |
| `~` | modifier | default value | whitespace optional |
| `=` | modifier | fixed value | whitespace optional |
| `=?` | modifier | selector (§5.2) | whitespace optional before `=` and between `=` and `?` |
| `?` | suffix | field name (omittable key, §5.2); group-member name (optional within its option, §5.11) | MUST be adjacent to the preceding field name |
| `+` | suffix | field group (at least one of, §5.11) | MUST be adjacent to the preceding `)` |
| `:` | separator | parameter type (§5.10) | whitespace optional on both sides |
| `\|` | separator | choice variant; field-group option | whitespace optional |
| `;` | separator | array size spec; map size spec (§5.3) | whitespace optional |
| `..` | binary | size-spec range (§5.3) | whitespace on either side optional |
| `=>` | separator | schema declaration; data map entry; map type sugar (§5.3) | whitespace optional (compound token from lexer) |

The whitespace requirement before removal `-` is a lexer fact restated as a rule: hyphen-minus continues an unquoted token ([TSON-DATA] §7.2.4 — hyphenated names are legal), so in `account- { password }` the hyphen is absorbed into `account-` and no removal clause exists — the same footgun class as `[1-2]` lexing as one token. When a token ending in `-` appears at a type-def position followed by `{`, the diagnostic SHOULD note the absorbed hyphen and suggest whitespace before `-`.


## 13. References

### 13.1 Normative References

| Reference | Title | URL |
|-----------|-------|-----|
| RFC 2119 | Key words for use in RFCs to Indicate Requirement Levels | https://www.rfc-editor.org/rfc/rfc2119 |
| RFC 5234 | Augmented BNF for Syntax Specifications (ABNF) | https://www.rfc-editor.org/rfc/rfc5234 |
| RFC 3339 | Date and Time on the Internet: Timestamps (Appendix A is the `duration` and `period` grammar, §5.5) | https://www.rfc-editor.org/rfc/rfc3339 |
| RFC 3986 | Uniform Resource Identifier (URI): Generic Syntax | https://www.rfc-editor.org/rfc/rfc3986 |
| RFC 3987 | Internationalized Resource Identifiers (IRIs) (`net.iri_type`, `net.host_type`'s `ihost`, §5.5) | https://www.rfc-editor.org/rfc/rfc3987 |
| RFC 3492 | Punycode: A Bootstring encoding of Unicode for IDNA (the A-label ↔ U-label mapping, `net.hostname_type`, §5.5) | https://www.rfc-editor.org/rfc/rfc3492 |
| RFC 4291 | IP Version 6 Addressing Architecture | https://www.rfc-editor.org/rfc/rfc4291 |
| RFC 4632 | Classless Inter-domain Routing (CIDR) | https://www.rfc-editor.org/rfc/rfc4632 |
| RFC 4648 | The Base16, Base32, and Base64 Data Encodings (the text spellings of `bytes`, §5.5) | https://www.rfc-editor.org/rfc/rfc4648 |
| RFC 5322 | Internet Message Format (email address syntax) | https://www.rfc-editor.org/rfc/rfc5322 |
| RFC 5890 | IDNA: Definitions and Document Framework (`net.hostname_type`, §5.5) | https://www.rfc-editor.org/rfc/rfc5890 |
| RFC 5891 | IDNA: Protocol | https://www.rfc-editor.org/rfc/rfc5891 |
| RFC 5892 | The Unicode Code Points and IDNA (U-label validity) | https://www.rfc-editor.org/rfc/rfc5892 |
| RFC 5893 | Right-to-Left Scripts for IDNA | https://www.rfc-editor.org/rfc/rfc5893 |
| RFC 5952 | A Recommendation for IPv6 Address Text Representation (`host`'s canonical text) | https://www.rfc-editor.org/rfc/rfc5952 |
| RFC 6838 | Media Type Specifications and Registration Procedures (`net.media_type_type`, §5.5) | https://www.rfc-editor.org/rfc/rfc6838 |
| RFC 6839 | Additional Media Type Structured Syntax Suffixes (`net.media_type_type.suffixes`) | https://www.rfc-editor.org/rfc/rfc6839 |
| RFC 9110 | HTTP Semantics (§8.3.1, media-type parameters) | https://www.rfc-editor.org/rfc/rfc9110 |
| RFC 9485 | I-Regexp: An Interoperable Regular Expression Format | https://www.rfc-editor.org/rfc/rfc9485 |
| RFC 9542 | IANA Considerations and IETF Protocol and Documentation Usage for IEEE 802 Parameters (EUI-48) | https://www.rfc-editor.org/rfc/rfc9542 |
| RFC 9562 | Universally Unique IDentifiers (UUIDs) | https://www.rfc-editor.org/rfc/rfc9562 |
| RFC 9651 | Structured Field Values for HTTP (the `TSON-Schema` field, [TSON-JSON] §3.5) | https://www.rfc-editor.org/rfc/rfc9651 |
| UAX #15 | Unicode Normalization Forms (the `normalization` facet, §5.5) | https://www.unicode.org/reports/tr15/ |
| UAX #31 | Unicode Identifier and Pattern Syntax (`unicode.identifier_type`'s profile and `toNFKC_Casefold`, §5.5, §7.4) | https://www.unicode.org/reports/tr31/ |
| UAX #24 | Unicode Script Property (the script names a policy document writes, `policy.tn`) | https://www.unicode.org/reports/tr24/ |
| UTS #39 | Unicode Security Mechanisms (name hygiene, §11.4; [TSON-DATA] §7.7, §8.2) | https://www.unicode.org/reports/tr39/ |
| CommonMark 0.31.2 | CommonMark Spec (the text of `@doc`, §6) | https://spec.commonmark.org/0.31.2/ |
| UCD | Unicode Character Database (the data files behind [TSON-DATA] §8.2, and the version a refusal reports) | https://www.unicode.org/ucd/ |
| IEEE 754-2019 | Standard for Floating-Point Arithmetic | https://ieeexplore.ieee.org/document/8766229 |

### 13.2 Series References

| Reference | Title | URL |
|-----------|-------|-----|
| TSON-DATA | TSON Part 1: Text Data Format | https://tson.io/2026/40/tson-part1-data |
| TSON-JSON | TSON Part 3: JSON Encoding | https://tson.io/2026/40/tson-part3-json |
| TSON-GUIDE | TSON Developer Guide (non-normative) | https://tson.io/2026/39/tson-guide |
| meta.tn | TSON Kernel (companion artifact, pre-loaded) | https://tson.io/2026/40/m/meta.tn?sha256=cc877a3b650622e33c20df744f044d048fbb41723079bd50e9ac75a749325ae9 |
| numeric.tn | TSON Numeric Library (companion artifact) | https://tson.io/2026/40/m/numeric.tn?sha256=cdb9876c5b6102d16940f2e137ea3b0766d5eb7824066eef599ff8d0231a91b0 |
| temporal.tn | TSON Temporal Library (companion artifact) | https://tson.io/2026/40/m/temporal.tn?sha256=71a40b2aa61bf7ee112ff1b316a6e4b98dd19b9c7da08b7cd014afd556de3695 |
| net.tn | TSON Network and Identifier Library (companion artifact) | https://tson.io/2026/40/m/net.tn?sha256=3db97e90f52c95d6ad212e7ac45dfd286537674caf91b9ecc6da516800c53a52 |
| core.tn | TSON Core Bundle (companion artifact) | https://tson.io/2026/40/m/core.tn?sha256=4b427e8cfc99148deeed4cc3b48e4fa77ba65f5e174a9b02cc2885045a1f9b89 |
| policy.tn | TSON Processor Policy (companion artifact) | https://tson.io/2026/40/m/policy.tn?sha256=e45bfa037d24b79eb202331739f8e7e1cff00e67c71cf2947c396f1cbd9ad57b |

### 13.3 Informative References

| Reference | Title | URL |
|-----------|-------|-----|
| RFC 8820 | URI Design and Ownership | https://www.rfc-editor.org/rfc/rfc8820 |
| ISO/IEC 11404:2007 | General Purpose Datatypes | https://www.iso.org/standard/39479.html |
| ISO 8601-1:2019 | Date and time — Representations for information interchange (the informative source behind RFC 3339 Appendix A) | https://www.iso.org/standard/70907.html |
| JSON Schema 2020-12 | JSON Schema: A Media Type for Describing JSON Documents | https://json-schema.org/specification |
| W3C XSD Part 2 | XML Schema Part 2: Datatypes Second Edition | https://www.w3.org/TR/xmlschema-2/ |
| Resolver Output Fixtures | meta-resolved.tn, numeric-resolved.tn, temporal-resolved.tn, net-resolved.tn, core-resolved.tn (non-normative) | &lt;published alongside this document&gt; |

