package com.interview.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.model.ConversationMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps active sessions available across page refreshes while the local process runs. */
@Service
@Profile("local")
public class LocalSessionCacheService extends SessionCacheService {
    private final Map<String, Map<String, List<ConversationMessage>>> messages = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> states = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public LocalSessionCacheService() {
        super(null);
    }

    @Override
    public void saveMessage(String userId, String sessionId, ConversationMessage message) {
        messages.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(sessionId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(message);
    }

    @Override
    public List<ConversationMessage> getMessages(String userId, String sessionId) {
        return new ArrayList<>(messages.getOrDefault(userId, Map.of()).getOrDefault(sessionId, List.of()));
    }

    @Override
    public long getMessageCount(String userId, String sessionId) {
        return getMessages(userId, sessionId).size();
    }

    @Override
    public void clearMessages(String userId, String sessionId) {
        Map<String, List<ConversationMessage>> userMessages = messages.get(userId);
        if (userMessages != null) userMessages.remove(sessionId);
    }

    @Override
    public boolean hasCachedMessages(String userId, String sessionId) {
        return getMessageCount(userId, sessionId) > 0;
    }

    @Override
    public List<String> getCachedSessionIds(String userId) {
        return messages.getOrDefault(userId, Map.of()).entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void saveSessionState(String userId, String sessionId, Object state) {
        try {
            states.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                    .put(sessionId, mapper.writeValueAsString(state));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to cache local session state", e);
        }
    }

    @Override
    public <T> T getSessionState(String userId, String sessionId, Class<T> clazz) {
        String json = states.getOrDefault(userId, Map.of()).get(sessionId);
        if (json == null) return null;
        try {
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to restore local session state", e);
        }
    }

    @Override
    public void clearSessionCache(String userId, String sessionId) {
        clearMessages(userId, sessionId);
        Map<String, String> userStates = states.get(userId);
        if (userStates != null) userStates.remove(sessionId);
    }
}
