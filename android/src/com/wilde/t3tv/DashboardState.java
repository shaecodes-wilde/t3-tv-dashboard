package com.wilde.t3tv;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure dashboard view logic: status labels, attention/working classification,
 * filter counts, and connection warnings. Kept free of Android and org.json
 * so tests/com/wilde/t3tv/DashboardStateTest.java can run it on a plain JDK.
 */
final class DashboardState {
    static final int CONNECTING = 0; // No successful bridge response yet.
    static final int LIVE = 1;
    static final int STALE = 2;      // Bridge unreachable or feed expired; shown data is cached.

    static final class Task {
        String id, environmentId, environment, title, project, model, status, updatedAt;
    }

    static final class Env {
        String id, label, error;
        boolean online;
        final List<Task> tasks = new ArrayList<>();
    }

    /** Everything MainActivity needs for one render pass. */
    static final class View {
        final List<Task> tasks = new ArrayList<>(); // Selected environment, active filter, sorted.
        int attention, working, all, shown, online, environments;
        boolean cached;
        String banner, bannerDetail, bannerAge; // banner == null means no warning.
        String availability;                    // Right of the environment filters.
        String footer;                          // Left footer connection label.
        String empty;                           // Empty-list message; null when shown > 0.
    }

    static String statusLabel(String status) {
        switch (status) {
            case "waiting": return "Waiting for you";
            case "completed": return "Ready to review";
            case "failed": return "Failed";
            case "running": case "starting": case "preparing": return "Working";
            case "queued": return "Queued";
            case "idle": return "Idle";
            default: return status.isEmpty() ? status : status.substring(0,1).toUpperCase() + status.substring(1);
        }
    }

    static boolean isAttention(String s) { return s.equals("waiting") || s.equals("completed") || s.equals("failed"); }
    static boolean isWorking(String s) { return s.equals("running") || s.equals("queued") || s.equals("starting") || s.equals("preparing"); }
    static int priority(String s) { return s.equals("waiting") ? 0 : s.equals("failed") ? 1 : s.equals("completed") ? 2 : isWorking(s) ? 3 : 4; }

    static String age(long lastSuccess, long now) {
        if (lastSuccess <= 0) return "no update received";
        long seconds = Math.max(0, (now - lastSuccess) / 1000);
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        return (seconds / 3600) + "h ago";
    }

    /**
     * Summarize the feed for the current selection/filter. Connection state is a
     * first-class input, so a stale/recovery change alters the result even when
     * the task data itself is identical.
     */
    static View summarize(int feed, long lastSuccess, long now, List<Env> envs, String selected, String filter, boolean alerts) {
        View v = new View();
        v.cached = feed == STALE;
        v.environments = envs.size();
        if (!selected.equals("all")) {
            boolean found = false;
            for (Env env : envs) if (env.id.equals(selected)) found = true;
            if (!found) selected = "all"; // A removed environment widens rather than blanks the list.
        }
        ArrayList<Env> offline = new ArrayList<>();
        for (Env env : envs) {
            if (env.online) v.online++; else offline.add(env);
            if (!selected.equals("all") && !selected.equals(env.id)) continue;
            for (Task task : env.tasks) {
                v.all++;
                if (isAttention(task.status)) v.attention++;
                if (isWorking(task.status)) v.working++;
                if (filter.equals("attention") && !isAttention(task.status)) continue;
                if (filter.equals("working") && !isWorking(task.status)) continue;
                v.tasks.add(task);
            }
        }
        v.tasks.sort((a, b) -> { int p = Integer.compare(priority(a.status), priority(b.status)); return p != 0 ? p : b.updatedAt.compareTo(a.updatedAt); });
        v.shown = v.tasks.size();

        if (feed == STALE) {
            v.availability = "Connection lost";
            v.banner = "Bridge offline. Reconnecting…";
            v.bannerAge = "Last update " + age(lastSuccess, now);
            v.footer = "● Showing cached data";
        } else if (feed == CONNECTING) {
            v.availability = "";
            v.footer = "○ Connecting…";
        } else {
            v.availability = v.environments > 0 ? v.online + " of " + v.environments + " online" : "";
            v.footer = "● Live · " + (alerts ? "Alerts on" : "Alerts paused");
            if (!offline.isEmpty()) {
                v.banner = offline.size() == 1 ? offline.get(0).label + " is offline. Reconnecting…"
                        : offline.size() + " environments are offline. Reconnecting…";
                if (offline.size() == 1 && offline.get(0).error != null && !offline.get(0).error.isEmpty())
                    v.bannerDetail = offline.get(0).error;
            }
        }

        if (v.shown == 0) {
            if (v.environments == 0) v.empty = "No environments configured.";
            else if (v.online == 0 && feed == LIVE) v.empty = "No environments online. Reconnecting…";
            else v.empty = filter.equals("attention") ? "No tasks need attention." : filter.equals("working") ? "No working tasks." : "No tasks.";
        }
        return v;
    }
}
