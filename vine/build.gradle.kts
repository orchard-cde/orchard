plugins {
    `java-library`
}

dependencies {
    // SshVine resolves host/port from Seedling. CommandRunner itself needs no core types.
    api(project(":core"))
}
