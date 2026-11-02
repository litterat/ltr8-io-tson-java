/**
 * The facet-comparison mechanics every atom family of the schema model shares, internal to {@code tson-schema} and
 * not exported: {@code AtomNarrowing}, behind {@code Atom.constraintsCheck} (does a refinement tighten its source?),
 * and {@code AtomCoherence}, behind {@code Atom.coherenceCheck} and {@code Product.coherenceCheck} (does one body's
 * own facets admit any value?). A family says which of its fields is which kind of facet; how a facet is compared is
 * said here, once, for every namespace's families.
 *
 * <p>No schema entry is bound to a class here: the kernel's {@code atom} namespace declares annotations and the
 * {@code atom.specification} mixin, whose {@code spec} field a family composing it carries as its own.
 */
package io.ltr8.tson.schema.atom;
