/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2023 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.addon.authhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.extension.Extension;
import org.parosproxy.paros.extension.history.ExtensionHistory;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpHeaderField;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.view.View;
import org.zaproxy.addon.authhelper.BrowserBasedAuthenticationMethodType.BrowserBasedAuthenticationMethod;
import org.zaproxy.zap.authentication.AuthenticationCredentials;
import org.zaproxy.zap.authentication.AuthenticationMethod;
import org.zaproxy.zap.authentication.UsernamePasswordAuthenticationCredentials;
import org.zaproxy.zap.extension.selenium.BrowserHook;
import org.zaproxy.zap.extension.selenium.ExtensionSelenium;
import org.zaproxy.zap.extension.selenium.SeleniumScriptUtils;
import org.zaproxy.zap.extension.users.ExtensionUserManagement;
import org.zaproxy.zap.model.Context;
import org.zaproxy.zap.model.SessionStructure;
import org.zaproxy.zap.users.User;
import org.zaproxy.zap.utils.Pair;
import org.zaproxy.zap.utils.Stats;

public class AuthUtils {

    public static final String AUTH_NO_USER_FIELD_STATS = "stats.auth.browser.nouserfield";
    public static final String AUTH_NO_PASSWORD_FIELD_STATS = "stats.auth.browser.nopasswordfield";
    public static final String AUTH_SESSION_TOKEN_STATS_PREFIX = "stats.auth.sessiontoken.";

    public static final String[] HEADERS = {HttpHeader.AUTHORIZATION};
    public static final String[] JSON_IDS = {"accesstoken", "token"};

    private static final String BEARER_PREFIX = "bearer";
    private static int MAX_NUM_RECORDS_TO_CHECK = 200;

    public static final int TIME_TO_SLEEP_IN_MSECS = 100;

    private static final Logger LOGGER = LogManager.getLogger(AuthUtils.class);

    private static AuthenticationBrowserHook browserHook;

    private static long timeToWaitMs = TimeUnit.SECONDS.toMillis(5);

    private static Map<String, SessionToken> knownTokenMap = new HashMap<>();

    public static long getTimeToWaitMs() {
        return timeToWaitMs;
    }

    public static void setTimeToWaitMs(long timeToWaitMs) {
        AuthUtils.timeToWaitMs = timeToWaitMs;
    }

    public static long getWaitLoopCount() {
        return getTimeToWaitMs() / TIME_TO_SLEEP_IN_MSECS;
    }

    static WebElement getUserField(List<WebElement> inputElements) {
        List<WebElement> filteredList =
                inputElements.stream()
                        .filter(
                                elem ->
                                        "text".equalsIgnoreCase(elem.getAttribute("type"))
                                                || "email"
                                                        .equalsIgnoreCase(
                                                                elem.getAttribute("type")))
                        .collect(Collectors.toList());

        if (!filteredList.isEmpty()) {
            if (filteredList.size() > 1) {
                LOGGER.warn(
                        "Found more than one potential user field : {} , using {}",
                        filteredList,
                        filteredList.get(0));
            }
            return filteredList.get(0);
        }
        return null;
    }

    static WebElement getPasswordField(List<WebElement> inputElements) {
        for (WebElement element : inputElements) {
            if ("password".equalsIgnoreCase(element.getAttribute("type"))) {
                return element;
            }
        }
        return null;
    }

    /**
     * Authenticate as the given user, by filling in and submitting the login form
     *
     * @param wd the WebDriver controlling the browser
     * @param loginPageUrl the URL of the login page
     * @param username the username
     * @param password the password
     * @return true if the login form was successfully submitted.
     */
    public static boolean authenticateAsUser(
            WebDriver wd, String loginPageUrl, String username, String password, int waitInSecs) {
        wd.get(loginPageUrl);
        sleep(50);

        WebElement userField = null;
        WebElement pwdField = null;

        for (int i = 0; i < getWaitLoopCount(); i++) {
            List<WebElement> inputElements = wd.findElements(By.xpath("//input"));
            userField = getUserField(inputElements);
            pwdField = getPasswordField(inputElements);
            if (userField != null && pwdField != null) {
                break;
            }
            sleep(TIME_TO_SLEEP_IN_MSECS);
        }
        if (userField != null && pwdField != null) {
            userField.sendKeys(username);
            pwdField.sendKeys(password);
            pwdField.sendKeys(Keys.RETURN);

            AuthUtils.sleep(TimeUnit.SECONDS.toMillis(waitInSecs));

            return true;
        }
        if (userField == null) {
            incStatsCounter(loginPageUrl, AUTH_NO_USER_FIELD_STATS);
        }
        if (pwdField == null) {
            incStatsCounter(loginPageUrl, AUTH_NO_PASSWORD_FIELD_STATS);
        }
        return false;
    }

