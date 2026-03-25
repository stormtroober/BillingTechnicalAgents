package org.example.rag;

import java.util.*;

/**
 * Reciprocal Rank Fusion (RRF) for merging results from multiple retrieval
 * methods.
 * 
 * RRF formula: score(d) = Σ 1/(k + rank_i(d))
 * where k is a constant (default 60) and rank_i is the rank of document d in
 * list i.
 * 
 */
public class RRFMerger {

    private final int k;

    /**
     * Create RRF merger with default k=60.
     */
    public RRFMerger() {
        this(60);
    }

    /**
     * Create RRF merger with custom k value.
     * Higher k reduces the impact of high rankings.
     */
    public RRFMerger(int k) {
        this.k = k;
    }

    /**
     * Merge two ranked lists using RRF.
     * 
     * @param list1 First ranked list (e.g., BM25 results)
     * @param list2 Second ranked list (e.g., vector search results)
     * @param topK  Number of results to return
     * @return Merged and re-ranked list
     */
    public List<ScoredChunk> merge(List<ScoredChunk> list1, List<ScoredChunk> list2, int topK) {
        Map<String, RRFScore> rrfScores = new HashMap<>();

        // Process first list
        for (int rank = 0; rank < list1.size(); rank++) {
            ScoredChunk chunk = list1.get(rank);
            double rrfScore = 1.0 / (k + rank + 1); // rank is 0-indexed, formula uses 1-indexed
            rrfScores.computeIfAbsent(chunk.id(), id -> new RRFScore(chunk))
                    .addScore(rrfScore);
        }

        // Process second list
        for (int rank = 0; rank < list2.size(); rank++) {
            ScoredChunk chunk = list2.get(rank);
            double rrfScore = 1.0 / (k + rank + 1);
            rrfScores.computeIfAbsent(chunk.id(), id -> new RRFScore(chunk))
                    .addScore(rrfScore);
        }

        // Sort by RRF score and return top K
        List<ScoredChunk> merged = rrfScores.values().stream()
                .sorted((a, b) -> Double.compare(b.totalScore, a.totalScore))
                .limit(topK)
                .map(rrf -> rrf.chunk.withScore(rrf.totalScore))
                .toList();

        return merged;
    }

    /**
     * Merge multiple ranked lists using RRF.
     */
    public List<ScoredChunk> mergeMultiple(List<List<ScoredChunk>> lists, int topK) {
        if (lists.isEmpty())
            return Collections.emptyList();
        if (lists.size() == 1)
            return lists.get(0).subList(0, Math.min(topK, lists.get(0).size()));

        List<ScoredChunk> result = lists.get(0);
        for (int i = 1; i < lists.size(); i++) {
            result = merge(result, lists.get(i), Math.max(topK, result.size() + lists.get(i).size()));
        }

        return result.subList(0, Math.min(topK, result.size()));
    }

    /**
     * Helper class to track RRF scores.
     */
    private static class RRFScore {
        final ScoredChunk chunk;
        double totalScore = 0;

        RRFScore(ScoredChunk chunk) {
            this.chunk = chunk;
        }

        void addScore(double score) {
            this.totalScore += score;
        }
    }
}
