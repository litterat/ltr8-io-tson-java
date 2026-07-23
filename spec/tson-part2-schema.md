# TSON Part 2: Type System and Schema

## 2026 Revision 32

**Status:** Working revision. The 2026 revision series is subject to change without compatibility guarantees. When finalised, this specification will be published as **TSON version 1** and frozen; until then, revisions are released under the 2026 series.

**Series:** TSON Specification, Part 2 of 2

**Copyright:** © 2026 Litterat Pty Ltd. This document is licensed under the Creative Commons Attribution-ShareAlike 4.0 International License (CC BY-SA 4.0): https://creativecommons.org/licenses/by-sa/4.0/


## 1. Introduction

TSON is a schema system, and this document is its centre: a type system of immutable, hash-pinned schemas whose definitions are themselves data, resolving down a verified chain — document → schema → meta-schema → kernel — so that one hash authenticates a document together with its entire contract. The text format of [TSON-DATA] is this system's notation and its reference encoding: schemas are written in it, resolver output is serialized in it, and §7 states its encoding rules — the first encoding to carry the type system's values, with others defined against the same encoding-independent model.

This document defines the TSON **type system and schema layer**: the schema grammar and its type-definition forms, the type system and its operations, the schema chain and its resolution model, the resolver output contract, the text encoding rules, and the schema-layer directive operations.

[TSON-DATA] defines the lexer, the data grammar, base type resolution, and the built-in type vocabulary. This document introduces no lexical changes: a schema document is parsed by a second body grammar over the same frozen lexer, selected by the document header ([TSON-DATA] §2.2), and every operator it uses is a token that lexer already emits — the reserved special tokens of [TSON-DATA] §7.2.5 receive their meaning here. The schema grammar imports [TSON-DATA]'s `data-value` production at exactly three points — constructor-application values and atom-refinement values (§5.5), and field-modifier values (§5.2) — and the coupling is one-directional: nothing in the data grammar depends on this document.


### 1.1 The TSON Specification Series

- **Part 1: Text Data Format** [TSON-DATA] — the notation and reference encoding: the lexer, the data grammar, base type resolution, and the built-in type vocabulary.
- **Part 2: Type System and Schema** (this document) — the centre of the series: the schema grammar, the type system, the schema chain, and the operations of the `schema`, `meta`, and `import` directives.

[TSON-DATA] §1.3 gives the series architecture: the schema chain — the type system — on one axis, and the notation and encodings that carry it on the other.


### 1.2 Design Principles

In addition to the principles of [TSON-DATA] §1.2:

1. **Schema-value separation** — A document never references its own definitions via type annotations. Every type reference (`!name`) resolves against an external schema identified by the document's `!!schema` directive (§3).

2. **Purpose-built representation** — A schema document shares the data format's lexicon — the frozen lexer, its tokens, annotations, and directives — but has its own body grammar, selected by the document header (§12.1). The semantics remain shared: a schema document resolves to an ordinary TSON value (a schema value, §2.1), and resolver output is an ordinary data document (§8).

3. **Permanent stability** — The schema grammar, the meta-schema, the core type library, and the resolver output contract are frozen once published, on the same terms as [TSON-DATA] §1.2 principle 7. New types are added through new type libraries, not through changes to this document.


### 1.3 Conformance

[TSON-DATA] §1.5 defines the series' two conformance classes. **Class 1** (data-format processor) is defined there in full; its sole schema-layer obligation is to reject schema documents with a categorized diagnostic. This document defines **Class 2**.

A **Class 2 processor** (schema-aware processor) conforms to [TSON-DATA] and additionally implements the schema grammar (§4–§5, §12), the directive operations (§2.2), name resolution and the schema library (§3, §10), schema compilation and resolver output (§8), atom token parsing (§7.4), and validation. Such a processor:

- MUST pre-load the meta-kernel and meta-schema (§3.4, §10.1) and SHOULD pre-load the core type library;
- MUST resolve type annotations through the active schema when one is in scope, and MUST NOT apply the [TSON-DATA] §5 built-in vocabulary in schema scope (§7.2);
- MUST produce, for every valid schema document, a resolved schema value conforming to the `type_definition` contract (§8) — `subtypes` computed, `supertypes` computed from source (§8.1). Serializing the resolved schema value as a data document is OPTIONAL; output, when produced, MUST conform to §8's serialization contract;
- MAY implement ingest of resolver output (§8, §10.1); an implementation that does MUST apply §8.1's derived-field treatment and §10.1's source rules;
- MUST enforce the identity-agreement rule (§10.1) and verify hash-pinned references per [TSON-DATA] §2.2.1 (§10.2);
- MUST report errors in the categories and phrasings of [TSON-DATA] §8.1.


### 1.4 Terminology

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in RFC 2119.

- A **schema document** is the document kind whose header carries `!!meta` ([TSON-DATA] §2.2) and whose body is a schema map (§2.1, §12.1) — the source artifact that is authored, published, hash-pinned, and resolved.
- A **schema** is what a schema document defines: a named, immutable collection of type declarations, identified by URL and referenced by the `!!schema`, `!!meta`, and `!!import` directives.
- A **schema value** is the resolved form of a schema: a value of the kernel's `schema` type, `map<type_name, type_definition>` (§9), produced by resolution and optionally serialized as a data document (§8). Schema values are derived artifacts, never schema sources (§10.1).
- A **meta-schema** is a schema in its governing role — the target of a `!!meta` directive. `meta.tn1` is the canonical meta-schema (§9); the meta-kernel is the root of the governing chain (§3.4).
- The **schema grammar** is the body grammar of schema documents (§12.1); the **type-definition grammar** is its declaration right-hand side — the `type-def` production that each `name => type-def` declaration activates.


### 1.5 Companion Artifacts

This document is published with six companion artifacts. The normative artifacts are pinned by content hash at publication. Per §3.4, implementations pre-load the kernel and meta-schema as in-memory structures; the artifact documents are descriptions of those structures, and the in-memory model is authoritative.

| Artifact | Status | Content |
|----------|--------|---------|
| `meta-kernel.tn1` | Normative | The self-referencing bootstrap layer (§9) |
| `meta.tn1` | Normative | The canonical meta-schema (§9) |
| `core.tn1` | Normative | The core type library (§9, [TSON-DATA] §5) |
| `meta-kernel-resolved.tn1` | Non-normative | Resolver-output fixture for the meta-kernel (§8) |
| `meta-resolved.tn1` | Non-normative | Resolver-output fixture for the meta-schema (§8) |
| `core-resolved.tn1` | Non-normative | Resolver-output fixture for the core type library (§8) |


### 1.6 A Complete Example

A schema document declaring three types, governed by the canonical meta-schema and importing the core type library:

