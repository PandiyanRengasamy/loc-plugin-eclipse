package com.cts.plugin.eclipse.loc.model;

/**
 * FileChange — per-file LOC breakdown inside a single LOCRequestPayload.
 * Identical contract to the IntelliJ plugin's FileChange so the same
 * Spring Boot backend accepts both without modification.
 */
public class FileChange {

    private String filePath;
    private String fileName;
    private String className;
    private int    linesAdded;
    private int    linesModified;
    private int    linesDeleted;
    /** Estimated LLM input (prompt) tokens for this file's change. */
    private int    inputTokens;
    /** Estimated LLM output (completion) tokens for this file's change. */
    private int    outputTokens;

    public FileChange(String filePath, String fileName, String className,
                      int linesAdded, int linesModified, int linesDeleted) {
        this.filePath      = filePath;
        this.fileName      = fileName;
        this.className     = className;
        this.linesAdded    = linesAdded;
        this.linesModified = linesModified;
        this.linesDeleted  = linesDeleted;
    }

    public FileChange(String filePath, String fileName, String className,
                      int linesAdded, int linesModified, int linesDeleted,
                      int inputTokens, int outputTokens) {
        this(filePath, fileName, className, linesAdded, linesModified, linesDeleted);
        this.inputTokens  = inputTokens;
        this.outputTokens = outputTokens;
    }

    /** Convenience factory — derives className from fileName automatically. */
    public static FileChange of(String filePath, String fileName,
                                int linesAdded, int linesModified, int linesDeleted) {
        String cls = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        return new FileChange(filePath, fileName, cls, linesAdded, linesModified, linesDeleted);
    }

    public String getFilePath()      { return filePath; }
    public String getFileName()      { return fileName; }
    public String getClassName()     { return className; }
    public int    getLinesAdded()    { return linesAdded; }
    public int    getLinesModified() { return linesModified; }
    public int    getLinesDeleted()  { return linesDeleted; }
    public int    getInputTokens()   { return inputTokens; }
    public int    getOutputTokens()  { return outputTokens; }

    public void setInputTokens(int inputTokens)   { this.inputTokens = inputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

    @Override
    public String toString() {
        return "FileChange{file=" + fileName + " +=" + linesAdded
                + " ~=" + linesModified + " -=" + linesDeleted
                + " inTok=" + inputTokens + " outTok=" + outputTokens + "}";
    }
}

