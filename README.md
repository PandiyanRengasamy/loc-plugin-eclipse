# Eclipse GenAI LOC Tracker

Eclipse plugin that detects when GitHub Copilot Chat generates code and sends
Lines-of-Code metrics (added / modified / deleted) to the same Spring Boot REST
service used by the IntelliJ plugin (`intellij-plugin-loc-service`).

Ported from the IntelliJ plugin `intellij-plugin-claude-ij`. Same JSON contract,
same backend, same MongoDB collection.

---

## Project structure

```
eclise-loc-plugin-claude-export/
├── pom.xml                                 Tycho/Maven build descriptor
├── META-INF/MANIFEST.MF                    OSGi bundle metadata
├── plugin.xml                              Eclipse extension points
├── build.properties                        PDE build descriptor
├── .project / .classpath                   Eclipse IDE descriptors
├── icons/                                  Plugin icons
└── src/main/java/com/cts/plugin/eclipse/loc/
    ├── EclipseLocPlugin.java               Bundle activator (start/stop)
    ├── action/
    │   ├── SendNowHandler.java             Menu: Send LOC Data Now
    │   └── TestBackendHandler.java         Menu: Test Backend Connection
    ├── listeners/
    │   ├── CopilotKeepAllMouseListener.java   AWT mouse listener (Accept/Keep)
    │   └── EclipseDocumentPartListener.java   IDocumentListener for PRE snapshot
    ├── model/
    │   ├── LOCRequestPayload.java          REST payload (matches backend)
    │   └── FileChange.java                 Per-file LOC breakdown
    ├── service/
    │   └── EventDispatcher.java            HTTP POST queue + retry
    ├── settings/
    │   ├── GenAiLocSettings.java           IPreferenceStore wrapper
    │   └── GenAiLocPreferencePage.java     Window → Preferences page
    └── startup/
        └── EclipseLocStartup.java          IStartup early activation
```

---

## How it works

### Root cause (same as IntelliJ plugin)
GitHub Copilot Chat applies its generated code to the editor as a **live preview**
BEFORE the user clicks "Accept" / "Keep All". This means PRE == POST at click
time → diff = 0.

### Solution
| Step | What happens |
|------|-------------|
| Editor opened | `EclipseDocumentPartListener` attaches `IDocumentListener` to the document |
| Copilot writes preview | `documentAboutToBeChanged()` fires → PRE `{lines, chars}` stored in `PRE_STATE_CACHE` |
| User clicks Accept/Keep All | `MOUSE_PRESSED` arms the listener |
| 800 ms later | POST snapshot taken, diff computed vs `PRE_STATE_CACHE` |
| Diff > 0 | `LOCRequestPayload` POSTed to REST service |

---

## Build

This project uses **Tycho** (Maven-based Eclipse plugin build). No Gradle, no
manual JAR downloads — Tycho resolves Eclipse Platform 2024-12 and `com.google.gson`
automatically from the eclipse.org P2 repository.

```bash
mvn clean verify
```

Output: `target/com.cts.plugin.eclipse.loc-1.0.0-SNAPSHOT.jar`

To install: copy the JAR into Eclipse's `dropins/` folder and restart Eclipse.

### Target Eclipse / Java
- Eclipse 2024-12 or later (configurable via `<eclipse.release>` in `pom.xml`)
- JavaSE-17 (BREE in MANIFEST.MF and compiler in pom.xml)

---

## Develop in Eclipse IDE

1. File → Import → Existing Maven Projects → select this folder
2. Open the *Plug-in Development* perspective
3. Right-click the project → Run As → Eclipse Application — launches a second
   Eclipse instance with the plugin loaded.

---

## Configure

Window → Preferences → GenAI LOC Tracker:

| Setting | Description | Default |
|---------|-------------|---------|
| Backend URL | REST endpoint | `http://localhost:8080/genai-loc/events` |
| Developer ID | Your employee ID | OS username |
| Developer Name | Display name | (developer ID) |
| Project ID | Project code | (blank) |
| Sprint ID | Current sprint | (blank) |
| Enable | Master on/off | enabled |
| Flush Interval | Auto-flush seconds | 30 |
| Batch Size | Events before immediate flush | 5 |

---

## Use

- **Trigger LOC capture**: open a Java file, ask Copilot Chat to add a method,
  click **Accept** / **Keep All**. The plugin batches the diff and POSTs it.
- **Force flush**: Menu → GenAI LOC → Send LOC Data Now
- **Verify backend**: Menu → GenAI LOC → Test Backend Connection (sends a
  synthetic event)
- **Check logs**: Window → Show View → Error Log, filter on `GenAI-LOC`

---

## Scope of this port

This port covers **core capture and dispatch** only. The following IntelliJ
features were intentionally left out:

| IntelliJ feature | Reason omitted |
|---|---|
| LOC Report tool window (Swing panel with table/filters) | Out of scope — UI rewrite from Swing to SWT/JFace is substantial |
| CSV fallback when backend is offline | Out of scope — single-source-of-truth REST only |
| IntelliJ-specific Copilot detection (`AnActionListener`, JCEF click listener) | Replaced with Eclipse-native AWT mouse + `IDocumentListener` approach in `CopilotKeepAllMouseListener` |
| `GenAiToolDetector` plugin auto-detection | Eclipse plugin metadata is not introspected — `genAiTool` is set to `"COPILOT"` by default |

---

## JSON sent to service

Identical to the IntelliJ plugin — same `LocEventController` accepts both:

```json
{
  "developerId": "752004",
  "developerName": "Pandiyan Rengasamy",
  "projectId": "BaaC360",
  "sprintId": "Sprint 1",
  "filePath": "C:/workspace/src/MyClass.java",
  "fileName": "MyClass.java",
  "className": "MyClass",
  "ideType": "ECLIPSE",
  "genAiTool": "COPILOT",
  "linesAdded": 8,
  "linesModified": 2,
  "linesDeleted": 0,
  "genAiGenerated": true,
  "genAiConfidenceScore": 0.95,
  "fileChanges": [
    {
      "filePath": "C:/workspace/src/MyClass.java",
      "fileName": "MyClass.java",
      "className": "MyClass",
      "linesAdded": 8,
      "linesModified": 2,
      "linesDeleted": 0
    }
  ]
}
```