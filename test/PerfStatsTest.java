package em.altitude.game.scene.player;

public class PerfStatsTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    public static void main(String[] args) {
        double[] sorted = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
        check(PerfStats.percentile(sorted, 10, 50) == 5, "nearest-rank p50 of 1..10 is 5");
        check(PerfStats.percentile(sorted, 10, 95) == 10 && PerfStats.percentile(sorted, 10, 90) == 9, "p95 rounds the rank up, p90 exact");
        check(PerfStats.percentile(sorted, 10, 1) == 1 && PerfStats.percentile(sorted, 10, 100) == 10, "extremes");
        check(PerfStats.percentile(sorted, 4, 99) == 4, "only the first n values count");
        check(Double.isNaN(PerfStats.percentile(sorted, 0, 50)), "no values");

        double[] intervals = new double[200];
        for (int i = 0; i < 100; i++) intervals[i] = i % 2 == 0 ? 10 : 20; // unsorted input, avg 15, stdev 5
        PerfStats.Frames f = PerfStats.frames(intervals, 100, 1.5, 16.7);
        check(f.frames() == 100 && near(f.avg(), 15) && near(f.stdev(), 5) && f.max() == 20, "avg, stdev, max");
        check(f.p50() == 10 && f.p95() == 20 && f.overRefresh() == 50, "p50, p95 and frames over one refresh");
        check(near(f.fps(), 100 / 1.5), "fps over the measured seconds");
        check(PerfStats.frames(intervals, 0, 1, 16.7).frames() == 0, "an empty window");

        check(PerfStats.countsPauses("ZGC Minor Pauses", true) && PerfStats.countsPauses("ZGC Major Pauses", true), "ZGC pause beans count");
        check(!PerfStats.countsPauses("ZGC Minor Cycles", true) && !PerfStats.countsPauses("ZGC Major Cycles", true), "ZGC cycles do not");
        check(PerfStats.countsPauses("G1 Young Generation", false) && !PerfStats.countsPauses("G1 Concurrent GC", false), "G1 pauses only");

        PerfStats.Sample s = new PerfStats.Sample(1000, "PlayerScene", f, 2, 3.5, 2, 30, 42, 512);
        String csv = s.csv();
        check(csv.split(",", -1).length == PerfStats.CSV_HEADER.split(",").length, "CSV row matches the header: " + csv);
        check(csv.startsWith("1000,PlayerScene,100,1.500,15.000,10.000,20.000,20.000,20.000,5.000,50,2,3.5,2.0,30.0,42,512"), csv);
        check(s.line().startsWith("FPS 67  frame 15.0 ms") && s.line().contains("ping 42") && s.line().contains("heap 512 MB"), s.line());
        System.out.println("PASS: perf statistics (nearest-rank percentiles, stdev, over-refresh, GC bean choice) and line/CSV formats.");
    }
}
