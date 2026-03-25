package org.example.rag;

import ai.djl.Application;
import ai.djl.MalformedModelException;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;

import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Embedding service using DJL for paraphrase-multilingual-MiniLM-L12-v2.
 *
 * Uses task prefixes as per model specification:
 * - "search_query: " for queries
 * - "search_document: " for documents
 */
public class EmbeddingService implements AutoCloseable {

    private static final String QUERY_PREFIX = "";
    private static final String DOCUMENT_PREFIX = "";

    private final int embeddingDimension;
    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;
    private boolean initialized = false;

    /**
     * Create embedding service with default 384 dimensions (standard for MiniLM).
     */
    public EmbeddingService() {
        this(384);
    }

    /**
     * Create embedding service with specified dimensions (for Matryoshka).
     */
    public EmbeddingService(int dimension) {
        this.embeddingDimension = dimension;
    }

    /**
     * Initialize the embedding model.
     * Throws RuntimeException if the model cannot be loaded.
     */
    public synchronized void initialize() {
        if (initialized)
            return;

        try {
            loadPrimaryModel();
            initialized = true;
        } catch (Exception e) {
            throw new RuntimeException("[EmbeddingService] Failed to load embedding model: " + e.getMessage(), e);
        }
    }

    /**
     * Load the primary embedding model.
     */
    private void loadPrimaryModel() throws ModelNotFoundException, MalformedModelException, IOException {
        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optApplication(Application.NLP.TEXT_EMBEDDING)
                .optEngine("PyTorch")
                .optModelUrls(
                        "djl://ai.djl.huggingface.pytorch/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
                .optTranslator(new EmbeddingTranslator())
                .build();

        model = criteria.loadModel();
        predictor = model.newPredictor();
    }

    private final Map<String, float[]> embeddingCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Embed a single text.
     *
     * @param text    The text to embed
     * @param isQuery If true, uses query prefix; otherwise document prefix
     * @return The embedding vector
     */
    public float[] embed(String text, boolean isQuery) {
        if (!initialized)
            initialize();

        String prefixedText = (isQuery ? QUERY_PREFIX : DOCUMENT_PREFIX) + text;

        if (embeddingCache.containsKey(prefixedText)) {
            return embeddingCache.get(prefixedText);
        }

        try {
            float[] embedding = predictor.predict(prefixedText);
            if (embedding.length > embeddingDimension) {
                embedding = Arrays.copyOf(embedding, embeddingDimension);
            }
            embeddingCache.put(prefixedText, embedding);
            return embedding;
        } catch (Exception e) {
            throw new RuntimeException("[EmbeddingService] Embedding failed for input: " + e.getMessage(), e);
        }
    }

    /**
     * Embed multiple texts in batch.
     */
    public List<float[]> embedBatch(List<String> texts, boolean isQuery) {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text, isQuery));
        }
        return results;
    }

    @Override
    public void close() {
        if (predictor != null)
            predictor.close();
        if (model != null)
            model.close();

        initialized = false;
        embeddingCache.clear();
    }

    /**
     * Custom translator for sentence embeddings.
     */
    private static class EmbeddingTranslator implements Translator<String, float[]> {

        private HuggingFaceTokenizer tokenizer;

        @Override
        public void prepare(TranslatorContext ctx) throws Exception {
            Path modelPath = ctx.getModel().getModelPath();
            tokenizer = HuggingFaceTokenizer.newInstance(modelPath.resolve("tokenizer.json"));
        }

        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            if (tokenizer == null) {
                throw new IllegalStateException("Tokenizer not initialized");
            }

            ai.djl.huggingface.tokenizers.Encoding encoding = tokenizer.encode(input);
            long[] ids = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();

            NDManager manager = ctx.getNDManager();
            NDArray inputIdArray = manager.create(ids).reshape(1, ids.length);
            NDArray attentionArray = manager.create(attentionMask).reshape(1, attentionMask.length);

            return new NDList(inputIdArray, attentionArray);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            NDArray embedding = list.get(0);

            if (embedding.getShape().dimension() > 2) {
                embedding = embedding.mean(new int[] { 1 });
            }

            NDArray norm = embedding.pow(2).sum(new int[] { 1 }, true).sqrt();
            embedding = embedding.div(norm);

            return embedding.toFloatArray();
        }

        @Override
        public Batchifier getBatchifier() {
            return null;
        }
    }
}
