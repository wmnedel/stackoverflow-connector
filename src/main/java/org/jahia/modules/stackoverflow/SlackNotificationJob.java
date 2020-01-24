package org.jahia.modules.stackoverflow;
 
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.quartz.JobDataMap;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.jahia.settings.SettingsBean;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SlackNotificationJob extends QuartzJobBean {
    private final String SLACK_WEBHOOK_CFG_KEY = "org.jahia.modules.stackoverflow.slack.webhook";
    private final String MAX_STORED_QUESTIONS_CFG_KEY = "org.jahia.modules.stackoverflow.maxstoredquestions";

    private static List<String> savedOpenQuestions = new ArrayList<String>();

    private HttpConnector slackConnection;
    private HttpConnector stackConnection;

    private String slackWebHook;
    private int maxStoredQuestions;

    private static Logger logger = LoggerFactory.getLogger(SlackNotificationJob.class);
    SettingsBean settings = SettingsBean.getInstance();

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        logger.info("Executing SlackNotification job...");

        List<String> currentOpenQuestions = new ArrayList<String>();

        JobDataMap mergedJobDataMap = context.getMergedJobDataMap();
        String stackTag = (String) mergedJobDataMap.get("tag");

        slackWebHook = settings.getString(
                SLACK_WEBHOOK_CFG_KEY,
                "");

        maxStoredQuestions = Integer.parseInt(settings.getString(
                MAX_STORED_QUESTIONS_CFG_KEY,
                "40"));

        if (slackWebHook.isEmpty()) {
            logger.info("Please configure property "
                    + SLACK_WEBHOOK_CFG_KEY
                    + " in your jahia.custom.properties file. Results will not be sent to Slack channel");
            return;
        }

        slackConnection = new HttpConnector(
                "hooks.slack.com", "https", 443);

        stackConnection = new HttpConnector(
                "api.stackexchange.com", "https", 443);

        String questionResponse = stackConnection.executeGetRequest(
                "/questions/no-answers?order=desc&sort=activity&site=stackoverflow&tagged=" + stackTag);

        if (questionResponse == null) {
            logger.error("Error in request to stack exchange API: " + stackConnection.getErrorMessage());
            return;
        }

        JSONObject questionResponseJsonObj = null;

        try {
            questionResponseJsonObj = new JSONObject(questionResponse);
        } catch (Exception e) {
            logger.error("Error parsing JSON from stackexchange API " + stackConnection.getHostName(), e);
            return;
        }

        try {
            JSONArray questionItemsArray = (JSONArray) questionResponseJsonObj.get("items");

            for (int i = 0; i < questionItemsArray.length(); i++) {
                JSONObject questionObj = questionItemsArray.getJSONObject(i);
                String questionLink = questionObj.getString("link");
                String questionTitle = questionObj.getString("title");
                long questionCreatedDate = questionObj.getLong("creation_date");
                Date createdDate = new java.util.Date(questionCreatedDate*1000);
                JSONObject owner = questionObj.getJSONObject("owner");
                String ownerName = owner.getString("display_name");

                currentOpenQuestions.add("Unanswered question from Stack Overflow - " + questionTitle
                        + " - from " + ownerName
                        + " - created on " + createdDate.toString()
                        + ": " + questionLink);
            }

            /* Avoid giant objects */
            if (currentOpenQuestions.size() >= maxStoredQuestions) {
                savedOpenQuestions.clear();
                sendMessageSlack("WARNING! Too many Stack Overflow unanswered question: "
                        + currentOpenQuestions.size() + ". Sending it all at once...");
            }

            /* Remove questions already answered from saved list */
            for (String savedQuestion : savedOpenQuestions) {
                if (currentOpenQuestions.contains(savedQuestion) == false) {
                    savedOpenQuestions.remove(savedQuestion);
                }
            }

            for (String currentQuestion : currentOpenQuestions) {
                if (savedOpenQuestions.contains(currentQuestion)) {
                    logger.debug("Skipping question already sent: " + currentQuestion);
                    continue;
                } else {
                    savedOpenQuestions.add(currentQuestion);
                    sendMessageSlack(currentQuestion);
                }
            }
        } catch (JSONException e) {
            logger.error("Cannot execute SlackNotification job");
        }
    }

    private void sendMessageSlack(String message) {
        String jsonStr = "{\"text\":\"" + message + "\"}";
        HttpEntity postEntity = new StringEntity(jsonStr, ContentType.APPLICATION_JSON);

        String postResult = slackConnection.executePostRequest(slackWebHook, postEntity);

        if (postResult == null) {
            logger.error("Error in request to slack API: " + slackConnection.getErrorMessage());
        } else {
            logger.debug("Question sent to slack channel: " + message);
        }
    }
}
