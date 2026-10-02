/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Khup Sawm, a1924824
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
//import java.util.ArrayDeque;
//import java.util.ArrayList;
import java.util.List;
//import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    

    private final List<FrameProcessor> frameProcessors;
    private final BlockingQueue<PendingAudio> pendingAudio = new ArrayBlockingQueue<>(50);
    // Holds raw decoded frames waiting to be effect-processed. Bounded so a fast
    // decode thread can't race arbitrarily far ahead of a slower effects thread.
    private final BlockingQueue<DecodedFrame> decodedFrames = new ArrayBlockingQueue<>(5);
    // Holds fully effect-processed, display-ready frames waiting for the FX thread.
    private final BlockingQueue<PreparedFrame> preparedFrames = new ArrayBlockingQueue<>(5);
    
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean playbackOpen; // True when playback is happening.
    private volatile boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private volatile long firstTimestampUs = NO_SEEK_REQUEST;
    private volatile long logicalPlaybackBaseUs;
    private volatile long playbackStartNs;
    private volatile long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    
    private Thread audioThread;
    private Thread decodeThread; // Owns the FFmpeg grabber exclusively; no other thread may touch it.
    private Thread effectsThread; // Applies the FrameProcessor chain and converts frames for display.
    
    // Epoch counter. Incremented immediately on every seek, so frames already
    // decoded or mid-processing under the old epoch can be recognised as
    // stale and dropped, rather than briefly appearing after the seek.
    private final AtomicLong generation = new AtomicLong();
    
    // Thread-safe handoff of a seek target to the decode thread. Only the
    // decode thread may call methods on grabber, so other threads request a
    // seek via this reference instead of touching grabber directly.
    private final AtomicReference<SeekRequest> pendingSeek = new AtomicReference<>();
    
    // Distinguishes a real shutdown interrupt (exit the decode loop) from an
    // interrupt sent only to wake the thread for a pending seek (keep going).
    private volatile boolean shuttingDown;
    
    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler
    		) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = frameProcessors; //No more hardcoded new Arraylist
