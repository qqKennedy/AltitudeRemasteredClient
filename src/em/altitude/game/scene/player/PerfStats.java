package em.altitude.game.scene.player;

import java.util.Arrays;
import java.util.Locale;

/** The perf HUD and log statistics: pure functions over one second of frame intervals, and their text forms. */
final class PerfStats {
    private PerfStats() {}

    static final String CSV_HEADER = "epoch_ms,scene,frames,seconds,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,stdev_ms,over_refresh,gc_pauses,gc_ms,gc_max_ms,ticks_per_s,ping_ms,heap_mb";

    /** Frame-interval statistics for one window; times in ms. */
    record Frames(int frames, double seconds, double avg, double p50, double p95, double p99, double max, double stdev, int overRefresh) {
        double fps() { return seconds > 0 ? frames / seconds : 0; }
    }

    /** One second of measurements. pingMs is -1 outside play. */
    record Sample(long epochMs, String scene, Frames f, int gcPauses, double gcMs, double gcMaxMs, double ticksPerS, int pingMs, long heapMb) {
        /** The perf HUD's two lines: frame times, then the rest. */
        String[] lines() {
            return new String[] {
                    String.format(Locale.ROOT, "FPS %.0f  frame %.1f ms (p95 %.1f  p99 %.1f  max %.1f)", f.fps(), f.avg(), f.p95(), f.p99(), f.max()),
                    String.format(Locale.ROOT, ">refresh %d  GC %d / %.0f ms  ticks %.0f/s  ping %s  heap %d MB",
                            f.overRefresh(), gcPauses, gcMs, ticksPerS, pingMs < 0 ? "-" : Integer.toString(pingMs), heapMb) };
        }

        /** Both lines on one, as /opt perf prints it. */
        String line() { return String.join("  ", lines()); }

        String csv() {
            return String.format(Locale.ROOT, "%d,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.1f,%.1f,%.1f,%d,%d",
                    epochMs, scene, f.frames(), f.seconds(), f.avg(), f.p50(), f.p95(), f.p99(), f.max(), f.stdev(), f.overRefresh(), gcPauses, gcMs, gcMaxMs, ticksPerS, pingMs, heapMb);
        }
    }

    /** Nearest-rank percentile (p in 0..100] of the first n values of an ascending array; NaN when n is 0. */
    static double percentile(double[] sorted, int n, double p) {
        if (n <= 0) return Double.NaN;
        int rank = (int) Math.ceil(p / 100.0 * n);
        return sorted[Math.min(n, Math.max(1, rank)) - 1];
    }

    /** Statistics of the first n intervals (ms) measured over seconds; frames over refreshMs count as over-refresh. */
    static Frames frames(double[] intervals, int n, double seconds, double refreshMs) {
        if (n <= 0) return new Frames(0, seconds, 0, 0, 0, 0, 0, 0, 0);
        double[] sorted = Arrays.copyOf(intervals, n);
        Arrays.sort(sorted);
        double sum = 0;
        int over = 0;
        for (double v : sorted) {
            sum += v;
            if (v > refreshMs) over++;
        }
        double avg = sum / n, squares = 0;
        for (double v : sorted) squares += (v - avg) * (v - avg);
        return new Frames(n, seconds, avg, percentile(sorted, n, 50), percentile(sorted, n, 95), percentile(sorted, n, 99), sorted[n - 1], Math.sqrt(squares / n), over);
    }

    /**
     * Whether a garbage collector bean reports pauses. ZGC has separate beans for pauses and for concurrent cycles,
     * only the pause beans count; with other collectors every bean counts except concurrent ones (G1 Concurrent GC).
     */
    static boolean countsPauses(String beanName, boolean zgc) {
        return zgc ? beanName.contains("Pauses") : !beanName.contains("Concurrent") && !beanName.contains("Cycles");
    }
}
