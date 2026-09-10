plugins { `java-library` }

dependencies {
    testImplementation(project(":modules:shared-kernel"))
    testImplementation(project(":modules:resource-context"))
    testImplementation(project(":modules:world-context"))
    testImplementation(project(":modules:runtime-context"))
    testImplementation(project(":modules:bake-context"))
    testImplementation(project(":modules:tile-context"))
    testImplementation(project(":modules:lod-context"))
    testImplementation(project(":modules:orchestration-context"))
    testImplementation(project(":modules:marker-context"))
    testImplementation(project(":modules:map-context"))
    testImplementation(libs.archunit.junit5)
}