```
!!id:"https://example.com/task.tn1"
!!meta:"https://tson.io/2026/32/m/meta.tn1"
!!import:"https://tson.io/2026/32/m/core.tn1"
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

`priority` refines core's `integer` instance (§5.5); `status` applies the `enum` constructor, reached through the structure namespace supplied by the `!!meta` target (§3.3.1); `task` is a fresh record whose field types resolve through the type-name namespace (§3.3.2). `flagged` is a **template** with a type parameter and a value parameter (§5.10): a fresh record whose `priority` field is *defaulted by parameter* (`~ N`, §5.7), built entirely from names in the schema's own namespace — templates that derive from kernel constructors like `array<T>` belong to layers where those names resolve (§3.3.2, §5.3). `flagged<status, 2>` is its fully-bound application, which the resolver materialises once per distinct application (§8.2); `history` wraps it in the plain array sugar. A data document binds the schema with `!!schema` (§7.1) and instantiates its types:

```
!!schema:"https://example.com/task.tn1"
!task {
  id:       550e8400-e29b-41d4-a716-446655440000
  title:    "Ship revision 32"
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
!!id:"https://example.com/people.tn1"
!!meta:"https://tson.io/2026/32/m/meta.tn1"
!!import:"https://tson.io/2026/32/m/core.tn1"
@doc:"Minimal example schema."
{
  person => { name: text  age: integer }
  employee => person & { department: text }
  status => !enum [ACTIVE INACTIVE SUSPENDED]
}
```

Entries are separated like data-map entries — whitespace or a comma ([TSON-DATA] §2.4) — and the map MUST contain at least one entry (§12.1). Each declaration binds a type name to a type definition with the `=>` operator — a compound token the frozen lexer already emits; the schema grammar introduces no reserved words — its operators are the reserved special tokens of the shared lexer ([TSON-DATA] §7.2.5).

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

User schemas normally chain to `meta.tn1`. Chaining to `meta-kernel.tn1` directly is a meta-programming case — an alternative type vocabulary replacing meta, or an extension of the meta layer itself (§9). The meta layer is the format's sanctioned extension point: new type vocabularies arrive as alternative or extended meta-schemas chaining to the kernel, never as grammar changes.


#### 2.2.3 The `!!import` Directive

`!!import` imports type entries from an external schema into the importing schema. The directive value is a URL string identifying a published schema. The directive loads the referenced schema and makes its locally-declared entries available as if they were declared in the importing schema: imported entries are available to all local declarations (including for recursive references), and local declarations may refine or compose with imported types.

**Imports are shallow.** Only the entries declared in the imported schema's own body are imported — entries the imported schema itself brought in via its own `!!import` directives are not transitively included. Each schema MUST explicitly import all the dependencies it needs.

Multiple `!!import` directives are permitted and are loaded in declaration order. If two imports declare the same name, or if an import declares a name that also appears as a local declaration, the collision is a resolver error and the entire schema fails to load. Imports populate only the type-name namespace: an import name that happens to match a name in the structure namespace (§3.3.1) is not a collision — the two are consulted at different grammar positions, and the `!T` lookup order resolves the one position where both could apply.

**Merged entries keep their home namespace.** An imported entry's internal references — reference targets, field types, `source` applications — are names in its *defining* schema's namespace, fixed when that schema was resolved; the merge does not re-resolve them against the importer. If Y declares `y_id => uuid` through its own import of core, then X, importing Y but not core, receives `y_id` still bound to core's `uuid` — even where X locally declares an unrelated `uuid` (no collision arises: core's name was never merged into X). Operationally, the library holds one resolved namespace per schema, a bare name is meaningful only within the output of the schema that wrote it, and a consumer following an imported entry's references follows them in the defining schema's resolution (§3.4.1).

**Import references MUST be acyclic.** A schema's reference closure — every document reachable through `!!meta` and `!!import` — is resolved dependencies-first (§3.4.1), and a cycle admits no such order: an `!!import` reaching, directly or transitively, a schema whose resolution is in progress is a resolver error — *import cycle* — reported with the cycle path. The prohibition costs nothing in practice: a hash-pinned cycle is unauthorable, since each document's pin would have to be computed over bytes that already contain the other's, so a cycle can arise only among unpinned development references. Mutual coupling has sanctioned routes: shared types belong in a third schema both partners import, and data-level coupling to a foreign schema is `extern` (§7.8), which names a schema without loading it into resolution.

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

A data document's `!!schema` names the user schema its type annotations resolve against. A user schema's `!!meta` names the meta-schema, and its `!!import` directives bring in type-library entries. The meta-schema (`meta.tn1`) chains to the meta-kernel together with an `!!import` of the kernel — the import supplies the kernel types meta's own declarations use *and*, because resolution is one hop, places the kernel's structural vocabulary in meta's namespace, where every meta-governed schema finds it (§3.3.1, §9). The meta-kernel's `!!meta` points at its own URL; both meta and the kernel resolve to pre-loaded Schema objects (§3.4).


### 3.2 Schema-Value Separation

A published schema is an immutable artifact: once published at a URL with a content hash, its type definitions do not change (§3.5). Because type references always resolve against an external schema, `!text` in a given document means exactly what the referenced schema defines it to mean — two documents referencing the same schema have identical type vocabularies. A document with no `!!schema` directive has no type vocabulary: base type resolution and the built-in annotations of [TSON-DATA] apply (§7.1).


### 3.3 Schema Layering

The directive architecture separates three layers: the **meta-schema** defines the structural vocabulary the type-definition grammar produces (`type_definition`, `record`, `record_field`, `array`, `enum`, …); **type libraries** define specific types (`integer`, `text`, `uuid`, …) using that vocabulary — type libraries are ordinary schemas; **application schemas** import type libraries and define domain types on top of them.

Name resolution is built from one primitive. The **namespace of a schema** is its local declarations plus the entries of its imports (§2.2.3) — nothing more. Every document has a **governing target**: a schema document's is its `!!meta` target; a data document's is its `!!schema` target. All resolution against the governing target is **one hop**: the target's namespace is consulted directly, and no further rung of the ladder is ever walked. Two namespaces are active when a schema document resolves, consulted at different grammar positions.


#### 3.3.1 The Structure Namespace

The **structure namespace** of a schema document is the namespace of its `!!meta` target — the target's local declarations plus its imports. One hop. It is consulted at exactly the **constructor roles**:

- **Constructor-application targets** — the name after `!` when no `^` follows (`!enum [...]`, `!integer_type {}`), subject to the lookup rule below.
- **Generic-application heads** — the name before `<` when the name is not otherwise in scope (`map<text, text>`, `set<text>`).
- **The implicit desugar targets of the sugar forms** — `[T]` desugars to `array`, the sized forms to the size-refinement templates (§5.3), `[T, U]` to `tuple`, `(A | B)` to `choice` (§5.6).

An entry consumed at an *author-written* constructor role MUST be a constructor (`constructor: true`); resolving a non-constructor there is a resolver error. The implicit desugar targets are grammar-supplied and exempt from the gate — the size sugar reaches the non-constructor size templates precisely because ordinary schemas cannot name them directly (§5.3). The structure namespace is never consulted for bare type-refs — a schema governed by meta cannot write `field: enum` or reach the kernel's `integer` as a field type (§3.3.2).

**Lookup for `!` targets.** The form after `!` declares its own intent, so no lookup-order tie-break is needed. Without `^`, `!C value` is **constructor application** (§5.5): `C` resolves first against the type-name namespace (the meta-kernel's self-hosted case, where its constructors are its own locals) and then against the structure namespace, and the resolved entry MUST be a constructor (`constructor: true`) — resolving an instance here is a resolver error, and the diagnostic SHOULD point at the refinement form (`!name ^ { ... }`). With `^`, `!I ^ { values }` is **atom refinement** (§5.5): `I` resolves against the type-name namespace only and MUST be a non-constructor instance of an atom family; the constructor it desugars to is reached through the instance's own `source` field — never by name, so refinement works even where the constructor is not name-visible. A name found in no permitted namespace is an unresolved-type error.

**Import what you expose.** Because resolution is one hop, a meta-schema's namespace is the *complete* vocabulary it offers everything it governs. A meta-schema MUST therefore import every schema whose entries it intends to expose — this is why `meta.tn1` imports the meta-kernel: the import is the delivery mechanism for the kernel's structural vocabulary (`enum`, the sugar-form targets, `type_definition`, …) to every meta-governed schema and its resolver output.


#### 3.3.2 The Type-Name Namespace

The **type-name namespace** provides the names a schema author can use as type-refs in their own definitions — field types, type arguments, choice variants, composition targets, and refinement sources. Lookup walks, in order:

1. Parameters of the enclosing definition (§5.10).
2. Local declarations of the current schema.
3. Entries brought in by `!!import` directives, in declaration order.

The type-name namespace is NOT extended by the structure namespace: names available through `!!meta` are available at constructor roles only, never as type-refs. This is why application schemas import core — the types that fill record fields (`text`, `integer`, `uuid`) must come from the schema's own namespace. Names from `!!import` directives must be disjoint from each other and from local entries (§2.2.3).


#### 3.3.3 Annotation Resolution

An annotation `@name` or `@name:value` resolves against the **governing target's namespace** — the `!!meta` target for a schema document, the `!!schema` target for a data document — one hop, locals plus imports. Neither the local declarations of the document being authored nor any further rung of the ladder participates.

The one-hop rule determines where annotation types must live:

- **Annotations for schema documents** live in the meta layer. `meta.tn1` declares `deprecated`, `since`, `todo`, `lang`, `ordered`, `bounded`, `exact`, `numeric`, and `disjoint` locally, and carries the kernel's `doc`, `documentation`, and `alias` through its kernel import.
- **Annotations for data documents** live in the governing user schema's namespace. Core declares its own `doc`, `documentation`, `annotation`, and `alias` — fresh siblings of the kernel's entries (§3.3.5) — so that data documents governed by core-importing schemas can write `@doc`. An annotation type declared locally in a user schema is usable by that schema's *data documents* — but not within the declaring schema document itself, whose governing target is meta. Custom annotations for schema documents therefore require an extended meta-schema; custom annotations for data documents require only a declaration in the user schema.


#### 3.3.4 Data Documents and Schema Layering

A data document's `!!schema` points to a single user schema, and that schema's namespace — locals plus imports, one hop — is the document's entire vocabulary. Type annotations and annotations resolve against it; nothing else is reachable. If a data document needs a core type like `uuid`, that type must be imported into the user schema.

A consequence: the structural vocabulary is invisible from ordinary data. `!enum`, `!record`, and `!type_definition` are not names in an application schema's namespace, so ordinary data documents cannot express resolved-schema structure; only a data document governed by a meta-schema (resolver output, §8) can, because the meta-schema's namespace carries that vocabulary through its kernel import. This pairs with §10.1's rule that resolved-form documents are never schema sources.


#### 3.3.5 Duplicate Names Across Layers

A type name defined in both the meta-schema and a type library is not a conflict: the two namespaces are consulted at different grammar positions. The kernel's `text` is not visible as a type-ref in a meta-governed schema — only an imported `text` (core's) is, and core's `text` is a fresh definition, not a view of the kernel's. Where both namespaces could apply (the bare-`!` constructor-application position), the lookup rule of §3.3.1 resolves it, with the type-name namespace taking precedence.


### 3.4 The Meta-Schema Bootstrap

The ladder terminates at the meta-kernel, whose `!!meta` references its own URL. The kernel's types are pre-loaded into the schema library by the implementation: they exist as in-memory structures before any document is parsed. When the kernel document is parsed, its type annotations resolve against these pre-loaded structures through the normal library lookup. The meta-schema (`meta.tn1`) is also pre-loaded; its constructors are resolved against the pre-loaded kernel before being registered as pre-loaded entries themselves.

The kernel defines its own core types (`integer`, `text`, `boolean`, …) directly so that its own constraint-field declarations can reference them locally. The kernel's local entries are the structure namespace of every schema the kernel directly governs — meta itself, and any alternative meta-schema; meta-governed schemas receive the same vocabulary one hop away, through meta's kernel import (§3.3.1). These types are NOT automatically available as type-refs in governed schemas (§3.3.2); schemas that want `integer` or `text` as type-refs import a type library that defines them (typically `core.tn1`).

The kernel and meta documents are descriptions of the pre-loaded types, not the source of them. Parsing them validates that the document's description matches the implementation's in-memory model; if they disagree, the document is invalid — the in-memory model is authoritative.


#### 3.4.1 Two-Pass Resolution

Schema resolution proceeds in two passes per schema, with imports fully resolved before either pass runs on the importing schema.

**Pass 1 — Name population.** The resolver collects all declaration names in the schema document. The schema's type-name namespace is populated with skeleton `type_definition` records keyed by name. Bodies are not yet validated.

**Pass 2 — Body resolution and validation.** The resolver resolves each entry's body against the populated namespace. Forward references between local entries work in this pass. The resolver validates that references resolve, that composition and refinement rules hold, that type arguments match parameter arities. It computes the transitive IS-A graph (`type_definition.supertypes`) and derives the inverse (`type_definition.subtypes`).

**Imports run first.** Resolution is defined over the schema's **reference closure**: the set of schema documents reachable through `!!meta` and `!!import` references, collected transitively. The order is:

1. **Collect the closure.** Starting from the schema under resolution, follow every `!!meta` and `!!import` reference, deduplicating by canonical identity ([TSON-DATA] §2.2.1): each canonical identity names one schema, loaded once, however many references reach it.
2. **Verify per identity.** Verification attaches to canonical identities, not to individual references: for each identity in the closure, collect the set of digests declared across all references reaching it. An empty set resolves without verification. A single digest is verified against the identity's content per §10.2 — once, at this step, before any schema in the closure resolves — and every reference to the identity, pinned and plain alike, thereafter resolves to the verified instance; a verification failure is a resolver error for the identity across the whole resolution, and no reference falls back to the rejected content. Two or more distinct digests for one identity is a resolver error (§10.2) — at most one can describe the real bytes.
3. **Order the closure.** The reference relation over the deduplicated closure MUST be acyclic (§2.2.3); the resolver computes a topological order — dependencies first — and reports the cycle path as a resolver error where none exists.
4. **Resolve in order.** Each schema in the closure is resolved exactly once, in topological order, by its own two passes; when a schema's turn arrives, every schema it references is already resolved. For each schema: merge each `!!import`'s local entries, in declaration order, into the accumulating type-name namespace — merged entries stay bound to their defining schema's namespace (§2.2.3), and collisions between imports are resolver errors (§2.2.3); then Pass 1 collects local entry names, a collision between a local name and an already-merged import being a resolver error; then Pass 2 resolves bodies against the populated namespace.

Pre-loaded schemas (the kernel and meta, §3.4) enter the closure already resolved and occupy the leaves of every order. Collection follows references only into schemas not yet resolved: a reference to an already-resolved schema terminates there — which is how the kernel's self-referencing `!!meta` grounds the ladder (§3.4) rather than forming a cycle. Digest sets (step 2) are collected over the references observed during this collection: a terminating reference still contributes its digest to its target identity's set, while an already-resolved schema contributes no outgoing references — its dependencies were verified when it was resolved (or shipped, for pre-loaded entries, whose pins are checked against the implementation-held digest per §10.2). Whether a dependency happens to be pre-loaded therefore never changes whether a closure loads.

Forward references are permitted within a schema: a definition may reference any other declaration in the same schema, declared earlier or later. Annotations resolve against the governing target's namespace (§3.3.3), not through the resolving schema's own Pass 1 namespace or imports.


### 3.5 Schema Evolution

Each published schema version is immutable; a schema's identity is its exact byte content. Version N and version N+1 are independent artifacts with different content hashes and different URLs. TSON defines no version negotiation, migration rules, or compatibility checks; accepting data validated against multiple schema versions is a deployment concern. See §7.2 for the records-are-closed rule.


## 4. The Type System

The type system is defined by the meta-kernel's vocabulary — the base kinds, the constructors, and the `type_definition` record — and every construct of the type-definition grammar (§5) is notation over it: each declaration form resolves to a `type_definition` whose body is the canonical constructor form `!C { bindings }` (§5.6, §8).


### 4.1 Kinds

The kernel defines `top` as the structural root and three **base kinds** — `atom`, `product`, `sum` — each composing with `top` via `top & {}`. Every type constructor in the kernel and in meta composes, directly or through another constructor, with one base kind, and each base kind IS-A `top` — so every constructor transitively IS-A `top`. IS-A does not extend below construction: `!T {}` transfers kind, not supertypes (§5.5), so constructor instances (`integer`, `value`, `unknown`) and fresh records (`person`) carry empty supertype chains and are not IS-A `top`. What is universal is *kind* membership — every type has exactly one of the four kinds:

- **Atom** — scalar types. An atom constructor composes with `atom` via `~atom & {...}`; its record of constraint fields describes the narrowable vocabulary available to instances. The kernel and meta define the series' atom constructors (§9); the `unit` constructor is the atom with no constraint vocabulary. Atom instances are produced by constructor application — `!<ctor> {}` (empty) or `!<ctor> { values }` — and refined with `!<instance> ^ { values }` (§5.5).
- **Product** — structural types. `record`, `array`, `set`, `map`, and `tuple` compose with `product`, fixing `access_pattern` and `size_type`; the parameterized constructors declare type slots in `<>` parameter lists. Bare `{...}` definitions without explicit composition resolve to `kind: PRODUCT` by structural default.
- **Sum** — discriminated-union types. `choice` in the kernel, `extern` and `unknown_type` in meta, compose with `sum`. `unknown` in core is `!unknown_type {}` — the empty instance accepting any well-formed value of any type.
- **Reference** — a type definition whose body is a pointer to another type: a `kind: REFERENCE` entry with body `!reference { target: T }`, where `target` names an entry (§8.3); when the reference was written as an application, the applied form is recorded in the entry's `source` (§8.1). References are aliasing relationships, not IS-A; the resolver flattens every use to the target and attaches `@alias` (§8.3).


### 4.2 Type Construction

Type constructors are factories that produce type definitions. Within the type-definition grammar, the `~` marker prefix declares a constructor; it sets `constructor: true` in resolver output. There is no construction in data: `!C { bindings }` produces a new type only in the schema grammar (§7.2). Constructors may declare type parameters (`<T>`, `<K, V>`) immediately after `=>`; references to a parameterized constructor MUST supply matching type arguments (§5.10). A parameterized constructor's open slots are ordinary fields of its vocabulary, typed `type_ref` and routed by parameter — `element_type: type_ref = T` — so the slot's *type* is fixed (a type slot always holds a reference) while the parameter rides the value channel (§5.10). The parametric spelling does not fix the field — nothing is fixed at declaration; it routes the application's argument into it, leaving the field REQUIRED in the open vocabulary (§5.7). A closed application therefore supplies the bound reference as the field's value, the way data supplies any required field, and its binding record is ordinary data of the constructor's vocabulary (§7.2).

The `~` character has two uses, disambiguated entirely by position: a **default-value modifier** in a field definition after a type-ref (`port: integer ~ 8080`, §5.2), and the **constructor marker** at the start of a type-def body, with or without a preceding parameter list (`~product & { ... }`, `<T> ~array<T> ^ { ... }`). The second use covers both composing a new constructor with a base kind and refining an existing constructor (as `set` refines `array`); constructor refinement is a meta-level operation and, unlike regular refinement, MAY replace fixed values.

**Constraint-vocabulary atom pairs.** Atom families whose instances can be narrowed with constraint values are defined as pairs: a constructor carrying the constraint vocabulary (`~atom & {...}` listing the family's narrowable fields) and a canonical empty instance (`!<constructor> {}`) that records `source: <constructor>` but establishes no IS-A. Refinements use the `^` operator — `age => !integer ^ { min: 0  max: 150 }` — which DOES establish IS-A with the refined instance (§5.5). Spec-bound constructors additionally compose with the kernel's `atom_specification` mixin and pin their `spec` field. §9 inventories the families.

**The `unit` atom constructor.** Atoms with no constraint vocabulary are constructed from `unit`. Its instances are opaque atoms distinguished by name and prose-level parsing contract (§7.4). The kernel defines three:

- `value` — admits the products of [TSON-DATA] §4 base type resolution (null, boolean, integer, float, string). The escape hatch for fields whose type the schema language cannot express (§7.4).
- `token` — admits NFC-canonical lexemes. Used for identifier types (`type_name`, `field_name`, `param_name`) and enum members (§7.4).
- `void` — the unit type of absence: its canonical value is the absent sentinel `_` (the token `null` is accepted at `void`-typed positions as an equivalent spelling, normalised to `_`; §7.3). `void` is the target type for bare annotations (§6) and usable in data as a field type or choice variant meaning "no value".

Core declares its own `void` under the same name — a fresh sibling (§3.3.5): the same `!unit {}` construction and the same contract, a distinct type entity — so that data documents governed by core-importing schemas can target it (§9, §7.3). User schemas SHOULD NOT introduce additional unit instances without a documented parsing contract.


### 4.3 Operations

TSON's type operations fall into two families. **Construction** operations compute a new field set and declare their own contract: a bare record claims none, constructor application transfers kind only, composition grants IS-A per parent, and subtraction revokes it while keeping lineage. **Refinement** inherits an existing contract and tightens within it, always preserving IS-A. Each operation is defined with its grammar form in §5:

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
set     => <T> ~array<T> ^ { unordered: = true }           ; constructor definition (§4.2)
id      => uuid                                            ; type reference (§8.3)
scores  => [integer; 1..]                                  ; array type (§5.3)
point   => [number, number]                                ; tuple type (§5.3)
contact_method => (email | phone | address)                ; choice type (§5.4)
translations   => map<text, text>                          ; constructor application, generic syntax (§5.3)
```


### 5.2 Field States

Each field in a record definition has a state determined by two independent axes: **presence** (required vs optional) and **mutability** (free, default, or fixed). A record body may also contain **field groups** — sets of mutually exclusive labelled fields — defined in §5.11.

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

Value modifiers are restricted to scalar tokens — quoted or unquoted — covering strings, numbers, booleans, and null; complex modifier values (arrays, records, maps) are not supported in v1 (§12.1). The `=` modifier additionally accepts the absent sentinel `_`, valid only when the field is OPTIONAL (declared with `?` or inherited as OPTIONAL): `field: type? = _` produces OPTIONAL_FIXED with an absent fixed value — the field MUST either be omitted or be the absent sentinel in conforming data; any other value is a validation error. The following are resolver errors:

- `~ _` (any field) — a required field cannot fall back to not-being-filled.
- `= _` on a REQUIRED field — a field cannot be required and fixed to not-being-present.
- `type? ~ value` — a default implies the field is always present, contradicting optional semantics. Use `type ~ value` for a fallback, `type?` for absence, `type? = value` for present-implies-value.

**Eager resolution.** Default and fixed value tokens are resolved and validated at schema-load time. The token is parsed by the field's type — for typed fields by the atom's parser; for `value`-typed fields by [TSON-DATA] §4 base type resolution — and stored as the resolved host value. A default or fixed value that fails parsing or the type's constraints is a resolver error, reported at schema load. This matches §7.4's eager-conversion rule for constraint-field values.

**Default injection.** When a field has `state: REQUIRED_DEFAULT` (or `REQUIRED_FIXED`) and the data does not provide a value, the decoder injects the default (or fixed) value into the output: decoded values are fully populated, and consumers do not consult the schema to retrieve defaults. An explicit absent sentinel `_` at a REQUIRED_DEFAULT field is treated as not-supplied: the decoder SHOULD warn — `_` asserts absence at a position the schema always fills — and injects the default. At a plain REQUIRED or a REQUIRED_FIXED field, `_` is a validation error: there is no fallback to inject at REQUIRED, and a fixed field's injection route is omission (§7.6). Encoders SHOULD write values for defaulted fields — a document that states its defaults reads without its schema; omitting a field whose value equals its default is a wire-size optimisation an encoder MAY offer, lossless only because the decoder injects the value back on read. Resolver output is exempt from this SHOULD: it omits fields at their default values (§8.1).

In data, a REQUIRED_FIXED field may be provided with a value matching the fixed value, or omitted (the fixed value is used). A contradicting value is a validation error.

**Resolution.** A record definition resolves to a `type_definition` with `kind: PRODUCT` and `body: !record { fields: [...] }`. Each field maps to a `record_field` record `{ name  type  state  value? }`: `type` carries the (flattened, §8.3) type reference, `state` the field state (the default `REQUIRED` is omitted in output), and `value` the eagerly-resolved default or fixed value. An empty record `{}` is the zero-field case, `body: !record { fields: [] }` — the shape of the kernel's `top`.

**Type-name resolution.** Type names used as type-refs (field positions, type arguments, choice variants, composition targets, refinement sources) resolve against the type-name namespace; constructor-application targets, generic-application heads, and the desugar targets of the sugar forms resolve through the structure namespace per §3.3.1. Bare names always refer to types — there is no field-name shadowing of type names.

**Inline atom refinements and bare records are prohibited.** Atom refinements (`!number ^ { min: -273.15 max: 10000 }`) and bare records (`{ name: text }`) MUST be introduced via named declarations and referenced by name; they MAY NOT appear inline in field-type, field-group-member, tuple-element, array-element, choice-variant, type-argument, or composition positions. Inline container forms are additionally restricted to type-argument shapes — size specifiers and element/position optionality are declaration-level syntax (§5.3). Implementations MAY warn on permitted inline forms that exceed a configurable nesting depth.


### 5.3 Type Expressions

The type-definition grammar supports two tiers of type expression, distinguished by position. **Inline forms** — legal at any type-ref position (field types, group members, tuple elements, array elements, choice variants, type arguments) — are plain arrays, all-REQUIRED tuples, choices, and generic applications, which may carry both type and value arguments (§5.10). **Declaration-level forms** — legal only as the top-level body of a declaration — additionally admit the bracket syntax for size specifiers and element/position optionality, and nest: an element position inside a declaration-level bracket form takes the full bracket syntax again (§12.1). The dividing line is the bracket syntax itself, not expressiveness: a size specifier is a spelling of an application of the kernel's size-refinement templates (below), and the application form — `array_ranged<order, 1, 100>` — is an ordinary generic application, legal inline wherever the template name resolves (§3.3.2); ordinary schemas reach the size templates through the sugar alone (layer visibility, below). An inline form may nest inline forms to any depth, but may not carry a size specifier or an element/position `?`.

```
config => {
  tags:     [text]                 ; inline: plain array
  meta:     map<text, integer>     ; inline: generic application
  point:    [number, number]       ; inline: tuple, all positions REQUIRED
  contact:  (email | phone)        ; inline: choice
  index:    map<text, [order]>     ; inline: nested inline forms
  aliases:  [text]?                ; inline array; the ? is FIELD optionality (§5.2)
  scores:   score_list             ; sized forms are declared, then referenced
  batch:    order_batch
  sparse:   sparse_pair
}

score_list  => [integer; 1..]      ; declaration-level: size specifier
order_batch => [order; 1..100]     ; declaration-level: bounded range
matrix9     => [number; 9]         ; declaration-level: exact size
opt_items   => [text?]             ; declaration-level: OPTIONAL elements
sparse_pair => [text, text?]       ; declaration-level: OPTIONAL tuple position
```

**Inline forms.** Four shapes are inline-legal: a plain array `[T]`; a tuple `[T, U, ...]` whose positions are all REQUIRED; a choice `(A | B | ...)` (§5.4); and a generic application `name<args>`. Each desugars to a constructor application or a template application (§5.10) and is represented structurally at the use site (below). A size specifier, an element `?`, or a position `?` inside an inline form is a parse error whose diagnostic SHOULD suggest a named declaration (or, in layers where the template names resolve, the equivalent size-refinement application).

**Declaration-level forms.** As a declaration's top-level body, the array and tuple forms take their full syntax, and element positions within them accept the full syntax recursively (§12.1). A pure size specifier is a spelling of a size-refinement template application (the desugar table below). The element- and position-`?` forms have no template route (their state binding pins the same array a size would, and template application nests rather than merges), so they desugar directly and become the declaration's body; these forms remain declaration-level only, consistent with the named-declaration rule for atom refinements and bare records (§5.2).

**Array types** use `[type]` with, at declaration level, an optional size specifier after `;`. The size specifier is a grammar production over the range token ([TSON-DATA] §7.2.4): a bound, optionally followed by `..` and an optional upper bound, or `..` followed by a bound (§12.1). Each bound is an unquoted token whose text MUST match the `decimal-natural` production of [TSON-DATA] §7.6 — a non-negative decimal integer without leading zeros — or, within a template body (§5.10), a value-parameter name: `grid => <T, N> [[T; N]; N]` is `array_ranged<array_ranged<T, N, N>, N, N>`, the inner form desugaring first — a nested declaration-level bracket form (§12.1); at an inline (type-ref) position a size specifier remains a parse error. Classification is unambiguous — parameters cannot be numeric — and a non-numeric bound token that resolves to no value parameter is a resolver error. Five forms result: `N` (exactly N elements), `N..M` (bounded range), `N..` (at least N), `..M` (at most M), and absent (unconstrained). When both bounds are present, N MUST be less than or equal to M — a value-level relation checked at the point the bounds are concrete: at schema load for literal bounds, at template materialisation for parameter bounds (§8.2); a violation is a resolver error. `[T; N..N]` and `[T; N]` are two spellings of the same application and land on the same entry (§8.2); the `N` form is RECOMMENDED. A lower bound of `0` with no upper bound (`0..`) is vacuous — every array satisfies it — and the resolver SHOULD warn; the desugar is nonetheless mechanical, `array_min<T, 0>` like any other lower bound, so the spelling produces an entry distinct from `[T]` (identity is application-structural, §8.2) whose pinned floor merely restates the default. Authors wanting the unconstrained array write `[T]`.

The element position accepts, at declaration level, an optional `?` suffix, producing `state: OPTIONAL` on the resolved `array`. Under `[T?]`, elements at any position MAY be the absent sentinel `_` (§7.6); absent elements occupy positional slots — `[a _ c]` has three elements and satisfies a `[T?; 3]` size constraint. Without the suffix, `state` defaults to `REQUIRED` and absent elements are a validation error when a schema is in scope. The `set` constructor refines `array` and pins `state: = REQUIRED` — absence has no meaning in an unordered collection of unique members.

**Tuple types** use `[type, type, ...]` with comma or whitespace separation between individually typed positions. A tuple requires at least two element type expressions: two or more type-refs separated by whitespace or comma inside brackets is always a tuple; a semicolon after a single type-ref introduces an array size specifier (declaration level only); a single type-ref with no semicolon is an unconstrained array — never a one-element tuple. `[text,]` is a parse error ([TSON-DATA] §2.4).

Tuple positions support only REQUIRED and OPTIONAL states (tuples and arrays share the two-member `element_state` enumeration; records use the five-member `field_state`); the OPTIONAL state is declarable at declaration level only. Tuples are fixed-length: every position MUST be present in the data. An OPTIONAL position may carry the absent sentinel `_`, but the slot itself MUST appear — a tuple value with fewer elements than the type's position count is a validation error regardless of trailing-optional positions. Given `sparse_pair => [text, text?]`: `[a, b]` and `[a, _]` are valid; `[a]` is a validation error. Authors wanting trailing-optional semantics should use an array (`[text; 1..]`).

**Choice types** use `(type | type | ...)` — see §5.4.

**Arguments** use `name<arg, arg>`, binding positional arguments to the declared parameters of a constructor or template (§5.10). The argument count MUST match the parameter count. An argument is a type reference — which may nest (`map<text, array<integer>>`, `map<text, [integer]>`) — or, for a template with value parameters, a concrete value: `array_ranged<order, 1, 100>`, `vector<pixel, 1920>`. Number, boolean, and quoted-string arguments are unambiguously values; an unquoted token argument is classified against the applied signature's parameter kinds — a reference in a type slot, a literal (an enum member, for instance) in a value slot (§5.10). Bare references to a parameterized type without `<>` are resolver errors — parameter binding is mandatory at every use site.

**Desugaring.** Inline and declaration-level forms desugar through fixed chains to constructor applications (§5.6):

| Source form                  | Desugaring                                                                     |
|------------------------------|--------------------------------------------------------------------------------|
| `[T]`                        | `!array { element_type: T }`                                                   |
| `[T; N..]`                   | `array_min<T, N>` (declaration level)                                          |
| `[T; ..M]`                   | `array_max<T, M>` (declaration level)                                          |
| `[T; N..M]`                  | `array_ranged<T, N, M>` (declaration level)                                    |
| `[T; N]`                     | `array_ranged<T, N, N>` (declaration level)                                    |
| `[T?]`, `[T?; ...]`          | the corresponding array form with `state: OPTIONAL` bound directly (declaration level) |
| `[T, U, ...]`                | `!tuple { elements: [...] }`                                                   |
| `(A \| B)`                   | `!choice { variants: [A B] }`                                                  |
| `set<T>`                     | `!set { element_type: T }`                                                     |
| `map<K, V>`                  | `!map { key_type: K, value_type: V }`                                          |
| `C<args>`                    | `!C { <bindings> }` when `C` is a constructor; a template application (§5.10, §8.2) when `C` is a non-constructor template |

The size sugar routes through the kernel's **size-refinement templates** — `array_min => <T, MIN> array<T> ^ { min_items: = MIN }`, `array_max`, and `array_ranged`, declared in the kernel without `~` (§4.2): not constructors but refinement templates, so their closures are ordinary members of the array family, IS-A `array` and substitutable where arrays are expected. Because the templates pin their sizes (`= MIN`, §5.7), a sugar-declared size is final — not further narrowable; a layer that imports the kernel, where `array` is nameable (§3.3.2), can declare alternative templates with different pinning choices. The relation is `N ≤ M` at every level — `array_ranged` accepts MIN = MAX — and a violated relation (`5..3`, or a template application that closes to one) is a resolver error at the point the bounds close (§5.3, §8.2).

**Layer visibility.** The structure namespace supplies constructor roles only, gated on `constructor: true` (§3.3.1). The sugar forms and `~` constructors — the kernel's `set` and `map`, the meta layer's `vector` — are therefore reachable from every governed schema, while the size-refinement templates, declared without `~`, are not: an ordinary schema reaches them only through the size sugar (which is why the sugar's parameterized bounds matter — the sugar is the ordinary schema's whole route), and names them directly only by importing the kernel. Refinement sources and composition targets resolve through the type-name namespace alone (§3.3.2), so *deriving* from `array<T>` — as `vector` and the size templates themselves do — is available only in layers where `array` is nameable; a template in an ordinary schema is built from the schema's own names and imports. The `~` flag thus sets three dials at once: annotation head (§5.6), entry weight (§8.2), and reach. Where fixed arity is the *intent* rather than a coincidence of bounds, the meta layer's `vector` constructor (§9) states it as a type of its own — `vector => <T, S> ~array<T> ^ { min_items: = S  max_items: = S }`, a `~` constructor parallel to `set`, so applications are structural and closed declarations carry `!vector` bodies; it is deliberately not a desugar target.

Array forms record `element_type`, `state` from the element's `?` suffix, and `min_items`/`max_items` from the size specifier; tuple forms record `elements` with a `tuple_element` per position, each carrying its own `state`.

**Resolution is structural.** An inline constructor application does not materialise a schema entry. The resolver represents it in place as a `type_ref` value (§8.1) whose `name` is the desugar target and whose `arguments` are `type_argument` records — `{ name: ... }` for a reference, `{ value: ... }` for a literal — recursively. `index: map<text, [order]>` resolves to a `record_field` whose `type` is `{ name: map  arguments: [ { name: text }  { name: { name: array  arguments: [ { name: order } ] } } ] }`. Constructor applications are self-describing under this representation — the constructor's declared parameters give the arguments their meaning; for the variadic pair, `tuple` and `choice`, arguments map positionally onto `elements` and `variants`, lossless precisely because the inline restriction keeps element states and sizes out of inline forms — and validators interpret them directly with a one-hop lookup of the head. A fully-bound application of a non-constructor template instead resolves to a materialised instantiation entry, referenced by name (§5.10.1, §8.2).


### 5.4 Choice Types

A choice type declares that a value may conform to any one of a set of alternative types:

```
contact_method => (email | phone | address)
```

A choice MUST contain at least two variants; each variant is a type reference. The choice name is then used like any other type name in field definitions, and choices MAY also appear inline in type-ref positions: `contact: (email | phone)`.

**Tagging.** At the data level, a value matching a choice type carries a type annotation (`!variant`) selecting the variant, **unless** the choice is disjoint under the active encoding's discrimination, in which case the annotation MAY be omitted and the variant recovered structurally. The permission is narrow and rests on two facts held apart: whether the variants are disjoint as value sets — an encoding-independent property the resolver derives (below) — and whether the active encoding's discrimination can recover the variant from the wire form given that disjointness. The tag is omissible only where both hold. Where the tag is omitted, the variant is recovered by **the same form resolution the encoding already performs** — for TSON text, the single base-type-resolution pass of [TSON-DATA] §4 — never by a second, type-directed inspection of the value's form; the once-only reading of form ([TSON-DATA] §2.4) is preserved. Where the encoding cannot separate the variants — variants that share a base-type class, such as `(email | uri)` (both the string class) — the tag is REQUIRED, and a value that omits it is a validation error. Each encoding states its own separability predicate over the derived disjointness fact; this document's rule is TSON text's. The validator never infers a variant from structure beyond what the encoding's own form resolution already decides.

**Disjointness.** The resolver derives whether a choice's variants are pairwise disjoint as value sets and records the result in `type_definition.disjoint` (§8.1): `true` when disjointness is proved, absent otherwise. Derivation is conservative — it proves disjointness or leaves the fact absent; it never asserts overlap it cannot prove. Different kinds are disjoint; different atom families are disjoint; same-family numerics are compared by their bound intervals. Where a variant IS-A another (`(positive_integer | integer)`), the variants are provably **not** disjoint. Record-set disjointness under composition and closure, and pattern disjointness over `regex`-constrained atoms, are cases a resolver MAY prove and MUST leave absent when it cannot; the labelled form (below) is the better model where they arise. A non-disjoint choice is legal — §5.4 requires distinct variant *types*, not disjoint *value sets* — it merely always requires the tag.

**The `@disjoint` assertion.** An author MAY annotate a choice definition with `@disjoint` (defined in `meta.tn1` as a `void`-targeted marker, written bare per §6 — presence is the assertion) to record the intent that its variants are mutually exclusive. The annotation carries no decode force — the resolver computes `type_definition.disjoint` whether or not it is present — and exists to be checked against that derived fact, converting a silent drift into a diagnostic:

- **Proved disjoint** (`@disjoint` present, derived `disjoint: true`) — verified; silent.
- **Refuted** (`@disjoint` present, provably not disjoint) — a **resolver error** at schema load: the author asserted a property the types demonstrably lack, in the manner of `N > M` in a size range (§5.3).
- **Unprovable** (`@disjoint` present, disjointness neither proved nor refuted) — a **resolver warning** at schema load: the assertion may hold in the domain but is beyond the resolver's reach; discrimination will fall back to the encoding's structural testing where the encoding permits, or require the tag where it does not.
- **Absent** — the resolver still derives and records `disjoint`; no claim is checked, so no diagnostic.

A resolver SHOULD NOT warn merely because a choice's variants are not disjoint — overlap is often intended, and the labelled form serves it deliberately. The warnable condition is an unverifiable *assertion*, not the underlying fact. Diagnostics about whether a given encoding can drop the tag for a disjoint choice belong to that encoding's rules, not here: this section's concern ends at the encoding-independent `disjoint` fact.

**Resolution.** `(A | B | ...)` desugars to `!choice { variants: [A B ...] }` — a SUM-kind `type_definition` when declared, and a structural `type_ref` when inline (§5.3): an inline choice materialises no entry. Each variant is a type reference (a `type_ref` value, §8.1) and may itself be an inline form. The resolver validates that each variant resolves to a distinct type. `type_definition.disjoint` is set on declared choices; an inline choice materialises no entry and so carries the fact only where a consuming validator recomputes it in place.

A choice discriminates by variant *type name*; for labelled disjunction — mutually exclusive alternatives distinguished by field label, including alternatives of the same underlying type — see field groups (§5.11). The labelled form is the recommended resolution wherever the tag would otherwise be mandatory: a choice whose variants share a base-type class, or whose disjointness is unprovable, is often better written as a single-group record (§5.11), which discriminates by label and needs no derived disjointness.


### 5.5 Constructor Application and Atom Refinement

Within the type-definition grammar, the `!` prefix always takes a **constructor**; the invariant the data format teaches therefore holds in every grammar of the series: `!T x` describes a value shaped by `T` — in schema source, in data documents, and in resolver output alike. Two forms follow the prefix, distinguished by the `^` operator; the name after `!` resolves per §3.3.1.

**Constructor application — `!C value`.** Produces a constructor instance filled with specific values. The data-value after `!C` is a record of bindings interpreted against the constructor's record shape — the field list `C` declared as its narrowable vocabulary — or the positional form of §5.6. This form does NOT establish IS-A: construction transfers only the constructor's `kind`; the result records `source: C` with empty `supertypes`. Resolving a non-constructor after a bare `!` is a resolver error (§3.3.1).

```
integer => !integer_type {}
boolean => !enum [true false]
base64  => !binary BASE64
```

**Kind determination.** A constructor's kind is settled at definition time by the **base kind** — `atom`, `product`, or `sum`, excluding `top` (§4.1) — reachable through its transitive supertypes chain. Zero base kinds in the chain → `kind: PRODUCT` by structural default; exactly one → that kind; two or more → resolver error, since the kinds are categorically distinct. `!C {}` simply inherits `C`'s settled kind.

**Atom refinement — `!I ^ { values }`.** Refines an atom-family instance by tightening values on its constructor's constraint fields. `I` MUST resolve to a non-constructor instance (§3.3.1), and the body MUST be a braced record of constraint bindings. This form DOES establish IS-A: the new type records `source:` `I`'s constructor, `supertypes: [I]`, and a body in the constructor's canonical form (§5.6). A refinement head admits no removal clause (§5.9).

```
age              => !integer ^ { min: 0  max: 150 }
non_empty_text   => !text ^ { min_length: 1 }
positive_integer => !integer ^ { min: 1 }
```

`age` has `source: integer_type`, `supertypes: [integer]`, and can be refined further. Founding and refining are distinguished at the head, by the operator: `!integer_type {}` applies the constructor (fresh family, no IS-A); `!integer ^ { min: 0 }` refines the instance (IS-A `integer`).

**Construction creates siblings, not subtypes.** One constructor may found any number of nominally distinct families: `dogs => !integer_type {}` is a fresh atom family with the same body as `integer` and no relation to it, and `small_dog_count => !dogs ^ { min: 0  max: 5 }` refines `dogs`, not `integer`. The only IS-A the `!` forms ever create is the refinement's single hop to its instance — recorded in `type_definition.supertypes` and deliberately nowhere in the body: the canonical form (§5.6) erases the surface distinction, so `supertypes` is the sole carrier of the atom family's direct IS-A fact (§8.1).

**Single-required-field positional form.** When a constructor has exactly one REQUIRED field, the data-value after `!C` fills that field directly; see §5.6. The positional form applies to constructor application only — a refinement body is always a braced record.


### 5.6 Canonical Form and Desugaring

All type-definition bodies ultimately take a single canonical form:

```
!C { bindings }
```

where `C` names a constructor and `bindings` is a record literal filling the constructor's fields. Every other form — inline type expressions, positional constructor forms, atom refinements — is syntactic sugar that desugars to this form during resolution; resolver output always records the fully expanded canonical form in the `body` field.

**Positional form.** When a constructor has exactly one field in state `REQUIRED` (no default, no fixed value), the data-value after `!C` may be that field's value directly:

```
!enum [true false]    →  !enum { members: [true false] }
!binary BASE64        →  !binary { encoding: BASE64 }
!array text           →  !array { element_type: text }
```

REQUIRED_DEFAULT, REQUIRED_FIXED, and OPTIONAL fields do not count toward the single-REQUIRED rule. The positional form is invalid when the type has zero, two, or more REQUIRED fields — the resolver MUST reject such uses with a clear error. This restriction applies to the positional form only.

**The positional form is general over schema-backed data.** At any data position whose declared type is a record with exactly one field in state REQUIRED, a non-brace-delimited value fills that field directly; a braced value is always the explicit record (so record- and map-valued fills cannot go positional — the same exclusion as above). The two spellings denote the same value; which one canonical output uses is a per-type convention (§8.1 for `type_ref`, whose bare-token spelling is this rule). Type-definition bodies canonicalise the other way — toward the explicit bindings record, per the end state below — a deliberate pair of conventions: bodies uniform, references minimal.

**Record-bindings form.** `!C { ... }` is the explicit form, valid for any constructor as long as the bindings cover all REQUIRED fields not pinned by FIXED or covered by DEFAULT. Empty bindings `!C {}` are valid whenever the constructor has no unfilled REQUIRED fields.

**Atom refinement.** `!I ^ { values }` desugars by retargeting to the instance's source constructor:

```
!integer ^ { min: 0  max: 150 }   →  !integer_type { min: 0  max: 150 }
!text ^ { min_length: 1 }         →  !text_type { min_length: 1 }
```

Recognition is syntactic — the `^` declares the intent — and the resolver verifies it: the target MUST resolve to a non-constructor atom-family instance, and the retarget follows the instance's `source`. The result records `source: I.source` and `supertypes: [I]`.

**End state.** After desugaring, every *closed* non-reference type-def body in resolver output is a binding record `!C { bindings }`, where `C` is the nearest `~` constructor in the definition's source chain — `!array` for a closure of `array_min`, `!set` for an application of `set`, `!vector` for an application of `vector` — and `bindings` supplies values for the vocabulary's REQUIRED fields not pinned by FIXED or covered by DEFAULT, plus the fields the application or refinement bound. Pins, defaults, and routes divide by where their values live: a pin or default whose value is *concrete in the head's own declaration* (`set`'s `unordered: = true`) comes from the vocabulary and does not appear in the binding record, while a value *routed by a parameter* — a value parameter (`vector`'s `min_items: = S`) or a type parameter on a `type_ref`-typed slot (`array`'s `element_type: = T`, §4.2) — is application-supplied information and does appear — `frame => vector<pixel, 1920>` closes to `!vector { element_type: pixel  min_items: 1920  max_items: 1920 }` — so a validator reads any closed body with a one-hop lookup of the head, never substitution. Type slots are `type_ref`-typed fields whose values are routed by parameter (`array.element_type: type_ref = T`, §4.2), so they follow the same rule as any parameter-routed value: the bound reference appears in the binding record as a `type_ref` value under the positional form (§8.1) — a bare token for a simple reference, the explicit record for an application. Binding records are closed-world: they never contain parameter references — a definition whose parameters remain open keeps its body at the vocabulary level instead (§5.10), so constructors and open non-reference templates carry `!record` vocabulary bodies, and every other non-reference entry carries a binding record. The surface abbreviations exist only in source text.

