package com.interview.agent.memory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local process cache for the development profile. */
@Component
@Profile("local")
public class LocalRedisStore extends RedisStore {
    private record SessionValue(String data, Instant expiresAt) {}

    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, SessionValue> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> fileHashes = new ConcurrentHashMap<>();

    public LocalRedisStore() {
        super(null);
    }

    @Override
    public void saveProfile(UserProfile profile) {
        profiles.put(profile.getUserId(), profile);
    }

    @Override
    public UserProfile loadProfile(String userId) {
        return profiles.get(userId);
    }

    @Override
    public void saveSession(String sessionId, String data, Duration ttl) {
        sessions.put(sessionId, new SessionValue(data, Instant.now().plus(ttl)));
    }

    @Override
    public String loadSession(String sessionId) {
        SessionValue value = sessions.get(sessionId);
        if (value == null) return null;
        if (Instant.now().isAfter(value.expiresAt())) {
            sessions.remove(sessionId);
            return null;
        }
        return value.data();
    }

    @Override
    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public String getFileHash(String userId, String filename) {
        return fileHashes.get(userId + ":" + filename);
    }

    @Override
    public void saveFileHash(String userId, String filename, String hash) {
        fileHashes.put(userId + ":" + filename, hash);
    }
}
