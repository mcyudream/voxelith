pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "voxelith"

include(
    "modules:shared-kernel",
    "modules:resource-context",
    "modules:world-context",
    "modules:runtime-context",
    "modules:bake-context",
    "modules:tile-context",
    "modules:lod-context",
    "modules:orchestration-context",
    "modules:marker-context",
    "modules:map-context",
    "modules:architecture-tests",
    "apps:voxelith-server",
)
