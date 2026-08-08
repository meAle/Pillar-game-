package com.pirro.minigames.plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stores match participation, remaining lives, eliminations, and respawn state. */
final class PlayerLifeManager {
    private final int startingLives;
    private final Map<UUID, Integer> lives = new HashMap<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> eliminated = new HashSet<>();
    private final Set<UUID> pendingSpectators = new HashSet<>();

    PlayerLifeManager(int startingLives) {
        this.startingLives = startingLives;
    }

    void reset() {
        lives.clear();
        participants.clear();
        eliminated.clear();
        pendingSpectators.clear();
    }

    void clearParticipants() {
        participants.clear();
    }

    void addParticipant(UUID playerId) {
        lives.putIfAbsent(playerId, startingLives);
        participants.add(playerId);
    }

    boolean hasParticipant(UUID playerId) {
        return participants.contains(playerId);
    }

    boolean hasParticipants() {
        return !participants.isEmpty();
    }

    int participantCount() {
        return participants.size();
    }

    Set<UUID> participantIds() {
        return Set.copyOf(participants);
    }

    int lives(UUID playerId) {
        return lives.getOrDefault(playerId, 0);
    }

    int consumeLife(UUID playerId) {
        int remaining = Math.max(0, lives.getOrDefault(playerId, startingLives) - 1);
        lives.put(playerId, remaining);
        return remaining;
    }

    boolean eliminate(UUID playerId) {
        if (!participants.contains(playerId) || !eliminated.add(playerId)) {
            return false;
        }
        lives.put(playerId, 0);
        return true;
    }

    boolean isEliminated(UUID playerId) {
        return eliminated.contains(playerId);
    }

    boolean isAlive(UUID playerId) {
        return participants.contains(playerId) && !eliminated.contains(playerId) && lives(playerId) > 0;
    }

    void markPendingSpectator(UUID playerId) {
        pendingSpectators.add(playerId);
    }

    boolean consumePendingSpectator(UUID playerId) {
        return pendingSpectators.remove(playerId);
    }

    void forgetPendingSpectator(UUID playerId) {
        pendingSpectators.remove(playerId);
    }
}
