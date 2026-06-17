import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("org.springframework.boot")
    id("org.graalvm.buildtools.native")
}

fun downloadTo(url: String, dest: File) {
    dest.parentFile.mkdirs()
    URI(url).toURL().openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
}
fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
fun sha256FromChecksums(checksumFile: File, tarballName: String): String =
    checksumFile.readLines().firstOrNull { it.trim().endsWith(tarballName) }
        ?.trim()?.split(Regex("\\s+"))?.first()
        ?: throw GradleException("No sha256 entry for $tarballName in ${checksumFile.name}")

configurations.all {
    // AWS SDK service modules pull apache-client and netty-nio-client transitively.
    // We use UrlConnectionHttpClient (smallest native-image surface), so exclude both.
    exclude(group = "software.amazon.awssdk", module = "apache-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("orchard-server")
            mainClass.set("dev.orchard.trellis.OrchardApplication")
            buildArgs.add("--no-fallback")
        }
    }
}

springBoot {
    buildInfo()
}

tasks.named("nativeCompile") {
    dependsOn(tasks.named("bootBuildInfo"))
}

dependencies {
    implementation(project(":core"))
    implementation(project(":roots"))
    implementation(project(":harvest"))
    implementation(project(":nursery"))
    implementation(project(":greenhouse"))
    implementation(project(":api"))

    // AWS SDK needed to wire Ec2Client / Ec2Operations / Ec2InstanceWaiter beans in NurseryConfig.
    // nursery declares these as `implementation` (not `api`), so we must repeat them here.
    implementation(platform("software.amazon.awssdk:bom:2.30.0"))
    implementation("software.amazon.awssdk:ec2")
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("software.amazon.awssdk:sts") // for default credential chain (web-identity tokens)

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("com.h2database:h2")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
}

val orchardUiBundleVersion = providers.gradleProperty("orchardUiBundleVersion").get()
val uiBundleDir = layout.buildDirectory.dir("ui-bundle")
val uiResourcesDir = layout.buildDirectory.dir("generated/ui-resources") // holds static/

val fetchUiBundle by tasks.registering {
    val tarballFile = uiBundleDir.map { it.file("orchard-ui-bundle-$orchardUiBundleVersion.tar.gz") }
    val checksumFile = uiBundleDir.map { it.file("checksums-sha256.txt") }
    inputs.property("version", orchardUiBundleVersion)
    outputs.file(tarballFile)
    // Prevent Gradle's stale-output cleanup from deleting a pre-seeded bootstrap file.
    // The task manages its own output: it skips the download when the sha256 already matches.
    doNotTrackState("download task manages its own output; pre-seed bootstrap file must not be cleaned")
    doLast {
        val base = "https://github.com/orchard-cde/orchard-ui/releases/download/v$orchardUiBundleVersion"
        val tarball = tarballFile.get().asFile
        val checksum = checksumFile.get().asFile
        // Anonymous GET; guard the checksum download too.
        if (!checksum.exists()) downloadTo("$base/checksums-sha256.txt", checksum)
        val expected = sha256FromChecksums(checksum, tarball.name)
        if (!tarball.exists() || sha256(tarball) != expected) {
            try {
                downloadTo("$base/orchard-ui-bundle-$orchardUiBundleVersion.tar.gz", tarball)
            } catch (e: java.io.IOException) {
                if (tarball.exists()) {
                    throw GradleException(
                        "orchard-ui bundle v$orchardUiBundleVersion is present but its sha256 does not match $expected " +
                        "and re-download failed (${e.message}). Delete $tarball and rebuild, or pre-seed a valid copy."
                    )
                }
                throw GradleException(
                    "Could not download orchard-ui bundle v$orchardUiBundleVersion (${e.message}). " +
                    "If orchard-ui is still private, pre-seed $tarball and ${checksum.name} (see CONTRIBUTING)."
                )
            }
        }
        val actual = sha256(tarball)
        if (actual != expected) {
            throw GradleException(
                "orchard-ui bundle checksum mismatch for v$orchardUiBundleVersion (expected=$expected actual=$actual). " +
                "Delete $tarball and rebuild, or pre-seed a valid copy."
            )
        }
    }
}

val unpackUiBundle by tasks.registering(Copy::class) {
    dependsOn(fetchUiBundle)
    from({ tarTree(resources.gzip(uiBundleDir.get().file("orchard-ui-bundle-$orchardUiBundleVersion.tar.gz").asFile)) })
    into(uiResourcesDir.map { it.dir("static") }) // archive root -> static/index.html
}

sourceSets.named("main") { resources.srcDir(uiResourcesDir) } // static/** on classpath
tasks.named("processResources") { dependsOn(unpackUiBundle) }
