/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.noorall.codex.bridge

import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

class WindowsIdeaBridgeSmokeTest {
    init {
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?,
                    ) {
                        fail<Unit>("$testName failed inside IntelliJ IDEA: $message\n$details")
                    }
                }
            }
        }
    }

    @Test
    fun `starts the plugin and accepts a Windows IDE bridge connection`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeTrue(System.getProperty("os.arch") in setOf("amd64", "x86_64"))

        val ideaVersion = System.getProperty("codex.bridge.integration.ide.version")
        val pluginPath = File(requireNotNull(System.getProperty("path.to.build.plugin")))
        val projectPath = Path.of("src", "integrationTest", "testData", "windows-smoke").toAbsolutePath().normalize()

        Starter.newContext(
            testName = "windowsIdeaBridge",
            testCase = TestCase(
                IdeProductProvider.IC,
                LocalProjectInfo(projectPath),
            ).withVersion(ideaVersion),
        ).apply {
            PluginConfigurator(this).installPluginFromFolder(pluginPath)
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(2.minutes)
            waitForNamedPipeConnection()
        }
    }

    private fun waitForNamedPipeConnection() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var lastFailure: IOException? = null

        while (System.nanoTime() < deadline) {
            try {
                RandomAccessFile(WINDOWS_PIPE_PATH, "rw").use { }
                return
            } catch (error: IOException) {
                lastFailure = error
                Thread.sleep(250)
            }
        }

        fail<Unit>("IntelliJ IDEA did not expose $WINDOWS_PIPE_PATH", lastFailure)
    }

    private companion object {
        const val WINDOWS_PIPE_PATH = "\\\\.\\pipe\\codex-ipc"
    }
}
