package com.wilde.t3tv;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-JDK executable test for DashboardState view logic. Run via scripts/test-state.sh.
 * Asserts behavior (classification, filtering, connection warnings), not layout strings.
 */
public final class DashboardStateTest {
    static int failures;

    static void check(String name, boolean condition) {
        if (!condition) { failures++; System.out.println("FAIL " + name); }
        else System.out.println("ok   " + name);
    }

    static DashboardState.Env env(String id, String label, boolean online, String... statuses) {
        DashboardState.Env env = new DashboardState.Env();
        env.id = id; env.label = label; env.online = online;
        for (int i = 0; i < statuses.length; i++) {
            DashboardState.Task task = new DashboardState.Task();
            task.id = id + "-t" + i; task.environmentId = id; task.environment = label;
            task.title = "Task " + i; task.project = "project"; task.model = "model";
            task.status = statuses[i]; task.updatedAt = "2026-11-0" + (i + 1);
            env.tasks.add(task);
        }
        return env;
    }

    public static void main(String[] args) {
        check("failed label is Failed", DashboardState.statusLabel("failed").equals("Failed"));
        check("waiting label", DashboardState.statusLabel("waiting").equals("Waiting for you"));
        check("completed label", DashboardState.statusLabel("completed").equals("Ready to review"));
        check("working labels", DashboardState.statusLabel("running").equals("Working")
                && DashboardState.statusLabel("starting").equals("Working") && DashboardState.statusLabel("preparing").equals("Working"));
        check("queued label", DashboardState.statusLabel("queued").equals("Queued"));

        check("attention membership", DashboardState.isAttention("waiting") && DashboardState.isAttention("completed")
                && DashboardState.isAttention("failed") && !DashboardState.isAttention("running"));
        check("working membership", DashboardState.isWorking("running") && DashboardState.isWorking("queued")
                && DashboardState.isWorking("starting") && DashboardState.isWorking("preparing") && !DashboardState.isWorking("completed"));
        check("priority order", DashboardState.priority("waiting") < DashboardState.priority("failed")
                && DashboardState.priority("failed") < DashboardState.priority("completed")
                && DashboardState.priority("completed") < DashboardState.priority("running"));

        List<DashboardState.Env> two = new ArrayList<>();
        two.add(env("a", "workstation", true, "waiting", "failed", "completed", "running"));
        two.add(env("b", "fedora", true, "queued", "idle"));

        DashboardState.View all = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, two, "all", "all", true);
        check("all-env counts", all.attention == 3 && all.working == 2 && all.all == 6 && all.shown == 6);
        check("priority sort puts waiting first", all.tasks.get(0).status.equals("waiting"));
        check("failed sorts before completed", all.tasks.get(1).status.equals("failed") && all.tasks.get(2).status.equals("completed"));

        DashboardState.View onlyA = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, two, "a", "all", true);
        check("env filter narrows counts", onlyA.all == 4 && onlyA.attention == 3 && onlyA.working == 1);

        DashboardState.View attention = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, two, "all", "attention", true);
        check("attention filter membership", attention.shown == 3 && attention.tasks.stream().allMatch(t -> DashboardState.isAttention(t.status)));
        DashboardState.View working = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, two, "all", "working", true);
        check("working filter membership", working.shown == 2 && working.tasks.stream().allMatch(t -> DashboardState.isWorking(t.status)));

        DashboardState.View missing = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, two, "gone", "all", true);
        check("missing env selection falls back to all", missing.all == 6);

        List<DashboardState.Env> oneOffline = new ArrayList<>();
        oneOffline.add(env("a", "workstation", true, "waiting"));
        DashboardState.Env fedora = env("b", "fedora", false);
        fedora.error = "Connection unavailable. Check the bridge log.";
        oneOffline.add(fedora);
        DashboardState.View partial = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, oneOffline, "all", "all", true);
        check("one offline env names it", partial.banner != null && partial.banner.contains("fedora") && !partial.banner.contains("Bridge"));
        check("offline env detail surfaces", partial.bannerDetail != null && partial.bannerDetail.contains("bridge log"));
        check("availability counts online", partial.availability.equals("1 of 2 online"));
        check("offline env contributes no tasks", partial.all == 1 && partial.online == 1 && !partial.cached);
        check("live footer reports Live", partial.footer.contains("Live") && partial.footer.contains("Alerts on"));
        DashboardState.View paused = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, oneOffline, "all", "all", false);
        check("paused alerts labelled", paused.footer.contains("Alerts paused"));

        long lastSuccess = System.currentTimeMillis() - 52_000;
        DashboardState.View stale = DashboardState.summarize(DashboardState.STALE, lastSuccess, System.currentTimeMillis(), oneOffline, "all", "all", true);
        check("stale banner names bridge", stale.banner != null && stale.banner.contains("Bridge offline"));
        check("stale age shown", stale.bannerAge != null && stale.bannerAge.startsWith("Last update") && stale.bannerAge.endsWith("ago"));
        check("stale hides online count", stale.availability.equals("Connection lost"));
        check("stale footer labels cached data", stale.footer.contains("cached") && !stale.footer.contains("Live"));
        check("stale keeps cached tasks", stale.cached && stale.shown == 1 && stale.tasks.get(0).status.equals("waiting"));

        DashboardState.View recovered = DashboardState.summarize(DashboardState.LIVE, System.currentTimeMillis(), System.currentTimeMillis(), oneOffline, "all", "all", true);
        check("recovery with identical tasks clears bridge warning", recovered.banner == null || !recovered.banner.contains("Bridge offline"));
        check("recovery restores live state", !recovered.cached && recovered.footer.contains("Live") && recovered.availability.contains("online"));

        DashboardState.View connecting = DashboardState.summarize(DashboardState.CONNECTING, 0, 1000, two, "all", "all", true);
        check("connecting footer is not live", connecting.footer.contains("Connecting") && !connecting.footer.contains("Live") && !connecting.cached);

        DashboardState.View none = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, new ArrayList<>(), "all", "all", true);
        check("no environments labelled", none.empty != null && none.empty.contains("No environments"));

        List<DashboardState.Env> allOffline = new ArrayList<>();
        allOffline.add(env("a", "workstation", false));
        DashboardState.View offlineAll = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, allOffline, "all", "all", true);
        check("all environments offline labelled", offlineAll.empty != null && offlineAll.empty.equals("No environments online. Reconnecting…"));
        check("all offline env banner", offlineAll.banner != null && offlineAll.banner.contains("offline"));

        DashboardState.View emptyAttention = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, two, "b", "attention", true);
        check("attention empty names filter", emptyAttention.empty != null && emptyAttention.empty.equals("No tasks need attention."));
        DashboardState.View emptyWorking = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, two, "a", "working", false);
        check("working has rows in env a", emptyWorking.shown == 1 && emptyWorking.empty == null);
        List<DashboardState.Env> done = new ArrayList<>();
        done.add(env("x", "solo", true, "waiting", "completed"));
        DashboardState.View noWorking = DashboardState.summarize(DashboardState.LIVE, 1000, 2000, done, "all", "working", false);
        check("working empty names filter", noWorking.empty != null && noWorking.empty.equals("No working tasks."));

        check("age formatting", DashboardState.age(1000, 53_000).equals("52s ago")
                && DashboardState.age(0, 5000).equals("no update received")
                && DashboardState.age(1000, 1000 + 5 * 60_000).equals("5m ago")
                && DashboardState.age(1000, 1000 + 3 * 3600_000L).equals("3h ago"));

        System.out.println(failures == 0 ? "PASS all DashboardState checks" : failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
