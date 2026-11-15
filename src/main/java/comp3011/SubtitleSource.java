/*
 * SubtitleSource (the interface) is a contract with one question: “what subtitle should be showing at this moment in the video?”
 */

package comp3011;

// Supplies the subtitles to show at given media time or null when nothing should be shown
public interface SubtitleSource {
	SubtitleCue cueAt(long timestampUs);

}
