/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    
    //Frame processors
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public List<FrameProcessor> getFrameProcessors() {
    	return frameProcessors; }
    
    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }
    
	 // Maps each short-form letter flag to a factory that creates a NEW instance
	 // of the matching FrameProcessor. A Supplier (not a stored instance) is used
	 // deliberately: every time a letter appears on the command line - including
	 // repeats like "-nn" - we need a fresh, independent processor object, not
	 // the same one reused.
	 private static final Map<Character, Supplier<FrameProcessor>> frameP = Map.ofEntries(
	     Map.entry('n', FrameNumberer::new),
	     Map.entry('s', FrameScratcher::new),
	     Map.entry('f', FrameFlickerer::new),
	     Map.entry('w', FrameBlackAndWhiter::new),
	     Map.entry('y', FrameYellower::new),
	     Map.entry('v', FrameVignetter::new),
	     Map.entry('d', FrameDuster::new),
	     Map.entry('j', FrameJitterer::new),
	     Map.entry('m', FrameMottler::new),
	     Map.entry('b', FrameBleeder::new),
	     Map.entry('p', FramePepperer::new)
	 );
	
	 // Maps each long-form flag (e.g. "--number-frames") to the short letter it
	 // is equivalent to, so long-form args can reuse the same factory map above
	 // instead of duplicating it.
	 private static final Map<String, Character> longForm = Map.ofEntries(
	     Map.entry("--number-frames", 'n'),
	     Map.entry("--scratch-frames", 's'),
	     Map.entry("--flicker-frames", 'f'),
	     Map.entry("--black-and-white", 'w'),
	     Map.entry("--yellow-frames", 'y'),
	     Map.entry("--vignette-frame", 'v'),
	     Map.entry("--dust-frame", 'd'),
	     Map.entry("--jitter-frames", 'j'),
	     Map.entry("--mottle-frames", 'm'),
	     Map.entry("--bleed-frames", 'b'),
	     Map.entry("--pepper-frames", 'p')
	 );
	 
	//Returns true only if every character in s is a known frame-processor letter, and s isn't empty.
    private boolean isAllFrameProcessorLetters(String s) {
        for (char c : s.toCharArray()) {
            if (!frameP.containsKey(c)) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            } else if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            //Long-form frame processor flag, e.g. "--scratch-frames". Look up
            //which letter it corresponds to, then create one new processor instance for it via the factory map.
            } else if (longForm.containsKey(arg)) {
                frameProcessors.add(frameP.get(longForm.get(arg)).get());

            //Short-form flag(s), e.g. "-n" or a cluster like "-nssnf". Every
            //character after the dash must be a valid processor letter (checked up front so a bad cluster like "-nz" is rejected as a whole,
            //rather than partially applying valid letters before failing).
            //Processors are created and appended in the exact left-to-right
            //order the letters appear, so repeats and ordering both work.
            } else if (arg.length() > 1 && arg.charAt(0) == '-' && isAllFrameProcessorLetters(arg.substring(1))) {
                for (char c : arg.substring(1).toCharArray()) {
                    frameProcessors.add(frameP.get(c).get());
                }
            } else if (arg.startsWith("-")) {
                errorMessage = "Unknown option: " + arg;
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames         Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames        Render vertical film scratches");

    }


}
