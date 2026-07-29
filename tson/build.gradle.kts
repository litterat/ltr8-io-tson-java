plugins {
    id("java-library")
}

dependencies {
    // api, not implementation -- this module's own public surface (Tson/TsonConfig in particular)
    // hands back tson-compiler/tson-schema types directly (TsonCompiledMetaSchema, TsonLinkedSchema,
    // TsonMapperReader/TsonMapperWriter, ...), so a caller depending on just this module still needs
    // them on its own compile classpath.
    api(project(":tson-compiler"))
    api(project(":tson-schema"))
    api(project(":tson-bind"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
