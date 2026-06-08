plugins {
    `java-library`
}

dependencies {
    api(project(":core"))

    implementation("org.slf4j:slf4j-api")

    implementation(platform("software.amazon.awssdk:bom:2.30.0"))
    implementation("software.amazon.awssdk:ec2")
    implementation("software.amazon.awssdk:url-connection-client")
    implementation("software.amazon.awssdk:apache-client") {
        // We use UrlConnectionHttpClient (native-image friendly). Exclude Apache HTTP
        // client which is the SDK's transitive default.
        isTransitive = false
    }
    runtimeOnly("software.amazon.awssdk:sts") // for default credential chain (web-identity tokens)

    testRuntimeOnly("org.slf4j:slf4j-simple")

    // Cloud provider SDK dependencies will be added when implementations are ready:
    // GCP:   implementation("com.google.cloud:google-cloud-compute:1.44.0")
    // Azure: implementation("com.azure.resourcemanager:azure-resourcemanager-compute:2.39.0") + implementation("com.azure:azure-identity:1.12.0")
}
