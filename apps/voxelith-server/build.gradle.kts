plugins {
    `java-library`
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
}

dependencies {
    implementation(project(":modules:shared-kernel"))
    implementation(project(":modules:resource-context"))
    implementation(project(":modules:world-context"))
    implementation(project(":modules:runtime-context"))
    implementation(project(":modules:bake-context"))
    implementation(project(":modules:tile-context"))
    implementation(project(":modules:lod-context"))
    implementation(project(":modules:orchestration-context"))
    implementation(project(":modules:marker-context"))
    implementation(project(":modules:map-context"))

    implementation(libs.springBoot.starter.web)
    implementation(libs.springBoot.starter.validation)

    testImplementation(libs.springBoot.starter.test)
}
