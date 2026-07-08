import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("com.diffplug.spotless") version "8.8.0"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
    id("org.nosphere.apache.rat") version "0.8.1"
}

group = "com.noorall.codex"
version = "0.1.1-dev"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

fun latestChangelogHtml(changelogFile: java.io.File): String {
    if (!changelogFile.isFile) {
        return ""
    }

    val lines = changelogFile.readLines()
    val start = lines.indexOfFirst { it.startsWith("## [") }
    if (start < 0) {
        return ""
    }

    val next = lines.drop(start + 1).indexOfFirst { it.startsWith("## [") }
    val end = if (next < 0) lines.size else start + 1 + next
    val section = lines.subList(start, end).dropLastWhile { it.isBlank() }
    if (section.isEmpty()) {
        return ""
    }

    val html = StringBuilder()
    html.append("<h2>").append(markdownInlineToHtml(section.first().removePrefix("## ").trim())).append("</h2>\n")

    section.drop(1).forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isBlank() -> Unit

            line.startsWith("### ") -> {
                html.append("""<p style="margin: 10px 0 4px 0;"><b>""")
                    .append(markdownInlineToHtml(line.removePrefix("### ").trim()))
                    .append("</b></p>\n")
            }

            line.startsWith("- ") -> {
                html.append("""<p style="margin: 0 0 2px 0;">&#8226;&nbsp;""")
                    .append(markdownInlineToHtml(line.removePrefix("- ").trim()))
                    .append("</p>\n")
            }

            else -> {
                html.append("<p>").append(markdownInlineToHtml(line)).append("</p>\n")
            }
        }
    }

    return pluginHtmlLayout("codex-bridge-change-notes", html.toString())
}

fun pluginHtmlLayout(className: String, bodyHtml: String): String {
    return """
        <style>
            .$className {
                width: auto;
                max-width: 100%;
                box-sizing: border-box;
                white-space: normal;
                overflow-wrap: break-word;
                word-wrap: break-word;
            }
            .$className * {
                max-width: 100%;
                box-sizing: border-box;
            }
            .$className code {
                white-space: normal;
                overflow-wrap: break-word;
                word-wrap: break-word;
            }
        </style>
        <div class="$className">
        $bodyHtml
        </div>
    """.trimIndent()
}

fun markdownInlineToHtml(text: String): String {
    val escaped = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    return escaped
        .replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
        .replace(Regex("""\[([^]]+)]\((https?://[^)]+)\)""")) {
            """<a href="${it.groupValues[2]}">${it.groupValues[1]}</a>"""
        }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("net.java.dev.jna:jna:5.17.0")

    intellijPlatform {
        create("IC", "2024.3.6")
        bundledPlugin("org.jetbrains.plugins.terminal")
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0")
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.rat {
    inputDir.set(layout.projectDirectory.dir("src/main/kotlin"))
    verbose.set(true)
}

tasks.named("check") {
    dependsOn("spotlessCheck", "rat")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }

        changeNotes = latestChangelogHtml(file("CHANGELOG.md"))
    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2026.1.4")
        }

        failureLevel.set(
            listOf(
                VerifyPluginTask.FailureLevel.COMPATIBILITY_WARNINGS,
                VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES,
                VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
                VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
                VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            ),
        )
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
