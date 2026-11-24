package io.ltr8.tson.suite.data;

/**
 * The host type for the corpus's {@code feed} family ({@code schemas/fixtures/link-feed.tn}): one class per member, the
 * fixture's {@code atom_feed} and the {@code rss_feed} a vector importing it declares.
 */
public sealed interface Feed permits AtomFeed, RssFeed {
}
