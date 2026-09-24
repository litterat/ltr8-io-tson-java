# tson-atom — the built-in atom vocabulary

Which tokens each family accepts and what host value results, over a `String`, shared by both encodings. Read
`design/base-types-and-atom-vocabulary.md`; for refinement and the two per-family checks,
`design/atom-refinement-and-coherence.md`.

- `AtomType` takes text only. Anything depending on *how* a token was written (`value`, `Token`) stays in the encoding.
- Each family is a constraint record in `schema.meta` plus a same-named `*Parser` in the unexported `atom.parser`.
- Pattern facets are `String`, matched through `tson-regex` (I-Regexp) — never `java.util.regex`.
- `AtomParsers` is the one answer to "which parser reads this body"; the compiled readers and the linker both ask it.
- `expected` on a refusal is the constraint that failed, from `AtomTypeException`'s six shapes — never the type's name.
