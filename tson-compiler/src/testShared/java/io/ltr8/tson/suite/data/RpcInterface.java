package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Map;

/** The host class for the corpus's {@code rpc_interface} constructor: an interface whose methods are rpc methods. */
@Typename(name = "rpc_interface")
public record RpcInterface(Map<String, RpcMethod> methods) implements Data {

}
