package org.example.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Array;

@Entity
public class DocumentChunkEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false, length = 4000)
    private String content;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 384) // Size of embeddings from ai.djl (usually 384 for standard sentence-transformers)
    private float[] embedding;

    public DocumentChunkEntity() {}

    public DocumentChunkEntity(String id, String source, String content, float[] embedding) {
        this.id = id;
        this.source = source;
        this.content = content;
        this.embedding = embedding;
    }

    public String getId() { return id; }
    public String getSource() { return source; }
    public String getContent() { return content; }
    public float[] getEmbedding() { return embedding; }
}
