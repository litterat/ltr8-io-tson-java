plugins {
    id("java-library")
}

dependencies {
    // api, not implementation -- this module now has a module-info.java (requires io.ltr8.annotation,
    // deliberately not `requires transitive`, since no public schema.meta method signature exposes an
    // annotation type directly). JPMS module-path resolution still needs io.ltr8.annotation physically
    // present for any downstream compilation that transitively requires io.ltr8.tson.schema (tson-parser,
    // tson) -- Gradle's own implementation/api split controls compile-classpath *type* visibility, which
    // is a separate concern from the module graph's own presence requirement.
    api(project(":tson-annotation"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
