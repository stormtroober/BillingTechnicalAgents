package org.example;

import io.github.cdimascio.dotenv.Dotenv;
import org.example.agent.CoordinatorAgent;
import org.example.llm.GeminiClient;
import org.example.llm.LLMClient;
import org.example.model.ConversationContext;
import org.example.model.ConversationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Multi-Agent System Test - Unified Multilingual Test Runner
 * 
 * Loads and runs test cases from all language-specific test sets.
 * Uses JUnit 5 Tags for filtering by language and dynamic filtering for
 * chapters/IDs.
 * 
 * Usage Examples:
 * 
 * All agent tests (all languages):
 * ./gradlew :app:test --tests "org.example.MultiAgentSystemTest"
 * 
 * Only Italian tests:
 * ./gradlew :app:test --tests "org.example.MultiAgentSystemTest" -Dtest.lang=it
 * 
 * Only English tests:
 * ./gradlew :app:test --tests "org.example.MultiAgentSystemTest" -Dtest.lang=en
 * 
 * Only Polish tests:
 * ./gradlew :app:test --tests "org.example.MultiAgentSystemTest" -Dtest.lang=pl
 * 
 * Specific chapter (e.g., 4.x):
 * ./gradlew :app:test --tests "org.example.MultiAgentSystemTest"
 * -Dtest.chapter=4
 * 
 * Specific chapter in a specific language:
 * ./gradlew :app:test --tests "org.example.MultiAgentSystemTest" -Dtest.lang=it
 * -Dtest.chapter=4
 * 
 * Single test by ID:
 * ./gradlew :app:test --tests "org.example.MultiAgentSystemTest" -Dtest.id=1.1
 * 
 * Single test by ID and language:
 * ./gradlew :app:test --tests "org.example.MultiAgentSystemTest" -Dtest.lang=en
 * -Dtest.id=1.1
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("agent")
public class MultiAgentSystemTest {

    private static final Map<String, String> TEST_SET_PATHS = Map.of(
            "it", "../TEST_SET_IT.md",
            "en", "../TEST_SET_EN.md",
            "pl", "../TEST_SET_PL.md");

    private LLMClient llmClient;
    private org.example.rag.HybridRetriever retriever;
    private CoordinatorAgent agent;
    private Map<String, List<TestCase>> testCasesByLanguage;

    @BeforeAll
    public void setup() throws IOException {
        // Load environment variables
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String apiKey = dotenv.get("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            dotenv = Dotenv.configure().directory("..").ignoreIfMissing().load();
            apiKey = dotenv.get("GEMINI_API_KEY");
        }

        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("GEMINI_API_KEY");
        }

        if (apiKey == null) {
            throw new IllegalStateException("GEMINI_API_KEY not found. Tests cannot run.");
        }

        llmClient = new GeminiClient(apiKey);
        retriever = new org.example.rag.HybridRetriever();
        retriever.initialize();
        agent = new CoordinatorAgent(llmClient, retriever);

