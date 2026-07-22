plugins {
    id("org.springframework.boot")
    id("org.graalvm.buildtools.native")
    id("me.champeau.jmh")
}

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
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    implementation(project(":core"))
    implementation(project(":roots"))
    implementation(project(":harvest"))
    implementation(project(":nursery"))
    implementation(project(":greenhouse"))
    implementation(project(":apiary"))
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server") // for default credential chain (web-identity tokens)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // AWS SDK needed to wire Ec2Client / Ec2Operations / Ec2InstanceWaiter beans in NurseryConfig.
    // nursery declares these as `implementation` (not `api`), so we must repeat them here.
    implementation(platform("software.amazon.awssdk:bom:2.47.5"))
    implementation("software.amazon.awssdk:ec2")
    implementation("software.amazon.awssdk:url-connection-client")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("software.amazon.awssdk:sts")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

jmh {
    jmhVersion.set("1.37")
    fork.set(1)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json").get().asFile)
}
