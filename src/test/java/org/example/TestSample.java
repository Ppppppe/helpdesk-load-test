package org.example;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static us.abstracta.jmeter.javadsl.JmeterDsl.*;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.dashboard.DashboardVisualizer.dashboardVisualizer;
import org.apache.http.entity.ContentType;

import java.io.IOException;
import java.time.Duration;

import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.util.TestResource;

public class TestSample {

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
        System.out.println();
    }

    @Test
    public void helpdeskLoadTest() throws IOException {
        String helpDesk = "http://192.168.1.59:23232";
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        TestPlanStats stats = testPlan(
                csvDataSet(new TestResource("users.csv")),
                httpCache().disable(),
                httpCookies(),
                threadGroup()
                        .rampTo(5, Duration.ofSeconds(3)).holdIterating(1)
                        .children(
                        httpDefaults()
                                .proxy("http://127.0.0.1:8888")
                                .connectionTimeout(Duration.ofSeconds(1))
                                .responseTimeout(Duration.ofSeconds(3)),
                        httpHeaders()
                                .header("Accept-Encoding", "gzip, deflate")
                                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                                .header("User-Agent", userAgent),
                        transaction("Script 1: Authorization user",
                                httpSampler("Main page", helpDesk + "/")
                                        .method(HTTPConstants.GET),
                                httpSampler("Open auth form", helpDesk + "/login/?next=/")
                                        .method(HTTPConstants.GET)
                                        .children(
                                                regexExtractor("CSRF_MIDDLEWARE_TOKEN",
                                                        "<input type=\"hidden\" name=\"csrfmiddlewaretoken\" value=\"(.+?)\"></form>")
                                        ),
                                ifController(s -> (s.vars.get("CSRF_MIDDLEWARE_TOKEN") != null),
                                httpSampler("Authorization", helpDesk + "/login/")
                                        .post("username=${USER}&password=${PASS}&csrfmiddlewaretoken=${CSRF_MIDDLEWARE_TOKEN}",
                                                ContentType.APPLICATION_FORM_URLENCODED)
                                )
                                //  redirect /login/  -->  /  -->  /tickets/
                        ),
                        transaction("Script 2: Create new ticket",
                                httpSampler("Open submission form", helpDesk + "/tickets/submit/")
                                        .children(
                                                regexExtractor("CSRF_MIDDLEWARE_TOKEN_TICKET",
                                                        "<input type=\"hidden\" name=\"csrfmiddlewaretoken\" value=\"(.+?)\">[\\s]*</form>"),
                                                regexExtractor("USER_ID_IN_DROPDOWN",
                                                        "<option value=\"(\\d+?)\">${USER}</option>")
                                        )
                                ,
                                httpSampler("Send submission form", helpDesk + "/tickets/submit/")
                                        .method(HTTPConstants.POST)
                                        .bodyPart("csrfmiddlewaretoken", "${CSRF_MIDDLEWARE_TOKEN_TICKET}", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("queue", "2", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("title", "Test title", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("body", "Test body", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("priority", "3", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("assigned_to", "${USER_ID_IN_DROPDOWN}", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("submitter_email", "someemail@test6123.com", ContentType.MULTIPART_FORM_DATA)
                                        .bodyPart("due_date", "2024-05-12 12:34:56", ContentType.MULTIPART_FORM_DATA)
                        )
                ),
                influxDbListener("http://127.0.0.1:8086/write?db=jmeter")
        ).run();
        assertThat(stats.overall().sampleTimePercentile99()).isLessThan(Duration.ofSeconds(5));
    }
}

