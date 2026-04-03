package org.example.rag;

import java.util.*;
import java.util.Set;
import jakarta.persistence.EntityManagerFactory;

/**
 * Hybrid retriever that orchestrates the full RAG pipeline:
 * 1. BM25 search (lexical)
 * 2. Vector search (semantic)
 * 3. RRF fusion
 * 4. Reranking
 */
public class HybridRetriever {

    private final DocumentChunker chunker;
    private final BM25Index bm25Index;
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final RRFMerger rrfMerger;

    private boolean initialized = false;

    // Configuration
    private static final int BM25_TOP_K = 50;
    private static final int VECTOR_TOP_K = 50;
    private static final int RRF_TOP_K = 20;
    private static final int FINAL_TOP_K = 5;

    /**
     * Create a new hybrid retriever with all components.
     */
    public HybridRetriever(EntityManagerFactory emf) {
        this.chunker = new DocumentChunker();
        this.bm25Index = new BM25Index();
        this.vectorStore = new VectorStore(emf);
        this.embeddingService = new EmbeddingService();
        this.rrfMerger = new RRFMerger();
    }

    /**
     * Initialize the retriever by loading and indexing all documents.
     */
    public synchronized void initialize() {
        if (initialized)
            return;

        // System.out.println("[HybridRetriever] Initializing...");
        // long startTime = System.currentTimeMillis();

        // Load and chunk all documents
        List<Chunk> chunks = chunker.loadAllChunks();
        // System.out.println("[HybridRetriever] Loaded " + chunks.size() + " chunks");

        // Index in BM25
        bm25Index.addChunks(chunks);
        // System.out.println("[HybridRetriever] BM25 index: " +
        // bm25Index.getDocumentCount() + " docs");

        // Create embeddings only for chunks NOT already in the vector DB
        Set<String> existingIds = vectorStore.getExistingIds();
        int skipped = 0;
        int indexed = 0;

        List<Chunk> newChunks = chunks.stream()
                .filter(c -> !existingIds.contains(c.id()))
                .toList();

        if (!newChunks.isEmpty()) {
            embeddingService.initialize();
            for (Chunk chunk : newChunks) {
                float[] embedding = embeddingService.embed(chunk.content(), false);
                vectorStore.addChunk(chunk, embedding);
                indexed++;
            }
        }
        skipped = chunks.size() - indexed;

        System.out.println("[RAG] " + indexed + " new chunks indexed, " + skipped + " already in DB (skipped embedding).");

        initialized = true;
        // long duration = System.currentTimeMillis() - startTime;
        // System.out.println("[HybridRetriever] Ready (took " + duration + "ms)");
    }

    /**
     * Retrieve relevant chunks for a query.
     * 
     * @param query The search query
     * @param topK  Number of results to return
     * @return List of relevant chunks with scores
     */
    public List<ScoredChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    private final Map<String, List<ScoredChunk>> retrievalCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Retrieve relevant chunks with optional source filter.
     * 
     * @param query        The search query
     * @param topK         Number of results to return
     * @param sourceFilter Optional source file filter (e.g., "billing_policy.md")
     * @return List of relevant chunks with scores
     */
    public List<ScoredChunk> retrieve(String query, int topK, String sourceFilter) {
        if (!initialized)
            initialize();

        // Check cache
        String cacheKey = query + "|" + topK + "|" + (sourceFilter != null ? sourceFilter : "NULL");
        if (retrievalCache.containsKey(cacheKey)) {
            // System.out.println("[HybridRetriever] Returning cached result for: " +
            // query);
            return retrievalCache.get(cacheKey);
        }

        // System.out.println("\n[HybridRetriever] Processing query: \"" + query + "\"
        // (Filter: " + sourceFilter + ")");

        // 1. BM25 search
        List<ScoredChunk> bm25Results = sourceFilter != null
                ? bm25Index.search(query, BM25_TOP_K, sourceFilter)
                : bm25Index.search(query, BM25_TOP_K);
        // System.out.println("[HybridRetriever] BM25 found " + bm25Results.size() + "
        // results");
        // if (!bm25Results.isEmpty()) {
        // System.out.println(" Top BM25: " + bm25Results.get(0).source() + " (Score: "
        // + String.format("%.2f", bm25Results.get(0).score()) + ")");
        // }

        // 2. Vector search
        // System.out.println("[HybridRetriever] Generating query embedding...");
        float[] queryEmbedding = embeddingService.embed(query, true);
        List<ScoredChunk> vectorResults = sourceFilter != null
                ? vectorStore.search(queryEmbedding, VECTOR_TOP_K, sourceFilter)
                : vectorStore.search(queryEmbedding, VECTOR_TOP_K);
        // System.out.println("[HybridRetriever] Vector search found " +
        // vectorResults.size() + " results");
        // if (!vectorResults.isEmpty()) {
        // System.out.println(" Top Vector: " + vectorResults.get(0).source() + "
        // (Score: "
        // + String.format("%.4f", vectorResults.get(0).score()) + ")");
        // }

        // 3. RRF fusion
        List<ScoredChunk> merged = rrfMerger.merge(bm25Results, vectorResults, RRF_TOP_K);
        // System.out.println("[HybridRetriever] RRF Fusion produced " + merged.size() +
        // " candidates");

        // 4. Final Crop
        List<ScoredChunk> finalResults = merged.stream()
                .limit(Math.min(topK, FINAL_TOP_K))
                .toList();

        // System.out.println("[HybridRetriever] Final Top " + finalResults.size() + "
        // results:");
        // for (int i = 0; i < finalResults.size(); i++) {
        // ScoredChunk c = finalResults.get(i);
        // String snippet = c.content().length() > 100 ? c.content().substring(0,
        // 100).replace("\n", " ") + "..."
        // : c.content().replace("\n", " ");
        // System.out.println(" " + (i + 1) + ". [" + c.source() + "] (Score: " +
        // String.format("%.4f", c.score())
        // + ") " + snippet);
        // }

        retrievalCache.put(cacheKey, finalResults);
        return finalResults;
    }

    /**
     * Get retrieval statistics for debugging.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("bm25_docs", bm25Index.getDocumentCount());
        stats.put("vector_count", vectorStore.size());
        stats.put("initialized", initialized);
        return stats;
    }

    /**
     * Close all resources.
     */
    public void close() {
        bm25Index.close();
        embeddingService.close();

        // Reset state so it can be re-initialized if needed
        initialized = false;
        retrievalCache.clear();
    }
}