**Top-level constructor applications are constructions.** A declaration whose body is a fully-bound application of a constructor — `lookup => map<text, integer>`, in either angle or bracket spelling — resolves as a construction: `kind` from the constructor's family, the applied form recorded in `source` (§8.1), the binding record as body, and no supertypes (construction transfers kind, not IS-A). Two such declarations are nominally distinct entries with structurally equal bodies. A declaration whose body is a fully-bound application of a non-constructor *template* resolves instead as a reference to the materialised instantiation (§5.10, §8.3).

**Named definitions required.** The refinement form (`!I ^ { values }`) and constructor application (`!C value`) are valid only as the top-level body of a declaration — the inline prohibition of §5.2. A constrained atom must be introduced with its own declaration and referenced by name.


### 5.7 Refinement

Refinement copies an existing definition and tightens it — binding values, fixing defaults, restricting ranges — producing a new definition with its own identity that IS-A the source. It never changes the field set: no field is added and none removed. It is expressed with the `^` operator between a source type name and a record body:

```
config => { host: text  port: integer ~ 8080  debug: boolean }
production => config ^ { host: = "prod.example.com"  port: = 9090 }
```

The operator carries the operation: `^` always means *refine, preserving IS-A*, at every rung of the ladder — record and map types (`config ^ { ... }`), constructors (`~array<T> ^ { ... }`, §4.2), and atom instances (`!integer ^ { ... }`, §5.5). A source type name followed directly by a braced body, with no operator, is a parse error; the diagnostic SHOULD suggest `^` (refinement) or `&` (composition). A refinement head admits no removal clause: `T ^ { ... } - { ... }` is a parse error — an operator that promises IS-A cannot host the operation that revokes it (§5.9).

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

