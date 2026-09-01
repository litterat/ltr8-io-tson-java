package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * meta.tn's {@code unknown_type} constructor ({@code unknown_type => ~sum & {}}) -- an empty
 * SUM-kind marker whose instance, {@code unknown} (core.tn), accepts any well-formed value of any
 * type: "the universe of types," distinct from both the absent sentinel and the unit type.
 *
 * <p><b>Implements {@link Sum}, not {@link Atom}</b> -- the first {@code schema.meta} constructor
 * added that composes with {@code sum} rather than {@code atom} (every other constraint-vocabulary
 * class so far does). This is what widened {@code DefinitionResolver}'s own {@code Instance} resolution
 * from binding against {@code Atom.class} specifically to {@code Top.class} -- previously a
 * deliberate scope limit ("every real Instance in core.tn/meta.tn targets an atom-family
 * constructor," flagged as "a one-word change if it's ever needed") -- see {@code DefinitionResolver}'s
 * own Javadoc for the widened version.
 *
 * <p>Pure marker, no parsing/validation behavior -- deliberately no {@code tson-compiler} compiler
 * exists for this atom (added as a {@code schema.meta}/{@link Sum} variant only, per explicit user
 * direction, so {@code !unknown_type {}}/{@code unknown}'s own resolution succeeds).
 */
@Typename(name = "unknown_type")
public record UnknownType() implements Sum {
}
