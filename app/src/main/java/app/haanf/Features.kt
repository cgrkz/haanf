package app.haanf

/** Parts of Haanf that are built but switched off for now. */
object Features {
    /**
     * The 1-mile walk benchmark, paused on 7 November 2026. While it is off, first run ends after
     * the watch, the program and the coach open without a score, Progress has no VO2max card, and
     * the planner cannot suggest one. The code and any saved results stay, so turning this back on
     * restores all of it.
     */
    const val BENCHMARK = false
}