**Open modifiers.** In a template body (§5.10), the value of a `=` or `~` modifier may be a parameter: `vector => <T, S> ~array<T> ^ { min_items: = S  max_items: = S }` routes, and `retry_policy => <N> { attempts: integer ~ N }` defaults. The parametric spellings do not take the concrete-value transitions, because nothing is fixed at declaration — the value does not exist yet; it arrives at application, and every application MUST bind every parameter (§5.10), so it is always supplied. Accordingly, a parametric `~ P` places the field in REQUIRED_DEFAULT, and a parametric `= P` places the field in REQUIRED — from OPTIONAL this is the table's ordinary OPTIONAL → REQUIRED tightening (`array_min`'s `min_items: = MIN` makes the bound mandatory, which is the template's point). In both cases the parameter is recorded at the vocabulary level as a `value_param` member in place of `value` (§8.1); since REQUIRED is the omitted default state, a `= P` route renders as a bare `value_param` member in output. On full binding, the parameter's argument becomes the field's supplied value in the binding record — supplied the way data supplies any required field. Fixation happens downstream, where values are concrete: a refinement deriving from an application head receives the arguments as REQUIRED_FIXED bindings (below). The modifier spellings are the *only* way to bind a field's value to a parameter: a bare parameter token in a plain value position would be indistinguishable from a token literal, so the grammar has no open plain binding — `min_items: S` parses as a type-narrowing entry and fails resolution, with a diagnostic that SHOULD suggest `= S`.

**Elided type-refs.** In a refinement or supertype-composition body, the type-ref in a field definition MAY be elided: when only a modifier is present (`field: = value` or `field: ~ value`), the field's type is inherited from the source declaration and only the value state changes. A modifier-only entry is always a tightening — it names no type, so it cannot declare a new field — and a modifier-only entry whose name matches no inherited field is a resolver error. Restating the type-ref remains necessary when the tightening also narrows the field's type. In a fresh record definition there is no inherited declaration to elide toward: every field MUST have an explicit type-ref, and the resolver MUST reject modifier-only entries there.

**Refinement requires a vocabulary body.** The source of `^` — after flattening references (§8.3) — MUST be a definition whose body is a `!record`: a fresh or refined record, a composition, a constructor, or an open template; or it is an application head over such a vocabulary (`map<text, text> ^ { min_items: 1 }` derives from `map`'s vocabulary with the arguments entered as REQUIRED_FIXED bindings — concrete values now, so the ordinary fixed-value rules apply, and a refinement body that contradicts an argument is a resolver error). A definition whose body is a binding record — a top-level constructor application (§5.6), a template instantiation (§8.2), or an alias resolving to either — is **finished**: its bindings are set, and `^` on it is a resolver error. Record refinement chains remain open because record refinement re-emits a `!record` body; map and array shapes are refined at their application heads, and a declared construction (`lookup => map<text, integer>`) is terminal — authors wanting a narrower relative re-derive from the head (`strict_lookup => map<text, integer> ^ { min_items: 1 }`, in a layer where `map` is nameable at a refinement source, §3.3.2). Atom refinement (`!I ^`, §5.5) is a distinct form with its own rule and is unaffected.

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

**Constructor marker is independent of supertypes.** The leading `~` is the sole signal for `constructor: true`. A composition like `uri_type => ~text_type & atom_specification & { ... }` is a constructor because of the `~`, not because `text_type` is; without the `~` the same composition produces a non-constructor type. Constructorness is a property of the definition, not inherited through IS-A.

```
atom_specification => { spec: uri }
uri_type => ~text_type & atom_specification & {
  spec:    = "https://www.rfc-editor.org/rfc/rfc3986"
  scheme:  text?
}
```

`uri_type` is a constructor, IS-A `text_type` and `atom_specification` directly. Its fields, in order: `text_type`'s four constraint fields, `atom_specification`'s `spec` — tightened in place to `REQUIRED_FIXED` via an elided-type modifier — and the new `scheme` field.

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
8. **Constructors and parameters.** `~` may precede a subtracted construction — a constructor with lineage and no contract — and a parameterised subtraction declares a parameterised type whose field set is the merged set minus the removals.

**Resolution.** The two `supertypes` fields in resolver output capture the contract/lineage distinction: `type_definition.supertypes` (the IS-A lattice) is empty — the subtracted type is not source-compatible; `record.supertypes` in the body (authorial lineage) is preserved as the head's source list (§8.1). A removed field's annotations are lost with the field. For ingest (§8.1), broken-IS-A-with-lineage is declared by the source syntax rather than inferred by diffing field sets against the parents.


### 5.10 Templates and Parameters

A type definition may declare parameters using `<>` immediately after `=>`. Parameters are local names referenced in the body and bound to concrete arguments when the definition is referenced with `<args>`. A definition with parameters is a **template** — it cannot be instantiated directly, and references to it MUST supply arguments for all its parameters:

```
container => <T> { items: [T] }
pair      => <T, U> { first: T  second: U }
vector    => <T, S> ~array<T> ^ { min_items: = S  max_items: = S }
matrix    => <T, M, N> vector<vector<T, N>, M>
```

`container<text>`, `vector<pixel, 1920>`, and `matrix<pixel, 1080, 1920>` are concrete types; bare `container` or `vector` references are resolver errors.

**Two parameter kinds.** A parameter is a **type parameter** or a **value parameter**. Kinds are not annotated; they are inferred from use sites, and the discriminator is *what flows through the channel*, not which side of the `:` it sits on: a parameter used in type-reference channels (field types, element types, choice variants, arguments filling type slots), **or routed into a `type_ref`-typed field** (`element_type: type_ref = T`, §4.2 — what flows through is a reference), is a type parameter; a parameter routed or defaulted into a scalar-typed field, or filling a scalar argument slot, is a value parameter. A parameter used in both kinds of channel is a resolver error. An unused parameter is a type parameter (the phantom-parameter reading) and the resolver SHOULD warn; a parameter whose kind is grounded only in mutual recursion between templates, with no concrete kind-determining use, is a resolver error. At application sites the same split classifies arguments: a reference argument (`{ name: … }`) binds a type parameter, a literal (`{ value: … }`) binds a value parameter, and unquoted tokens are settled against the applied signature's parameter kinds (§5.3). Value parameters bind **scalars only** — the values that fill scalar slots (§5.3, [TSON-DATA] §4): numbers, booleans, tokens, and quoted strings; type parameters bind references, simple or compound. Collection-valued slots are not parameterizable — an enum's member list, for instance, cannot be bound through a parameter — and there is no parameter arithmetic: every value argument is used as given.

**Scoping and the shadowing/label rule.** Parameters are local to the declaring definition; they do not escape and do not compose across `&`, and two definitions can independently use the same parameter name. Two positions that must agree share a parameter (`homogeneous_pair => <T> { first: T  second: T }`; `vector` uses `S` twice) — sharing the name is the link. Where a parameter reference lives depends on what else can occupy the channel. Type-reference channels are all-reference — a token there is always a name — so parameters ride them by **shadowing**: within the body, parameter names take precedence over the schema namespace, and implementations SHOULD warn when a parameter shadows a schema type. Value channels take the **labelled** form regardless of the field's type: the `= P` pin in source (§5.7), the `value_param` member in output (§8.1) — on scalar-typed fields because a bare token there is always a literal (enum members never collide with parameter names), so only a label can mark a parameter at all; on `type_ref`-typed slots for uniformity, so substitution rewrites one channel. One rule, both directions: shadow where the grammar position admits only references; label wherever a value stands.

**Templates are not directly instantiable.** A `type_definition` with a non-empty parameter list cannot validate data; using a template as a type annotation in data is a resolver error — with one narrow carve-out, stated in §7.2: a parameterized definition whose every parameter occurrence is a `value_param` member (the parameterized constructors, §4.2) may head the binding records of resolver output, since a parametric pin imposes no value constraint on the annotated data.

**Open bodies are vocabulary records.** While parameters remain unbound, a template's resolved body stays at the vocabulary level — the level where the parameter channels exist. A non-reference template — a record template like `container`, a refinement template over a constructor application like `array_min`, or a parameterized constructor like `vector` (§4.2) — resolves to a `!record` vocabulary body: the source chain's vocabulary materialised per §5.7, with parameters as `value_param` members on the routed and defaulted fields — type parameters on `type_ref`-typed slots, value parameters on scalar ones (§4.2) — and, in record templates, type parameters riding `record_field.type` channels by shadowing. A reference template — a pure application body like `matrix` — resolves to `body: !reference { target: head }` with the open application recorded in the entry's `source` (§8.1). Binding records never contain parameter references (§5.6): openness is visible both at the entry head (`parameters`) and in the body's own level.

**Substitution is structural rewriting.** Binding a template's parameters rewrites the recorded structures — parameter references in the `source` application and the vocabulary body are replaced by the corresponding arguments — with no rendered intermediate to re-parse. When every parameter closes, the resolver materialises the result (§5.10.1, §8.2): the vocabulary body collapses to a binding record headed by the nearest `~` constructor in the source chain, and the fully-bound application is recorded in the entry's `source`.

**Fully-bound references** resolve to REFERENCE-kind entries targeting the materialised instantiation by its entry name:

```
string_triple => array_ranged<text, 3, 3>
```

resolves to `kind: REFERENCE`, `source: { name: array_ranged  arguments: [ { name: text }  { value: 3 }  { value: 3 } ] }`, and `body: !reference { target: E }`, where `E` is the instantiation entry's internal name (§8.2). (`array_ranged` is nameable in kernel-importing layers; `[text; 3]` is the same declaration in sugar spelling, legal everywhere, §5.3.) A fully-bound application of a *constructor* at the same position is not a reference — `string_vec => vector<text, 3>` resolves as a construction in place (§5.6), with no instantiation entry behind it.

**Partial application.** When a reference to or refinement of a parameterized type leaves parameters open, it MUST re-declare the open parameters in its own `<>` slot — implicit parameter inheritance is not permitted; every parameter has a visible declaration site:

```
text_keyed_map => <V> map<text, V>
```

A partially-applied reference is itself a template. Applications of constructors stay structural, so `text_keyed_map` resolves to `parameters: [V]`, `source: { name: map  arguments: [ { name: text }  { name: V } ] }`, and a construction on binding — `matrix` above is the same case, its head being the `vector` constructor; an open application of a *template* (`row => <T, N> [T; N]`, an open `array_ranged` application via the size sugar, §5.3) resolves to a reference template whose `target` is the head and whose `source` carries the open application. Reference-kind templates compose during substitution — no intermediate entry per alias hop; the origin survives as `source` and, at use sites, `@alias` (§8.3).

**Heads are not parameters.** Parameters bind *arguments of a named head*; the head itself — the type being refined, composed, or applied — is always a concrete name. `<A, N> A ^ { max_items: = N }`, abstracting over the refinement source, is not expressible: the size-refinement family is declared per-constructor (`array_min` names `array`), and generic derivation over arbitrary heads is a deliberate v1 boundary.

**Parameters carry no bounds** — a second deliberate v1 boundary. A parameter declaration is a bare name: no supertype bound on a type parameter, no range or type constraint on a value parameter. An argument is checked only by the channels the parameter occupies — a type argument must resolve; a value argument must be a scalar and satisfy the field it ultimately pins (eager resolution, §5.2). Bounds are additive: they can be introduced later without changing the template model, so their absence is a recorded deferral, not a design claim.

**Closed entries are parameter-free.** An entry whose `parameters` list is empty MUST contain no parameter references anywhere: no `value_param` members, and no reference `name` — in field types, element types, variants, `source`, or `type_argument`s — that resolves to a parameter, at any depth. This is a well-formedness rule on resolver output and an integrity check on ingest (§8.1); only template entries may be open.

The `_` token is not valid in field-type positions (§7.6); authors expressing "type to be filled later" use parameters.


#### 5.10.1 Self-Referential Types

Types may reference themselves:

```
node => { value: text  children: [node] }
linked_list => <T> { value: T  next: linked_list<T>? }
tree => <T> { value: T  children: [tree<T>]? }
```

The resolver MUST detect and handle cycles in type references. A self-referential type is complete as long as the recursive reference is through an optional field or a variable-length container; a required direct self-reference (`item => { inner: item }`) creates an infinitely nested structure that MUST NOT be instantiated — the resolver SHOULD warn about such definitions. In a template body, a recursive reference is an open application like any other — `tree`'s `children` field carries `{ name: array  arguments: [ { name: { name: tree  arguments: [ { name: T } ] } } ] }` — and needs no special representation.

**Template materialisation.** When a template application closes, the resolver materialises one entry per distinct application: identity is structural equality of the flattened, fully-bound application (§8.2), so `tree<text>` and `tree<integer>` are two entries, and every further occurrence of the same application anywhere in the schema reuses the existing one. The entry's name is internal (§8.2). Recursive references within the body, once bound, denote the entry being materialised; the resolver ties the knot by resolving them to the entry's own name. The same model applies to non-recursive templates.


### 5.11 Field Groups

A record body may declare a **field group**: a parenthesised, `|`-separated set of labelled members occupying one logical position, of which at most one may be present in conforming data. The field name is the discriminator; the wire form of instances is unchanged by grouping.

```
integer_type => ~atom & {
  size:  integer_size?
  ( min: integer | exclusive_min: integer )?
  ( max: integer | exclusive_max: integer )?
  multiple_of:  integer?
}
```

A group MUST contain at least two members. Each member is a `field-name`/`type-ref` pair. Member labels share the enclosing record's field namespace: a label MUST be unique across the record's plain fields and all groups' members, including fields contributed by supertypes (§5.8's disjointness rule extends to member labels).

