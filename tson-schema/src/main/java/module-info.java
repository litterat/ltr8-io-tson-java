module io.ltr8.tson.schema {
    exports io.ltr8.tson.schema;
    exports io.ltr8.tson.schema.meta;
    exports io.ltr8.tson.schema.unicode;
    exports io.ltr8.tson.schema.numeric;
    exports io.ltr8.tson.schema.net;
    exports io.ltr8.tson.schema.binary;
    exports io.ltr8.tson.schema.temporal;

    requires io.ltr8.annotation;
    requires io.ltr8.regex;
    requires transitive io.ltr8.tson.base;
}
