package io.ltr8.tson.schema.meta;

import io.ltr8.tson.base.unicode.Normalization;

/**
 * A family whose constructor composes {@code text_type}: its value is a token's text put into the
 * {@link #normalization} form (SPEC-FEEDBACK.md #19). An enum over such a family matches its members in that form,
 * which is why the form is reachable without knowing which family a body is.
 */
public interface TextFamily {

    /** The form a value of this family is put into. */
    Normalization normalization();
}