        // Load all test sets
        testCasesByLanguage = new HashMap<>();
        for (Map.Entry<String, String> entry : TEST_SET_PATHS.entrySet()) {
            String lang = entry.getKey();
            Path path = Paths.get(entry.getValue());
            if (Files.exists(path)) {
                testCasesByLanguage.put(lang, parseTestCases(path));
                System.out.println(">>> Loaded " + testCasesByLanguage.get(lang).size() + " test cases for language: "
                        + lang.toUpperCase());
            } else {
                System.out.println(">>> WARNING: Test set not found for language " + lang + ": " + path);
            }
        }
    }

    @org.junit.jupiter.api.AfterAll
    public void teardown() {
        if (retriever != null) {
            retriever.close();
        }
    }

    /**
     * Generates individual test cases from all language test sets.
     * Filtering is controlled via system properties:
     * - test.lang: Filter by language (it, en)
     * - test.chapter: Filter by chapter prefix (1, 2, 3, 4, 5)
     * - test.id: Filter by exact test ID (1.1, 2.3, etc.)
     */
    @TestFactory
    public Collection<DynamicTest> generateTests() {
        List<DynamicTest> dynamicTests = new ArrayList<>();

        // Get filter properties
        String filterLang = System.getProperty("test.lang");
        String filterChapter = System.getProperty("test.chapter");
        String filterTestId = System.getProperty("test.id");

        System.out.println(">>> Filter: lang=" + filterLang + ", chapter=" + filterChapter + ", id=" + filterTestId);

        for (Map.Entry<String, List<TestCase>> langEntry : testCasesByLanguage.entrySet()) {
            String lang = langEntry.getKey();
            List<TestCase> testCases = langEntry.getValue();

            // Apply language filter
            if (isFilterSet(filterLang) && !filterLang.equalsIgnoreCase(lang)) {
                System.out.println(">>> Skipping language " + lang.toUpperCase() + " (filter: " + filterLang + ")");
                continue;
            }

            for (TestCase testCase : testCases) {
                // Apply chapter filter
                if (isFilterSet(filterChapter)) {
                    if (!testCase.id.startsWith(filterChapter + ".") && !testCase.id.equals(filterChapter)) {
                        continue;
                    }
                }

                // Apply test ID filter
                if (isFilterSet(filterTestId)) {
                    boolean matches = testCase.id.equals(filterTestId) || testCase.id.startsWith(filterTestId + ".");
                    if (!matches) {
                        continue;
                    }
                }

                // Create test name: [IT 1.1] Title or [EN 1.1] Title
                String testName = "[" + lang.toUpperCase() + " " + testCase.id + "] " + testCase.title;
                String testLang = lang; // capture for lambda

                dynamicTests.add(dynamicTest(testName, () -> runSingleTest(testCase, testLang)));
            }
        }

        System.out.println(">>> Generated " + dynamicTests.size() + " dynamic tests");
        return dynamicTests;
    }

    private boolean isFilterSet(String filter) {
        return filter != null && !filter.equals("null") && !filter.isEmpty();
    }

    private void runSingleTest(TestCase testCase, String lang) {
        System.out.println(
                ">>> [" + lang.toUpperCase() + "] STARTING TEST CASE: " + testCase.id + " - " + testCase.title);
        ConversationContext context = new ConversationContext();

        int turnIndex = 1;
        for (Turn turn : testCase.turns) {
            System.out.println("   [Turn " + turnIndex + "] Input: " + turn.input);
            long start = System.currentTimeMillis();
            String agentResponse = agent.process(turn.input, context);
            long duration = System.currentTimeMillis() - start;
            System.out.println("   [Turn " + turnIndex + "] Agent responded in " + duration + "ms");
            System.out.println("   [Turn " + turnIndex + "] Response: "
                    + agentResponse.replace("\n", "\n                    "));
            System.out.println("   [Turn " + turnIndex + "] Expected: " + turn.expected);

            // Verify with LLM Judge
            VerificationResult result = verifyWithJudge(testCase, turn, agentResponse, lang);

            if (!result.passed) {
                System.out.println("   [Turn " + turnIndex + "] ❌ FAILED verification: " + result.reason);
                fail("Test " + testCase.id + " failed at Turn " + turnIndex + ": " + result.reason);
            } else {
                System.out.println("   [Turn " + turnIndex + "] ✅ PASSED verification");
            }
            turnIndex++;
        }
        System.out.println(">>> [" + lang.toUpperCase() + "] TEST CASE " + testCase.id + " PASSED ✅");
    }

    private VerificationResult verifyWithJudge(TestCase testCase, Turn turn, String actualResponse, String lang) {
        String systemInstruction = "You are an impartial Judge for an AI Agent testing system. Evaluate if the agent output satisfies the expected behavior.";

        String languageNote;
        switch (lang) {
            case "en":
                languageNote = "- The response should be in English since input was in English.";
                break;
            case "it":
                languageNote = "- If the expected says \"2-3 business days\" and the response says \"2-3 giorni lavorativi\" (Italian), that's a PASS.";
                break;
            case "pl":
                languageNote = "- If the expected says \"2-3 business days\" and the response says \"2-3 dni roboczych\" (Polish), that's a PASS.";
                break;
            default:
                languageNote = "- Accept semantically equivalent responses in any language.";
        }

        String userContent = String.format(
                """
                        Test Case: %s - %s
                        Language: %s
                        User Input: %s
                        Expected Behavior/Response:
                        %s

                        Actual Agent Response:
                        %s

                        Task:
                        Evaluate if the "Actual Agent Response" semantically satisfies the "Expected Behavior/Response".

                        IMPORTANT GUIDELINES:
                        - Focus on SEMANTIC correctness, not exact wording.
                        %s
                        - If the response contains the required information, even with additional helpful details, that's a PASS.
                        - Only FAIL if the response is missing the key expected information or provides incorrect information.
                        - Tool calls: If expected says "calls X tool" but response contains the correct info, assume tool was called (PASS).

                        Format your response strictly as:
                        STATUS: [PASS or FAIL]
                        REASON: [Brief explanation]
                        """,
                testCase.id, testCase.title, lang.toUpperCase(), turn.input, turn.expected, actualResponse,
                languageNote);

        try {
            String judgeResponse = llmClient.chat(systemInstruction, List.of(ConversationMessage.user(userContent)));
            boolean passed = judgeResponse.contains("STATUS: PASS");
            String reason = "Unknown";
            if (judgeResponse.contains("REASON:")) {
                reason = judgeResponse.substring(judgeResponse.indexOf("REASON:") + 7).trim();
            }
            return new VerificationResult(passed, reason);
        } catch (Exception e) {
            e.printStackTrace();
            return new VerificationResult(false, "Judge invocation failed: " + e.getMessage());
        }
    }

    // --- Parser Logic ---

    private List<TestCase> parseTestCases(Path path) throws IOException {
        String content = Files.readString(path);
        List<TestCase> cases = new ArrayList<>();

        Pattern testHeaderPattern = Pattern.compile("#### TEST (\\d+\\.\\d+) - (.+)");
        String[] lines = content.split("\\R");

        TestCase currentCase = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher m = testHeaderPattern.matcher(line);
            if (m.find()) {
                if (currentCase != null)
                    cases.add(currentCase);
                currentCase = new TestCase(m.group(1), m.group(2));
                continue;
            }

            if (currentCase != null) {
                // Standard block detection
                if (line.startsWith("**Input:**") || line.matches("\\*\\*Turn \\d+ Input:\\*\\*")) {
                    StringBuilder inputBuilder = new StringBuilder();
                    int j = i + 1;
                    boolean inCodeBlock = false;
                    while (j < lines.length) {
                        String l = lines[j].trim();
                        if (l.startsWith("```")) {
                            if (inCodeBlock) {
                                i = j;
                                break;
                            }
                            inCodeBlock = true;
                        } else if (inCodeBlock) {
                            inputBuilder.append(l).append("\n");
                        }
                        j++;
                    }
                    String input = inputBuilder.toString().trim();

                    String expected = "";
                    for (int k = i + 1; k < lines.length; k++) {
                        if (lines[k].trim().startsWith("#### TEST"))
                            break;
                        if (lines[k].trim().startsWith("**Expected")) {
                            String expLine = lines[k].trim();
                            int colonIdx = expLine.indexOf(":");
                            if (colonIdx != -1 && colonIdx < expLine.length() - 1) {
                                String sameLineContent = expLine.substring(colonIdx + 1).replace("**", "").trim();
                                if (!sameLineContent.isEmpty()) {
                                    expected = sameLineContent;
                                    break;
                                }
                            }

                            StringBuilder expBuilder = new StringBuilder();
                            for (int z = k + 1; z < lines.length; z++) {
                                String zLine = lines[z].trim();
                                if (zLine.startsWith("#### TEST") ||
                                        zLine.startsWith("**Turn") ||
                                        zLine.startsWith("---") ||
                                        zLine.startsWith("###") ||
                                        zLine.startsWith("**Input"))
                                    break;
                                if (!zLine.isEmpty())
                                    expBuilder.append(zLine).append("\n");
                            }
                            expected = expBuilder.toString().trim();
                            break;
                        }
                    }

                    if (!input.isEmpty()) {
                        currentCase.turns.add(new Turn(input, expected));
                    }
                }

                // Table detection (Category 5)
                if (line.startsWith("|") && line.contains("Turn") && line.contains("Input")) {
                    i++;
                    while (i + 1 < lines.length) {
                        String row = lines[++i].trim();
                        if (!row.startsWith("|"))
                            break;
                        String[] parts = row.split("\\|");
                        if (parts.length >= 4) {
                            String input = parts[2].trim().replace("\"", "");
                            String expected = parts[3].trim();
                            currentCase.turns.add(new Turn(input, expected));
                        }
                    }
                }
            }
        }
        if (currentCase != null)
            cases.add(currentCase);

        return cases;
    }

    // --- Helper Classes ---

    private static class TestCase {
        String id;
        String title;
        List<Turn> turns = new ArrayList<>();

        TestCase(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private static class Turn {
        String input;
        String expected;

        Turn(String input, String expected) {
            this.input = input;
            this.expected = expected;
        }
    }

    private static class VerificationResult {
        boolean passed;
        String reason;

        VerificationResult(boolean passed, String reason) {
            this.passed = passed;
            this.reason = reason;
        }
    }
}
