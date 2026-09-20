plugins { `java-library` }

dependencies {
    api(project(":modules:shared-kernel"))

    // markers.json 的协议编解码
    implementation(libs.gson)

    // interfaces/infrastructure 层适配 REST 与 DI；由 apps/voxelith-server 在运行时提供实现
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-context")
}
