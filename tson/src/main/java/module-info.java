module io.ltr8.tson {
    exports io.ltr8.tson;

    requires transitive io.ltr8.bind;
    requires transitive io.ltr8.tson.compiler;
    requires transitive io.ltr8.tson.schema;
    requires transitive io.ltr8.tson.tree;

    // transitive: TsonHttpSchemaSource.Builder.httpClient(HttpClient) is public API, so a consumer naming it
    // needs this module too. A JDK module, so it is no external dependency -- the one rule this module has.
    requires transitive java.net.http;
}
