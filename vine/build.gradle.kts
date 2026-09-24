plugins {
    `java-library`
}

dependencies {
    // SshExecutor logs command execution/failures over SSH.
    implementation("org.slf4j:slf4j-api")

    // Main sources take a bare host/port/id endpoint and no longer need :core (#86 stage 2).
    // VineTestSeedlings still builds Seedling fixtures for these tests, so the edge survives
    // test-scoped only.
    testImplementation(project(":core"))
    testRuntimeOnly("org.slf4j:slf4j-simple")
}
