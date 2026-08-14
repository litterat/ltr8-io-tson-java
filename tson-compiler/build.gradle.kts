dependencies {
    implementation(project(":tson-schema"))
    implementation(project(":tson-annotation"))
    // The immutable TsonValue tree model, a pure leaf module. tson-compiler holds the engine that produces
    // and consumes it (TsonTreeReader/TsonTreeWriter, the reader.*TreeReader family) -- the same direction
    // as the tson-schema dependency, model in its own module, engine here. Public tson-compiler signatures
    // return TsonValue, so it is `requires transitive` in module-info and re-declared `api` in the tson module.
    implementation(project(":tson-tree"))
    // The native RFC 9485 I-Regexp engine. The atom vocabulary (RegexParser, and TextParser/UriParser via
    // their `pattern` constraint) validates regex values against it rather than java.util.regex, so this
    // implementation defines I-Regexp semantics rather than inheriting the JVM's. Used internally only (no
    // public tson-compiler signature returns a tson-regex type), so `implementation` + a non-transitive
    // `requires` in module-info.
    implementation(project(":tson-regex"))
    // tson-bind has no dependency on tson-compiler/tson-schema (a leaf module), so depending on it
    // here directly is clean -- needed in main scope because the class-driven read/write front doors
    // (TsonObjectReader/TsonObjectWriter, and the SchemalessObjectReader engine under them) live in
    // this module, so schema resolution can bind a DataValue onto a schema.meta class without a
    // module cycle. DefinitionResolver's atom-refinement merge is the caller that forces it.
    implementation(project(":tson-bind"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
