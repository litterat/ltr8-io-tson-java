plugins {
    id("java-library")
}

dependencies {
    // A JSON stack of its own -- lexer, event stream, tree, readers -- rather than a second front end over
    // `tson-compiler`'s `TsonEventSource`. The lexical, structural and tree layers depend on nothing: RFC 8259
    // is an external standard and none of them is TSON-specific.
    //
    // `tson-bind` is the one dependency, and it is the shape `tson-compiler` already takes on it: a
    // dependency-free binding engine that reads a class's own descriptor, so a JSON document binds to a Java
    // object with no TSON schema in sight. `api` rather than `implementation` because a caller building a
    // `DataBindContext` to hand `JsonObjectReader` names its types directly.
    //
    // The schema-directed decode of [TSON-JSON] §5-§8 is what will bring a dependency on `tson-compiler`.
    api(project(":tson-base"))
    api(project(":tson-bind"))

    // `io.ltr8.bind` requires it transitively, so the module path needs it here even though nothing in
    // this module names one of its types directly -- `tson-bind` declares it `implementation`, which does
    // not propagate. `tson-compiler` carries the same line for the same reason.
    implementation(project(":tson-annotation"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
