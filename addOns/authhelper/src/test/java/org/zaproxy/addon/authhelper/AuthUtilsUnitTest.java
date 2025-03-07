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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.httpclient.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.parosproxy.paros.network.HttpResponseHeader;
import org.zaproxy.zap.network.HttpRequestBody;
import org.zaproxy.zap.network.HttpResponseBody;
import org.zaproxy.zap.testutils.TestUtils;
import org.zaproxy.zap.utils.Pair;

class AuthUtilsUnitTest extends TestUtils {

    @BeforeEach
    void setUp() throws Exception {
        mockMessages(new ExtensionAuthhelper());
        AuthUtils.clearSessionTokens();
    }

    @Test
    void shouldReturnUserTextField() throws Exception {
        // Given
        List<WebElement> inputElements = new ArrayList<>();
        inputElements.add(new TestWebElement("input", "password"));
        inputElements.add(new TestWebElement("input", "text"));
        inputElements.add(new TestWebElement("input", "checkbox"));

        // When
        WebElement field = AuthUtils.getUserField(inputElements);

        // Then
        assertThat(field, is(notNullValue()));
        assertThat(field.getAttribute("type"), is(equalTo("text")));
    }

    @Test
    void shouldReturnUserEmailField() throws Exception {
        // Given
        List<WebElement> inputElements = new ArrayList<>();
        inputElements.add(new TestWebElement("input", "password"));
        inputElements.add(new TestWebElement("input", "email"));
        inputElements.add(new TestWebElement("input", "checkbox"));

        // When
        WebElement field = AuthUtils.getUserField(inputElements);

        // Then
        assertThat(field, is(notNullValue()));
        assertThat(field.getAttribute("type"), is(equalTo("email")));
    }

    @Test
    void shouldReturnNoUserField() throws Exception {
        // Given
        List<WebElement> inputElements = new ArrayList<>();
        inputElements.add(new TestWebElement("input", "password"));
        inputElements.add(new TestWebElement("input", "hidden"));
        inputElements.add(new TestWebElement("input", "checkbox"));

        // When
        WebElement field = AuthUtils.getUserField(inputElements);

        // Then
        assertThat(field, is(nullValue()));
    }

    @Test
    void shouldReturnPasswordField() throws Exception {
        // Given
        List<WebElement> inputElements = new ArrayList<>();
        inputElements.add(new TestWebElement("input", "email"));
        inputElements.add(new TestWebElement("input", "checkbox"));
        inputElements.add(new TestWebElement("input", "password"));

        // When
        WebElement field = AuthUtils.getPasswordField(inputElements);

        // Then
        assertThat(field, is(notNullValue()));
        assertThat(field.getAttribute("type"), is(equalTo("password")));
    }

    @Test
    void shouldReturnNoPasswordField() throws Exception {
        // Given
        List<WebElement> inputElements = new ArrayList<>();
        inputElements.add(new TestWebElement("input", "email"));
        inputElements.add(new TestWebElement("input", "hidden"));
        inputElements.add(new TestWebElement("input", "checkbox"));

        // When
        WebElement field = AuthUtils.getPasswordField(inputElements);

        // Then
        assertThat(field, is(nullValue()));
    }

