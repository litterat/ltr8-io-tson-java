package io.ltr8.tson.suite.data;

/**
 * The host class for the {@code rss_feed} record a vector importing {@code schemas/fixtures/link-feed.tn} declares, a
 * member of its {@code feed}.
 */
public record RssFeed(String url) implements Feed {
}
