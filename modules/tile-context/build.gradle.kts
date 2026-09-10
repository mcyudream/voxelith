plugins { `java-library` }

dependencies {
    api(project(":modules:shared-kernel"))
    api(project(":modules:bake-context"))
    api(project(":modules:resource-context"))
    implementation(libs.gson)
}
