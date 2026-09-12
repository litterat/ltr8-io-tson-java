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
 * <p><b>The tag's spelling is the caller's, and it is data.</b> Each rule below takes the tag as the reader's
 * own encoding writes it, so the prose stays the schema's vernacular while {@code actual} echoes what the
 * document literally held ({@link RecordDiagnostics}'s own note).
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
                "'%s' is abstract and has no direct instances, so a value here must name its type with %s -- "
                        .formatted(typeName, tagSpelling) + "one of (%s)".formatted(members),
                members, "(no " + tagSpelling + ")");
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
                "%s names '%s' but '%s's discriminator selects '%s' -- at a sealed position the members are the "
                        .formatted(tagSpelling, named, typeName, selected)
                        + "selector and a tag may only agree with them",
                selected, named);
    }
}
