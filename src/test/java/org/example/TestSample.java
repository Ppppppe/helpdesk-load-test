package org.example;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static us.abstracta.jmeter.javadsl.JmeterDsl.*;

import org.apache.http.entity.ContentType;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;

import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.util.TestResource;
import org.apache.jmeter.threads.JMeterVariables;

public class TestSample {

    private Properties prop;

    @Test
    public void oneSamplerTest() throws IOException {
        String helpDesk = "http://192.168.1.59:23232";
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        TestPlanStats stats = testPlan(
                httpCache().disable(),
                threadGroup(1, 1,
                        httpHeaders()
                                .header("Accept-Encoding", "gzip, deflate")
                                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                                .header("User-Agent", userAgent),
                        transaction("Script 1: Authorization user",
                                httpSampler("Main page", helpDesk + "/")
                                        .proxy("http://127.0.0.1:8888")
                                        .method(HTTPConstants.GET)
                        )
                )
                ,influxDbListener("http://127.0.0.1:8086/write?db=jmeter")
        ).run();
    }

    @BeforeEach
    public void setUp() {
        prop = new Properties();
        String fileName = "src/test/resources/app.config";
        try (FileInputStream fis = new FileInputStream(fileName)) {
            prop.load(fis);
        } catch (FileNotFoundException e) {
            System.out.println("Configuration file not found!");
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void helpdeskLoadTest() throws IOException {

        String helpDesk = prop.getProperty("helpdesk.address");
        int userCount = Integer.parseInt(prop.getProperty("user.count"));
        String influxDb = prop.getProperty("influxDb.address");
        System.out.println(helpDesk);
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        TestPlanStats stats = testPlan(
                csvDataSet(new TestResource(prop.getProperty("users.credentials"))),
                httpCache().disable(),
                httpCookies(),
                threadGroup()
                        .rampTo(userCount, Duration.ofSeconds(3)).holdIterating(1)
                        .children(
                        httpDefaults()
                                .url(helpDesk)
                                .proxy(prop.getProperty("fiddler.proxy"))
                                .connectionTimeout(Duration.ofSeconds(1))
                                .responseTimeout(Duration.ofSeconds(3)),
                        httpHeaders()
                                .header("Accept-Encoding", "gzip, deflate")
                                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                                .header("User-Agent", userAgent),
                        transaction("Script 1: Authorization user",
                                httpSampler("Main page", "/")
                                        .method(HTTPConstants.GET),
                                httpSampler("Open auth form", "/login/?next=/")
                                        .method(HTTPConstants.GET)
                                        .children(
                                                regexExtractor("CSRF_MIDDLEWARE_TOKEN",
                                                        "<input type=\"hidden\" name=\"csrfmiddlewaretoken\" value=\"(.+?)\"></form>")
                                        ),
                                ifController(s -> (s.vars.get("CSRF_MIDDLEWARE_TOKEN") != null),
                                httpSampler("Authorization", "/login/")
                                        .post("username=${USER}&password=${PASS}&csrfmiddlewaretoken=${CSRF_MIDDLEWARE_TOKEN}",
                                                ContentType.APPLICATION_FORM_URLENCODED)
                                )
                                //  redirect /login/  -->  /  -->  /tickets/
                        ),
                        transaction("Script 2: Create new ticket",
                                httpSampler("Open submission form", "/tickets/submit/")
                                        .children(
                                                regexExtractor("CSRF_MIDDLEWARE_TOKEN",
                                                        "<input type=\"hidden\" name=\"csrfmiddlewaretoken\" value=\"(.+?)\">[\\s]*</form>"),
                                                regexExtractor("USER_ID_IN_DROPDOWN",
                                                        "<option value=\"(\\d+?)\">${USER}</option>")
                                        )
                                ,
                                httpSampler("Send submission form", "/tickets/submit/")
                                        .method(HTTPConstants.POST)
                                        .bodyPart("csrfmiddlewaretoken", "${CSRF_MIDDLEWARE_TOKEN}", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("queue", "2", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("title", "Test title", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("body", "Test body", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("priority", "3", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("assigned_to", "${USER_ID_IN_DROPDOWN}", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("submitter_email", "someemail@test6123.com", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("due_date", "2024-05-12 12:34:56", ContentType.MULTIPART_FORM_DATA)
                                        // /tickets/{id} as response
                                        .children(
                                                regexExtractor("TICKET_ID", "/tickets/(\\d+?)/")
                                                        .defaultValue("0"),
                                                regexExtractor("CSRF_MIDDLEWARE_TOKEN",
                                                        "<input type=\"hidden\" name=\"csrfmiddlewaretoken\" value=\"(.+?)\">[\\s]*</form>"),
                                                regexExtractor("STATUS_ID", "<input type='hidden'[\\s]*name='status'[\\s]*value='(\\d+?)'[\\s]*/>")
                                                        .defaultValue("0"),
                                                // Opened 1 » Reopened 2 » Resolved 3 » Closed 4 » Duplicate 5
                                                jsr223PostProcessor(s -> s.vars.put("MOD_STATUS_ID",
                                                            String.valueOf((Integer.parseInt(s.vars.get("STATUS_ID")) + 2) % 5)
                                                        )
                                                )
                                        )
                        ),
                        transaction("Script 3: Change ticket status",
                                ifController(s -> (s.vars.get("TICKET_ID") != null)),
                                uniformRandomTimer(Duration.ofSeconds(1), Duration.ofSeconds(3)),
                                httpSampler("Update ticket status and comment", "/tickets/${TICKET_ID}/update/")
                                        .method(HTTPConstants.POST)
                                        .bodyPart("csrfmiddlewaretoken", "${CSRF_MIDDLEWARE_TOKEN}", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("new_status", "${MOD_STATUS_ID}", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("comment", "Some new comment", ContentType.MULTIPART_FORM_DATA)

                        )
                ),
                influxDbListener(influxDb + "/write?db=jmeter")
        ).run();
        assertThat(stats.overall().sampleTimePercentile99()).isLessThan(Duration.ofSeconds(5));
    }
}