**Group state.** The `?` suffix applies to the group as a whole: a bare group is REQUIRED — exactly one member MUST be present; a group with `?` is OPTIONAL — at most one member MAY be present. These are the only group states; a group has no default or fixed form in v1.

**Member positions are deliberately bare.** A member takes a type-ref and nothing else: the `?` suffix and the `~`/`=` value modifiers are parse errors on a member — selection belongs to the label, presence belongs to the group. A group is not a type-ref: it cannot appear in field-type, element, argument, or variant positions, so multiplicity around a group (`[( a: T | b: U )]`) is not expressible; repetition of alternatives is written as an array of a named choice type (§5.4).

**Resolution.** Groups flatten. Each member becomes an ordinary `record_field` in the body's `fields` list — in source order, contiguous with its sibling members — with `state: OPTIONAL` regardless of group state. The grouping is recorded in the body's `record.groups` list as a `field_group { members  state }` entry, members in source order (the default `REQUIRED` is omitted in output, per §8.1's convention). The flattened fields-plus-groups form is canonical: group membership is fully derivable from `groups` in one local pass, and implementations SHOULD compile it into a per-record lookup at schema-load time, per the eager-resolution convention of §5.2 and §7.4 — the output form is canonical, not operational.

**Validation.** For each group of a record type, the validator counts present members after ordinary field validation: a REQUIRED group with zero or with two or more present members is a validation error; an OPTIONAL group errs only on two or more. A present member is validated as an ordinary field of its declared type.

**Refinement and composition.** In a refinement or composition body, members are addressable by name as ordinary fields under §5.7's rules — an inherited member is OPTIONAL, so it may be tightened to any state the transition table permits, including `= _` (forbidding that alternative's value, §5.2). Group presence rules are checked against the refined states at schema load: a refinement under which two members of one group are always present (both in a REQUIRED-family state) is a resolver error. A body entry may also restate a group: the restated group MUST have the same member labels in the same order (member type-refs restated verbatim), and may tighten state OPTIONAL→REQUIRED; REQUIRED→OPTIONAL is a resolver error, and changing membership is a resolver error. Supertypes contribute their groups whole; the composed entry's `groups` lists inherited groups in supertype order followed by the body's own. A field belongs to at most one group — guaranteed by label disjointness. A removal clause naming a member (§5.9) removes it from `fields` and from its group's `members`; a group reduced to one member is dissolved, and the surviving field takes the group's state (REQUIRED group → field REQUIRED, OPTIONAL group → field OPTIONAL).

**The labelled-sum pattern** (informative). A record whose entire body is a single REQUIRED group admits exactly one field — a labelled sum in record clothing:

```
timestamps => { ( created: timestamp | modified: timestamp | accessed: timestamp ) }
```

An instance is `{ modified: 2026-05-21T13:05:00Z }`; the label is both the semantic role and the discriminator. Where `choice` (§5.4) discriminates by variant type name — via a `!variant` annotation, required except where the choice is disjoint under the encoding's discrimination — and requires distinct variant types, a single-group record discriminates by label and permits variants of the same underlying type, needing no disjointness and no tag. The pattern's kind is PRODUCT; host bindings MAY recognise the shape (one REQUIRED group, no other fields) and lower it to a native sum. [TSON-GUIDE] discusses the pattern and the design history.


## 6. Annotations as Types

[TSON-DATA] §3.1 defines annotation syntax and preservation. This section defines annotation semantics: an annotation is a typed metadata attachment, resolved and validated against a type reachable through the schema chain.

Annotation values are always data values — concrete values, not type definitions — both in data values and within the type-definition grammar. Placement follows [TSON-DATA] §3.1; the resolver preserves annotations in their authored positions. The type-definition grammar adds one position: in `field-def` (§12.1), annotations precede the field name and annotate the field itself, mapping to the `record_field` in resolver output.

In schema declarations, an annotation immediately preceding the declared name binds to the name (the `type_name` token at the declaration's name position), not the `type_definition` value; the resolver does not hoist annotations from key to value. Metadata about the definition must follow `=>`: `name => @doc:"..." {...}`.

**Annotations are types.** An annotation `@T` (or `@T:value`) names a type `T` and attaches it as metadata to the surrounding value. Resolution is one-hop against the governing target's namespace (§3.3.3): the `!!meta` target for a schema document, the `!!schema` target for a data document. `!!import` entries and local entries of the document being authored are NOT part of the annotation namespace. The value is validated against `T`'s contract:

- For `void`-targeted `T` (a type whose resolved body, after reference flattening, is `void` — such as `annotation` or `numeric`), the annotation form is `@T` with no colon and no value. Bare `@T` is shorthand for `@T:_`; the resolver fills the implicit `_` and validates against `void`'s contract (§4.2) — presence is the information.
- For any non-`void` `T`, the form is `@T:value`, where `value` is a single data-value conforming to `T`: `@doc:"User's full name"`, `@ordered:TOTAL`, `@since:2025-01`.

Any type in the governing target's namespace can be used as an annotation — there is no separate annotation namespace. The one-hop rule is what allows the kernel's `annotation => @annotation void` self-reference to work: the kernel's governing target is itself, and the kernel is pre-loaded before any document is parsed.

**The `@annotation` marker is advisory.** Attaching `@annotation` to a type definition signals "intended as annotation metadata" so tools can surface available annotations; it carries no runtime force, and a type without the marker is no less usable as an annotation.

**Resolver-attached annotations.** Some annotations are attached by the resolver rather than written by the author — most commonly `@alias` (defined in the meta-kernel, with core declaring its own sibling for data documents, §3.3.5): when a reference is flattened (§8.3), the resolver attaches `@alias:name` to the resolved type, naming the source-level alias used at the reference site. Resolver-attached annotations follow the same resolution rules as user-written ones.


## 7. Text Encoding Rules: Data Values Under a Schema

This section is the text format's **encoding rules**: how the type system's values are carried in TSON text. Every encoding of the type system owns a section shaped like this one — its atom lexical forms, its container syntax, its treatment of absence, and its discrimination predicate over the derived disjointness fact (§5.4). This section states TSON text's rules; future encodings (a JSON encoding, for instance) state their own against the same encoding-independent model, in their own encoding-rules documents.

[TSON-DATA] defines the syntax of data values and their schemaless interpretation. This section defines the `!!schema` directive (§7.1) and how an active schema changes interpretation: type-annotation resolution (§7.2), atom parsing in place of base type resolution (§7.3, §7.4), sets (§7.5), the absent sentinel (§7.6), resolver behaviours at typed positions (§7.7), and cross-schema references (§7.8).


### 7.1 The `!!schema` Directive

`!!schema` identifies the schema whose types are available for `!name` references in the value it governs. The directive value is a URL string identifying a published schema.

```
!!schema:"https://example.com/people.tn1?sha256=c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5"
!person { name: Alice age: 30 }
```

**The referent is a schema document.** A `!!schema` URL resolves to a schema document — never to a resolved-schema data document, whose resolver-derived fields cannot be verified from the document alone. Resolved-form documents enter the schema library only through the explicit ingest path (§8.1, §10.1).

On a data document's header, `!!schema` binds the schema for the entire document. On a scoped value — a record field value, map entry value, or array element ([TSON-DATA] §2.3) — it binds the schema for that value alone: the referenced schema becomes the active scope for all `!name` annotations within that value and its descendants, reverting to the enclosing scope when the value ends. Directives remain excluded before map keys, field names, and annotation values ([TSON-DATA] §3.3); §7.8 defines the typed-position rules.

**The directive names a namespace, not a root contract.** `!!schema` supplies the vocabulary against which `!name` annotations resolve; it does not assert the governed value's type, and a schema has no privileged entry point. The value names its own type by annotation. Encoders SHOULD annotate the value a `!!schema` directive governs with the type it instantiates; an unannotated value under a bound schema is legal but vocabulary-only — validation engages only where annotations appear within it. At extern-matched positions the annotation is not optional (§7.8).

A scoped `!!schema` at a position whose type is constrained by the outer schema is a resolver error unless the outer type is permissive (`extern`, `value`, `unknown`, or a container thereof) — see §7.8.

Schema documents do not carry `!!schema`: a schema's governing contract is declared by `!!meta` (§2.2.2). A document with no `!!schema` directive has no type vocabulary: base type resolution ([TSON-DATA] §4) applies, type annotations are limited to the built-in vocabulary of [TSON-DATA] §5, and any other type annotation is preserved unresolved — applications SHOULD treat unresolved type annotations as informational.


### 7.2 Type Annotation Resolution

In data values, a type annotation (`!name`) marks **instantiation** — the value is concrete data conforming to the named type. The name resolves against the external schema identified by the current `!!schema` directive — never against definitions within the same document (§3).

**The built-in vocabulary does not apply in schema scope.** When a schema is in scope, all type annotations MUST resolve through the schema's type-name namespace; a built-in annotation name not defined by the active schema is an unresolved-type error. Schemas wanting `uuid`, `base64`, `datetime`, and the other built-in names import the core type library or define them locally. This is the normative statement of the scoping rule; [TSON-DATA] §5.1 restates it for schemaless processors. A document with no `!!schema` falls back to schemaless processing (§7.1).

**Records are closed under their type.** When a schema is in scope and a record's type is known, the record MUST contain only fields defined by its type; fields not present in the type definition are validation errors. This applies to directly-typed records (`!person { ... }`) and structurally-positioned records (a record at a record-typed field position). Schemaless records have no closure rule. Closure is what makes schema evolution a discrete operation: every published schema version is a precise, immutable contract about what fields exist (§3.5).

**Type expression syntax is not available in data values** ([TSON-DATA] §3.2). To annotate an array or map value with a named type, declare a named type in the schema (`int_list => [integer]`) and write `!int_list [1 2 4 8 32]`.

**Validation follows what the name is.** `!T value` asserts that the value conforms to `T`, and conformance is determined by `T`'s own definition. An **atom instance** validates a single token by its parsing contract (§7.4): `!age 42`, never `!age { ... }`. A **product** validates a record against its field list; a **choice** validates any conforming variant. A **constructor** is a record-shaped type, so it validates a record against its constraint-field vocabulary: `!integer_type { min: 0  max: 255 }` is a record conforming to `integer_type`'s fields, receiving ordinary record validation; family coherence between bindings (e.g. `min ≤ max`) is a compilation and ingest concern (§8), not data validation.

**Subsumption at typed positions.** At a position whose declared type is `T`, a value annotated `!S` is valid if and only if, after reference flattening of both (§8.3), `S` is `T` or `T` appears in `S`'s transitive `type_definition.supertypes`. The check reads the contract index only — never the body's lineage `record.supertypes` (§5.9, §8.1) — so a subtracted type does not stand where its source is expected. An admitted value validates against `S` in full: closure, constraints, and defaults are `S`'s; the position's `T` contributed admission alone. An unannotated value at a typed position is validated as exactly `T`, so the annotation carries information precisely when `S` is a proper subtype — there is no structural recovery of `S`, per the once-only reading of form ([TSON-DATA] §2.4). This is the data-level payoff of composition in an application schema: a field typed `person` admits `!employee` where the schema declares `employee => person & { ... }`, and without the annotation the value is a `person`, full stop. Choice-typed positions discriminate by variant membership (§5.4) and extern-matched positions by the foreign schema's namespace (§7.8) — the same surface gesture, an annotation selecting a more specific type than the position promises, under their own membership relations; this rule governs every other typed position. It is also what admits resolver-output bodies at the `top`-typed `body` field: every body annotation head carries `top` in its transitive supertypes — constructors through their base kinds, `reference` through its direct composition with `top` (§4.1, §8.1, §9).

**There is no construction in data.** `!C { bindings }` produces a new type only in the schema grammar (§5.5); the same surface shape in a data document is a record that *describes* constraints — which is precisely what resolver output stores in `type_definition.body` (§8). The two category errors are symmetric, in data as in schema: a constructor never types its family's atom values (`!integer_type 42` is a type error — the value type is the instance, `!integer 42`), and an instance never types records (`!age { min: 0 }` is a type error — the constraint vocabulary belongs to the constructor).

**Parameterized heads over binding records.** A type with a non-empty `parameters` list may annotate a data value if and only if every occurrence of its parameters in its vocabulary is a `value_param` member (§5.10) — the parameterized constructors (`array`, `map`, `set`, `vector`) and their refinement templates, whose open slots all ride the value channel (§4.2). Validation is ordinary record validation against the vocabulary: each field is checked against its declared type — a type slot against `type_ref` — and a `value_param` member imposes nothing at the data level — it exists for substitution and ingest recomputation. The field's state is ordinary: `= P` leaves the field REQUIRED (§5.7), so the binding record supplies it exactly as data supplies any required field, and `~ P` leaves REQUIRED_DEFAULT. This is what makes resolver-output bodies (`!array { element_type: person }`, `!map { key_type: … }`, §8.1) ordinary, validatable data. A template with any parameter in a type channel (`container<T>`, whose `T` sits inside a field's *type*) remains a resolver error as a data annotation, per §5.10.

**Schema values.** The type annotation `!schema` marks a map as a schema value — a regular type annotation; `schema` is defined in the meta-kernel as `map<type_name, type_definition>`. It appears on data documents that carry resolved schema structure, most notably resolver output (§8); schema-document source never carries it (§2.1).


### 7.3 Atom Parsing Replaces Base Type Resolution

When a schema is in scope, base type resolution ([TSON-DATA] §4) does not apply at typed positions: each position's declared atom type owns its own parsing contract (§7.4). The tokens `true`, `false`, and `null` have no special status when a schema is in scope — their meaning is determined entirely by the position's type.

**`null` at `void`-typed positions.** The sole exception is a position whose declared type resolves, after reference flattening (§8.3), to an entry bearing the void parsing contract — the kernel's `void`, core's sibling redeclaration of it (§3.3.5, §9), or the counterpart an alternative meta layer defines (§3.4). The concession follows the contract, not the name: the siblings are distinct type entities that state the same contract. At such a position the token `null` is accepted as an equivalent spelling of the absent sentinel `_` and normalised to absence. The concession is local to `void` — it has a single inhabitant, so no absence-vs-value distinction is lost — and does not change `null`'s meaning elsewhere. Authors SHOULD write `_`; a `void` position round-trips to `_`. This also covers JSON-shaped data under a schema: a JSON `null` at a `void`-typed position is accepted as absence; everywhere else it must satisfy the position's declared type.


### 7.4 Atom Token Parsing

Each atom type owns its parsing contract. When a token appears at a position whose type is an atom, the atom's parser takes the token and produces either a typed host value or a parse error; the atom's constraint record is applied as validation after parsing succeeds.

**Parsing and validation are distinct.** Parsing takes a token to a host value; validation checks the host value against the constraint record. `twelve` at an `integer`-typed field is a parse error; `300` at a field typed `age` (refining `integer` with `{min: 0 max: 150}`) parses as an integer, then fails validation. Implementations SHOULD distinguish these in error reporting ([TSON-DATA] §8.1).

**Enum member semantics.** The `enum` atom's `members` field is a `set<token>` enumerating the lexical tokens permitted at an enum-typed position. Parsing is a token-identity check against the member tokens (canonicalised per `token`'s contract, below); the resolved host value is determined by natural parsing of the matched token — `true`/`false` in core's `boolean` resolve to native host booleans; members of user-defined enums resolve to the member token as host text, or a host-language enum value where the implementation provides a mapping. `members` describes the permitted lexemes, not the resolved representation.

**The `token` primitive.** A `token` value is the NFC-canonical form of a source lexeme: unquoted lexemes are NFC by rule ([TSON-DATA] §7.2.1 — a non-NFC unquoted token is a lexer error), and quoted lexemes at identifier positions are NFC-normalised by the resolver. Two tokens are equal iff their canonical forms are byte-identical; tokens are whitespace-free by construction. `token` shares its host representation with `text` but differs in contract: it rejects whitespace and requires NFC, so a `token` value can always be rendered back as an unquoted lexeme. Use `token` where the value must be a valid TSON identifier (map keys authored as bare lexemes, enum members, identifier-like fields); use `text` for free-form content. `token` is not used in data values; it appears in the kernel's own declarations (`enum.members`, `type_name`, `field_name`, `param_name`).

**Number-grammar reuse.** Atoms that parse numeric values SHOULD use the number grammar of [TSON-DATA] §7.6 for the relevant numeric form; a single shared number parser dispatching on the atom's declared form is the expected pattern.

