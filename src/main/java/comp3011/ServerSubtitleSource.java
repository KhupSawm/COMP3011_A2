
/*
 * Author: Khup Sawm, a1924824
 * Written with AI assistance.
 */


package comp3011;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ServerSubtitleSource implements SubtitleSource {

    private static final String SERVER_URL =
            "http://localhost:8080/api/v1/subtitles";

    private static final long MIN_DURATION_US = 1_000_000L;
    private static final long MAX_DURATION_US = 7_000_000L;

    // Whisper's upload limit is 25 MiB.
    private static final long MAX_AUDIO_SIZE = 25L * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    // Holds the subtitle cues received from the server.
    private volatile List<SubtitleCue> subtitles = List.of();

    public ServerSubtitleSource(File mediaFile) {

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Start transcription in the background so playback is not blocked.
        CompletableFuture.runAsync(() -> requestSubtitles(mediaFile));
    }

    private void requestSubtitles(File mediaFile) {

        File audioFile = null;

        try {

            // Extract audio from the video before uploading it.
            audioFile = extractAudio(mediaFile);

            long audioSize = Files.size(audioFile.toPath());

            // Prevent uploading audio that exceeds the Whisper file limit.
            if (audioSize > MAX_AUDIO_SIZE) {
                throw new IOException(
                        "Extracted audio is larger than the 25 MiB upload limit."
                );
            }

            System.out.println(
                    "Extracted audio size: " + (audioSize / 1024) + " KB"
            );

            // Read the smaller MP3 file instead of the original video.
            byte[] fileBytes = Files.readAllBytes(audioFile.toPath());

            String boundary = "----VideoPlayer" + UUID.randomUUID();

            ByteArrayOutputStream body = new ByteArrayOutputStream();

            body.write(("--" + boundary + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));

            body.write(
                    "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.mp3\"\r\n"
                            .getBytes(StandardCharsets.UTF_8)
            );

            body.write("Content-Type: audio/mpeg\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));

            body.write(fileBytes);

            body.write(("\r\n--" + boundary + "--\r\n")
                    .getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERVER_URL))
                    .timeout(Duration.ofMinutes(5))
                    .header(
                            "Content-Type",
                            "multipart/form-data; boundary=" + boundary
                    )
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                System.err.println(
                        "Subtitle server returned HTTP " + response.statusCode()
                );
                System.err.println(response.body());
                return;
            }

            // Convert the server's response into subtitle cues.
            subtitles = parseSubtitles(response.body());

            System.out.println(
                    "Received " + subtitles.size()
                            + " subtitle segments from the server."
            );

        } catch (Exception e) {

            System.err.println(
                    "Could not retrieve subtitles: " + e.getMessage()
            );

            e.printStackTrace();

        } finally {

            // Delete the temporary MP3 after the server request finishes.
            if (audioFile != null) {
                try {
                    Files.deleteIfExists(audioFile.toPath());
                } catch (IOException e) {
                    System.err.println(
                            "Could not delete temporary audio file: " + e.getMessage()
                    );
                }
            }
        }
    }

    private File extractAudio(File mediaFile) throws Exception {

        // Create a temporary MP3 file for the extracted audio.
        Path audioPath = Files.createTempFile("subtitle-audio-", ".mp3");

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile);
        FFmpegFrameRecorder recorder = null;

        boolean completed = false;

        try {

            // Open the original video and check that it contains audio.
            grabber.start();

            if (!grabber.hasAudio()) {
                throw new IOException("The selected video has no audio stream.");
            }

            int audioChannels = grabber.getAudioChannels();
            int sampleRate = grabber.getSampleRate();

            // Use safe defaults if the media does not report these values.
            if (audioChannels <= 0) {
                audioChannels = 1;
            }

            if (sampleRate <= 0) {
                sampleRate = 44100;
            }

            // Create an audio-only MP3 recorder.
            recorder = new FFmpegFrameRecorder(
                    audioPath.toFile(),
                    audioChannels
            );

            recorder.setFormat("mp3");
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
            recorder.setAudioBitrate(96000);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(audioChannels);

            recorder.start();

            Frame audioFrame;

            // Read only the audio frames and write them into the MP3 file.
            while ((audioFrame = grabber.grabSamples()) != null) {
                recorder.record(audioFrame);
            }

            recorder.stop();
            recorder.release();
            recorder = null;

            completed = true;

            System.out.println("Audio extraction completed.");

            return audioPath.toFile();

        } finally {

            // Release the recorder even when audio extraction fails.
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                    // The recorder may not have started successfully.
                }

                try {
                    recorder.release();
                } catch (Exception ignored) {
                    // Continue cleaning up the grabber.
                }
            }

            // Release the original video reader.
            try {
                grabber.stop();
            } catch (Exception ignored) {
                // The grabber may already be stopped.
            }

            try {
                grabber.release();
            } catch (Exception ignored) {
                // Continue with temporary-file cleanup.
            }

            // Remove incomplete audio files when extraction fails.
            if (!completed) {
                Files.deleteIfExists(audioPath);
            }
        }
    }

    private List<SubtitleCue> parseSubtitles(String response)
            throws IOException {

        JsonNode root = MAPPER.readTree(response);
        JsonNode segments = root.path("segments");

        List<SubtitleCue> result = new ArrayList<>();

        for (JsonNode segment : segments) {

            double startSeconds = segment.path("start").asDouble();
            double endSeconds = segment.path("end").asDouble();

            String text = segment.path("text").asText("").trim();

            if (text.isEmpty()) {
                continue;
            }

            long startUs = Math.round(startSeconds * 1_000_000L);
            long originalEndUs = Math.round(endSeconds * 1_000_000L);

            // Keep subtitle display duration between one and seven seconds.
            long durationUs = Math.max(
                    MIN_DURATION_US,
                    Math.min(MAX_DURATION_US, originalEndUs - startUs)
            );

            long endUs = startUs + durationUs;

            result.add(
                    new SubtitleCue(startUs, endUs, List.of(text))
            );
        }

        result.sort(Comparator.comparingLong(SubtitleCue::start));

        return List.copyOf(result);
    }

    @Override
    public SubtitleCue cueAt(long timestampUs) {

        List<SubtitleCue> currentSubtitles = subtitles;

        for (SubtitleCue cue : currentSubtitles) {

            if (timestampUs < cue.start()) {
                return null;
            }

            if (timestampUs < cue.end()) {
                return cue;
            }
        }

        return null;
    }
}
