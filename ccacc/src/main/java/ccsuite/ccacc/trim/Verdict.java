package ccsuite.ccacc.trim;

/** What the idle decision says about one process. */
enum Verdict {
    TRIM("would trim"), DISABLED("trimming is off"), NOT_IDLE("not idle long enough"), SMALL("working set too small"), GAP("trimmed recently"), RAM_PLENTY("free RAM is plentiful");

    final String text;

    Verdict(String text) { this.text = text; }
}
