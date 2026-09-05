---
title: "TSON Part 2: Type System and Schema"
draft: "2026"
status: "Working Draft"
part: 2
description: >
  The centre of the TSON series: the schema grammar, the type system and its operations,
  the schema chain and its resolution model, schema compilation and resolver output, the
  text encoding rules, and the operations of the schema, meta, and import directives.
---

# TSON Part 2: Type System and Schema

## 2026 Revision 35

**Status:** Working revision. The 2026 revision series is subject to change without compatibility guarantees. When finalised, this specification will be published as **TSON version 1** and frozen; until then, revisions are released under the 2026 series. This revision removes the `~` marker and `type_definition.constructor` — applicability is IS-A `top` (§3.3.1, §4.2) — and removes use-site flattening and `@alias`, a reference being a hop (§8.3), with identity nominal for a declaration and canonical for an application (§8.2). It replaces `extern` and `unknown_type` with the `scoped` sum constructor (§7.8), `binary` with `bytes_type` (§5.5), and one temporal `duration` with `duration` and `period` (§5.5); adds `members` to the exact numeric tiers (§7.4) and the checked annotations `@discriminator` and `@rest` (§6); states the value-space clause every equality, ordering and identity rule rests on (§5.5); removes base type resolution from schema scope entirely, with `null` gone from the notation (§7.1, §7.3); and carries open entries in resolver output as `type_definition` values (§8.1).

**Series:** TSON Specification, Part 2 of 2

**Copyright:** © 2026 Litterat Pty Ltd. This document is licensed under the Creative Commons Attribution-ShareAlike 4.0 International License (CC BY-SA 4.0): https://creativecommons.org/licenses/by-sa/4.0/


## 1. Introduction

TSON is a schema system, and this document is its centre: a type system of immutable, hash-pinned schemas whose definitions are themselves data, resolving down a verified chain — document → schema → meta-schema → kernel — so that one hash authenticates a document together with its entire contract. The text format of [TSON-DATA] is this system's notation and its reference encoding: schemas are written in it, resolver output is serialized in it, and §7 states its encoding rules — the first encoding to carry the type system's values, with others defined against the same encoding-independent model.

This document defines the TSON **type system and schema layer**: the schema grammar and its type-definition forms, the type system and its operations, the schema chain and its resolution model, the resolver output contract, the text encoding rules, and the schema-layer directive operations.

[TSON-DATA] defines the lexer, the data grammar, base type resolution, and the built-in type vocabulary. This document introduces no lexical changes: a schema document is parsed by a second body grammar over the same frozen lexer, selected by the document header ([TSON-DATA] §2.2), and every operator it uses is a token that lexer already emits — the reserved special tokens of [TSON-DATA] §7.2.5 receive their meaning here. The schema grammar imports [TSON-DATA]'s value grammar at exactly two points — the constructor-application payload, a `core-value`, and the atom-refinement body, its braced subset (§5.5, §5.6, §12.1), the second desugaring into the first; every other value position is deliberately narrower (a record-refinement body is a braced `record-def`, §5.7; a field-modifier value is a bare token or the absent sentinel, §5.2) — and the coupling is one-directional: nothing in the data grammar depends on this document.


### 1.1 The TSON Specification Series

- **Part 1: Text Data Format** [TSON-DATA] — the notation and reference encoding: the lexer, the data grammar, base type resolution, and the built-in type vocabulary.
- **Part 2: Type System and Schema** (this document) — the centre of the series: the schema grammar, the type system, the schema chain, and the operations of the `schema`, `meta`, and `import` directives.

[TSON-DATA] §1.3 gives the series architecture: the schema chain — the type system — on one axis, and the notation and encodings that carry it on the other.


### 1.2 Design Principles

In addition to the principles of [TSON-DATA] §1.2:

1. **Schema-value separation** — A document never references its own definitions via type annotations. Every type reference (`!name`) resolves against an external schema identified by the document's `!!schema` directive (§3).

2. **Purpose-built representation** — A schema document shares the data format's lexicon — the frozen lexer, its tokens, annotations, and directives — but has its own body grammar, selected by the document header (§12.1). The semantics remain shared: a schema document resolves to an ordinary TSON value (a schema value, §2.1), and resolver output is an ordinary data document (§8).

3. **Permanent stability** — The schema grammar, the meta-schema, the core type library, and the resolver output contract are frozen once published, on the same terms as [TSON-DATA] §1.2 principle 6. New types are added through new type libraries, not through changes to this document.


### 1.3 Conformance

[TSON-DATA] §1.5 defines the series' two conformance classes. **Class 1** (data-format processor) is defined there in full; its sole schema-layer obligation is to reject schema documents with a categorized diagnostic. This document defines **Class 2**.

A **Class 2 processor** (schema-aware processor) conforms to [TSON-DATA] and additionally implements the schema grammar (§4–§5, §12), the directive operations (§2.2), name resolution and the schema library (§3, §10), schema compilation and resolver output (§8), atom token parsing (§7.4), and validation. Such a processor:

- MUST pre-load the meta-kernel and meta-schema (§3.4, §10.1) and SHOULD pre-load the core type library;
- MUST resolve type annotations through the active schema when one is in scope, and MUST NOT apply the [TSON-DATA] §5 built-in vocabulary or [TSON-DATA] §4 base type resolution in schema scope: under a schema every value is typed by its position or by its tag (§7.1, §7.3);
- MUST produce, for every valid schema document, a resolved schema value conforming to the `type_definition` contract (§8) — `subtypes` computed, `supertypes` computed from source (§8.1). Serializing the resolved schema value as a data document is OPTIONAL; output, when produced, MUST conform to §8's serialization contract;
- MAY implement ingest of resolver output (§8, §10.1); an implementation that does MUST apply §8.1's derived-field treatment and §10.1's source rules;
- MUST resolve annotations (`@name`, `@name:value`) one hop against the governing target's namespace (§3.3.3) and validate each annotation's value against the named type's contract (§6); an annotation whose name does not resolve is a resolver error (§6), and annotations are preserved in resolver output (§8.1);
- MUST enforce the identity-agreement rule (§10.1) and verify hash-pinned references per [TSON-DATA] §2.2.1 (§10.2);
- MUST match every declared name, parameter name, referenced name, and constructor head against the identifier grammar ([TSON-DATA] §7.7) and reject a failure as a parse error (§12.1);
- MUST implement the name-hygiene checks of [TSON-DATA] §8.2 over the schema-layer scopes (§11.4), enforce them by default, and report a refusal as [TSON-DATA] §8.1's fifth outcome — distinguishable from a verdict, in the same report;
- MUST enforce the schema-side resource limits of §11.5 on [TSON-DATA] §9.1's terms;
- MUST report errors in the categories and phrasings of [TSON-DATA] §8.1.

**Resolved-output consumers.** A consumer that ingests only resolved schema values (§8, §10.1) — never schema source — is fully conforming with no support for templates or parameters (§5.10). An open entry appears in output as a `type_definition` whose `parameters` list is non-empty and whose `body` is the held application in wire form (§8.1); a consumer of closed entries reads `parameters` empty and never meets a held body, since every entry a data document's type can reach is closed by the closed-entry rule (§5.10), so the wire never presents template machinery to a data-path consumer.


### 1.4 Terminology

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in RFC 2119.

- A **schema document** is the document kind whose header carries `!!meta` ([TSON-DATA] §2.2) and whose body is a schema map (§2.1, §12.1) — the source artifact that is authored, published, hash-pinned, and resolved.
- A **schema** is what a schema document defines: a named, immutable collection of type declarations, identified by URL and referenced by the `!!schema`, `!!meta`, and `!!import` directives.
- A **schema value** is the resolved form of a schema: a value of the kernel's `schema` type, `map<type_name, type_definition>` (§9), produced by resolution and optionally serialized as a data document (§8). Schema values are derived artifacts, never schema sources (§10.1).
- A **meta-schema** is a schema in its governing role — the target of a `!!meta` directive. `meta.tn` is the canonical meta-schema (§9); the meta-kernel is the root of the governing chain (§3.4).
- The **schema grammar** is the body grammar of schema documents (§12.1); the **type-definition grammar** is its declaration right-hand side — the `type-def` production that each `name => type-def` declaration activates.


### 1.5 Companion Artifacts

This document is published with six companion artifacts. The normative artifacts are pinned by content hash at publication. Per §3.4, implementations pre-load the kernel and meta-schema as in-memory structures; the artifact documents are descriptions of those structures, and the in-memory model is authoritative.

| Artifact | Status | Content |
|----------|--------|---------|
| `meta-kernel.tn` | Normative | The self-referencing bootstrap layer (§9) |
| `meta.tn` | Normative | The canonical meta-schema (§9) |
| `core.tn` | Normative | The core type library (§9, [TSON-DATA] §5) |
| `meta-kernel-resolved.tn` | Non-normative | Resolver-output fixture for the meta-kernel (§8) |
| `meta-resolved.tn` | Non-normative | Resolver-output fixture for the meta-schema (§8) |
| `core-resolved.tn` | Non-normative | Resolver-output fixture for the core type library (§8) |


### 1.6 A Complete Example

A schema document declaring three types, governed by the canonical meta-schema and importing the core type library:

```
!!id:"https://example.com/task.tn"
!!meta:"https://tson.io/2026/35/m/meta.tn"
!!import:"https://tson.io/2026/35/m/core.tn"
@doc:"Task-tracking example schema."
{
  priority => !integer ^ { min: 1  max: 5 }
  status   => !enum [OPEN ACTIVE DONE]
  flagged  => <T, N> { entry: T  priority: priority ~ N }
  task => {
    id:       uuid
    title:    non_empty_text
    priority: priority ~ 3
    status:   status ~ OPEN
    due:      date?
    tags:     [text]?
    history:  [flagged<status, 2>]?
  }
}
```

`priority` refines core's `integer` instance (§5.5); `status` applies the `enum` constructor, reached through the structure namespace supplied by the `!!meta` target (§3.3.1); `task` is a fresh record whose field types resolve through the type-name namespace (§3.3.2). `flagged` is a **template** with a type parameter and a value parameter (§5.10): a fresh record whose `priority` field is *defaulted by parameter* (`~ N`, §5.7), built entirely from names in the schema's own namespace. `flagged<status, 2>` is its fully-bound application, which the resolver materialises once per distinct application (§8.2); `history` wraps it in the plain array sugar. A data document binds the schema with `!!schema` (§7.1) and instantiates its types:

```
!!schema:"https://example.com/task.tn"
!task {
  id:       550e8400-e29b-41d4-a716-446655440000
  title:    "Ship revision 35"
  priority: 3
  status:   OPEN
  due:      2026-08-01
  tags:     [spec editorial]
  history:  [{ entry: OPEN }  { entry: ACTIVE  priority: 4 }]
}
```

Resolution derives a schema value from the schema document, optionally serialized as resolver output — a data document governed by the meta-schema in which every declaration has desugared to the canonical `!C { bindings }` form (§5.6, §8). The companion fixtures (§1.5) and [TSON-GUIDE] give a full worked resolver-output example.


## 2. Schema Documents

A schema document is the source artifact of the schema layer (§1.4). This section defines the document's structure and the operations of its header directives. The `!!schema` directive, which binds a schema to a data document, is defined in §7.1.


### 2.1 Schema Document Structure

A schema document is a fixed-shape header followed by a braced map of declarations — the **schema map**. The header carries the schema's identity (`!!id`, first line; optional in the grammar, required for publication and hash-pinning, §2.2.1), its governing meta-schema (`!!meta`, mandatory, exactly once), and its dependencies (`!!import`, repeatable, declaration order significant); annotations that bind to the schema sit after the header, immediately before the opening brace. Header order and cardinality are grammar productions, not conventions ([TSON-DATA] §2.2, §3.3; §12.1):

```
!!id:"https://example.com/people.tn"
!!meta:"https://tson.io/2026/35/m/meta.tn"
!!import:"https://tson.io/2026/35/m/core.tn"
@doc:"Minimal example schema."
{
  person => { name: text  age: integer }
  employee => person & { department: text }
  status => !enum [ACTIVE INACTIVE SUSPENDED]
}
```

Entries are separated like data-map entries — whitespace or a comma, and a comma may follow the last ([TSON-DATA] §2.4) — and the map MUST contain at least one entry (§12.1). Each declaration binds a type name to a type definition with the `=>` operator — a compound token the frozen lexer already emits; the schema grammar introduces no reserved words — its operators are the reserved special tokens of the shared lexer ([TSON-DATA] §7.2.5).

**The body is a map, in syntax and in semantics.** The document resolves to a value of the kernel's `schema` type, `map<type_name, type_definition>` (§9, §8), and the braces carry the document's annotation anchor: annotations precede the value they bind to ([TSON-DATA] §3.1), and the schema map is the value the document-level annotations bind to. The `!schema` type-ref never appears in schema-document source — the document kind and `!!meta` already say what the body is; `!schema` marks *data* representations of resolved schema structure, most notably resolver output (§8). The `!!` prefix is always a directive; the `!` prefix is always a type annotation.

**Annotation binding.** Annotations immediately before the opening brace bind to the schema — the header directives carry no annotations ([TSON-DATA] §7.4 gives them no annotation slot). Inside the braces the data-map convention applies unchanged ([TSON-DATA] §3.1): an annotation immediately preceding a declaration's name binds to the key — the name itself (§6) — and annotations after `=>` and before the type-def bind to the type definition. Resolver output preserves the placement (§8).


### 2.2 Directives

[TSON-DATA] §3.3 defines the directive set: four names — `id`, `schema`, `meta`, `import` — each legal only at fixed positions, with order and cardinality enforced by the grammar. The set is closed: any other directive name, or a legal name outside its position, is a parse error. There is no directive registry and no unknown-directive category.

This document defines the directive *operations* — what each directive means to the resolver. Every directive value is a URL string; URLs are logical identifiers resolved through the schema library (§10), never fetch instructions.

| Operation | Directive | Placement ([TSON-DATA] §3.3) | Defined in |
|-----------|-----------|------------------------------|------------|
| Identity declaration | `!!id` | first header line, either kind; optional in the grammar — required for published schemas | §2.2.1 |
| Schema binding | `!!schema` | data-document header; record field values; map entry values; array elements | §7.1 |
| Meta binding | `!!meta` | schema-document header, immediately after `!!id`; exactly once | §2.2.2 |
| Type-library import | `!!import` | schema-document header, after `!!meta`; repeatable | §2.2.3 |


#### 2.2.1 The `!!id` Directive

`!!id` declares the authoritative identity of a document — the name other documents use to reference it, compared as a canonical identity ([TSON-DATA] §2.2.1). It connects the file's content to its logical name in the schema library (§10) and anchors content addressing: the hash input for a document's content hash is every byte after the id line ([TSON-DATA] §2.2.1; §10.2).

`!!id` is optional in the grammar and, when present, must be the first line. For schema documents it is required by policy, not grammar: **publishing a schema — registering it under its own name for reference by other documents, or pinning it by content hash — REQUIRES `!!id`.** An id-less schema is a development artifact; it may be registered under an application-supplied URL (§10.1) but has no published identity and cannot be hash-pinned.


#### 2.2.2 The `!!meta` Directive

`!!meta` names a schema document's governing **meta-schema**: the contract the schema's declarations are validated against. It appears exactly once, as the first directive after the optional `!!id`; its position carries the document-kind dispatch bit ([TSON-DATA] §2.2).

The `!!meta` target supplies two things:

- **The validation contract.** Each declaration resolves to a `type_definition` value (§8); the meta-schema's vocabulary defines what those values may be. A schema document is valid only if its resolved form conforms to its meta-schema.
- **The structure namespace.** The `!!meta` target's namespace supplies the constructors the schema builds with and the structural vocabulary the resolver uses to materialise type-definition output. Resolution is one hop; §3.3.1 defines the rule.

The ladder of governing relations terminates at the meta-kernel, whose `!!meta` references its own URL. The self-reference is never resolved: the kernel and the meta-schema are pre-loaded (§3.4, §10.1), and the ladder is closed by pre-loading, not resolution.

User schemas normally chain to `meta.tn`. Chaining to `meta-kernel.tn` directly is a meta-programming case — an alternative type vocabulary replacing meta, or an extension of the meta layer itself (§9). The meta layer is the format's sanctioned extension point: new type vocabularies arrive as alternative or extended meta-schemas chaining to the kernel, never as grammar changes.

**Constructor declarations are a meta-schema privilege.** A constructor is an entry that IS-A `top` (§4.1, §4.2) — one that composes, directly or transitively, with a base kind, or refines an entry that does. Such an entry may be declared only in a schema document whose own `!!meta` names the meta-kernel; in any other schema the declaration is a resolver error. User schemas and type libraries apply and refine constructors' *instances*; they do not declare constructors — declaring one is what makes a document a meta-schema, and the ladder keeps that role explicit rather than emergent. The rule is asked of every declared entry by its resolved supertype chain, so an ordinary type library cannot reach constructor level by composing its way there, and no marker is needed to say which declarations it governs.


#### 2.2.3 The `!!import` Directive

`!!import` imports type entries from an external schema into the importing schema. The directive value is a URL string identifying a published schema. The directive loads the referenced schema and makes its locally-declared entries available as if they were declared in the importing schema: imported entries are available to all local declarations (including for recursive references), and local declarations may refine or compose with imported types.

**Imports are transitive.** An `!!import` contributes the imported schema's entire namespace — the entries it declares and the entries it imported — exactly as `!!meta` contributes its target's namespace (§3.3.1). A schema's namespace is therefore flat: one name denotes one type throughout a resolution, and every name in the import closure is reachable by the data documents the schema governs, so the types §7.2's subsumption admits at a position are always nameable where the position is used.

Multiple `!!import` directives are permitted and are loaded in declaration order. Collisions are decided by **entry identity**, not by name occurrence. One schema reached through more than one route contributes its entries once — re-arrival unifies, so the diamond (two imports that each import core) is ordinary rather than an error, and naming one schema twice, or under two spellings of one canonical identity ([TSON-DATA] §2.2.1), is redundant rather than a conflict. A `?sha256=` pin is verification metadata ([TSON-DATA] §2.2.1, §10.2), never part of identity: routes that disagree about pinning still name one schema, and a pin disagreeing with that schema's content is a verification failure, not a second identity. It is a resolver error for two *different* schemas to declare the same name anywhere in the import closure, or for a local declaration to reuse a name the closure already binds — no hiding and no redefinition: the schema fails to load, and the diagnostic names both declaring schemas. Imports populate only the type-name namespace: an import name that happens to match a name in the structure namespace (§3.3.1) is not a collision — the two are consulted at different grammar positions.

Two costs of the flat namespace are deliberate and worth knowing in advance. Every name in the closure is reserved — importing two peers and the core library spends `id`, `name`, `value`, `text` and the rest, with no aliasing to escape with; that is the price of a bare name meaning one thing everywhere. And some conflicts are unfixable by the importer: if two imported schemas transitively reach different schemas that both declare `id`, the importing schema is rejected over names it never wrote and can only be repaired upstream. One consequence follows without a separate rule: schema identities carry the specification revision, so an import closure reaching two revisions of one library is rejected at namespace construction — the collision rule firing on genuinely different documents — rather than allowing two identically-spelled types to coexist and surface later as an inexplicable field conflict.

**Merged entries keep their home resolution.** An imported entry's internal references — reference targets, field types, `source` applications — are names in its *defining* schema's namespace, fixed when that schema was resolved; the merge does not re-resolve them against the importer. Operationally, the library holds one resolved namespace per schema, and a consumer following an imported entry's references follows them in the defining schema's resolution (§3.4.1).

**Import references MUST be acyclic.** A schema's reference closure — every document reachable through `!!meta` and `!!import` — is resolved dependencies-first (§3.4.1), and a cycle admits no such order: an `!!import` reaching, directly or transitively, a schema whose resolution is in progress is a resolver error — *import cycle* — reported with the cycle path. The prohibition costs nothing in practice: a hash-pinned cycle is unauthorable, since each document's pin would have to be computed over bytes that already contain the other's, so a cycle can arise only among unpinned development references. Mutual coupling has sanctioned routes: shared types belong in a third schema both partners import, and data-level coupling to a foreign schema is a `scoped` instance such as `extern` (§7.8), which admits a foreign schema's values without loading it into resolution.

The imported schema MUST itself be a valid schema document, resolved independently.


## 3. The Schema Chain

A TSON document never resolves type annotations against its own definitions. This is the fundamental rule of schema-value separation: every type annotation (`!name`) in a document resolves against an external schema — the schema identified by the document's `!!schema` directive. Definitions in a schema document exist only for consumers of the published schema. This rule has no exceptions: it applies to data documents, user schemas, extended meta-schemas, and the meta layer itself.

Inside a schema document, type names used in type-ref positions within the type-definition grammar resolve against the **type-name namespace** (§3.3.2); structural forms resolve against the **structure namespace** supplied by the `!!meta` target (§3.3.1). In data mode, `!name` type annotations resolve only against the type-name namespace of the schema identified by `!!schema`.


### 3.1 The Schema Ladder

Every TSON document that uses type annotations sits on the **schema ladder** — the chain of governing relations. Each rung binds the same relation — *validate me against X* — with the directive of its document kind:

```
data document
  └─ !!schema → user schema
                  └─ !!meta → meta-schema
                                └─ !!meta → meta-kernel
                                              └─ !!meta → itself (pre-loaded)
```

A data document's `!!schema` names the user schema its type annotations resolve against. A user schema's `!!meta` names the meta-schema, and its `!!import` directives bring in type-library entries. The meta-schema (`meta.tn`) chains to the meta-kernel together with an `!!import` of the kernel — the import supplies the kernel types meta's own declarations use *and*, because resolution is one hop, places the kernel's structural vocabulary in meta's namespace, where every meta-governed schema finds it (§3.3.1, §9). The meta-kernel's `!!meta` points at its own URL; both meta and the kernel resolve to pre-loaded Schema objects (§3.4).


### 3.2 Schema-Value Separation

A published schema is an immutable artifact: once published at a URL with a content hash, its type definitions do not change (§3.5). Because type references always resolve against an external schema, `!text` in a given document means exactly what the referenced schema defines it to mean — two documents referencing the same schema have identical type vocabularies. A document with no `!!schema` directive has no type vocabulary: base type resolution and the built-in annotations of [TSON-DATA] apply there, and only there (§7.1).


### 3.3 Schema Layering

The directive architecture separates three layers: the **meta-schema** defines the structural vocabulary the type-definition grammar produces (`type_definition`, `record`, `record_field`, `array`, `enum`, …); **type libraries** define specific types (`integer`, `text`, `uuid`, …) using that vocabulary — type libraries are ordinary schemas; **application schemas** import type libraries and define domain types on top of them.

Name resolution is built from one primitive. The **namespace of a schema** is its local declarations plus the entries of its imports (§2.2.3) — nothing more. Every document has a **governing target**: a schema document's is its `!!meta` target; a data document's is its `!!schema` target. All resolution against the governing target is **one hop**: the target's namespace is consulted directly, and no further rung of the ladder is ever walked. Two namespaces are active when a schema document resolves, consulted at different grammar positions.


#### 3.3.1 The Structure Namespace

The **structure namespace** of a schema document is the namespace of its `!!meta` target — the target's local declarations plus its imports. One hop: because resolution is one hop, the target's namespace here means the target's full closure (§2.2.3 — imports are transitive), which is the complete vocabulary it offers everything it governs. The structure namespace is consulted at exactly two **constructor roles**, both marked or grammar-supplied:

- **Constructor-application targets** — the name after `!` when no `^` follows (`!enum [...]`, `!integer_type {}`), resolved through the structure namespace **only**: a miss is an unresolved-constructor error, and **the found entry MUST be IS-A `top`** (§4.1) — a hit whose entry is not is a resolver error (the diagnostic SHOULD point at the refinement form, `!name ^ { ... }`, where the entry is an atom instance). IS-A stops at construction, so the predicate separates a type from a *part* of one: the base kinds, every constructor, and the kernel's `reference` are types by that measure and may be applied — `!reference { target: T }` is the explicit spelling of the alias `T` (§8.3), open or closed, and a base kind's own reader refuses a direct application by naming what would satisfy it — while `record_field`, `type_ref`, `type_argument`, `tuple_element`, `field_group`, `integer_size`, `atom_specification` and `type_definition` are record-bodied like a constructor and are not, and are refused at the spec level rather than by whatever a host model does with them. Local and imported declarations never participate, so no declaration can capture a `!` target. The kernel's self-hosted case needs no ordering rule: when a schema is its own meta, the two namespaces are the same entry set. The direction of service is the invariant: a schema's own constructors serve the layers it governs; its `!` targets come from the meta that governs it.
- **The implicit desugar targets of the sugar forms** — `[T]` and the sized forms to `array`, `[T, U]` to `tuple`, `(A | B)` to `choice`, `{K => V}` to `map` (§5.3, §5.6) — which are grammar-supplied and never author-written.

Bare names and generic-application heads never consult the structure namespace: `name<args>` resolves its head through the type-name namespace only — parameters, then locals, then imports — and an unresolved head is an unresolved-type error; a head resolving to a *parameter* is a resolver error (§5.10's no-head-abstraction boundary), diagnosed rather than applied. Implementations SHOULD emit a migration diagnostic when a generic head fails type-name resolution but matches a parameterless constructor in the structure namespace, suggesting the sugar spelling or the `!C { … }` form (`map<text, text>` → "did you mean `{text => text}`?"). The structure namespace is likewise never consulted for bare type-refs — a schema governed by meta cannot write `field: enum` or reach the kernel's `integer` as a field type (§3.3.2).

**Atom refinement is not a constructor role.** With `^`, `!I ^ { values }` is **atom refinement** (§5.5): `I` resolves against the type-name namespace only and MUST be an atom-kinded entry that is not itself applicable — an instance of an atom family, not the family's constructor; the constructor it desugars to is reached through the instance's own `source` field — never by name, so refinement works even where the constructor is not name-visible. A name found in no permitted namespace is an unresolved-type error.

**Import what you expose.** Because resolution is one hop, a meta-schema's namespace is the *complete* vocabulary it offers everything it governs. A meta-schema MUST therefore import every schema whose entries it intends to expose — this is why `meta.tn` imports the meta-kernel: the import is the delivery mechanism for the kernel's structural vocabulary (`enum`, the sugar-form targets, `type_definition`, …) to every meta-governed schema and its resolver output.


#### 3.3.2 The Type-Name Namespace

The **type-name namespace** provides the names a schema author can use as type-refs in their own definitions — field types, type arguments, choice variants, composition targets, and refinement sources. Lookup walks, in order:

1. Parameters of the enclosing definition (§5.10).
2. Local declarations of the current schema.
3. Entries brought in by `!!import` directives, in declaration order.

The type-name namespace is NOT extended by the structure namespace: names available through `!!meta` are available at constructor roles only, never as type-refs. This is why application schemas import core — the types that fill record fields (`text`, `integer`, `uuid`) must come from the schema's own namespace. Name collisions across the import closure and with local entries are decided by entry identity (§2.2.3): one schema reached twice contributes once; two different schemas binding one name reject the load.


#### 3.3.3 Annotation Resolution

An annotation `@name` or `@name:value` resolves against the **governing target's namespace** — the `!!meta` target for a schema document, the `!!schema` target for a data document — one hop, locals plus imports. Neither the local declarations of the document being authored nor any further rung of the ladder participates.

The one-hop rule determines where annotation types must live:

- **Annotations for schema documents** live in the meta layer. `meta.tn` declares `deprecated`, `since`, `todo`, `lang`, `title`, `examples`, `read_only`, `write_only`, `ordered`, `bounded`, `exact`, `numeric`, `disjoint`, `discriminator`, and `rest` locally, and carries the kernel's `doc`, `documentation`, and `annotation` through its kernel import.
- **Annotations for data documents** live in the governing user schema's namespace. Core declares its own `doc`, `documentation`, and `annotation` — fresh siblings of the kernel's entries (§3.3.5) — so that data documents governed by core-importing schemas can write `@doc`. An annotation type declared locally in a user schema is usable by that schema's *data documents* — but not within the declaring schema document itself, whose governing target is meta. Custom annotations for schema documents therefore require an extended meta-schema; writing one whose type is declared only locally is a resolver error (§6), not a silently valueless annotation. Custom annotations for data documents require only a declaration in the user schema.


#### 3.3.4 Data Documents and Schema Layering

A data document's `!!schema` points to a single user schema, and that schema's namespace — locals plus imports, one hop — is the document's entire vocabulary. Type annotations and annotations resolve against it; nothing else is reachable. If a data document needs a core type like `uuid`, that type must be imported into the user schema.

A consequence: the structural vocabulary is invisible from ordinary data. `!enum`, `!record`, and `!type_definition` are not names in an application schema's namespace, so ordinary data documents cannot express resolved-schema structure; only a data document governed by a meta-schema (resolver output, §8) can, because the meta-schema's namespace carries that vocabulary through its kernel import. This pairs with §10.1's rule that resolved-form documents are never schema sources.


#### 3.3.5 Duplicate Names Across Layers

A type name defined in both the meta-schema and a type library is not a conflict: the two namespaces are consulted at different grammar positions. The kernel's `text` is not visible as a type-ref in a meta-governed schema — only an imported `text` (core's) is, and core's `text` is a fresh definition, not a view of the kernel's. No position consults both: a `!` target resolves through the structure namespace only, and every other reference through the type-name namespace only (§3.3.1), so a declaration can never capture a constructor name and a constructor can never shadow a type.


