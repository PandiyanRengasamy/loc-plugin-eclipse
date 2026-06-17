package com.cts.plugin.eclipse.loc.settings;

import com.cts.plugin.eclipse.loc.EclipseLocPlugin;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * GenAiLocSettings — wraps Eclipse IPreferenceStore.
 *
 * All settings are persisted automatically by Eclipse in the workspace .metadata.
 * Default values match the IntelliJ plugin for backend compatibility.
 *
 * Settings available:
 *   - backendUrl        REST endpoint (default: http://localhost:8080/genai-loc/events)
 *   - developerId       Employee / user ID
 *   - developerName     Display name
 *   - projectId         Project name / code
 *   - sprintId          Sprint identifier
 *   - enabled           Master on/off switch
 *   - flushIntervalSec  How often to auto-flush the queue (default: 30s)
 *   - batchSize         Queue size that triggers immediate flush (default: 5)
 */
public class GenAiLocSettings {

    // ── Preference keys ───────────────────────────────────────────────────────
    public static final String KEY_BACKEND_URL     = "genai.loc.backendUrl";
    public static final String KEY_DEVELOPER_ID    = "genai.loc.developerId";
    public static final String KEY_DEVELOPER_NAME  = "genai.loc.developerName";
    public static final String KEY_PROJECT_ID      = "genai.loc.projectId";
    public static final String KEY_SPRINT_ID       = "genai.loc.sprintId";
    public static final String KEY_ENABLED         = "genai.loc.enabled";
    public static final String KEY_FLUSH_INTERVAL  = "genai.loc.flushIntervalSec";
    public static final String KEY_BATCH_SIZE      = "genai.loc.batchSize";

    // ── Defaults ──────────────────────────────────────────────────────────────
    public static final String  DEFAULT_BACKEND_URL    = "http://localhost:8080/genai-loc/events";
    public static final String  DEFAULT_DEVELOPER_ID   = System.getProperty("user.name", "unknown");
    public static final boolean DEFAULT_ENABLED        = true;
    public static final int     DEFAULT_FLUSH_INTERVAL = 30;
    public static final int     DEFAULT_BATCH_SIZE     = 5;

    private static GenAiLocSettings INSTANCE;
    private IPreferenceStore store;

    private GenAiLocSettings(IPreferenceStore store) {
        this.store = store;
    }

    public static void init(IPreferenceStore store) {
        // Set defaults
        store.setDefault(KEY_BACKEND_URL,    DEFAULT_BACKEND_URL);
        store.setDefault(KEY_DEVELOPER_ID,   DEFAULT_DEVELOPER_ID);
        store.setDefault(KEY_DEVELOPER_NAME, DEFAULT_DEVELOPER_ID);
        store.setDefault(KEY_PROJECT_ID,     "");
        store.setDefault(KEY_SPRINT_ID,      "");
        store.setDefault(KEY_ENABLED,        DEFAULT_ENABLED);
        store.setDefault(KEY_FLUSH_INTERVAL, DEFAULT_FLUSH_INTERVAL);
        store.setDefault(KEY_BATCH_SIZE,     DEFAULT_BATCH_SIZE);
        INSTANCE = new GenAiLocSettings(store);
    }

    public static GenAiLocSettings getInstance() {
        if (INSTANCE == null) {
            // Fallback when accessed before init (e.g. in tests)
            IPreferenceStore s = EclipseLocPlugin.getInstance() != null
                    ? EclipseLocPlugin.getInstance().getPreferenceStore()
                    : null;
            if (s != null) init(s);
            else throw new IllegalStateException("GenAiLocSettings not initialised");
        }
        return INSTANCE;
    }

    public String  getBackendUrl()         { return store.getString(KEY_BACKEND_URL); }
    public String  getDeveloperId()        { return store.getString(KEY_DEVELOPER_ID); }
    public String  getDeveloperName()      { return store.getString(KEY_DEVELOPER_NAME); }
    public String  getProjectId()          { return store.getString(KEY_PROJECT_ID); }
    public String  getSprintId()           { return store.getString(KEY_SPRINT_ID); }
    public boolean isEnabled()             { return store.getBoolean(KEY_ENABLED); }
    public int     getFlushIntervalSeconds(){ return store.getInt(KEY_FLUSH_INTERVAL); }
    public int     getBatchSize()          { return store.getInt(KEY_BATCH_SIZE); }

    public void setBackendUrl(String v)    { store.setValue(KEY_BACKEND_URL, v); }
    public void setDeveloperId(String v)   { store.setValue(KEY_DEVELOPER_ID, v); }
    public void setDeveloperName(String v) { store.setValue(KEY_DEVELOPER_NAME, v); }
    public void setProjectId(String v)     { store.setValue(KEY_PROJECT_ID, v); }
    public void setSprintId(String v)      { store.setValue(KEY_SPRINT_ID, v); }
    public void setEnabled(boolean v)      { store.setValue(KEY_ENABLED, v); }
}

