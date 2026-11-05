package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a record and a record template state at the record level ([TSON-SCHEMA] §5.2, §5.10, §5.11): the name type
 * its field names are values of, those names in declaration order, the fields its members are selected by, the field
 * groups, and how it may be realised. Each body produces it ({@link RecordBody#head}, {@link RecordTemplateBody#head}),
 * and a phase that reads only these facts reads them here, with one question for both shapes ({@link #of}).
 *
 * <p><b>A value, not a parent.</b> The two bodies share no Java type, as {@code record} and {@code record_template}
 * share no kernel parent beyond {@code product}: a record is what readers and writers are built for and never names
 * a template, so the facts they have in common travel as a value each produces rather than as an interface each
 * implements. What differs -- the fields' own facts, the parameters, what {@link #extension} means of the template
 * itself -- stays on each body.
 *
 * <p>{@link #extension} is the body's own statement: a record's, or a template's for each of its applications, the
 * template itself being ABSTRACT whatever it says.
 */
public record RecordHead(Optional<String> nameType, Set<String> fieldNames, List<String> discriminators,
                         List<FieldGroup> groups, RecordExtensionType extension) {

    public RecordHead {
        Objects.requireNonNull(nameType, "nameType");
        Objects.requireNonNull(fieldNames, "fieldNames");
        Objects.requireNonNull(discriminators, "discriminators");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(extension, "extension");
    }

    /** The head of a record or a record template, and empty for any other value. */
    public static Optional<RecordHead> of(Top value) {
        return switch (value) {
            case RecordBody record -> Optional.of(record.head());
            case RecordTemplateBody template -> Optional.of(template.head());
            case null, default -> Optional.empty();
        };
    }

    /** The name type's name, for a message: the one stated, or {@link RecordBody#FIELD_NAME}. */
    public String nameTypeName() {
        return nameType.orElse(RecordBody.FIELD_NAME);
    }
}