### 3.4 The Meta-Schema Bootstrap

The ladder terminates at the meta-kernel, whose `!!meta` references its own URL. The kernel's types are pre-loaded into the schema library by the implementation: they exist as in-memory structures before any document is parsed. When the kernel document is parsed, its type annotations resolve against these pre-loaded structures through the normal library lookup. The meta-schema (`meta.tn`) is also pre-loaded; its constructors are resolved against the pre-loaded kernel before being registered as pre-loaded entries themselves.

The kernel defines its own core types (`integer`, `text`, `boolean`, …) directly so that its own constraint-field declarations can reference them locally. The kernel's local entries are the structure namespace of every schema the kernel directly governs — meta itself, and any alternative meta-schema; meta-governed schemas receive the same vocabulary one hop away, through meta's kernel import (§3.3.1). These types are NOT automatically available as type-refs in governed schemas (§3.3.2); schemas that want `integer` or `text` as type-refs import a type library that defines them (typically `core.tn`).

The kernel and meta documents are descriptions of the pre-loaded types, not the source of them. Parsing them validates that the document's description matches the implementation's in-memory model; if they disagree, the document is invalid — the in-memory model is authoritative.


#### 3.4.1 Two-Pass Resolution

Schema resolution proceeds per schema as **Parse → Desugar → Pass 1 → Pass 2**, with imports fully resolved before any phase runs on the importing schema.

**Desugar** is purely syntactic and per-declaration: sugar forms rewrite to canonical constructor applications, and every nested form lifts to a synthetic declaration — closed for a concrete form, open for a parameter-bearing one (§5.3) — entering Pass 1 alongside declared names. A template declaration contributes its own name plus one synthetic per open form it holds.

**Pass 1 — Name population.** The resolver collects all declaration names in the schema document, synthetic declarations included. The schema's type-name namespace is populated with skeleton `type_definition` records keyed by name. Bodies are not yet validated.

**Pass 2 — Body resolution and validation.** The resolver resolves each entry's body against the populated namespace. Forward references between local entries work in this pass. The resolver validates that references resolve, that composition and refinement rules hold, that type arguments match parameter arities. It computes the transitive IS-A graph (`type_definition.supertypes`) and derives the inverse (`type_definition.subtypes`).

**Imports run first.** Resolution is defined over the schema's **reference closure**: the set of schema documents reachable through `!!meta` and `!!import` references, collected transitively. The order is:

1. **Collect the closure.** Starting from the schema under resolution, follow every `!!meta` and `!!import` reference, deduplicating by canonical identity ([TSON-DATA] §2.2.1): each canonical identity names one schema, loaded once, however many references reach it.
2. **Verify per identity.** Verification attaches to canonical identities, not to individual references: for each identity in the closure, collect the set of digests declared across all references reaching it. An empty set resolves without verification. A single digest is verified against the identity's content per §10.2 — once, at this step, before any schema in the closure resolves — and every reference to the identity, pinned and plain alike, thereafter resolves to the verified instance; a verification failure is a resolver error for the identity across the whole resolution, and no reference falls back to the rejected content. Two or more distinct digests for one identity is a resolver error (§10.2) — at most one can describe the real bytes.
3. **Order the closure.** The reference relation over the deduplicated closure MUST be acyclic (§2.2.3); the resolver computes a topological order — dependencies first — and reports the cycle path as a resolver error where none exists.
4. **Resolve in order.** Each schema in the closure is resolved exactly once, in topological order, by its own two passes; when a schema's turn arrives, every schema it references is already resolved. For each schema: merge each `!!import`'s local entries, in declaration order, into the accumulating type-name namespace — merged entries stay bound to their defining schema's namespace (§2.2.3), and collisions between imports are resolver errors (§2.2.3); then Desugar rewrites sugar and lifts synthetics, then Pass 1 collects local entry names, a collision between a local name and an already-merged import being a resolver error (synthetic names are resolver-chosen and fresh by construction, so they never collide with declared names; a synthetic arriving through an import whose identity an own application re-derives denotes the merged entry, §8.2); then Pass 2 resolves bodies against the populated namespace.

Pre-loaded schemas (the kernel and meta, §3.4) enter the closure already resolved and occupy the leaves of every order. Collection follows references only into schemas not yet resolved: a reference to an already-resolved schema terminates there — which is how the kernel's self-referencing `!!meta` grounds the ladder (§3.4) rather than forming a cycle. Digest sets (step 2) are collected over the references observed during this collection: a terminating reference still contributes its digest to its target identity's set, while an already-resolved schema contributes no outgoing references — its dependencies were verified when it was resolved (or shipped, for pre-loaded entries, whose pins are checked against the implementation-held digest per §10.2). Whether a dependency happens to be pre-loaded therefore never changes whether a closure loads.

Forward references are permitted within a schema: a definition may reference any other declaration in the same schema, declared earlier or later. Annotations resolve against the governing target's namespace (§3.3.3), not through the resolving schema's own Pass 1 namespace or imports.


### 3.5 Schema Evolution

Each published schema version is immutable; a schema's identity is its exact byte content. Version N and version N+1 are independent artifacts with different content hashes and different URLs. TSON defines no version negotiation, migration rules, or compatibility checks; accepting data validated against multiple schema versions is a deployment concern. See §7.2 for the records-are-closed rule.


## 4. The Type System

The type system is defined by the meta-kernel's vocabulary — the base kinds, the constructors, and the `type_definition` record — and every construct of the type-definition grammar (§5) is notation over it: each declaration form resolves to a `type_definition` whose body is the canonical constructor form `!C { bindings }` (§5.6, §8).


### 4.1 Kinds

The kernel defines `top` as the structural root and four **base kinds** — `atom`, `product`, `sum`, and `data` — each composing with `top` via `top & {}`. Every constructor in the kernel and in meta composes, directly or through another constructor, with one base kind, and each base kind IS-A `top` — so every constructor transitively IS-A `top`. IS-A does not extend below construction: `!T {}` transfers kind, not supertypes (§5.5), so constructor instances (`integer`, `value`, `dynamic`) and fresh records (`person`) carry empty supertype chains and are not IS-A `top`. That boundary is what makes IS-A `top` the predicate for *being a constructor*: an entry IS-A `top` describes a type and may be applied with `!` (§3.3.1, §4.2); an entry that is not — an instance, a fresh record, or one of the kernel's supporting records — describes a value or a part of a type. What is universal is *kind* membership — every entry has exactly one of the five kinds:

- **Atom** — scalar types. An atom constructor composes with `atom` via `atom & {...}`; its record of constraint fields describes the narrowable vocabulary available to instances. The kernel and meta define the series' atom constructors (§9); the `unit` constructor is the atom with no constraint vocabulary. Atom instances are produced by constructor application — `!<ctor> {}` (empty) or `!<ctor> { values }` — and refined with `!<instance> ^ { values }` (§5.5).
- **Product** — structural types. `record`, `array`, `set_type`, `map`, and `tuple` compose with `product`, fixing `access_pattern` and `size_type`; the container constructors expose their type slots as REQUIRED `type_ref`-typed fields (§4.2). Bare `{...}` definitions without explicit composition resolve to `kind: PRODUCT` by structural default.
- **Sum** — types in which the value names its own type. `choice` in the kernel is the *closed* sum, enumerating its variants; `scoped` in meta is the *open* sum, naming the namespaces its variants are drawn from (§7.8). Core's `declared`, `extern` and `dynamic` are `scoped` instances over the three admitting subsets.
- **Data** — the non-type kind. The first three kinds describe the shape of a data value; `data` describes an entry that is *not* one — vocabulary a meta-schema introduces beyond the kernel's own, whose instances ride along in a schema map without being types (an HTTP operation binding request and response types by name is the motivating case, §9). A constructor composing with `data` (`operation => data & { ... }`) is IS-A `top` like any other and yields instances resolving to `kind: DATA`; a governed schema may write `!operation { … }`. No data value ever has such an entry as its type: **naming a `kind: DATA` entry where a type is expected — a field type, element type, variant, argument, composition operand, or refinement source — is a resolver error**, checked at schema load. Kind determination extends accordingly: `data` joins the base-kind names §5.5's kind transfer searches for.
- **Reference** — a type definition whose body is a pointer to another type: a `kind: REFERENCE` entry with body `!reference { target: T }`, where `target` names an entry (§8.3); when the reference was written as an application, the applied form is recorded in the entry's `source` (§8.1). References are aliasing relationships, not IS-A: a reference is a hop, stated as written in resolver output and collapsed only when a processor compiles for reading (§8.3). `reference` composes with `top` directly and is applicable (§3.3.1); its entries take `kind: REFERENCE` from the alias form rather than from a base kind.


### 4.2 Type Construction

Type constructors are factories that produce type definitions. **A constructor is an entry that IS-A `top`** (§4.1): one that composes with a base kind or with another constructor, or refines one — the position in the IS-A chain is what makes it applicable with `!`, and nothing is asserted by an author beyond the composition itself. There is no marker and no `constructor` field: constructorness is inherited through composition, derivable from `supertypes` wherever it is wanted (§8.1), and never a property a declaration states apart from its chain. There is no construction in data: `!C { bindings }` produces a new type only in the schema grammar (§7.2). The kernel's container constructors are parameterless: a type slot (`array`'s `element_type`, `map`'s `key_type` and `value_type`) is an ordinary REQUIRED field typed `type_ref`, filled by the construction — or by the sugar's desugaring (§5.3) — the way data supplies any required field, so a closed application's binding record is ordinary data of the constructor's vocabulary (§7.2). Where a *parameter* must route into a slot, the route arises at the application site inside a user-template body (§5.10), never in a constructor's own declaration.

One declaration-time rule governs constructors: **placement**. An entry that IS-A `top` may be declared only in a meta-schema — a schema document whose own `!!meta` names the meta-kernel (§2.2.2); elsewhere it is a resolver error at the declaration. The rule is asked of every declared entry after its supertype chain resolves, so it covers what a level rule would otherwise have to: an ordinary schema composing with, refining, or subtracting from a constructor has declared an entry that IS-A `top` and is refused for that; in a meta-schema the same composition is exactly what the author is doing — extending a constructor's vocabulary — and the two IS-A relations stay apart by placement, one layer up, with nothing left for a marker to repeat. A constructor's parameters are read as any template's are (§5.10): an argument is substituted as a token and read by the position it lands in, so a slot typed `type_ref` takes a type where an atom-typed slot takes a value, and no rule confines a constructor's parameters to one channel.

The `~` character has one use in the type-definition grammar: the **default-value modifier** in a field definition after a type-ref (`port: integer ~ 8080`, §5.2). Constructor refinement — a constructor refining another, as `set_type` refines `array` — is written with `^` like any refinement; it is a meta-level operation by placement, and, unlike regular refinement, MAY replace fixed values (§5.7, §5.8).

