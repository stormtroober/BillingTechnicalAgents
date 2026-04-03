package org.example.rag;

import java.util.*;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import org.example.model.DocumentChunkEntity;

/**
 * pgvector storage with cosine similarity search using Entity Manager.
 */
public class VectorStore {

    private final EntityManagerFactory emf;

    public VectorStore(EntityManagerFactory emf) {
        this.emf = emf;
    }

    /**
     * Add chunk with its embedding to DB.
     */
    public void addChunk(Chunk chunk, float[] embedding) {
        EntityManager em = null;
        try {
            em = emf.createEntityManager();
            em.getTransaction().begin();
            // Check if exists
            DocumentChunkEntity existing = em.find(DocumentChunkEntity.class, chunk.id());
            if (existing == null) {
                DocumentChunkEntity entity = new DocumentChunkEntity(chunk.id(), chunk.source(), chunk.content(), embedding);
                em.persist(entity);
            }
            em.getTransaction().commit();
        } finally {
            if (em != null) em.close();
        }
    }

    public List<ScoredChunk> search(float[] queryEmbedding, int topK) {
        return search(queryEmbedding, topK, null);
    }

    @SuppressWarnings("unchecked")
    public List<ScoredChunk> search(float[] queryEmbedding, int topK, String sourceFilter) {
        EntityManager em = null;
        List<ScoredChunk> computedResults = new ArrayList<>();
        try {
            em = emf.createEntityManager();
            String sql;
            if (sourceFilter != null) {
                sql = "SELECT id, content, source, 1 - (embedding <=> cast(:query as vector)) as score FROM DocumentChunkEntity WHERE source = :source ORDER BY embedding <=> cast(:query as vector) ASC LIMIT :limit";
            } else {
                sql = "SELECT id, content, source, 1 - (embedding <=> cast(:query as vector)) as score FROM DocumentChunkEntity ORDER BY embedding <=> cast(:query as vector) ASC LIMIT :limit";
            }
            
            var query = em.createNativeQuery(sql);
            // pgvector JDBC needs standard float array mapped to string natively or mapped properly via java.sql.Array. 
            // In hibernate-vector we can pass it as java array but usually native needs string casting for vector.
            String vectorStr = java.util.Arrays.toString(queryEmbedding);
            query.setParameter("query", vectorStr);
            query.setParameter("limit", topK);
            
            if (sourceFilter != null) {
                query.setParameter("source", sourceFilter);
            }

            List<Object[]> results = query.getResultList();
            for (Object[] row : results) {
                String id = (String) row[0];
                String content = (String) row[1];
                String source = (String) row[2];
                double score = ((Number) row[3]).doubleValue();
                computedResults.add(new ScoredChunk(id, content, source, score));
            }
            return computedResults;
        } finally {
            if (em != null) em.close();
        }
    }

    public int size() {
        EntityManager em = null;
        try {
            em = emf.createEntityManager();
            Long count = em.createQuery("SELECT COUNT(d) FROM DocumentChunkEntity d", Long.class).getSingleResult();
            return count.intValue();
        } finally {
            if (em != null) em.close();
        }
    }

    /**
     * Returns all chunk IDs already persisted in the DB in a single query.
     * Used to skip recomputing embeddings for chunks already indexed.
     */
    public Set<String> getExistingIds() {
        EntityManager em = null;
        try {
            em = emf.createEntityManager();
            List<String> ids = em.createQuery("SELECT d.id FROM DocumentChunkEntity d", String.class).getResultList();
            return new HashSet<>(ids);
        } finally {
            if (em != null) em.close();
        }
    }

    public void clear() {
        EntityManager em = null;
        try {
            em = emf.createEntityManager();
            em.getTransaction().begin();
            em.createQuery("DELETE FROM DocumentChunkEntity").executeUpdate();
            em.getTransaction().commit();
        } finally {
            if (em != null) em.close();
        }
    }
}
