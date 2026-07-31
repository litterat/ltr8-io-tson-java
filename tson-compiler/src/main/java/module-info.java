module io.ltr8.tson.compiler {
    exports io.ltr8.tson.compiler;
    exports io.ltr8.tson.compiler.ast;
    exports io.ltr8.tson.compiler.ast.schema;
    exports io.ltr8.tson.compiler.config;
    exports io.ltr8.tson.compiler.stream;

    requires io.ltr8.annotation;
    requires transitive io.ltr8.bind;
    requires transitive io.ltr8.tson.schema;
}
