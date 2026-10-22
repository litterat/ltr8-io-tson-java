package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Map;

/**
 * The host class for the corpus's {@code interface} constructor ({@code schemas/fixtures/meta-service.tn}): a
 * named set of methods, each a value of the meta layer's {@code method} record.
 *
 * <p>It declares nothing of its references: each method's {@code request}, {@code response}, {@code errors} element
 * and {@code replies} value is a type's name at a kind-typed slot, which the resolver's read records with its slot
 * ({@code /methods/<key>/...}) and the linker judges a member.
 */
@Typename(name = "interface")
public record Interface(Map<String, Method> methods) implements Data {

}
