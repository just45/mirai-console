/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3 license that can be found via the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress("NOTHING_TO_INLINE", "unused")

package net.mamoe.mirai.console.internal.plugin

import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.extension.useExtensions
import net.mamoe.mirai.console.extensions.PluginLoaderProvider
import net.mamoe.mirai.console.internal.data.cast
import net.mamoe.mirai.console.internal.data.mkdir
import net.mamoe.mirai.console.plugin.*
import net.mamoe.mirai.console.plugin.description.PluginDependency
import net.mamoe.mirai.console.plugin.description.PluginDescription
import net.mamoe.mirai.console.plugin.description.PluginKind
import net.mamoe.mirai.console.plugin.jvm.JvmPlugin
import net.mamoe.mirai.console.util.CoroutineScopeUtils.childScope
import net.mamoe.mirai.utils.info
import java.io.File
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock

internal object PluginManagerImpl : PluginManager, CoroutineScope by MiraiConsole.childScope("PluginManager") {

    override val pluginsPath: Path = MiraiConsole.rootPath.resolve("plugins").apply { mkdir() }
    override val pluginsFolder: File = pluginsPath.toFile()
    override val pluginsDataPath: Path = MiraiConsole.rootPath.resolve("data").apply { mkdir() }
    override val pluginsDataFolder: File = pluginsDataPath.toFile()
    override val pluginsConfigPath: Path = MiraiConsole.rootPath.resolve("config").apply { mkdir() }
    override val pluginsConfigFolder: File = pluginsConfigPath.toFile()

    @Suppress("ObjectPropertyName")
    private val _pluginLoaders: MutableList<PluginLoader<*, *>> by lazy {
        MiraiConsole.builtInPluginLoaders.toMutableList()
    }
    private val loadersLock: ReentrantLock = ReentrantLock()
    private val logger = MiraiConsole.createLogger("plugin")

    @JvmField
    internal val resolvedPlugins: MutableList<Plugin> = mutableListOf()
    override val plugins: List<Plugin>
        get() = resolvedPlugins.toList()
    override val builtInLoaders: List<PluginLoader<*, *>>
        get() = MiraiConsole.builtInPluginLoaders
    override val pluginLoaders: List<PluginLoader<*, *>>
        get() = _pluginLoaders.toList()

    override val Plugin.description: PluginDescription
        get() = if (this is JvmPlugin) {
            this.safeLoader.getDescription(this)
        } else resolvedPlugins.firstOrNull { it == this }
            ?.loader?.cast<PluginLoader<Plugin, PluginDescription>>()
            ?.getDescription(this)
            ?: error("Plugin is unloaded")


    override fun PluginLoader<*, *>.register(): Boolean = loadersLock.withLock {
        if (_pluginLoaders.any { it::class == this::class }) {
            return false
        }
        _pluginLoaders.add(this)
    }

    override fun PluginLoader<*, *>.unregister() = loadersLock.withLock {
        _pluginLoaders.remove(this)
    }

    init {
        MiraiConsole.coroutineContext[Job]!!.invokeOnCompletion {
            plugins.forEach { it.disable() }
        }
    }

    // region LOADING

    private fun <P : Plugin, D : PluginDescription> PluginLoader<P, D>.loadPluginNoEnable(plugin: P) {
        kotlin.runCatching {
            this.load(plugin)
            resolvedPlugins.add(plugin)
        }.fold(
            onSuccess = {
                logger.info { "Successfully loaded plugin ${plugin.description.name}" }
            },
            onFailure = {
                logger.info { "Cannot load plugin ${plugin.description.name}" }
                throw it
            }
        )
    }

    private fun <P : Plugin, D : PluginDescription> PluginLoader<P, D>.enablePlugin(plugin: Plugin) {
        kotlin.runCatching {
            @Suppress("UNCHECKED_CAST")
            this.enable(plugin as P)
        }.fold(
            onSuccess = {
                logger.info { "Successfully enabled plugin ${plugin.description.name}" }
            },
            onFailure = {
                logger.info { "Cannot enable plugin ${plugin.description.name}" }
                throw it
            }
        )
    }

    internal class PluginLoadSession(
        val allKindsOfPlugins: List<Pair<PluginLoader<*, *>, List<PluginDescriptionWithLoader>>>
    )

    // Phase #2
    internal fun scanPluginsUsingPluginLoadersIncludingThoseFromPluginLoaderProvider(): PluginLoadSession {
        return PluginLoadSession(loadersLock.withLock { _pluginLoaders.listAllPlugins() })
    }

    // Phase #0
    internal fun loadEnablePluginProviderPlugins() {
        loadAndEnableLoaderProvidersUsingBuiltInLoaders()
    }

