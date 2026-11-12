package za.co.oip.crawling.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTxtTest {

    @Test
    void emptyDisallowAllowsEverything() {
        RobotsTxt robots = RobotsTxt.parse("User-agent: *\nDisallow:\n");
        assertThat(robots.isAllowed("OIP-Crawler", "/tenders")).isTrue();
    }

    @Test
    void longestMatchWinsAndAllowWinsTies() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /private
                Allow: /private/tenders
                Disallow: /*.pdf$
                """);
        assertThat(robots.isAllowed("OIP-Crawler", "/private/secret")).isFalse();
        assertThat(robots.isAllowed("OIP-Crawler", "/private/tenders/list")).isTrue();
        assertThat(robots.isAllowed("OIP-Crawler", "/docs/file.pdf")).isFalse();
        assertThat(robots.isAllowed("OIP-Crawler", "/docs/file.pdf?x=1")).isTrue();
        assertThat(robots.isAllowed("OIP-Crawler", "/public")).isTrue();
    }

    @Test
    void specificGroupOverridesWildcardGroup() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /

                User-agent: OIP-Crawler
                Disallow: /admin
                """);
        assertThat(robots.isAllowed("OIP-Crawler", "/tenders")).isTrue();
        assertThat(robots.isAllowed("OIP-Crawler", "/admin/x")).isFalse();
        assertThat(robots.isAllowed("SomeOtherBot", "/tenders")).isFalse();
    }

    @Test
    void groupedUserAgentsShareRules() {
        RobotsTxt robots = RobotsTxt.parse("User-agent: a\nUser-agent: oip-crawler\nDisallow: /x\n");
        assertThat(robots.isAllowed("OIP-Crawler", "/x/y")).isFalse();
    }
}