    public static void incStatsCounter(String url, String stat) {
        try {
            incStatsCounter(new URI(url, true), stat);
        } catch (URIException e) {
            // Ignore
        }
    }

    public static void incStatsCounter(URI uri, String stat) {
        try {
            Stats.incCounter(SessionStructure.getHostName(uri), stat);
        } catch (URIException e) {
            // Ignore
        }
    }

    public static void sleep(long millisecs) {
        try {
            Thread.sleep(millisecs);
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    /**
     * A temporary method to enable browser based authentication whenever a browser is launched via
     * selenium. The first context found configured with browser based authentication and a user
     * will be chosen.
     */
    public static void enableBrowserAuthentication() {
        if (browserHook != null) {
            throw new IllegalStateException("BrowserHook already enabled");
        }
        ExtensionUserManagement extUser = getExtension(ExtensionUserManagement.class);
        if (extUser == null) {
            throw new IllegalStateException("Failed to access ExtensionUserManagement");
        }

        for (Context context : Model.getSingleton().getSession().getContexts()) {
            AuthenticationMethod method = context.getAuthenticationMethod();
            if (method instanceof BrowserBasedAuthenticationMethod) {
                for (User user : extUser.getContextUserAuthManager(context.getId()).getUsers()) {
                    AuthenticationCredentials creds = user.getAuthenticationCredentials();
                    if (creds instanceof UsernamePasswordAuthenticationCredentials) {
                        browserHook = new AuthenticationBrowserHook(context, user);
                        break;
                    }
                }
            }
            if (browserHook != null) {
                break;
            }
        }
        if (browserHook != null) {
            getExtension(ExtensionSelenium.class).registerBrowserHook(browserHook);
        } else {
            throw new IllegalStateException("Failed to find suitable context and user");
        }
    }

    /**
     * A temporary method to enable browser based authentication whenever a browser is launched via
     * selenium.
     *
     * @param context the context, which must use browser based auth
     * @param userName the name of the user, which must be present in the context
     */
    public static void enableBrowserAuthentication(Context context, String userName) {
        if (browserHook != null) {
            throw new IllegalStateException("BrowserHook already enabled");
        }
        browserHook = new AuthenticationBrowserHook(context, userName);

        getExtension(ExtensionSelenium.class).registerBrowserHook(browserHook);
    }

    /** A temporary method for disabling browser based authentication. */
    public static void disableBrowserAuthentication() {
        if (browserHook != null) {
            getExtension(ExtensionSelenium.class).deregisterBrowserHook(browserHook);
            browserHook = null;
        }
    }

    /**
     * Returns all of the identified session token labels in the given message
     *
     * @param msg the message to extract the tokens from
     * @return all of the identified session token labels in the given message
     */
    public static Map<String, SessionToken> getResponseSessionTokens(HttpMessage msg) {
        Map<String, SessionToken> map = new HashMap<>();

        Arrays.stream(HEADERS)
                .forEach(
                        h -> {
                            for (String v : msg.getResponseHeader().getHeaderValues(h)) {
                                addToMap(map, new SessionToken(SessionToken.HEADER_TYPE, h, v));
                            }
                        });

        if (msg.getResponseHeader().isJson()) {
            Map<String, SessionToken> tokens = new HashMap<>();
            String responseData = msg.getResponseBody().toString();
            try {
                try {
                    AuthUtils.extractJsonTokens(JSONObject.fromObject(responseData), "", tokens);
                } catch (JSONException e) {
                    AuthUtils.extractJsonTokens(JSONArray.fromObject(responseData), "", tokens);
                }
                for (SessionToken token : tokens.values()) {
                    String tokenLc = token.getKey().toLowerCase(Locale.ROOT);
                    for (String id : JSON_IDS) {
                        if (tokenLc.equals(id) || tokenLc.endsWith("." + id)) {
                            addToMap(map, token);
                            break;
                        }
                    }
                }
            } catch (JSONException e) {
                LOGGER.warn(
                        "Unable to parse authentication response body from {} as JSON: {} ",
                        msg.getRequestHeader().getURI().toString(),
                        responseData,
                        e);
            }
        }
        if (!map.isEmpty()) {
            LOGGER.debug("Found session tokens in {} : {}", msg.getRequestHeader().getURI(), map);
            map.forEach(
                    (k, v) ->
                            AuthUtils.incStatsCounter(
                                    msg.getRequestHeader().getURI(),
                                    AUTH_SESSION_TOKEN_STATS_PREFIX + v.getKey()));
        }

        return map;
    }

    public static List<Pair<String, String>> getHeaderTokens(
            HttpMessage msg, List<SessionToken> tokens) {
        List<Pair<String, String>> list = new ArrayList<>();
        for (SessionToken token : tokens) {
            for (HttpHeaderField header : msg.getRequestHeader().getHeaders()) {
                if (header.getValue().contains(token.getValue())) {
                    String hv =
                            header.getValue()
                                    .replace(token.getValue(), "{%" + token.getToken() + "%}");
                    list.add(new Pair<String, String>(header.getName(), hv));
                }
            }
        }
        return list;
    }

    public static void logUserMessage(Level level, String str) {
        LOGGER.log(level, str);
        if (View.isInitialised()) {
            View.getSingleton().getOutputPanel().append(str + "\n");
        }
    }

    protected static void addToMap(Map<String, SessionToken> map, SessionToken token) {
        map.put(token.getToken(), token);
    }

    protected static Map<String, SessionToken> getAllTokens(HttpMessage msg) {
        Map<String, SessionToken> tokens = new HashMap<>();
        if (msg.getResponseHeader().isJson()) {
            // Extract json response data
            String responseData = msg.getResponseBody().toString();
            try {
                AuthUtils.extractJsonTokens(JSONObject.fromObject(responseData), "", tokens);
            } catch (JSONException e) {
                String url = msg.getRequestHeader().getURI().toString();
                logUserMessage(
                        Level.ERROR,
                        Constant.messages.getString(
                                "authhelper.session.method.header.error.json.parse",
                                url,
                                responseData));
            }
        }
        // Add response headers
        msg.getResponseHeader()
                .getHeaders()
                .forEach(
                        h ->
                                addToMap(
                                        tokens,
                                        new SessionToken(
                                                SessionToken.HEADER_TYPE,
                                                h.getName(),
                                                h.getValue())));

        // Add URL params
        msg.getUrlParams()
                .forEach(
                        p ->
                                addToMap(
                                        tokens,
                                        new SessionToken(
                                                SessionToken.URL_TYPE, p.getName(), p.getValue())));

        return tokens;
    }

    /**
     * Returns all of the identified session tokens in a request. Currently this method only looks
     * for Authorization headers - it will need to detect other potential locations including
     * cookies, but they are less reliable (as cookies are used for many purposes) so more checking
     * will be required.
     *
     * @param msg the message containing the request to check
     * @return all of the identified session tokens in the request.
     */
    public static Map<String, SessionToken> getRequestSessionTokens(HttpMessage msg) {
        Map<String, SessionToken> map = new HashMap<>();
        List<String> authHeaders = msg.getRequestHeader().getHeaderValues(HttpHeader.AUTHORIZATION);
        for (String header : authHeaders) {
            map.put(
                    header,
                    new SessionToken(SessionToken.HEADER_TYPE, HttpHeader.AUTHORIZATION, header));
        }
        return map;
    }

    static SessionManagementRequestDetails findSessionTokenSource(String token) {
        return findSessionTokenSource(token, -1);
    }

    static SessionManagementRequestDetails findSessionTokenSource(String token, int firstId) {
        ExtensionHistory extHist = AuthUtils.getExtension(ExtensionHistory.class);

        int spaceIndex = token.indexOf(" ");
        if (spaceIndex > 0 && token.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
            // Cope with "bearer " and "bearer: "
            token = token.substring(spaceIndex + 1);
        }
        String tokenf = token;
        int lastId = extHist.getLastHistoryId();
        if (firstId == -1) {
            firstId = Math.max(0, lastId - MAX_NUM_RECORDS_TO_CHECK);
        }

        LOGGER.debug("Searching for session token from {} down to {} ", lastId, firstId);

        for (int i = lastId; i >= firstId; i--) {
            HistoryReference hr = extHist.getHistoryReference(i);
            if (hr != null) {
                try {
                    HttpMessage msg = hr.getHttpMessage();
                    if (msg.getResponseHeader().isJson()) {
                        Optional<SessionToken> es =
                                AuthUtils.getAllTokens(msg).values().stream()
                                        .filter(v -> v.getValue().equals(tokenf))
                                        .findFirst();
                        if (es.isPresent()) {
                            AuthUtils.incStatsCounter(
                                    msg.getRequestHeader().getURI(),
                                    AuthUtils.AUTH_SESSION_TOKEN_STATS_PREFIX + es.get().getKey());
                            List<SessionToken> tokens = new ArrayList<>();
                            tokens.add(
                                    new SessionToken(
                                            SessionToken.JSON_TYPE,
                                            es.get().getKey(),
                                            es.get().getValue()));
                            return new SessionManagementRequestDetails(
                                    msg, tokens, Alert.CONFIDENCE_HIGH);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug(e.getMessage(), e);
                }
            }
        }
        return null;
    }

    public static void extractJsonTokens(
            JSONObject jsonObject, String parent, Map<String, SessionToken> tokens) {
        for (Object key : jsonObject.keySet()) {
            Object obj = jsonObject.get(key);
            extractJsonTokens(obj, normalisedKey(parent, (String) key), tokens);
        }
    }

    private static void extractJsonTokens(
            Object obj, String parent, Map<String, SessionToken> tokens) {
        if (obj instanceof JSONObject) {
            extractJsonTokens((JSONObject) obj, parent, tokens);
        } else if (obj instanceof JSONArray) {
            Object[] oa = ((JSONArray) obj).toArray();
            for (int i = 0; i < oa.length; i++) {
                extractJsonTokens(oa[i], parent + "[" + i + "]", tokens);
            }
        } else if (obj instanceof String) {
            addToMap(tokens, new SessionToken(SessionToken.JSON_TYPE, parent, (String) obj));
        }
    }

    private static String normalisedKey(String parent, String key) {
        return parent.isEmpty() ? key : parent + "." + key;
    }

    public static <T extends Extension> T getExtension(Class<T> clazz) {
        return Control.getSingleton().getExtensionLoader().getExtension(clazz);
    }

    public static void recordSessionToken(SessionToken token) {
        knownTokenMap.put(token.getValue(), token);
    }

    public static SessionToken getSessionToken(String value) {
        return knownTokenMap.get(value);
    }

    public static SessionToken containsSessionToken(String value) {
        Optional<Entry<String, SessionToken>> entry =
                knownTokenMap.entrySet().stream()
                        .filter(m -> value.contains(m.getKey()))
                        .findFirst();
        if (entry.isPresent()) {
            return entry.get().getValue();
        }
        return null;
    }

    public static void removeSessionToken(SessionToken token) {
        Optional<Entry<String, SessionToken>> entry =
                knownTokenMap.entrySet().stream()
                        .filter(m -> m.getValue().equals(token))
                        .findFirst();
        if (entry.isPresent()) {
            knownTokenMap.remove(entry.get().getKey());
        }
    }

    public static void clearSessionTokens() {
        knownTokenMap.clear();
    }

    static User getUser(Context context, String userName) {
        ExtensionUserManagement extUser = getExtension(ExtensionUserManagement.class);
        if (extUser == null) {
            throw new IllegalStateException("Failed to access ExtensionUserManagement");
        }
        Optional<User> oUser =
                extUser.getContextUserAuthManager(context.getId()).getUsers().stream()
                        .filter(u -> u.getName().equals(userName))
                        .findAny();
        if (oUser.isEmpty()) {
            throw new IllegalStateException("Failed to find user " + userName);
        }
        return oUser.get();
    }

    static class AuthenticationBrowserHook implements BrowserHook {

        private BrowserBasedAuthenticationMethod bbaMethod;
        private UsernamePasswordAuthenticationCredentials userCreds;

        AuthenticationBrowserHook(Context context, String userName) {
            this(context, getUser(context, userName));
        }

        AuthenticationBrowserHook(Context context, User user) {
            AuthenticationMethod method = context.getAuthenticationMethod();
            if (!(method instanceof BrowserBasedAuthenticationMethod)) {
                throw new IllegalStateException("Unsupported method " + method.getType().getName());
            }
            bbaMethod = (BrowserBasedAuthenticationMethod) method;

            AuthenticationCredentials creds = user.getAuthenticationCredentials();
            if (!(creds instanceof UsernamePasswordAuthenticationCredentials)) {
                throw new IllegalStateException(
                        "Unsupported user credentials type " + creds.getClass().getCanonicalName());
            }
            userCreds = (UsernamePasswordAuthenticationCredentials) creds;
        }

        @Override
        public void browserLaunched(SeleniumScriptUtils ssutils) {
            AuthUtils.authenticateAsUser(
                    ssutils.getWebDriver(),
                    bbaMethod.getLoginPageUrl(),
                    userCreds.getUsername(),
                    userCreds.getPassword(),
                    bbaMethod.getLoginPageWait());
        }
    }
}
