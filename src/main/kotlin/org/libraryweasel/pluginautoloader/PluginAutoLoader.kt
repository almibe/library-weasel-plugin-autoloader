/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.libraryweasel.pluginautoloader

import org.libraryweasel.applicationpaths.api.StaticContentAccess
import org.libraryweasel.servo.Component
import org.libraryweasel.servo.Service
import org.osgi.framework.Bundle
import org.osgi.framework.BundleContext
import org.osgi.framework.Constants
import org.osgi.service.log.LogService
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Component(PluginAutoLoader::class)
class PluginAutoLoader () {
    @Service
    private lateinit var instanceFiles: StaticContentAccess
    @Service
    private lateinit var logger: LogService
    private lateinit var bundleContext: BundleContext

    private lateinit var rootPath: Path
    private val watchService = FileSystems.getDefault().newWatchService()
    private val pathToBundle = ConcurrentHashMap<String, Bundle>()
    private val isRunning = AtomicBoolean(false)

    fun stop() {
        isRunning.compareAndSet(true, false)
        watchService.close()
    }

    fun start() {
        logger.log(LogService.LOG_INFO, "Starting Plugin Auto Loader")
        rootPath = instanceFiles.getPath("/plugin/")
        val installedBundles = ArrayList<Bundle>()
        try {
            rootPath.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            val directoryContents = Files.list(rootPath)
            directoryContents.forEach { file ->
                if (Files.isRegularFile(file) && file.toString().toLowerCase().endsWith(".jar")) {
                    try {
                        //logger.info("Loading " + file.toString())
                        val bundle = bundleContext.installBundle("file:" + file.toFile().absolutePath)
                        installedBundles.add(bundle)
                        pathToBundle.put(file.toAbsolutePath().toString(), bundle)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }
            installedBundles.forEach { bundle ->
                if (bundle.headers.get(Constants.FRAGMENT_HOST) == null) {
                    try {
                        bundle.start()
                    } catch (ex: Exception) {
                        //do nothing
                    }

                }
            }
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }

        isRunning.compareAndSet(false, true)
        val backgroundTask = Runnable {
            while (true) {
                try {
                    if (isRunning.get()) {
                        val watchKey = watchService.take()
                        handlePluginEvent(watchKey)
                        val valid = watchKey.reset()
                        if (!valid) {
                            break
                        }
                    } else {
                        break
                    }
                } catch (ex: Exception) {
                    if (isRunning.get()) {
                        throw RuntimeException(ex)
                    } else {
                        break
                    }
                }
            }
        }
        val thread = Thread(backgroundTask)
        thread.start()
    }

    private fun handlePluginEvent(watchKey: WatchKey) {
        for (event in watchKey.pollEvents()) {
            val eventKind = event.kind()

            if (eventKind == OVERFLOW) { //TODO how should I handle this?
                return
            }

            val bundleFile = rootPath.resolve(event.context() as Path) //TODO check that file is a jar

            if (eventKind == ENTRY_CREATE) {
                addBundle(bundleFile)
            } else if (eventKind == ENTRY_MODIFY) {
                restartBundle(bundleFile)
            } else if (eventKind == ENTRY_DELETE) {
                removeBundle(bundleFile)
            }
        }
    }

    //TODO all three of these methods need revisited at some point
    private fun addBundle(bundleFile: Path) {
        logger.log(LogService.LOG_INFO, "Auto Adding Plugin: " + bundleFile.toString())
        try {
            val bundle = bundleContext.installBundle("file:" + bundleFile.toFile().absolutePath)
            pathToBundle.put(bundleFile.toAbsolutePath().toString(), bundle)
            bundle.start()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        restartFailedBundles()
    }

    private fun restartBundle(bundleFile: Path) {
        logger.log(LogService.LOG_INFO, "Auto Restarting Plugin: " + bundleFile.toString())
        //TODO there's probably a better way to do this
        removeBundle(bundleFile)
        addBundle(bundleFile)
    }

    private fun removeBundle(bundleFile: Path) {
        logger.log(LogService.LOG_INFO, "Auto Removing Plugin: " + bundleFile.toString())
        try {
            val bundle = pathToBundle.remove(bundleFile.toString())
            bundle?.stop()
            bundle?.uninstall()
        } catch (ex: Exception) {
            logger.log(LogService.LOG_ERROR, "Error removing bundle", ex)
        }
    }

    //TODO room for optimization here but it's not needed yet
    private fun restartFailedBundles() {
        val previousRun = HashSet<Bundle>()
        val thisRun = HashSet<Bundle>()
        do {
            previousRun.clear()
            previousRun.addAll(thisRun)
            thisRun.clear()
            pathToBundle.forEach { path, bundle ->
                if (bundle.state != Bundle.ACTIVE) {
                    try {
                        bundle.start()
                    } catch (ex: Exception) {
                        thisRun.add(bundle)
                    }

                }
            }
        } while (previousRun != thisRun)
    }
}
