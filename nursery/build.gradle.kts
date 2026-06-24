plugins {
    `java-library`
    id("me.champeau.jmh")
}

configurations.all {
    // AWS SDK service modules pull apache-client and netty-nio-client transitively.
    // We use UrlConnectionHttpClient (smallest native-image surface), so exclude both
    // to keep the classpath minimal and avoid HTTP-client ambiguity.
    exclude(group = "software.amazon.awssdk", module = "apache-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

dependencies {
    api(project(":core"))

    implementation("org.slf4j:slf4j-api")

    // DevcontainerCli parses @devcontainers/cli JSON outcome lines off SSH stdout.
    implementation("tools.jackson.core:jackson-databind")

    // DevcontainerCliConfig is a Spring @ConfigurationProperties record with @ImportRuntimeHints.
    // spring-context provides the annotation; spring-boot provides @ConfigurationProperties.
    // Versions resolved via the Spring Boot BOM applied in the root build.
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot")

    implementation(platform("software.amazon.awssdk:bom:2.46.15"))
    implementation("software.amazon.awssdk:ec2")
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("software.amazon.awssdk:sts") // for default credential chain (web-identity tokens)

    testRuntimeOnly("org.slf4j:slf4j-simple")

    // Cloud provider SDK dependencies will be added when implementations are ready:
    // GCP:   implementation("com.google.cloud:google-cloud-compute:1.44.0")
    // Azure: implementation("com.azure.resourcemanager:azure-resourcemanager-compute:2.39.0") + implementation("com.azure:azure-identity:1.12.0")
}

jmh {
    jmhVersion.set("1.37")
    fork.set(1)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json").get().asFile)
}
