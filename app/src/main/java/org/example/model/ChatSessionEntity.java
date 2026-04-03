package org.example.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import java.util.ArrayList;
import java.util.List;

@Entity
public class ChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ChatMessageEntity> messages = new ArrayList<>();

    private String currentAgentType;

    public ChatSessionEntity() {}

    public Long getId() { return id; }

    public List<ChatMessageEntity> getMessages() { return messages; }

    public void addMessage(ChatMessageEntity message) {
        messages.add(message);
        message.setSession(this);
    }

    public String getCurrentAgentType() { return currentAgentType; }
    public void setCurrentAgentType(String currentAgentType) { this.currentAgentType = currentAgentType; }
}
