plugins {
    `java-library`
}

dependencies {
    // Held deliberately, not because main sources reference it — after #86 stage 2 they take a
    // bare host/port/id endpoint and import nothing from :core. The edge stays because module
    // placement for the exec seam is still open (#215, open question 1): if Plan 3's PlotEndpoint
    // lands in :core and Vine comes to take it, this is the dependency that would be needed back,
    // and dropping it now would be churn. Keeping it `api` preserves the pre-existing transitive
    // behaviour for :vine's consumers (:apiary, :nursery, :trellis all declare :core themselves).
    api(project(":core"))
    // SshExecutor logs command execution/failures over SSH.
    implementation("org.slf4j:slf4j-api")

    testRuntimeOnly("org.slf4j:slf4j-simple")
}
