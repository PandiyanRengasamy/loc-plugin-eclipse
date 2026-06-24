package com.cts.plugin.eclipse.loc.util;

/**
 * TokenEstimator — estimates LLM input (prompt) and output (completion) tokens
 * for a GenAI code-generation event.
 *
 * <p>The Eclipse plugin does not have direct access to the LLM provider's token
 * usage API, so these values are <strong>estimates</strong> derived from the
 * code that was changed and the file context the model worked against. The
 * approximation uses the common GPT rule of thumb of ~4 characters per token
 * for English text and source code.</p>
 *
 * <ul>
 *   <li><b>Input tokens</b>  ≈ (context characters the model saw before the
 *       change) / 4, plus a fixed instruction-prompt overhead.</li>
 *   <li><b>Output tokens</b> ≈ (characters of code the model produced) / 4,
 *       where the produced volume is the larger of the net character delta or
 *       the added/modified line volume.</li>
 * </ul>
 */
public final class TokenEstimator {

    /** Approx characters per token (OpenAI/GPT rule of thumb for English &amp; code). */
    public static final double CHARS_PER_TOKEN = 4.0;

    /** Default characters-per-line when the real average cannot be computed. */
    public static final double DEFAULT_CHARS_PER_LINE = 40.0;

    /**
     * Fixed token overhead representing the system / instruction prompt that
     * always accompanies a code-generation request.
     */
    public static final int PROMPT_OVERHEAD_TOKENS = 20;

    private TokenEstimator() { }

    /** Estimate tokens from a raw character count (≥ 0). */
    public static int estimateTokensFromChars(int charCount) {
        if (charCount <= 0) {
            return 0;
        }
        return (int) Math.ceil(charCount / CHARS_PER_TOKEN);
    }

    /** Estimate tokens for a block of text. */
    public static int estimateTokensFromText(String text) {
        return text == null ? 0 : estimateTokensFromChars(text.length());
    }

    /**
     * Estimate input (prompt) tokens from the file context the model saw before
     * generating, plus a fixed instruction-prompt overhead.
     *
     * @param contextChars number of characters in the file before the change
     * @return estimated input tokens (always ≥ {@link #PROMPT_OVERHEAD_TOKENS})
     */
    public static int estimateInputTokens(int contextChars) {
        return estimateTokensFromChars(Math.max(0, contextChars)) + PROMPT_OVERHEAD_TOKENS;
    }

    /**
     * Estimate output (completion) tokens from the amount of code the model
     * produced — the larger of the net character delta or the added/modified
     * line volume.
     *
     * @param addedLines      lines added by the AI
     * @param modifiedLines   lines modified by the AI
     * @param charDelta       net character delta (post - pre)
     * @param avgCharsPerLine average characters per line in the file
     * @return estimated output tokens
     */
    public static int estimateOutputTokens(int addedLines, int modifiedLines,
                                           int charDelta, double avgCharsPerLine) {
        double cpl = avgCharsPerLine > 0 ? avgCharsPerLine : DEFAULT_CHARS_PER_LINE;
        int byChars = Math.abs(charDelta);
        int byLines = (int) Math.round(Math.max(0, addedLines + modifiedLines) * cpl);
        int generatedChars = Math.max(byChars, byLines);
        return estimateTokensFromChars(generatedChars);
    }
}

