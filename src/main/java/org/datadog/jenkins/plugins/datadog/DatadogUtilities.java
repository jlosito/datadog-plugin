/*
The MIT License

Copyright (c) 2015-Present Datadog, Inc <opensource@datadoghq.com>
All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */

package org.datadog.jenkins.plugins.datadog;

import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.ProxyConfiguration;
import hudson.XmlFile;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.User;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringEscapeUtils;
import org.datadog.jenkins.plugins.datadog.model.CIGlobalTagsAction;
import org.datadog.jenkins.plugins.datadog.model.GitCommitAction;
import org.datadog.jenkins.plugins.datadog.model.GitRepositoryAction;
import org.datadog.jenkins.plugins.datadog.model.PipelineNodeInfoAction;
import org.datadog.jenkins.plugins.datadog.model.PipelineQueueInfoAction;
import org.datadog.jenkins.plugins.datadog.model.StageBreakdownAction;
import org.datadog.jenkins.plugins.datadog.steps.DatadogPipelineAction;
import org.datadog.jenkins.plugins.datadog.traces.BuildSpanAction;
import org.datadog.jenkins.plugins.datadog.traces.IsPipelineAction;
import org.datadog.jenkins.plugins.datadog.traces.StepDataAction;
import org.datadog.jenkins.plugins.datadog.traces.StepTraceDataAction;
import org.datadog.jenkins.plugins.datadog.util.SuppressFBWarnings;
import org.datadog.jenkins.plugins.datadog.util.TagsUtil;
import org.jenkinsci.plugins.pipeline.StageStatus;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.NotExecutedNodeAction;
import org.jenkinsci.plugins.workflow.actions.QueueItemAction;
import org.jenkinsci.plugins.workflow.actions.StageAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatadogUtilities {

    private static final Logger logger = Logger.getLogger(DatadogUtilities.class.getName());

    private static final Integer MAX_HOSTNAME_LEN = 255;
    private static final String DATE_FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

    /**
     * @return - The descriptor for the Datadog plugin. In this case the global configuration.
     */
    public static DatadogGlobalConfiguration getDatadogGlobalDescriptor() {
        try {
            return ExtensionList.lookupSingleton(DatadogGlobalConfiguration.class);
        } catch (IllegalStateException | NullPointerException e) {
            // It can only throw such an exception when running tests
            return null;
        }
    }

    /**
     * @param r - Current build.
     * @return - The configured {@link DatadogJobProperty}. Null if not there
     */
    public static DatadogJobProperty getDatadogJobProperties(@Nonnull Run r) {
        try {
            return (DatadogJobProperty) r.getParent().getProperty(DatadogJobProperty.class);
        } catch(NullPointerException e){
            // It can only throw a NullPointerException when running tests
            return null;
        }
    }

    /**
     * Builds extraTags if any are configured in the Job.
     *
     * @param run      - Current build
     * @param envVars  - Environment Variables
     * @return A {@link HashMap} containing the key,value pairs of tags if any.
     */
    public static Map<String, Set<String>> getBuildTags(Run run, EnvVars envVars) {
        Map<String, Set<String>> result = new HashMap<>();
        if(run == null){
            return result;
        }
        String jobName;
        try {
            jobName = run.getParent().getFullName();
        } catch (NullPointerException e){
            // It can only throw a NullPointerException when running tests
            return result;
        }
        final DatadogGlobalConfiguration datadogGlobalConfig = getDatadogGlobalDescriptor();
        if (datadogGlobalConfig == null){
            return result;
        }
        final String globalJobTags = datadogGlobalConfig.getGlobalJobTags();
        String workspaceTagFile = null;
        String tagProperties = null;
        final DatadogJobProperty property = DatadogUtilities.getDatadogJobProperties(run);
        if(property != null){
            workspaceTagFile = property.readTagFile(run);
            tagProperties = property.getTagProperties();
        }

        // If job doesn't have a workspace Tag File set we check if one has been defined globally
        if(workspaceTagFile == null){
            workspaceTagFile = datadogGlobalConfig.getGlobalTagFile();
        }
        if (workspaceTagFile != null) {
            result = TagsUtil.merge(result, computeTagListFromVarList(envVars, workspaceTagFile));
        }
        result = TagsUtil.merge(result, computeTagListFromVarList(envVars, tagProperties));

        result = TagsUtil.merge(result, getTagsFromGlobalJobTags(jobName, globalJobTags));

        result = TagsUtil.merge(result, getTagsFromPipelineAction(run));

        return result;
    }

    /**
     * Pipeline extraTags if any are configured in the Job from DatadogPipelineAction.
     *
     * @param run      - Current build
     * @return A {@link HashMap} containing the key,value pairs of tags if any.
     */
    public static Map<String, Set<String>> getTagsFromPipelineAction(Run run) {
        // pipeline defined tags
        final Map<String, Set<String>> result = new HashMap<>();
        DatadogPipelineAction action = run.getAction(DatadogPipelineAction.class);
        if(action != null) {
            List<String> pipelineTags = action.getTags();
            for (int i = 0; i < pipelineTags.size(); i++) {
                String[] tagItem = pipelineTags.get(i).replaceAll(" ", "").split(":", 2);
                if(tagItem.length == 2) {
                    String tagName = tagItem[0];
                    String tagValue = tagItem[1];
                    Set<String> tagValues = result.containsKey(tagName) ? result.get(tagName) : new HashSet<String>();
                    tagValues.add(tagValue.toLowerCase());
                    result.put(tagName, tagValues);
                } else if(tagItem.length == 1) {
                    String tagName = tagItem[0];
                    Set<String> tagValues = result.containsKey(tagName) ? result.get(tagName) : new HashSet<String>();
                    tagValues.add(""); // no values
                    result.put(tagName, tagValues);
                } else {
                    logger.fine(String.format("Ignoring the tag %s. It is empty.", tagItem));
                }
            }
        }

        return result;
    }

    /**
     * Checks if a jobName is excluded, included, or neither.
     *
     * @param jobName - A String containing the name of some job.
     * @return a boolean to signify if the jobName is or is not excluded or included.
     */
    public static boolean isJobTracked(final String jobName) {
        return !isJobExcluded(jobName) && isJobIncluded(jobName);
    }

    /**
     * Human-friendly OS name. Commons return values are windows, linux, mac, sunos, freebsd
     *
     * @return a String with a human-friendly OS name
     */
    private static String getOS() {
        String out = System.getProperty("os.name");
        String os = out.split(" ")[0];
        return os.toLowerCase();
    }

    /**
     * Retrieve the list of tags from the globalJobTagsLines param for jobName
     *
     * @param jobName - JobName to retrieve and process tags from.
     * @param globalJobTags - globalJobTags string
     * @return - A Map of values containing the key and values of each Datadog tag to apply to the metric/event
     */
    private static Map<String, Set<String>> getTagsFromGlobalJobTags(String jobName, final String globalJobTags) {
        Map<String, Set<String>> tags = new HashMap<>();
        List<String> globalJobTagsLines = linesToList(globalJobTags);
        logger.fine(String.format("The list of Global Job Tags are: %s", globalJobTagsLines));

        // Each jobInfo is a list containing one regex, and a variable number of tags
        for (String globalTagsLine : globalJobTagsLines) {
            List<String> jobInfo = cstrToList(globalTagsLine);
            if (jobInfo.isEmpty()) {
                continue;
            }
            Pattern jobNamePattern = Pattern.compile(jobInfo.get(0));
            Matcher jobNameMatcher = jobNamePattern.matcher(jobName);
            if (jobNameMatcher.matches()) {
                for (int i = 1; i < jobInfo.size(); i++) {
                    String[] tagItem = jobInfo.get(i).replaceAll(" ", "").split(":", 2);
                    if (tagItem.length == 2) {
                        String tagName = tagItem[0];
                        String tagValue = tagItem[1];
                        // Fills regex group values from the regex job name to tag values
                        // eg: (.*?)-job, owner:$1 or (.*?)-job
                        // Also fills environment variables defined in the tag value.
                        // eg: (.*?)-job, custom_tag:$ENV_VAR
                        if (tagValue.startsWith("$")) {
                            try {
                                tagValue = jobNameMatcher.group(Character.getNumericValue(tagValue.charAt(1)));
                            } catch (IndexOutOfBoundsException e) {

                                String tagNameEnvVar = tagValue.substring(1);
                                if (EnvVars.masterEnvVars.containsKey(tagNameEnvVar)){
                                    tagValue = EnvVars.masterEnvVars.get(tagNameEnvVar);
                                }
                                else {
                                    logger.fine(String.format(
                                        "Specified a capture group or environment variable that doesn't exist, not applying tag: %s Exception: %s",
                                        Arrays.toString(tagItem), e));
                                }
                            }
                        }
                        Set<String> tagValues = tags.containsKey(tagName) ? tags.get(tagName) : new HashSet<String>();
                        tagValues.add(tagValue.toLowerCase());
                        tags.put(tagName, tagValues);
                    } else if(tagItem.length == 1) {
                        String tagName = tagItem[0];
                        Set<String> tagValues = tags.containsKey(tagName) ? tags.get(tagName) : new HashSet<String>();
                        tagValues.add(""); // no values
                        tags.put(tagName, tagValues);
                    } else {
                        logger.fine(String.format("Ignoring the tag %s. It is empty.", tagItem));
                    }
                }
            }
        }

        return tags;
    }

    /**
     * Getter function for the globalTags global configuration, containing
     * a comma-separated list of tags that should be applied everywhere.
     *
     * @return a map containing the globalTags global configuration.
     */
    public static Map<String, Set<String>> getTagsFromGlobalTags() {
        Map<String, Set<String>> tags = new HashMap<>();

        final DatadogGlobalConfiguration datadogGlobalConfig = getDatadogGlobalDescriptor();
        if (datadogGlobalConfig == null){
            return tags;
        }

        final String globalTags = datadogGlobalConfig.getGlobalTags();
        List<String> globalTagsLines = DatadogUtilities.linesToList(globalTags);

        for (String globalTagsLine : globalTagsLines) {
            List<String> tagList = DatadogUtilities.cstrToList(globalTagsLine);
            if (tagList.isEmpty()) {
                continue;
            }

            for (int i = 0; i < tagList.size(); i++) {
                String[] tagItem = tagList.get(i).replaceAll(" ", "").split(":", 2);
                if(tagItem.length == 2) {
                    String tagName = tagItem[0];
                    String tagValue = tagItem[1];
                    Set<String> tagValues = tags.containsKey(tagName) ? tags.get(tagName) : new HashSet<String>();
                    // Apply environment variables if specified. ie (custom_tag:$ENV_VAR)
                    if (tagValue.startsWith("$") && EnvVars.masterEnvVars.containsKey(tagValue.substring(1))){
                        tagValue = EnvVars.masterEnvVars.get(tagValue.substring(1));
                    }
                    else {
                        logger.fine(String.format(
                            "Specified an environment variable that doesn't exist, not applying tag: %s",
                            Arrays.toString(tagItem)));
                    }
                    tagValues.add(tagValue.toLowerCase());
                    tags.put(tagName, tagValues);
                } else if(tagItem.length == 1) {
                    String tagName = tagItem[0];
                    Set<String> tagValues = tags.containsKey(tagName) ? tags.get(tagName) : new HashSet<String>();
                    tagValues.add(""); // no values
                    tags.put(tagName, tagValues);
                } else {
                    logger.fine(String.format("Ignoring the tag %s. It is empty.", tagItem));
                }
            }
        }

        return tags;
    }

    /**
     * Checks if a jobName is excluded.
     *
     * @param jobName - A String containing the name of some job.
     * @return a boolean to signify if the jobName is or is not excluded.
     */
    private static boolean isJobExcluded(final String jobName) {
        final DatadogGlobalConfiguration datadogGlobalConfig = getDatadogGlobalDescriptor();
        if (datadogGlobalConfig == null){
            return false;
        }
        final String excludedProp = datadogGlobalConfig.getExcluded();
        List<String> excluded = cstrToList(excludedProp);
        for (String excludedJob : excluded){
            Pattern excludedJobPattern = Pattern.compile(excludedJob);
            Matcher jobNameMatcher = excludedJobPattern.matcher(jobName);
            if (jobNameMatcher.matches()) {
                return true;
            }
        }
        return false;

    }

    /**
     * Checks if a jobName is included.
     *
     * @param jobName - A String containing the name of some job.
     * @return a boolean to signify if the jobName is or is not included.
     */
    private static boolean isJobIncluded(final String jobName) {
        final DatadogGlobalConfiguration datadogGlobalConfig = getDatadogGlobalDescriptor();
        if (datadogGlobalConfig == null){
            return true;
        }
        final String includedProp = datadogGlobalConfig.getIncluded();
        final List<String> included = cstrToList(includedProp);
        for (String includedJob : included){
            Pattern includedJobPattern = Pattern.compile(includedJob);
            Matcher jobNameMatcher = includedJobPattern.matcher(jobName);
            if (jobNameMatcher.matches()) {
                return true;
            }
        }
        return included.isEmpty();
    }

    /**
     * Converts a Comma Separated List into a List Object
     *
     * @param str - A String containing a comma separated list of items.
     * @return a String List with all items transform with trim and lower case
     */
    public static List<String> cstrToList(final String str) {
        return convertRegexStringToList(str, ",");
    }

    /**
     * Converts a string List into a List Object
     *
     * @param str - A String containing a comma separated list of items.
     * @return a String List with all items
     */
    public static List<String> linesToList(final String str) {
        return convertRegexStringToList(str, "\\r?\\n");
    }

    /**
     * Converts a string List into a List Object
     *
     * @param str - A String containing a comma separated list of items.
     * @param regex - Regex to use to split the string list
     * @return a String List with all items
     */
    private static List<String> convertRegexStringToList(final String str, String regex) {
        List<String> result = new ArrayList<>();
        if (str != null && str.length() != 0) {
            for (String item : str.trim().split(regex)) {
                if (!item.isEmpty()) {
                    result.add(item.trim());
                }
            }
        }
        return result;
    }

    public static Map<String, Set<String>> computeTagListFromVarList(EnvVars envVars, final String varList) {
        HashMap<String, Set<String>> result = new HashMap<>();
        List<String> rawTagList = linesToList(varList);
        for (String tagLine : rawTagList) {
            List<String> tagList = DatadogUtilities.cstrToList(tagLine);
            if (tagList.isEmpty()) {
                continue;
            }
            for (int i = 0; i < tagList.size(); i++) {
                String tag = tagList.get(i).replaceAll(" ", "");
                String[] expanded = envVars.expand(tag).split("=", 2);
                if (expanded.length == 2) {
                    String name = expanded[0];
                    String value = expanded[1];
                    Set<String> values = result.containsKey(name) ? result.get(name) : new HashSet<String>();
                    values.add(value);
                    result.put(name, values);
                    logger.fine(String.format("Emitted tag %s:%s", name, value));
                } else if(expanded.length == 1) {
                    String name = expanded[0];
                    Set<String> values = result.containsKey(name) ? result.get(name) : new HashSet<String>();
                    values.add(""); // no values
                    result.put(name, values);
                } else {
                    logger.fine(String.format("Ignoring the tag %s. It is empty.", tag));
                }
            }
        }
        return result;
    }

    /**
     * Getter function to return either the saved hostname global configuration,
     * or the hostname that is set in the Jenkins host itself. Returns null if no
     * valid hostname is found.
     *
     * Tries, in order:
     * Jenkins configuration
     * Jenkins hostname environment variable
     * Unix hostname via `/bin/hostname -f`
     * Localhost hostname
     *
     * @param envVars - The Jenkins environment variables
     * @return a human readable String for the hostname.
     */
    public static String getHostname(EnvVars envVars) {
        String[] UNIX_OS = {"mac", "linux", "freebsd", "sunos"};

        // Check hostname configuration from Jenkins
        String hostname = null;
        try {
            hostname = getDatadogGlobalDescriptor().getHostname();
        } catch (NullPointerException e){
            // noop
        }
        if (isValidHostname(hostname)) {
            logger.fine("Using hostname set in 'Manage Plugins'. Hostname: " + hostname);
            return hostname;
        }

        // Check hostname using jenkins env variables
        if (envVars != null) {
            hostname = envVars.get("HOSTNAME");
        }
        if (isValidHostname(hostname)) {
            logger.fine("Using hostname found in $HOSTNAME host environment variable. Hostname: " + hostname);
            return hostname;
        }

        // Check OS specific unix commands
        String os = getOS();
        if (Arrays.asList(UNIX_OS).contains(os)) {
            // Attempt to grab unix hostname
            try {
                String[] cmd = {"/bin/hostname", "-f"};
                Process proc = Runtime.getRuntime().exec(cmd);
                InputStream in = proc.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line);
                }
                reader.close();

                hostname = out.toString();
            } catch (Exception e) {
                severe(logger, e, "Failed to obtain UNIX hostname");
            }

            // Check hostname
            if (isValidHostname(hostname)) {
                logger.fine(String.format("Using unix hostname found via `/bin/hostname -f`. Hostname: %s",
                        hostname));
                return hostname;
            }
        }

        // Check localhost hostname
        try {
            hostname = Inet4Address.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            logger.fine(String.format("Unknown hostname error received for localhost. Error: %s", e));
        }
        if (isValidHostname(hostname)) {
            logger.fine(String.format("Using hostname found via "
                    + "Inet4Address.getLocalHost().getHostName()."
                    + " Hostname: %s", hostname));
            return hostname;
        }

        // Never found the hostname
        if (hostname == null || "".equals(hostname)) {
            logger.warning("Unable to reliably determine host name. You can define one in "
                    + "the 'Manage Plugins' section under the 'Datadog Plugin' section.");
        }

        return null;
    }

    /**
     * Validator function to ensure that the hostname is valid. Also, fails on
     * empty String.
     *
     * @param hostname - A String object containing the name of a host.
     * @return a boolean representing the validity of the hostname
     */
    public static Boolean isValidHostname(String hostname) {
        if (hostname == null) {
            return false;
        }

        String[] localHosts = {"localhost", "localhost.localdomain",
                "localhost6.localdomain6", "ip6-localhost"};
        String VALID_HOSTNAME_RFC_1123_PATTERN = "^(([a-zA-Z0-9]|"
                + "[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*"
                + "([A-Za-z0-9]|"
                + "[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$";
        String host = hostname.toLowerCase();

        // Check if hostname is local
        if (Arrays.asList(localHosts).contains(host)) {
            logger.fine(String.format("Hostname: %s is local", hostname));
            return false;
        }

        // Ensure proper length
        if (hostname.length() > MAX_HOSTNAME_LEN) {
            logger.fine(String.format("Hostname: %s is too long (max length is %s characters)",
                    hostname, MAX_HOSTNAME_LEN));
            return false;
        }

        // Check compliance with RFC 1123
        Pattern r = Pattern.compile(VALID_HOSTNAME_RFC_1123_PATTERN);
        Matcher m = r.matcher(hostname);

        // Final check: Hostname matches RFC1123?
        return m.find();
    }

    public static Map<String, Set<String>> getComputerTags(Computer computer) {
        Set<LabelAtom> labels = null;
        try {
            labels = computer.getNode().getAssignedLabels();
        } catch (NullPointerException e){
            logger.fine("Could not retrieve labels");
        }
        String nodeHostname = null;
        try {
            nodeHostname = computer.getHostName();
        } catch (IOException | InterruptedException e) {
            logger.fine("Could not retrieve hostname");
        }
        String nodeName = getNodeName(computer);
        Map<String, Set<String>> result = new HashMap<>();
        Set<String> nodeNameValues = new HashSet<>();
        nodeNameValues.add(nodeName);
        result.put("node_name", nodeNameValues);
        if(nodeHostname != null){
            Set<String> nodeHostnameValues = new HashSet<>();
            nodeHostnameValues.add(nodeHostname);
            result.put("node_hostname", nodeHostnameValues);
        }
        if(labels != null){
            Set<String> nodeLabelsValues = new HashSet<>();
            for (LabelAtom label: labels){
                nodeLabelsValues.add(label.getName());
            }
            result.put("node_label", nodeLabelsValues);
        }

        return result;
    }

    public static String getNodeName(Computer computer){
        if(computer == null){
            return null;
        }
        if (computer instanceof Jenkins.MasterComputer) {
            return "master";
        } else {
            return computer.getName();
        }
    }


    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public static Set<String> getNodeLabels(Computer computer) {
        Set<LabelAtom> labels;
        try {
            labels = computer.getNode().getAssignedLabels();
        } catch (Exception e){
            logger.fine("Could not retrieve labels: " + e.getMessage());
            return Collections.emptySet();
        }

        final Set<String> labelsStr = new HashSet<>();
        for(final LabelAtom label : labels) {
            labelsStr.add(label.getName());
        }

        return labelsStr;
    }

    public static String getUserId() {
        User user = User.current();
        if (user == null) {
            return "anonymous";
        } else {
            return user.getId();
        }
    }

    public static String getItemName(Item item) {
        if (item == null) {
            return "unknown";
        }
        return item.getName();
    }

    public static Long getRunStartTimeInMillis(Run run) {
        // getStartTimeInMillis wrapper in order to mock it in unit tests
        return run.getStartTimeInMillis();
    }

    public static long currentTimeMillis(){
        // This method exist so we can mock System.currentTimeMillis in unit tests
        return System.currentTimeMillis();
    }

    public static String getFileName(XmlFile file) {
        if(file == null || file.getFile() == null || file.getFile().getName().isEmpty()){
            return "unknown";
        } else {
            return file.getFile().getName();
        }
    }

    public static String getJenkinsUrl() {
        Jenkins jenkins = Jenkins.getInstance();
        if(jenkins == null){
            return "unknown";
        }else{
            try {
                return jenkins.getRootUrl();
            }catch(Exception e){
                return "unknown";
            }
        }
    }

    public static String getResultTag(@Nonnull FlowNode node) {
        if (StageStatus.isSkippedStage(node)) {
            return  "SKIPPED";
        }
        if (node instanceof BlockEndNode) {
            BlockStartNode startNode = ((BlockEndNode) node).getStartNode();
            if (StageStatus.isSkippedStage(startNode)) {
                return  "SKIPPED";
            }
        }
        ErrorAction error = node.getError();
        if (error != null) {
            return "ERROR";
        }
        WarningAction warningAction = node.getPersistentAction(WarningAction.class);
        if (warningAction != null) {
            Result result = warningAction.getResult();
            // Result could be SUCCESS, NOT_BUILT, FAILURE, etc https://javadoc.jenkins-ci.org/hudson/model/Result.html
            return result.toString();
        }
        // Other possibilities are queued, launched, unknown: https://javadoc.jenkins.io/plugin/workflow-api/org/jenkinsci/plugins/workflow/actions/QueueItemAction.QueueState.html
        if (QueueItemAction.getNodeState(node) == QueueItemAction.QueueState.CANCELLED) {
            return "CANCELED";
        }
        FlowExecution exec = node.getExecution();
        if ((exec != null && exec.isComplete()) || NotExecutedNodeAction.isExecuted(node)) {
            return "SUCCESS";
        }
        return "UNKNOWN";
    }

    /**
     * Returns true if a {@code FlowNode} is a Stage node.
     * @param flowNode the flow node to evaluate
     * @return flag indicating if a flowNode is a Stage node.
     */
    public static boolean isStageNode(BlockStartNode flowNode) {
        if (flowNode == null) {
            return false;
        }
        if (flowNode.getAction(StageAction.class) != null) {
            // Legacy style stage block without a body
            // https://groups.google.com/g/jenkinsci-users/c/MIVk-44cUcA
            return true;
        }
        if (flowNode.getAction(ThreadNameAction.class) != null) {
            // TODO comment
            return false;
        }
        return flowNode.getAction(LabelAction.class) != null;
    }

    /**
     * Returns true if a {@code FlowNode} is a Pipeline node.
     * @param flowNode the flow node to evaluate
     * @return flag indicating if a flowNode is a Pipeline node.
     */
    public static boolean isPipelineNode(FlowNode flowNode) {
        return flowNode instanceof FlowEndNode;
    }

    /**
     * Returns a normalize result for traces.
     * @param result (success, failure, error, aborted, not_build, canceled, skipped, unknown)
     * @return the normalized result for the traces based on the jenkins result
     */
    public static String getNormalizedResultForTraces(@Nonnull String result) {
        switch (result.toLowerCase()){
            case "failure":
                return "error";
            case "aborted":
            case "not_built":
                return "canceled";
            default:
                return result.toLowerCase();
        }
    }

    /**
     * Returns a normalized result for traces.
     * NOTE: This is very similar to the above "getNormalizedResultForTraces", but with the difference
     * that this will not return "unstable", which isn't a valid status on the Webhooks API.
     * @param result (success, failure, error, aborted, not_build, canceled, skipped, unknown)
     * @return the normalized result for the traces based on the jenkins result
     */
    public static String statusFromResult(@Nonnull String result) {
        switch (result.toLowerCase()){
            case "failure":
                return "error";
            case "aborted":
                return "canceled";
            case "not_built":
                return "skipped";
            case "unstable": // has non-fatal errors
                return "success";
            default:
                return result.toLowerCase();
        }
    }

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH")
    public static void severe(Logger logger, Throwable e, String message){
        if(message == null){
            message = e != null ? "An unexpected error occurred": "";
        }
        if(!message.isEmpty()) {
            logger.severe(message);
        }
        if(e != null) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.info(message + ": " + sw.toString());
        }
    }

    public static int toInt(boolean b) {
        return b ? 1 : 0;
    }


    /**
     * Returns a date as String in the ISO8601 format
     * @param date the date object to transform
     * @return date as String in the ISO8601 format
     */
    public static String toISO8601(Date date) {
        if(date == null) {
            return null;
        }

        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_ISO8601);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }

    /**
     * Returns a JSON array string based on the set.
     * @param set the set to transform into a JSON
     * @return json array string
     */
    public static String toJson(final Set<String> set) {
        if(set == null || set.isEmpty()) {
            return null;
        }

        // We want to avoid using Json libraries cause
        // may cause incompatibilities on different Jenkins versions.
        final StringBuilder sb = new StringBuilder();
        sb.append("[");
        int index = 1;
        for(String val : set) {
            final String escapedValue = StringEscapeUtils.escapeJavaScript(val);
            sb.append("\"").append(escapedValue).append("\"");
            if(index < set.size()) {
                sb.append(",");
            }
            index += 1;
        }
        sb.append("]");

        return sb.toString();
    }

    /**
     * Returns a JSON object string based on the map.
     * @param map the map to transform into a JSON
     * @return json object string
     */
    public static String toJson(final Map<String, String> map) {
        if(map == null || map.isEmpty()) {
            return null;
        }

        // We want to avoid using Json libraries cause
        // may cause incompatibilities on different Jenkins versions.
        final StringBuilder sb = new StringBuilder();
        sb.append("{");
        int index = 1;
        for(Map.Entry<String, String> entry : map.entrySet()) {
            final String escapedKey = StringEscapeUtils.escapeJavaScript(entry.getKey());
            final String escapedValue = StringEscapeUtils.escapeJavaScript(entry.getValue());
            sb.append(String.format("\"%s\":\"%s\"", escapedKey, escapedValue));
            if(index < map.size()) {
                sb.append(",");
            }
            index += 1;
        }
        sb.append("}");

        return sb.toString();
    }

    /**
     * Removes all actions related to traces for Jenkins pipelines.
     * @param run the current run.
     */
    public static void cleanUpTraceActions(final Run<?, ?> run) {
        if(run != null) {
            run.removeActions(BuildSpanAction.class);
            run.removeActions(StepDataAction.class);
            run.removeActions(CIGlobalTagsAction.class);
            run.removeActions(GitCommitAction.class);
            run.removeActions(GitRepositoryAction.class);
            run.removeActions(PipelineNodeInfoAction.class);
            run.removeActions(PipelineQueueInfoAction.class);
            run.removeActions(StageBreakdownAction.class);
            run.removeActions(IsPipelineAction.class);
            run.removeActions(StepTraceDataAction.class);
        }
    }

    /**
     * Check if a run is from a Jenkins pipeline.
     * This action is added if the run is based on FlowNodes.
     * @param run the current run.
     * @return true if is a Jenkins pipeline.
     */
    public static boolean isPipeline(final Run<?, ?> run) {
        return run != null && run.getAction(IsPipelineAction.class) != null;
    }

    /**
     * Returns an HTTP url connection given a url object. Supports jenkins configured proxy.
     *
     * @param url - a URL object containing the URL to open a connection to.
     * @param timeoutMS - the timeout in MS
     * @return a HttpURLConnection object.
     * @throws IOException if HttpURLConnection fails to open connection
     */
    public static HttpURLConnection getHttpURLConnection(final URL url, final int timeoutMS) throws IOException {
        HttpURLConnection conn = null;
        ProxyConfiguration proxyConfig = null;

        Jenkins jenkins = Jenkins.getInstance();
        if(jenkins != null){
            proxyConfig = jenkins.proxy;
        }

        /* Attempt to use proxy */
        if (proxyConfig != null) {
            Proxy proxy = proxyConfig.createProxy(url.getHost());
            if (proxy != null && proxy.type() == Proxy.Type.HTTP) {
                logger.fine("Attempting to use the Jenkins proxy configuration");
                conn = (HttpURLConnection) url.openConnection(proxy);
            }
        } else {
            logger.fine("Jenkins proxy configuration not found");
        }

        /* If proxy fails, use HttpURLConnection */
        if (conn == null) {
            conn = (HttpURLConnection) url.openConnection();
            logger.fine("Using HttpURLConnection, without proxy");
        }

        conn.setConnectTimeout(timeoutMS);
        conn.setReadTimeout(timeoutMS);

        return conn;
    }

    /**
     * Returns an HTTP URL
     * @param hostname - the Hostname
     * @param port - the port to use
     * @param path - the path
     * @return the HTTP URL
     * @throws MalformedURLException if the URL is not in a valid format
     */
    public static URL buildHttpURL(final String hostname, final Integer port, final String path) throws MalformedURLException {
        return new URL(String.format("http://%s:%d"+path, hostname, port));
    }
}
