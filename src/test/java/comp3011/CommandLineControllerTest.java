/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Khup Sawm, a1924824 collaboration with Claude.
 *
 * This test class was written with AI assistance (Claude, Anthropic),
 * based on the parsing behaviour and test cases discussed and specified
 * by the student, including the exact mixing examples from the
 * assignment specification (Spec Part 1).
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for CommandLineController's argument parsing, focused on
 * the Spec Part 1 requirement that long-form, short-form, and stacked frame
 * processor flags can be freely mixed and matched while preserving order
 * and repeats.
 */
class CommandLineControllerTest {

    @TempDir
    Path tempDir;

    private String videoPath;

    // CommandLineController requires the video file argument to actually
    // exist on disk, so an empty placeholder file is created fresh before
    // each test rather than depending on any real media file.
    @BeforeEach
    void createDummyVideoFile() throws IOException {
        Path file = tempDir.resolve("dummy.mp4");
        Files.createFile(file);
        videoPath = file.toString();
    }

    @Test
    void helpFlagRequestsHelpAndDoesNotLaunch() {
        CommandLineController controller = new CommandLineController(new String[] { "-h" });

        assertEquals(0, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void singleLongFormFrameProcessorIsAdded() {
        CommandLineController controller = new CommandLineController(
                new String[] { "--number-frames", videoPath });

        List<FrameProcessor> processors = controller.getFrameProcessors();
        assertEquals(1, processors.size());
        assertInstanceOf(FrameNumberer.class, processors.get(0));
    }

    @Test
    void stackedShortFormClusterPreservesOrderAndRepeats() {
        // The exact example from the assignment spec: numbers, scratches
        // twice, numbers again, flickers, converts, warms, vignettes,
        // dusts, jitters, mottles, bleeds, then peppers.
        CommandLineController controller = new CommandLineController(
                new String[] { "-nssnfwyvdjmbp", videoPath });

        List<FrameProcessor> processors = controller.getFrameProcessors();
        assertEquals(13, processors.size());
        assertInstanceOf(FrameNumberer.class, processors.get(0));
        assertInstanceOf(FrameScratcher.class, processors.get(1));
        assertInstanceOf(FrameScratcher.class, processors.get(2));
        assertInstanceOf(FrameNumberer.class, processors.get(3));
        assertInstanceOf(FrameFlickerer.class, processors.get(4));
        assertInstanceOf(FrameBlackAndWhiter.class, processors.get(5));
        assertInstanceOf(FrameYellower.class, processors.get(6));
        assertInstanceOf(FrameVignetter.class, processors.get(7));
        assertInstanceOf(FrameDuster.class, processors.get(8));
        assertInstanceOf(FrameJitterer.class, processors.get(9));
        assertInstanceOf(FrameMottler.class, processors.get(10));
        assertInstanceOf(FrameBleeder.class, processors.get(11));
        assertInstanceOf(FramePepperer.class, processors.get(12));
    }

    @Test
    void longFormShortFormAndStackedFormsAreEquivalent() {
        // Mirrors the assignment spec's example of four equivalent ways to
        // request the same processor sequence: jitter, flicker, dust, dust,
        // number.
        String[][] equivalentArgSets = {
            { "--jitter-frames", "--flicker-frames", "--dust-frame", "--dust-frame", "--number-frames" },
            { "-j", "-f", "-d", "-d", "-n" },
            { "-jfddn" },
            { "-jf", "--dust-frame", "-d", "--number-frames" }
        };

        for (String[] args : equivalentArgSets) {
            String[] fullArgs = appendVideoPath(args);
            CommandLineController controller = new CommandLineController(fullArgs);

            List<FrameProcessor> processors = controller.getFrameProcessors();
            assertEquals(5, processors.size());
            assertInstanceOf(FrameJitterer.class, processors.get(0));
            assertInstanceOf(FrameFlickerer.class, processors.get(1));
            assertInstanceOf(FrameDuster.class, processors.get(2));
            assertInstanceOf(FrameDuster.class, processors.get(3));
            assertInstanceOf(FrameNumberer.class, processors.get(4));
        }
    }

    @Test
    void invalidClusterRejectsEntireArgumentRatherThanPartiallyApplying() {
        CommandLineController controller = new CommandLineController(
                new String[] { "-nz", videoPath });

        assertNotNull(controller.getErrorMessage());
        assertTrue(controller.getErrorMessage().contains("-nz"));
        assertFalse(controller.shouldLaunchApplication());
        assertTrue(controller.getFrameProcessors().isEmpty());
    }

    @Test
    void missingVideoFileProducesError() {
        CommandLineController controller = new CommandLineController(new String[] {});

        assertNotNull(controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void nonExistentVideoFileProducesError() {
        CommandLineController controller = new CommandLineController(
                new String[] { tempDir.resolve("does-not-exist.mp4").toString() });

        assertNotNull(controller.getErrorMessage());
        assertNull(controller.getVideoFile());
    }

    private String[] appendVideoPath(String[] args) {
        String[] result = new String[args.length + 1];
        System.arraycopy(args, 0, result, 0, args.length);
        result[args.length] = videoPath;
        return result;
    }
}