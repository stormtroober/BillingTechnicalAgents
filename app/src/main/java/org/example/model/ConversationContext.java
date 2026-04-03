package org.example.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Manages conversation history for multi-turn support.
 */
public class ConversationContext {
    private final List<ConversationMessage> messages = new ArrayList<>();
    private String currentAgentType = null;
    
    private final EntityManagerFactory emf;
    private ChatSessionEntity sessionEntity;

    public ConversationContext(EntityManagerFactory emf) {
        this.emf = emf;
        EntityManager em = null;
        try {
            em = emf.createEntityManager();
            em.getTransaction().begin();
            sessionEntity = new ChatSessionEntity();
            em.persist(sessionEntity);
            em.getTransaction().commit();
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    public void addMessage(ConversationMessage message) {
        messages.add(message);
        if (message.agentType() != null) {
            currentAgentType = message.agentType();
        }
        
        EntityManager em = null;
        try {
            em = emf.createEntityManager();
            em.getTransaction().begin();
            // Re-fetch to avoid detached state
            sessionEntity = em.find(ChatSessionEntity.class, sessionEntity.getId());
            sessionEntity.setCurrentAgentType(currentAgentType);
            
            ChatMessageEntity entity = new ChatMessageEntity(
                message.role(),
                message.content(),
                message.agentType(),
                message.toolName(),
                message.toolArguments()
            );
            sessionEntity.addMessage(entity);
            
            em.getTransaction().commit();
        } catch (Exception e) {
            System.err.println("Warning: failed to persist chat message: " + e.getMessage());
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    public List<ConversationMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public String getCurrentAgentType() {
        return currentAgentType;
    }

    public void setCurrentAgentType(String agentType) {
        this.currentAgentType = agentType;
    }

    public List<ConversationMessage> getRecentMessages(int count) {
        int start = Math.max(0, messages.size() - count);
        return messages.subList(start, messages.size());
    }

    public void clear() {
        messages.clear();
        currentAgentType = null;
        
        // Create a new session entity in db
        EntityManager em = null;
        try {
            em = emf.createEntityManager();
            em.getTransaction().begin();
            sessionEntity = new ChatSessionEntity();
            em.persist(sessionEntity);
            em.getTransaction().commit();
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }
}
