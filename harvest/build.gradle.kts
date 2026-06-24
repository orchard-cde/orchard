plugins {
    `java-library`
    id("me.champeau.jmh")
}

dependencies {
    api(project(":core"))

    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

jmh {
    jmhVersion.set("1.37")
    fork.set(1)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json").get().asFile)
}
