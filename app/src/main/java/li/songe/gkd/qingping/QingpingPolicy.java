package li.songe.gkd.qingping;

/** Restrict subscriptions to the two actions this product exposes. */
public final class QingpingPolicy {
    public static final long STARTUP_WINDOW_MS = 15000L;
    private QingpingPolicy() {}

    public static String kind(String name) {
        if (name == null) return "other";
        if (name.startsWith("开屏广告")) return "splash";
        if (name.contains("广告") && (name.contains("跳转") || name.contains("误触"))) return "exit";
        return "other";
    }

    public static boolean allows(String kind, boolean exitEnabled, boolean interactive,
                                 long appAgeMs, boolean appSpecific) {
        if (!interactive) return false;
        if ("splash".equals(kind)) return appAgeMs >= 0 && appAgeMs <= STARTUP_WINDOW_MS;
        return "exit".equals(kind) && exitEnabled && appSpecific;
    }

    public static boolean shouldCount(boolean success, String action) {
        return success && action != null && !"none".equals(action);
    }

    public static boolean shouldDropContent(boolean sameApp, long appAgeMs, boolean exitEnabled) {
        return sameApp && appAgeMs > STARTUP_WINDOW_MS && !exitEnabled;
    }
}
