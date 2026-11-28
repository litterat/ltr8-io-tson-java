package io.ltr8.tson.encoding;

import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.tson.schema.EntryKey;

import java.util.List;
import java.util.Objects;

/**
 * Which host class an entry binds to under one bind context: a class, nothing, or a class the context cannot
 * describe. Each case is told apart by its type, never by catching.
 */
public sealed interface Descriptor {

    /**
     * The entry binds to {@code dataClass}, under the candidate {@code matched}: the first of its binding candidates
     * the binder binds.
     */
    record Bound(DataClass dataClass, EntryKey matched) implements Descriptor {

        public Bound {
            Objects.requireNonNull(dataClass, "dataClass");
            Objects.requireNonNull(matched, "matched");
        }
    }

    /**
     * The binder binds no class under any of {@code tried}, the entry's binding candidates in the order asked;
     * {@code refusal} is its answer for the first of them, the name the author wrote, which says what the binder
     * does hold.
     */
    record Unbound(List<EntryKey> tried, DataBindException refusal) implements Descriptor {

        public Unbound {
            tried = List.copyOf(tried);
            Objects.requireNonNull(refusal, "refusal");
        }
    }

    /**
     * The binder binds a class under {@code matched}, and the context cannot describe it; {@code cause} names the
     * class and why. Asking stops there: a later candidate never stands in for a class the binder did choose.
     */
    record Unanalysable(EntryKey matched, DataBindException cause) implements Descriptor {

        public Unanalysable {
            Objects.requireNonNull(matched, "matched");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
