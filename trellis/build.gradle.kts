plugins {
    id("org.springframework.boot")
    id("org.graalvm.buildtools.native")
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
    implementation(project(":core"))
    implementation(project(":roots"))
    implementation(project(":harvest"))
    implementation(project(":nursery"))
    implementation(project(":greenhouse"))
    implementation(project(":api"))

    // AWS SDK needed to wire Ec2Client / Ec2Operations / Ec2InstanceWaiter beans in NurseryConfig.
    // nursery declares these as `implementation` (not `api`), so we must repeat them here.
    implementation(platform("software.amazon.awssdk:bom:2.46.17"))
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
