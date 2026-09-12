package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;

/**
 * What a record's rules say when a document breaks one -- stated once, for every encoding.
 *
 * <p><b>Why one place.</b> [TSON-JSON] §9.4 gives both encodings one diagnostic vocabulary and adds no
 * category of its own, so a document that is wrong in one encoding is wrong in the other, for the same stated
 * reason. The {@code code} and the machine-readable {@code expected} are what a consumer routes on, and
 * nothing was holding two independently-written readers to the same values for them -- they agreed only
 * because one was copied from the other.
 *
 * <p><b>The prose is the schema's vernacular, not the format's.</b> A record has <em>fields</em>, in TSON
 * text and in JSON alike, even though JSON's own word for what carries one is a member; a map has keys and
 * values; a value the schema forbids is <em>absent</em> rather than {@code _} or {@code null}. The reason is
 * consistency about the thing being described: it is the <b>schema</b> that refused the document, so the
 * schema's nouns are the ones that explain it, and a reader who moves between encodings learns one
 * vocabulary. The encoding's own spelling is not lost -- it rides in {@link Refusal#actual}, which echoes
 * what the document literally held and is data rather than prose.
 *
 * <p><b>What is not here</b> is any rule one encoding has and the other does not: JSON's reserved member
 * namespace ([TSON-JSON] §3.2) and TSON's positional record form have no counterpart across the wire, so
 * each stays with the reader that owns it. A shared class that grew those would be a second switch
 * responsible for rules it cannot name.
 *
 * @param typeName       the record type being read, as the author wrote it
 * @param declaredFields its field names joined for a closed-list diagnostic, {@code "a | b | c"}
 */
public record RecordDiagnostics(String typeName, String declaredFields) {

    /** The value is not a record at all -- {@code found} is the encoding's description of what was there. */
    public Refusal notARecord(String found) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "expected a record for '%s', found %s".formatted(typeName, found), "a record", found);
    }

    /** [TSON-SCHEMA] §7.2: a record is closed under its type, so a name it does not declare is an error. */
    public Refusal unrecognizedField(String field) {
        return new Refusal(Diagnostic.Code.UNRECOGNIZED_FIELD,
                "unknown field '%s' on '%s' -- a record is closed under its type (§7.2), whose fields are (%s)"
                        .formatted(field, typeName, declaredFields), declaredFields, field);
    }

    /** [TSON-DATA] §2.5: a record states each field at most once, and the repeat states a value for nothing. */
    public Refusal duplicateField(String field) {
        return new Refusal(Diagnostic.Code.DUPLICATE_FIELD,
                "duplicate field '%s' on '%s' -- a record states each field at most once (§2.5), and the repeat "
                        .formatted(field, typeName) + "states a value for nothing",
                "each field stated once", "'" + field + "' stated again");
    }

    /** A REQUIRED field the document never mentioned. */
    public Refusal missingRequiredField(String field) {
        return new Refusal(Diagnostic.Code.FIELD_REQUIRED,
                "missing required field '%s' for '%s'".formatted(field, typeName),
                "a value for '" + field + "'", "(absent)");
    }

    /**
     * Absence written at a field whose state admits none ([TSON-SCHEMA] §7.6).
     *
     * <p>{@code spelling} is how the document said it -- {@code _} in TSON text, {@code null} in JSON -- and
     * appears in {@code actual} rather than in the prose, because the rule is about the field's state and not
     * about how absence was spelled.
     */
    public Refusal absenceAtRequiredField(String field, String spelling) {
        return new Refusal(Diagnostic.Code.FIELD_REQUIRED,
                "'%s' on '%s' admits no absence".formatted(field, typeName),
                "a value for '" + field + "'", spelling);
    }

    /**
     * Absence written at a REQUIRED_DEFAULT field. [TSON-SCHEMA] §5.2 makes omission the injection route, so
     * the fix is to omit the field rather than to disclaim its value -- and the default is still what the
     * field decodes to, only the verdict changes.
     */
    public Refusal absenceAtDefaultedField(String field, String spelling) {
        return new Refusal(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                "'%s' on '%s' is always filled from the schema and cannot be written as absent -- omit the field "
                        .formatted(field, typeName) + "to take its default (§5.2)",
                "the field omitted, or a value for '" + field + "'", spelling);
    }

    /** Absence written at a REQUIRED_FIXED field, whose value the schema settles and which is never absent. */
    public Refusal fixedFieldAbsent(String field, String pinned, String spelling) {
        return new Refusal(Diagnostic.Code.FIELD_FIXED,
                "'%s' is fixed on '%s' and cannot be absent".formatted(field, typeName), pinned, spelling);
    }

    /**
     * A value written at an {@code OPTIONAL_FIXED = _} field -- the group-member state, where the schema fixes
     * the field to absence and presence alone is the information ([TSON-SCHEMA] §5.2).
     */
    public Refusal fixedToAbsentFieldValued(String field, String spelling, String found) {
        return new Refusal(Diagnostic.Code.FIELD_FIXED,
                "'%s' is fixed to absent on '%s' and may only be omitted or written as absent"
                        .formatted(field, typeName), spelling, found);
    }

    /**
     * A value at a FIXED field contradicting the pin ([TSON-SCHEMA] §5.2: verified, "never a value the decoder
     * silently overwrites"). The message names the remedy, because the two modifiers differ by exactly this.
     */
    public Refusal fixedFieldContradicted(String field, String pinned, String written) {
        return new Refusal(Diagnostic.Code.FIELD_FIXED,
                "'%s' is fixed on '%s' and cannot be given another value -- the schema declares it with '=' "
                        .formatted(field, typeName) + "(fixed); for a default the data may override, use '~'",
                pinned, written);
    }

    /** [TSON-SCHEMA] §5.11: an OPTIONAL group admits at most one member, a REQUIRED group exactly one. */
    public Refusal groupAdmitsAtMostOne(String members, int present) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "at most one of (%s) may be present for '%s', found %d".formatted(members, typeName, present),
                "at most one of (" + members + ")", present + " present");
    }

    /** A REQUIRED group with no member present -- §5.11 counts presence after ordinary field validation. */
    public Refusal groupRequiresOne(String members) {
        return new Refusal(Diagnostic.Code.FIELD_REQUIRED,
                "exactly one of (%s) must be present for '%s'".formatted(members, typeName),
                "one of (" + members + ")", "none present");
    }
}
