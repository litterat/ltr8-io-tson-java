package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The host class for the corpus's {@code interface} constructor ({@code schemas/fixtures/meta-service.tn}): a
 * named set of methods, each a value of the meta layer's {@code method} record.
 *
 * <p>It declares its methods' {@code response}s, type references at {@code type_ref} slots. Each {@code request},
 * {@code errors} element and {@code replies} value is a reference by name, which the resolver's read records with
 * its slot ({@code /methods/<key>/...}) without the class declaring it.
 */
@Typename(name = "interface")
public record Interface(Map<String, Method> methods) implements Data {

    @Override
    public List<TypeRef> references() {
        return methods.values().stream().map(Method::response).filter(Objects::nonNull).toList();
    }

}
