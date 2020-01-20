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

import java.util.Date;


public class SlackNotificationJob extends QuartzJobBean {
    private static Logger logger = LoggerFactory.getLogger(SlackNotificationJob.class);

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        logger.info("Executing SlackNotification job...");

        JobDataMap mergedJobDataMap = context.getMergedJobDataMap();
        String slackUrl = (String) mergedJobDataMap.get("slackUrl");
        String stackTag = (String) mergedJobDataMap.get("tag");
        String stackApiUrl = (String) mergedJobDataMap.get("stackApiUrl");
        logger.debug("slackUrl=" + slackUrl);
        logger.debug("stackTag=" + stackTag);
        logger.debug("stackApiUrl=" + stackApiUrl);

        HttpConnector slackConnection = new HttpConnector(
                "hooks.slack.com", "https", 443, "guest", "guest");

        HttpConnector stackConnection = new HttpConnector(
                "api.stackexchange.com", "https", 443, "guest", "guest");
        String questionResponse = stackConnection.executeGetRequest("/questions/no-answers?order=desc&sort=activity&tagged=jahia&site=stackoverflow");

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
                int questionAnswerCount = questionObj.getInt("answer_count");
                String questionLink = questionObj.getString("link");
                String questionTitle = questionObj.getString("title");
                long questionCreatedDate = questionObj.getLong("creation_date");
                Date createdDate = new java.util.Date(questionCreatedDate*1000);
                JSONObject owner = questionObj.getJSONObject("owner");
                String ownerName = owner.getString("display_name");

                if (questionAnswerCount > 0) {
                    logger.debug("Answered question - " + questionTitle
                            + " - from " + ownerName
                            + " - created on " + createdDate.toString()
                            + ": " + questionLink);
                    continue;
                }

                String toLog = "Unanswered question - " + questionTitle
                        + " - from " + ownerName
                        + " - created on " + createdDate.toString()
                        + ": " + questionLink;

                logger.info(toLog);

                String jsonStr = "{\"text\":\"" + toLog + "\"}";
                HttpEntity postEntity = new StringEntity(jsonStr, ContentType.APPLICATION_JSON);

                String postResult = slackConnection.executePostRequest("/services/T04CA9GN2/BSW277YGM/T4sjOwH0BDuDa5xaCkfcuo0t", postEntity);

                if (postResult == null) {
                    logger.error("Error in request to stack exchange API: " + slackConnection.getErrorMessage());
                    return;
                }
            }

        } catch (JSONException e) {
            logger.error("Cannot execute SlackNotification job");
        }
    }
}
