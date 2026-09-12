package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;

/**
 * What a subtype family's rules say when a document breaks one -- stated once, for every encoding.
 *
 * <p>A record states how it may be realised ([TSON-SCHEMA] §5.2), and two of the four members change how a
 * position typed by it is read. At an <b>ABSTRACT</b> position the record has no direct instances, so the tag
 * is the only selector and is required. At a <b>SEALED</b> one the subtype is recovered from the value's own
 * discriminator members, the tag is optional, and where it is written it may only agree. Both readings are
 * [TSON-SCHEMA]'s rather than any encoding's -- what differs across encodings is the spelling of the tag
 * ({@code $type} in JSON, {@code !dog} in TSON text) and nothing else -- so the refusals belong here beside
 * {@link RecordDiagnostics} rather than being written once per stack.
 *
 * <p><b>The tag's spelling never reaches the message, and that is what makes these shareable at all.</b> It is
 * the one thing about a tag that genuinely differs across encodings -- {@code $type} against {@code !dog} --
 * and [TSON-JSON] §9.4 holds both stacks to one {@code message} and one {@code expected} for a rule. So each
 * rule below takes the spelling and spends it only in {@code actual}, which is data and echoes what the
 * document held ({@link RecordDiagnostics}'s own note). A message naming {@code $type} would be a message the
 * text encoding could not repeat.
 *
 * @param typeName the family's base, as the author wrote it
 * @param members  what a tag may name here, joined for a closed-list diagnostic -- the base and its subtypes
 */
public record FamilyDiagnostics(String typeName, String members) {

    /**
     * An ABSTRACT position with no tag. The failure lands before the members are read: the record has no
     * direct instances, so there is no value for an untagged object to be, and nothing about its shape is
     * consulted.
     */
    public Refusal tagRequired(String tagSpelling) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "'%s' is abstract and has no direct instances, so a value here must name its type -- one of (%s)"
                        .formatted(typeName, members),
                members, "(no " + tagSpelling + ")");
    }

    /**
     * A tag naming something the family does not admit. At an ABSTRACT position the base is among those
     * things: it has no direct instances, so naming it is an error where a concrete position would take the
     * same tag as a redundant restatement of its own type.
     */
    public Refusal notASubtype(String tagSpelling, String named) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "'%s' is not admissible at a '%s' position -- '%s' is abstract and has no direct instances, so "
                        .formatted(named, typeName, typeName) + "a value here is a value of one of (%s)"
                        .formatted(members),
                members, tagSpelling + " " + named);
    }

    /**
     * A tag naming the family's own base. §8.1 admits a redundant tag restating a position's own type, but
     * that rule assumes a type with direct instances: an abstract base has none, so a tag naming it asserts
     * something no value satisfies and selects nothing. Its own rule because the remedy differs -- the tag is
     * not <em>wrong</em> about the position, it is just not an answer.
     */
    public Refusal tagNamesTheBase(String tagSpelling) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "a tag naming '%s' selects nothing -- it is abstract and has no direct instances. Name the "
                        .formatted(typeName) + "member this value is, one of (%s), or omit the tag"
                        .formatted(members),
                members, tagSpelling + " " + typeName);
    }

    /**
     * A SEALED position whose value leaves out a discriminator. <b>Never an occasion to fall back to the
     * tag</b>: the selector is a required field of the base, so a value without it is invalid on §5.2's
     * ordinary terms, and reading the tag instead would make a family dispatch two ways.
     */
    public Refusal discriminatorMissing(String field) {
        return new Refusal(Diagnostic.Code.FIELD_REQUIRED,
                "missing discriminator '%s' for '%s' -- a sealed family selects its member by reading it, so a "
                        .formatted(field, typeName) + "value that leaves it out selects nothing",
                "a value for '" + field + "'", "(absent)");
    }

    /**
     * A SEALED position whose discriminator holds a value no member pins. Names what arrived and the pinned
     * alternatives, which is the whole of what an author needs: the mapping is derived from the pins and is
     * never written down anywhere they could go and read it.
     */
    public Refusal unmatchedDiscriminator(String selector, String found, String pinned) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "no member of '%s' pins %s to %s -- a sealed family is selected by that value, and this one "
                        .formatted(typeName, selector, found) + "matches none of (%s)".formatted(pinned),
                pinned, found);
    }

    /**
     * A SEALED position carrying both a tag and a discriminator that disagree. <b>Not a precedence
     * question</b>: there is one selector per position, the members are it, and a tag may only assert what
     * they already decided.
     */
    public Refusal tagContradictsDiscriminator(String tagSpelling, String named, String selected) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "the discriminator of '%s' selects '%s', but this value is tagged '%s' -- at a sealed position "
                        .formatted(typeName, selected, named)
                        + "the members are the selector and a tag may only agree with them",
                selected, tagSpelling + " " + named);
    }
}
