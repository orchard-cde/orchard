plugins {
    `java-library`
    id("me.champeau.jmh")
}

dependencies {
    api(project(":core"))
    api(project(":nursery"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testRuntimeOnly("org.slf4j:slf4j-simple")
}

jmh {
    jmhVersion.set("1.37")
    fork.set(1)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json").get().asFile)
}
