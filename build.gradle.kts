plugins {
    java
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.graalvm.buildtools.native") version "1.1.3" apply false
    id("org.openrewrite.rewrite") version "latest.release"
    id("me.champeau.jmh") version "0.7.3" apply false
}

rewrite {
    activeRecipe(
        // DependencyCleanup was run once to remove redundant BOM pins (see commit history).
        // It is kept in rewrite.yml for reference but is NOT active — re-running it would
        // remove versionless dependency declarations that are still needed.
        "dev.orchard.BestPractices",
    )
    // TEMPORARY (tracked by #152): CommonStaticAnalysis' MinimumSwitchCases recipe throws
    // IndexOutOfBoundsException on pattern-matching switches (case Type x ->), which aborts the
    // whole run. These two files are the only ones using that idiom. The bug is already fixed
    // upstream in rewrite-static-analysis; once a release with the fix lands (picked up
    // automatically via latest.release), drop this exclusion block. Note: this excludes the files
    // from ALL active recipes, and a third file adopting a pattern switch will re-break the run.
    exclusion(
        "greenhouse/**/ImageBuilder.java",
        "nursery/**/FruitGrower.java",
    )
    setExportDatatables(true)
}

dependencies {
    rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.release"))
    rewrite("org.openrewrite.recipe:rewrite-java-dependencies")
    rewrite("org.openrewrite.recipe:rewrite-testing-frameworks")
    rewrite("org.openrewrite.recipe:rewrite-static-analysis")
    rewrite("org.openrewrite.recipe:rewrite-spring")
}

allprojects {
    group = "dev.orchard"
    version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        }
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:6.1.1"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.assertj:assertj-core:3.27.7")
        testImplementation("org.mockito:mockito-core:5.23.0")
        testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

tasks.register<Copy>("install") {
    description = "Builds and installs native executables into ~/.orchard/bin/"
    group = "distribution"

    val installDir = File(System.getProperty("user.home"), ".orchard/bin")

    dependsOn(":trellis:nativeCompile", ":trowel:nativeCompile")

    from(project(":trellis").layout.buildDirectory.dir("native/nativeCompile"))
    from(project(":trowel").layout.buildDirectory.dir("native/nativeCompile"))
    into(installDir)
}
