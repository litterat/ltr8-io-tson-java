package io.ltr8.tson.compiler;

import java.util.Optional;

import io.ltr8.tson.tree.TsonDocument;

/**
 * A whole data document read into a bound object: the header directives ([TSON-DATA] §2.2), the type the
 * value was read as, and the object itself. What {@link TsonObjectReader#readDocument} returns where
 * {@link TsonObjectReader#read} returns the object alone.
 *
 * <p><b>What this carries is what the <em>read</em> established</b>, which is the whole of why it exists. A
 * bound object's class plus its {@code DataBindContext} already fix which schema governs it -- one context
 * per schema version is the design -- so the schema is the weakest of the three. The other two are not
 * recoverable from anything the caller holds: {@code id} is per-document data (§2.2 makes it a property of
 * the document, so a class modelling it as a field would be lying about its shape), and {@code rootType} is
 * the type this document was resolved to, which a {@code DataNameBinder} cannot hand back -- it maps name to
 * class, and a binding profile lets one class serve several shapes, so class to name does not invert.
 *
 * <p><b>Not the same shape as {@link TsonDocument}, which is why it is not the same type.</b> The tree's
 * document needs no {@code rootType}: a {@code TsonValue} carries its own {@code typeRef()}. A bound object
 * carries nothing, so the type has to live here. Naming the two as siblings would claim a kinship their
 * arities deny.
 *
 * <p><b>{@code rootType} is populated by a schema-driven read and by nothing else.</b> A schemaless read
 * resolves no type, so it leaves this empty rather than guessing from a wire type-ref it did not check.
 *
 * @param id       the document's own identity ({@code !!id}), or empty
 * @param schema   the schema governing its value ({@code !!schema}), or empty
 * @param rootType the schema type the value was read as, or empty where no schema applied
 * @param value    the bound object
 * @param <T>      the bound type
 */
public record TsonObjectDocument<T>(Optional<String> id, Optional<String> schema, Optional<String> rootType,
                                     T value) {

    public TsonObjectDocument {
        id = id == null ? Optional.empty() : id;
        schema = schema == null ? Optional.empty() : schema;
        rootType = rootType == null ? Optional.empty() : rootType;
    }

    /** A document with no header directives and no schema type -- a bare value, which is every Class 1 document. */
    public static <T> TsonObjectDocument<T> of(T value) {
        return new TsonObjectDocument<>(Optional.empty(), Optional.empty(), Optional.empty(), value);
    }

    /**
     * The same document around a different value, its header and root type carried across.
     *
     * <p>Generic in the new value so a caller may map the payload -- a projection, a wrapper, a different
     * binding of the same document -- without restating what governed it.
     */
    public <U> TsonObjectDocument<U> withValue(U newValue) {
        return new TsonObjectDocument<>(id, schema, rootType, newValue);
    }
}
