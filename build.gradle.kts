plugins {
    id("zenithproxy.plugin.dev") version "1.0.1-SNAPSHOT"
}

group = property("maven_group") as String
version = property("plugin_version") as String
val mc = property("mc") as String
val pluginId = property("plugin_id") as String

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

zenithProxyPlugin {
    templateProperties = mapOf(
        // variables in your BuildConstants.java template class
        "version" to project.version,
        "mc_version" to mc,
        "plugin_id" to pluginId,
        "maven_group" to group as String,
    )
    // the minimum supported java version for users of your plugin
    javaReleaseVersion = JavaLanguageVersion.of(21)
}

repositories {
//    maven("https://maven.2b2t.vc/snapshots") {
//        description = "ZenithProxy Prereleases"
//    }
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$mc-SNAPSHOT")

    /** to include dependencies into your plugin jar **/
    shade("io.javalin:javalin:7.2.3")
}

tasks {
    shadowJar {
        /**
         * relocate shaded dependencies to avoid conflicts with other plugins
         * transitive dependencies should also be relocated or removed (with exclude)
         * build and examine your plugin jar contents to check
         * https://gradleup.com/shadow/configuration/relocation/
         */
        val basePackage = "${project.group}.web.shadow"
        relocate("io.javalin", "$basePackage.javalin")
        relocate("jakarta.servlet", "$basePackage.jakarta.servlet")
        relocate("kotlin", "$basePackage.kotlin")
        relocate("org.eclipse", "$basePackage.org.eclipse")
        exclude("META-INF/maven/**")

        /**
         * remove unneeded transitive dependencies
         * https://gradleup.com/shadow/configuration/dependencies/#filtering-dependencies
         */
        dependencies {
            exclude(dependency("org.slf4j:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*:.*"))
            exclude(dependency("org.ow2.asm:.*:.*"))
        }
    }
}