    @Test
    void shouldReturnNoSessionTokens() throws Exception {
        // Given
        HttpMessage msg = new HttpMessage(new URI("https://example.com/test", true));

        // When
        Map<String, SessionToken> tokens = AuthUtils.getResponseSessionTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(0)));
    }

    @Test
    void shouldExtractHeaderSessionTokens() throws Exception {
        // Given
        HttpMessage msg = new HttpMessage(new URI("https://example.com/test", true));
        msg.getResponseHeader().addHeader("CustomHeader", "example-session-token");
        msg.getResponseHeader().addHeader(HttpHeader.AUTHORIZATION, "example-session-token");

        // When
        Map<String, SessionToken> tokens = AuthUtils.getResponseSessionTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(1)));

        assertThat(tokens.get("header:Authorization"), is(notNullValue()));
        assertThat(
                tokens.get("header:Authorization").getType(),
                is(equalTo(SessionToken.HEADER_TYPE)));
        assertThat(
                tokens.get("header:Authorization").getKey(), is(equalTo(HttpHeader.AUTHORIZATION)));
    }

    @Test
    void shouldExtractJsonSessionTokens() throws Exception {
        // Given
        HttpMessage msg = new HttpMessage(new URI("https://example.com/test", true));
        msg.getResponseHeader().addHeader(HttpHeader.CONTENT_TYPE, "blah-blah-json");
        msg.getResponseBody()
                .setBody("{'auth': {'test': '123', accessToken: 'example-session-token'}}");

        // When
        Map<String, SessionToken> tokens = AuthUtils.getResponseSessionTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(1)));
        assertThat(
                tokens.get("json:auth.accessToken").getType(), is(equalTo(SessionToken.JSON_TYPE)));
        assertThat(tokens.get("json:auth.accessToken").getKey(), is(equalTo("auth.accessToken")));
        assertThat(
                tokens.get("json:auth.accessToken").getValue(),
                is(equalTo("example-session-token")));
    }

    @Test
    void shouldDefaultToNoTokens() throws Exception {
        // Given
        HttpMessage msg =
                new HttpMessage(
                        new HttpRequestHeader(
                                "GET / HTTP/1.1\r\n"
                                        + "Header1: Value1\r\n"
                                        + "Header2: Value2\r\n"
                                        + "Host: example.com\r\n\r\n"),
                        new HttpRequestBody("Request Body"),
                        new HttpResponseHeader("HTTP/1.1 200 OK\r\n"),
                        new HttpResponseBody("Response Body"));
        // When
        Map<String, SessionToken> tokens = AuthUtils.getResponseSessionTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(0)));
    }

    @Test
    void shouldExtractHeaderTokens() throws Exception {
        // Given
        HttpMessage msg =
                new HttpMessage(
                        new HttpRequestHeader("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"),
                        new HttpRequestBody("Request Body"),
                        new HttpResponseHeader(
                                "HTTP/1.1 200 OK\r\n"
                                        + "Header1: Value1\r\n"
                                        + "Header2: Value2\r\n"),
                        new HttpResponseBody("Response Body"));
        // When
        Map<String, SessionToken> tokens = AuthUtils.getAllTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(2)));
        assertThat(tokens.get("header:Header1").getValue(), is(equalTo("Value1")));
        assertThat(tokens.get("header:Header2").getValue(), is(equalTo("Value2")));
    }

    @Test
    void shouldExtractUrlParams() throws Exception {
        // Given
        HttpMessage msg =
                new HttpMessage(
                        new HttpRequestHeader(
                                "GET https://example.com/?att1=val1&att2=val2 HTTP/1.1\r\nHost: example.com\r\n\r\n"),
                        new HttpRequestBody("Request Body"),
                        new HttpResponseHeader("HTTP/1.1 200 OK\r\n"),
                        new HttpResponseBody("Response Body"));
        // When
        Map<String, SessionToken> tokens = AuthUtils.getAllTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(2)));
        assertThat(tokens.get("url:att1").getValue(), is(equalTo("val1")));
        assertThat(tokens.get("url:att2").getValue(), is(equalTo("val2")));
    }

    @Test
    void shouldExtractJsonTokens() throws Exception {
        // Given
        HttpMessage msg =
                new HttpMessage(
                        new HttpRequestHeader("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"),
                        new HttpRequestBody("Request Body"),
                        new HttpResponseHeader(
                                "HTTP/1.1 200 OK\r\n" + "Content-Type: application/json"),
                        new HttpResponseBody(
                                "{'wrapper1': {\n"
                                        + "  'att1': 'val1',\n"
                                        + "  'att2': 'val2',\n"
                                        + "  'wrapper2': {\n"
                                        + "    'att1': 'val3',\n"
                                        + "    'array': [\n"
                                        + "      {'att1': 'val4', 'att2': 'val5'},\n"
                                        + "      {'att3': 'val6', 'att4': 'val7'}\n"
                                        + "    ]\n"
                                        + "  }\n"
                                        + "}}"));
        // When
        Map<String, SessionToken> tokens = AuthUtils.getAllTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(8)));
        assertThat(tokens.get("json:wrapper1.att1").getValue(), is(equalTo("val1")));
        assertThat(tokens.get("json:wrapper1.att2").getValue(), is(equalTo("val2")));
        assertThat(tokens.get("json:wrapper1.wrapper2.att1").getValue(), is(equalTo("val3")));
        assertThat(
                tokens.get("json:wrapper1.wrapper2.array[0].att1").getValue(), is(equalTo("val4")));
        assertThat(
                tokens.get("json:wrapper1.wrapper2.array[0].att2").getValue(), is(equalTo("val5")));
        assertThat(
                tokens.get("json:wrapper1.wrapper2.array[1].att3").getValue(), is(equalTo("val6")));
        assertThat(
                tokens.get("json:wrapper1.wrapper2.array[1].att4").getValue(), is(equalTo("val7")));
        assertThat(tokens.get("header:Content-Type").getValue(), is(equalTo("application/json")));
    }

    @Test
    void shouldGetEmptyHeaderTokens() throws Exception {
        // Given
        HttpMessage msg =
                new HttpMessage(
                        new HttpRequestHeader(
                                "GET https://example.com/?att1=val1&att2=val2 HTTP/1.1\r\nHost: example.com\r\n\r\n"),
                        new HttpRequestBody("Request Body"),
                        new HttpResponseHeader("HTTP/1.1 200 OK\r\n"),
                        new HttpResponseBody("Response Body"));

        // When
        List<Pair<String, String>> headerTokens = AuthUtils.getHeaderTokens(msg, new ArrayList<>());

        // Then
        assertThat(headerTokens.size(), is(equalTo(0)));
    }

    @Test
    void shouldGetHeaderTokens() throws Exception {
        // Given
        String token1 = "96438673498764398";
        String token2 = "bndkdfsojhgkdshgk";
        String token3 = "89jdhf9834herg03s";

        HttpMessage msg =
                new HttpMessage(
                        new HttpRequestHeader(
                                "GET https://example.com/?att1=val1&att2=val2 HTTP/1.1\r\nHost: example.com\r\n\r\n"),
                        new HttpRequestBody("Request Body"),
                        new HttpResponseHeader("HTTP/1.1 200 OK\r\n"),
                        new HttpResponseBody("Response Body"));
        msg.getRequestHeader().addHeader(HttpHeader.AUTHORIZATION, "Bearer " + token1);
        msg.getRequestHeader().addHeader(HttpHeader.SET_COOKIE, token2 + "; SameSite=Strict");
        msg.getRequestHeader().addHeader(HttpHeader.AUTHORIZATION, token3);
        List<SessionToken> tokens = new ArrayList<>();
        tokens.add(new SessionToken(SessionToken.HEADER_TYPE, HttpHeader.AUTHORIZATION, token1));
        tokens.add(new SessionToken(SessionToken.JSON_TYPE, "set.cookie", token2));

        // When
        List<Pair<String, String>> headerTokens = AuthUtils.getHeaderTokens(msg, tokens);

        // Then
        assertThat(headerTokens.size(), is(equalTo(2)));
        assertThat(headerTokens.get(0).first, is(equalTo(HttpHeader.AUTHORIZATION)));
        assertThat(headerTokens.get(0).second, is(equalTo("Bearer {%header:Authorization%}")));
        assertThat(headerTokens.get(1).first, is(equalTo(HttpHeader.SET_COOKIE)));
        assertThat(headerTokens.get(1).second, is(equalTo("{%json:set.cookie%}; SameSite=Strict")));
    }

    @Test
    void shouldGetNoRequestSessionTokens() throws Exception {
        // Given
        HttpMessage msg =
                new HttpMessage(
                        new HttpRequestHeader(
                                "GET https://example.com/?att1=val1&att2=val2 HTTP/1.1\r\nHost: example.com\r\n\r\n"),
                        new HttpRequestBody("Request Body"),
                        new HttpResponseHeader("HTTP/1.1 200 OK\r\n"),
                        new HttpResponseBody("Response Body"));

        // When
        Map<String, SessionToken> tokens = AuthUtils.getRequestSessionTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(0)));
    }

    @Test
    void shouldGetRequestSessionTokens() throws Exception {
        // Given
        String token1 = "96438673498764398";
        String token2 = "bndkdfsojhgkdshgk";
        String token3 = "89jdhf9834herg03s";

        HttpMessage msg =
                new HttpMessage(
                        new HttpRequestHeader(
                                "GET https://example.com/?att1=val1&att2=val2 HTTP/1.1\r\nHost: example.com\r\n\r\n"),
                        new HttpRequestBody("Request Body"),
                        new HttpResponseHeader("HTTP/1.1 200 OK\r\n"),
                        new HttpResponseBody("Response Body"));
        msg.getRequestHeader().addHeader(HttpHeader.AUTHORIZATION, "Bearer " + token1);
        msg.getRequestHeader().addHeader(HttpHeader.SET_COOKIE, token2 + "; SameSite=Strict");
        msg.getRequestHeader().addHeader(HttpHeader.AUTHORIZATION, token3);

        // When
        Map<String, SessionToken> tokens = AuthUtils.getRequestSessionTokens(msg);

        // Then
        assertThat(tokens.size(), is(equalTo(2)));
        assertThat(tokens.containsKey("Bearer " + token1), is(equalTo(true)));
        assertThat(tokens.get("Bearer " + token1).getType(), is(equalTo(SessionToken.HEADER_TYPE)));
        assertThat(tokens.get("Bearer " + token1).getValue(), is(equalTo("Bearer " + token1)));
        assertThat(tokens.get("Bearer " + token1).getToken(), is(equalTo("header:Authorization")));
        assertThat(tokens.get(token3).getType(), is(equalTo(SessionToken.HEADER_TYPE)));
        assertThat(tokens.get(token3).getValue(), is(equalTo(token3)));
        assertThat(tokens.get(token3).getToken(), is(equalTo("header:Authorization")));
        assertThat(tokens.containsKey(token3), is(equalTo(true)));
    }

    @Test
    void shouldReturnNoSessionToken() throws Exception {
        // Given
        AuthUtils.recordSessionToken(
                new SessionToken(SessionToken.HEADER_TYPE, HttpHeader.AUTHORIZATION, "456"));
        // When
        SessionToken st = AuthUtils.getSessionToken("123");
        // Then
        assertThat(st, is(nullValue()));
    }

    @Test
    void shouldReturnSessionToken() throws Exception {
        // Given
        AuthUtils.recordSessionToken(new SessionToken(SessionToken.HEADER_TYPE, "Header1", "123"));
        AuthUtils.recordSessionToken(new SessionToken(SessionToken.HEADER_TYPE, "Header2", "456"));
        AuthUtils.recordSessionToken(new SessionToken(SessionToken.HEADER_TYPE, "Header3", "789"));
        // When
        SessionToken st = AuthUtils.getSessionToken("789");
        // Then
        assertThat(st, is(notNullValue()));
        assertThat(st.getKey(), is("Header3"));
        assertThat(st.getValue(), is("789"));
        assertThat(st.getToken(), is("header:Header3"));
    }

    @Test
    void shouldRemoveSessionToken() throws Exception {
        // Given
        AuthUtils.recordSessionToken(new SessionToken(SessionToken.HEADER_TYPE, "Header1", "123"));
        AuthUtils.recordSessionToken(new SessionToken(SessionToken.HEADER_TYPE, "Header2", "456"));
        AuthUtils.recordSessionToken(new SessionToken(SessionToken.HEADER_TYPE, "Header3", "789"));
        // When
        SessionToken st1 = AuthUtils.getSessionToken("789");
        AuthUtils.removeSessionToken(st1);
        SessionToken st2 = AuthUtils.getSessionToken("789");
        // Then
        assertThat(st1, is(notNullValue()));
        assertThat(st2, is(nullValue()));
    }

    class TestWebElement implements WebElement {

        private String tag;
        private String type;

        TestWebElement(String tag, String type) {
            this.tag = tag;
            this.type = type;
        }

        @Override
        public <X> X getScreenshotAs(OutputType<X> target) throws WebDriverException {
            return null;
        }

        @Override
        public void click() {}

        @Override
        public void submit() {}

        @Override
        public void sendKeys(CharSequence... keysToSend) {}

        @Override
        public void clear() {}

        @Override
        public String getTagName() {
            return tag;
        }

        @Override
        public String getAttribute(String name) {
            if ("type".equalsIgnoreCase(name)) {
                return type;
            }
            return null;
        }

        @Override
        public boolean isSelected() {
            return false;
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public String getText() {
            return null;
        }

        @Override
        public List<WebElement> findElements(By by) {
            return null;
        }

        @Override
        public WebElement findElement(By by) {
            return null;
        }

        @Override
        public boolean isDisplayed() {
            return false;
        }

        @Override
        public Point getLocation() {
            return null;
        }

        @Override
        public Dimension getSize() {
            return null;
        }

        @Override
        public Rectangle getRect() {
            return null;
        }

        @Override
        public String getCssValue(String propertyName) {
            return null;
        }
    }
}
