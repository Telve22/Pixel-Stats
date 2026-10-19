plugins {
    id("net.labymod.labygradle")
    id("net.labymod.labygradle.addon")
}

val versions = providers.gradleProperty("net.labymod.minecraft-versions").get().split(";")

group = "de.pixelstats"
version = providers.environmentVariable("VERSION").getOrElse("1.0.0")

labyMod {
    defaultPackageName = "de.pixelstats"

    minecraft {
        registerVersion(versions.toTypedArray()) {}
    }

    addonInfo {
        namespace = "pixelstats"
        displayName = "Pixel Stats"
        author = "Musti"
        description = "Pixel balance, earnings, expenses and apartment tracking for LabyWorld."
        minecraftVersion = "*"
        version = rootProject.version.toString()
    }
}

allprojects {
    tasks.withType<ProcessResources>().configureEach {
        exclude("assets/example/**")
    }
}

subprojects {
    plugins.apply("net.labymod.labygradle")
    plugins.apply("net.labymod.labygradle.addon")

    group = rootProject.group
    version = rootProject.version

    extensions.findByType(JavaPluginExtension::class.java)?.apply {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
