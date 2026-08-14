plugins {
    id("java-library")
}

dependencies {
    // api, not implementation -- this module's own public surface (Tson/TsonConfig in particular)
    // hands back tson-compiler/tson-schema types directly (TsonCompiledMetaSchema, TsonLinkedSchema,
    // TsonObjectReader/TsonTreeReader/TsonObjectWriter, ...), so a caller depending on just this
    // module still needs them on its own compile classpath.
    api(project(":tson-compiler"))
    api(project(":tson-schema"))
    api(project(":tson-bind"))
    api(project(":tson-tree"))

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

// ExamplesTest runs every examples/ program as a real subprocess, so an API change that breaks one
// fails the build instead of silently rotting the docs. It needs the gathered module path (`modules`)
// and the two directories, passed as system properties.
tasks.named<Test>("test") {
    dependsOn("modules")
    systemProperty("tson.examples.dir", rootProject.projectDir.resolve("examples").absolutePath)
    systemProperty("tson.modules.dir", layout.buildDirectory.dir("modules").get().asFile.absolutePath)
}