**Constraint fields typed as `value`.** Some atom constructors declare constraint fields with type `value` because the constrained atom cannot be referenced at the point of declaration — `decimal_type.min: value?` cannot use `number`, which is a core instance of `decimal_type` (bootstrap ordering, §3.4). The kernel's `integer_type` is the exception that proves the rule: its bounds are typed `integer`, available in the kernel's own namespace. Tokens at `value`-typed positions are parsed by [TSON-DATA] §4 base type resolution, and whatever it produces is what the resolver stores.

Each constrained atom's implementation converts `value`-typed constraint values to its internal representation. Conversion MUST occur at schema-load time, not per-validation; an atom that cannot convert a given constraint value MUST report a resolver error at schema-load time — a schema either loads cleanly or fails with a clear diagnostic, and a half-valid schema that silently mis-validates is never produced. Which constraint-value types an implementation accepts for conversion is an implementation choice; the validation semantics after conversion are the atom's contract.

**Lexical classes vs type names.** The categories of [TSON-DATA] §4 base type resolution (null, boolean, integer, float, string) are lexical classifications of tokens, not type names: the lexical class and the core type `number` are distinct namespaces that share a word. A schema cannot reference a lexical class, and base resolution never produces a declared type; when a schema is in scope, the schemaless dispatch does not apply (§7.3).


### 7.5 Sets

A **set** is an unordered collection of unique values. Sets share array syntax `[ ... ]` at the data level ([TSON-DATA] §2.7); set-ness is a schema property declared via the `set` constructor (§4.2). Without a schema, every `[ ... ]` is an array.

**Duplicate handling.** When source data contains a value more than once at a set-typed position, duplicates are silently deduped — first occurrence wins. The resolver SHOULD warn when dedup occurs. Two values are duplicates if the element type's equality contract considers them equal (token identity for `set<token>`, value equality for `set<integer>`).

**Element order.** Sets are unordered; the materialised representation uses array syntax, but element order is implementation-defined. Implementations comparing resolver outputs MUST compare set-typed fields as sets, not ordered lists; fixture-comparison tools SHOULD canonicalise set-typed fields (e.g. lexical sort) before byte-comparison.

These rules apply uniformly to every set-typed position: the kernel's `enum.members`, user-defined set-typed fields, and set values produced by the positional fill rule (§5.6). An absent element at a set-typed position is rejected — `set` pins `state: = REQUIRED` (§4.2).


### 7.6 The Absent Sentinel Under a Schema

[TSON-DATA] §2.9 defines the absent sentinel and its data-value positions. `_` is not itself a type; the type whose sole conforming value is `_` is `void` (§4.2), and at a `void`-typed position the token `null` is also accepted (§7.3). Everywhere else `_` (absence) and the base value `null` remain distinct.

This document extends the position rules of [TSON-DATA] §2.9:

| Position                                  | `_` permitted? | Meaning                                                                                          |
|-------------------------------------------|----------------|--------------------------------------------------------------------------------------------------|
| Array element (schema in scope)           | conditional    | Permitted only when the array type's element state is OPTIONAL, written `[T?]` (§5.3)            |
| Tuple element (schema in scope)           | conditional    | Permitted only when the position's state is OPTIONAL (§5.3); the absent element occupies its slot |
| Record field value (schema in scope)      | conditional    | Permitted when the field's state admits absence: OPTIONAL, or OPTIONAL_FIXED with `= _` (§5.2). At REQUIRED_DEFAULT, treated as not-supplied — warn and inject the default (§5.2); at REQUIRED and REQUIRED_FIXED, a validation error |
| Map key (schema in scope)                 | no             | Resolver error — restates [TSON-DATA] §2.9's resolver-layer rule; keys define membership and are always required |
| Map entry value (schema in scope)         | yes            | Entry present with an absent value ([TSON-DATA] §2.9); `map` carries no element-state facet, so the permission is not schema-conditional |
| Field-type position (type-definition grammar) | no         | Parse error — use type parameters (§5.10)                                                        |
| Type-ref position (type-definition grammar) | no           | Parse error                                                                                      |
| Type-def body (declaration right-hand side) | no             | Parse error — use `{}` for an empty record                                                       |
| Field modifier value (`~`/`=`)            | `=` only       | `= _` valid on OPTIONAL fields; `~ _`, and `= _` on REQUIRED fields, are resolver errors (§5.2) |

When a schema is in scope, absence at an element position requires the governing container type to permit it. Absent elements occupy positional slots, and size constraints count all slots. For tuples, OPTIONAL positions require explicit `_` — the tuple's length is fixed by its type, and short tuples are validation errors (§5.3).


### 7.7 Resolver Behaviours at Typed Positions

**Typed key equality.** [TSON-DATA] §2.6 defines parser-layer (textual) duplicate-key detection for maps. When the key type is known, the resolver MAY additionally apply type-specific equality and SHOULD warn when it detects duplicates the parser did not. The MAY is deliberate: once keys are realised as host-language values, equality semantics are determined by the host's collection types.

**Empty braces.** [TSON-DATA] §2.8 defers empty-brace disambiguation to declared type information. Under a schema, the expected type at the position supplies it: the resolver transforms an empty-brace value into an empty record or empty map per the expected type, defaulting to an empty record when the position is untyped.


### 7.8 Cross-Schema Type References

A type definition may reference types from a different schema through the `extern` constructor (defined in `meta.tn1`). An `extern` type carries a `schema` field (a URL identifying the external schema) and an optional `types` field (a list of permitted type references from that schema). When `types` is absent, any type from the external schema is accepted; when present, only the listed types are accepted.

`extern` is a sum constructor — its membership is the set of types defined in the named schema, optionally narrowed by `types`. Where `choice` enumerates variants explicitly, `extern` defers to an external schema for the variant set. The companion type `unknown` (in `core.tn1`, produced as `!unknown_type {}`) is a sum instance with universe membership — any well-formed value of any type, with no constraint on the type's source. `unknown` is the right tool when the parent schema has no contract at all on the data; `extern` when it knows the data belongs to a specific external schema but does not import it.

At the data level, values matched by an `extern` field MUST carry their own `!!schema` directive identifying the external schema and a `!type` annotation identifying the type within it — schema scope changes are always visible in the data, never implicit. A `!!schema` directive on a scoped value pushes the new schema scope for that value; when the value ends, the scope reverts:

```
!!schema:"https://tson.io/2026/medical/patient.tn1?sha256=a4f2e8d1c3b5a7f9e2d4c6b8a0f1e3d5c7b9a2f4e6d8c0b3a5f7e9d1c4b6a8f0"
!patient_record {
  patient: "1234"
  attachments: [
    !!schema:"https://tson.io/2026/insurance/claim.tn1?sha256=f8b2a1d3c5e7f9a1b3d5e7f9a2b4d6e8f0a3b5d7e9f1a4b6d8e0f2a5b7d9e1f3"
    !insurance_claim { claim_id: CLM-5678  amount: 450.00  provider: "City Medical" }
    !!schema:"https://tson.io/2026/radiology/report.tn1?sha256=d4e9c7f1a3b5d7e9f2a4b6d8e0f3a5b7d9e1f4a6b8d0e2f5a7b9d1e3f6a8b0d2"
    !radiology_report { study_id: RAD-9012  modality: MRI  findings: Normal }
  ]
}
```

Each directive binds to the single array element it prefixes ([TSON-DATA] §2.3, §2.7); the example follows the encoder recommendation of one directive-carrying element per line.

**Composition order under a scoped element.** Within a scoped value the order is fixed by the grammar: directive, then annotations, then the optional type annotation, then the core value ([TSON-DATA] §2.3, §3.1). The directive opens its scope before the element's own augmentation resolves: annotations on a scoped element resolve against the newly scoped schema's namespace (§3.3.3), and the type annotation resolves against its type-name namespace.

**The discriminant is required at extern positions.** At an extern-matched position, the `!type` annotation is the sum's discriminant and MUST be present: a scoped value that opens a schema scope but names no type is a validation error there. Everywhere else the general rule of §7.1 applies.

For multi-schema heterogeneous arrays, declare the field as `[extern]` — an array of `extern`, each element carrying its own `!!schema` and `!type` annotations.

**Typed-position restriction.** A nested `!!schema` directive at a position whose type is constrained by the outer schema is a resolver error unless the outer type is one of the permissive types: `extern`, `value`, `unknown`, or a container thereof (`[extern]`, `map<text, value>`). The check is per-position: for a record field or map entry value the field or entry type applies; for an array element, the element type applies. The outer schema must opt in to receiving foreign values at each position where schema switching is permitted — cross-schema acceptance is authored intent, not accident. Schemaless outer documents have no type expectations and always permit nested `!!schema` directives.


## 8. Resolver Output

The resolver's output for a schema is a map of `type_definition` records: the output records and their conventions (§8.1), template instantiation (§8.2), and references and flattening (§8.3). Each construct's mapping to its `type_definition` is defined with the construct in §4–§5.


### 8.1 Output Records: `type_definition`, `type_ref`, `type_argument`

The `type_definition` record captures the resolver's output for any type. Its fields: `source` (structured provenance; below), `kind` (ATOM, PRODUCT, SUM, or REFERENCE), `parameters` (declared parameter names; non-empty marks a template, §5.10), `constructor` (`true` when declared with `~`), `supertypes` and `subtypes` (name-level indexes, resolver-managed; below), `disjoint` (resolver-derived, sum definitions only; below), and `body` (required, declared as `top`). Body values are annotated with the structurally-appropriate type: `!record` for vocabulary bodies (constructors and open non-reference templates, §5.10), a binding record headed by the nearest `~` constructor for every closed non-reference definition (§5.6), and `!reference { target: T }` for reference-kind entries. The `top` declaration is sufficient by the subsumption rule of §7.2: every body annotation head carries `top` in its transitive `supertypes` — constructors through their base kinds (§4.1), the kernel's `reference` through its direct composition with `top` — so the parser validates body annotations without dependent typing.

**The `type_ref` and `type_argument` records.** Every type-reference position in resolver output — `record_field.type`, `tuple_element.element_type`, `choice.variants` elements, `type_definition.source`, the type slots of binding records (§5.6), and the resolved `value` of a `type_ref`-typed pin in vocabulary bodies (§4.2, §5.10) — holds a value of the kernel's `type_ref` record; applications carry their arguments as `type_argument` records:

```
type_ref => {
  name:       type_name
  arguments:  [type_argument]?
}

type_argument => {
  ( name: type_ref | value: value )
}
```

A `type_ref`'s `name` is the referenced type or, within a template body, a parameter, and `arguments` carries the application's positional arguments. A `type_argument`'s `name` member holds every *reference* — a type, an entry, or (in template bodies) a parameter of either kind — while its `value` member holds concrete literals only; exactly one member is present (the group is REQUIRED, §5.11). The reference/literal split is therefore structural: a token in a `name` member is always a reference, so value-typed token literals (enum members) can never be mistaken for parameter names, and member population is the open/closed signal — substitution replaces `{ name: P }` with the bound argument (`{ name: text }`, `{ value: 1920 }`) as parameters close.

**Positional form.** `name` is `type_ref`'s only REQUIRED field, so the general positional form (§5.6) applies at every `type_ref`-typed position: a bare name token fills `name` directly, and a braced record is the explicit form. Canonical output MUST use the bare token whenever `arguments` is absent; an explicit record without `arguments` is valid data but non-canonical. `type_argument` deliberately has *no* REQUIRED field and hence no positional form: a bare token cannot self-classify as reference or literal, so its braced record is load-bearing, not ceremony.

**Reading parameter references.** Parameters and type names share the lexical class `token`, so a `name` — in any `type_ref`, at any depth, including `type_argument` name members and the `source` field — resolves against the enclosing `type_definition.parameters` list first, then the schema's type-name namespace, the same precedence used during source-level parsing (§5.10); a name at a constructor-channel position — a construction's `source` head (`source: enum`) or an application head that arose from a sugar or generic form (`array`, `map`, `set`, `vector`) — resolves through the structure namespace (§3.3.1), the same channel that resolved it at source level. The two channels are consulted at disjoint positions and never mix (§2.2.3): type-name tokens are data scoped to the schema being described, while annotation and constructor heads belong to the governing chain. Parameter routes and defaults additionally appear as `value_param` members on `record_field` (§5.7, §5.10) — a type parameter when the routed field is `type_ref`-typed, a value parameter when it is scalar. Only template entries may contain parameter references (the closed-entry rule, §5.10); a consumer holding an entry with empty `parameters` interprets every name directly against the schema.

**`source` is structured provenance.** `source` records where a definition came from, as a `type_ref` — a bare name under the positional form in the common cases, the applied form when the origin was an application. Concretely: a construction records the constructor (`source: enum`); an atom refinement records the instance's constructor (`source: integer_type`, §5.5); a `^` refinement records the source name; a composition records **no** `source` — its provenance is the direct `&` list in the body's `record.supertypes` (below); a refinement of, construction from, or reference to an *application* records the application itself (`set`: `source: { name: array  arguments: [ { name: T } ] }`; the constructions `lookup => map<text, integer>` and `string_vec => vector<text, 3>`: the map and vector applications, as written); and a materialised instantiation records the fully-bound application with reference chains and nested instantiation arguments flattened to entry names (§8.3), which makes the entry **self-describing**: its body is recomputable by substitution from its `source`, and identity comparison is single-level structural equality of `source` (§8.2).

**`supertypes` and `subtypes` are name-level indexes**, resolver-managed; declarations never set them. Each answers a one-hop question — "is this in family X?", "what is in family X?" — by name; the structured truth lives in `source` and the bodies, so a parameterized supertype contributes its *head name* to the index while its arguments travel through the absorbed fields (§5.8). Their standing differs. `subtypes` is a cache: fully derivable, always recomputable, never trusted — the resolver MUST compute it as the transitive inverse of `supertypes` across the schema's namespace. `supertypes` is derivable from `body` for product types (the body's `record.supertypes` carries the direct compositions) but NOT for the atom family: desugaring erases the surface distinction between refining an instance (`age => !integer ^ { min: 0 max: 150 }`, IS-A `integer`) and constructing a fresh sibling (`port => !integer_type { min: 0 max: 65535 }`, IS-A nothing) — both serialize to `source: integer_type` with an identical body shape. The atom family's direct IS-A hop therefore lives only in `type_definition.supertypes`, making that field part of the type's serialized meaning rather than a recomputable cache. Construction and instantiation are not IS-A: a construction of `vector` (`frame => vector<pixel, 1920>`) records empty `supertypes` (§5.6), and a closure of `array_ranged` records `supertypes: [array product top]` — the template's own supertypes, with no hop to `array_ranged` itself — so "is this a vector?" and "did this come through `array_ranged`?" are `source.name` questions, not `supertypes` ones.

**`disjoint` is a resolver-derived fact over sums.** For a SUM-kind definition, `disjoint: true` records that the resolver proved the variants pairwise disjoint as value sets (§5.4); the field is absent when disjointness is refuted or merely unprovable, and absent on all non-sum definitions. Like `subtypes` it is a cache — fully recomputable, never trusted: on ingest (below) it MUST be discarded and recomputed, never taken from the document. It carries the encoding-independent fact that each encoding's discrimination rules consume to decide whether a `!variant` tag may be dropped; the `@disjoint` annotation (§5.4), when present, is the author's assertion checked against this derived field at schema load, and does not appear in the field itself.

**Two `supertypes` fields with different semantics.** `type_definition.supertypes` records the **transitive** IS-A chain — direct parents plus each parent's chain, deduplicated; construction via `!T {}` does not contribute. The body's `record.supertypes` records only the **direct** `&` compositions as written. Consumers use `type_definition.supertypes` for IS-A queries and `record.supertypes` to recover source-level composition. Example: `text_type => ~atom & { ... }` produces `type_definition.supertypes: [atom, top]` and `body: !record { supertypes: [atom], ... }`.

**Ingest.** When `!type_definition` records are ingested as data (§10.1): the resolved document is a *data* document, and its header admits only `!!id` and `!!schema` ([TSON-DATA] §2.2, §3.3) — it cannot carry `!!import`, and its `!!schema` names its own governing chain, not the schema it describes. The described schema's identity, and the import list that constitutes the namespace in which the map's type-name tokens are interpreted (§2.2.3), are therefore not recoverable from the resolved document and MUST be supplied to ingest explicitly; the source schema document's header, held in the library (§10.1), is the sole authority for both. Then: `subtypes` and `disjoint` MUST be discarded and recomputed; `supertypes` is taken as input, with the transitive closure recomputed and integrity verified — every listed supertype must exist, atom-family supertypes must share the entry's `source` constructor, product-type lists must be consistent with the body's `record.supertypes`, and transitive lists must be closed. The closed-entry rule (§5.10) MUST be verified — no `value_param` members and no parameter-resolving names in an entry with empty `parameters`, and no parameter references of any kind inside binding records (§5.6). Every instantiation entry's body MUST equal the recomputation by substitution from its recorded `source` — the self-describing check. Symmetrically, a construction's binding record MUST agree with its `source` application: each parameter-routed slot of the head's vocabulary carries exactly the corresponding argument (a `!map` body's `key_type` and `value_type` match the application's two arguments, §4.2). Canonical-form violations (an explicit `type_ref` record without `arguments`, a positional form where the convention requires the explicit record) are resolver errors at ingest. Within-family retargeting — a document claiming an entry refines a different sibling of the same constructor — is internally consistent and undetectable from the document alone; this residual gap is one reason resolved-form documents are never schema sources (§10.1) and ingest is an explicit, opt-in act.

