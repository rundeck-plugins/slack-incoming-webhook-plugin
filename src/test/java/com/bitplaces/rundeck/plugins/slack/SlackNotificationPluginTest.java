package com.bitplaces.rundeck.plugins.slack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests cover:
 * - external template path resolution (blank -> rdeck.base/libext/templates, and var expansion)
 * - fallback to built-in when external invalid
 * - HTTP request headers & payload
 * - Slack response handling
 */
public class SlackNotificationPluginTest {

    private static final String TEST_TOKEN = "T111111/B222222/CCCCCCCCCCCCCCCCCCCC";
    private static final String TEST_BASE = "https://hooks.slack.com/services";

    private static Map<String, Object> executionData() {
        Map<String, Object> job = new HashMap<>();
        job.put("name", "HelloWorld");
        job.put("href", "http://rundeck.example/job/1");

        Map<String, Object> exec = new HashMap<>();
        exec.put("id", "1");
        exec.put("href", "http://rundeck.example/execution/1");
        exec.put("project", "demo");
        exec.put("user", "test-user");
        exec.put("job", job);
        return exec;
    }

    /**
     * Minimal fake "connection" to carry what the plugin would have sent.
     * Not an actual HttpURLConnection — we simulate at the invokeSlackAPIMethod layer.
     */
    static class FakeConn {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        String contentType;
        String response = "ok"; // default Slack response
    }

    /**
     * Testable subclass that bypasses real HTTP and captures the POST body & headers.
     */
    private static class TestableSlackPlugin extends SlackNotificationPlugin {
        final FakeConn conn = new FakeConn();



