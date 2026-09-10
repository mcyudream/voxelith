plugins { `java-library` }

dependencies {
    api(project(":modules:shared-kernel"))

    // interfaces/infrastructure 层适配 HTTP 与 DI 所需；由 apps/voxelith-server 在运行时提供实现
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-context")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")
}
