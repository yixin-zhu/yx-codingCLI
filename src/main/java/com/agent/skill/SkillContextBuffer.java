package com.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * load_skill 写入、下一轮 user message 前 drain 的一次性 skill 注入缓冲区。
 */
public final class SkillContextBuffer {

    private static final int MAX_SKILLS = 3;

    private final Map<String, String> entries = new LinkedHashMap<>();

    public synchronized void push(String skillName, String body) {
        if (skillName == null || skillName.isBlank() || body == null) {
            return;
        }
        entries.remove(skillName);
        entries.put(skillName, body);
        while (entries.size() > MAX_SKILLS) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    public synchronized String drain() {
        if (entries.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> snapshot = new ArrayList<>(entries.entrySet());
        entries.clear();

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : snapshot) {
            sb.append("## 已加载 Skill：").append(entry.getKey()).append('\n')
                    .append(entry.getValue().trim()).append('\n')
                    .append('\n');
        }
        sb.append("---\n");
        return sb.toString();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
