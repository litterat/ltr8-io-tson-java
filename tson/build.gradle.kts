plugins {
    id("java-library")
}

// The Class 2 conformance runner lives here rather than in :tson-compiler because what it exercises is
// the front door: Tson.resolve/validateSchema/validate own the phase boundaries a Class 2 vector is about.
// It shares the corpus checkout search and sidecar reading with the Class 1 runner -- see that module's
// src/testShared, added to both test source sets rather than copied into each.
sourceSets["test"].java.srcDir("../tson-compiler/src/testShared/java")

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

// The allocation harness alone, with its report on stdout -- `./gradlew :tson:allocationReport`. It also runs
// as part of `test`, where its assertions are the point and the numbers scroll past; this task is for when
// the numbers *are* the point. Add `-Dtson.alloc.jfr=build/alloc.jfr` to record the run under Flight
// Recorder, for allocation by call site rather than in total.
tasks.register<Test>("allocationReport") {
    group = "verification"
    description = "Runs the allocation harness alone, printing bytes allocated and retained per read."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("io.ltr8.tson.perf.*") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }   // a measurement is never up to date
    val recording = providers.systemProperty("tson.alloc.jfr")
    if (recording.isPresent) {
        jvmArgs("-XX:StartFlightRecording=settings=profile,filename=${recording.get()}")
    }
}