**Body patterns** (informative — the construct sections govern):

| Source form | Resolved `type_definition` | Examples |
|---|---|---|
| Root record `{}` | PRODUCT; `body: !record { fields: [] }`; no supertypes | `top` |
| Base kind `top & {}` | PRODUCT; `supertypes: [top]` | `atom`, `sum` |
| Fresh record `{ fields }` | PRODUCT; `body: !record { fields: [...] }`; no supertypes | `person`, `record_field` |
| Composition `A & B & { ... }` | `supertypes: [A B ...transitive]`; kind per §5.5 | `employee` |
| Refinement `T ^ { ... }` | `source` per §5.7; `supertypes: [T ...]`; materialised body | `production` |
| Subtraction `T - { f }` | `type_definition.supertypes` empty; lineage in `record.supertypes` (§5.9) | `account_public` |
| Atom constructor `~atom & { ... }` | ATOM; `constructor: true`; `supertypes: [atom top]` | `integer_type`, `enum` |
| Product constructor `~product & { ... }` | PRODUCT; `constructor: true`; `supertypes: [product top]` | `record`, `array` |
| Sum constructor `~sum & { ... }` | SUM; `constructor: true`; `supertypes: [sum top]` | `choice`, `extern` |
| Constructor refinement `~T<P> ^ { ... }` | `constructor: true`; `source`: the application; `supertypes: [T ...]` | `set`, `vector` |
| Refinement template `<T, S> C<T> ^ { ... }` | `parameters` non-empty; vocabulary `!record` body with `value_param` members; `supertypes: [C ...]` (§5.10) | `array_min`, `array_ranged` |
| Constructor instance `!T {}` | kind from `T`'s family; `source: T`; no supertypes; `body: !T {}` | `integer`, `value`, `unknown` |
| Atom refinement `!I ^ { v }` | `source: I`'s constructor; `supertypes: [I ...]`; `body: !ctor { v }` | `age` |
| Top-level constructor application `C<args>` | PRODUCT construction; `source`: the application; binding-record body; no supertypes (§5.6) | `lookup => map<text, integer>`, `schema` |
| Record template `<T> { ... }` | `parameters` non-empty; not instantiable; vocabulary body (§5.10) | `container` |
| Inline form at a use site | no entry; represented in place as a `type_ref` (§5.3) | `[text]`, `map<text, integer>` |
| Template instantiation | internally named entry; `source`: the flattened application; binding-record body (§8.2) | closures of `array_ranged`, `tree` |
| Choice `(A \| B)` | SUM; `body: !choice { variants: [...] }`; `disjoint: true` when derived (§5.4) | `contact_method` |
| Field group `( a: T \| b: U )` | members flattened into `fields` as OPTIONAL; grouping in `record.groups` (§5.11) | `integer_type`, `type_argument` |
| Reference `name` | REFERENCE; `body: !reference { target: name }`; no supertypes | `id => uuid` (§8.3) |

The `schema` type is a map from type names to type definitions; schema lookup is by name. Declared entries are keyed by their declared names; instantiation entries are keyed by their internal names (§8.2).


### 8.2 Template Instantiation

Constructor applications never materialise entries — they are carried structurally wherever they occur (§5.3) and, as declaration bodies, resolve in place as constructions (§5.6). Exactly one form materialises: a **fully-bound application of a non-constructor template** (§5.10). A template instantiation is a genuine new `type_definition` — the product of substitution, with its own bindings — and recursion, dedup, and reference targets need it to be an entry.

*Trigger positions.* Instantiation fires wherever a fully-bound template application appears: record field types, group member types, tuple element types, array element types, choice variants, arguments at any depth, and a top-level type-def body that is a template application (which resolves the declaration to a REFERENCE entry targeting the instantiation, §8.3). Composition targets and refinement sources remain restricted to named type references, optionally with arguments; inline structural forms there are resolver errors (§5.8, §12.1). An *open* application never materialises — it remains structural inside its template's body (§5.10).

*Identity.* Within a schema, two fully-bound applications denote the same entry if and only if their **flattened applications are structurally equal**: the application with every reference chain resolved to its terminal entry name and every nested fully-bound template application resolved to its instantiation's entry name (§8.3), compared member-by-member — heads, argument order, `name` against `name`, `value` against `value` under the argument types' own equality. The flattened application is exactly what the instantiation's `source` records, so identity is a single-level structural comparison of `source` values, and spelling variance never forks an entry: `[order]` and `array<order>` in argument positions, aliased and terminal names, and whitespace all land on the same entry. The first occurrence materialises; later occurrences reuse; recursive references resolve to the entry's own name (§5.10.1).

*Entry names are internal.* Instantiation entries are named by the resolver, and the names are **not part of this specification's conformance surface**: an implementation chooses them freely, subject to two rules and one recommendation. Freshness (MUST): an internal name is a valid `token` and collides with no declared entry and no other internal entry. Stability within a resolution (MUST): all references to one instantiation use one name. Determinism (SHOULD): names derived from the flattened application's content — a readable head plus a structural hash, for instance — keep re-resolution output diff-stable. Internal names do not exist at source level (there is nothing to author), MUST NOT be relied on across resolutions or implementations, and resolved-form comparison between implementations is structural — equality up to renaming of instantiation entries.

*Entry shape.* `source`: the flattened fully-bound application; `kind`: the template's kind; `parameters`: empty (the entry is closed, §5.10); `supertypes`: the template's supertypes, unchanged by substitution (§8.1); `body`: the substituted binding record, headed by the nearest `~` constructor in the source chain (§5.6). Materialisation also runs the value-level checks that open bounds deferred: family coherence rules whose operands were parameters — the array family's `min_items ≤ max_items` (§5.3), bounds within a width-derived range, and their kin — are verified once the substituted values are concrete, and a violation is a resolver error reported at the materialising application. For the kernel's `array_ranged => <T, MIN, MAX> array<T> ^ { min_items: = MIN  max_items: = MAX }` applied as `[pixel; 1920]` — the sugar spelling of `array_ranged<pixel, 1920, 1920>` (§5.3) — a resolver might produce:

```
array_ranged_pixel_af3 => !type_definition {
  kind: PRODUCT
  source: { name: array_ranged  arguments: [ { name: pixel }  { value: 1920 }  { value: 1920 } ] }
  supertypes: [array product top]
  body: !array { element_type: pixel  min_items: 1920  max_items: 1920 }
}
```

*Non-exposure.* Instantiation entries are resolver-materialised, not declared. They cannot be named from any schema source and MUST NOT participate in `!!import` resolution: imports merge declared entries only (§2.2.3); an importing schema applying the same imported template materialises its own entry. Authors reach the type by writing the application (reusing the entry) or by declaring a named alias (§8.3).

*Diagnostics and cross-schema identity.* Internal names MUST NOT be the primary form shown to users: diagnostics surface the source application — recoverable from the entry's `source`, the originating source position, and `@alias` where recorded. Instantiation entries are per-schema internals; cross-schema identity is through named declarations or not at all.


### 8.3 References and Flattening

A type-def body that is purely a type *reference* — a bare name or a template application, with no body record, no `&`, and no construction — produces a REFERENCE-kind entry with body `!reference { target: T }`, where `target` names an entry, and `source` records the referent as written (§8.1):

- **Simple leaf** (`id => uuid`): `source: uuid`, `body: !reference { target: uuid }`. The `target` is the immediate referent, not the ultimate type — reference chains (`doc → documentation → text`) appear as distinct entries, each pointing one hop forward.
- **Fully-bound template application** (`string_triple => array_ranged<text, 3, 3>`, equivalently `[text; 3]` in sugar spelling, §5.3): the application is materialised (§8.2); `source` records it as written; `target` is the instantiation's internal entry name.
- **Open template application** (`text_keyed_map => <V> map<text, V>`, `row => <T, N> [T; N]` — an open `array_ranged` application, §5.3): the declaration is itself a template (§5.10); `source` records the open application; for a template head, `target` holds the head name as the pending index. Reference-kind templates compose during substitution without materialising intermediate entries.

A top-level *constructor* application is not a reference — it resolves as a construction in place (§5.6).

**Resolution at use sites.** When a reference is used — as a field type, tuple element, choice variant, argument, binding-record type-slot value, or data-mode type annotation — the resolver flattens it: it walks the reference chain to the first non-REFERENCE entry, rewrites the use-site name to that entry's name, and attaches an `@alias` annotation recording the source-level name:

```
!record_field { name: owner   type: @alias:id uuid }
!record_field { name: batch   type: @alias:string_triple array_ranged_text_9d4 }
```

The alias attaches to the type value, not the `record_field` — it describes the type reference, not the field. Only the source-site alias is preserved; intermediate hops in a chain are not aliased on the use-site type (they remain visible as schema entries). Flattening applies recursively inside `arguments` — every name in a flattened application is a terminal entry name — which is what makes instantiation identity a single-level comparison (§8.2). The same flattening applies in data mode: `!id 550e8400-…` resolves to a uuid-typed value with `@alias:id` on the type annotation.

**References are not supertypes.** REFERENCE-kind entries do not contribute to the `supertypes`/`subtypes` graph: `uuid.subtypes` does not list `id`; `id.supertypes` is empty.

**Instantiation entries are not references.** Instantiation entries (§8.2) carry the substituted template's kind and a binding-record body, not a `!reference` record. A REFERENCE entry may *point at* an instantiation; the instantiation itself is a closed, concrete definition.


## 9. The Meta Layer

Two pre-loaded schemas form the meta layer. Implementations MUST pre-load both (§3.4, §10.1). The normative source for both is carried in the companion artifacts (§1.5), pinned by content hash at publication; the pre-loaded in-memory model is authoritative and the documents are descriptions of it.

| Schema | Role | Declares |
|--------|------|----------|
| `2026/32/m/meta-kernel.tn1` | Self-referencing bootstrap; its `!!id` and `!!meta` reference its own URL | `top`, the base kinds `atom`/`product`/`sum`, `reference`; the `record`/`array`/`set`/`map`/`tuple`/`enum`/`choice` constructors; the `record_field`/`tuple_element`/`field_group`/`type_ref`/`type_argument`/`type_definition`/`schema` supporting records; the `array_min`/`array_max`/`array_ranged` size-refinement templates (§5.3); the `unit` constructor and its instances `value`/`token`/`void`; the identifier references `type_name`/`field_name`/`param_name` (lexical roles over `token`, §7.4); the atom-constructor/instance pairs `integer_type`/`integer` (with the `integer_size` supporting record), `text_type`/`text`, `uri_type`/`uri`, `regex_type`/`regex`; `boolean`; `atom_specification`; the internal enums; the annotation types `annotation`, `documentation`, `doc`, `alias` |
| `2026/32/m/meta.tn1` | Canonical meta-schema; chains to the kernel and imports it | `binary` (with `binary_encoding`), `extern`, `unknown_type`; the `vector` fixed-size array constructor (§5.3); the constraint-vocabulary constructors for numeric (`float_type` with `ieee_format`, `decimal_type`, `rational_type`, `complex_type` with `complex_component`), temporal (`date_type`, `time_type`, `datetime_type`, `duration_type`), identifier (`uuid_type`), network (`ipv4_type`, `ipv6_type`, `cidr4_type`, `cidr6_type`, `mac_type`), and text (`email_type`) families; the annotation types `ordered`, `bounded`, `exact`, `numeric`, `disjoint`, `deprecated`, `since`, `todo`, `lang` |

Each constraint-bearing atom family is a constructor/instance pair (§4.2): the constructor lists the family's constraint fields; the canonical empty instance lives in the kernel or in core (`integer`, `text`, `uri`, `regex`; `number`, `float32`, `float64`, `rational`, `complex`, `date`, and the rest in `core.tn1`). Meta's kernel import is doubly load-bearing: it supplies the kernel types meta's own declarations use and delivers the kernel's structural vocabulary to every meta-governed schema (§3.3.1). Annotation types live in the chain because of the one-hop rule (§3.3.3, §6); core redeclares `void`, `doc`, `documentation`, `annotation`, and `alias` — fresh siblings of the kernel's entries: same constructions, same contracts, distinct type entities (§3.3.5) — for data documents governed by core-importing schemas, and adds `complex` (`!complex_type {}`).

Users normally chain to `meta.tn1`. Schemas that chain to `meta-kernel.tn1` directly are alternative type vocabularies replacing meta, or extensions of the meta layer — the format's sanctioned extension point (§2.2.2).


## 10. Schema Resolution Model

Schema URLs are **logical identifiers first**: a URL names a schema, and resolving it does not by itself require a network request. Implementations resolve schema references through a **schema library** — a local store mapping canonical identities ([TSON-DATA] §2.2.1) to schema content. Fetching is a permitted but opt-in way to *populate* the library (§10.1, §11.2), not the meaning of a reference.


### 10.1 The Schema Library

When the resolver encounters a `!!schema`, `!!meta`, or `!!import` directive, it looks the reference up in the library; if not found, the resolver reports an error — it does not attempt to fetch. The library is populated through three mechanisms, in order of precedence:

- **Pre-loaded schemas.** The implementation ships the meta-kernel and meta-schema as pre-loaded entries and SHOULD ship the core type library. Pre-loaded schemas are authoritative — their in-memory representation takes precedence over any external source (§3.4).
- **Registered schemas.** Applications register schemas before parsing documents that reference them, from local files, embedded resources, or any application-specific source.
- **Fetched schemas (optional).** Implementations MAY support fetching by URL; fetching MUST be explicitly enabled by the application — never the default — and is subject to §11.2. Production systems SHOULD register all required schemas at startup rather than fetch at runtime.

**Identity agreement.** Registration whose target identity differs from the canonical identity of the content's declared `!!id` is an error — the library MUST reject it as identity confusion. The comparison is over canonical identities, so scheme variance agrees; a differing host or path does not. Content with no `!!id` MAY be registered under an application-supplied identity (a development-mode convenience, §2.2.1); content with an `!!id` is registered under that identity and no other.

