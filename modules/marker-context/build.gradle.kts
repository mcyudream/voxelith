plugins { `java-library` }

dependencies {
    api(project(":modules:shared-kernel"))
    implementation(libs.gson)
}
