allprojects {
    group = "io.ltr8"
    version = "0.35.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

/**
 * One line per module for its published POM -- the same account CLAUDE.md's own module list gives.
 */
val moduleDescriptions = mapOf(
    "tson" to "TSON front door: the Tson facade over the compiler's readers, writers and registries",
    "tson-compiler" to "TSON engine: lexer, both grammars, schema resolution, Class 2 compilation, readers, writers",
    "tson-schema" to "TSON resolved-schema value model (schema.meta) plus the schema registry and identity algorithm",
    "tson-tree" to "TSON data-document tree model: TsonValue and its pure immutable node types",
    "tson-bind" to "TSON binding engine between data values and Java objects",
    "tson-annotation" to "TSON binding annotations and the wire-annotation carrier a bound class declares",
    "tson-regex" to "A native RFC 9485 I-Regexp engine: parse, match, and decide whether two patterns are disjoint",
    "tson-cli" to "The tson command-line application"
)

subprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        // Part of the publication, not an extra: a consumer reading this API in an IDE is the audience.
        withSourcesJar()
        withJavadocJar()
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

        // CI sets this so a missing conformance corpus fails the build instead of aborting through
        // Assumptions. An abort reads green, which is how CI ran none of the shared corpus for as
        // long as it did -- see SuiteCheckout. Passed through explicitly rather than left to
        // environment inheritance, so the daemon sees it change.
        System.getenv("TSON_REQUIRE_TEST_SUITE")?.let { environment("TSON_REQUIRE_TEST_SUITE", it) }
        System.getProperty("tson.testSuite.dir")?.let { systemProperty("tson.testSuite.dir", it) }

        // The conformance corpus is a real test input that lives outside this build, so Gradle cannot
        // see a vector change and would report the previous run's result as up to date -- a stale green
        // over an edited corpus, which is the one thing a conformance signal must not do. Declared here
        // with the same search order SuiteCheckout uses, so editing a vector re-runs the suite.
        val corpus = listOfNotNull(
            System.getProperty("tson.testSuite.dir")?.let { file(it) },
            rootProject.file("../ltr8-io-tson-test-suite"),
            rootProject.file(".references/ltr8-io-tson-test-suite")
        ).firstOrNull { it.isDirectory }
        corpus?.let { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("conformanceCorpus") }
    }

    // Every module publishes, so `./gradlew publishToMavenLocal` puts the whole set in ~/.m2 and another
    // project takes an ordinary `io.ltr8:tson:<version>` dependency instead of an included build. The jars
    // carry real `module-info.class`es, so they work on the module path as well as the class path.
    //
    // **Deliberately no remote repository.** This is packaging, not release: Maven Central needs signed
    // artifacts and a POM carrying scm/developers/url, and publishing under a name is a decision this build
    // should not make quietly. What is here is the part a consuming project needs.
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set(moduleDescriptions.getValue(project.name))
                    url.set("https://github.com/litterat/ltr8-io-tson-java")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
    }
}
