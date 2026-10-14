package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The host class for the corpus's {@code rpc_interface} constructor: an interface whose methods are rpc methods. */
@Typename(name = "rpc_interface")
public record RpcInterface(Map<String, RpcMethod> methods) implements Data {

    @Override
    public List<TypeRef> references() {
        return methods.values().stream().map(RpcMethod::response).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Named> names() {
        List<Named> names = new ArrayList<>();
        methods.forEach((key, method) -> names.addAll(method.names("methods", key)));
        return names;
    }
}