//        frameProcessors.add(new FrameBleeder());
//        frameProcessors.add(new FrameScratcher());
//        frameProcessors.add(new FrameDuster());
//        frameProcessors.add(new FramePepperer());
//        frameProcessors.add(new FrameBlackAndWhiter());
//        frameProcessors.add(new FrameYellower());
//        frameProcessors.add(new FrameVignetter());
//        frameProcessors.add(new FrameFlickerer());
//        frameProcessors.add(new FrameJitterer());
//        frameProcessors.add(new FrameNumberer());
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        // Bump the epoch immediately, making every frame already queued or midd processing unusable.
        generation.incrementAndGet();
        
        // Best-effort cleanup only, not relied on for correctness by itself
        // a frame a consumer thread already took off a queue won't be
        // removed by clear() and that's what the generation checks are for.
        decodedFrames.clear();
        preparedFrames.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;

        pendingSeek.set(new SeekRequest(grabTimestampUs, logicalTimestampUs));

        // Wake the decode thread immediately if it's blocked waiting for
        // queue space, so it notices the seek without delay.
        if (decodeThread != null) {
            decodeThread.interrupt();
        }

        flushAudioOutput();
        notifyPlaybackStateChanged();
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startAudioThread();
//            prepareNextFrame();
            // Calling startDecordThread here removed prepareNextFrame() 
            // since the decode thread now starts producing frame on its own.
            startDecodeThread(); 
            // Calling startEffectsThread starting the effects thread
            startEffectsThread();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    
    // AI: Copilot assisted here with expection thread.
    // Runs on its own thread. Continuously grabs frames from the FFmpeg
    // grabber, queues any audio samples, and hands each decoded video frame
    // to the effects stage via decodedFrames. This thread is the sole owner
    // of grabber - no other thread may call methods on it, which keeps
    // FFmpeg's non-thread-safe grabber access free of race conditions.
    private void runDecodeLoop() {
        long localGeneration = generation.get();
            while (playbackOpen) {
                SeekRequest seek = pendingSeek.getAndSet(null);
                if (seek != null) {
                    try {
                        performSeek(seek);
                    } catch (Exception e) {
                        Platform.runLater(() -> handlePlaybackError(e));
                        return;
                    }
                    localGeneration = generation.get();
                }
                
            	Frame frame;
                
                try {
                    frame = grabFrame();
                } catch (Exception e) {
                    Platform.runLater(() -> handlePlaybackError(e));
                    return;
                }
               
                if (frame == null) {
                    Platform.runLater(this::finishPlayback);
                    return;
                }

                long timestampUs = grabber.getTimestamp();
                if (audioAvailable && frame.samples != null) {
                    queueAudio(timestampUs, frame);
                }

                if (frame.image == null) {
                    continue;
                }

                if (!frameProcessorsInitialised) {
                    try {
                        initialiseFrameProcessors(new InfoVideo(
                                mediaName(videoFile), totalVideoFrames,
                                frame.imageWidth, frame.imageHeight, frame.imageDepth,
                                frame.imageChannels, frame.imageStride, frameRate,
                                intFrameRate, videoFrameDurationUs, grabber.getPixelFormat()));
                    } catch (Exception e) {
                        Platform.runLater(() -> handlePlaybackError(e));
                        return;
                    }
                    frameProcessorsInitialised = true;
                }

                if (firstTimestampUs == NO_SEEK_REQUEST) {
                    firstTimestampUs = timestampUs;
                    playbackStartNs = System.nanoTime();
                    if (pauseRequested) {
                        pauseStartedNs = playbackStartNs;
                    }
                }

                long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
                long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
                long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
                
                // AI assisted here provided the idea to use .clone().
                // Critical region: grabber.grab() reuses the same underlying Frame
                // object on every call, overwriting its buffers in place. Without
                // cloning here, the next loop iteration would corrupt the pixel
                // data out from under the effects thread mid-processing - a frame
                // tearing race condition. clone() gives the effects thread its own
                // independent copy, safe from being overwritten by future decodes.
                Frame frameCopy = frame.clone();

                DecodedFrame decoded = new DecodedFrame(
                        frameCopy,
                        grabber.getFrameNumber(),
                        timestampUs,
                        logicalTimestampUs,
                        targetTimeNs, localGeneration);
            
                try {
                    decodedFrames.put(decoded);
                } catch (InterruptedException e) {
                    if (shuttingDown) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Woken for a seek, not a shutdown: this frame is now stale
                    // anyway (a newer epoch is coming), so just loop back around -
                    // the top-of-loop check will pick up pendingSeek next pass.
                }
        }
    }
    
    // Runs only on the decode thread, the sole owner of grabber. Jumps the
    // decoder to the requested timestamp and resets the clock fields the
    // decode loop uses to time subsequently decoded frames.
    private void performSeek(SeekRequest seek) throws Exception {
        grabber.setTimestamp(seek.grabTimestampUs());
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        logicalPlaybackBaseUs = seek.logicalTimestampUs();
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    // Starts the decode thread. Called once per playback session from
    // startPlayback, mirroring the pattern already used for the audio thread.
    private void startDecodeThread() {
        decodeThread = new Thread(this::runDecodeLoop, "decode-thread");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }
    
    // Runs on its own thread. Takes raw decoded frames from decodedFrames,
    // applies the full FrameProcessor chain in order, converts the result to
    // a displayable JavaFX Image, and hands it to the FX thread via
    // preparedFrames. Running this separately from decode lets both stages
    // use a separate CPU core at once, which is the core performance gain
    // this redesign is aiming for.
    private void runEffectsLoop() {
        
        while (!Thread.currentThread().isInterrupted()) {
            DecodedFrame decoded;

            try {
                decoded = decodedFrames.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Drop frames decoded under an older epoch - a seek happened
            // after this frame was produced but before it was consumed here.
            if (decoded.generation() != generation.get()) {
                continue;
            }
            
            InfoFrame info = new InfoFrame(decoded.frameNumber(), decoded.timestampUs());
            
            try {
                processFrame(decoded.frame(), info);
            } catch (Exception e) {
                Platform.runLater(() -> handlePlaybackError(e));
                return;
            }
            
            // Adding a guard to limit the race check the interrupted flag before touching converter
            // so that a thread that's already been told to stop doesn't attempt another step.
            if (Thread.currentThread().isInterrupted()) {
            	return;
            }
            
            Image image = converter.convert(decoded.frame());

            // Re-check after processing: old-generation work is allowed to
            // finish, but its result must never be forwarded for display.
            if (decoded.generation() != generation.get()) {
                continue;
            }
            
            try {
            preparedFrames.put(new PreparedFrame(
                    image,
                    decoded.frameNumber(),
                    decoded.timestampUs(),
                    decoded.logicalTimestampUs(),
                    decoded.targetTimeNs(),
                    System.nanoTime(), decoded.generation()));
            } catch (InterruptedException e) {
            	Thread.currentThread().interrupt();
            	return;
            }
        }

    }

    // Starts the effects thread, mirroring the pattern used for decode and audio.
    private void startEffectsThread() {
        effectsThread = new Thread(this::runEffectsLoop, "effects-thread");
        effectsThread.setDaemon(true);
        effectsThread.start();
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1) — audio output is now handled entirely by the dedicated audio thread (runAudioLoop),
        // independent of this heartbeat.
//        writeDueAudio(now);

        // Task (2)
        if (preparedFrame != null && preparedFrame.targetTimeNs() <= now) {
            // We have a prepared frame ready to go and it is due (or just past due!) so get it up on screen ASAP!
            displayPreparedFrame(now);
        }

        // Task (3). We'll try and be nice to the GUI event loop here by queueing the prepareNextFrame call rather than
        // hogging the thread and doing it here, hence the tricky callback and use of prepareNextFrameQueued. Feel free
        // to replace this whole block with simple linear control flow logic
        // such as: if (!pauseRequested && preparedFrame == null) prepareNextFrame();
        // to compare.
        if (!pauseRequested && preparedFrame == null) {
            PreparedFrame candidate = preparedFrames.poll();
            // Discard if a seek happened after this frame was prepared but before the FX thread picked it up.
            if (candidate != null && candidate.generation() == generation.get()) {
                preparedFrame = candidate;
            }
        }
        
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
        	//On blockingQueue throws an exception if the queue is full
            boolean queued = pendingAudio.offer(new PendingAudio(timestampUs, samples)); //Offer returns false if there is no space
        }
    }
    
    private void runAudioLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (!audioOutputEnabled || audioPlayer == null || pauseRequested
                        || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
                    Thread.sleep(5);
                    continue;
                }

                PendingAudio audio = pendingAudio.peek();
                if (audio == null) {
                    Thread.sleep(2);
                    continue;
                }

                long now = System.nanoTime();
                long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;

                if (audio.timestampUs() > dueTimestampUs) {
                    Thread.sleep(1);
                    continue;
                }

                int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
                if (written > 0) {
                    audio.advance(written);
                    if (audio.finished()) {
                        pendingAudio.poll();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void startAudioThread() {
        audioThread = new Thread(this::runAudioLoop, "audio-thread");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }
    
    // Waits briefly for a worker thread to actually finish after being
    // interrupted. Needed because interrupt() alone doesn't guarantee a
    // thread has stopped using shared resources (grabber, converter) without
    // this, closing those resources could race against a thread still
    // actively using them.
    private void joinQuietly(Thread thread) {
    	// Making joinQuietly itself null safe.
    	if (thread == null) {
    		return;
    	}
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closePlaybackResources() {
    	shuttingDown = true;
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;
        
        if (audioThread != null) {
            audioThread.interrupt();
            joinQuietly(audioThread);
            audioThread = null;
        }
        
        // Interrupt the decode thread on shutdown.
        if (decodeThread != null) {
            decodeThread.interrupt();
            joinQuietly(audioThread);
            decodeThread = null;
        }
        
        // Interrupt it on shutdown
        if (effectsThread != null) {
            effectsThread.interrupt();
            joinQuietly(audioThread);
            effectsThread = null;
        }

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }
        

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
        
        pendingSeek.set(null);
        shuttingDown = false;
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }
    
    // Carries one raw decoded frame plus its timing metadata from the decode
    // thread to the effects thread, before any FrameProcessor has run on it.
    private record DecodedFrame(
            Frame frame,
            int frameNumber,
            long timestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long generation) {
    }
    
    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs,
            long generation) {
    }

    // Carries a seek target from seekTo (FX thread) to the decode thread.
    private record SeekRequest(long grabTimestampUs, long logicalTimestampUs) {
    	
    }
    
    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }

}
