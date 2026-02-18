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
    "api",
    "server",
    "trowel",
    "canopy"
)
