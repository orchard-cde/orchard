plugins {
    `java-library`
}

dependencies {
    // SshVine is built from a VmPlot (#215): the vine translates
    // the domain plot into transport primitives itself, so providers hand it a plot rather than
    // destructuring one. SshExecutor stays primitive and package-private.
    api(project(":core"))
    // SshExecutor logs command execution/failures over SSH.
    implementation("org.slf4j:slf4j-api")

    testRuntimeOnly("org.slf4j:slf4j-simple")
}
