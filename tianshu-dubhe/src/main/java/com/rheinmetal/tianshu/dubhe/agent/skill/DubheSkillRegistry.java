package com.rheinmetal.tianshu.dubhe.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The open set of skills the agent may read and call (G45).
 *
 * <p>An empty registry is a legal state: the agent then has no tools and answers from its own knowledge
 * ({@code skillsUsed=[]}, G50). Unknown ids are tolerated everywhere — {@link #find} returns empty and
 * {@link #resolve} skips them — because ids may arrive from configuration that is newer or older than this build.</p>
 *
 * <p>Registration order is the directory order, so the agent's prompt is stable for a given configuration
 * (identical input → identical directory text). Registration happens at assembly time; reads happen per task.</p>
 */
public final class DubheSkillRegistry {

    private final Map<String, DubheSkill> skills = new LinkedHashMap<>();

    /**
     * Adds a skill.
     *
     * @throws IllegalArgumentException when the id is already registered; two skills answering to one id would
     *                                  make {@code skillsUsed} ambiguous
     */
    public synchronized DubheSkill register(DubheSkill skill) {
        if (skill == null) {
            throw new IllegalArgumentException("skill must not be null");
        }
        if (skills.containsKey(skill.id())) {
            throw new IllegalArgumentException("duplicate skill id: " + skill.id());
        }
        skills.put(skill.id(), skill);
        return skill;
    }

    public DubheSkill register(DubheSkillSpec spec, DubheSkillExecutor executor) {
        return register(new DubheSkill(spec, executor));
    }

    /** Looks a skill up; an unknown or blank id is simply absent, never an error (open set). */
    public synchronized Optional<DubheSkill> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(id.trim()));
    }

    /** Resolves ids in order, skipping the ones this build does not know. */
    public synchronized List<DubheSkill> resolve(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<DubheSkill> resolved = new ArrayList<>(ids.size());
        for (String id : ids) {
            find(id).ifPresent(resolved::add);
        }
        return List.copyOf(resolved);
    }

    /** The directory, in registration order. */
    public synchronized List<DubheSkill> all() {
        return List.copyOf(skills.values());
    }

    public synchronized int size() {
        return skills.size();
    }

    public synchronized boolean isEmpty() {
        return skills.isEmpty();
    }

    /** Drops every registration; tests use it to start from the empty state. */
    public synchronized void clear() {
        skills.clear();
    }
}