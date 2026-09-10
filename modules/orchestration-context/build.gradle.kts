plugins { `java-library` }

dependencies {
    api(project(":modules:shared-kernel"))
    implementation(project(":modules:resource-context"))
    implementation(project(":modules:world-context"))
    implementation(project(":modules:runtime-context"))
    implementation(project(":modules:bake-context"))
    implementation(project(":modules:tile-context"))
    implementation(project(":modules:lod-context"))
    implementation(project(":modules:map-context"))
    implementation(libs.gson)
}
