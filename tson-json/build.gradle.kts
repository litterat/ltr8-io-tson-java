plugins {
    id("java-library")
}

// The allocation probe, shared with :tson's harness -- see that srcDir's note there.
sourceSets["test"].java.srcDir("../tson-base/src/testShared/java")

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

    // The atom vocabulary -- TsonAtomContext, so this encoding's default reader binds the host types the
    // built-in families read to exactly as the TSON text one does. [TSON-JSON] §5.1 is why that is right
    // rather than convenient: a string's content is handed to the atom's own parser exactly as a TSON
    // quoted token's text would be. §5-§8's schema-directed decode needs the rest of it.
    api(project(":tson-atom"))

    // `io.ltr8.bind` requires it transitively, so the module path needs it here even though nothing in
    // this module names one of its types directly -- `tson-bind` declares it `implementation`, which does
    // not propagate. `tson-compiler` carries the same line for the same reason.
    implementation(project(":tson-annotation"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// This encoding's allocation harness alone, with its report on stdout. It also runs as part of `test`,
// where its assertions are the point and the numbers scroll past; this task is for when the numbers *are*
// the point. The mirror of `:tson:allocationReport`, and it takes the same `-Dtson.alloc.jfr=<file>` for a
// Flight Recorder run when the next question is "where".
tasks.register<Test>("allocationReport") {
    group = "verification"
    description = "Runs the JSON allocation harness alone, printing bytes allocated per read."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("io.ltr8.tson.json.perf.*") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }   // a measurement is never up to date
    val recording = providers.systemProperty("tson.alloc.jfr")
    if (recording.isPresent) {
        jvmArgs("-XX:StartFlightRecording=settings=profile,filename=${recording.get()}")
    }
}
