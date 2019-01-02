/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * See LICENSE in the project root for license information.
 */

package jetbrains.buildServer.unity

import com.github.zafarkhaja.semver.Version
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import jetbrains.buildServer.agent.*
import jetbrains.buildServer.util.EventDispatcher
import java.io.File

/**
 * Determines tool location.
 */
class UnityToolProvider(toolsRegistry: ToolProvidersRegistry,
                        events: EventDispatcher<AgentLifeCycleListener>)
    : AgentLifeCycleAdapter(), ToolProvider {

    private val unityDetector = when {
        SystemInfo.isWindows -> WindowsUnityDetector()
        SystemInfo.isMac -> MacOsUnityDetector()
        SystemInfo.isLinux -> LinuxUnityDetector()
        else -> null
    }

    private val unityVersions = mutableMapOf<Version, String>()

    init {
        toolsRegistry.registerToolProvider(this)
        events.addListener(this)
    }

    override fun afterAgentConfigurationLoaded(agent: BuildAgent) {
        LOG.info("Locating ${UnityConstants.RUNNER_DISPLAY_NAME} tools")

        unityDetector?.let { detector ->
            detector.registerAdditionalHintPath(agent.configuration.agentToolsDirectory)

            detector.findInstallations().let { versions ->
                unityVersions.putAll(versions.sortedBy { it.first }.map {
                    it.first to it.second.absolutePath
                }.toMap())
            }
        }

        // Report all Unity versions
        unityVersions.forEach { (version, path) ->
            LOG.info("Found Unity $version at $path")
            agent.configuration.apply {
                val name = "${UnityConstants.UNITY_CONFIG_NAME}$version"
                addConfigurationParameter(name, path)
            }
        }
    }

    override fun supports(toolName: String): Boolean {
        return UnityConstants.RUNNER_TYPE.equals(toolName, true)
    }

    override fun getPath(toolName: String): String {
        return getUnityPath(toolName, null)
    }

    override fun getPath(toolName: String,
                         build: AgentRunningBuild,
                         runner: BuildRunnerContext): String {
        if (runner.isVirtualContext) {
            return UnityConstants.RUNNER_TYPE
        }

        val unityVersion = getUnityVersion(runner, build)
        return getUnityPath(toolName, unityVersion)
    }

    fun getUnityPath(toolName: String, unityVersion: Version?): String {
        if (!supports(toolName)) {
            throw ToolCannotBeFoundException("Unsupported tool $toolName")
        }

        if (unityDetector == null) {
            throw ToolCannotBeFoundException(UnityConstants.RUNNER_TYPE)
        }

        val unity = getUnity(toolName, unityVersion)
        return unityDetector.getEditorPath(File(unity.second)).absolutePath
    }

    fun getUnity(toolName: String,
                 build: AgentRunningBuild,
                 runner: BuildRunnerContext): Pair<Version, String> {
        if (runner.isVirtualContext) {
            return Version.forIntegers(2019) to UnityConstants.RUNNER_TYPE
        }

        val unityVersion = getUnityVersion(runner, build)
        return getUnity(toolName, unityVersion)
    }

    private fun getUnity(toolName: String, unityVersion: Version?): Pair<Version, String> {
        if (!supports(toolName)) {
            throw ToolCannotBeFoundException("Unsupported tool $toolName")
        }

        if (unityDetector == null) {
            throw ToolCannotBeFoundException(UnityConstants.RUNNER_TYPE)
        }

        val unity = if (unityVersion == null) {
            unityVersions.entries.lastOrNull()
        } else {
            unityVersions.entries.lastOrNull { it.key >= unityVersion }
        } ?: throw ToolCannotBeFoundException("""
                Unable to locate tool $toolName in system. Please make sure to specify UNITY_PATH environment variable
                """.trimIndent())

        return unity.key to unityDetector.getEditorPath(File(unity.value)).absolutePath
    }

    private fun getUnityVersion(runner: BuildRunnerContext, build: AgentRunningBuild): Version? {
        var unityVersion = runner.runnerParameters[UnityConstants.PARAM_UNITY_VERSION]?.trim()
        if (unityVersion.isNullOrEmpty()) {
            build.getBuildFeaturesOfType(UnityConstants.BUILD_FEATURE_TYPE).firstOrNull()?.let { feature ->
                unityVersion = feature.parameters[UnityConstants.PARAM_UNITY_VERSION]?.trim()
            }
        }
        if (unityVersion == null) {
            return null
        }

        return Version.valueOf(unityVersion)
    }

    companion object {
        private val LOG = Logger.getInstance(UnityToolProvider::class.java.name)
    }
}
