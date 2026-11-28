package io.ltr8.tson.encoding.plan;

import io.ltr8.tson.encoding.TsonTypeBinding;
import io.ltr8.tson.schema.EntryKey;

import java.util.Optional;

/**
 * Which host class each entry of a linked schema binds to, as a compile's factories ask it: a strict binding
 * ({@code TsonSchemaBinding}) or a checkpoint's ({@link BindingTable#checkpoint}).
 */
public interface EntryBindings {

    /** The binding of entry {@code key}; empty where nothing binds it. */
    Optional<TsonTypeBinding> get(EntryKey key);

    /**
     * The binding of the entry {@code writtenName} denotes past its alias hops and its extern; empty where nothing
     * binds it or the name reaches no entry.
     */
    Optional<TsonTypeBinding> get(String writtenName);

    /**
     * The binding of entry {@code key}.
     *
     * @throws io.ltr8.tson.base.MissingBindingException where nothing binds it, saying why
     * @throws IllegalArgumentException                  where {@code key} is no entry this binding was loaded for
     */
    TsonTypeBinding require(EntryKey key);
}
