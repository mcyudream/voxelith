import java.io.File

plugins {
    `java-library`
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
}

dependencies {
    implementation(project(":modules:shared-kernel"))
    implementation(project(":modules:resource-context"))
    implementation(project(":modules:world-context"))
    implementation(project(":modules:runtime-context"))
    implementation(project(":modules:bake-context"))
    implementation(project(":modules:tile-context"))
    implementation(project(":modules:lod-context"))
    implementation(project(":modules:orchestration-context"))
    implementation(project(":modules:marker-context"))
    implementation(project(":modules:map-context"))

    implementation(libs.springBoot.starter.web)
    implementation(libs.springBoot.starter.validation)

    testImplementation(libs.springBoot.starter.test)
}

/**
 * headless 采集入口（Phase 5 生产入口，路线图「仓库内管线入口」的第一段）：
 * 采集 BakedModel 并导出 models.json.gz，mod 方块随 --mods 一并导出。
 *
 * worker 必须跑在独立 JVM 里（ADR 0001），因此把 runtime-worker 的 runtimeClasspath
 * 作为参数传给 CLI，由 CLI 原样转交子进程；父进程自身不需要（也不应）依赖 runtime-worker。
 *
 * 用法：
 *   gradlew :apps:voxelith-server:harvestModels -PpackDir=<原版 client.jar> [-Pmods=<mod1.jar,mod2.jar>]
 * 可选：-PmcVersion -PloaderVersion -PworkDir -PprovisionCache -PtimeoutMinutes -PskipProvision
 */
tasks.register<JavaExec>("harvestModels") {
    group = "voxelith"
    description = "headless 采集 BakedModel 导出 models.json.gz（支持加载 mod 方块）"
    mainClass.set("online.yudream.voxelith.server.cli.HarvestModelsCli")
    classpath = sourceSets["main"].runtimeClasspath

    // 配置期解析为字符串：worker classpath 只作为参数传给子进程，不是本任务的类路径输入。
    // 也随之避开配置缓存「不能序列化脚本对象引用」的限制（参数全是普通字符串）。
    val workerClasspath: String = project(":modules:runtime-worker")
        .extensions.getByType<SourceSetContainer>()["main"]
        .runtimeClasspath
        .asPath

    val cliArgs = buildList {
        fun option(name: String, property: String) {
            providers.gradleProperty(property).orNull?.let {
                add("--$name")
                add(it)
            }
        }
        option("mc-version", "mcVersion")
        option("loader-version", "loaderVersion")
        option("work-dir", "workDir")
        option("pack-dir", "packDir")
        option("provision-cache", "provisionCache")
        option("timeout-minutes", "timeoutMinutes")
        providers.gradleProperty("mods").orNull?.let {
            add("--mod")
            add(it)
        }
        if (providers.gradleProperty("skipProvision").orNull == "true") {
            add("--skip-provision")
        }
        workerClasspath.split(File.pathSeparator).forEach {
            add("--worker-classpath")
            add(it)
        }
    }
    args(cliArgs)
}

/**
 * 全量管线入口（Phase 7「仓库内管线入口」）：把存档的 region 窗口渲染为可发布地图，
 * 串起 bake → tile → lod → manifest，产物直接落在 publishDir 供服务端服务。
 *
 * 必须单遍跑完一个窗口：hires 图集布局按本次 run 用到的贴图打包，分多遍每遍布局不同，
 * 跨遍的瓦片 UV 会对不上（缺的贴图会变成品红兜底格）。
 *
 * 用法：
 *   gradlew :apps:voxelith-server:renderMap -PworldDir=<存档> -PmapId=yit \
 *       -Ppacks="<原版 client.jar>,<mod.jar>" -PregionX0=.. -PregionX1=.. -PregionZ0=.. -PregionZ1=..
 * 大世界请配合 -Pheap=<如 8g>（bake 会把窗口内全部区块网格留在内存里）。
 */
tasks.register<JavaExec>("renderMap") {
    group = "voxelith"
    description = "渲染存档为可发布地图（bake→tile→lod→manifest，支持 mod 资源包与 region 窗口）"
    mainClass.set("online.yudream.voxelith.server.cli.RenderMapCli")
    classpath = sourceSets["main"].runtimeClasspath
    // 中文报告统一按 UTF-8 输出（Java 18+ 的 System.out 走 stdout.encoding，不是 file.encoding）
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    providers.gradleProperty("heap").orNull?.let { maxHeapSize = it }

    val workerClasspath: String = project(":modules:runtime-worker")
        .extensions.getByType<SourceSetContainer>()["main"]
        .runtimeClasspath
        .asPath

    val cliArgs = buildList {
        fun option(name: String, property: String) {
            providers.gradleProperty(property).orNull?.let {
                add("--$name")
                add(it)
            }
        }
        option("world-dir", "worldDir")
        option("map-id", "mapId")
        option("map-name", "mapName")
        option("packs", "packs")
        option("work-dir", "workDir")
        option("publish-dir", "publishDir")
        option("models-file", "modelsFile")
        option("dimension", "dimension")
        option("region-x0", "regionX0")
        option("region-x1", "regionX1")
        option("region-z0", "regionZ0")
        option("region-z1", "regionZ1")
        option("max-level", "maxLevel")
        option("sample-chunks", "sampleChunks")
        option("min-y", "minY")
        option("min-x", "minX")
        option("max-x", "maxX")
        option("min-z", "minZ")
        option("max-z", "maxZ")
        if (providers.gradleProperty("noLodAtlas").orNull == "true") {
            add("--no-lod-atlas")
        }
        option("mc-version", "mcVersion")
        option("loader-version", "loaderVersion")
        if (providers.gradleProperty("skipHarvest").orNull == "true") {
            add("--skip-harvest")
        }
        // 采集要跑独立 JVM 子进程，把 runtime-worker 的 classpath 一并注入
        workerClasspath.split(File.pathSeparator).forEach {
            add("--worker-classpath")
            add(it)
        }
    }
    args(cliArgs)
}

/**
 * 后端全景渲染（服务端预渲染）：读 renderMap 产出的地表栅格 + 高度场，烘一张透视全景 PNG。
 * 不触碰任何 3D 瓦片链路——3D 效果保持不变，全景只是额外产物（弱机兜底 / 俯瞰模式）。
 *
 * 用法：gradlew :apps:voxelith-server:renderPanorama -PmapId=<id> [-Pyaw=0 -Ppitch=45 -Pwidth=3840 -Pheight=2160 -Pfov=60 -Pdistance=1.25]
 * 产物：<publishDir>/<mapId>/panorama.png + panorama.json（相机参数）+ aerial.png（正交航拍图）
 */
tasks.register<JavaExec>("renderPanorama") {
    group = "voxelith"
    description = "服务端预渲染全景图（读 workDir 的地表栅格与高度场，不重跑渲染管线）"
    mainClass.set("online.yudream.voxelith.server.cli.PanoramaCli")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    providers.gradleProperty("heap").orNull?.let { maxHeapSize = it }

    val cliArgs = buildList {
        fun option(name: String, property: String) {
            providers.gradleProperty(property).orNull?.let {
                add("--$name")
                add(it)
            }
        }
        option("work-dir", "workDir")
        option("publish-dir", "publishDir")
        option("map-id", "mapId")
        option("width", "width")
        option("height", "height")
        option("yaw", "yaw")
        option("pitch", "pitch")
        option("fov", "fov")
        option("distance", "distance")
        option("output", "output")
    }
    args(cliArgs)
}