        @Override
        protected String invokeSlackAPIMethod(String url, String message) {
            // Simulate x-www-form-urlencoded body with payload=
            try {
                String body = "payload=" + URLEncoder.encode(
                        message, StandardCharsets.UTF_8.name()
                );
                conn.sent.reset();
                conn.sent.write(body.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            // Simulate the content type that the real plugin sets
            conn.contentType = "application/x-www-form-urlencoded; charset=UTF-8";
            // Return whatever the test currently configured
            return conn.response;
        }
    }

    private Map<String, Object> config() {
        return new HashMap<>();
    }

    @BeforeEach
    void setUp() {
        // Ensure a predictable rdeck.base for tests that rely on it
        System.setProperty("rdeck.base", System.getProperty("java.io.tmpdir"));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("rdeck.base");
    }

    @Test
    void externalTemplate_resolvesBlankPath_usesRdeckBase(@TempDir Path rdeck) throws Exception {
        // Point rdeck.base to a tmp dir
        System.setProperty("rdeck.base", rdeck.toString());

        // Create ${rdeck.base}/libext/templates
        Path templatesDir = rdeck.resolve("libext").resolve("templates");
        Files.createDirectories(templatesDir);

        // Write an EXTERNAL override using the *same* name as the built-in
        String externalName = "slack-incoming-message.ftl";
        String marker = "EXTERNAL_OK_123";
        Files.writeString(
                templatesDir.resolve(externalName),
                "{\"text\":\"" + marker + "\"}\n",
                StandardCharsets.UTF_8
        );

        TestableSlackPlugin plugin = new TestableSlackPlugin();
        setField(plugin, "slack_ext_message_template_path", "");
        setField(plugin, "external_template", externalName);
        setField(plugin, "webhook_base_url", "https://hooks.slack.com/services");
        setField(plugin, "webhook_token", "T/B/XYZ");
        setField(plugin, "slack_channel", "#dummy");

        Map<String,Object> exec = new HashMap<>();
        exec.put("user", "anyone");

        boolean ok = plugin.postNotification("start", exec, Collections.emptyMap());
        assertTrue(ok, "postNotification should return true");

        String body = plugin.conn.sent.toString(StandardCharsets.UTF_8);
        String decoded = URLDecoder.decode(body.substring("payload=".length()), StandardCharsets.UTF_8);
        assertTrue(decoded.contains(marker), "Template output rendered");
    }


    @Test
    void externalTemplate_expandsVariables(@TempDir Path base) throws Exception {
        System.setProperty("rdeck.base", base.toString());

        File dir = new File(base.toFile(), "libext/templates");
        assertTrue(dir.mkdirs() || dir.exists());

        File ftl = new File(dir, "x.ftl");
        Files.writeString(ftl.toPath(),
                "{ \"text\": \"X-${executionData.id}\" }",
                StandardCharsets.UTF_8
        );

        TestableSlackPlugin p = new TestableSlackPlugin();
        setField(p, "webhook_base_url", TEST_BASE);
        setField(p, "webhook_token", TEST_TOKEN);
        setField(p, "external_template", "x.ftl");
        setField(p, "slack_ext_message_template_path", "${rdeck.base}/libext/templates");

        boolean ok = p.postNotification("success", executionData(), config());
        assertTrue(ok);

        String body = p.conn.sent.toString(StandardCharsets.UTF_8);
        String decoded = URLDecoder.decode(body.substring("payload=".length()), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("\"X-1\""));
    }


    @Test
    void externalTemplate_invalidPath_fallsBackAndStillPosts() {
        TestableSlackPlugin p = new TestableSlackPlugin();
        setField(p, "webhook_base_url", TEST_BASE);
        setField(p, "webhook_token", TEST_TOKEN);
        setField(p, "external_template", "does-not-exist.ftl");
        setField(p, "slack_ext_message_template_path", "/definitely/not/here");

        boolean ok = p.postNotification("failure", executionData(), config());
        assertTrue(ok);
        assertNotNull(p.conn);
    }

    @Test
    void nonOkResponse_throws() {
        TestableSlackPlugin p = new TestableSlackPlugin();
        setField(p, "webhook_base_url", TEST_BASE);
        setField(p, "webhook_token", TEST_TOKEN);

        // Make the next call behave like Slack returned an error
        p.conn.response = "invalid_payload";

        SlackNotificationPluginException ex = assertThrows(
                SlackNotificationPluginException.class,
                () -> p.postNotification("start", executionData(), config())
        );
        assertTrue(ex.getMessage().contains("invalid_payload"));
    }

    @Test
    void contentType_isSetAndUtf8() {
        TestableSlackPlugin p = new TestableSlackPlugin();
        setField(p, "webhook_base_url", TEST_BASE);
        setField(p, "webhook_token", TEST_TOKEN);

        boolean ok = p.postNotification("avgduration", executionData(), config());
        assertTrue(ok);
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", p.conn.contentType);
    }

    @Test
    void defaultTemplate_escapesFieldsForJsonAndSlack() throws Exception {
        TestableSlackPlugin p = new TestableSlackPlugin();
        setField(p, "webhook_base_url", TEST_BASE);
        setField(p, "webhook_token", TEST_TOKEN);
        setField(p, "slack_channel", "#ops \"blue\"");

        Map<String, Object> exec = executionData();
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) exec.get("job");
        job.put("name", "Backup \"nightly\" C:\\data");
        job.put("group", "ops\nlinux");
        exec.put("user", "<!channel> & co");
        exec.put("argstring", "-path C:\\tmp -msg \"<http://evil.example|click>\"");
        exec.put("failedNodeListString", "node\"1");

        assertTrue(p.postNotification("failure", exec, config()));

        Map<?, ?> payload = new com.fasterxml.jackson.databind.ObjectMapper().readValue(sentPayload(p), Map.class);
        assertEquals("#ops \"blue\"", payload.get("channel"));
        Map<?, ?> attachment = (Map<?, ?>) ((List<?>) payload.get("attachments")).get(0);
        assertEquals(
                "<http://rundeck.example/execution/1|Execution #1> of job "
                        + "<http://rundeck.example/job/1|ops\nlinux / Backup \"nightly\" C:\\data>",
                attachment.get("pretext"));
        Map<String, Object> fields = new HashMap<>();
        for (Object f : (List<?>) attachment.get("fields")) {
            fields.put((String) ((Map<?, ?>) f).get("title"), ((Map<?, ?>) f).get("value"));
        }
        assertEquals("&lt;!channel&gt; &amp; co", fields.get("Started By"));
        assertEquals("-path C:\\tmp -msg \"&lt;http://evil.example|click&gt;\"", fields.get("Options"));
        assertEquals("node\"1", fields.get("Failed Nodes"));
    }

    @Test
    void defaultTemplate_doesNotGroupExecutionIdDigits() throws Exception {
        TestableSlackPlugin p = new TestableSlackPlugin();
        setField(p, "webhook_base_url", TEST_BASE);
        setField(p, "webhook_token", TEST_TOKEN);

        Map<String, Object> exec = executionData();
        exec.put("id", 1234567L);

        assertTrue(p.postNotification("success", exec, config()));

        String payload = sentPayload(p);
        assertTrue(payload.contains("Execution #1234567>"), payload);
    }

    /* ---------- helpers ---------- */

    private static String sentPayload(TestableSlackPlugin p) {
        String body = p.conn.sent.toString(StandardCharsets.UTF_8);
        return URLDecoder.decode(body.substring("payload=".length()), StandardCharsets.UTF_8);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = SlackNotificationPlugin.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + name, e);
        }
    }
}
