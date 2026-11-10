package ccsuite.ccacc.trim;

import module java.base;
import lombok.Data;
import lombok.experimental.Accessors;

/** What Tabs said about its live tabs: the pids to hold back (selected or busy) and the Claude session name of each tab, by the tab's pid. Empty when Tabs did not answer. */
@Data
@Accessors(fluent = true, chain = true)
public class TabsInfo {
    Set<Long> hold = new HashSet<>();
    Map<Long, String> names = new HashMap<>();

    /** The name of the tab that holds a process of this lineage, or empty. */
    String nameFor(Set<Long> lineage) { return lineage.stream().map(names::get).filter(Objects::nonNull).findFirst().orElse(""); }
}
