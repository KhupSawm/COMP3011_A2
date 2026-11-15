/*
 * Author: Khup Sawm, a1924824
 * Written with AI assistance (Claude, Anthropic).
 */

package comp3011;

import java.util.List;

/** Fake subtitles for testing before connecting to the transcription server. */
public class StubSubtitleSource implements SubtitleSource {

    private static final long SLOT_SECONDS = 6;
    private static final long VISIBLE_SECONDS = 4;

    @Override
    public SubtitleCue cueAt(long timestampUs) {
        long second = timestampUs / 1_000_000L;
        long slot = second / SLOT_SECONDS;
        long startSecond = slot * SLOT_SECONDS;

        // Returns null if there is no subtitle blank
        if (second - startSecond >= VISIBLE_SECONDS) {
            return null;
        }

        List<String> lines = (slot % 2 == 0)
                ? List.of("Stub subtitle at " + startSecond + " seconds")
                : List.of("This is a longer stub subtitle,",
                          "wrapped across two lines.");

        return new SubtitleCue(
                startSecond * 1_000_000L,
                (startSecond + VISIBLE_SECONDS) * 1_000_000L,
                lines
        );
    }
}