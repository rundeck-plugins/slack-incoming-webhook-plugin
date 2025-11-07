/*
 * Copyright 2014 Andrew Karpow
 * based on Slack Plugin from Hayden Bakkum
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.bitplaces.rundeck.plugins.slack;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;
import com.dtolabs.rundeck.plugins.descriptions.Password;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Sends Rundeck job notification messages to a Slack room.
 *
 * @author Hayden Bakkum
 */
@Plugin(service= "Notification", name="SlackNotification")
@PluginDescription(title="Slack Incoming WebHook", description="Sends Rundeck Notifications to Slack")
public class SlackNotificationPlugin implements NotificationPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(SlackNotificationPlugin.class);


    private static final String SLACK_MESSAGE_COLOR_GREEN = "good";
    private static final String SLACK_MESSAGE_COLOR_YELLOW = "warning";
    private static final String SLACK_MESSAGE_COLOR_RED = "danger";

    private static final String SLACK_MESSAGE_TEMPLATE = "slack-incoming-message.ftl";

    private static final String TRIGGER_START = "start";
    private static final String TRIGGER_SUCCESS = "success";
    private static final String TRIGGER_FAILURE = "failure";
    private static final String TRIGGER_AVERAGE = "avgduration";
    private static final String TRIGGER_ONRETRY = "retryablefailure";

    private static final Map<String, SlackNotificationData> TRIGGER_NOTIFICATION_DATA = new HashMap<String, SlackNotificationData>();

    private static final Configuration FREEMARKER_CFG = new Configuration();

    @PluginProperty(title = "WebHook Base URL",
                    description = "Slack Incoming WebHook Base URL",
                    defaultValue = "https://hooks.slack.com/services",
                    scope=PropertyScope.Instance)
    private String webhook_base_url;

    @Password
    @PluginProperty(title = "WebHook Token",
                    description = "WebHook Token, like T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX",
                    scope=PropertyScope.Instance)
    private String webhook_token;

    @PluginProperty(title = "Slack Channel",
                    description = "Slack Channel, like #channel-name (optional)",
                    scope=PropertyScope.Instance)
    private String slack_channel;

    @PluginProperty(
            title = "Custom Template Path",
            description = "Directory containing external Slack message custom templates (.ftl). " +
                    "Defaults to ${rdeck.base}/libext/templates",
            defaultValue = "${rdeck.base}/libext/templates",
            scope = PropertyScope.Instance
    )
    private String slack_ext_message_template_path;

    @PluginProperty(
            title = "Custom Template",
            description = "Custom Freemarker Template to use for notification. Leave empty for default message",
            required = false
    )
    private String external_template;

    /**
     * Sends a message to a Slack room when a job notification event is raised by Rundeck.
     *
     * @param trigger name of job notification event causing notification
     * @param executionData job execution data
     * @param config plugin configuration
     * @throws SlackNotificationPluginException when any error occurs sending the Slack message
     * @return true, if the Slack API response indicates a message was successfully delivered to a chat room
     */
    public boolean postNotification(String trigger, Map executionData, Map config) {

        String ACTUAL_SLACK_TEMPLATE;
        try {
            final ClassTemplateLoader builtInTemplate =
                    new ClassTemplateLoader(SlackNotificationPlugin.class, "/templates");

            if (external_template != null && !external_template.isEmpty()) {
                // Resolve external templates path safely
                String resolvedTemplatePath = slack_ext_message_template_path;

                // Default when blank/null: ${rdeck.base}/libext/templates
                String rdeckBase = System.getProperty("rdeck.base", ".");
                if (resolvedTemplatePath == null || resolvedTemplatePath.trim().isEmpty()) {
                    resolvedTemplatePath = rdeckBase + File.separator + "libext" + File.separator + "templates";
                } else {
                    // Expand ${rdeck.base} and $RDECK_BASE if user typed them
                    String rdeckBaseEnv = System.getenv("RDECK_BASE");
                    if (rdeckBaseEnv == null || rdeckBaseEnv.trim().isEmpty()) {
                        rdeckBaseEnv = rdeckBase;
                    }
                    resolvedTemplatePath = resolvedTemplatePath
                            .replace("${rdeck.base}", rdeckBase)
                            .replace("$RDECK_BASE", rdeckBaseEnv);
                }

                // 3) Try external dir FIRST, then built-in as fallback
                try {
                    final FileTemplateLoader externalDir =
                            new FileTemplateLoader(new File(resolvedTemplatePath));
                    final MultiTemplateLoader mtl =
                            new MultiTemplateLoader(new TemplateLoader[]{externalDir, builtInTemplate});
                    FREEMARKER_CFG.setTemplateLoader(mtl);
                    ACTUAL_SLACK_TEMPLATE = external_template;
                    LOG.info("Slack: using external template dir: {}; template: {}%n",
                            resolvedTemplatePath, external_template);
                } catch (IOException | SecurityException e) {
                    LOG.warn(
                            "Slack: could not use external template path '{}' ({}). Falling back to built-in.%n",
                            resolvedTemplatePath, e.getMessage()
                    );
                    final MultiTemplateLoader mtl =
                            new MultiTemplateLoader(new TemplateLoader[]{builtInTemplate});
                    FREEMARKER_CFG.setTemplateLoader(mtl);
                    ACTUAL_SLACK_TEMPLATE = SLACK_MESSAGE_TEMPLATE;
                }

            } else {
                final MultiTemplateLoader mtl =
                        new MultiTemplateLoader(new TemplateLoader[]{builtInTemplate});
                FREEMARKER_CFG.setTemplateLoader(mtl);
                ACTUAL_SLACK_TEMPLATE = SLACK_MESSAGE_TEMPLATE;
            }

            FREEMARKER_CFG.setDefaultEncoding("UTF-8");
        } catch (Exception e) {
            // Last-resort fallback to built-in template
            LOG.error(
                    "Slack: unexpected error resolving templates ({}). Falling back to built-in.%n",
                    e.getMessage()
            );
            final ClassTemplateLoader builtInTemplate =
                    new ClassTemplateLoader(SlackNotificationPlugin.class, "/templates");
            final MultiTemplateLoader mtl =
                    new MultiTemplateLoader(new TemplateLoader[]{builtInTemplate});
            FREEMARKER_CFG.setTemplateLoader(mtl);
            ACTUAL_SLACK_TEMPLATE = SLACK_MESSAGE_TEMPLATE;
        }
        LOG.debug("Slack: trigger='{}', template='{}', channel='{}'", trigger, ACTUAL_SLACK_TEMPLATE, slack_channel);


        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_START,   new SlackNotificationData(ACTUAL_SLACK_TEMPLATE, SLACK_MESSAGE_COLOR_YELLOW));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_SUCCESS, new SlackNotificationData(ACTUAL_SLACK_TEMPLATE, SLACK_MESSAGE_COLOR_GREEN));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_FAILURE, new SlackNotificationData(ACTUAL_SLACK_TEMPLATE, SLACK_MESSAGE_COLOR_RED));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_AVERAGE, new SlackNotificationData(ACTUAL_SLACK_TEMPLATE, SLACK_MESSAGE_COLOR_YELLOW));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_ONRETRY, new SlackNotificationData(ACTUAL_SLACK_TEMPLATE, SLACK_MESSAGE_COLOR_YELLOW));


        try {
            FREEMARKER_CFG.setSetting(Configuration.CACHE_STORAGE_KEY, "strong:20, soft:250");
        }catch(Exception e){
            LOG.warn("Got and exception from Freemarker: {}", e.getMessage());
        }

        if (!TRIGGER_NOTIFICATION_DATA.containsKey(trigger)) {
            throw new IllegalArgumentException("Unknown trigger type: [" + trigger + "].");
        }

        if(this.webhook_base_url == null || this.webhook_base_url.isEmpty()
                || this.webhook_token == null || this.webhook_token.isEmpty()) {
            throw new IllegalArgumentException("URL or Token not set");
        }

        String webhook_url=this.webhook_base_url+"/"+this.webhook_token;

        String message = generateMessage(trigger, executionData, config, this.slack_channel);
        LOG.debug("Slack: posting to baseUrl='{}', token='{}'", webhook_base_url, maskToken(webhook_token));
        String slackResponse = invokeSlackAPIMethod(webhook_url, message);
        if (!"ok".equals(slackResponse)) {
            LOG.warn("Slack: non-ok response: {}", slackResponse);
        }
        String ms = "payload=" + this.urlEncode(message);

        if ("ok".equals(slackResponse)) {
            return true;
        } else {
            // Unfortunately there seems to be no way to obtain a reference to the plugin logger within notification plugins,
            // but throwing an exception will result in its message being logged.
            throw new SlackNotificationPluginException("Unknown status returned from Slack API: [" + slackResponse + "]." + "\n" + ms);
        }
    }

    private String generateMessage(String trigger, Map executionData, Map config, String channel) {
        String templateName = TRIGGER_NOTIFICATION_DATA.get(trigger).template;
        String color = TRIGGER_NOTIFICATION_DATA.get(trigger).color;

        HashMap<String, Object> model = new HashMap<String, Object>();
        model.put("trigger", trigger);
        model.put("color", color);
        model.put("executionData", executionData);
        model.put("config", config);
        if (channel != null && !channel.isEmpty()) {
            model.put("channel", channel);
        }

        StringWriter sw = new StringWriter();
        try {
            Template template = FREEMARKER_CFG.getTemplate(templateName);
            template.process(model,sw);

        } catch (IOException ioEx) {
            throw new SlackNotificationPluginException("Error loading Slack notification message template: [" + ioEx.getMessage() + "].", ioEx);
        } catch (TemplateException templateEx) {
            throw new SlackNotificationPluginException("Error merging Slack notification message template: [" + templateEx.getMessage() + "].", templateEx);
        }

        return sw.toString();

    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            throw new SlackNotificationPluginException("URL encoding error: [" + unsupportedEncodingException.getMessage() + "].", unsupportedEncodingException);
        }
    }

    protected String invokeSlackAPIMethod(String webhook_url, String message) {
        URL requestUrl = toURL(webhook_url);

        HttpURLConnection connection = null;
        InputStream responseStream = null;
        String body = "payload=" + this.urlEncode(message);
        try {
            connection = openConnection(requestUrl);
            putRequestStream(connection, body);
            responseStream = getResponseStream(connection);
            return getSlackResponse(responseStream);

        } finally {
            closeQuietly(responseStream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    protected URL toURL(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException malformedURLEx) {
            throw new SlackNotificationPluginException("Slack API URL is malformed: [" + malformedURLEx.getMessage() + "].", malformedURLEx);
        }
    }

    protected HttpURLConnection openConnection(URL requestUrl) {
        try {
            LOG.trace("Slack: opening connection to {}", requestUrl);
            return (HttpURLConnection) requestUrl.openConnection();
        } catch (IOException ioEx) {
            throw new SlackNotificationPluginException("Error opening connection to Slack URL: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private void putRequestStream(HttpURLConnection connection, String body) {
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("charset", "utf-8");
            connection.setDoInput(true);
            connection.setDoOutput(true);
            LOG.trace("Slack: sending POST with Content-Type={}", connection.getRequestProperty("Content-Type"));
            try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream())) {
                wr.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                wr.flush();
            }
        } catch (IOException ioEx) {
            throw new SlackNotificationPluginException(
                    "Error putting data to Slack URL: [" + ioEx.getMessage() + "].", ioEx
            );
        }
    }

    private InputStream getResponseStream(HttpURLConnection connection) {
        InputStream input = null;
        try {
            input = connection.getInputStream();
        } catch (IOException ioEx) {
            input = connection.getErrorStream();
        }
        LOG.trace("Slack: got response stream (errorStream? {})", input == connection.getErrorStream());

        return input;
    }

    private int getResponseCode(HttpURLConnection connection) {
        try {
            return connection.getResponseCode();
        } catch (IOException ioEx) {
            throw new SlackNotificationPluginException("Failed to obtain HTTP response: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private String getSlackResponse(InputStream responseStream) {
        try (Scanner s = new Scanner(responseStream, java.nio.charset.StandardCharsets.UTF_8.name())) {
            return s.useDelimiter("\\A").hasNext() ? s.next() : "";
        } catch (Exception ioEx) {
            throw new SlackNotificationPluginException(
                    "Error reading Slack API JSON response: [" + ioEx.getMessage() + "].", ioEx
            );
        }
    }

    private void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ioEx) {
                // ignore
            }
        }
    }

    private static class SlackNotificationData {
        private String template;
        private String color;
        public SlackNotificationData(String template, String color) {
            this.color = color;
            this.template = template;
        }
    }
    private String maskToken(String token) {
        if (token == null) return "null";
        // Keep first 6 chars of each path segment, mask the rest
        String[] parts = token.split("/");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() > 6) {
                parts[i] = p.substring(0, 6) + "…";
            }
        }
        return String.join("/", parts);
    }

}
