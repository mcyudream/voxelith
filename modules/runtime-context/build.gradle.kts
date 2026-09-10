plugins { `java-library` }

dependencies {
    api(project(":modules:shared-kernel"))
    // 降级兜底：runtime 采集失败时经 resource-context application 端口回退静态解析
    api(project(":modules:resource-context"))
    implementation(libs.gson)
}

// 进程隔离集成测试需要 worker 子进程的可执行 classpath
tasks.test {
    dependsOn(":modules:runtime-worker:classes")
}
