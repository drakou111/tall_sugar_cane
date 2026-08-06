package dev.drakou111.sugarcane;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SeedReporter {

    private static final String DEFAULT_WEB_APP_URL = "https://script.google.com/macros/s/AKfycbwtYOsLc2kRDX_wzh1ap1vk8bpGIhhT4TeiZ5iQPsajPAdqvYh8GV3XjDMXdxIBR6_s/exec";

    /**
     * Where reports go. Overridable with {@code -Dsugarcane.reportUrl=...} so the
     * reporting path can be exercised end to end against a local server: the only other
     * way to check it is to post a fake find to the shared spreadsheet, which is not a
     * test, it is vandalism with extra steps.
     */
    private static final String WEB_APP_URL =
            System.getProperty("sugarcane.reportUrl", DEFAULT_WEB_APP_URL);

    private final HttpClient httpClient;

    public SeedReporter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    /**
     * The report body.
     *
     * <p>Every number goes as a JSON <em>string</em>. JSON has one numeric type and the
     * receiving end is JavaScript, where that is an IEEE-754 double: exact only to 2^53, while
     * a world seed uses the full 64 bits. {@code -7585781829663227268} parses as
     * {@code -7585781829663227000} and the find is then unreproducible — silently, because the
     * number that comes back still looks like a seed. Quoting costs nothing and the loss is
     * unrecoverable, so quote everything numeric rather than only the two fields that overflow
     * today.
     */
    static String payload(String username, long seed, int x, int base, int z, int biome,
            int chunkX, int chunkZ, boolean isCrossChunk, int height, int spawnX, int spawnZ,
            long away) {
        return String.format(
                "{\"username\":\"%s\",\"seed\":\"%d\",\"x\":\"%d\",\"base\":\"%d\",\"z\":\"%d\","
                        + "\"biome\":\"%d\",\"chunkX\":\"%d\",\"chunkZ\":\"%d\","
                        + "\"isCrossChunk\":%b,\"height\":\"%d\",\"spawnX\":\"%d\","
                        + "\"spawnZ\":\"%d\",\"distance\":\"%d\"}",
                escapeJson(username), seed, x, base, z, biome, chunkX, chunkZ, isCrossChunk,
                height, spawnX, spawnZ, away);
    }

    public void reportToDataBase(long seed, int x, int base, int z, int biome, int chunkX, int chunkZ, boolean isCrossChunk, int height, int spawnX, int spawnZ, long away) {
        String jsonPayload = payload(Cli.getReporterUsername(), seed, x, base, z, biome,
                chunkX, chunkZ, isCrossChunk, height, spawnX, spawnZ, away);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WEB_APP_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("Successfully reported find to spreadsheet!");
            } else {
                System.err.println("Failed to report find. HTTP Code: " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("Error sending report: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
