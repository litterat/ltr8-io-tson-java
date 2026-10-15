package io.ltr8.tson.schema.meta;

import java.util.List;

/** The one normalisation every body's chain components take: absent and empty are one list. */
final class Chains {

    private Chains() {
    }

    /** {@code list} copied, or empty where a bound document omitted the field. */
    static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