**Constraint-vocabulary atom pairs.** Atom families whose instances can be narrowed with constraint values are defined as pairs: a constructor carrying the constraint vocabulary (`atom & {...}` listing the family's narrowable fields) and a canonical empty instance (`!<constructor> {}`) that records `source: <constructor>` but establishes no IS-A. Refinements use the `^` operator — `age => !integer ^ { min: 0  max: 150 }` — which DOES establish IS-A with the refined instance (§5.5). Spec-bound constructors additionally compose with the kernel's `atom_specification` mixin and pin their `spec` field. §9 inventories the families.

**The `unit` atom constructor.** Atoms with no constraint vocabulary are constructed from `unit`. Its instances are opaque atoms distinguished by name and parsing contract (§7.4): the resolved shapes are identical and deliberately uninformative, so implementations MUST dispatch `value`, `identifier`, and `void` by their declared names — these are internal, machine-recognised primitives whose contracts are fixed by this specification, not derivable from schema shape. The kernel defines three:

- `value` — the token, uninterpreted, read by the type the position hands it to: a `value`-typed field is read by the declared type it stands for (§7.4), never by base type resolution, which applies only in schemaless documents ([TSON-DATA] §4.1); its host inhabitants are a boolean, an integer, a float or a string. The escape hatch for fields whose type the schema language cannot express at the point of declaration. A `value` position is a single token and not a scope: it admits no nested `!!schema`, having no tag of its own for one to resolve (§7.8).
- `identifier` — admits names: the decoded text of a token matching the identifier grammar of [TSON-DATA] §7.7, in NFC, however it was spelled. The type of every naming position in the series — type names, field names, and parameter names through the three roles `type_name`, `field_name`, `param_name`, and enum members through `enum_set` (§7.4) — so a rule stated on it reaches all of them.
- `void` — the unit type of absence: its sole value is the absent sentinel `_`, and nothing else is admitted at a `void`-typed position (§7.3). `void` is the target type for bare annotations (§6) and usable in data as a field type meaning "no value". It is not a valid choice variant (§5.4): optionality is a property of a position, not a type.

Core declares its own `void` under the same name — a fresh sibling (§3.3.5): the same `!unit {}` construction and the same contract, a distinct type entity — so that data documents governed by core-importing schemas can target it (§9, §7.3), and both meta and core declare `set` — the `set<T>` template over the kernel's parameterless `set_type` — on the same pattern (§5.3, §9). User schemas SHOULD NOT introduce additional unit instances without a documented parsing contract.


### 4.3 Operations

TSON's type operations fall into two families. **Construction** operations compute a new field set and declare their own contract: a bare record claims none, constructor application transfers kind only, composition grants IS-A per parent, and subtraction revokes IS-A for every parent while keeping lineage (§5.9 states why the break is total). **Refinement** inherits an existing contract and tightens within it, always preserving IS-A.

**Both families consume vocabulary bodies.** The source of a refinement and every operand of a composition or subtraction MUST, after following its reference chain (§8.3), be a definition whose body is a `!record` — a shape with fields to tighten or merge. A definition whose body is a binding record — a top-level constructor application (§5.6), a template instantiation (§8.2), or an alias resolving to either — is *finished* and admits neither operator; a choice is likewise inadmissible — it has variants, not fields, so there is nothing to tighten and nothing to merge. §5.7 and §5.8 apply this rule to their own operand forms.

Each operation is defined with its grammar form in §5:

| Operation | Syntax | Section | IS-A | Adds fields | Removes fields | Tightens fields |
|---|---|---|---|---|---|---|
| Record construction | `{ ... }` | §5.2 | none | yes | n/a | n/a |
| Constructor application | `!C value` | §5.5 | none (kind transfers) | n/a | n/a | n/a |
| Composition | `A & B & { ... }` | §5.8 | preserved (each parent) | yes | no | yes |
| Subtraction | `head - { fields }` | §5.9 | broken (lineage kept) | via the `&` body | yes | via the `&` body |
| Refinement | `T ^ { ... }` | §5.7 | preserved (source) | no | no | yes |
| Atom refinement | `!I ^ { values }` | §5.5 | preserved (source) | no | no | yes |
| Instantiation | `!T value` in data | §7.2 | n/a (data) | n/a | n/a | n/a |

Instantiation is data-level and terminal. A refined definition remains a definition: it can be refined further or instantiated. All parameters in a definition MUST be bound before instantiation (§5.10).


## 5. The Type Definition Grammar

Each declaration in a schema document binds a type name to a type definition: `name => type-def` (§2.1). Everything to the right of `=>` is parsed by the type-definition grammar; the complete ABNF is in §12.1. Each construct is defined by its syntax, its rules, and the canonical `type_definition` it resolves to — a form's meaning is its resolution (§8).

Inside the type-definition grammar, type positions are determined by grammar context — the `!` prefix marks constructor application and atom refinement (§5.5), not type reference. Type names used as type-refs resolve against the type-name namespace (§3.3.2).


### 5.1 Declarations

The right-hand side of a declaration takes one of the following forms; each resolves to a `type_definition` (§8) as defined in the sections below.

```
person => { name: text  age: integer }                    ; record construction (§5.2, §5.3)
employee => person & contact & { department: text }        ; supertype composition (§5.8)
account_public => account - { password }                   ; subtraction (§5.9)
production => config ^ { host: = "prod.example.com" }      ; record refinement (§5.7)
status  => !enum [ACTIVE INACTIVE SUSPENDED]               ; constructor application (§5.5)
age     => !integer ^ { min: 0  max: 150 }                 ; atom refinement (§5.5)
set_type => array ^ { unordered: = true }                  ; constructor refinement (§4.2), in a meta-schema
id      => uuid                                            ; type reference (§8.3)
scores  => [integer; 1..]                                  ; array type (§5.3)
point   => [number, number]                                ; tuple type (§5.3)
contact_method => (email | phone | address)                ; choice type (§5.4)
translations   => {text => text}                           ; map type sugar (§5.3)
```


### 5.2 Field States

Each field in a record definition has one of five states, spelled through two interacting markers: **presence** (required vs optional, the `?` suffix) and **mutability** (free, default, or fixed, the value modifier). The markers are not independent axes — six spellings collapse to five states, one state has two forms, and three combinations are errors (below) — because presence and mutability constrain each other: a default implies presence, and a fixed value means different things depending on whether the field's *appearance* is itself information. A record body may also contain **field groups** — sets of mutually exclusive labelled fields — defined in §5.11.

The presence axis is determined by the type suffix: `type` is **required**, `type?` is **optional**. The mutability axis is determined by the value modifier that optionally follows the type expression: no modifier — the field is **free**; `~ token` — the value is a **default**, used when no value is supplied but overridable by refinement or instantiation; `= token` — the value is **fixed**, immutable from this point down. Whitespace around `~` and `=` is optional.

```
config => {
  host:   text
  port:   integer ~ 8080
  debug:  boolean = false
  label:  text?
  format: text? = json
}
```

The field states — five states across six spellings; `OPTIONAL_FIXED` has a valued and an absent form:

| Syntax                    | State              | Meaning                                    |
|---------------------------|--------------------|--------------------------------------------|
| `field: type`             | REQUIRED           | Must be filled by refinement or instantiation |
| `field: type ~ value`     | REQUIRED_DEFAULT   | Value used when not supplied, overridable  |
| `field: type = value`     | REQUIRED_FIXED     | Value is immutable from this point down    |
| `field: type?`            | OPTIONAL           | May be absent; no value required           |
| `field: type? = value`    | OPTIONAL_FIXED     | If present, must be this value             |
| `field: type? = _`        | OPTIONAL_FIXED (no value) | Field is forbidden from carrying a value; encoded in output as a `record_field` without a `value` field |

Value modifiers are restricted to scalar tokens — quoted or unquoted — covering strings, numbers, booleans and enum members; complex modifier values (arrays, records, maps) are not supported in v1 (§12.1). The `=` modifier additionally accepts the absent sentinel `_`, valid only when the field is OPTIONAL (declared with `?` or inherited as OPTIONAL): `field: type? = _` produces OPTIONAL_FIXED with an absent fixed value — the field MUST either be omitted or be the absent sentinel in conforming data; any other value is a validation error. The following are resolver errors:

- `~ _` (any field) — a required field cannot fall back to not-being-filled.
- `= _` on a REQUIRED field — a field cannot be required and fixed to not-being-present.
- `type? ~ value` — a default implies the field is always present, contradicting optional semantics. Use `type ~ value` for a fallback, `type?` for absence, `type? = value` for present-implies-value.

**Which fields may carry a value.** A `~` or `=` value is admitted only on a field whose declared type resolves, after following its reference chain (§8.3), to an **atom-family instance or an enum** — the types a single scalar token denotes directly. On a field of any other declared type — a record, container, tuple, choice, `void`, or a `scoped` instance — a value modifier is a resolver error at the declaration, whatever token stands beside it. The positional form (§5.6) is a spelling rule for data values and does not make a record a token: admitting `p: point ~ 3` would make a field's eligibility for a default depend on another declaration's field count, so that adding a second field to `point` silently invalidated a default written elsewhere. The refusal costs two spellings and buys a rule that fits in one line.

**Value conformance.** A fixed or default value MUST conform to the field's declared type — the dependency the kernel's `record_field.value` slot cannot express structurally is stated here normatively. The check is the field's own parsing contract (§7.4): a value is accepted exactly when a read would accept the same token at that position. It applies wherever the value came from: written literally (`{ first: int32 ~ "nope" }` is refused at the declaration), or routed from a template parameter and checked at materialisation (`retry => <N> { attempts: int32 ~ N }` applied as `retry<text>` is refused identically, §5.10). This one rule is what makes an argument's *kind* unnecessary to declare (§5.10): a type name that reaches a value slot fails conformance, and a literal that reaches a type slot resolves to no type.

**Eager resolution.** Default and fixed value tokens are resolved and validated at schema-load time. The token is parsed by the field's type — by the atom's parser; a `value`-typed field is read by the atom the position stands for once that atom is in scope (§7.4) — and stored as the resolved host value. A default or fixed value that fails parsing or the type's constraints is a resolver error, reported at schema load. This matches §7.4's eager-conversion rule for constraint-field values. Inside a template body a value slot may instead hold a parameter (§5.7), which is resolved when the template closes.

**Default injection.** Injection is stated for all five states in one place. When a field has `state: REQUIRED_DEFAULT` or `REQUIRED_FIXED` and the data does not provide a value, the decoder injects the default (or fixed) value into the output: decoded values are fully populated, and consumers do not consult the schema to retrieve defaults. **OPTIONAL and OPTIONAL_FIXED fields are never injected**: an omitted OPTIONAL_FIXED field is absent in the decoded output — the state exists precisely because the field's *presence* carries the information while its value is pinned, which is why it is useful as a group member (§5.11, where presence selects the alternative) and nearly contentless on a plain field. An explicit absent sentinel `_` at any REQUIRED-family field is a validation error — `_` asserts absence at a position the schema always fills; at REQUIRED_DEFAULT the fix is to omit the field, and omission remains the injection route (§7.6). Encoders SHOULD write values for defaulted fields — a document that states its defaults reads without its schema; omitting a field whose value equals its default is a wire-size optimisation an encoder MAY offer, lossless only because the decoder injects the value back on read. Resolver output is exempt from this SHOULD: it omits fields at their default values (§8.1).

In data, a FIXED-state field (REQUIRED_FIXED, or OPTIONAL_FIXED when present) may be provided with a value matching the fixed value, or omitted (REQUIRED_FIXED injects; OPTIONAL_FIXED stays absent). **A written value at a FIXED field MUST be verified against the fixed value**: a contradicting value is a validation error, never a value the decoder silently overwrites — an implementation that seeds fixed fields and skips the check produces decoded output that differs from the bytes with no diagnostic.

**Resolution.** A record definition resolves to a `type_definition` with `kind: PRODUCT` and `body: !record { fields: [...] }`. Each field maps to a `record_field` record `{ name  type  state  value? }`: `type` carries the type reference as written (a reference is a hop, §8.3), `state` the field state (the default `REQUIRED` is omitted in output), and `value` the eagerly-resolved default or fixed value. An empty record `{}` is the zero-field case, `body: !record { fields: [] }` — the shape of the kernel's `top`.

**Type-name resolution.** Type names used as type-refs (field positions, type arguments, choice variants, composition targets, refinement sources) and generic-application heads resolve against the type-name namespace; constructor-application targets and the desugar targets of the sugar forms resolve through the structure namespace per §3.3.1. Bare names always refer to types — there is no field-name shadowing of type names.

**Inline atom refinements and bare records are prohibited.** Atom refinements (`!number ^ { min: -273.15 max: 10000 }`) and bare records (`{ name: text }`) MUST be introduced via named declarations and referenced by name; they MAY NOT appear inline in field-type, field-group-member, tuple-element, array-element, choice-variant, type-argument, or composition positions. The container sugar forms carry no positional restriction (§5.3): a size specifier or an element/position `?` is legal wherever the bracket or map form is. Implementations MAY enforce a configurable nesting-depth limit on inline forms as a resource bound in the manner of [TSON-DATA] §9.1; where such a limit is enabled, exceeding it is an error.


### 5.3 Type Expressions

The type-definition grammar has **one form per container, legal at every type-ref position** — field types, group members, tuple elements, array elements, choice variants, type arguments, and declaration bodies alike. There is one bracket form and one map form, each admitting a size specifier after `;` and an element/position `?` wherever the form appears; nesting is the recursion already present in the element position (§12.1). Whether a form sits as a declaration's own body or inline at a use site changes what the resolver *does* with it — a declaration's body is the construction in place, everything else lifts to a synthetic entry (below) — never what may be written.

```
config => {
  tags:     [text]                 ; plain array
  meta:     {text => integer}      ; map sugar
  point:    [number, number]       ; tuple, all positions REQUIRED
  contact:  (email | phone)        ; choice
  index:    {text => [order]}      ; nested forms
  aliases:  [text]?                ; the trailing ? is FIELD optionality (§5.2)
  scores:   [integer; 1..]         ; size specifier, legal at a field
  sparse:   [text, text?]          ; OPTIONAL tuple position, legal at a field
  batch:    order_batch            ; a named declaration remains good style
}

order_batch => [order; 1..100]     ; bounded range as a declaration body
matrix9     => [number; 9]         ; exact size
opt_items   => [text?]             ; OPTIONAL elements
```

**Array types** use `[type]` with an optional size specifier after `;`. The size specifier is a grammar production over the range token ([TSON-DATA] §7.2.4): a bound, optionally followed by `..` and an optional upper bound, or `..` followed by a bound (§12.1). Each bound is an unquoted token whose text MUST match the `decimal-natural` production of [TSON-DATA] §7.6 — a non-negative decimal integer without leading zeros — or, within a template body (§5.10), a value-parameter name. Classification is unambiguous — parameters cannot be numeric — and a non-numeric bound token that resolves to no value parameter is a resolver error. Five forms result: `N` (exactly N elements), `N..M` (bounded range), `N..` (at least N), `..M` (at most M), and absent (unconstrained). `[T; N..N]` and `[T; N]` are two spellings of the same binding and land on the same entry (§8.2); the `N` form is RECOMMENDED. A lower bound of `0` with no upper bound (`0..`) is a resolver error — every array satisfies it, and because identity is structural (§8.2) the spelling would mint an entry distinct from `[T]` that means the same thing; the diagnostic SHOULD say that the unconstrained array is written `[T]`.

**Bound coherence.** A size specifier desugars to the `min_items`/`max_items` binding pair (the table below), and one rule governs the pair wherever it arises, arrays and maps identically: when both bounds are present, `min_items` MUST be less than or equal to `max_items`, checked at the point the bounds are concrete — a resolver error at schema load where the bounds are literal, at materialisation where parameter-bound (§8.2).

The element position accepts an optional `?` suffix, producing `state: OPTIONAL` on the resolved `array`. Under `[T?]`, elements at any position MAY be the absent sentinel `_` (§7.6); absent elements occupy positional slots — `[a _ c]` has three elements and satisfies a `[T?; 3]` size constraint. Without the suffix, `state` defaults to `REQUIRED` and absent elements are a validation error when a schema is in scope. The kernel's `set_type` constructor refines `array`, pins `state: = REQUIRED` — absence has no meaning in an unordered collection of unique members — and defaults `min_items` to 1, so a set is non-empty unless a body writes `min_items: 0`. Sets have no bracket sugar of their own; the spelling an author writes is the template `set<T>`, which meta and core each declare as `set => <T> !set_type { element_type: T }` (§9) — a §5.10 application, admitted at a field position where a `!` construction is not (§5.2) — or a named entry such as the kernel's `enum_set`.

**Map types** use `{key => value}`, mirroring the data notation, with an optional size specifier after `;` (`{text => order; 1..}`) desugaring to `min_items`/`max_items` under the same grammar, coherence, and diagnostic rules as arrays. The sugar's key position accepts a `type-name` optionally carrying type arguments — not a paren or bracket form: composite map key types deserve a named declaration, and the explicit `!map { key_type: … }` form remains available for them. The sugar takes exactly one `key => value` entry — a map *type* has one key type and one value type; the sugar mirrors the data's shape, not its arity, and the diagnostic for a second entry SHOULD say so. The value side admits `?`, marking the value OPTIONAL exactly as `[T?]` marks an array element: `{text => order?}` produces `state: OPTIONAL` on the resolved `map`, under which an entry's value MAY be the absent sentinel `_` (§7.6) — the entry is present with an absent value ([TSON-DATA] §2.9) and counts toward the size bounds like any other. Without the suffix, `state` defaults to `REQUIRED` and an absent entry value is a validation error. The key side does not admit `?`: an absent key is a resolver error ([TSON-DATA] §2.9), and `map-key` has no `?` to write (§12.1). Annotations inside the sugar braces are a parse error; the declaration is the annotation anchor. Brace dispatch between the record and map readings is by one consumed token plus one of lookahead, reusing [TSON-DATA] §2.8's machinery (§12.2).

**Tuple types** use `[type, type, ...]` with comma or whitespace separation between individually typed positions, and a comma may follow the last ([TSON-DATA] §2.4). A tuple requires at least two element type expressions: two or more type-refs separated by whitespace or comma inside brackets is always a tuple; a semicolon after a single type-ref introduces an array size specifier; a single type-ref with no semicolon is an unconstrained array — never a one-element tuple. `[text,]` is therefore an unconstrained array of `text`, the trailing comma following a value and changing nothing.

Tuple positions support only REQUIRED and OPTIONAL states (tuples and arrays share the two-member `element_state` enumeration; records use the five-member `field_state`). Tuples are fixed-length: every position MUST be present in the data. An OPTIONAL position may carry the absent sentinel `_`, but the slot itself MUST appear — a tuple value with fewer elements than the type's position count is a validation error regardless of trailing-optional positions. Given `sparse_pair => [text, text?]`: `[a, b]` and `[a, _]` are valid; `[a]` is a validation error. Authors wanting trailing-optional semantics should use an array (`[text; 1..]`).

**Choice types** use `(type | type | ...)` — see §5.4.

**Arguments** use `name<arg, arg>`, binding positional arguments to the declared parameters of a template (§5.10); the head resolves through the type-name namespace only (§3.3.1). The argument count MUST match the parameter count. An argument is a type reference — which may nest (`box<[integer]>`, `pair<text, {text => order}>`) — or, for a template with value parameters, a concrete value: `vector<pixel, 1920>`. Number and quoted-string arguments are unambiguously values; an unquoted token argument that satisfies the identifier grammar — `true` and `false` included — is substituted as a token and read by the position it lands in — a reference where the parameter stands in a type position, a literal (an enum member, for instance) where it stands in a value position (§5.10). Bare references to a parameterized type without `<>` are resolver errors — parameter binding is mandatory at every use site.

**Desugaring.** Every sugar form desugars to a canonical constructor application (§5.6):

| Source form                  | Desugaring                                                                     |
|------------------------------|--------------------------------------------------------------------------------|
| `[T]`                        | `!array { element_type: T }`                                                   |
| `[T; N]`                     | `!array { element_type: T  min_items: N  max_items: N }`                       |
| `[T; N..]`                   | `!array { element_type: T  min_items: N }`                                     |
| `[T; ..M]`                   | `!array { element_type: T  max_items: M }`                                     |
| `[T; N..M]`                  | `!array { element_type: T  min_items: N  max_items: M }`                       |
| `[T?]`, `[T?; ...]`          | the corresponding form with `state: OPTIONAL` bound directly                   |
| `[T, U, ...]`                | `!tuple { elements: [...] }`                                                   |
| `(A \| B)`                   | `!choice { variants: [A B] }`                                                   |
| `{K => V}`                   | `!map { key_type: K  value_type: V }`                                          |
| `{K => V ; spec}`            | `!map { key_type: K  value_type: V  min_items/max_items: … }`                  |
| `{K => V?}`, `{K => V?; …}`  | the corresponding map form with `state: OPTIONAL` bound directly                |
| `set<T>`                     | the template application `!set_type { element_type: T }` through meta's or core's `set` (§9) |
| `C<args>`                    | a template application (§5.10, §8.2); `C` resolves through the type-name namespace only (§3.3.1) |

Array forms record `element_type`, `state` from the element's `?` suffix, and `min_items`/`max_items` from the size specifier; tuple forms record `elements` with a `tuple_element` per position, each carrying its own `state`; map forms record `key_type`, `value_type`, `state` from the value's `?` suffix, and the bounds.

**Nested forms and synthetic entries.** Every sugar form **lifts** at desugar time to a resolver-materialised entry — a **synthetic entry** — except a declaration's own body, which never lifts: it *is* the declaration (`ids => [order]` is the construction in place, not a reference to one). The lift rule's dividing line is closed versus open: a **concrete** form lifts to a *closed* synthetic entry with an ordinary constructor body; a **parameter-bearing** form — one mentioning a parameter of the enclosing declaration — lifts to an *open* synthetic entry capturing those parameters in declaration order, whose body is the constructor application as written, held until materialisation (§5.10, §8.2). The rule needs no case for whether the enclosing declaration has parameters: a concrete `[order]` inside `<T> { a: T  b: [order] }` lifts closed, like any other concrete form. Materialisation creates no synthetic entries — it closes open ones, innermost-out (§8.2). Synthetic names follow §8.2's internal-name rules: resolver-chosen, fresh by construction, disjoint from declared names, and unreachable from source — and the entries themselves are an implementation's own: their shape is unconstrained beyond producing the required resolved output, and a processor is free to mint one per schema, share them, or normalise them however it likes (§8.2). The lift targets are grammar-supplied and never kernel-declared, so desugaring stays purely syntactic and independent of the import set. Because every form lifts to an entry, nesting needs no special channel — an inner form's states, sizes, and bindings live on its own entry, and the outer form references it by name — and the positional restrictions earlier revisions imposed on inline forms are gone: a size specifier or `?` is legal wherever the form is.

A **use-site application never resolves in place**: an inline constructor application, sugared or explicit, resolves to a reference to its (synthetic) entry, and a fully-bound application of a non-constructor template resolves to a reference to its materialised instantiation entry (§8.2). `type_ref.arguments` therefore appears in resolver output only where an application is still *open* — inside held template bodies (§5.10), in an open alias's `reference.target` (§8.3), and in the `source` field that records how an entry was derived (§8.1) — and means "an application", nothing else.


### 5.4 Choice Types

A choice type declares that a value may conform to any one of a set of alternative types:

```
contact_method => (email | phone | address)
```

A choice MUST contain at least two variants; each variant is a type reference. The choice name is then used like any other type name in field definitions, and choices MAY also appear inline in type-ref positions: `contact: (email | phone)`.

**A variant MUST NOT resolve to `void`** — judged after following the reference chain (§8.3), so an alias of `void` is caught too; the declaration is a resolver error, and the diagnostic SHOULD say why: optionality is not choice — a value's absence is the position's own state, so the author marks the position optional (§5.2's `?`), or types the field `void` outright where a unit placeholder is genuinely meant. `(T | void)` would create a second, worse spelling of "optional T": absence is the one thing the format spells one way ([TSON-DATA] §2.9), and it is a state of a position, never a variant.

**Disjointness** is a total, two-valued fact of the declarations: **discrimination-class distinctness**. Every type has at most one **discrimination class**, derived after following its reference chain (§8.3):

| Class | Types |
|---|---|
| `boolean` | the boolean family |
| `number` | every numeric family — integer, decimal, and float tiers alike |
| `string` | every text-form family — `text`, `uuid`, `uri`, `email`, the temporal families, `bytes`, the network-address families, and their refinements |
| `brace` | records and maps (both `{...}`) |
| `bracket` | arrays and tuples (both `[...]`) |

An enum's class is its members' shared class (`[true false]` is boolean-class; mixed members yield none). A type with no single class — `rational` and `complex` (whose typed forms straddle classes), the `unit` instances (`value`, `identifier`), a nested choice, a `scoped` instance (§7.8), or an unresolvable or cyclic reference — has none, and a classless variant makes its choice not disjoint. **The rule:** a choice is `disjoint: true` if and only if every variant has a class and no class appears twice; `false` otherwise. The procedure is closed and normative: a resolver MUST record exactly this — it MUST NOT prove more (value-set separation such as disjoint numeric bounds or disjoint patterns does not make a choice disjoint) or less. The classes are [TSON-DATA] §4's own host-type partition plus the two delimiter forms — three scalar classes and no more, deliberately ([TSON-DATA] §4.5): a class for a text-form family such as `date` would narrow what a `text` variant admits untagged in every schema that never mentions a date — and every text-class encoding shares them, so the fact is portable across encodings by construction. Every declared choice records `disjoint`; there is no absent state for a choice.

**A narrower constructor leaves values unreachable.** Discrimination recovers the variant from the value's class and nothing else, so a value dispatched to a variant that then refuses it is refused outright — there is no second attempt at another variant. A constructor narrower than its base-type class therefore leaves values of that class unreachable in any untagged choice it appears in: core's `number` is decimal-only where [TSON-DATA] §4's `number` production admits `0xFF`, `0b1010`, `0o377`, `.inf` and `.nan`, so `( number | text )` classifies `0xFF` as number-class, dispatches it to `number`, and fails, never reaching `text`; `( int32 | text )` refuses `99999999999999` the same way, and a pattern-refined `text` beside another string-class variant refuses everything the pattern excludes. This is a consequence to know rather than a rule to add, and it is one more reason the labelled form (below) is the shape for alternatives an author expects to overlap.

**Tagging.** At the data level, a value matching a choice type carries a type annotation (`!variant`) selecting the variant. The tag is REQUIRED when the choice is not disjoint, and MAY be omitted when it is: `disjoint` means precisely that the encoding's own form resolution — for TSON text, the single base-type-resolution pass of [TSON-DATA] §4, plus the brace/bracket delimiter — recovers the variant, so an omitted tag is recovered from the value's class, never by a second, type-directed inspection of the value's form; the once-only reading of form ([TSON-DATA] §2.4) is preserved. A value omitting the tag at a non-disjoint choice is a validation error. For an emitter the rule is one sentence: *if two variants share a class, tag every value of the choice; a tag is never wrong.*

**The `@disjoint` assertion.** An author MAY annotate a choice definition with `@disjoint` (defined in `meta.tn` as a `void`-targeted marker, written bare per §6 — presence is the assertion) to assert that its variants are **distinguishable by the encoding's form resolution** — the property the derivation checks, and a weaker and more useful one than mutual exclusion of inhabitance. Overlap in inhabitance is ordinary and expected: [TSON-DATA] §2.4's *form is not meaning* makes `text` admit every token, so `42` inhabits both variants of `( int32 | text )`, and the choice is disjoint all the same because the token's class recovers the variant. The annotation carries no decode force — the resolver computes `type_definition.disjoint` whether or not it is present — and is a **checked annotation** (§6) with exactly two outcomes against the derived fact: **verified** (`disjoint: true`; silent), or a **resolver error** (`disjoint: false`): the author asserted a property the declarations lack, in the manner of `N > M` in a size range (§5.3). The derivation is total, so there is no unprovable state and no warning tier. A choice whose variants are separated only by value sets — bounded numerics, disjoint patterns, disjoint enum member sets — derives `false` and rejects the assertion; nothing operational is lost, since no encoding could drop those tags, and the labelled form (below) is the recommended model for such alternatives. A resolver never diagnoses a choice merely for being non-disjoint — overlap is often intended, and the tag serves it.

**Resolution.** `(A | B | ...)` desugars to `!choice { variants: [A B ...] }` — a SUM-kind `type_definition` when declared, and a *synthetic entry* when inline (§5.3), referenced by name from the use site, so the entry carries the `disjoint` fact wherever the choice appears. Each variant is a type reference (a `type_ref` value, §8.1) and may itself be a sugar form, which lifts to its own entry (§5.3). The resolver validates that each variant resolves to a distinct type.

A choice discriminates by variant *type name*; for labelled disjunction — mutually exclusive alternatives distinguished by field label, including alternatives of the same underlying type — see field groups (§5.11). The labelled form is the recommended resolution wherever the tag would otherwise be mandatory: a choice whose variants share a base-type class, or whose disjointness is unprovable, is often better written as a single-group record (§5.11), which discriminates by label and needs no derived disjointness.


### 5.5 Constructor Application and Atom Refinement

Within the type-definition grammar, the `!` prefix always takes an entry that IS-A `top` — a **constructor** (§4.2); the invariant the data format teaches therefore holds in every grammar of the series: `!T x` describes a value shaped by `T` — in schema source, in data documents, and in resolver output alike. Two forms follow the prefix, distinguished by the `^` operator; the name after `!` resolves per §3.3.1.

**Constructor application — `!C value`.** Produces a constructor instance filled with specific values. The core value after `!C` (§12.1) is a record of bindings interpreted against the constructor's record shape — the field list `C` declared as its narrowable vocabulary — or the positional form of §5.6. This form does NOT establish IS-A: construction transfers only the constructor's `kind`; the result records `source: C` with empty `supertypes`. Applying an entry that is not IS-A `top` after a bare `!` is a resolver error (§3.3.1). The kernel's `reference` is applicable like any other: `!reference { target: X }` denotes the alias `X` — `kind: REFERENCE`, with `X` as source and body — and not a value of the head's own kind, open (`<B> !reference { … }`, §5.10) and closed alike.

```
integer => !integer_type {}
boolean => !enum [true false]
bytes   => !bytes_type { encoding: BASE64 }
```

**Kind determination.** A constructor's kind is settled at definition time by the **base kind** — `atom`, `product`, `sum`, or `data`, excluding `top` (§4.1) — reachable through its transitive supertypes chain. Zero base kinds in the chain → `kind: PRODUCT` by structural default; exactly one → that kind; two or more → resolver error, since the kinds are categorically distinct. `!C {}` simply inherits `C`'s settled kind — an instance of a `data`-composed constructor resolves to `kind: DATA` and is not a type (§4.1).

**Bodies are closed.** A construction or refinement body is a record whose type is known — the constructor's own constraint-field vocabulary — and is validated as an ordinary closed record (§7.2): each member binds against a declared field, and **a member the vocabulary does not declare is a resolver error** at the declaration, naming the member and the constructor's real fields. This is stated here, where bodies are written, because the silent alternative is the dangerous one: an implementation that binds field-by-field and ignores unmatched members reports success while discarding the constraint the author wrote (`!integer ^ { minimum: 1  maximum: 100 }` — JSON Schema's spellings — would compile clean and constrain nothing).

**Atom refinement — `!I ^ { values }`.** Refines an atom-family instance by tightening values on its constructor's constraint fields. `I` MUST resolve to an atom-kinded entry that is not itself applicable — an instance, not a constructor (§3.3.1): an atom constructor is ATOM-kinded exactly like its instances, so kind alone does not separate them, and "IS-A `atom`" says the opposite of what a reader expects (true of the constructor, false of every instance, IS-A stopping at construction) — and the body is the braced subset of the payload a constructor application takes — a data-grammar `record` or `empty-brace` (§12.1) read against the constructor's vocabulary: a bare value, a second type-ref, an annotation, or a map in body position is a parse error, and the positional form of §5.6 does not apply. The body is data, not a record of field declarations: `size: { bits: 8  signed: true }` binds a nested value, and `min: 1` binds a number, exactly as they would after `!integer_type`. This form DOES establish IS-A: the new type records `source:` `I`'s constructor, `supertypes: [I]`, and a body in the constructor's canonical form (§5.6). A refinement head admits no removal clause (§5.9).

```
age              => !integer ^ { min: 0  max: 150 }
non_empty_text   => !text ^ { min_length: 1 }
positive_integer => !integer ^ { min: 1 }
```

`age` has `source: integer_type`, `supertypes: [integer]`, and can be refined further. Founding and refining are distinguished at the head, by the operator: `!integer_type {}` applies the constructor (fresh family, no IS-A); `!integer ^ { min: 0 }` refines the instance (IS-A `integer`).

**Construction creates siblings, not subtypes.** One constructor may found any number of nominally distinct families: `dogs => !integer_type {}` is a fresh atom family with the same body as `integer` and no relation to it, and `small_dog_count => !dogs ^ { min: 0  max: 5 }` refines `dogs`, not `integer`. Identity is nominal for a declaration (§8.2): `stock_id => !uuid_type {}` and `other_id => !uuid_type {}` have byte-identical resolved bodies and are two types, and `!other_id` is refused at a `stock_id` position, as is `!uuid`. The only IS-A the `!` forms ever create is the refinement's single hop to its instance — recorded in `type_definition.supertypes` and deliberately nowhere in the body: the canonical form (§5.6) erases the surface distinction, so `supertypes` is the sole carrier of the atom family's direct IS-A fact (§8.1).

**Single-required-field positional form.** When a constructor has exactly one REQUIRED field, the core value after `!C` fills that field directly; see §5.6. The positional form applies to constructor application only — a refinement body is always a braced record.

**Facets with a stated meaning.** Each constraint family's facets are defined by the constructor's documentation in `meta-kernel.tn` and `meta.tn` (§9), with the tightening rules of §5.7 applying per facet kind. What every family's facets rest on, and what the artifacts state per family, is one clause:

> **A type denotes a value space. An encoding defines a lexical space over it and one canonical form per value. Equality, ordering, refinement, disjointness and content addressing are defined over value spaces only, never over spellings.**

Every rule in the series that compares two decoded values — the set duplicate rule (§7.5), map-key identity ([TSON-DATA] §2.6), the check of a written value against a FIXED one (§5.2), the identity of a value argument (§8.2), and the digest a pinned reference verifies (§10.2) — delegates to the element type's equality, and this clause is what that delegation reaches. For the numeric families [TSON-DATA] §4.3 already states it (`255` and `0xFF` are one value); for every family whose value space differs from its lexical space the companion artifacts state it in the family's own `@doc`, and the consequences that decide interoperation are these:

- **`bytes`** is an octet sequence. The RFC 4648 alphabet a text encoding spells it in is a `bytes_type` *selector* (`encoding`, defaulting to `BASE64`), which picks a spelling and can never change what two values compare as: case in hex and padding in base64 are lexical, `"abcd"` and `"ABCD"` under `HEX` are one value, and the length facets count octets — `length: 32` is a 32-byte digest whether it arrives as 64 hex characters, 44 base64 characters, or 32 raw bytes. An encoding whose values are octets ignores the selector and writes them raw, and a round trip through it is value-preserving: the alphabet is re-spelled from the schema, not recovered from the bytes. The selector is a facet rather than an annotation because a container element has no annotation position — `[hexdigest]` works only if the element's own type carries the alphabet — and it is **not refinable**: an alphabet narrows nothing, since every octet string is writable in every one of them, so `hexbytes => !bytes ^ { encoding: HEX }` would claim an IS-A carrying no narrowing at positions no base64 reader could honour, and is a resolver error (§5.7). Another alphabet is another **instance**, `hexdigest => !bytes_type { encoding: HEX  length: 4 }`; refining for length inherits the alphabet, `sha256 => !bytes ^ { length: 32 }` being a base64 sha256. `bytes_type` carries no `spec`: RFC 4648 governs spellings, not octets. Core declares one `bytes` and no spelled siblings, and [TSON-DATA] §5.3's `!bytes` is base64, so the name denotes one type in both conformance classes.
- **`number`** and the exact numeric tiers are exact values with no scale: `1`, `1.0` and `1.00` are one value, and whether a spelling's trailing zeros survive a round trip is an encoding's promise, not the type's. `rational` is the fraction — `2/4` and `1/2` are one value.
- **`time` and `datetime` are instants.** RFC 3339 makes the offset mandatory, and the offset is a *spelling*: a `datetime` is the instant on the UTC timeline and a `time` is the time of day in UTC on `[00:00:00, 24:00:00)`, so `2026-01-01T10:00:00+01:00` and `2026-01-01T09:00:00Z` are one value, `-00:00` (offset unknown, RFC 3339 §4.3) is the same instant as `Z`, and `23:30:00-02:00` is `01:30:00Z`. Both families are totally ordered — the mandatory offset is what makes them so; XSD's partial order is the cost of an optional one. A wall clock with no offset, and an appointment for which the zone is data, are other value spaces and not these types. TSON text preserves the offset as written; an encoding that stores the instant may drop it.
- **`duration` is a signed exact decimal number of seconds, and `period` a signed integer number of months.** The split is what gives each a total order: a month has no fixed length beside a second that has one, so the two are two value spaces rather than one partially ordered one, and every ordered-bound facet on them is enforceable (§5.7). `duration`'s lexical form is RFC 3339 Appendix A's `duration` production with an optional leading `-`, a fraction on the seconds component only, any subset of `D`/`H`/`M`/`S` in order with at least one present, and no `Y` or month-`M`; `PnW` stands alone, a week is 7 days and a day 86400 s, so `PT90M`, `PT1H30M` and `P0DT5400S` are one value and `PT0S`, `P0D` and `-PT0S` are one value. `period`'s is `P` with a `Y` component, an `M` component, or both, and nothing else. Both ends of `duration`'s space are fixed at a signed 64-bit count of nanoseconds: **`time-secfrac` is `"." 1*9DIGIT`** — stated once, for `duration`, `time` and `datetime` alike, since no host runtime represents finer than a nanosecond and a value below that floor is not exactness anyone can use — and **a `duration`'s magnitude does not exceed 2⁶³ − 1 ns** (about 292 years), as a magnitude so that negating an admitted value yields an admitted one; a processor MUST represent every value in that range and MUST reject one outside it whether or not its own representation could hold it. A span of centuries is a calendar span and is a `period`; a quantity finer or wider than the range is a `number` in the unit the schema names. The floor is lexical (a fraction reaches the value through one component and is capped at the token) and the ceiling is a value rule (the seconds count is summed from `D`, `H`, `M` and `S`, so `P400000D` overflows with no single component long enough to catch).
- **`precision`** on `time_type`, `datetime_type` and `duration_type` is a constraint on the *value*, not on a spelling: `precision: N` admits a value that is a whole number of 10⁻ᴺ seconds — `fraction_digits` on the seconds count — so `precision: 3` is millisecond resolution, `precision: 0` whole seconds, and `precision: 9` the nanosecond grid; a text encoding may spell an admitted value with trailing zeros (`12:00:00.500` under `precision: 1`) and writes at most N digits. The atom is exact — nothing is truncated; a value off the grid is rejected. `precision` may not exceed 9, which falls out of the fraction production rather than being stated twice; a larger value is a schema-load coherence error. The temporal families carry no timezone facet: RFC 3339 `full-time` and `date-time`, which the atoms' `spec` pins, already make the offset mandatory, so a facet requiring it would be vacuous and one relaxing it would widen the atom against its own pin. All five temporal families state their bounds as §5.11 groups, `( min | exclusive_min )?` and `( max | exclusive_max )?`, so a half-open interval is spellable on a date exactly as on an integer.
- **The network families' `within`/`excluding` pair MUST admit a value**, and the rule is exact. `ipv4_type`, `ipv6_type`, `cidr4_type` and `cidr6_type` each carry `within` (a value must lie inside some listed network; a network must be a subnet of one) and `excluding` (an address must lie inside none; a network must not *overlap* any — overlap, not containment, so a wider value cannot smuggle an excluded block). A body whose two lists between them admit nothing is invalid at the schema level, in the voice the family already uses for a prefix outside its range: `{ within: ["10.0.0.0/8"]  excluding: ["10.0.0.0/8"] }` is `{ min: 10  max: 3 }` in another spelling, and one an author reaches by editing. The check is decidable exactly — CIDR blocks are nested or disjoint and never partly overlap, so an exclusion meeting a permitted block either contains it or lies wholly inside one of its halves, and the walk terminates at the address width — and a resolver MUST implement it exactly rather than pairwise, since `10.0.0.0/9` and `10.128.0.0/9` cover `10.0.0.0/8` by counting and only the tiling case arises from a real edit. **For a network family the prefix bounds participate**: a value must be a subnet of a `within` block, so its prefix is at least that block's, and `max_prefix` caps it from above; `{ within: ["10.0.0.0/24"]  excluding: ["10.0.0.5/32"]  max_prefix: 24 }` admits almost every *address* and no *network* at all, since the one block the prefix bounds leave overlaps the hole. The diagnostic SHOULD say which cause it found — an exclusion covering everything permitted, or the largest block the bounds leave — since the two want different edits.


### 5.6 Canonical Form and Desugaring

All type-definition bodies ultimately take a single canonical form:

```
!C { bindings }
```

where `C` names a constructor and `bindings` is a record literal filling the constructor's fields. Every other form — inline type expressions, positional constructor forms, atom refinements — is syntactic sugar that desugars to this form during resolution; resolver output always records the fully expanded canonical form in the `body` field.

**Positional form.** When a constructor has exactly one field in state `REQUIRED` (no default, no fixed value), the core value after `!C` may be that field's value directly:

```
!enum [true false]    →  !enum { members: [true false] }
!array text           →  !array { element_type: text }
!choice [a b]         →  !choice { variants: [a b] }
```

REQUIRED_DEFAULT, REQUIRED_FIXED, and OPTIONAL fields do not count toward the single-REQUIRED rule. The positional form is invalid when the type has zero, two, or more REQUIRED fields — the resolver MUST reject such uses with a clear error. This restriction applies to the positional form only.

**The positional form is general over schema-backed data.** At any data position whose declared type is a record with exactly one field in state REQUIRED, a non-brace-delimited value fills that field directly; a braced value is always the explicit record (so record- and map-valued fills cannot go positional — the same exclusion as above). The two spellings denote the same value; which one canonical output uses is a per-type convention (§8.1 for `type_ref`, whose bare-token spelling is this rule). Type-definition bodies canonicalise the other way — toward the explicit bindings record, per the end state below — a deliberate pair of conventions: bodies uniform, references minimal. The `name<args>` **application sugar is schema syntax only**: at a `type_ref`-typed slot in *data* — an `!operation { … }` governed by a meta layer, say — `page<order>` is a parse error, `<` never being data syntax, and an application with arguments is written in `type_ref`'s explicit record form, `{ name: page  arguments: [ { name: order } ] }` (§8.1). The spelling that gives such an application a name is the alias, `order_page => page<order>` (§8.2).

**Record-bindings form.** `!C { ... }` is the explicit form, valid for any constructor as long as the bindings cover all REQUIRED fields not pinned by FIXED or covered by DEFAULT. Empty bindings `!C {}` are valid whenever the constructor has no unfilled REQUIRED fields.

**Atom refinement.** `!I ^ { values }` desugars by retargeting to the instance's source constructor, **with `values` merged over `I`'s own already-bound field values**: a field named in `values` overrides `I`'s value for it, and every field `I` itself bound that `values` does not mention keeps `I`'s value — the atom counterpart of §5.7's body-materialisation rule, under which inherited constraints survive a refinement that does not mention them:

```
!integer ^ { min: 0  max: 150 }   →  !integer_type { min: 0  max: 150 }
!text ^ { min_length: 1 }         →  !text_type { min_length: 1 }
```

For a fresh instance (`integer => !integer_type {}`) the merge is a no-op — every inherited field is absent — so the single-hop desugar above is the general rule's degenerate case. Chained refinement is where the merge matters:

```
int8      => !integer ^ { size: { bits: 8  signed: true } }
bigNumber => !int8 ^ { min: -500  max: 5000 }
          →  !integer_type { size: { bits: 8  signed: true }  min: -500  max: 5000 }
```

`bigNumber` retargets to `integer_type` (an instance's `source` is always the base constructor) and keeps `int8`'s `size` binding, which nothing in its own body mentions — replacement rather than merge would silently discard the width and yield an unconstrained-width integer with bounds. Recognition is syntactic — the `^` declares the intent — and the resolver verifies it: the target MUST resolve to a non-constructor atom-family instance, and the retarget follows the instance's `source`. The result records `source: I.source` and `supertypes: [I]`.

**End state.** After desugaring, every *closed* non-reference type-def body in resolver output is a binding record `!C { bindings }`, where `C` is the constructor the form applies — with the container constructors parameterless (§4.2), the nearest constructor in the source chain for every container closure is the container constructor itself: `!array` for every array form, `!map` for the map sugar, `!set_type` for an application of `set<T>` — and `bindings` supplies values for the vocabulary's REQUIRED fields not pinned by FIXED or covered by DEFAULT, plus the fields the application or refinement bound. Pins, defaults, and routes divide by where their values live: a pin or default whose value is *concrete in the head's own declaration* (`set`'s `unordered: = true`) comes from the vocabulary and does not appear in the binding record, while a value routed by a user-template parameter (§5.10) is application-supplied information and does appear — so a validator reads any closed body with a one-hop lookup of the head, never substitution. A type slot is an ordinary REQUIRED `type_ref`-typed field (§4.2), and the bound reference appears in the binding record as a `type_ref` value under the positional form (§8.1) — a bare token for a simple reference (which, after lifting, is what every closed form holds; §5.3). Binding records are closed-world: they never contain parameter references. A definition whose parameters remain open is not a binding record at all but a **held** constructor application — the same `!C { … }` text, unread against `C`'s vocabulary until materialisation substitutes the parameters away (§5.10, §8.2) — so constructors carry `!record` vocabulary bodies, open templates carry held applications, and every other non-reference entry carries a binding record. The surface abbreviations exist only in source text — except the one that names a reference: a use site names whatever entry the author named, an alias included, and output states the chain as written (§8.3).

**Top-level constructor applications are constructions.** A declaration whose body applies a constructor — the explicit form (`lookup => !map { key_type: text  value_type: integer }`) or its sugar spelling (`lookup => {text => integer}`) — resolves as a construction: `kind` from the constructor's family, the constructor recorded in `source` (§8.1), the binding record as body, and no supertypes (construction transfers kind, not IS-A). Two such declarations are nominally distinct entries with structurally equal bodies. A declaration whose body is a fully-bound application of a non-constructor *template* resolves instead as a reference to the materialised instantiation (§5.10, §8.3).

**Named definitions required.** The refinement form (`!I ^ { values }`) and constructor application (`!C value`) are valid only as the top-level body of a declaration — the inline prohibition of §5.2. A constrained atom must be introduced with its own declaration and referenced by name.


### 5.7 Refinement

Refinement copies an existing definition and tightens it — binding values, fixing defaults, restricting ranges — producing a new definition with its own identity that IS-A the source. It never changes the field set: no field is added and none removed. It is expressed with the `^` operator between a source type name and a record body:

```
config => { host: text  port: integer ~ 8080  debug: boolean }
production => config ^ { host: = "prod.example.com"  port: = 9090 }
```

The operator carries the operation: `^` always means *refine, preserving IS-A*, at every rung of the ladder — record and map types (`config ^ { ... }`), constructors (`array ^ { ... }` in a meta-schema, §4.2), and atom instances (`!integer ^ { ... }`, §5.5). A source type name followed directly by a braced body, with no operator, is a parse error; the diagnostic SHOULD suggest `^` (refinement) or `&` (composition). A refinement head admits no removal clause: `T ^ { ... } - { ... }` is a parse error — an operator that promises IS-A cannot host the operation that revokes it (§5.9).

In a refinement, only existing fields may be modified: fields in the body MUST exist in the source definition, and adding fields is a resolver error. The guiding rule is that refinement can only restrict, never expand — FIXED states are terminal, and loosening a required field to optional is a resolver error. The source name resolves in the type-name namespace; the same syntax refines local and imported types.

The refinement state transition table:

```
From \ To          | REQUIRED | OPTIONAL | REQ_DEFAULT | REQ_FIXED | OPT_FIXED |
-------------------|----------|----------|-------------|-----------|-----------|
REQUIRED           | allowed  | error    | allowed     | allowed   | error     |
OPTIONAL           | allowed  | allowed  | allowed     | allowed   | allowed   |
REQUIRED_DEFAULT   | error    | error    | allowed     | allowed   | error     |
REQUIRED_FIXED     | error    | error    | error       | allowed   | error     |
OPTIONAL_FIXED     | error    | error    | error       | error     | allowed   |
```

**Identity diagonal.** Each state may be restated as itself. For value-carrying states, identity restatement is governed by the value's own mutability: a REQUIRED_DEFAULT restatement may change the default (defaults are overridable, §5.2); REQUIRED_FIXED and OPTIONAL_FIXED restatements MUST NOT change the value — the identity cells exist so a body may restate a fixed field without error, not so fixed values can be replaced.

**Value tightening is per facet kind.** "Refinement can only restrict" is checkable per constraint field, and each field's rule follows its facet's kind, declared once here for every family: an **ordered bound** (`min`, `max`, `min_length`, `max_items`, exclusive bounds, and kin) may move only inward — a lower bound may rise, an upper bound may fall, never the reverse — and requires its family's value space to be **totally ordered**, which every family carrying one now is (§5.5; the temporal split is what made `duration`'s so); a **step** (`multiple_of`, wherever it appears) is strictly positive, tests the value's magnitude with the sign ignored, and may tighten only to an integer multiple of the inherited step (`15` under `5` tightens; `10` under `15` is an error); a **permission** facet (a boolean granting latitude, such as a normalisation or case-folding allowance) may go from granted to withdrawn, never the reverse; a **member set** (an enum's `members`, the `members` of the exact numeric tiers, a pattern alternation authored as a set) may shrink to a subset, never grow or replace, compared by the family's own value identity (§5.5, §7.4); a **selector** facet — a discriminant among members of a closed vocabulary (`integer_type.size`, `complex_type.component`, `bytes_type.encoding`, `float_type.format`) — may move under refinement **only along the narrowing relation its members carry**, which each family states: a width chain for `size` (`int8` is inside `int16`, so `!int8 ^ { size: { bits: 16 } }` widens and fails, whatever the arithmetic direction); a partial order for `component` (`INTEGER ⊂ NUMBER ⊂ RATIONAL` and `FLOAT32 ⊂ FLOAT64`, the exact and approximate families incomparable since binary64 carries ±inf and NaN that no exact decimal represents, so `!complex ^ { component: INTEGER }` narrows and `!complex ^ { component: FLOAT64 }` is refused, a float complex being its own instance); and **no relation at all** for `encoding`, which a refinement may neither set nor change — an alphabet is a spelling of one value space, so a change would produce an IS-A carrying no narrowing, and another alphabet is another instance of the constructor (§5.5). A selector at the constructor's default is not thereby free: the relation is over effective values, so an unwritten default and a written one refine identically, and there is no "set-from-default" permission apart from the relation. A **fixed** value follows §5.2's FIXED rules. A refinement value violating its facet's rule is a resolver error at schema load, in both record refinement and atom refinement (§5.5, §5.6). Constructor refinement in a meta-schema is the meta-level exception and MAY replace fixed values (§4.2).

**Three ways to name a type after another, and what each buys.** An author writing `user_id => uuid` and expecting a `stock_id` to be refused at a `user_id` position gets no protection, and nothing else tells them the other two forms exist:

| Spelling | Relation to `uuid` | `!uuid` at its position | a sibling's tag |
|---|---|---|---|
| `user_id => uuid` — **reference** (§8.3) | the same type, under another name | accepted | accepted |
| `user_id => !uuid ^ {}` — **refinement** | IS-A `uuid`; usable at a `uuid` position | refused | refused |
| `user_id => !uuid_type {}` — **fresh instance** (§5.5) | unrelated in either direction | refused | refused |

Only the first is a rename, and it is what identity dereferences (§8.2): `box<user_id>` and `box<uuid>` are one application. The **empty refinement is legal** — `!uuid ^ {}` narrows nothing and is the language's one nominal-subtype spelling, which is why "a refinement tightens" is a description of what a refinement may do and not a MUST that a body change something.

**Open modifiers.** In a template body (§5.10) the value of a `=` or `~` modifier may be a parameter: `bounded => <N> { attempts: integer = N }` routes, and `retry_policy => <N> { attempts: integer ~ N }` defaults. The parametric spellings do not take the concrete-value transitions, because nothing is fixed at declaration — the value does not exist yet; it arrives at application, and every application MUST bind every parameter (§5.10), so it is always supplied. Accordingly, a parametric `~ P` places the field in REQUIRED_DEFAULT, and a parametric `= P` places the field in REQUIRED — from OPTIONAL this is the table's ordinary OPTIONAL → REQUIRED tightening. In both cases the parameter rides the ordinary `value` slot of the held `record_field` (§8.1) — no label distinguishes it from a literal, because a held body is not read against the vocabulary until its parameters are gone, and a token there is a parameter exactly when its text resolves into the enclosing entry's `parameters` (the shadowing rule, §5.10). **Fixation happens at materialisation, where values are concrete**: a field whose `value` was a parameter and is now bound to a concrete argument takes the state its literal spelling would have — `= P` becomes REQUIRED_FIXED with the argument as its value, `~ P` remains REQUIRED_DEFAULT with the argument as its default — so the materialised entry enforces exactly what the literal form one line down would (a closed `status: int32 = S` bound to `201` is `REQUIRED_FIXED`, `value: 201`, and a document writing `status: 999` is a validation error), and the bound value is checked against the field's type by the value-conformance rule (§5.2). In a *record* template body the modifier spellings are the only way to bind a field's value to a parameter — `min_items: S` in a record body is a field declaration whose type is `S`, not a value binding; in a held *constructor* body (`<S> !array { element_type: text  min_items: S }`) the slot is a value slot of `array`'s own vocabulary and the parameter stands in it directly (§5.10).

**Elided type-refs.** In a refinement or supertype-composition body, the type-ref in a field definition MAY be elided: when only a modifier is present (`field: = value` or `field: ~ value`), the field's type is inherited from the source declaration and only the value state changes. A modifier-only entry is always a tightening — it names no type, so it cannot declare a new field — and a modifier-only entry whose name matches no inherited field is a resolver error. Restating the type-ref remains necessary when the tightening also narrows the field's type. In a fresh record definition there is no inherited declaration to elide toward: every field MUST have an explicit type-ref, and the resolver MUST reject modifier-only entries there.

**Refinement requires a vocabulary body** (§4.3 states the rule for both operator families). The source of `^` — after following its reference chain (§8.3) — MUST be a definition whose body is a `!record`: a fresh or refined record, a composition, a constructor (in a meta-schema, §4.2), or an open record template. A definition whose body is a binding record — a top-level constructor application (§5.6), a template instantiation (§8.2), or an alias resolving to either — is **finished**: its bindings are set, and `^` on it is a resolver error; a choice is likewise inadmissible (§4.3). There is no refinement of an application head: `{text => text} ^ { min_items: 1 }` has no head to refine, and the use case is the size specifier on the sugar itself (`{text => text; 1..}`, §5.3). Record refinement chains remain open because record refinement re-emits a `!record` body; a declared construction (`lookup => {text => integer}`) is terminal — authors wanting a narrower relative write the bounds on the form (`strict_lookup => {text => integer; 1..}`). The `refined-def` grammar's optional `<type-args>` head serves user-template heads only (§5.10). Atom refinement (`!I ^`, §5.5) is a distinct form with its own rule and is unaffected.

A refined record definition remains a definition: it can be refined further or instantiated. A refinement that takes an OPTIONAL field to `= _` (fixed to absent) effectively forbids the field's value in the refined type while keeping the field in the contract — the IS-A-preserving counterpart of removal (§5.9). Individual map entries cannot be refined because map keys are data, not definition fields.

**Body materialisation.** The refined body re-emits the complete inherited field set in source order; each field carries either its inherited state and value or the tightened ones. The materialised body is self-describing — consumers of resolver output do not walk the supertype chain to learn the field set. Inherited REQUIRED_FIXED and REQUIRED_DEFAULT fields appear with their pinned values even when the refinement did not refer to them.

**Resolution.** The refined entry's `source` records the refinement origin, `supertypes` records the IS-A chain through it (§8.1), and tightened fields appear in the materialised body.


### 5.8 Supertype Composition

Supertype composition (`&`) is a construction tool: it combines one or more parent types with new fields into a new definition, declaring an IS-A relationship with each listed supertype. New fields are permitted; existing fields may be tightened.

```
address => { street: text  city: text  postcode: text }
contact => { name: text  email: text }
customer => address & contact & { loyalty_tier: text }
```

**Supertype field conflicts.** The supertypes MUST contribute disjoint field sets — a field name appearing in more than one supertype path is a resolver error, including diamond cases where the field traces to the same originating type through both paths.

**The trailing body.** The trailing `& { ... }` body is optional (`customer => address & contact` is valid). Body fields that match an inherited field are tightening entries and follow the refinement rules of §5.7 (including elided type-refs); body fields that match no inherited field are new fields.

**Field ordering.** Supertypes contribute fields in left-to-right order as listed; each supertype's fields appear in their declared order. Tightening entries replace inherited fields in place; new fields are appended after all inherited fields.

**Constructorness is inherited through composition.** Composition propagates the supertype chain, so an entry composing with something IS-A `top` is IS-A `top` and is therefore a constructor (§4.2): `uri_type => text_type & atom_specification & { ... }` is a constructor because `text_type` is. A record mixin such as `atom_specification` lends vocabulary without conferring the level, and a composition reaching no base kind — `box => <T> base & { value: T }` over a plain record `base` — is an ordinary template, not a constructor, whatever its author intended. Placement (§2.2.2) is what keeps the level inside the meta layer.

```
atom_specification => { spec: uri }
uri_type => text_type & atom_specification & {
  spec:    = "https://www.rfc-editor.org/rfc/rfc3986"
  scheme:  text?
}
```

`uri_type` is a constructor, IS-A `text_type` and `atom_specification` directly. Its fields, in order: `text_type`'s four constraint fields, `atom_specification`'s `spec` — tightened in place to `REQUIRED_FIXED` via an elided-type modifier — and the new `scheme` field. An alias to an *instantiation* (`place_order => method<order, order>`) has no vocabulary body to compose with (§4.3); a composition wanting one writes a trailing body, `method<order, order> & { … }`.

**A restated field's annotations are the restatement's own followed by the inherited ones.** §6 puts an annotation on a `record_field` and §8.1 preserves it; composition copies inherited fields whole and §5.7 lets a body entry restate one, and the two paths must agree. The rule: a restated field — in a composition body or a refinement body, including §5.7's modifier-only entry — carries **the restatement's own annotations, in source order, followed by the inherited field's, in source order**. Nothing is dropped and no name is treated as a key, because [TSON-DATA] §3.1 already makes an annotation name repeatable on one value and annotations are a list; and the restatement leads because order is already the precedence mechanism — every first-occurrence lookup reads it that way, so the nearer declaration wins at every such site without any of them knowing about composition. A restatement adds and never removes: a subtype rewriting an inherited `@doc` leaves the field carrying both, which is what "annotations are not removable" buys. Per-name replacement would need a cardinality on the declared annotation, which v1 does not have. The rule is what makes a count along a composed chain — "at most one `@rest` per chain" (§6) — checkable, and it has read-side force wherever an annotation directs reading: a field that tightens presence and writes no annotation of its own inherits its source's, rather than silently losing it.

**Parameterized references.** Both `&` composition and `^` refinement operate on type-refs, which may carry arguments. A refinement of a parameterized type must re-declare its open parameters in its own `<>` slot (§5.10); composing with a parameterized supertype works the same way: `vip => <T> customer & box<T> & { ... }`. The `supertypes` lists record the head names only (`[customer box]`) — they are name-level IS-A indexes (§8.1) — while the applied form, arguments included, lives in the entry's `source` and in the absorbed fields, which carry the parameters through ordinary type channels. Parameterized substitutability is therefore a two-part check: the name-level edge via `supertypes`, and binding agreement via the bodies.

**Resolution.** The composed entry's `supertypes` records the listed parents and, transitively, their own chains (§8.1); the body's `record.supertypes` records the direct compositions as written, and inherited fields are copied into the body's `fields` list in the order above.


### 5.9 Subtraction

Subtraction removes fields from a construction and deliberately breaks IS-A — the resulting type is no longer source-compatible. Taxonomically it is a construction operation, not a refinement: like composition, it computes a new field set; unlike composition, it disclaims the contract. It is expressed as a trailing **removal clause** on a construction head:

```
removal-set = "-" ws "{" ws field-name *( separator field-name ) ws "}"
```

```
account => { name: text  email: text  password: text }
account_public => account - { password }
account_view   => account & { email: text ~ "n/a" } - { password }
staff_public   => account & user & { badge: text } - { password  ssn }
```

The clause is head-level: a reader of the declaration line knows the contract is broken without scanning the body. It attaches only to construction heads — a bare source, or an `&` chain with or without a trailing body; a refinement head admits no removal clause (§5.7, §12.1).

Rules:

1. **Resolution order.** Supertypes merge first — §5.8's disjointness rule fires here unchanged, so subtraction cannot be used to resolve diamond conflicts — the body applies second, and removals apply last.
2. **Removing a nonexistent field is a resolver error** — symmetric with refinement's existing-fields-only rule.
3. **Source path does not restrict removal.** Removal operates on the merged field set, not on field provenance; since IS-A is already broken, there is no contract to violate.
4. **The body and the removal set are disjoint.** A removal naming a field the body itself introduces is a resolver error — adding and removing a field in one declaration is incoherent — and a body entry tightening a removed field is a resolver error. Body entries tightening the remaining inherited fields follow §5.7's rules.
5. **Removal and fix-to-absent are distinct.** `- { field }` removes the field from the contract and breaks IS-A; `field: type? = _` (§5.2) keeps the field in the contract, forbids its value, and preserves IS-A.
6. **Empty subtraction does not exist**, by grammar: the removal set requires at least one name, and `source & {}` is composition-with-no-additions and preserves IS-A.
7. **Groups.** A removal may name a field-group member; the member leaves the group's `members` list, and a group reduced to one member is dissolved per §5.11.
8. **Constructors and parameters.** A subtracted construction in a meta-schema whose head is a constructor is itself one — a constructor with lineage and no contract — and a parameterised subtraction declares a parameterised type whose field set is the merged set minus the removals.

**Resolution.** The two `supertypes` fields in resolver output capture the contract/lineage distinction: `type_definition.supertypes` (the IS-A lattice) is empty — the subtracted type is not source-compatible; `record.supertypes` in the body (authorial lineage) is preserved as the head's source list (§8.1). A removed field's annotations are lost with the field. For ingest (§8.1), broken-IS-A-with-lineage is declared by the source syntax rather than inferred by diffing field sets against the parents.


### 5.10 Templates and Parameters

A type definition may declare parameters using `<>` immediately after `=>`. Parameters are local names referenced in the body and bound to concrete arguments when the definition is referenced with `<args>`. A definition with parameters is a **template** — it cannot be instantiated directly, and references to it MUST supply arguments for all its parameters:

```
container => <T> { items: [T] }
pair      => <T, U> { first: T  second: U }
vector    => <T, N> !array { element_type: T  min_items: N  max_items: N }
matrix    => <T, M, N> [[T; N]; M]
```

`container<text>`, `vector<pixel, 1920>`, and `matrix<pixel, 1080, 1920>` are concrete types; bare `container` or `vector` references are resolver errors. A template application's head resolves through the type-name namespace only (§3.3.1); parameters and templates are an authoring-side feature exclusively — resolved output governing data carries none of it (§1.3, §8.2).

**Template forms.** A template declaration's body may be a record (`container`), a container or sugar form (`matrix`), a reference (a pure application body, below), or — the fourth form — an **instance**: a constructor application whose bindings mention parameters (`vector` above). The instance form is the route to a constructor with no sugar of its own (`bounded_set => <N> !set { element_type: text  min_items: N }`) and the target the sugar desugars into; for the sugared constructors the compact spelling exists alongside it (`vector` could equally be written `<T, N> [T; N]`). Every one of these forms resolves to the same thing — a **held** constructor application, `<params> !C core-value` — which is what lets one substitution rule serve all of them (below).

**Held bodies.** An open entry's body is the constructor application as written, held and *unread* until materialisation substitutes its parameters away. It is not a typed quotation of the constructor's vocabulary, and it is not read against that vocabulary while a parameter remains: a record template `<T> { x: T }` is normalised to `<T> !record { fields: [ { name: x  type: T } ] }` (the form §5.2 already says a bare record denotes) and held; a sugar form is desugared per §5.3's table and held; an explicit `<T, N> !array { … }` is held as written; and a composition or refinement template is resolved against its namespace first — a tightening entry states a modifier and no type-ref, so it is not a `record_field` until the inherited field supplies one — and the *flattened* record is held. Because a held body is not classified by slot kind, a parameter needs no channel of its own: a parameter in a value slot (`min_items: N`), in a type slot (`element_type: T`), inside a collection (`variants: [T error]`, `members: [a b M]`), or at any depth is a token like any other, and **collection-valued slots are parameterizable** — `result => <T> ( T | error )`, the sum-typed result envelope, resolves and closes to an ordinary `choice` body. The cost is shadowing's usual one: inside a template, a literal spelled like a live parameter is unreachable, so an author renames the parameter.

**Substitution is one rule at any depth.** Binding a template's parameters rewrites the held body and the recorded `source` application by replacing every token whose text resolves into the entry's `parameters` list with the corresponding argument — a reference argument as a `type_ref` (a bare name, or the record form when the argument is itself an application), a literal argument as written — and nothing else is classified. An argument is substituted as a token and **read by the position it lands in**: at a type position it must resolve as a type, at a value position it must conform to the field's declared type (§5.2), and in an enum's member list it is a member — `e => <M> !enum [a b M]` applied as `e<c>` admits `c` without any special spelling. When every parameter of an entry closes, the held body is read, once, against the constructor's own vocabulary — materialisation binds against vocabulary exactly once, at the only moment binding is decidable — and the result is an ordinary closed body (§8.2).

**One spelling.** A held body is read by later phases as wire form, and an entry's identity and content-derived name are computed over what is written, so the open form MUST have **one spelling** however many phases produce it: no-argument `type_ref`s in the positional (bare-token) form, `type_argument` and `type_ref` records only where an application is open, and parameter tokens unquoted — a producer that quotes every token has written a body that references no parameters at all. Two of the four template shapes cannot be normalised syntactically (composition and refinement need a namespace to flatten against), so "one phase" is not achievable and "one spelling" is the requirement.

**Two parameter kinds, inferred by use.** A parameter is a **type parameter** or a **value parameter**, and kinds are not annotated: a parameter used in type-reference positions is a type parameter; one used in value positions — routed or defaulted into a field, or standing in a scalar slot of a held constructor body — is a value parameter. A parameter used in both kinds of position is a resolver error at the declaration. **A parameter with no kind-determining use is a type parameter**: a value parameter is one that stands in a scalar slot, so a parameter with no concrete use anywhere in its cycle cannot be one, and TYPE is the only assignment consistent with every occurrence — a reference template's body *is* its application (`reference.target` is a `type_ref`, §8.1), so `loop => <T> loop<T>` has no second slot for a concrete use and its `T` is a type parameter; what is wrong with that declaration is that it applies itself forever and denotes no type, and that is what its diagnostic names. The kind is not what checks an argument — the position an argument lands in does that (above) — but it is what a diagnostic names. **A declared parameter the body never references is a resolver error** — `<T> !array { element_type: text }` is a mistake, not a degenerate template, and the rule holds for every template form (`box => <T> { v: text }` likewise). Value parameters bind **scalars only** — numbers, booleans, identifiers, and quoted strings; type parameters bind references, simple or compound. There is no parameter arithmetic: every value argument is used as given.

**Scoping and shadowing.** Parameters are local to the declaring definition; they do not escape and do not compose across `&`, and two definitions can independently use the same parameter name. Two positions that must agree share a parameter (`homogeneous_pair => <T> { first: T  second: T }`) — sharing the name is the link. Within a held body, parameter names take precedence over the schema namespace, and **a parameter that shadows a schema type is a resolver error** — renaming a parameter is free, and silent capture of a type the body also means to use is the confusability hazard [TSON-DATA] §8.2 exists for. A template's parameter list is not one of §11.4's hygiene scopes: one author writes it whole on one line, and a deliberate confusable pair there is not a population the list should grow for.

**Declaration-time checking splits by what the parameter list obscures, and nothing more.** Two questions are answered at the declaration from the held body's own field names, needing no stand-in values and so unable to fabricate a verdict: that each binding key names a field the constructor declares (`<T> !array { elemen_type: T }` is a typo, not a template), and that every REQUIRED field not covered by a default or a fix is bound. The typing of every *concrete* binding is checked there too (`min_items: "two"` is not an integer whatever `T` becomes), as is the unreferenced-parameter rule, from the tokens the held body names. Everything value-shaped that a parameter obscures waits for materialisation, where the whole body binds through the constructor's own reader (§8.2). An **unapplied** template is checked no further and receives no verdict: a resolver MUST NOT check it by substituting stand-in values, since that manufactures false errors on exactly the slots the mechanism exists for (`<N> !integer_type { min: N  max: 3 }` is correct for every argument anyone passes and fails under a stand-in of 10). Deferring everything would make the template the one form validated at use rather than at declaration, shipping broken templates to fail at their first user's application site; deferring only what the parameters obscure is the line.

**Where a deferred diagnostic lands.** A materialisation diagnostic MUST be located at the declaration whose text wrote the offending name, with the application as context — deferred checking is survivable only if the author is sent to the line they can edit. The rule is over the *name*, not over the template: `box => <T> { v: T  w: no_such_type }` applied by `holder` belongs to `box`, which wrote the name, while `box => <T> { v: T }` applied as `box<3>` belongs to `holder`, which wrote the `3`. One defect earns one diagnostic, however many declarations apply the template.

**Sugar inside templates.** A sugar form inside a template body desugars to the same construction it would outside one — §5.3's table unchanged — and lifts to its own synthetic entry, **open** if the form mentions a parameter (§5.3). Nesting needs no special member: an inner form lifts to its own open synthetic, and the outer body holds an ordinary open application of it. At its smallest, `box => <T> { a: [T] }` desugars as if written

```
array_t => <T> !array { element_type: T }
box     => <T> !record { fields: [ { name: a  type: { name: array_t  arguments: [ { name: T } ] } } ] }
```

with `array_t` an internal name, both entries held. The application in `box`'s field rides `type_ref.arguments` — the channel that means "an application" and nothing else. Applications inside a held body close before its entry is named: desugar lifts innermost-first, so a form it writes already names the entry its inner form became, and a form closed at materialisation MUST agree — `[[pixel; 3]; 3]` written out and `grid<pixel, 3>` closed land on one entry for one type. Because a closed lift hashes its binding record at desugar, before inner applications are rewritten, while an open lift hashes the closed record at materialisation, the two channels can produce two candidate entries for one type (`[box<text>]` written directly, and `[box<T>]` closed with `T := text`); §8.2's merge pass, which re-derives every synthetic's identity from its *resolved* record after Pass 2, is therefore **required**, not an optimisation.

**Templates are not directly instantiable.** A `type_definition` with a non-empty parameter list cannot validate data; a template with any parameter is a resolver error as a data annotation, without exception (§7.2).

**Open bodies in output.** While parameters remain unbound, an entry's resolved form is a `type_definition` whose `parameters` list is non-empty and whose `body` is the held application in wire form under the one-spelling rule — a record template as `!record`, a container as `!array`/`!map`/`!tuple`/`!choice`, an alias as `!reference` — with the open application recorded in the entry's `source` where the body is a pure application (§8.1). Openness is visible at the entry head (`parameters`) and in the body's own form, and no new kernel vocabulary is needed to carry it: `type_definition.body` is declared `top`, a held body's head IS-A `top` like any other body's, and a parameter reference type-checks structurally (`type_ref.name` is an `identifier`, and `T` is one), so the kernel's `schema` types the open entry without a second value shape. What distinguishes it is how it is *read*: a held body is compared as wire form and never as bound values, and is read against its constructor's vocabulary only at materialisation. Closed bodies are binding records and never contain parameter references (§5.6).

**Materialisation.** When every parameter closes, the resolver materialises the result (§8.2): materialisation is total and closes **innermost-out** — each open synthetic the application reaches becomes closed as its bindings go concrete, a held body whose last parameter is substituted being read against its constructor's vocabulary. Given `grid => <T, N> { x: [[T; 1..N]; 2..N] }`, closing `grid<pixel, 3>` yields

```
c1 => !array { element_type: pixel  min_items: 1  max_items: 3 }
c2 => !array { element_type: c1     min_items: 2  max_items: 3 }
c3 => !record { fields: [ { name: x  type: c2 } ] }
```

`c1` and `c2` are closed synthetics keyed on body structure — `c1` is the same entry an independently written `[pixel; 1..3]` produces anywhere in the schema — and `c3` is the instantiation entry keyed on its `source`, `{ name: grid  arguments: [{name: pixel} {value: 3}] }` (§8.2). Deferred value checks land here: substituting `N := "two"` yields `min_items: "two"`, and *that* is the error, located per the rule above. A recursive reference inside a nested form denotes, at materialisation, the instantiation entry under construction — the open synthetic's binding references that entry by its internal name before the entry is complete.

**Fully-bound references** resolve to REFERENCE-kind entries targeting the materialised instantiation by its entry name: `string_triple => vector<text, 3>` resolves to `kind: REFERENCE`, `source: { name: vector  arguments: [ { name: text }  { value: 3 } ] }`, and `body: !reference { target: E }`, where `E` is the instantiation entry's internal name (§8.2). A declaration whose body is a fully-bound application of a *constructor* is not a reference — it resolves as a construction in place (§5.6).

**Partial application.** When a reference to or refinement of a parameterized type leaves parameters open, it MUST re-declare the open parameters in its own `<>` slot — implicit parameter inheritance is not permitted; every parameter has a visible declaration site:

```
text_keyed_map => <V> {text => V}
uuid_pair      => <B> pair<uuid, B>
```

A partially-applied reference is itself a template. The sugar case is an open synthetic-backed template whose held body is `!map { key_type: text  value_type: V }`; the alias case is the held application `<B> !reference { target: { name: pair  arguments: [ { name: uuid }  { name: B } ] } }` — the application in `type_ref`'s record form, as every application inside a `!C` payload is written (§12.1); `reference.target` is a `type_ref`, so an alias to an application states the arguments it already binds (§8.1, §8.3), and the partial application is an ordinary open entry rather than the one shape that had to keep its arguments elsewhere. Reference-kind templates compose during substitution — no intermediate entry per alias hop; the origin survives as `source`, and a use site states the name it named (§8.3). `reference` is applicable because it IS-A `top` (§3.3.1): it describes no *value*, but it describes a type, which is the question a construction site asks; its entries take `kind: REFERENCE` from the alias form rather than from a base kind (§4.1). A resolver tells the held cases apart by the head — `record` closes to the instantiation entry, `reference` composes and mints nothing, every other constructor closes to a synthetic.

**Heads are not parameters.** Parameters bind *arguments of a named head*; the head itself — the type being refined, composed, or applied — is always a concrete name. `<A, N> A ^ { max_items: = N }`, abstracting over the refinement source, is not expressible, and a generic-application head that resolves to a parameter (`weird => <map> map<text, text>`) is a resolver error, diagnosed rather than applied (§3.3.1). Generic derivation over arbitrary heads is a deliberate v1 boundary, as is a parameterized atom refinement: `<N> !integer ^ { min: N }` is no form at all (§12.1) — the constructor spelling `<N> !integer_type { min: N }` is the open route.

**Parameters carry no bounds** — a second deliberate v1 boundary. A parameter declaration is a bare name: no supertype bound on a type parameter, no range or type constraint on a value parameter. An argument is checked only by the positions it lands in — a type argument must resolve; a value argument must be a scalar and conform to the field it ultimately fills (§5.2). Bounds are additive: they can be introduced later without changing the template model, so their absence is a recorded deferral, not a design claim.

**Closed entries are parameter-free.** An entry whose `parameters` list is empty MUST contain no parameter references anywhere — no reference `name` that resolves to a parameter and no `value` that does, at any depth — and its body is a binding record or a `!reference`, never a held application: a well-formedness rule on resolver output, where open entries appear beside closed ones with `parameters` non-empty (§8.1), and an integrity check on ingest; only template entries may be open.

The `_` token is not valid in field-type positions (§7.6); authors expressing "type to be filled later" use parameters.


#### 5.10.1 Self-Referential Types

Types may reference themselves:

```
node => { value: text  children: [node] }
linked_list => <T> { value: T  next: linked_list<T>? }
tree => <T> { value: T  children: [tree<T>]? }
```

The resolver MUST detect and handle cycles in type references. In a template body, a recursive reference is an open application like any other — `tree`'s `children` carries a reference to the open synthetic for `[tree<T>]?`, whose binding applies `tree<T>` (§5.3, §5.10) — and needs no special representation.

**Regular recursion.** Within a template body, a recursive application — direct or mutual — MUST pass each parameter through **unchanged**: `tree<T>` inside `tree`'s own body is well-formed; `weird => <T> { next: weird<[T]>? }`, whose recursive application rebuilds the argument, is a resolver error at the declaration. This is the standard regularity restriction of recursive-type theory, and it belongs beside this section's other v1 boundaries (no head abstraction, no parameter bounds): without it, one application could demand an unbounded family of distinct instantiations, and no portable depth limit distinguishes that from legitimate depth. The check is static, at declaration time.

**Productivity.** A type MUST admit at least one finite value; a definition with no finite member — a required direct or mutual self-reference with no terminating alternative — is a **resolver error** at schema load, not a warning: `item => { inner: item }` describes no data, and validation against it can only ever fail, so rejecting it at the schema is the earlier, better diagnostic. A recursive reference is **guarded**, and the definition productive, when every cycle through it passes at least one position that can terminate: an OPTIONAL field or tuple position (`?`), an array or map position whose floor admits emptiness or whose elements admit absence, or a choice with at least one non-recursive variant. A REQUIRED group (§5.11) terminates when at least one member is non-recursive — exactly one member must be present, so one terminating member suffices. Every entry is judged, synthetic entries included; a template is judged with its parameters assumed inhabited (parameters carry no bounds, §5.10, so an argument can always be chosen productive), and its materialisations need no re-check the entry did not already pass. Productivity is structural: whether an atom's *constraints* admit any value (a `min > max` interval) is a separate, value-level question (§5.3, §7.4).

**Template materialisation.** When a template application closes, the resolver materialises one entry per distinct application: identity is the canonical application — arguments compared after following reference chains (§8.2) — so `tree<text>` and `tree<integer>` are two entries and `tree<user_id>` over `user_id => text` is `tree<text>`'s, and every further occurrence of the same application anywhere in the schema reuses the existing one. The entry's name is internal (§8.2). Recursive references within the body, once bound, denote the entry being materialised; the resolver ties the knot by resolving them to the entry's own name (§5.10). The same model applies to non-recursive templates.


### 5.11 Field Groups

A record body may declare a **field group**: a parenthesised, `|`-separated set of labelled members occupying one logical position, of which at most one may be present in conforming data. The field name is the discriminator; the wire form of instances is unchanged by grouping.

```
integer_type => atom & {
  size:  integer_size?
  ( min: integer | exclusive_min: integer )?
  ( max: integer | exclusive_max: integer )?
  multiple_of:  non_negative_integer?
  members:      integer_member_set?
}
```

A group MUST contain at least two members — a declared group of one has a simpler spelling (a plain field with the group's state), and the grammar refuses the noise. Each member is a `field-name`/`type-ref` pair. Member labels share the enclosing record's field namespace: a label MUST be unique across the record's plain fields and all groups' members, including fields contributed by supertypes (§5.8's disjointness rule extends to member labels).

**Group state.** The `?` suffix applies to the group as a whole: a bare group is REQUIRED — exactly one member MUST be present; a group with `?` is OPTIONAL — at most one member MAY be present. These are the only group states; a group has no default or fixed form in v1.

**Member positions are deliberately bare.** A member takes a type-ref and nothing else: the `?` suffix and the `~`/`=` value modifiers are parse errors on a member — selection belongs to the label, presence belongs to the group. A group is not a type-ref: it cannot appear in field-type, element, argument, or variant positions, so multiplicity around a group (`[( a: T | b: U )]`) is not expressible; repetition of alternatives is written as an array of a named choice type (§5.4).

**Resolution.** Groups flatten. Each member becomes an ordinary `record_field` in the body's `fields` list — in source order, contiguous with its sibling members — with `state: OPTIONAL` regardless of group state. The grouping is recorded in the body's `record.groups` list as a `field_group { members  state }` entry, members in source order (the default `REQUIRED` is omitted in output, per §8.1's convention). The flattened fields-plus-groups form is canonical: group membership is fully derivable from `groups` in one local pass, and implementations SHOULD compile it into a per-record lookup at schema-load time, per the eager-resolution convention of §5.2 and §7.4 — the output form is canonical, not operational.

**Validation.** For each group of a record type, the validator counts present members after ordinary field validation: a REQUIRED group with zero or with two or more present members is a validation error; an OPTIONAL group errs only on two or more. A present member is validated as an ordinary field of its declared type.

**Refinement and composition.** In a refinement or composition body, members are addressable by name as ordinary fields under §5.7's rules — an inherited member is OPTIONAL, so it may be tightened to any state the transition table permits, including `= _` (forbidding that alternative's value, §5.2). Group presence rules are checked against the refined states at schema load: a refinement under which two members of one group are always present (both in a REQUIRED-family state) is a resolver error. A body entry may also restate a group: the restated group MUST have the same member labels in the same order (member type-refs restated verbatim), and may tighten state OPTIONAL→REQUIRED; REQUIRED→OPTIONAL is a resolver error, and changing membership is a resolver error. Supertypes contribute their groups whole; the composed entry's `groups` lists inherited groups in supertype order followed by the body's own. A field belongs to at most one group — guaranteed by label disjointness. A removal clause naming a member (§5.9) removes it from `fields` and from its group's `members`, and the arity ladder runs to zero: a group still holding two or more members survives with the survivors; a group reduced to **one** member is dissolved, the surviving field taking the group's state (REQUIRED group → field REQUIRED, OPTIONAL group → field OPTIONAL); a group reduced to **zero** members — every member named in the removal set — is removed outright, with nothing left to occupy its logical position. The two-member minimum is thus an invariant of resolved output as well as source: no resolved record carries a one- or zero-member group.

**The labelled-sum pattern** (informative). A record whose entire body is a single REQUIRED group admits exactly one field — a labelled sum in record clothing:

```
timestamps => { ( created: timestamp | modified: timestamp | accessed: timestamp ) }
```

An instance is `{ modified: "2026-05-21T13:05:00Z" }` — quoted, since a datetime contains colons ([TSON-DATA] §7.1); the label is both the semantic role and the discriminator. Where `choice` (§5.4) discriminates by variant type name — via a `!variant` annotation, required except where the choice is disjoint under the encoding's discrimination — and requires distinct variant types, a single-group record discriminates by label and permits variants of the same underlying type, needing no disjointness and no tag. The pattern's kind is PRODUCT; host bindings MAY recognise the shape (one REQUIRED group, no other fields) and lower it to a native sum. [TSON-GUIDE] discusses the pattern and the design history.


## 6. Annotations as Types

[TSON-DATA] §3.1 defines annotation syntax and preservation. This section defines annotation semantics: an annotation is a typed metadata attachment, resolved and validated against a type reachable through the schema chain.

Annotation values are always data values — concrete values, not type definitions — both in data values and within the type-definition grammar. Placement follows [TSON-DATA] §3.1; the resolver preserves annotations in their authored positions. The type-definition grammar adds one position: in `field-def` (§12.1), annotations precede the field name and annotate the field itself, mapping to the `record_field` in resolver output.

In schema declarations, an annotation may stand at two positions with two meanings, and the resolver does not hoist between them. An annotation immediately preceding the declared name binds to the name — the schema-map key — and is **metadata about the declaration**: documentation of the entry, provenance, lifecycle (`@deprecated name => …`), and the resolver's own derived marker (`@synthetic`, §8.1) live here. An annotation after `=>` binds to the `type_definition` value and is metadata *inside* the definition. Both positions are preserved in resolver output at their authored places (§8.1): a key annotation on the output schema-map key, a value annotation on the definition value. A **checked** annotation (below) is an assertion in either position and is honoured at both: a processor MUST consult both spellings, and MUST NOT ignore one with no diagnostic.

**Annotations are types.** An annotation `@T` (or `@T:value`) names a type `T` and attaches it as metadata to the surrounding value. Resolution is one-hop against the governing target's namespace (§3.3.3): the `!!meta` target for a schema document, the `!!schema` target for a data document. `!!import` entries and local entries of the document being authored are NOT part of the annotation namespace. The value is validated against `T`'s contract:

- For `void`-targeted `T` (a type whose resolved body, after following its reference chain, is `void` — such as `annotation` or `numeric`), the annotation form is `@T` with no colon and no value. Bare `@T` is shorthand for `@T:_`; the resolver fills the implicit `_` and validates against `void`'s contract (§4.2) — presence is the information.
- For any non-`void` `T`, the form is `@T:value`, where `value` is a single data-value conforming to `T`: `@doc:"User's full name"`, `@ordered:TOTAL`, `@since:2025-01`.

Any type in the governing target's namespace can be used as an annotation — there is no separate annotation namespace. **An annotation whose name does not resolve in that namespace is a resolver error**, for the valueless form as much as the valued one: `@doc` in a schema whose meta does not supply `doc` fails exactly as `@doc:"..."` does — the bare form is shorthand for `@T:_`, and `_` still needs a `void`-targeted `T` to validate against. Annotations are not free-form markers under a governing schema; the preserved-uninterpreted treatment belongs to schemaless processing alone ([TSON-DATA] §3.1). The one exception is structural: the meta-kernel's own bootstrap self-reference (`annotation => @annotation void`), resolvable because the kernel's governing target is itself and the kernel is pre-loaded before any document is parsed.

The one-hop rule is what allows that self-reference to work; it also fixes where annotation types must live (§3.3.3).

**What an annotation may do.** An annotation never changes a value, its type, or whether it is valid: validation is over values, and a schema with every annotation erased admits exactly the same values. That is the criterion for the home of a fact — *an annotation is the right home exactly when the mark changes no value's validity* — and it is what keeps an encoding's projection concern out of every processor's type model. Within it an annotation may do two further things, and the series has one of each kind:

- It may carry **load-time force**: a *checked* annotation is verified against a fact the resolver derives, with two outcomes and no third — verified, or a resolver error at schema load — and the section defining it states the check. `@disjoint` (§5.4) is checked against derived disjointness; `@discriminator` and `@rest` (below) are checked against the shape of the choice or field they mark.
- It may be a **representation directive**: it directs how a *class* of encodings represents a value, and force is confined to the encodings that claim it. No encoding is privileged — TSON text is one member of the text class, beside JSON — so a directive binds every encoding in its class, and a document in a directed encoding may not be readable without it; in every other encoding, and in the model, it changes nothing. An encoding-rules document MAY bind projection behaviour to a schema-side annotation declared for it, and text keeps `!variant` at every non-disjoint choice, discriminated or not (§5.4).

Everything else an annotation can be is *advisory*: `@doc`, `@deprecated`, `@title`, `@examples`, `@read_only` and `@write_only` (the last two void presence markers — a bare mark is the same statement as `true`, and both on one field is a schema-load error), and the `@annotation` marker itself, which signals "intended as annotation metadata" so tools can surface available annotations, carries no runtime force, and leaves a type without it no less usable as an annotation.

**`@discriminator` and `@rest`.** Two facts a schema already fixes but had no way to state, both needed by an encoding that dispatches on members rather than on a type tag, and neither needed by TSON text. `discriminator => @annotation field_name` on a *choice* declaration names the field a member-dispatching encoding selects the variant on; `field_name` is an `identifier`, so a non-name spelling fails at the annotation's own type. It is checked at schema load: every variant is a record declaring the named field; that field is `REQUIRED_FIXED` in every variant (never `REQUIRED_DEFAULT` — a default is omissible and cannot dispatch); and the fixed values are pairwise distinct. Deriving the field instead was considered and refused: with two `REQUIRED_FIXED` fields in every variant a derivation picks arbitrarily or picks both, and either way couples the wire to a pin the author fixed for an unrelated reason. `rest => @annotation void` on a *field* designates the map-typed field an encoding that flattens is meant to flatten — the field a record's undeclared entries live in, a record being closed under its type (§7.2). Checked at schema load: the field's type resolves to a text-keyed map, and at most one field per composed chain carries the mark, a count §5.8's restated-field rule makes well-defined. Both ride the ordinary author-annotation channel in output (§8.1), and neither changes a value's validity: a discriminated choice admits exactly the variants it admitted, and a `@rest`-marked record admits exactly the values §7.2 already admits.

**Resolver-attached annotations.** One annotation is attached by the resolver rather than written by the author: every synthetic entry's key in resolver output carries the bare marker `@synthetic` (§8.1, §8.2), so tooling can fold materialised entries back into nested display. It is defined in the meta-kernel, follows the same resolution rules as user-written annotations, and is **derived**: on ingest it is discarded and recomputed (§8.1) — it carries no decode force and cannot be forged into a resolved document to change its meaning. No annotation records a reference: a use site names what the author named, and the chain is stated as written (§8.3).


## 7. Text Encoding Rules: Data Values Under a Schema

This section is the text format's **encoding rules**: how the type system's values are carried in TSON text. Every encoding of the type system owns a section shaped like this one — its atom lexical forms, its container syntax, its treatment of absence, and its discrimination predicate over the derived disjointness fact (§5.4). This section states TSON text's rules; future encodings (a JSON encoding, for instance) state their own against the same encoding-independent model, in their own encoding-rules documents.

[TSON-DATA] defines the syntax of data values and their schemaless interpretation. This section defines the `!!schema` directive (§7.1) and how an active schema changes interpretation: type-annotation resolution (§7.2), atom parsing in place of base type resolution (§7.3, §7.4), sets (§7.5), the absent sentinel (§7.6), resolver behaviours at typed positions (§7.7), and cross-schema references (§7.8).


### 7.1 The `!!schema` Directive

`!!schema` identifies the schema whose types are available for `!name` references in the value it governs. The directive value is a URL string identifying a published schema.

```
!!schema:"https://example.com/people.tn?sha256=c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5"
!person { name: Alice age: 30 }
```

**The referent is a schema document.** A `!!schema` URL resolves to a schema document — never to a resolved-schema data document, whose resolver-derived fields cannot be verified from the document alone. Resolved-form documents enter the schema library only through the explicit ingest path (§8.1, §10.1).

On a data document's header, `!!schema` binds the schema for the entire document. On a scoped value — a record field value, map entry value, or array element ([TSON-DATA] §2.3) — it binds the schema for that value alone: the referenced schema becomes the active scope for all `!name` annotations within that value and its descendants, reverting to the enclosing scope when the value ends. Directives remain excluded before map keys, field names, and annotation values ([TSON-DATA] §3.3); §7.8 defines the typed-position rules.

**The directive names a namespace, not a root contract.** `!!schema` supplies the vocabulary against which `!name` annotations resolve; it does not assert the governed value's type, and a schema has no privileged entry point. The value names its own type by annotation, and **under `!!schema` the root MUST name its type**: an unannotated root under a bound schema is a validation error in every mode, since under a schema every value is typed by its position or by its tag (§7.3) and a root has no position to be typed by. There is no "vocabulary-only" reading of a governed document — a value read by base type resolution is a schemaless value, and a document is one thing or the other from its header. The same holds at every position whose type is a `scoped` instance: a value there names its type or is invalid (§7.8).

A scoped `!!schema` at a position whose type is constrained by the outer schema is admitted exactly when that type resolves to a `scoped` instance whose `scope` holds `EXTERN`, or a container of one; anywhere else it is a resolver error — see §7.8, where the rule is derived rather than listed.

Schema documents do not carry `!!schema`: a schema's governing contract is declared by `!!meta` (§2.2.2). A document with no `!!schema` directive has no type vocabulary: base type resolution ([TSON-DATA] §4) applies, type annotations are limited to the built-in vocabulary of [TSON-DATA] §5, and any other type annotation is preserved unresolved — applications SHOULD treat unresolved type annotations as informational. Such a document opens no schema scope of its own (§7.8).


### 7.2 Type Annotation Resolution

In data values, a type annotation (`!name`) marks **instantiation** — the value is concrete data conforming to the named type. The name resolves against the external schema identified by the current `!!schema` directive — never against definitions within the same document (§3).

**The built-in vocabulary does not apply in schema scope.** When a schema is in scope, all type annotations MUST resolve through the schema's type-name namespace; a built-in annotation name not defined by the active schema is an unresolved-type error. Schemas wanting `uuid`, `bytes`, `datetime`, and the other built-in names import the core type library or define them locally. This is the normative statement of the scoping rule; [TSON-DATA] §5.1 restates it for schemaless processors. A document with no `!!schema` falls back to schemaless processing (§7.1).

**Records are closed under their type.** When a schema is in scope and a record's type is known, the record MUST contain only fields defined by its type; fields not present in the type definition are validation errors. This applies to directly-typed records (`!person { ... }`) and structurally-positioned records (a record at a record-typed field position). Schemaless records have no closure rule. Closure is what makes schema evolution a discrete operation: every published schema version is a precise, immutable contract about what fields exist (§3.5).

**Type expression syntax is not available in data values** ([TSON-DATA] §3.2). To annotate an array or map value with a named type, declare a named type in the schema (`int_list => [integer]`) and write `!int_list [1 2 4 8 32]`.

**Validation follows what the name is.** `!T value` asserts that the value conforms to `T`, and conformance is determined by `T`'s own definition. An **atom instance** validates a single token by its parsing contract (§7.4): `!age 42`, never `!age { ... }`. A **product** validates a record against its field list; a **choice** validates any conforming variant. A **constructor** is a record-shaped type, so it validates a record against its constraint-field vocabulary: `!integer_type { min: 0  max: 255 }` is a record conforming to `integer_type`'s fields, receiving ordinary record validation; family coherence between bindings (e.g. `min ≤ max`) is a compilation and ingest concern (§8), not data validation.

**Subsumption at typed positions.** At a position whose declared type is `T`, a value annotated `!S` is valid if and only if, after following both reference chains to their terminal entries (§8.3), `S` is `T` or `T` appears in `S`'s transitive `type_definition.supertypes`. The check reads the contract index only — never the body's lineage `record.supertypes` (§5.9, §8.1) — so a subtracted type does not stand where its source is expected. An admitted value validates against `S` in full: closure, constraints, and defaults are `S`'s; the position's `T` contributed admission alone. An unannotated value at a typed position is validated as exactly `T`, so the annotation carries information precisely when `S` is a proper subtype — there is no structural recovery of `S`, per the once-only reading of form ([TSON-DATA] §2.4). This is the data-level payoff of composition in an application schema: a field typed `person` admits `!employee` where the schema declares `employee => person & { ... }`, and without the annotation the value is a `person`, full stop. Choice-typed positions discriminate by variant membership (§5.4) and scoped positions by the namespaces the instance admits (§7.8) — the same surface gesture, an annotation selecting a more specific type than the position promises, under their own membership relations; this rule governs every other typed position. It is also what admits resolver-output bodies at the `top`-typed `body` field: every body annotation head carries `top` in its transitive supertypes — constructors through their base kinds, `reference` through its direct composition with `top` (§4.1, §8.1, §9).

**There is no construction in data.** `!C { bindings }` produces a new type only in the schema grammar (§5.5); the same surface shape in a data document is a record that *describes* constraints — which is precisely what resolver output stores in `type_definition.body` (§8). The two category errors are symmetric, in data as in schema: a constructor never types its family's atom values (`!integer_type 42` is a type error — the value type is the instance, `!integer 42`), and an instance never types records (`!age { min: 0 }` is a type error — the constraint vocabulary belongs to the constructor).

**No parameterized annotations.** Resolver-output bodies (`!array { element_type: person }`, `!map { key_type: … }`, §8.1) are annotations by ordinary parameterless constructors (§4.2), validated by ordinary record validation: each field against its declared type, a type slot against `type_ref`. No special rule remains — a template with any parameter is a resolver error as a data annotation, without exception (§5.10).

**Schema values.** The type annotation `!schema` marks a map as a schema value — a regular type annotation; `schema` is defined in the meta-kernel as `map<type_name, type_definition>`. It appears on data documents that carry resolved schema structure, most notably resolver output (§8); schema-document source never carries it (§2.1).


### 7.3 Atom Parsing Replaces Base Type Resolution

When a schema is in scope, base type resolution ([TSON-DATA] §4) does not apply at all: every value is typed by its position or by its tag, each declared atom type owns its own parsing contract (§7.4), and there is no third way to read a token. The tokens `true` and `false` have no special status when a schema is in scope — their meaning is determined entirely by the position's type, and at core's `boolean` they are enum members like any other (§7.4). There is no `null` in the notation ([TSON-DATA] §4.4): the token `null` at a `text` position is the string `null`, at an `int32` position a parse error, and at a `void`-typed position — the kernel's `void`, core's sibling redeclaration of it (§3.3.5, §9), or the counterpart an alternative meta layer defines (§3.4) — a validation error, since `void` admits the absent sentinel `_` and nothing else. A JSON document's `null` reaches the model as absence through the JSON reader ([TSON-DATA] §6), where the position's state decides whether absence is admitted (§7.6); no keyword in this notation is needed to carry it.


### 7.4 Atom Token Parsing

Each atom type owns its parsing contract. When a token appears at a position whose type is an atom, the atom's parser takes the token and produces either a typed host value or a parse error; the atom's constraint record is applied as validation after parsing succeeds.

**Parsing and validation are distinct.** Parsing takes a token to a host value; validation checks the host value against the constraint record. `twelve` at an `integer`-typed field is a parse error; `300` at a field typed `age` (refining `integer` with `{min: 0 max: 150}`) parses as an integer, then fails validation. Implementations SHOULD distinguish these in error reporting ([TSON-DATA] §8.1).

**Enum member semantics.** The `enum` atom's `members` field is typed by the kernel's `enum_set` — `!set_type { element_type: identifier }`, non-empty by `set_type`'s default (§9) — enumerating the names permitted at an enum-typed position. Members are identifiers: each MUST match the identifier grammar ([TSON-DATA] §7.7), so a member is spelled as a name (`ACTIVE`, `in_progress`), never as a number or a quoted string with spaces — `!enum [1 2 3]` is a schema-load error, and a display string is mapped at the boundary, as every comparable schema language requires. The replacements cover the space a numeric enum was reaching for: a contiguous range is `!integer ^ { min: 1  max: 3 }`, an arithmetic progression is `multiple_of`, a **sparse set is `members`** — `!integer ^ { members: [80 443 8080] }`, the `integer_member_set`-typed facet `integer_type` carries and the `set<value>`-typed one `decimal_type` carries (§9) — and a choice is for alternatives that are not one family. A mixed source enum (`["AUTO", "MANUAL", 80, 443]`) becomes a choice over an identifier enum and a member-set integer, which is disjoint by class and tag-free. An enum MUST have at least one member (`!enum []` fails the set's `min_items` at schema load), and members are unique by the set's contract (`!enum [OPEN OPEN]` is a schema-load error). There is no carve-out for `true` and `false`: the kernel defines `boolean` as `!enum [true false]`, and the member rule constrains only how a member is *written* — an enum's discrimination class (§5.4) is still read off each member's own token by [TSON-DATA] §4, so `boolean` stays boolean-class and `[INDEX NAMED]` string-class. Parsing at an enum-typed position is an identity check of the token's decoded text against the member names; the resolved host value is determined by natural parsing of the matched token — `true`/`false` in core's `boolean` resolve to native host booleans; members of user-defined enums resolve to the member name as host text, or a host-language enum value where the implementation provides a mapping. `members` describes the permitted names, not the resolved representation.

**The `identifier` primitive.** An `identifier` value is a name: the decoded text of a token, after unquoting, escape processing, and NFC normalisation, matching the identifier grammar of [TSON-DATA] §7.7 — `XID_Start`-initial, `XID_Continue ∪ { - }` thereafter, in NFC, with the joining controls admitted by context only. Two identifiers are equal iff their NFC texts are byte-identical; identity is case-sensitive. `identifier` shares its host representation with `text` but differs in contract: it rejects whitespace, digits and signs at the start, every format and control character, and non-NFC text, so an identifier can always be rendered back as an unquoted lexeme. `identifier` is not used in data values; it appears in the kernel's own declarations — `type_name`, `field_name`, `param_name` alias it, and `enum_set` is a set of it — so a rule stated on it reaches every naming position in the series. Because every identifier lies inside the unquoted-token profile ([TSON-DATA] §7.1), the positions that admit no quoted form (`!` targets, `@` names, and every schema-grammar name) lose nothing.

**Number-grammar reuse.** Atoms that parse numeric values SHOULD use the number grammar of [TSON-DATA] §7.6 for the relevant numeric form; a single shared number parser dispatching on the atom's declared form is the expected pattern.

**Constraint fields typed as `value`.** Some atom constructors declare constraint fields with type `value` because the constrained atom cannot be referenced at the point of declaration — `decimal_type.min: value?` cannot use `number`, which is a core instance of `decimal_type` (bootstrap ordering, §3.4). The kernel's `integer_type` is the exception that proves the rule: its bounds, its `multiple_of` and its `members` are typed `integer`, available in the kernel's own namespace, and every facet that counts something is typed by the kernel's `non_negative_integer` (§9). A token at a `value`-typed position is the token, uninterpreted (§4.2): **the resolver reads it under the atom it stands for**, once that atom is in scope, and stores the result. That reading is what makes a `value`-typed constraint comparable at all — `1` and `1.0` at `decimal_type.min` are one number, not an integer beside a float — and for a `value`-typed *set* it runs before the set is formed: each member of `decimal_type.members` is read under the constrained atom first, so `[1 1.0]` is a duplicate the set catches, a member that does not parse as a decimal fails at schema load, and §5.7's member-set tightening compares decimals rather than whatever host type a bare token would have become.

Each constrained atom's implementation converts `value`-typed constraint values to its internal representation. Conversion MUST occur at schema-load time, not per-validation; an atom that cannot convert a given constraint value MUST report a resolver error at schema-load time — a schema either loads cleanly or fails with a clear diagnostic, and a half-valid schema that silently mis-validates is never produced. Which constraint-value types an implementation accepts for conversion is an implementation choice; the validation semantics after conversion are the atom's contract.

**Coherence of a body's facets.** A body's facets MUST between them admit a value: the lower bound not exceeding the upper (across whichever inclusive or exclusive forms are present), a `multiple_of` strictly positive, a `precision` at most 9, a network family's `within`/`excluding` pair and prefix bounds admitting a value (§5.5), and — where `members` is present — **every member satisfying the body's other facets**, the range a family derives rather than stores included (`{ members: [443]  size: { bits: 8 } }` fails the way a stated bound outside the width does). Each is a resolver error at schema load; at materialisation the same rules run again over the operands that were parameters (§8.2).

**Lexical classes vs type names.** The categories of [TSON-DATA] §4 base type resolution (boolean, integer, float, string) are lexical classifications of tokens a schemaless read produces, not type names: the lexical class and the core type `number` are distinct namespaces that share a word. A schema cannot reference a lexical class, and base resolution never produces a declared type; when a schema is in scope, the schemaless dispatch does not apply (§7.3).


### 7.5 Sets

A **set** is an unordered collection of unique values. Sets share array syntax `[ ... ]` at the data level ([TSON-DATA] §2.7); set-ness is a schema property declared via the kernel's `set_type` constructor, most often through the `set<T>` template (§4.2, §5.3). Without a schema, every `[ ... ]` is an array.

**Duplicate handling.** When source data contains a value more than once at a set-typed position, the duplicate is a **validation error** at the repeated occurrence — a set-typed position asserts uniqueness, and silently dropping a member the author wrote would decode the document to something other than what it says (the same posture as duplicate map keys, [TSON-DATA] §2.6). Two values are duplicates if the element type's equality contract considers them equal, and that contract is the type's **value space** (§5.5): name identity for a set of `identifier`, value equality for a set of `integer` or `number` (`1` and `1.0` are one member), octet equality for a set of `bytes` whatever the spelling, and instant equality for a set of `datetime`.

**Element order.** Sets are unordered; the materialised representation uses array syntax, but element order is implementation-defined. Implementations comparing resolver outputs MUST compare set-typed fields as sets, not ordered lists; fixture-comparison tools SHOULD canonicalise set-typed fields (e.g. lexical sort) before byte-comparison.

These rules apply uniformly to every set-typed position: the kernel's `enum.members`, the exact tiers' `members`, `scoped.scope`, user-defined set-typed fields, and set values produced by the positional fill rule (§5.6). An absent element at a set-typed position is rejected — `set_type` pins `state: = REQUIRED` (§4.2) — and a set is non-empty unless its type wrote `min_items: 0`.


### 7.6 The Absent Sentinel Under a Schema

[TSON-DATA] §2.9 defines the absent sentinel and its data-value positions. `_` is not itself a type; the type whose sole conforming value is `_` is `void` (§4.2). It is the one spelling of absence: the token `null` is a string, or a parse error, or a validation error, by the position's type (§7.3).

This document extends the position rules of [TSON-DATA] §2.9:

| Position                                  | `_` permitted? | Meaning                                                                                          |
|-------------------------------------------|----------------|--------------------------------------------------------------------------------------------------|
| Array element (schema in scope)           | conditional    | Permitted only when the array type's element state is OPTIONAL, written `[T?]` (§5.3)            |
| Tuple element (schema in scope)           | conditional    | Permitted only when the position's state is OPTIONAL (§5.3); the absent element occupies its slot |
| Record field value (schema in scope)      | conditional    | Permitted when the field's state admits absence: OPTIONAL, or OPTIONAL_FIXED with `= _` (§5.2). At every REQUIRED-family state (REQUIRED, REQUIRED_DEFAULT, REQUIRED_FIXED), a validation error — at REQUIRED_DEFAULT the fix is omission, which injects the default (§5.2) |
| Map key (schema in scope)                 | no             | Resolver error — restates [TSON-DATA] §2.9's resolver-layer rule; keys define membership and are always required |
| Map entry value (schema in scope)         | conditional    | Permitted only when the map type's value state is OPTIONAL, written `{K => V?}` (§5.3); the entry is then present with an absent value ([TSON-DATA] §2.9) and counts toward the size bounds |
| Field-type position (type-definition grammar) | no         | Parse error — use type parameters (§5.10)                                                        |
| Type-ref position (type-definition grammar) | no           | Parse error                                                                                      |
| Type-def body (declaration right-hand side) | no             | Parse error — use `{}` for an empty record                                                       |
| Field modifier value (`~`/`=`)            | `=` only       | `= _` valid on OPTIONAL fields; `~ _`, and `= _` on REQUIRED fields, are resolver errors (§5.2) |

When a schema is in scope, absence at an element position requires the governing container type to permit it. Absent elements occupy positional slots, and size constraints count all slots. For tuples, OPTIONAL positions require explicit `_` — the tuple's length is fixed by its type, and short tuples are validation errors (§5.3).


### 7.7 Resolver Behaviours at Typed Positions

**Typed key equality.** [TSON-DATA] §2.6 defines layered duplicate-key identity: textual identity as the parser's minimum, decoded-value identity for any processor that decodes, and declared-type identity — which can only make *more* keys equal — on top. A schema-aware processor decodes keys by the declared key type's contract (§7.4), so two keys equal under that contract are one key, and a duplicate that only the declared key type relates (`1` and `1.0` under a `number`-keyed map, two spellings of one octet string under a `bytes`-keyed one — the textual and decoded-value duplicates of [TSON-DATA] §2.6 are already resolver errors below this layer) is a **Class 2 validation error** at the repeated occurrence, consistent with the set rule (§7.5): identity under a declared type is over the key type's value space (§5.5). Which host representation the decoded keys take afterwards is the implementation's concern; the identity contract is not.

**Empty braces.** [TSON-DATA] §2.8 defers empty-brace disambiguation to declared type information. Under a schema, the expected type at the position supplies it: the resolver transforms an empty-brace value into an empty record or an empty map per the expected type, defaulting to an empty record when the position is untyped. The rule covers exactly the two containers that share the brace form and cannot be told apart when empty; an empty map then faces the map's size bounds like any other. At a position whose expected type is anything else — an array, a tuple, an atom, a choice with no brace-class variant — an empty brace is a **validation error**, reported as a value of the wrong form: an array or tuple has its own empty spelling (`[]`), and admitting `{}` there would let a brace-class value conform to a bracket-class type (§5.4).


### 7.8 Cross-Schema Type References

Under a schema a value's type comes from a namespace, and two questions are independent at every position: whether the **governing namespace** — the schema's own declarations and its imports (§2.2.3) — is admitted, and which **foreign schemas** are. The `scoped` constructor (defined in `meta.tn`) is the sum type that answers both: its instance names the namespaces a value's type may be drawn from, and the value names its own type.

```
scope_kind => !enum [LOCAL EXTERN]
scoped     => sum & {
  scope:    set<scope_kind>
  schemas:  {uri => [type_name; 1..]?; 1..}?
}
```

`scope` says which namespaces are admitted; `schemas` narrows the foreign ones — absent is any foreign schema, a keyed map is those schemas, and a key's absent value (`{K => V?}`, §5.3) is every type that schema declares where a list is those types. The map is keyed by schema identity, so one schema cannot be listed twice, and keys are compared by canonical identity ([TSON-DATA] §2.2.1), so a pinned key and an unpinned `!!schema` in the data match, each pin verified by the loader on its own (§10.2). The state admitting nothing is unspellable rather than refused — both collections are non-empty by construction — and one coherence rule, of the family a resolver already runs over bound pairs, ties the fields together: `schemas` requires `EXTERN` in `scope`. Where `choice` is the *closed* sum, enumerating its variants, `scoped` is the *open* one, naming where variants are drawn from; both are sums in which the value names its own type, and `disjoint` is absent on `scoped` as on every non-choice sum (§8.1).

Core declares the three named subsets and two applications over them (§9):

| Instance | `scope` | Meaning |
|---|---|---|
| `declared` | `[LOCAL]` | a type this schema declares or imports, chosen by the value |
| `extern` | `[EXTERN]` | a type from any foreign schema, which the value names for itself |
| `dynamic` | `[LOCAL EXTERN]` | either, from anywhere — the successor to what earlier revisions called `unknown`, under a narrower meaning: the *data* decides the type, and the value is validated in full against the type it names, which is why it is not called `any` |
| `extern_of<S>` | `[EXTERN]`, `schemas: { S => _ }` | any type of one named foreign schema |
| `extern_type<S, T>` | `[EXTERN]`, `schemas: { S => [T] }` | one named type of one named foreign schema |

The two templates are ordinary §5.10 partial applications over a meta constructor: `attachment: extern_type<"https://…/claim.tn", claim>` declares nothing, `S` standing in a `uri`-typed key and `T` inside `[type_name]` — both atoms, so both value parameters — and reaching one schema and one type per application; the multi-type and multi-schema rows keep the instance form or a named declaration in the user's schema. An application's identity is the argument as written (§8.2), so a pinned and an unpinned `S` are two applications where the data-side match is canonical.

**The data rule.** At a position whose type resolves to a `scoped` instance, the value's own shape picks the cell:

- a value carrying `!!schema` is **EXTERN**: its identity MUST be a key of `schemas`, or any schema if `schemas` is absent, and its `!type` MUST be in that key's list, or any of that schema's types if the list is absent;
- a value carrying `!type` alone is **LOCAL**, resolved in the governing namespace exactly as at any other position;
- a value carrying neither is a **validation error** — *a value at a scoped position must name its type* — in every mode, since the position promises no type for an untagged value to be read as (§7.1).

A cell the instance's `scope` does not hold refuses the value it would have taken, as a validation error: nothing failed to resolve, and [TSON-DATA] §8.1's categories make that of it at both cells. The read of an EXTERN value is the scope push below: load the named schema through the ordinary library (§10.1), so a schema nothing would supply is a schema-resolution error and never a verdict on the value; push the scope; resolve `!type` in it; validate in full; pop.

**Values carry their scope.** A value matched at an EXTERN cell MUST carry its own `!!schema` directive identifying the foreign schema and a `!type` annotation identifying the type within it — schema scope changes are always visible in the data, never implicit. A `!!schema` directive on a scoped value pushes the new schema scope for that value; when the value ends, the scope reverts:

```
!!schema:"https://tson.io/2026/medical/patient.tn?sha256=a4f2e8d1c3b5a7f9e2d4c6b8a0f1e3d5c7b9a2f4e6d8c0b3a5f7e9d1c4b6a8f0"
!patient_record {
  patient: "1234"
  attachments: [
    !!schema:"https://tson.io/2026/insurance/claim.tn?sha256=f8b2a1d3c5e7f9a1b3d5e7f9a2b4d6e8f0a3b5d7e9f1a4b6d8e0f2a5b7d9e1f3"
    !insurance_claim { claim_id: CLM-5678  amount: 450.00  provider: "City Medical" }
    !!schema:"https://tson.io/2026/radiology/report.tn?sha256=d4e9c7f1a3b5d7e9f2a4b6d8e0f3a5b7d9e1f4a6b8d0e2f5a7b9d1e3f6a8b0d2"
    !radiology_report { study_id: RAD-9012  modality: MRI  findings: Normal }
  ]
}
```

Here `attachments` is declared `[extern]` — any foreign schema per element — or, where the schema means exactly those two, a narrowed instance listing both: `attachments: [claim_or_report]` over `claim_or_report => !scoped { scope: [EXTERN]  schemas: { "https://…/claim.tn" => _  "https://…/report.tn" => _ } }`, which is the case the example exists to show and which no single-schema form could spell. Each directive binds to the single array element it prefixes ([TSON-DATA] §2.3, §2.7); the example follows the encoder recommendation of one directive-carrying element per line.

**Composition order under a scoped element.** Within a scoped value the order is fixed by the grammar: directive, then annotations, then the optional type annotation, then the core value ([TSON-DATA] §2.3, §3.1). The directive opens its scope before the element's own augmentation resolves: annotations on a scoped element resolve against the newly scoped schema's namespace (§3.3.3), and the type annotation resolves against its type-name namespace.

**Typed-position restriction, derived.** A nested `!!schema` directive is admitted at a position exactly when the position's type resolves to a `scoped` instance whose `scope` holds `EXTERN`, containers descending as above — for a record field or map entry value the field or entry type applies; for an array element, the element type applies. Anywhere else it is a resolver error. The rule is no longer a list of permissive names: a position admits a scope push because its type says so, the way `disjoint` is a fact of a choice's declarations (§8.1). A `value`-typed position in particular admits none — a single token is not a scope, and a pushed schema's namespace is consulted only by the `!name` annotations a token position does not carry. The outer schema opts in to receiving foreign values at each position, by naming a type that admits them: cross-schema acceptance is authored intent, not accident.

**A schemaless document opens no schema scope.** A nested `!!schema` in a document with no `!!schema` of its own is a validation error naming the directive. The restriction above exists so that an outer schema can opt in, and a schemaless document has no outer schema to do the opting: admitting the push there would make a Class 1 document a Class 2 document halfway down, on the strength of the same absence that makes the check impossible, would give the one directive with no header-visible cost ceiling to a document nothing agreed to validate, and would produce a verdict on one value in a document that is otherwise not being validated at all. [TSON-DATA] §1.5's classes stay decidable from the header: a reader knows from the first two lines which obligations it has taken on. A processor that meets such a directive reports it once, consumes it, and reads the value it prefixed schemalessly.


## 8. Resolver Output

The resolver's output for a schema is a map of `type_definition` records: the output records and their conventions (§8.1), template instantiation and identity (§8.2), and references (§8.3). Each construct's mapping to its `type_definition` is defined with the construct in §4–§5.


### 8.1 Output Records: `type_definition`, `type_ref`, `type_argument`

The `type_definition` record captures the resolver's output for any entry. Its fields: `source` (structured provenance; below), `kind` (ATOM, PRODUCT, SUM, DATA, or REFERENCE — §4.1), `parameters` (declared parameter names; non-empty marks a template, §5.10), `supertypes` and `subtypes` (name-level indexes, resolver-managed; below), `disjoint` (resolver-derived, recorded on every choice definition; below), and `body` (required, declared as `top`). There is no `constructor` field: whether an entry is a constructor is whether it IS-A `top`, derivable from `supertypes` (§4.2). Body values are annotated with the structurally-appropriate type: `!record` for vocabulary bodies (constructors, §4.2), a binding record headed by the applied constructor for every closed non-reference definition (§5.6), and `!reference { target: T }` for reference-kind entries. The `top` declaration is sufficient by the subsumption rule of §7.2: every body annotation head carries `top` in its transitive `supertypes` — constructors through their base kinds (§4.1), the kernel's `reference` through its direct composition with `top` — so the parser validates body annotations without dependent typing. An **open** entry is a `type_definition` like any other: its `parameters` list is non-empty and its `body` is the held application in wire form under §5.10's one-spelling rule (`set => !type_definition { kind: PRODUCT  source: set_type  parameters: [T]  body: !set_type { element_type: T } }`), typed by the kernel's `schema` without a second value shape, since a parameter reference is an `identifier` where a type name is. What sets it apart is the reading, not the shape: a held body is compared as wire form and never as bound values, is read against its constructor's vocabulary only at materialisation, and a consumer of closed entries never meets one (§1.3).

**The `type_ref` and `type_argument` records.** Every type-reference position in resolver output — `record_field.type`, `tuple_element.element_type`, `choice.variants` elements, `type_definition.source`, and the type slots of binding records (§5.6) — holds a value of the kernel's `type_ref` record; applications carry their arguments as `type_argument` records:

```
type_ref => {
  name:       type_name
  arguments:  [type_argument]?
}

type_argument => {
  ( name: type_ref | value: value )
}
```

A `type_ref`'s `name` is the referenced type or, within a template body, a parameter, and `arguments` carries the application's positional arguments — present only where an application is still open (§5.3): a use-site application resolves to a bare reference to its materialised entry, so `arguments` in output means "an application" and appears inside template bodies and in `source` provenance only. A `type_argument`'s `name` member holds every *reference* — a type, an entry, or (in template bodies) a parameter of either kind — while its `value` member holds concrete literals only; exactly one member is present (the group is REQUIRED, §5.11). The reference/literal split is therefore structural: a token in a `name` member is always a reference, so value-typed token literals (enum members) can never be mistaken for parameter names, and member population is the open/closed signal — substitution replaces `{ name: P }` with the bound argument (`{ name: text }`, `{ value: 1920 }`) as parameters close.

**The `reference` and `record_field` records.** An alias body is `!reference { target: T }`, where `target` is a `type_ref` — a bare name for every closed alias, and an application with its arguments, in `type_ref`'s record form, where the alias is still open (`<B> !reference { target: { name: pair  arguments: [ { name: uuid }  { name: B } ] } }`, §5.10), so that a partial application states the arguments it binds and `source` is never asked to hold them. A reference is a hop: `target` names the immediate referent, and a use site elsewhere in the output that names the alias names the alias (§8.3). A `record_field` carries `name`, `type`, `state`, and an optional `value`; there is no separate parameter channel. In a closed entry `value` holds a concrete fixed or default value, read against the field's declared type (§5.2). In a held template body the same slot holds a parameter, unlabelled: a token there is a parameter exactly when its text resolves into the enclosing entry's `parameters` (§5.10), and a closed entry has no parameters for one to resolve into, so the slot is unambiguous at both ends. §5.7's fixation at materialisation is what the single channel costs and where it is paid.

**Positional form.** `name` is `type_ref`'s only REQUIRED field, so the general positional form (§5.6) applies at every `type_ref`-typed position: a bare name token fills `name` directly, and a braced record is the explicit form. Canonical output MUST use the bare token whenever `arguments` is absent; an explicit record without `arguments` is valid data but non-canonical. `type_argument` deliberately has *no* REQUIRED field and hence no positional form: a bare token cannot self-classify as reference or literal, so its braced record is load-bearing, not ceremony. The `name<args>` sugar is schema syntax and not data syntax (§5.6): a data document at a `type_ref` slot writes an application as the explicit record, `{ name: page  arguments: [ { name: order } ] }`, and the spelling that names one is an alias in the schema (§8.2).

**Reading parameter references.** This is the rule for an open entry (above) and for nothing else. Parameters and type names share the `identifier` primitive, so a `name` — in any `type_ref`, at any depth, including `type_argument` name members, `reference.target`, and the `source` field — resolves against the enclosing entry's `parameters` list first, then the schema's type-name namespace, the same precedence used during source-level parsing (§5.10); a name at a constructor-channel position — a construction's `source` head (`source: enum`, `source: array`), a held body's head, or a body-annotation head — resolves through the structure namespace (§3.3.1), the same channel that resolved it at source level. The two channels are consulted at disjoint positions and never mix (§2.2.3): type-name tokens are data scoped to the schema being described, while annotation and constructor heads belong to the governing chain. Inside a held body a parameter may also stand in a value slot — a `record_field.value`, a constructor's scalar field, an enum's member list — read by the same precedence (§5.10). Only template entries may contain parameter references or held bodies (the closed-entry rule, §5.10); a consumer holding an entry with empty `parameters` interprets every name directly against the schema.

**`source` is structured provenance.** `source` records where a definition came from, as a `type_ref` — a bare name under the positional form in the common cases, the applied form when the origin was an application. Concretely: a construction records the constructor (`source: enum`); an atom refinement records the instance's constructor (`source: integer_type`, §5.5); a `^` refinement records the source name; a composition records **no** `source` — its provenance is the direct `&` list in the body's `record.supertypes` (below); a sugar-desugared construction records the desugar target (`ids => [order]`: `source: array`), and a synthetic entry likewise records the constructor it builds — never the application that produced it (§8.2); a reference to a *template application* records the application itself; and a materialised instantiation records the **canonical** application — arguments with reference chains followed to their terminal entries and nested applications resolved to entry names (§8.2, §8.3) — which makes the entry **self-describing**: its body is recomputable by substitution from its `source`, and identity comparison is single-level structural equality of `source` (§8.2). What is canonicalised is identity, not provenance: the name the author wrote survives at the use site that wrote it.

**`supertypes` and `subtypes` are name-level indexes**, resolver-managed; declarations never set them. Each answers a one-hop question — "is this in family X?", "what is in family X?" — by name; the structured truth lives in `source` and the bodies, so a parameterized supertype contributes its *head name* to the index while its arguments travel through the absorbed fields (§5.8). Their standing differs. `subtypes` is a cache: fully derivable, always recomputable, never trusted — the resolver MUST compute it as the transitive inverse of `supertypes` across the schema's namespace. `supertypes` is derivable from `body` for product types (the body's `record.supertypes` carries the direct compositions) but NOT for the atom family: desugaring erases the surface distinction between refining an instance (`age => !integer ^ { min: 0 max: 150 }`, IS-A `integer`) and constructing a fresh sibling (`port => !integer_type { min: 0 max: 65535 }`, IS-A nothing) — both serialize to `source: integer_type` with an identical body shape. The atom family's direct IS-A hop therefore lives only in `type_definition.supertypes`, making that field part of the type's serialized meaning rather than a recomputable cache. Construction and instantiation are not IS-A: a sugar construction (`ids => [order]`) records empty `supertypes` (§5.6), and a template instantiation records the template's own supertypes, with no hop to the template itself — so "did this come through `vector`?" is a `source.name` question, not a `supertypes` one. Like every name in resolver output, the names in `supertypes` and `subtypes` are tokens in the described schema's own namespace (§2.2.3): an index never reaches outside the resolution that wrote it, and a consumer follows the entries in the defining schema's output.

**`disjoint` is a resolver-derived fact over choices.** For every choice definition — every entry whose body is a `!choice` binding record, declared or synthetic — the resolver records `disjoint: true` or `false` — discrimination-class distinctness, a total, two-valued derivation (§5.4); the field is absent on every other definition, the non-choice sums (the `scoped` instances, §7.8) included, since they have no variant list to derive it over. Like `subtypes` it is a cache — fully recomputable, never trusted: on ingest (below) it MUST be discarded and recomputed, never taken from the document. It carries the encoding-independent fact that each encoding's discrimination rules consume to decide whether a `!variant` tag may be dropped; the `@disjoint` annotation (§5.4), when present, is the author's assertion checked against this derived field at schema load, and does not appear in the field itself.

**Two `supertypes` fields with different semantics.** `type_definition.supertypes` records the **transitive** IS-A chain — direct parents plus each parent's chain, deduplicated; construction via `!T {}` does not contribute. The body's `record.supertypes` records only the **direct** `&` compositions as written. Consumers use `type_definition.supertypes` for IS-A queries and `record.supertypes` to recover source-level composition. Example: `text_type => atom & { ... }` produces `type_definition.supertypes: [atom, top]` and `body: !record { supertypes: [atom], ... }`.

**Ingest.** When `!type_definition` records are ingested as data (§10.1): the resolved document is a *data* document, and its header admits only `!!id` and `!!schema` ([TSON-DATA] §2.2, §3.3) — it cannot carry `!!import`, and its `!!schema` names its own governing chain, not the schema it describes. The described schema's identity, and the import list that constitutes the namespace in which the map's type-name tokens are interpreted (§2.2.3), are therefore not recoverable from the resolved document and MUST be supplied to ingest explicitly; the source schema document's header, held in the library (§10.1), is the sole authority for both. Then: `subtypes` and `disjoint` MUST be discarded and recomputed, and so MUST the derived annotation `@synthetic` on synthetic entries' keys (§8.2): ingest discards and recomputes it, so it cannot be forged into a document to change how it reads. Author-written annotations, key- and value-position alike (§6), are preserved as data, a restated field's in the order §5.8 fixes. `supertypes` is taken as input, with the transitive closure recomputed and integrity verified — every listed supertype must exist, atom-family supertypes must share the entry's `source` constructor, product-type lists must be consistent with the body's `record.supertypes`, and transitive lists must be closed. The closed-entry rule (§5.10) MUST be verified — no parameter-resolving name or value and no held body in an entry with empty `parameters`, and no parameter references of any kind inside binding records (§5.6); an open entry — a `type_definition` with `parameters` non-empty — has its held body re-resolved as source, since a held body is never read against a vocabulary until it closes, and its wire form MUST equal the one-spelling form §5.10 requires. Every instantiation entry's body MUST equal the recomputation by substitution from its recorded `source`, and every synthetic entry MUST carry the body its `source` constructor's vocabulary admits — the self-describing checks. Symmetrically, a construction's binding record MUST agree with its `source` application: each parameter-routed slot of the head's vocabulary carries exactly the corresponding argument (a `!map` body's `key_type` and `value_type` match the application's two arguments, §4.2). A checked annotation (§6) is re-checked on ingest as it was at load: `@disjoint` against the recomputed `disjoint`, `@discriminator` and `@rest` against the shapes they mark. Canonical-form violations (an explicit `type_ref` record without `arguments`, a positional form where the convention requires the explicit record) are resolver errors at ingest. Within-family retargeting — a document claiming an entry refines a different sibling of the same constructor — is internally consistent and undetectable from the document alone; this residual gap is one reason resolved-form documents are never schema sources (§10.1) and ingest is an explicit, opt-in act.

**Body patterns** (informative — the construct sections govern):

| Source form | Resolved `type_definition` | Examples |
|---|---|---|
| Root record `{}` | PRODUCT; `body: !record { fields: [] }`; no supertypes | `top` |
| Base kind `top & {}` | PRODUCT; `supertypes: [top]` | `atom`, `sum` |
| Fresh record `{ fields }` | PRODUCT; `body: !record { fields: [...] }`; no supertypes | `person`, `record_field` |
| Composition `A & B & { ... }` | `supertypes: [A B ...transitive]`; kind per §5.5 | `employee` |
| Refinement `T ^ { ... }` | `source` per §5.7; `supertypes: [T ...]`; materialised body | `production` |
| Subtraction `T - { f }` | `type_definition.supertypes` empty; lineage in `record.supertypes` (§5.9) | `account_public` |
| Atom constructor `atom & { ... }` | ATOM; IS-A `top`; `supertypes: [atom top]` | `integer_type`, `enum` |
| Product constructor `product & { ... }` | PRODUCT; IS-A `top`; `supertypes: [product top]` | `record`, `array` |
| Sum constructor `sum & { ... }` | SUM; IS-A `top`; `supertypes: [sum top]` | `choice`, `scoped` |
| Constructor refinement `T ^ { ... }` in a meta-schema | IS-A `top` through `T`; `source: T`; `supertypes: [T ...]` | `set_type` |
| Instance template `<T, N> !C { ... }` | `parameters` non-empty; `body` is the held application `!C { ... }` in wire form (§5.10, §8.1) | `vector`, `set`, `extern_of` |
| Constructor instance `!T {}` | kind from `T`'s family; `source: T`; no supertypes; `body: !T {}` | `integer`, `value`, `dynamic` |
| Atom refinement `!I ^ { v }` | `source: I`'s constructor; `supertypes: [I ...]`; `body: !ctor { v }` | `age` |
| Sugar or constructor-application body | construction in place; `source`: the constructor; binding-record body; no supertypes (§5.6) | `lookup => {text => integer}`, `schema` |
| Record template `<T> { ... }` | `parameters` non-empty; not instantiable; `body` is the held `!record { fields: [...] }` (§5.10) | `container` |
| Sugar form at a use site | lifts to a synthetic entry — closed (constructor body) or open (held application) per §5.3; the use site holds a bare reference | `[text]`, `{text => integer}`, `[T; N]` in a template body |
| Template instantiation | internally named entry; `source`: the canonical application; binding-record body (§8.2) | closures of `vector`, `tree` |
| Choice `(A \| B)` | SUM; `body: !choice { variants: [...] }`; `disjoint` always recorded (§5.4) | `contact_method` |
| Field group `( a: T \| b: U )` | members flattened into `fields` as OPTIONAL; grouping in `record.groups` (§5.11) | `integer_type`, `type_argument` |
| Reference `name` | REFERENCE; `body: !reference { target: name }`; no supertypes | `id => uuid` (§8.3) |

The `schema` type is a map from type names to type definitions; schema lookup is by name. Declared entries are keyed by their declared names; instantiation and synthetic entries are keyed by their internal names, each synthetic entry's key carrying the derived `@synthetic` marker (§6, §8.2).


### 8.2 Instantiation and Synthetic Entries

Resolver output contains three families of internally-named entries beyond the declared ones, and one identity discipline covers them all. **Every application materialises**: a sugar form or constructor application at a use site lifts to a **synthetic entry** — closed or open per §5.3's lift rule — and a fully-bound application of a non-constructor template materialises an **instantiation entry** (§5.10). Nothing is carried structurally in place: a use site holds a bare reference to its entry, and `type_ref.arguments` in output means an open application inside a template body or a `source` record, nothing else (§8.1). A declaration's own body resolves in place as a construction (§5.6) and is the one thing that never lifts.

*Trigger positions.* Lifting and instantiation fire wherever an application appears: record field types, group member types, tuple element types, array and map element positions, choice variants, arguments at any depth, and a top-level type-def body that is a template application (which resolves the declaration to a REFERENCE entry targeting the instantiation, §8.3). Composition targets and refinement sources remain restricted to named type references, optionally with arguments; inline structural forms there are resolver errors (§5.7, §5.8, §12.1). An *open* application inside a template body does not materialise — it closes when its template does (§5.10).

*Two identities.* **A declared entry's identity is its name**: two declarations are two types however alike their bodies — `stock_id => !uuid_type {}` and `other_id => !uuid_type {}` are two atoms, and a purely structural rule would collapse them and destroy a distinction §7.2 enforces (§5.5). **A minted entry's identity is its canonical content**, since it has no declared name to be its identity; "identity is structural" is true of the minted half only, one rule per family:

- **Instantiation entries** — two fully-bound applications denote the same entry if and only if their **canonical applications are structurally equal**: the application with every argument's reference chain followed to its terminal entry and every nested application resolved to its entry's name (§8.3), compared member-by-member — heads, argument order, `name` against `name`, `value` against `value` under the rule below. Following the chain is what makes a *rename* transparent: `box<user_id>` over `user_id => uuid` denotes the same type as `box<uuid>` and mints the same entry, because a reference is the same type under another name (§5.7's table) — where `box<!uuid ^ {}>` and `box<!uuid_type {}>`, a refinement and a fresh instance, keep entries of their own. The canonical application is exactly what the entry's `source` records, so identity is a single-level structural comparison of `source`, and spelling variance never forks an entry; the name the author wrote is provenance, not identity, and survives at the use site that wrote it.
- **Value arguments compare as values, and are recorded as written.** A literal argument is recorded in `source` (and substituted into the body) as the token the author wrote, so resolver output round-trips the spelling; identity, however, compares the value the token denotes under [TSON-DATA] §4 — exactly the equivalence §4.3 states and no wider. Radix, digit separators, and a redundant sign fall away (`255`, `0xFF`, `0b1111_1111`, `0o377`, and `+255` are one argument), a float's written scale falls away (`.5`/`0.5`, `1.0`/`1.00`/`1e0`), and `.inf`/`.infinity` are one value; quoted strings compare by text. What does **not** fall away is the base type: §4 resolves `1` to an integer and `1.0` to a float, so those remain two arguments even though one magnitude covers both. `vector<float32, 255>` and `vector<float32, 0xFF>` therefore denote one entry, and a choice listing `[float32; 255]` and `[float32; 0xFF]` as two variants is the same-variant-twice error of §5.4, not an accepted non-disjoint choice.
- **Closed synthetic entries** — structural equality of the resolved binding record: one entry per distinct concrete form schema-wide. A closed synthetic's `source` names the **constructor** it builds, never the application that produced it — an open synthetic's name is internal, so keying on it would make identity depend on an unstable name and would prevent the cross-channel deduplication below.
- **Open synthetic entries** — structural equality of the held body **up to consistent renaming of parameters** (`<T, N>` and `<A, B>` over the same shape are the same template), most simply by normalising parameters to positional indices before comparing.

The two channels dedupe against each other's products: `[order; 1..]` written directly in a plain declaration and the same form arising inside a materialised template land on **one** closed synthetic entry, because both comparisons occur after names have meaning, over resolved structure. The moment is normative: desugar-time lifting *creates* a synthetic entry, but its identity settles **after Pass 2**, when references have resolved — eagerly-lifted synthetics that become structurally identical under resolution merge into one entry, so the one-entry-per-form rule holds schema-wide regardless of which moment produced each candidate. **The merge pass is required**, not an optimisation: a closed lift hashes its binding record at desugar, before its inner applications are rewritten to entry names, while an open lift hashes the closed record at materialisation, so without a pass that re-derives every synthetic's identity from its resolved record, `[box<text>]` written directly and `[box<T>]` closed with `T := text` land on two entries for one type (§5.10). The first occurrence materialises; later occurrences reuse; recursive references resolve to the entry's own name (§5.10.1). Closure consults templates and never modifies them: closing `grid<pixel, 4>` after `grid<pixel, 3>` reuses the template entry and both open synthetics untouched while producing fresh closed entries.

*Synthetic entries are an implementation's own.* Their names are resolver-chosen and their shape is unconstrained beyond producing the required resolved output: a processor is free to mint one per schema, share them, alpha-normalise their parameters, or keep them however its own resolver requires, and the lift targets are never kernel-declared (§5.3). An author never writes one and never references one; the one thing an author does is name an application they want to reuse, and the spelling for that is an alias — `order_page => page<order>` — which is one line, gives the application an identity and a diagnostic located at the author's own declaration, and is RECOMMENDED over repeating the application or the explicit record form (§8.1).

*Entry names are internal.* Instantiation and synthetic entries are named by the resolver, and the names are **not part of this specification's conformance surface**: an implementation chooses them freely, subject to two rules and one recommendation. Freshness (MUST): an internal name is a valid `identifier` and collides with no declared entry and no other internal entry. Stability within a resolution (MUST): all references to one entry use one name. Determinism (SHOULD): names SHOULD be derived from the entry's identity-bearing content — the canonical `source` for an instantiation, the resolved body structure for a closed synthetic, the parameter-normalised body for an open one — a readable head plus a structural hash, for instance. Content-derived names keep re-resolution output diff-stable, and they are what makes independently-resolved namespaces agree on their internal names wherever their structures agree — which is what lets the import merge (below) unify rather than collide. Internal names do not exist at source level (there is nothing to author), MUST NOT be relied on across resolutions or implementations, and resolved-form comparison between implementations is structural — equality up to renaming of internal entries.

*Entry shape.* An instantiation entry: `source`: the canonical fully-bound application; `kind`: the template's kind; `parameters`: empty; `supertypes`: the template's supertypes, unchanged by substitution (§8.1); `body`: the substituted binding record, headed by the applied constructor (§5.6). A synthetic entry resolves under the top-level-construction rule: `kind` from the constructor, `source: array`/`map`/`tuple`/`choice`, binding-record body (a held application while open), no supertypes; its schema-map key carries the derived `@synthetic` marker (§6, §8.1). Materialisation also runs the value-level checks that the held body deferred: **every family coherence rule that §5.3 and §5.5 state for a literally written body applies again at materialisation, over the operands that were parameters** — `min_items ≤ max_items` for the containers, `min ≤ max` and `min_length ≤ max_length` for the atom families, `min_prefix ≤ max_prefix` and the prefix range for the network families, and any rule a family states that is not a bound pair — together with the conformance of every substituted value to the slot it fills (§5.2, §5.10). A resolver needs no list: the rule an application closes onto is the rule the family already owns for a literal body, asked once more of the closed record. A violation is a resolver error located per §5.10's rule — at the declaration whose text wrote the offending name, with the materialising application as context. For the §5.10 template `vector => <T, N> !array { element_type: T  min_items: N  max_items: N }` applied as `vector<pixel, 1920>`, a resolver might produce:

```
vector_pixel_af3 => !type_definition {
  kind: PRODUCT
  source: { name: vector  arguments: [ { name: pixel }  { value: 1920 } ] }
  supertypes: []
  body: !array { element_type: pixel  min_items: 1920  max_items: 1920 }
}
```

while the structurally identical form written directly as `[pixel; 1920]` anywhere in the schema lands on a *closed synthetic* entry — `source: array`, the same body, and `@synthetic` at its key. The instantiation entry carries no `@synthetic` marker: the two families are distinguishable (an instantiation's `source` is an application; a synthetic's is a bare constructor), and only synthetics are the fold-back-into-display case the marker serves.

*Non-exposure and the import merge.* Instantiation and synthetic entries are resolver-materialised, not declared: they cannot be named from any schema source, and authors reach a type by writing the application (reusing the entry) or by declaring a named alias (§8.3). Under the transitive import merge (§2.2.3) the internal entries travel with their namespace — an imported entry's body references them, so they are part of what the import delivers — and they merge by the same structural identities as within a schema: an importing schema whose own application derives an identical entry denotes the merged entry, never a duplicate, and internal names never collide with declared names by construction (§3.4.1).

*Diagnostics and cross-schema identity.* Internal names MUST NOT be the primary form shown to users: diagnostics surface the source application or form — recoverable from the entry's `source` and the originating source position — located at the declaration whose text wrote the offending name (§5.10). Cross-schema identity of *declared* types is through named declarations or not at all; internal entries carry structural identity only.


### 8.3 References

A type-def body that is purely a type *reference* — a bare name or a template application, with no body record, no `&`, and no construction — produces a REFERENCE-kind entry with body `!reference { target: T }`, where `target` names an entry, and `source` records the referent as written (§8.1):

- **Simple leaf** (`id => uuid`): `source: uuid`, `body: !reference { target: uuid }`. The `target` is the immediate referent, not the ultimate type — reference chains (`doc → documentation → text`) appear as distinct entries, each pointing one hop forward.
- **Fully-bound template application** (`string_triple => vector<text, 3>`, over §5.10's `vector` template): the application is materialised (§8.2); `source` records it as written; `target` is the instantiation's internal entry name. (A sugar-form body — `string_triple => [text; 3]` — is not this case: a declaration's own body is a construction in place, §5.3, §5.6.)
- **Open template application** (`same_tree => <T> tree<T>`, `uuid_pair => <B> pair<uuid, B>` — an application of a named template with at least one parameter still open): the declaration is itself a template (§5.10); `target` is the application itself, arguments included — `reference.target` is a `type_ref`, so the alias states what it already binds — and `source` records the same application. Reference-kind templates compose during substitution without materialising intermediate entries.

A top-level *constructor* application is not a reference — it resolves as a construction in place (§5.6). `!reference { target: X }` is the explicit spelling of the alias `X` and denotes exactly that (§5.5).

**A reference is a hop, not a rewrite.** Resolved output states a reference chain **as the author wrote it**: a use site — a field type, tuple element, choice variant, argument, or binding-record type slot — that names a REFERENCE entry names that entry, and `target` names the next. Nothing is flattened, and no annotation records a source-level name, because the chain itself is the record: an earlier revision rewrote each use site to the chain's terminal entry and attached an `@alias` naming the first hop, which was a second, lossy representation of a walk that happens anyway — a chain of two hops kept the name of the one that carried nothing — and every pass that needs the terminal entry (reader compilation, discrimination-class classification, inhabitance, variant distinctness) walks the chain regardless. A processor MAY collapse a chain **after linking, when it compiles for reading** — a REFERENCE entry's reader is its target's, named for the entry doing the referring, which is what gives a diagnostic the alias the author wrote for free — and MUST NOT collapse it in resolved output, which is what two conforming processors compare. The walk stops *at* an argument-bearing target — that is an application, not a hop to another entry, and there is no entry at the end of it until materialisation mints one.

**Where a chain is followed, and where it is not.** Identity of an application follows its arguments' chains to their terminal entries, because a reference is the same type under another name (§8.2); subsumption at a typed position follows both chains (§7.2); the source of a refinement or composition, a choice variant judged for `void`, and a discrimination class are all read after following the chain (§4.3, §5.4, §5.7). What the chain *never* does is carry meaning of its own: nothing that changes how a value reads may live on a reference, and the meta layer declares no annotation that does — a fact that decides reading, such as an alphabet, lives in the type (§5.5), where every position already names it. That is what makes a rename safe to see through.

**References are not supertypes.** REFERENCE-kind entries do not contribute to the `supertypes`/`subtypes` graph: `uuid.subtypes` does not list `id`; `id.supertypes` is empty. The three ways to name a type after another, and what each buys, are tabled in §5.7.

**Instantiation entries are not references.** Instantiation entries (§8.2) carry the substituted template's kind and a binding-record body, not a `!reference` record. A REFERENCE entry may *point at* an instantiation; the instantiation itself is a closed, concrete definition.


## 9. The Meta Layer

Two pre-loaded schemas form the meta layer. Implementations MUST pre-load both (§3.4, §10.1). The normative source for both is carried in the companion artifacts (§1.5), pinned by content hash at publication; the pre-loaded in-memory model is authoritative and the documents are descriptions of it.

| Schema | Role | Declares |
|--------|------|----------|
| `2026/35/m/meta-kernel.tn` | Self-referencing bootstrap; its `!!id` and `!!meta` reference its own URL | `top`, the base kinds `atom`/`product`/`sum`/`data` (§4.1), `reference`; the parameterless `record`/`array`/`set_type`/`map`/`tuple`/`enum`/`choice` constructors (§4.2) — `set_type` refining `array` with `min_items ~ 1`; the `record_field`/`tuple_element`/`field_group`/`type_ref`/`type_argument`/`type_definition`/`schema` supporting records (§8.1); `enum_set` (`!set_type { element_type: identifier }`, which `enum.members` uses) and `integer_member_set` (`!set_type { element_type: integer }`, which `integer_type.members` uses, §7.4); the `unit` constructor and its instances `value`/`identifier`/`void`; the identifier roles `type_name`/`field_name`/`param_name` (naming positions over `identifier`, §7.4); the atom-constructor/instance pairs `integer_type`/`integer` (with the `integer_size` supporting record, `size`, the bound groups, `multiple_of` and `members`), `text_type`/`text`, `uri_type`/`uri`, `regex_type`/`regex`; `non_negative_integer` (`!integer ^ { min: 0 }`, the type of every counting facet); `boolean`; `atom_specification`; the internal enums; the annotation types `annotation`, `documentation`, `doc`, `synthetic` (§6, §8.2) |
| `2026/35/m/meta.tn` | Canonical meta-schema; chains to the kernel and imports it | `set` (the `set<T>` template over `set_type`, §5.3); `bytes_type` (with `bytes_encoding`, §5.5); `scoped` (with `scope_kind`, §7.8); the constraint-vocabulary constructors for numeric (`float_type` with `ieee_format`, `decimal_type` with `members`, `rational_type`, `complex_type` with `complex_component` — a partial order under refinement, §5.7), temporal (`date_type`, `time_type`, `datetime_type`, `duration_type`, `period_type` — all five with the exclusive bound groups, the last three of `time`/`datetime`/`duration` carrying `precision`, `duration` and `period` carrying `multiple_of`, §5.5), identifier (`uuid_type`), network (`ipv4_type`, `ipv6_type`, `cidr4_type`, `cidr6_type`, `mac_type`), and text (`email_type`) families; the annotation types — the classifying markers `ordered`, `bounded`, `exact`, `numeric`; the checked `disjoint`, `discriminator`, `rest`; the documentary `deprecated`, `since`, `todo`, `lang`, `title`, `examples`, `read_only`, `write_only` (§6) |

Each constraint-bearing atom family is a constructor/instance pair (§4.2): the constructor lists the family's constraint fields; the canonical empty instance lives in the kernel or in core (`integer`, `text`, `uri`, `regex`; `number`, `float32`, `float64`, `rational`, `complex`, `date`, `time`, `datetime`, `duration`, `period`, `bytes`, and the rest in `core.tn`). Core also declares the `scoped` instances `declared`, `extern`, `dynamic` and the templates `extern_of`, `extern_type` (§7.8), and one `bytes` with no spelled siblings (§5.5). Two rules hold across every family and are stated once in meta's header: `multiple_of`, wherever it appears, is strictly positive, tests the value's magnitude with the sign ignored, tightens only to an integer multiple, and composes with every other facet; and `members`, wherever it appears, has every member satisfying the body's other facets, and a refinement may only shrink the set (§5.7, §7.4). Meta's kernel import is doubly load-bearing: it supplies the kernel types meta's own declarations use and delivers the kernel's structural vocabulary to every meta-governed schema (§3.3.1). Annotation types live in the chain because of the one-hop rule (§3.3.3, §6); core redeclares `void`, `set`, `doc`, `documentation`, and `annotation` — fresh siblings of the kernel's and meta's entries: same constructions, same contracts, distinct type entities (§3.3.5) — for data documents and user schemas governed by or importing core, and adds `complex` (`!complex_type {}`).

**JSON.** A JSON document is read through a JSON reader, which is a second encoding of this model and not a mode of the TSON notation ([TSON-DATA] §6): it maps a JSON number to `number`, JSON `null` to absence — admitted where the position's state admits it (§7.6) — and a JSON object to a record where its keys are identifiers. Every type doc in the artifacts describes its value space without reference to any encoding and names TSON text where it means it; JSON is a second member of the text class of encodings, and a representation directive (§6) binds both.

Users normally chain to `meta.tn`. Schemas that chain to `meta-kernel.tn` directly are alternative type vocabularies replacing meta, or extensions of the meta layer — the format's sanctioned extension point (§2.2.2).

**Guidance for extension meta-schemas.** Two rules keep extended vocabularies coherent with the kernel's. First, a constructor field that holds a type reference MUST be typed `type_ref`, not `type_name`: `type_ref` is what makes the slot participate in reference chains and structural identity (§8.1, §8.3) — a `type_name`-typed slot would carry a bare token the resolver treats as data, invisible to every reference mechanism. `data`-kind constructors (§4.1) follow the same rule for the type-shaped slots their instances bind (an HTTP operation's `request`/`response` slots are `type_ref`s into the governed schema's namespace). Second, the kernel's `regex` atom is pinned to a specification (`atom_specification`, §4.2), and the pin is a strict gate: an implementation MUST implement the pinned dialect as specified — not a host library's near-relative — and MUST document any divergence it cannot avoid; a pattern using a feature outside the pinned dialect is a resolver error at schema load, not a silently host-dependent behaviour. The same discipline applies to every spec-pinned atom an extension declares: the `spec` pin is the contract, and "whatever the platform regex engine does" is never a conforming reading.


## 10. Schema Resolution Model

Schema URLs are **logical identifiers first**: a URL names a schema, and resolving it does not by itself require a network request. Implementations resolve schema references through a **schema library** — a local store mapping canonical identities ([TSON-DATA] §2.2.1) to schema content. Fetching is a permitted but opt-in way to *populate* the library (§10.1, §11.2), not the meaning of a reference.


### 10.1 The Schema Library

When the resolver encounters a `!!schema`, `!!meta`, or `!!import` directive, it looks the reference up in the library; if not found, the resolver reports an error — it does not attempt to fetch. The library is populated through three mechanisms, in order of precedence:

- **Pre-loaded schemas.** The implementation ships the meta-kernel and meta-schema as pre-loaded entries and SHOULD ship the core type library. Pre-loaded schemas are authoritative — their in-memory representation takes precedence over any external source (§3.4).
- **Registered schemas.** Applications register schemas before parsing documents that reference them, from local files, embedded resources, or any application-specific source.
- **Fetched schemas (optional).** Implementations MAY support fetching by URL; fetching MUST be explicitly enabled by the application — never the default — and is subject to §11.2. Production systems SHOULD register all required schemas at startup rather than fetch at runtime.

**Identity agreement.** Registration whose target identity differs from the canonical identity of the content's declared `!!id` is an error — the library MUST reject it as identity confusion. The comparison is over canonical identities, so scheme variance agrees; a differing host or path does not. Content with no `!!id` MAY be registered under an application-supplied identity (a development-mode convenience, §2.2.1); content with an `!!id` is registered under that identity and no other.

**Schema sources are schema documents.** Whenever the library is populated from a document — registered or fetched, and for the targets of `!!schema`, `!!meta`, and `!!import` alike — that document MUST classify as a schema document ([TSON-DATA] §2.2). A resolved-schema data document (resolver output, §8) is not a valid schema source: its derived fields cannot be verified from the document alone; it is non-canonical and not hash-pinnable; and, being a data document, its header cannot carry `!!import` — the described schema's namespace is not expressible in it, only in the source schema's own header (§8.1's ingest rule). An implementation MUST reject a data document supplied as the content of a schema URL, with a categorized diagnostic. Resolved-form documents MAY enter the library only through the explicit ingest path (§8.1), which does not take derived fields on trust: the derived annotation `@synthetic` and the derived fields are discarded and recomputed, author-written key and value annotations are preserved as data, and `kind: DATA` entries (§4.1) ingest like any other entry — their bodies are ordinary data of the extended meta-schema's vocabulary, validated against it, with the naming-a-DATA-entry-as-a-type check (§4.1) re-run on the ingested map.


### 10.2 Hash-Pinned References

The hash-parameter URI convention — the algorithm-named query parameter, lowercase full-length hex, the query-is-hash-parameters-only rule, canonical identity, and the mismatch rule — is defined in [TSON-DATA] §2.2.1. This section defines how the library applies it.

Library keys are canonical identities: a plain reference, its `http`/`https` variant, and its hash-pinned form all name the same entry — the pinned form additionally demands verification. When a hashed reference resolves, the implementation MUST verify the library's content against the declared hash before use; a mismatch is a resolver error, and the library MUST NOT silently substitute mismatched content. When registration supplies a document whose declared `!!id` itself carries a hash, the implementation SHOULD verify at registration time.

**Hashes attach to the canonical entry.** Verification is a property of the canonical identity, not of each reference. Within a single resolution's reference closure (§3.4.1), the digests declared across all references to one identity form a set, and the set determines the identity's treatment. An empty set resolves unverified. A single digest is verified once against the entry's content, before any reference to the identity resolves; every reference — pinned and plain alike — then resolves to the verified entry. A plain reference alongside a pinned one is therefore not a conflict but an inheritance: it rides the pin, and it inherits the pin's failure — a verification failure applies to the identity for the whole resolution, and a plain reference MUST NOT proceed with content a declared digest has rejected. Two distinct digests for one identity is a resolver error: at most one describes the real bytes, and the library MUST NOT choose between them. A verified entry, once cached under its canonical identity, is immutable. A *failed* verification SHOULD NOT be cached under the canonical identity; an implementation MAY maintain a separate negative cache keyed on the full reference string.

**Pins to pre-loaded identities.** Pre-loaded schemas resolve to in-memory structures (§3.4, §10.1), but a pinned reference to a pre-loaded identity is an ordinary pinned reference and MUST be verifiable: an implementation MUST hold, for each pre-loaded schema, the published content digest (or the canonical document bytes) of the artifact its structures implement, and MUST verify declared pins against it — a mismatch is the ordinary verification error. One reference in the series can never carry a pin: the meta-kernel's self-referencing `!!meta`, whose hash input would contain the pin itself. It needs none — it resolves by pre-loading (§3.4) — and under the per-identity rule its plain spelling composes without conflict with pinned references to the kernel elsewhere in a closure.

An implementation MAY offer a strict resolution mode that rejects any closure containing an identity whose digest set is empty (pre-loaded identities excepted), for deployments requiring a fully verified chain; the per-identity rule above is the default and the interchange baseline.

When no digest is declared for an identity anywhere in the closure, it resolves without integrity verification — appropriate for development, NOT RECOMMENDED for production interchange. References that cross a trust boundary SHOULD be hash-pinned: a schema's `!!meta` and `!!import` values pin its contract and dependencies; a data document's `!!schema` pins its vocabulary. Because pinning is per-identity, one pinned reference raises the guarantee for every reference to that identity in the closure, and a published plain reference never prevents downstream pinning. Pinning composes into the verification chain of [TSON-DATA] §2.2.1 — a consumer holding a single hashed reference can verify a document together with its schema, that schema's meta-schema, and the kernel, provided each rung's own references are pinned; published schemas SHOULD pin every reference they carry, the kernel's self-reference being the sole exception.

```
!!schema:"https://example.com/people.tn?sha256=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
```


### 10.3 Canonical Identity

Reference identity is defined by [TSON-DATA] §2.2.1: two references name the same document if and only if their canonical identities — lowercase host plus path, scheme and query removed — are byte-for-byte identical. The library applies this as its key rule:

- `http://tson.io/2026/35/m/core.tn` and `https://tson.io/2026/35/m/core.tn` — **same** entry; the scheme is a transport hint.
- `https://tson.io/2026/35/m/core.tn` and the same URL with `?sha256=…` — same entry; the pinned form additionally requires verification (§10.2).
- `https://tson.io/2026/35/m/core.tn` and `https://elsewhere.example/2026/35/m/core.tn` — **different** entries; the host is load-bearing, so a fetch-endpoint change cannot silently redirect a name.

The identity profile *forbids* the spelling variants a general web stack would normalize (non-lowercase host, userinfo, ports, percent-encoded unreserved characters, dot-segments, fragments) rather than normalizing them away — an identifier outside the profile is an error, and comparison never performs runtime normalization ([TSON-DATA] §2.2.1). The pin rides in the query so that it reaches the origin and the cache, and the fragment is reserved for a later intra-document reference ([TSON-DATA] §2.2.1). Content addressing is over bytes, and every identity rule above a digest is over value spaces (§5.5): two spellings of one octet string hash the same document to one identity and are one map key, and a content-addressed reference is never encoding-dependent.

**Confusable identities.** Because identity selects a document's entire type vocabulary, a look-alike identity is a higher-value spoof than a confusable field name. Implementations processing untrusted references SHOULD surface, and MAY reject, two registered identities that differ only in ways the profile does not already forbid — e.g. visually confusable host labels (UTS #39; [TSON-DATA] §9.4).


## 11. Security Considerations

The security considerations of [TSON-DATA] §9 apply; this section adds the schema layer's.


### 11.1 Schema Validation

Documents without `!!schema` carry no type guarantees — only base type resolution applies. Applications processing untrusted TSON input SHOULD validate against a schema before use.


### 11.2 External References

Schema URLs are logical identifiers resolved through the schema library (§10), not fetch instructions; no network access occurs unless the application explicitly enables it. Implementations that support optional fetching MUST treat it as opt-in, disabled by default, and when enabled SHOULD enforce:

- **Transport security.** Fetched references MUST use an authenticated, encrypted transport (in practice, `https`); a fetcher MUST NOT downgrade to a cleartext scheme for an unpinned reference.
- **Allowlists.** Restrict fetchable references to approved hosts or path prefixes, matched on canonical identity (§10.3) so scheme variance cannot evade the list. `!!schema` and `!!meta` references are particularly sensitive: a malicious reference could load an untrusted schema that redefines expected types.
- **Content hash verification.** Require hashes on fetched references and reject mismatches — preventing tampering and silent schema drift.
- **Size limits** on fetched content.
- **No recursive fetching.** A fetched schema's own `!!import` directives MUST NOT trigger further fetches; transitive dependencies must be pre-registered or pre-fetched.
- **Caching.** Cache verified schemas locally, keyed on canonical identity; a verified hash-matching schema is immutable. A failed verification MUST NOT overwrite or poison the canonical-identity entry (§10.2).

Production systems SHOULD pre-register all required schemas at startup and disable runtime fetching entirely.


### 11.3 Directive Security

Directives are a control channel that affects interpretation. Applications processing untrusted input SHOULD restrict which directives are accepted; `!!meta` and `!!import` are particularly sensitive because they select and extend the type vocabulary — production systems MAY restrict which meta and import URLs are permitted. See [TSON-DATA] §9.3.


### 11.4 Name Hygiene at the Schema Layer

[TSON-DATA] §8.2 defines the name-hygiene mechanisms — skeleton distinctness within a scope, `Identifier_Status`, and a restriction level — as policy that every conforming processor implements and enforces by default, never as validity. This section supplies the schema layer's **scopes** and the one rule the scope structure adds.

The named scopes at this layer are: the members of one enum; the field names of one record definition (member labels of its groups included, §5.11), which is the same scope the data layer applies to a record, a field name being an identifier at every layer ([TSON-DATA] §2.5); the declared names of one schema; and **the merged namespace at `!!import`** — the sharpest, because §2.2.3 decides collisions by exact identity, which a confusable pair passes by construction: two entries a reviewer reads as one name are, to the resolver, two names. Choice variants are not a scope: a variant is a reference to a declared name, so two confusable variants are two confusable entries in the namespace and are reported there. A template's parameter list is not a scope either (§5.10): one author writes it whole on one line, and the list stays short so that it stays reviewable. Mechanisms 2 and 3 are per-name and reach every identifier position without a scope ([TSON-DATA] §8.2); only mechanism 1 needs the list above.

Because identity selects a document's entire type vocabulary, the import merge is exactly where the compositional hazard bites — two independently published, independently pinned schemas, each clean alone, can collide on a skeleton when one imports the other (`list_item` beside `Iist_item`), and the fix is a rename in a document the importing author may neither control nor republish. That is the strongest single reason the mechanism is policy rather than validity: as a validity rule it would make such an import impossible to write; as a policy rule the operator relaxes the check, visibly and in code, and proceeds. A refusal at the merge is reported at the second arrival with both declaring schemas named, in the manner of §2.2.3's collision diagnostic, as [TSON-DATA] §8.1's fifth outcome — in the same report as the four categories, distinguishable from them — and the report carries the UCD version and the identifier and token policies it was judged under ([TSON-DATA] §8.2). The policy is not a property of a schema and no schema carries one: the merged namespace is what the check runs over, and only the importing site knows it.

Data documents governed by a schema need no scope of their own: a data field name is valid only if it matches a declared one, so it inherits the declaration's verdict. The confusable-identity concern for schema references themselves is §10.3's.


### 11.5 Resource Limits for Schemas

[TSON-DATA] §9.1 bounds what a processor will spend on a *document* under one limits policy — every limit with a default, configurable or documented, its threshold reported on refusal, and a refusal that is not a verdict. A schema is untrusted input too wherever it is accepted over the wire or reached through `!!import`, and a schema document is a document, so §9.1's counters apply to it as written. What §9.1 cannot see is the work resolution adds, and this section names those limits on the same terms, as part of the same policy and reported through the same surfaces:

| Limit | Applies to | Default |
|---|---|---|
| import closure | schema documents reachable from one header through `!!meta` and `!!import` (§3.4.1) | 64 |
| entries | declarations in one schema map, synthetic and instantiation entries included | 65,536 |
| reference chain | hops from a use site to a terminal entry (§8.3) | 64 |
| supertype chain | length of one entry's transitive `supertypes` (§8.1) | 64 |
| materialisation depth | nested open synthetics closed for one application (§5.10, §8.2) | 64 |

Exceeding one is a limit refusal, not a resolver error: the schema may be well-formed, load cleanly on the next processor along, and be refused here because *this* deployment declined to spend the resources, and the diagnostic names the limit and the configured threshold. The defaults are set at the tight end on [TSON-DATA] §9.1's argument — a schema that fits them loads everywhere — and a deployment raises them. The `!!schema` scope pushes a data document can provoke are counted on the document side, under §9.1's foreign-schemas limit.


## 12. ABNF: The Schema Grammar

This section defines the schema grammar — the schema document's body grammar, the second of the two body grammars behind the shared document header ([TSON-DATA] §2.2, §7.4). The lexer is unchanged ([TSON-DATA] §7.3); the productions below consume the same token stream, and the reserved special tokens of [TSON-DATA] §7.2.5 receive their meaning here.


### 12.1 The Schema Grammar

The schema-document header is defined entirely by [TSON-DATA]'s grammar; this document defines the schema body: `schema-map`, the annotated, braced declaration map that [TSON-DATA]'s `schema-doc` production delegates here. `ws`, `ws1`, `separator`, `token`, `unquoted-token`, `absent`, `annotation`, `field-name`, `record`, `empty-brace`, `identifier`, and `core-value` are imported from [TSON-DATA] §7.3, §7.4, and §7.7; the data grammar's value productions appear at exactly two points — the full `core-value` as the constructor-application payload (`instance`, §5.6), and its braced subset (`record` / `empty-brace`) as the atom-refinement body (`atom-refinement`, §5.5), the same text under two heads, which is the desugar §5.6 states. No production of this grammar uses the full `data-value`: a record-refinement body is a braced `record-def` (§5.7), and a field-modifier value is restricted to a bare token or the absent sentinel (§5.2), never annotations, a type-ref, or a container.

A `schema-map` copies the shape of [TSON-DATA]'s `map` production but requires at least one entry — `{}` at schema-body position is a parse error. An entry is called a **declaration**. Annotations before the opening brace bind to the schema; annotations at the head of an entry bind to the key; annotations after `=>` bind to the type definition (§2.1, §6).

```
; ── Schema Map (schema body) ──────────────────────────────

schema-map       = *( annotation ws ) "{" ws schema-map-entry
                   *( separator schema-map-entry ) [ ws "," ] ws "}"
                 ; a comma may follow a value ([TSON-DATA] §2.4);
                 ; every list in this grammar admits one after its
                 ; last element

schema-map-entry = *( annotation ws ) type-name ws "=>" ws
                   *( annotation ws ) type-def

; ── Type Definition (declaration right-hand side) ─────────

type-def = atom-refinement                ; never parameterised
         / instance
         / [type-params] structural-def
         / [type-params] type-ref
         ; there is no constructor marker: an entry is a
         ; constructor by being IS-A `top` (§4.2), and `~` is
         ; a special token with no role at type-def position

type-params = "<" ws param-name *( separator param-name ) [ ws "," ] ws ">"
param-name  = type-name   ; same lexical class as type-name

structural-def = refined-def
               / construction-def
               / record-def

refined-def  = type-name [ws "<" type-args ">"] ws "^" ws record-def
             ; record refinement, and constructor refinement in
             ; a meta-schema (§5.7, §4.2); the optional <type-args>
             ; head serves user-template heads only (§5.7). No
             ; removal clause.

construction-def = supertype-ref 1*(ws "&" ws supertype-ref)
                   [ws record-def] [ws removal-set]
                 / supertype-ref ws "&" ws record-def [ws removal-set]
                 / supertype-ref ws removal-set

supertype-ref = type-name [ws "<" type-args ">"]
              ; composition and subtraction operands are named
              ; type references, optionally with arguments —
              ; never paren, bracket, or map forms (§4.3, §5.8)

removal-set  = "-" ws "{" ws field-name
               *( separator field-name ) [ ws "," ] ws "}"

record-def   = "{" ws [record-entry *(separator record-entry)
               [ ws "," ]] ws "}"
record-entry = field-def / group-def

atom-refinement = "!" type-name ws "^" ws ( record / empty-brace )
                ; atom refinement (§5.5): the constructor's own
                ; constraint bindings — the braced subset of the
                ; core-value payload `instance` takes, read by the
                ; same data grammar; the target MUST resolve to an
                ; atom-family instance (§3.3.1)

instance     = [type-params ws] "!" type-name ws core-value
             ; constructor application (§5.5): the target MUST
             ; resolve to an entry that IS-A `top` (§3.3.1), the
             ; kernel's `reference` included (§5.10). The payload
             ; is a core value — braced bindings or the positional
             ; form (§5.6) — never annotations or directives. With a
             ; parameter list the body is held unread until
             ; materialisation (§5.10); open and closed share one
             ; production and one payload grammar. What no payload
             ; can spell is an application: `box<text>` is schema
             ; grammar, and inside a `!C` payload an application is
             ; written in `type_ref`'s own record form,
             ; `{ name: box  arguments: [ { name: text } ] }`.

; ── Field Definitions ─────────────────────────────────────

field-def      = *annotation field-name ws ":" ws
                 ( field-type field-modifier
                 / field-type
                 / field-modifier )

field-type     = type-ref ["?"]

field-modifier = ws ("~" / "=") ws ( token / absent )
               ; the value is a single scalar token or the
               ; absent sentinel — never a compound value (§5.2)

; ── Field Groups (§5.11) ──────────────────────────────────

group-def    = *annotation "(" ws group-member
               1*( ws "|" ws group-member ) ws ")" ["?"]
group-member = *annotation field-name ws ":" ws type-ref
               ; no "?", no modifier on members

; ── Type References (any type position) ───────────────────

type-ref = paren-type
         / bracket-type
         / map-type
         / type-name "<" type-args ">"
         / type-name

; ── Compound Type Expressions ─────────────────────────────

paren-type = "(" type-ref "|" type-ref *("|" type-ref) ")"   ; choice, 2+ variants

bracket-type = "[" element-type [ ws ";" ws size-spec ] ws "]"           ; array
             / "[" element-type 1*(separator element-type) "]"           ; tuple

map-type     = "{" ws map-key ws "=>" ws element-type
               [ ws ";" ws size-spec ] ws "}"                            ; map sugar (§5.3)

map-key      = type-name [ "<" type-args ">" ]
             ; a simple ref, optionally with arguments — never
             ; paren, bracket, or map forms (§5.3)

element-type = type-ref [ "?" ]
             ; nesting is this recursion: [[T; N]; N] and
             ; {text => [order; 1..]} work by the same rule
             ; that makes [[T]] work

size-spec    = size-bound [ ws ".." ws [ size-bound ] ]
             / ".." ws size-bound
             ; a size-bound is decimal-natural or, within a
             ; template body, a value-parameter name (§5.3)

; One production per container, at every position: an
; element's "?" belongs to element-type inside the brackets,
; and a field's own "?" to field-type, so "xs: [T?]?" is
; unambiguous — element optional, field optional.

; ── Terminals ─────────────────────────────────────────────

type-args  = type-arg *(separator type-arg) [ ws "," ]
           ; separator = ws "," ws / ws1; a trailing comma
           ; follows a value and changes nothing
type-arg   = type-ref / value-literal
value-literal = token
           ; a single scalar lexeme: a number, quoted string,
           ; or other non-name token. An unquoted token that
           ; satisfies identifier (`true` and `false` included)
           ; parses as a type-ref; it is substituted as a token and read
           ; by the position it lands in (§5.10), so a name
           ; reaching an enum's member list is a member and
           ; one reaching a type slot must resolve as a type.
size-bound = unquoted-token
           ; text MUST match the decimal-natural production
           ; of [TSON-DATA] §7.6 or, within a template body,
           ; be a value-parameter name (§5.3)
type-name  = identifier
           ; [TSON-DATA] §7.7: an unquoted token whose decoded
           ; text matches the identifier grammar — XID_Start-
           ; initial, so no name begins with a digit, a sign, or
           ; a dot, which is what makes numbers undeclarable
           ; (param-name shares the rule, as do every referenced
           ; name and every `!` head). A token in name position
           ; that fails the grammar is a parse error. field-name
           ; is an identifier position at every layer ([TSON-DATA]
           ; §2.5): it admits a quoted spelling, and its decoded,
           ; NFC-normalised text — in field-def, group-member and
           ; removal-set as in data — MUST match identifier; failure
           ; is a parse error. The schema grammar and the data
           ; grammar share the production and the rule.
           ; Resolver-materialised instantiation and synthetic
           ; entries (§8.2) carry internal names in this same
           ; class; the resolver keeps them disjoint from
           ; declared names by construction (freshness, §8.2),
           ; and they are unreachable from source because they
           ; do not exist at source level.
```

Notes:

- The `type-params` slot declares type parameters (§5.10); parameters take precedence over schema-namespace lookup, and references to a parameterized type MUST supply matching type arguments.
- `paren-type` produces choice types; choices require at least two variants — `(T)` is a parse error.
- `group-def` produces field groups (§5.11); a group requires at least two members. Inside a record body, `(` at entry position (after any leading annotations) opens a group; `(` after a `field-name ":"` opens a `paren-type`. The two never collide — a group is an entry, a choice is a type-ref. The `?` after the closing `)` sets the group's state; member positions reject `?` and modifiers by grammar.
- The `?` suffix marks field-level, tuple-position-level, array-element-level, or group-level optionality and is valid only in those positions, recording `state: OPTIONAL` on the containing `record_field`, `tuple_element`, `array`, or `field_group`. There is no generic "optional type" in TSON.
- `type-def` reaches the bracket and map forms through `type-ref` like any other position; there is no separate declaration-level container production, and no positional restriction on size specifiers or element/position `?` (§5.3).
- `instance` is decidable on one token after the optional parameter list: `!` opens an `instance`, with or without a preceding `<…>`; `<` only ever starts `type-params`, so consuming it first costs no lookahead. Inside the `!` branch a following `^` separates `atom-refinement` from `instance`; `atom-refinement` admits no parameter list — a parameterised refinement of an atom instance is no form (§5.10), and `<…> ! name ^` is a parse error.
- The trailing record-def in `construction-def` is optional (`customer => address & contact` is valid). When a `{` follows a `&`-chain, it always belongs to the construction's record-def.
- The refined-def target and every `supertype-ref` operand are restricted to a bare type-name, optionally with type-args; inline structural forms cannot precede `^`, `&`, or `-` by grammar (§4.3). A `^` whose resolved target has no refinable body (a choice, for example) is a resolver error reported with the target's kind.
- The removal clause attaches to construction heads only; a refinement head admits none — `T ^ { ... } - { ... }` is a parse error (§5.7, §5.9).
- After a bare type-ref in type-def position, `{` is a parse error; the diagnostic SHOULD suggest `^` (refinement) or `&` (composition).
- Parameters and type arguments inside `<>` alike separate by comma or whitespace — the general separator convention ([TSON-DATA] §7.4): `<T, MIN>` and `<T MIN>`, `map<text, integer>` and `map<text integer>` are all valid.
- `_` is not valid in type-ref or type-def body positions (§7.6); empty records use `{}`.
- A comma may follow the last element of every list in this grammar — declarations, fields, groups, removal sets, parameters, arguments, tuple elements ([TSON-DATA] §2.4); `[text, int32, ]` and `pair<uuid, B, >` are legal. A comma following nothing, or another comma, is a parse error, as in data.


### 12.2 Disambiguation Summary

This section is informative.

```
; schema body (after the header):
;   @              → annotation; before "{" it binds to the schema,
;                    inside the braces to the entry key (name) or,
;                    after "=>", to the type definition
;   {              → schema map opens
;   name =>        → declaration (two-token lookahead)
;   }              → schema map closes; end of document
;   anything else  → parse error
;
; type-def position (after =>):
;   <              → type-params; then dispatch continues:
;     !              → instance, held open (§5.10)
;     otherwise      → templated structural-def / type-ref
;   ! name ^       → atom refinement (§5.5)
;   ! name         → constructor application (§5.5)
;   name ^         → refined-def (§5.7)
;   name &         → construction-def (composition, §5.8)
;   name -         → construction-def (subtraction, §5.9)
;   name {         → parse error (write ^ or &)
;   {              → brace form; consume "{" and dispatch on
;                    content (reusing [TSON-DATA] §2.8's
;                    consume-one-then-inspect machinery):
;     "}"            → empty record ({}, top's shape)
;     "("            → record-def (leading field group)
;     "@"            → record-def (annotations precede field
;                      names, §6; the map sugar admits no
;                      interior annotations, §5.3, so "@"
;                      commits to a record)
;     name ":"       → record-def (field)
;     name "=>"      → map-type
;     name "<"       → map-type (generic key; consume the
;                      arguments, expect "=>")
;     name (other)   → parse error
;   (              → paren-type (choice)
;   [              → bracket-type (array or tuple, full syntax)
;   name ? / name  → type-ref
;
; type-ref position (field types, array elements, etc.):
;   (              → paren-type (choice)
;   [              → bracket-type (full syntax at every position)
;   {              → map-type: "{" name … "=>" required;
;                    "{" name ":" remains a parse error — bare
;                    records must be declared (§5.2); the
;                    diagnostic SHOULD say so and distinguish
;                    the two brace meanings
;   name <         → generic
;   name ? / name  → simple ref
;
; record-def entry position (after leading annotations):
;   (              → group-def (field group, §5.11)
;   name ":"       → field-def
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

Each case in the type-def block is decided by at most two tokens of lookahead at the start of the production — the brace form by one consumed token plus one token of lookahead, the same budget in the same sense as the data grammar's §2.8 dispatch; inside a bracket form, the choice between tuple, sized array, and unconstrained array is made by one-token lookahead after the complete preceding element type. A `field-type` can itself be nested (`(email | [phone])?`) and parses without backtracking via the disambiguation above; the outer `?` there is field optionality (§5.2). The schema body requires at most two tokens of lookahead to detect a declaration boundary. The parser never backtracks at any level.


### 12.3 Adjacency Rules

The following rows extend the adjacency table of [TSON-DATA] §7.5 for the operators of the type-definition grammar; as there, the rules are enforced by the parser via source-position comparison.

| Operator | Type | Context | Rule |
|---|---|---|---|
| `!` | prefix | type-def body (constructor application, atom refinement) | MUST be adjacent to the following unquoted-token (constructor or instance name) |
| `?` | suffix | field type, tuple position, array/map element (element-type, §5.3), field group | MUST be adjacent to the preceding token (type name or closing bracket/paren) |
| `&` | binary | composition | whitespace on either side optional |
| `^` | binary | refinement (§5.5, §5.7) | whitespace on either side optional |
| `-` | prefix | removal clause (§5.9) | at least one whitespace character MUST separate the preceding token from `-`; whitespace optional before the following `{` |
| `~` | modifier | default value | whitespace optional |
| `=` | modifier | fixed value | whitespace optional |
| `\|` | separator | choice variant; field-group member | whitespace optional |
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
| RFC 4291 | IP Version 6 Addressing Architecture | https://www.rfc-editor.org/rfc/rfc4291 |
| RFC 4632 | Classless Inter-domain Routing (CIDR) | https://www.rfc-editor.org/rfc/rfc4632 |
| RFC 4648 | The Base16, Base32, and Base64 Data Encodings (the text spellings of `bytes`, §5.5) | https://www.rfc-editor.org/rfc/rfc4648 |
| RFC 5322 | Internet Message Format (email address syntax) | https://www.rfc-editor.org/rfc/rfc5322 |
| RFC 9485 | I-Regexp: An Interoperable Regular Expression Format | https://www.rfc-editor.org/rfc/rfc9485 |
| RFC 9542 | IANA Considerations and IETF Protocol and Documentation Usage for IEEE 802 Parameters (EUI-48) | https://www.rfc-editor.org/rfc/rfc9542 |
| RFC 9562 | Universally Unique IDentifiers (UUIDs) | https://www.rfc-editor.org/rfc/rfc9562 |
| UTS #39 | Unicode Security Mechanisms (name hygiene, §11.4; [TSON-DATA] §7.7, §8.2) | https://www.unicode.org/reports/tr39/ |
| UCD | Unicode Character Database (the data files behind [TSON-DATA] §8.2, and the version a refusal reports) | https://www.unicode.org/ucd/ |
| IEEE 754-2019 | Standard for Floating-Point Arithmetic | https://ieeexplore.ieee.org/document/8766229 |

### 13.2 Series References

| Reference | Title | URL |
|-----------|-------|-----|
| TSON-DATA | TSON Part 1: Text Data Format | https://tson.io/2026/35/tson-part1-data |
| TSON-GUIDE | TSON Developer Guide (non-normative) | https://tson.io/2026/35/tson-guide |
| meta-kernel.tn | TSON Meta-Kernel (companion artifact) | https://tson.io/2026/35/m/meta-kernel.tn?sha256=6991f932be2f61e8e6e59d7f45287e9c50f6c559c4c437163b6eeb1a01d1155f |
| meta.tn | TSON Meta-Schema (companion artifact) | https://tson.io/2026/35/m/meta.tn?sha256=bae2658bbefd1e4ab9b996835d68f5bc8aaaca808c7a253d00398348937f0208 |
| core.tn | TSON Core Type Library (companion artifact) | https://tson.io/2026/35/m/core.tn?sha256=653698705ba6cac7716a00d4717ec5b1cfde68743bbe2e4fc13a18ac1ad20a39 |

### 13.3 Informative References

| Reference | Title | URL |
|-----------|-------|-----|
| RFC 8820 | URI Design and Ownership | https://www.rfc-editor.org/rfc/rfc8820 |
| ISO/IEC 11404:2007 | General Purpose Datatypes | https://www.iso.org/standard/39479.html |
| ISO 8601-1:2019 | Date and time — Representations for information interchange (the informative source behind RFC 3339 Appendix A) | https://www.iso.org/standard/70907.html |
| JSON Schema 2020-12 | JSON Schema: A Media Type for Describing JSON Documents | https://json-schema.org/specification |
| RFC 5646 | Tags for Identifying Languages (BCP 47) | https://www.rfc-editor.org/rfc/rfc5646 |
| W3C XSD Part 2 | XML Schema Part 2: Datatypes Second Edition | https://www.w3.org/TR/xmlschema-2/ |
| Resolver Output Fixtures | meta-kernel-resolved.tn, meta-resolved.tn, core-resolved.tn (non-normative) | &lt;published alongside this document&gt; |

