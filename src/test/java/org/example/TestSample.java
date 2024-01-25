package org.example;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static us.abstracta.jmeter.javadsl.JmeterDsl.*;
import static us.abstracta.jmeter.javadsl.dashboard.DashboardVisualizer.dashboardVisualizer;

import java.io.IOException;
import java.time.Duration;
import org.apache.http.*;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

public class TestSample {

    @Test
    public void TestSample1() throws IOException {
        TestPlanStats stats = testPlan(
                threadGroup(2, 100,
                        httpSampler("home", "http://opencart.abstracta.us")),
                dashboardVisualizer()
        ).run();
        assertThat(stats.overall().sampleTimePercentile99()).isLessThan(Duration.ofSeconds(5));
    }
}