    // Phase #3
    internal fun loadEnableHighPriorityExtensionPlugins(session: PluginLoadSession): Int {
        loadersLock.withLock {
            session.allKindsOfPlugins.flatMap { it.second }
                .filter { it.kind == PluginKind.HIGH_PRIORITY_EXTENSIONS }
                .sortByDependencies()
                .also { it.loadAndEnableAllInOrder() }
                .let { return it.size }
        }
    }

    // Phase #4
    internal fun loadEnableNormalPlugins(session: PluginLoadSession): Int {
        loadersLock.withLock {
            session.allKindsOfPlugins.flatMap { it.second }
                .filter { it.kind == PluginKind.NORMAL }
                .sortByDependencies()
                .also { it.loadAndEnableAllInOrder() }
                .let { return it.size }
        }
    }

    // Phase #1
    internal fun loadPluginLoaderProvidedByPlugins() {
        loadersLock.withLock {
            PluginLoaderProvider.useExtensions { ext, plugin ->
                logger.info { "Loaded PluginLoader ${ext.instance} from ${plugin.name}" }
                _pluginLoaders.add(ext.instance)
            }
        }
    }


    private fun List<PluginDescriptionWithLoader>.loadAndEnableAllInOrder() {
        this.forEach { (loader, _, plugin) ->
            loader.loadPluginNoEnable(plugin)
        }
        this.forEach { (loader, _, plugin) ->
            loader.enablePlugin(plugin)
        }
    }

    @kotlin.jvm.Throws(PluginLoadException::class)
    internal fun checkPluginDescription(description: PluginDescription) {
        when (description.name.toLowerCase()) {
            "main", "console", "plugin", "config", "data" -> throw PluginLoadException("Plugin name '${description.name}' is forbidden.")
        }
    }

    /**
     * @return [builtInLoaders] 可以加载的插件. 已经完成了 [PluginLoader.load], 但没有 [PluginLoader.enable]
     */
    @Suppress("UNCHECKED_CAST")
    @Throws(PluginMissingDependencyException::class)
    private fun loadAndEnableLoaderProvidersUsingBuiltInLoaders(): List<PluginDescriptionWithLoader> {
        val allDescriptions =
            builtInLoaders.listAllPlugins()
                .asSequence()
                .onEach { (loader, descriptions) ->
                    loader as PluginLoader<Plugin, PluginDescription>

                    descriptions.forEach(PluginManagerImpl::checkPluginDescription)
                    descriptions.filter { it.kind == PluginKind.LOADER }.sortByDependencies().loadAndEnableAllInOrder()
                }
                .flatMap { it.second.asSequence() }

        return allDescriptions.toList()
    }

    private fun List<PluginLoader<*, *>>.listAllPlugins(): List<Pair<PluginLoader<*, *>, List<PluginDescriptionWithLoader>>> {
        return associateWith { loader ->
            loader.listPlugins().map { plugin -> plugin.description.wrapWith(loader, plugin) }
        }.toList()
    }

    @Throws(PluginMissingDependencyException::class)
    private fun <D : PluginDescription> List<D>.sortByDependencies(): List<D> {
        val resolved = ArrayList<D>(this.size)

        fun D.canBeLoad(): Boolean = this.dependencies.all { it.isOptional || it in resolved }

        fun List<D>.consumeLoadable(): List<D> {
            val (canBeLoad, cannotBeLoad) = this.partition { it.canBeLoad() }
            resolved.addAll(canBeLoad)
            return cannotBeLoad
        }

        fun List<PluginDependency>.filterIsMissing(): List<PluginDependency> =
            this.filterNot { it.isOptional || it in resolved }

        tailrec fun List<D>.doSort() {
            if (this.isEmpty()) return

            val beforeSize = this.size
            this.consumeLoadable().also { resultPlugins ->
                check(resultPlugins.size < beforeSize) {
                    throw PluginMissingDependencyException(resultPlugins.joinToString("\n") { badPlugin ->
                        "Cannot load plugin ${badPlugin.name}, missing dependencies: ${
                            badPlugin.dependencies.filterIsMissing()
                                .joinToString()
                        }"
                    })
                }
            }.doSort()
        }

        this.doSort()
        return resolved
    }

    // endregion
}

internal data class PluginDescriptionWithLoader(
    @JvmField val loader: PluginLoader<Plugin, PluginDescription>, // easier type
    @JvmField val delegate: PluginDescription,
    @JvmField val plugin: Plugin
) : PluginDescription by delegate

@Suppress("UNCHECKED_CAST")
internal fun <D : PluginDescription> PluginDescription.unwrap(): D =
    if (this is PluginDescriptionWithLoader) this.delegate as D else this as D

@Suppress("UNCHECKED_CAST")
internal fun PluginDescription.wrapWith(loader: PluginLoader<*, *>, plugin: Plugin): PluginDescriptionWithLoader =
    PluginDescriptionWithLoader(
        loader as PluginLoader<Plugin, PluginDescription>, this, plugin
    )

internal operator fun List<PluginDescription>.contains(dependency: PluginDependency): Boolean =
    any { it.name == dependency.name }
