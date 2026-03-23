package org.example;

import org.example.tools.DocumentRetrievalTool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.util.Map;

public class ManualRAGTest {

    @Test
    public void testRAGFlow() {
        // Filter logic: Skip if language-specific filters are active
        String itFilter = System.getProperty("test.id.it");
        String enFilter = System.getProperty("test.id.en");
        String generalFilter = System.getProperty("test.id"); // Helper for IT usually

        boolean languageFilterActive = (itFilter != null && !itFilter.equals("null") && !itFilter.isEmpty()) ||
                (enFilter != null && !enFilter.equals("null") && !enFilter.isEmpty()) ||
                (generalFilter != null && !generalFilter.equals("null") && !generalFilter.isEmpty());

        String ragFilter = System.getProperty("test.rag");
        boolean ragFilterActive = ragFilter != null && !ragFilter.equals("null") && !ragFilter.isEmpty();

        // If language filters are active AND rag filter is NOT active, skip.
        if (languageFilterActive && !ragFilterActive) {
            System.out.println(">>> SKIPPING ManualRAGTest (Language filter active)");
            Assumptions.assumeFalse(true, "Skipping ManualRAGTest because language filter is active");
        }

        System.out.println(">>> STARTING MANUAL RAG TEST <<<\n");

        org.example.rag.HybridRetriever retriever = new org.example.rag.HybridRetriever();
        retriever.initialize();
        DocumentRetrievalTool tool = new DocumentRetrievalTool(retriever);

        // Query that should trigger results
        String query = "authentication error 401";

        System.out.println("Invoking DocumentRetrievalTool with query: " + query);
        String result = tool.execute(Map.of("query", query));

        System.out.println("\n>>> TOOL OUTPUT <<<");
        System.out.println(result);
        System.out.println(">>> END MANUAL RAG TEST <<<");
    }
}
