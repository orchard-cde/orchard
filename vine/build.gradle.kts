plugins {
    `java-library`
}

dependencies {
    // SshVine resolves host/port from Seedling. CommandRunner itself needs no core types.
    api(project(":core"))
    // SshExecutor logs command execution/failures over SSH.
    implementation("org.slf4j:slf4j-api")

    testRuntimeOnly("org.slf4j:slf4j-simple")
}
