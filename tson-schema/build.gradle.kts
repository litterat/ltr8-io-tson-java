plugins {
    id("java-library")
}

dependencies {
    // api, not implementation -- this module now has a module-info.java (requires io.ltr8.annotation,
    // deliberately not `requires transitive`, since no public schema.meta method signature exposes an
    // annotation type directly). JPMS module-path resolution still needs io.ltr8.annotation physically
    // present for any downstream compilation that transitively requires io.ltr8.tson.schema (tson-compiler,
    // tson) -- Gradle's own implementation/api split controls compile-classpath *type* visibility, which
    // is a separate concern from the module graph's own presence requirement.
    api(project(":tson-annotation"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// TsonBundledSchemas needs meta-kernel/meta/core's own source files on the classpath at runtime
// (none of the three can be resolved from nothing -- see that class's own Javadoc). Packaged straight
// from the repo's own spec/ snapshot rather than a duplicated copy under src/main/resources, so there
// is exactly one file per document to keep in sync with the spec. Moved here from tson-compiler
// (2026-07-29) alongside TsonBundledSchemas itself -- see that class's own Javadoc.
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("spec/m")) {
        include("meta-kernel.tn", "meta.tn", "core.tn")
    }
}
