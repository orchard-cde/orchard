rootProject.name = "orchard"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "core",
    "roots",
    "harvest",
    "nursery",
    "greenhouse",
    "api",
    "trellis",
    "trowel",
    "integration-tests"
)
