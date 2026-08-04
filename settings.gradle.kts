rootProject.name = "tson-java"

gradle.projectsEvaluated {
    allprojects {
        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.add("-Xlint:-module")
        }
    }
}

include("tson-compiler")
include("tson-annotation")
include("tson-bind")
include("tson-schema")
include("tson-tree")
include("tson-regex")
include("tson-cli")
include("tson")

