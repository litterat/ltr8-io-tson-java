dependencies {
    implementation(project(":tson-schema"))
    implementation(project(":tson-annotation"))
    // The immutable TsonNode tree model, a pure leaf module. tson-compiler holds the engine that produces
    // and consumes it (TsonTreeReader/TsonTreeWriter, the reader.*TreeReader family) -- the same direction
    // as the tson-schema dependency, model in its own module, engine here. Public tson-compiler signatures
    // return TsonNode, so it is `requires transitive` in module-info and re-declared `api` in the tson module.
    implementation(project(":tson-tree"))
    // tson-bind has no dependency on tson-compiler/tson-schema (a leaf module), so depending on it
    // here directly is clean -- needed in main scope now that TsonMapperReader/TsonMapperWriter
    // (originally in the separate tson-mapper module, which depended on tson-compiler and so could
    // never be depended on back) live in this module instead, so schema resolution can bind a
    // DataValue onto a schema.meta class without a module cycle.
    implementation(project(":tson-bind"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
