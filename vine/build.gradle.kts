plugins {
    `java-library`
}

dependencies {
    // SshExecutor logs command execution/failures over SSH.
    implementation("org.slf4j:slf4j-api")

    // Main and test sources take a bare host/port/id endpoint; :vine has no :core edge (#86 stage 2).
    testRuntimeOnly("org.slf4j:slf4j-simple")
}
