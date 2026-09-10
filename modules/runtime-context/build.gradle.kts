plugins { `java-library` }

dependencies {
    api(project(":modules:shared-kernel"))
    implementation(libs.gson)
}

// 进程隔离集成测试需要 worker 子进程的可执行 classpath
tasks.test {
    dependsOn(":modules:runtime-worker:classes")
}
