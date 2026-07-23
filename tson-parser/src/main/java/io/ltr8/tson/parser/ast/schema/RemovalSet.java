package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code removal-set = "-" ws "{" ws field-name *(separator field-name) ws "}"} (Part 2 §12.1,
 * §5.9) -- the trailing removal clause on a {@link ConstructionDef}, naming fields (or field-group
 * members) to drop and deliberately break IS-A. At least one name is required by grammar --
 * "empty subtraction does not exist" (§5.9 rule 6).
 */
public record RemovalSet(List<String> fieldNames) {

    public RemovalSet {
        fieldNames = List.copyOf(fieldNames);
        if (fieldNames.isEmpty()) {
            throw new IllegalArgumentException("a removal set requires at least one field name");
        }
    }
}
