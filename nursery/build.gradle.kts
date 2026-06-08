plugins {
    `java-library`
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

    implementation(platform("software.amazon.awssdk:bom:2.30.0"))
    implementation("software.amazon.awssdk:ec2")
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("software.amazon.awssdk:sts") // for default credential chain (web-identity tokens)

    testRuntimeOnly("org.slf4j:slf4j-simple")

    // Cloud provider SDK dependencies will be added when implementations are ready:
    // GCP:   implementation("com.google.cloud:google-cloud-compute:1.44.0")
    // Azure: implementation("com.azure.resourcemanager:azure-resourcemanager-compute:2.39.0") + implementation("com.azure:azure-identity:1.12.0")
}
