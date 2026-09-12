package sg.phuc.lob.engine;

import java.util.ArrayList;
import java.util.List;

public final class Validation {
    public long badLength, unknownType, duplicateRef, unknownRef, execExceeds, cancelExceeds, noDirectory,
                crossedInMarket, priorityChecked, priorityViolations, duplicateMatch, brokenUnknown, liveAtC;
    private final List<String> samples = new ArrayList<>();
    private static final int MAX_SAMPLES = 20;
    private MatchTracker matches;                       // Phase 3: LongSet-backed; null = disabled

    public interface MatchTracker { boolean add(long m); boolean contains(long m); }
    public void enableMatchTracking(MatchTracker t) { matches = t; }
    public void matchSeen(long m) { if (matches != null && !matches.add(m)) duplicateMatch++; }
    public void broken(long m) { if (matches != null && !matches.contains(m)) brokenUnknown++; }
    void sample(String kind, long msgNo, long id) { if (samples.size() < MAX_SAMPLES) samples.add(kind + "@" + msgNo + ":" + id); }
    public List<String> samples() { return samples; }
    public boolean structurallyClean() { return badLength + unknownType + duplicateRef + unknownRef + execExceeds + cancelExceeds == 0; }
    public String toJson() {
        StringBuilder s = new StringBuilder(512);
        s.append("{\"badLength\":").append(badLength).append(",\"unknownType\":").append(unknownType)
         .append(",\"duplicateRef\":").append(duplicateRef).append(",\"unknownRef\":").append(unknownRef)
         .append(",\"execExceeds\":").append(execExceeds).append(",\"cancelExceeds\":").append(cancelExceeds)
         .append(",\"noDirectory\":").append(noDirectory).append(",\"crossedInMarket\":").append(crossedInMarket)
         .append(",\"priorityChecked\":").append(priorityChecked).append(",\"priorityViolations\":").append(priorityViolations)
         .append(",\"duplicateMatch\":").append(duplicateMatch).append(",\"brokenUnknown\":").append(brokenUnknown)
         .append(",\"liveAtC\":").append(liveAtC).append(",\"samples\":[");
        for (int i = 0; i < samples.size(); i++) { if (i > 0) s.append(','); s.append('"').append(samples.get(i)).append('"'); }
        return s.append("]}").toString();
    }
}
