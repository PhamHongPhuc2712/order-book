package sg.phuc.lob.engine;

import java.util.ArrayList;
import java.util.List;

public final class Validation {
    public long badLength, unknownType, duplicateRef, unknownRef, execExceeds, cancelExceeds, noDirectory,
                crossedInMarket, crossedAtResume, crossedAtResumeMaxLagNs, priorityChecked, priorityViolations, duplicateMatch, brokenUnknown, liveAtC;
    private final List<String> samples = new ArrayList<>();
    private final java.util.HashMap<String, Integer> perKind = new java.util.HashMap<>();
    private static final int MAX_SAMPLES_PER_KIND = 20;
    private MatchTracker matches;                       // Phase 3: LongSet-backed; null = disabled

    public interface MatchTracker { boolean add(long m); boolean contains(long m); }
    public void enableMatchTracking(MatchTracker t) { matches = t; }
    public void matchSeen(long m) { if (matches != null && !matches.add(m)) duplicateMatch++; }
    public void broken(long m) { if (matches != null && !matches.contains(m)) brokenUnknown++; }
    /** Keeps the first MAX_SAMPLES_PER_KIND offenders of each kind as "kind@msgNo:id@ts" so no kind starves another. */
    void sample(String kind, long msgNo, long id, long ts) {
        int n = perKind.getOrDefault(kind, 0);
        if (n < MAX_SAMPLES_PER_KIND) { perKind.put(kind, n + 1); samples.add(kind + "@" + msgNo + ":" + id + "@" + ts); }
    }
    public List<String> samples() { return samples; }
    public boolean structurallyClean() { return badLength + unknownType + duplicateRef + unknownRef + execExceeds + cancelExceeds == 0; }
    public String toJson() {
        StringBuilder s = new StringBuilder(512);
        s.append("{\"badLength\":").append(badLength).append(",\"unknownType\":").append(unknownType)
         .append(",\"duplicateRef\":").append(duplicateRef).append(",\"unknownRef\":").append(unknownRef)
         .append(",\"execExceeds\":").append(execExceeds).append(",\"cancelExceeds\":").append(cancelExceeds)
         .append(",\"noDirectory\":").append(noDirectory).append(",\"crossedInMarket\":").append(crossedInMarket)
         .append(",\"crossedAtResume\":").append(crossedAtResume).append(",\"crossedAtResumeMaxLagNs\":").append(crossedAtResumeMaxLagNs)
         .append(",\"priorityChecked\":").append(priorityChecked).append(",\"priorityViolations\":").append(priorityViolations)
         .append(",\"duplicateMatch\":").append(duplicateMatch).append(",\"brokenUnknown\":").append(brokenUnknown)
         .append(",\"liveAtC\":").append(liveAtC).append(",\"samples\":[");
        for (int i = 0; i < samples.size(); i++) { if (i > 0) s.append(','); s.append('"').append(samples.get(i)).append('"'); }
        return s.append("]}").toString();
    }
}
