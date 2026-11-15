/*
 * Author: Khup Sawm, a1924824
 * The time it starts, the time it ends, and the text, as one or two lines.
 * Plain data holder.
 */

package comp3011;
import java.util.List;

// One subtitle: the media time range it is shown for, and its text lines (at most two)

public record SubtitleCue(long start, long end, List<String> lines) {

}
