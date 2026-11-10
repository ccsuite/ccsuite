package ccsuite.ccacc.trim;

import module java.base;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

/** {@code ~/ccsuite/ccacc/trim-state.json}: the processes seen (keyed pid@start, so a reused pid is a new process) and the MB given back today. */
@Data
@Accessors(fluent = true, chain = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class TrimState {
    Map<String, Seen> seen = new LinkedHashMap<>();
    String day = "";
    double givenBackMb;

    static String key(Proc p) { return p.pid() + "@" + p.startMs(); }

    static Path file() { return TrimJson.home().resolve("trim-state.json"); }

    static TrimState load(Path file) { return TrimJson.load(file, TrimState.class, TrimState::new); }

    void save(Path file) { TrimJson.save(file, this); }

    /** The MB given back today; a new day starts again at zero. */
    double today(long now) { return day.equals(dayOf(now)) ? givenBackMb : 0; }

    void add(long now, double mb) {
        if (!day.equals(dayOf(now))) { day = dayOf(now); givenBackMb = 0; }
        givenBackMb += mb;
    }

    static String dayOf(long now) { return Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }
}
