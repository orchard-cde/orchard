plugins {
    `java-library`
}

dependencies {
    api(project(":core"))

    implementation("org.slf4j:slf4j-api:2.0.16")

    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")

    // Cloud provider SDK dependencies will be added when implementations are ready:
    // AWS:   implementation(platform("software.amazon.awssdk:bom:2.25.0")) + implementation("software.amazon.awssdk:ec2")
    // GCP:   implementation("com.google.cloud:google-cloud-compute:1.44.0")
    // Azure: implementation("com.azure.resourcemanager:azure-resourcemanager-compute:2.39.0") + implementation("com.azure:azure-identity:1.12.0")
}
