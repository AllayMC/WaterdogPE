import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
    application
    id("com.gorylenko.gradle-git-properties") version "2.5.7"
    id("com.gradleup.shadow") version "9.4.1"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

val baseVersion = providers.gradleProperty("waterdog.version").get()
val isDevBuild = providers.gradleProperty("waterdog.is-dev-build")
    .map(String::toBoolean)
    .orElse(true)
    .get()
val log4j2Version = "2.25.4"
val jlineVersion = "3.30.6"
val nettyVersion = "4.1.101.Final"
val raklibVersion = "1.0.0.CR3-20260328.145829-29"
val protocolVersion = "1.26.10-R2"

group = "org.allaymc"
version = if (isDevBuild) "$baseVersion-SNAPSHOT" else baseVersion
description = "Brand new Minecraft: Bedrock Edition proxy created by authors of well-known Waterdog proxy"

application {
    mainClass.set("dev.waterdog.waterdogpe.WaterdogPE")
}

shadow {
    addShadowVariantIntoJavaComponent = false
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
    maven("https://repo.waterdog.dev/main")
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    implementation("com.bugsnag:bugsnag:[3.0,4.0)")
    implementation("org.bstats:bstats-base:3.0.1")
    implementation("net.cubespace:Yamler-Core:2.4.1-20240423.205119-1")
    implementation("org.yaml:snakeyaml:1.32")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("it.unimi.dsi:fastutil:8.5.12")
    implementation("org.apache.commons:commons-lang3:3.18.0")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("com.lmax:disruptor:3.4.4")
    implementation("org.jline:jline:$jlineVersion")
    implementation("org.jline:jline-terminal:$jlineVersion")
    implementation("org.jline:jline-terminal-jna:$jlineVersion")
    implementation("org.jline:jline-reader:$jlineVersion")
    implementation("net.minecrell:terminalconsoleappender:1.3.0") {
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
        exclude(group = "org.jline", module = "jline-reader")
        exclude(group = "org.jline", module = "jline-terminal-jna")
        exclude(group = "org.jline", module = "jline-terminal")
    }
    implementation("org.allaymc.protocol:bedrock-codec:$protocolVersion") {
        exclude(group = "io.netty", module = "netty-buffer")
    }
    implementation("org.allaymc.protocol:bedrock-connection:$protocolVersion")
    implementation("org.cloudburstmc.netty:netty-transport-raknet:$raklibVersion")
    implementation("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
    implementation("io.netty:netty-transport-native-kqueue:$nettyVersion:osx-x86_64")
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.4")
}

gitProperties {
    dotGitDirectory = layout.projectDirectory.dir(".git")
    failOnNoGitDirectory = false
    keys = listOf("git.branch", "git.commit.id.abbrev", "git.build.version")
    gitProperties {
        customProperty("git.build.base_version", baseVersion)
        customProperty("git.build.is_dev_build", isDevBuild)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }

    named<ShadowJar>("shadowJar") {
        archiveFileName.set("Waterdog.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        manifest {
            attributes(
                "Main-Class" to application.mainClass.get(),
                "Multi-Release" to "true"
            )
        }

        exclude("org/jline/terminal/impl/ffm/**")

        filesMatching("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        transform(Log4j2PluginsCacheFileTransformer::class.java)
        mergeServiceFiles()
        relocate("org.bstats", "dev.waterdog")

        exclude("META-INF/maven/**")
        exclude("META-INF/native-image/**")
        exclude("META-INF/proguard/**")
        exclude("META-INF/AL2.0")
        exclude("META-INF/DEPENDENCIES")
        exclude("META-INF/*-LICENSE")
        exclude("META-INF/*-NOTICE")
        exclude("META-INF/io.netty.versions.properties")
        exclude("META-INF/LGPL2.1")
        exclude("META-INF/LICENSE")
        exclude("META-INF/LICENSE.txt")
        exclude("META-INF/NOTICE")
        exclude("META-INF/NOTICE.txt")
        exclude("META-INF/thirdparty-LICENSE")
    }

    named<JavaExec>("run") {
        enabled = false
    }

    named<JavaExec>("runShadow") {
        workingDir = file(".run")
    }

    named("build") {
        dependsOn("shadowJar")
    }
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral()
    signAllPublications()

    coordinates(project.group.toString(), "waterdogpe", project.version.toString())

    pom {
        name.set("waterdogpe")
        description.set(project.description)
        inceptionYear.set("2022")
        url.set("https://github.com/AllayMC/WaterdogPE")

        scm {
            connection.set("scm:git:git://github.com/AllayMC/WaterdogPE.git")
            developerConnection.set("scm:git:ssh://github.com/AllayMC/WaterdogPE.git")
            url.set("https://github.com/AllayMC/WaterdogPE")
        }

        licenses {
            license {
                name.set("GNU General Public License v2.0")
                url.set("https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")
            }
        }

        developers {
            developer {
                name.set("AllayMC Team")
                organization.set("AllayMC")
                organizationUrl.set("https://github.com/AllayMC")
            }
            developer {
                name.set("WaterdogTEAM")
                organization.set("WaterdogPE")
                organizationUrl.set("https://github.com/WaterdogPE")
            }
        }
    }
}
