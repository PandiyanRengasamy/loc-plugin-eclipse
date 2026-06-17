package com.cts.plugin.eclipse.loc.startup;

import com.cts.plugin.eclipse.loc.EclipseLocPlugin;
import org.eclipse.ui.IStartup;

/**
 * EclipseLocStartup — called by Eclipse early startup extension point.
 * Ensures the plugin activator runs even if no preference page is opened.
 */
public class EclipseLocStartup implements IStartup {
    @Override
    public void earlyStartup() {
        // Accessing getInstance() forces the bundle activator to run (if not already active).
        EclipseLocPlugin plugin = EclipseLocPlugin.getInstance();
        if (plugin != null) {
            // Listeners were already registered in EclipseLocPlugin.start()
        }
    }
}

