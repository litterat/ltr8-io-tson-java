dependencies {
    implementation(project(":tson-schema"))
    implementation(project(":tson-annotation"))
    // tson-bind has no dependency on tson-parser/tson-schema (a leaf module), so depending on it
    // here directly is clean -- needed in main scope now that TsonMapperReader/TsonMapperWriter
    // (originally in the separate tson-mapper module, which depended on tson-parser and so could
    // never be depended on back) live in this module instead, so schema resolution can bind a
    // DataValue onto a schema.meta class without a module cycle.
    implementation(project(":tson-bind"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
