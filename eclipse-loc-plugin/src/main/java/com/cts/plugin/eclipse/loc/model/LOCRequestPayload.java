package com.cts.plugin.eclipse.loc.model;

import java.util.ArrayList;
import java.util.List;

/**
 * LOCRequestPayload — mirrors the backend REST API contract exactly.
 * Same structure as the IntelliJ plugin's CodeEventRequest so the same
 * Spring Boot service accepts both IDE plugins without any change.
 *
 * JSON shape:
 * {
 *   "developerId"    : "dev-001",
 *   "projectId"      : "MyProject",
 *   ...
 *   "linesAdded"     : 35,
 *   "fileChanges"    : [ { "filePath":..., "linesAdded":... }, ... ]
 * }
 */
public class LOCRequestPayload {

    private String  developerId;
    private String  developerName;
    private String  projectId;
    private String  sprintId;
    private String  filePath;
    private String  fileName;
    private String  className;
    private String  ideType;
    private String  genAiTool;
    private String  developmentMode;
    private int     linesAdded;
    private int     linesModified;
    private int     linesDeleted;
    private boolean genAiGenerated;
    private Double  genAiConfidenceScore;
    private String  eventTimestamp;
    private String  sessionId;
    private List<FileChange> fileChanges;

    // ── Multi-file constructor ────────────────────────────────────────────────

    public LOCRequestPayload(
            String developerId, String developerName,
            String projectId, String sprintId,
            String filePath, String fileName, String className,
            String ideType, String genAiTool, String developmentMode,
            int linesAdded, int linesModified, int linesDeleted,
            boolean genAiGenerated, Double genAiConfidenceScore,
            String eventTimestamp, String sessionId,
            List<FileChange> fileChanges) {
        this.developerId          = developerId;
        this.developerName        = developerName;
        this.projectId            = projectId;
        this.sprintId             = sprintId;
        this.filePath             = filePath;
        this.fileName             = fileName;
        this.className            = className;
        this.ideType              = ideType;
        this.genAiTool            = genAiTool;
        this.developmentMode      = developmentMode;
        this.linesAdded           = linesAdded;
        this.linesModified        = linesModified;
        this.linesDeleted         = linesDeleted;
        this.genAiGenerated       = genAiGenerated;
        this.genAiConfidenceScore = genAiConfidenceScore;
        this.eventTimestamp       = eventTimestamp;
        this.sessionId            = sessionId;
        this.fileChanges          = fileChanges != null ? fileChanges : new ArrayList<>();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getDeveloperId()          { return developerId; }
    public String  getDeveloperName()        { return developerName; }
    public String  getProjectId()            { return projectId; }
    public String  getSprintId()             { return sprintId; }
    public String  getFilePath()             { return filePath; }
    public String  getFileName()             { return fileName; }
    public String  getClassName()            { return className; }
    public String  getIdeType()              { return ideType; }
    public String  getGenAiTool()            { return genAiTool; }
    public String  getDevelopmentMode()      { return developmentMode; }
    public int     getLinesAdded()           { return linesAdded; }
    public int     getLinesModified()        { return linesModified; }
    public int     getLinesDeleted()         { return linesDeleted; }
    public boolean isGenAiGenerated()        { return genAiGenerated; }
    public Double  getGenAiConfidenceScore() { return genAiConfidenceScore; }
    public String  getEventTimestamp()       { return eventTimestamp; }
    public String  getSessionId()            { return sessionId; }
    public List<FileChange> getFileChanges() { return fileChanges; }

    public LOCRequestPayload addFileChange(FileChange fc) {
        this.fileChanges.add(fc);
        this.linesAdded    += fc.getLinesAdded();
        this.linesModified += fc.getLinesModified();
        this.linesDeleted  += fc.getLinesDeleted();
        return this;
    }

    @Override
    public String toString() {
        return "LOCRequestPayload{dev=" + developerId + " project=" + projectId
                + " file=" + fileName + " tool=" + genAiTool
                + " +=" + linesAdded + " ~=" + linesModified + " -=" + linesDeleted + "}";
    }
}

