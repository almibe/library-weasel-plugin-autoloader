/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.libraryweasel.pluginautoloader

import org.apache.felix.dm.DependencyActivatorBase
import org.apache.felix.dm.DependencyManager
import org.libraryweasel.servo.LibraryWeaselComponentRegistrar
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext

class Activator : DependencyActivatorBase(), BundleActivator {
    override fun init(context: BundleContext, manager: DependencyManager) {
        val registrar = LibraryWeaselComponentRegistrar(manager)
     //   registrar.register(PluginAutoLoader::class.java)
    }

    override fun destroy(context: BundleContext, manager: DependencyManager) {

    }
}
