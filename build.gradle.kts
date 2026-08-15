allprojects {
    group = "io.ltr8"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all"))
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"

        // Every doclint group except `missing`. Not every public member earns a doc comment -- an
        // obvious accessor is clearer undocumented than wrapped in a restatement of its own name --
        // so `missing` (no comment / no @param / no @return) is noise here, and ~560 lines of it
        // buried the errors that do matter. What stays on is the half that catches real breakage:
        // `reference` (a {@link} to something that doesn't exist), `syntax`, `html`, `accessibility`.
        //
        // Written as addStringOption because Gradle has no first-class doclint setting: it emits
        // `-<key> <value>`, so the flag rides in the key and `-quiet` is a harmless filler value.
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
