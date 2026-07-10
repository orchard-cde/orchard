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
    "trellis",
    "trowel",
    "integration-tests"
)
