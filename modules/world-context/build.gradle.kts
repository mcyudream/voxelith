plugins { `java-library`; `java-test-fixtures` }

dependencies {
    api(project(":modules:shared-kernel"))
    implementation(libs.gson)
}
