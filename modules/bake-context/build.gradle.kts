plugins { `java-library` }

dependencies {
    api(project(":modules:shared-kernel"))
    api(project(":modules:world-context"))
    api(project(":modules:resource-context"))
    implementation(libs.gson)

    testImplementation(testFixtures(project(":modules:world-context")))
}
