rootProject.name = "tson-java"

gradle.projectsEvaluated {
    allprojects {
        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.add("-Xlint:-module")
        }
    }
}

include("tson-compiler")
include("tson-base")
include("ltr8-annotation")
include("ltr8-bind")
include("tson-schema")
include("tson-atom")
include("tson-tree")
include("ltr8-regex")
include("ltr8-net")
include("ltr8-unicode")
include("tson-cli")
include("tson")
include("tson-json")

