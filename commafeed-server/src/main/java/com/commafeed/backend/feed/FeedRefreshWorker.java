package com.commafeed.backend.feed;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.commafeed.CommaFeedConfiguration;
import com.commafeed.backend.HttpGetter.NotModifiedException;
import com.commafeed.backend.feed.FeedFetcher.FeedFetcherResult;
import com.commafeed.backend.model.Feed;
import com.commafeed.backend.model.FeedEntry;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Calls {@link FeedFetcher} and updates the Feed object, but does not update the database ({@link FeedRefreshUpdater} does that)
 */
@Slf4j
@Singleton
public class FeedRefreshWorker {

	private final FeedRefreshIntervalCalculator refreshIntervalCalculator;
	private final FeedFetcher fetcher;
	private final CommaFeedConfiguration config;
	private final Meter feedFetched;

	@Inject
	public FeedRefreshWorker(FeedRefreshIntervalCalculator refreshIntervalCalculator, FeedFetcher fetcher, CommaFeedConfiguration config,
			MetricRegistry metrics) {
		this.refreshIntervalCalculator = refreshIntervalCalculator;
		this.fetcher = fetcher;
		this.config = config;
		this.feedFetched = metrics.meter(MetricRegistry.name(getClass(), "feedFetched"));

	}

	public FeedRefreshWorkerResult update(Feed feed) {
		try {
			String url = Optional.ofNullable(feed.getUrlAfterRedirect()).orElse(feed.getUrl());
			FeedFetcherResult feedFetcherResult = fetcher.fetch(url, false, feed.getLastModifiedHeader(), feed.getEtagHeader(),
					feed.getLastPublishedDate(), feed.getLastContentHash());
			// stops here if NotModifiedException or any other exception is thrown
			List<FeedEntry> entries = feedFetcherResult.getEntries();

			Integer maxFeedCapacity = config.getApplicationSettings().getMaxFeedCapacity();
			if (maxFeedCapacity > 0) {
				entries = entries.stream().limit(maxFeedCapacity).toList();
			}

			String urlAfterRedirect = feedFetcherResult.getUrlAfterRedirect();
			if (StringUtils.equals(url, urlAfterRedirect)) {
				urlAfterRedirect = null;
			}
			feed.setUrlAfterRedirect(urlAfterRedirect);
			feed.setLink(feedFetcherResult.getFeed().getLink());
			feed.setLastModifiedHeader(feedFetcherResult.getFeed().getLastModifiedHeader());
			feed.setEtagHeader(feedFetcherResult.getFeed().getEtagHeader());
			feed.setLastContentHash(feedFetcherResult.getFeed().getLastContentHash());
			feed.setLastPublishedDate(feedFetcherResult.getFeed().getLastPublishedDate());
			feed.setAverageEntryInterval(feedFetcherResult.getFeed().getAverageEntryInterval());
			feed.setLastEntryDate(feedFetcherResult.getFeed().getLastEntryDate());

			feed.setErrorCount(0);
			feed.setMessage(null);
			feed.setDisabledUntil(refreshIntervalCalculator.onFetchSuccess(feedFetcherResult.getFeed()));

			return new FeedRefreshWorkerResult(feed, entries);
		} catch (NotModifiedException e) {
			log.debug("Feed not modified : {} - {}", feed.getUrl(), e.getMessage());

			feed.setErrorCount(0);
			feed.setMessage(e.getMessage());
			feed.setDisabledUntil(refreshIntervalCalculator.onFeedNotModified(feed));

			if (e.getNewLastModifiedHeader() != null) {
				feed.setLastModifiedHeader(e.getNewLastModifiedHeader());
			}

			if (e.getNewEtagHeader() != null) {
				feed.setEtagHeader(e.getNewEtagHeader());
			}

			return new FeedRefreshWorkerResult(feed, Collections.emptyList());
		} catch (Exception e) {
			log.debug("unable to refresh feed {}", feed.getUrl(), e);

			feed.setErrorCount(feed.getErrorCount() + 1);
			feed.setMessage("Unable to refresh feed : " + e.getMessage());
			feed.setDisabledUntil(refreshIntervalCalculator.onFetchError(feed));

			return new FeedRefreshWorkerResult(feed, Collections.emptyList());
		} finally {
			feedFetched.mark();
		}
	}

	@Value
	public static class FeedRefreshWorkerResult {
		Feed feed;
		List<FeedEntry> entries;
	}

}