**Schema sources are schema documents.** Whenever the library is populated from a document — registered or fetched, and for the targets of `!!schema`, `!!meta`, and `!!import` alike — that document MUST classify as a schema document ([TSON-DATA] §2.2). A resolved-schema data document (resolver output, §8) is not a valid schema source: its derived fields cannot be verified from the document alone; it is non-canonical and not hash-pinnable; and, being a data document, its header cannot carry `!!import` — the described schema's namespace is not expressible in it, only in the source schema's own header (§8.1's ingest rule). An implementation MUST reject a data document supplied as the content of a schema URL, with a categorized diagnostic. Resolved-form documents MAY enter the library only through the explicit ingest path (§8.1), which does not take derived fields on trust.


### 10.2 Hash-Pinned References

The hash-parameter URI convention — the algorithm-named query parameter, lowercase full-length hex, the query-is-hash-parameters-only rule, canonical identity, and the mismatch rule — is defined in [TSON-DATA] §2.2.1. This section defines how the library applies it.

Library keys are canonical identities: a plain reference, its `http`/`https` variant, and its hash-pinned form all name the same entry — the pinned form additionally demands verification. When a hashed reference resolves, the implementation MUST verify the library's content against the declared hash before use; a mismatch is a resolver error, and the library MUST NOT silently substitute mismatched content. When registration supplies a document whose declared `!!id` itself carries a hash, the implementation SHOULD verify at registration time.

**Hashes attach to the canonical entry.** Verification is a property of the canonical identity, not of each reference. Within a single resolution's reference closure (§3.4.1), the digests declared across all references to one identity form a set, and the set determines the identity's treatment. An empty set resolves unverified. A single digest is verified once against the entry's content, before any reference to the identity resolves; every reference — pinned and plain alike — then resolves to the verified entry. A plain reference alongside a pinned one is therefore not a conflict but an inheritance: it rides the pin, and it inherits the pin's failure — a verification failure applies to the identity for the whole resolution, and a plain reference MUST NOT proceed with content a declared digest has rejected. Two distinct digests for one identity is a resolver error: at most one describes the real bytes, and the library MUST NOT choose between them. A verified entry, once cached under its canonical identity, is immutable. A *failed* verification SHOULD NOT be cached under the canonical identity; an implementation MAY maintain a separate negative cache keyed on the full reference string.

**Pins to pre-loaded identities.** Pre-loaded schemas resolve to in-memory structures (§3.4, §10.1), but a pinned reference to a pre-loaded identity is an ordinary pinned reference and MUST be verifiable: an implementation MUST hold, for each pre-loaded schema, the published content digest (or the canonical document bytes) of the artifact its structures implement, and MUST verify declared pins against it — a mismatch is the ordinary verification error. One reference in the series can never carry a pin: the meta-kernel's self-referencing `!!meta`, whose hash input would contain the pin itself. It needs none — it resolves by pre-loading (§3.4) — and under the per-identity rule its plain spelling composes without conflict with pinned references to the kernel elsewhere in a closure.

An implementation MAY offer a strict resolution mode that rejects any closure containing an identity whose digest set is empty (pre-loaded identities excepted), for deployments requiring a fully verified chain; the per-identity rule above is the default and the interchange baseline.

When no digest is declared for an identity anywhere in the closure, it resolves without integrity verification — appropriate for development, NOT RECOMMENDED for production interchange. References that cross a trust boundary SHOULD be hash-pinned: a schema's `!!meta` and `!!import` values pin its contract and dependencies; a data document's `!!schema` pins its vocabulary. Because pinning is per-identity, one pinned reference raises the guarantee for every reference to that identity in the closure, and a published plain reference never prevents downstream pinning. Pinning composes into the verification chain of [TSON-DATA] §2.2.1 — a consumer holding a single hashed reference can verify a document together with its schema, that schema's meta-schema, and the kernel, provided each rung's own references are pinned; published schemas SHOULD pin every reference they carry, the kernel's self-reference being the sole exception.

```
!!schema:"https://example.com/people.tn1?sha256=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
```


### 10.3 Canonical Identity

Reference identity is defined by [TSON-DATA] §2.2.1: two references name the same document if and only if their canonical identities — lowercase host plus path, scheme and query removed — are byte-for-byte identical. The library applies this as its key rule:

- `http://tson.io/2026/32/m/core.tn1` and `https://tson.io/2026/32/m/core.tn1` — **same** entry; the scheme is a transport hint.
- `https://tson.io/2026/32/m/core.tn1` and the same URL with `?sha256=…` — same entry; the pinned form additionally requires verification (§10.2).
- `https://tson.io/2026/32/m/core.tn1` and `https://elsewhere.example/2026/32/m/core.tn1` — **different** entries; the host is load-bearing, so a fetch-endpoint change cannot silently redirect a name.

The identity profile *forbids* the spelling variants a general web stack would normalize (non-lowercase host, userinfo, ports, percent-encoded unreserved characters, dot-segments, fragments) rather than normalizing them away — an identifier outside the profile is an error, and comparison never performs runtime normalization ([TSON-DATA] §2.2.1).

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


## 12. ABNF: The Schema Grammar

This section defines the schema grammar — the schema document's body grammar, the second of the two body grammars behind the shared document header ([TSON-DATA] §2.2, §7.4). The lexer is unchanged ([TSON-DATA] §7.3); the productions below consume the same token stream, and the reserved special tokens of [TSON-DATA] §7.2.5 receive their meaning here.


### 12.1 The Schema Grammar

The schema-document header is defined entirely by [TSON-DATA]'s grammar; this document defines the schema body: `schema-map`, the annotated, braced declaration map that [TSON-DATA]'s `schema-doc` production delegates here. `annotation`, `separator`, `field-name`, and `data-value` are imported from [TSON-DATA] §7.4; `data-value` appears at exactly three points — constructor-application values, atom-refinement values, and field-modifier values.

A `schema-map` copies the shape of [TSON-DATA]'s `map` production but requires at least one entry — `{}` at schema-body position is a parse error. An entry is called a **declaration**. Annotations before the opening brace bind to the schema; annotations at the head of an entry bind to the key; annotations after `=>` bind to the type definition (§2.1, §6).

```
; ── Schema Map (schema body) ──────────────────────────────

schema-map       = *( annotation ws ) "{" ws schema-map-entry
                   *( separator schema-map-entry ) ws "}"

schema-map-entry = *( annotation ws ) type-name ws "=>" ws
                   *( annotation ws ) type-def

; ── Type Definition (declaration right-hand side) ─────────

type-def = atom-refinement
         / instance
         / [type-params] ["~"] structural-def
         / [type-params] container-def
         / [type-params] type-ref

type-params = "<" ws param-name *( separator param-name ) ws ">"
param-name  = type-name   ; same lexical class as type-name

structural-def = refined-def
               / construction-def
               / record-def

refined-def  = type-name [ws "<" type-args ">"] ws "^" ws record-def
             ; record, map, and (with ~) constructor
             ; refinement (§5.7, §4.2). No removal clause.

construction-def = type-ref 1*(ws "&" ws type-ref)
                   [ws record-def] [ws removal-set]
                 / type-ref ws "&" ws record-def [ws removal-set]
                 / type-ref ws removal-set

removal-set  = "-" ws "{" ws field-name
               *( separator field-name ) ws "}"

record-def   = "{" ws [record-entry *(separator record-entry)] ws "}"
record-entry = field-def / group-def

atom-refinement = "!" type-name ws "^" ws data-value
                ; atom refinement (§5.5): the data-value MUST be
                ; a braced record of constraint bindings, and the
                ; target MUST resolve to an atom-family instance
                ; (§3.3.1)

instance     = "!" type-name ws data-value
             ; constructor application (§5.5): the target MUST
             ; resolve to a constructor (§3.3.1)

; ── Field Definitions ─────────────────────────────────────

field-def      = *annotation field-name ws ":" ws
                 ( field-type field-modifier
                 / field-type
                 / field-modifier )

field-type     = type-ref ["?"]

field-modifier = ws ("~" / "=") ws ( token / absent )

; ── Field Groups (§5.11) ──────────────────────────────────

group-def    = *annotation "(" ws group-member
               1*( ws "|" ws group-member ) ws ")" ["?"]
group-member = *annotation field-name ws ":" ws type-ref
               ; no "?", no modifier on members

; ── Type References (any type position) ───────────────────

type-ref = paren-type
         / inline-array
         / type-name "<" type-args ">"
         / type-name

; ── Compound Type Expressions ─────────────────────────────

paren-type = "(" type-ref "|" type-ref *("|" type-ref) ")"   ; choice, 2+ variants

inline-array = "[" type-ref ws "]"                            ; plain array
             / "[" type-ref 1*(separator type-ref) "]"        ; tuple, all positions REQUIRED

; Declaration-level container forms (top-level type-def body
; only, §5.3): size specifiers and element/position "?" are
; not valid inside a type-ref.

container-def = "[" element-type [ ws ";" ws size-spec ] ws "]"          ; array
              / "[" element-type 1*(separator element-type) "]"          ; tuple

element-type  = ( container-def / type-ref ) ["?"]
              ; container-def nests: an element position of a
              ; declaration-level bracket form takes the full
              ; bracket syntax (§5.3); a type-ref position never does
size-spec     = size-bound [ ws ".." ws [ size-bound ] ]
              / ".." ws size-bound
              ; a size-bound is decimal-natural or, within a
              ; template body, a value-parameter name (§5.3)

; The "?" in field-type marks FIELD optionality (§5.2) and
; remains valid at field positions; the "?" in element-type
; marks element or tuple-position optionality and is valid
; only inside a container-def.

; ── Terminals ─────────────────────────────────────────────

type-args  = type-arg *(separator type-arg)    ; separator = ws "," ws / ws1
type-arg   = type-ref / value-literal
value-literal = token
           ; a single scalar lexeme: a number, quoted string,
           ; boolean, or other non-name token. An unquoted
           ; token that satisfies type-name parses as a
           ; type-ref; whether it denotes a type or a value
           ; (an enum member) is settled against the applied
           ; signature's parameter kinds (§5.10, §5.3), not
           ; by the grammar.
size-bound = unquoted-token
           ; text MUST match the decimal-natural production
           ; of [TSON-DATA] §7.6
type-name  = unquoted-token
           ; one restriction beyond the lexeme class: a
           ; declaration name whose text matches the number
           ; production of [TSON-DATA] §7.6 is a parse error —
           ; numbers are not declarable names (param-name
           ; shares the rule). Resolver-materialised
           ; instantiation entries (§8.2) carry internal names
           ; in this same lexeme class; the resolver keeps them
           ; disjoint from declared names by construction
           ; (freshness, §8.2), and they are unreachable from
           ; source because they do not exist at source level.
```

Notes:

- The `type-params` slot declares type parameters (§5.10); parameters take precedence over schema-namespace lookup, and references to a parameterized type MUST supply matching type arguments.
- `paren-type` produces choice types; choices require at least two variants — `(T)` is a parse error.
- `group-def` produces field groups (§5.11); a group requires at least two members. Inside a record body, `(` at entry position (after any leading annotations) opens a group; `(` after a `field-name ":"` opens a `paren-type`. The two never collide — a group is an entry, a choice is a type-ref. The `?` after the closing `)` sets the group's state; member positions reject `?` and modifiers by grammar.
- The `?` suffix marks field-level, tuple-position-level, array-element-level, or group-level optionality and is valid only in those positions, recording `state: OPTIONAL` on the containing `record_field`, `tuple_element`, `array`, or `field_group`. Element- and position-level `?` is declaration-level syntax: it appears in `container-def` — a top-level type-def body, or a nested element position within one (§5.3) — and never inside a `type-ref`. There is no generic "optional type" in TSON.
- `inline-array` and `container-def` overlap on the plain-array and all-REQUIRED-tuple shapes; at top-level type-def position `container-def` is tried first, and the two parses are semantically identical there. Size specifiers and element/position `?` are exclusive to `container-def` (§5.3): an inline `[T; n]`, `[T?]`, or `[T, U?]` is a parse error whose diagnostic SHOULD suggest a named declaration or, in layers where the template names resolve (§5.3), the equivalent size-refinement application. `container-def` nests within itself — `[[T; N]; N]` is legal as a declaration body — so the restriction and its diagnostic apply to type-ref positions only.
- The trailing record-def in `construction-def` is optional (`customer => address & contact` is valid). When a `{` follows a `&`-chain, it always belongs to the construction's record-def.
- The refined-def target is restricted to a bare type-name, optionally with type-args; inline structural forms cannot precede `^` by grammar. A `^` whose resolved target has no refinable body (a choice, for example) is a resolver error reported with the target's kind.
- The removal clause attaches to construction heads only; a refinement head admits none — `T ^ { ... } - { ... }` is a parse error (§5.7, §5.9).
- After a bare type-ref in type-def position, `{` is a parse error; the diagnostic SHOULD suggest `^` (refinement) or `&` (composition).
- Parameters and type arguments inside `<>` alike separate by comma or whitespace — the general separator convention ([TSON-DATA] §7.4): `<T, MIN>` and `<T MIN>`, `map<text, integer>` and `map<text integer>` are all valid.
- `_` is not valid in type-ref or type-def body positions (§7.6); empty records use `{}`.


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
;   ! name ^       → atom refinement (§5.5)
;   ! name         → constructor application (§5.5)
;   ~              → constructor marker, then structural-def
;   name ^         → refined-def (§5.7)
;   name &         → construction-def (composition, §5.8)
;   name -         → construction-def (subtraction, §5.9)
;   name {         → parse error (write ^ or &)
;   {              → fresh record-def
;   (              → paren-type (choice)
;   [              → container-def (array or tuple, full syntax)
;   name ? / name  → type-ref
;
; type-ref position (field types, array elements, etc.):
;   (              → paren-type (choice)
;   [              → inline-array (plain array or tuple; no
;                    size spec, no element/position "?")
;   name <         → generic
;   name ? / name  → simple ref
;
; record-def entry position (after leading annotations):
;   (              → group-def (field group, §5.11)
;   name ":"       → field-def
;
; bracket-form internal disambiguation:
;   [type sep type  → tuple (whitespace or comma)
;   [type ; spec    → array with size constraint (container-def only)
;   [type ]         → unconstrained array
;   [type ? ...     → element/position "?" (container-def only)
;   [[ ...          → nested container-def (full syntax; container-def only)
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

Each case in the type-def block is decided by at most two tokens of lookahead at the start of the production; inside a bracket form, the choice between tuple, sized array, and unconstrained array is made by one-token lookahead after the complete preceding element type. A `field-type` can itself be nested (`(email | [phone])?`) and parses without backtracking via the disambiguation above; the outer `?` there is field optionality (§5.2). The schema body requires at most two tokens of lookahead to detect a declaration boundary. The parser never backtracks at any level.


### 12.3 Adjacency Rules

The following rows extend the adjacency table of [TSON-DATA] §7.5 for the operators of the type-definition grammar; as there, the rules are enforced by the parser via source-position comparison.

| Operator | Type | Context | Rule |
|---|---|---|---|
| `!` | prefix | type-def body (constructor application, atom refinement) | MUST be adjacent to the following unquoted-token (constructor or instance name) |
| `?` | suffix | field type, tuple position, array element (container-def), field group | MUST be adjacent to the preceding token (type name or closing bracket/paren) |
| `&` | binary | composition | whitespace on either side optional |
| `^` | binary | refinement (§5.5, §5.7) | whitespace on either side optional |
| `-` | prefix | removal clause (§5.9) | at least one whitespace character MUST separate the preceding token from `-`; whitespace optional before the following `{` |
| `~` | prefix/modifier | constructor marker, default value | whitespace optional |
| `=` | modifier | fixed value | whitespace optional |
| `\|` | separator | choice variant; field-group member | whitespace optional |
| `;` | separator | array size spec (container-def, §5.3) | whitespace optional |
| `..` | binary | size-spec range (§5.3) | whitespace on either side optional |
| `=>` | separator | schema declaration; data map entry | whitespace optional (compound token from lexer) |

The whitespace requirement before removal `-` is a lexer fact restated as a rule: hyphen-minus continues an unquoted token ([TSON-DATA] §7.2.4 — hyphenated names are legal), so in `account- { password }` the hyphen is absorbed into `account-` and no removal clause exists — the same footgun class as `[1-2]` lexing as one token. When a token ending in `-` appears at a type-def position followed by `{`, the diagnostic SHOULD note the absorbed hyphen and suggest whitespace before `-`.


## 13. References

### 13.1 Normative References

| Reference | Title | URL |
|-----------|-------|-----|
| RFC 2119 | Key words for use in RFCs to Indicate Requirement Levels | https://www.rfc-editor.org/rfc/rfc2119 |
| RFC 5234 | Augmented BNF for Syntax Specifications (ABNF) | https://www.rfc-editor.org/rfc/rfc5234 |
| RFC 3339 | Date and Time on the Internet: Timestamps | https://www.rfc-editor.org/rfc/rfc3339 |
| RFC 3986 | Uniform Resource Identifier (URI): Generic Syntax | https://www.rfc-editor.org/rfc/rfc3986 |
| RFC 4291 | IP Version 6 Addressing Architecture | https://www.rfc-editor.org/rfc/rfc4291 |
| RFC 4632 | Classless Inter-domain Routing (CIDR) | https://www.rfc-editor.org/rfc/rfc4632 |
| RFC 4648 | The Base16, Base32, and Base64 Data Encodings | https://www.rfc-editor.org/rfc/rfc4648 |
| RFC 5322 | Internet Message Format (email address syntax) | https://www.rfc-editor.org/rfc/rfc5322 |
| RFC 9485 | I-Regexp: An Interoperable Regular Expression Format | https://www.rfc-editor.org/rfc/rfc9485 |
| RFC 9542 | IANA Considerations and IETF Protocol and Documentation Usage for IEEE 802 Parameters (EUI-48) | https://www.rfc-editor.org/rfc/rfc9542 |
| RFC 9562 | Universally Unique IDentifiers (UUIDs) | https://www.rfc-editor.org/rfc/rfc9562 |
| ISO 8601-1:2019 | Date and time — Representations for information interchange | https://www.iso.org/standard/70907.html |
| IEEE 754-2019 | Standard for Floating-Point Arithmetic | https://ieeexplore.ieee.org/document/8766229 |

### 13.2 Series References

| Reference | Title | URL |
|-----------|-------|-----|
| TSON-DATA | TSON Part 1: Text Data Format | https://tson.io/2026/32/tson-part1-data |
| TSON-GUIDE | TSON Developer Guide (non-normative) | https://tson.io/2026/32/tson-guide |
| meta-kernel.tn1 | TSON Meta-Kernel (companion artifact) | https://tson.io/2026/32/m/meta-kernel.tn1 (hash pin to be published) |
| meta.tn1 | TSON Meta-Schema (companion artifact) | https://tson.io/2026/32/m/meta.tn1 (hash pin to be published) |
| core.tn1 | TSON Core Type Library (companion artifact) | https://tson.io/2026/32/m/core.tn1 (hash pin to be published) |

### 13.3 Informative References

| Reference | Title | URL |
|-----------|-------|-----|
| RFC 8820 | URI Design and Ownership | https://www.rfc-editor.org/rfc/rfc8820 |
| ISO/IEC 11404:2007 | General Purpose Datatypes | https://www.iso.org/standard/39479.html |
| JSON Schema 2020-12 | JSON Schema: A Media Type for Describing JSON Documents | https://json-schema.org/specification |
| RFC 5646 | Tags for Identifying Languages (BCP 47) | https://www.rfc-editor.org/rfc/rfc5646 |
| W3C XSD Part 2 | XML Schema Part 2: Datatypes Second Edition | https://www.w3.org/TR/xmlschema-2/ |
| Resolver Output Fixtures | meta-kernel-resolved.tn1, meta-resolved.tn1, core-resolved.tn1 (non-normative) | &lt;published alongside this document&gt; |