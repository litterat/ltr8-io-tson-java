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

// Gather this module's runtime jars -- itself plus its transitive dependencies (tson-compiler,
// tson-schema, tson-bind, tson-annotation) -- into a single directory, so the single-file programs
// in examples/ can run straight off the module path with no build tool:
//   ./gradlew :tson:modules
//   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/ObjectBinding.java
tasks.register<Sync>("modules") {
    description = "Collects the tson runtime module jars into build/modules for the examples/ programs."
    from(configurations.runtimeClasspath)
    from(tasks.named("jar"))
    into(layout.buildDirectory.dir("modules"))
}
