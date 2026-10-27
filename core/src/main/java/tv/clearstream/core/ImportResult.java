package tv.clearstream.core;
import java.util.*;
public final class ImportResult {
    public final List<SourceEntry> entries = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public int duplicates;
}
