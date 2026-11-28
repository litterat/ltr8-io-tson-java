plugins {
    id("java-library")
}

dependencies {
    // What both encodings join when a document is read into Java objects: a linked schema and the binding engine
    // that describes host classes. `api`, because the public view hands back their types (`EntryKey`, `Top`,
    // `DataClass`), and the two stacks above already depend on both, so no module gains a dependency it lacked.
    // `tson-base` arrives through them.
    api(project(":tson-schema"))
    api(project(":ltr8-bind"))

    // Tests only, and one direction only: a binding is loaded from a schema resolved and linked by the real
    // pipeline, which is `tson-compiler`'s and reached through the front door, as `tson-json`'s tests reach it.
    testImplementation(project(":tson"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
