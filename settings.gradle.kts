rootProject.name = "gkd"
include(
    ":app",
    ":hidden_api",
    ":selector",
)

pluginManagement {
    repositories {
        // 原本还有 mavenLocal(): 本机根本没有 ~/.m2(已核实), 没有任何构件来自它 ——
        // 留着只会让"依赖到底从哪来"变得不可预期, 故删除
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://jitpack.io")
    }
}
