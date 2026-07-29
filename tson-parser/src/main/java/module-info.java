module io.ltr8.tson.parser {
    exports io.ltr8.tson.parser;
    exports io.ltr8.tson.parser.ast;
    exports io.ltr8.tson.parser.ast.schema;
    exports io.ltr8.tson.parser.compiler;
    exports io.ltr8.tson.parser.config;
    exports io.ltr8.tson.parser.mapper;
    exports io.ltr8.tson.parser.resolver;

    requires io.ltr8.annotation;
    requires transitive io.ltr8.bind;
    requires transitive io.ltr8.tson.schema;
}
