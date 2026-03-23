plugins {
    application
    id("org.graalvm.buildtools.native") version "0.11.4"
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("trowel")
            mainClass.set("dev.orchard.trowel.Trowel")
            buildArgs.add("--no-fallback")
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
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

tasks.build {
    dependsOn(tasks.named("fatJar"))
}
