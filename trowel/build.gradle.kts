plugins {
    application
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("trowel")
            mainClass.set("dev.orchard.trowel.Trowel")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:IncludeResources=version\\.properties")
        }
    }
}

dependencies {
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    implementation(project(":core"))

    implementation("info.picocli:picocli:4.7.7")
    implementation("tools.jackson.dataformat:jackson-dataformat-toml")
}

application {
    mainClass.set("dev.orchard.trowel.Trowel")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

// Create a fat JAR for distribution
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.orchard.trowel.Trowel"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}

tasks.named<ProcessResources>("processResources") {
    // Capture the version at configuration time; reading project.version inside the
    // filesMatching action runs at execution time and breaks the configuration cache.
    val projectVersion = project.version.toString()
    filesMatching("version.properties") {
        expand("version" to projectVersion)
    }
}

tasks.build {
    dependsOn(tasks.named("fatJar"))
}
