/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.datafeed.extractor.chunked;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.mock.orig.Mockito;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.metrics.max.Max;
import org.elasticsearch.search.aggregations.metrics.min.Min;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ml.datafeed.extractor.DataExtractor;
import org.elasticsearch.xpack.ml.datafeed.extractor.DataExtractorFactory;
import org.junit.Before;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ChunkedDataExtractorTests extends ESTestCase {

    private Client client;
    private List<SearchRequestBuilder> capturedSearchRequests;
    private String jobId;
    private String timeField;
    private List<String> types;
    private List<String> indexes;
    private QueryBuilder query;
    private int scrollSize;
    private TimeValue chunkSpan;
    private DataExtractorFactory dataExtractorFactory;

    private class TestDataExtractor extends ChunkedDataExtractor {

        private SearchResponse nextResponse;

        TestDataExtractor(long start, long end) {
            super(client, dataExtractorFactory, createContext(start, end));
        }

        @Override
        protected SearchResponse executeSearchRequest(SearchRequestBuilder searchRequestBuilder) {
            capturedSearchRequests.add(searchRequestBuilder);
            return nextResponse;
        }

        void setNextResponse(SearchResponse searchResponse) {
            nextResponse = searchResponse;
        }
    }

    @Before
    public void setUpTests() {
        client = mock(Client.class);
        capturedSearchRequests = new ArrayList<>();
        jobId = "test-job";
        timeField = "time";
        indexes = Arrays.asList("index-1", "index-2");
        types = Arrays.asList("type-1", "type-2");
        query = QueryBuilders.matchAllQuery();
        scrollSize = 1000;
        chunkSpan = null;
        dataExtractorFactory = mock(DataExtractorFactory.class);
    }

    public void testExtractionGivenNoData() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2300L);
        extractor.setNextResponse(createSearchResponse(0L, 0L, 0L));

        assertThat(extractor.hasNext(), is(true));
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);
    }

    public void testExtractionGivenSpecifiedChunk() throws IOException {
        chunkSpan = TimeValue.timeValueSeconds(1);
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2300L);
        extractor.setNextResponse(createSearchResponse(10L, 1000L, 2200L));

        InputStream inputStream1 = mock(InputStream.class);
        InputStream inputStream2 = mock(InputStream.class);
        InputStream inputStream3 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1, inputStream2);
        when(dataExtractorFactory.newExtractor(1000L, 2000L)).thenReturn(subExtactor1);

        DataExtractor subExtactor2 = new StubSubExtractor(inputStream3);
        when(dataExtractorFactory.newExtractor(2000L, 2300L)).thenReturn(subExtactor2);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream2, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream3, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertThat(extractor.next().isPresent(), is(false));

        verify(dataExtractorFactory).newExtractor(1000L, 2000L);
        verify(dataExtractorFactory).newExtractor(2000L, 2300L);
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);

        assertThat(capturedSearchRequests.size(), equalTo(1));
        String searchRequest = capturedSearchRequests.get(0).toString().replaceAll("\\s", "");
        assertThat(searchRequest, containsString("\"size\":0"));
        assertThat(searchRequest, containsString("\"query\":{\"bool\":{\"filter\":[{\"match_all\":{\"boost\":1.0}}," +
                "{\"range\":{\"time\":{\"from\":1000,\"to\":2300,\"include_lower\":true,\"include_upper\":false," +
                "\"format\":\"epoch_millis\",\"boost\":1.0}}}]"));
        assertThat(searchRequest, containsString("\"aggregations\":{\"earliest_time\":{\"min\":{\"field\":\"time\"}}," +
                "\"latest_time\":{\"max\":{\"field\":\"time\"}}}}"));
        assertThat(searchRequest, not(containsString("\"sort\"")));
    }

    public void testExtractionGivenAutoChunkAndScrollSize1000() throws IOException {
        chunkSpan = null;
        scrollSize = 1000;
        TestDataExtractor extractor = new TestDataExtractor(100000L, 450000L);

        // 300K millis * 1000 * 10 / 15K docs = 200000
        extractor.setNextResponse(createSearchResponse(15000L, 100000L, 400000L));

        InputStream inputStream1 = mock(InputStream.class);
        InputStream inputStream2 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1);
        when(dataExtractorFactory.newExtractor(100000L, 300000L)).thenReturn(subExtactor1);

        DataExtractor subExtactor2 = new StubSubExtractor(inputStream2);
        when(dataExtractorFactory.newExtractor(300000L, 450000L)).thenReturn(subExtactor2);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream2, extractor.next().get());
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));

        verify(dataExtractorFactory).newExtractor(100000L, 300000L);
        verify(dataExtractorFactory).newExtractor(300000L, 450000L);
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenAutoChunkAndScrollSize500() throws IOException {
        chunkSpan = null;
        scrollSize = 500;
        TestDataExtractor extractor = new TestDataExtractor(100000L, 450000L);

        // 300K millis * 500 * 10 / 15K docs = 100000
        extractor.setNextResponse(createSearchResponse(15000L, 100000L, 400000L));

        InputStream inputStream1 = mock(InputStream.class);
        InputStream inputStream2 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1);
        when(dataExtractorFactory.newExtractor(100000L, 200000L)).thenReturn(subExtactor1);

        DataExtractor subExtactor2 = new StubSubExtractor(inputStream2);
        when(dataExtractorFactory.newExtractor(200000L, 300000L)).thenReturn(subExtactor2);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream2, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));

        verify(dataExtractorFactory).newExtractor(100000L, 200000L);
        verify(dataExtractorFactory).newExtractor(200000L, 300000L);

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenAutoChunkIsLessThanMinChunk() throws IOException {
        chunkSpan = null;
        scrollSize = 1000;
        TestDataExtractor extractor = new TestDataExtractor(100000L, 450000L);

        // 30K millis * 1000 * 10 / 150K docs = 2000 < min of 60K
        extractor.setNextResponse(createSearchResponse(150000L, 100000L, 400000L));

        InputStream inputStream1 = mock(InputStream.class);
        InputStream inputStream2 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1);
        when(dataExtractorFactory.newExtractor(100000L, 160000L)).thenReturn(subExtactor1);

        DataExtractor subExtactor2 = new StubSubExtractor(inputStream2);
        when(dataExtractorFactory.newExtractor(160000L, 220000L)).thenReturn(subExtactor2);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream2, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));

        verify(dataExtractorFactory).newExtractor(100000L, 160000L);
        verify(dataExtractorFactory).newExtractor(160000L, 220000L);
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenAutoChunkAndDataTimeSpreadIsZero() throws IOException {
        chunkSpan = null;
        scrollSize = 1000;
        TestDataExtractor extractor = new TestDataExtractor(100L, 500L);

        extractor.setNextResponse(createSearchResponse(150000L, 300L, 300L));

        InputStream inputStream1 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1);
        when(dataExtractorFactory.newExtractor(300L, 500L)).thenReturn(subExtactor1);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));

        verify(dataExtractorFactory).newExtractor(300L, 500L);
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenAutoChunkAndTotalTimeRangeSmallerThanChunk() throws IOException {
        chunkSpan = null;
        scrollSize = 1000;
        TestDataExtractor extractor = new TestDataExtractor(1L, 101L);

        // 100 millis * 1000 * 10 / 10 docs = 100000
        extractor.setNextResponse(createSearchResponse(10L, 1L, 101L));

        InputStream inputStream1 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1);
        when(dataExtractorFactory.newExtractor(1L, 101L)).thenReturn(subExtactor1);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));

        verify(dataExtractorFactory).newExtractor(1L, 101L);
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenAutoChunkAndIntermediateEmptySearchShouldReconfigure() throws IOException {
        chunkSpan = null;
        scrollSize = 500;
        TestDataExtractor extractor = new TestDataExtractor(100000L, 400000L);

        // 300K millis * 500 * 10 / 15K docs = 100000
        extractor.setNextResponse(createSearchResponse(15000L, 100000L, 400000L));

        InputStream inputStream1 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1);
        when(dataExtractorFactory.newExtractor(100000L, 200000L)).thenReturn(subExtactor1);

        // This one is empty
        DataExtractor subExtactor2 = new StubSubExtractor();
        when(dataExtractorFactory.newExtractor(200000, 300000L)).thenReturn(subExtactor2);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));

        // Now we have: 200K millis * 500 * 10 / 5K docs = 200000
        extractor.setNextResponse(createSearchResponse(5000, 200000L, 400000L));

        // This is the last one
        InputStream inputStream2 = mock(InputStream.class);
        DataExtractor subExtactor3 = new StubSubExtractor(inputStream2);
        when(dataExtractorFactory.newExtractor(200000, 400000)).thenReturn(subExtactor3);

        assertEquals(inputStream2, extractor.next().get());
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));

        verify(dataExtractorFactory).newExtractor(100000L, 200000L);
        verify(dataExtractorFactory).newExtractor(200000L, 300000L);
        verify(dataExtractorFactory).newExtractor(200000L, 400000L);
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);

        assertThat(capturedSearchRequests.size(), equalTo(2));

        String searchRequest = capturedSearchRequests.get(0).toString().replaceAll("\\s", "");
        assertThat(searchRequest, containsString("\"from\":100000,\"to\":400000"));
        searchRequest = capturedSearchRequests.get(1).toString().replaceAll("\\s", "");
        assertThat(searchRequest, containsString("\"from\":200000,\"to\":400000"));
    }

    public void testCancelGivenNextWasNeverCalled() throws IOException {
        chunkSpan = TimeValue.timeValueSeconds(1);
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2300L);
        extractor.setNextResponse(createSearchResponse(10L, 1000L, 2200L));

        InputStream inputStream1 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1);
        when(dataExtractorFactory.newExtractor(1000L, 2000L)).thenReturn(subExtactor1);

        assertThat(extractor.hasNext(), is(true));

        extractor.cancel();

        assertThat(extractor.isCancelled(), is(true));
        assertThat(extractor.hasNext(), is(false));
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);
    }

    public void testCancelGivenCurrentSubExtractorHasMore() throws IOException {
        chunkSpan = TimeValue.timeValueSeconds(1);
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2300L);
        extractor.setNextResponse(createSearchResponse(10L, 1000L, 2200L));

        InputStream inputStream1 = mock(InputStream.class);
        InputStream inputStream2 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1, inputStream2);
        when(dataExtractorFactory.newExtractor(1000L, 2000L)).thenReturn(subExtactor1);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());

        extractor.cancel();

        assertThat(extractor.isCancelled(), is(true));
        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream2, extractor.next().get());
        assertThat(extractor.hasNext(), is(true));
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));

        verify(dataExtractorFactory).newExtractor(1000L, 2000L);
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);
    }

    public void testCancelGivenCurrentSubExtractorIsDone() throws IOException {
        chunkSpan = TimeValue.timeValueSeconds(1);
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2300L);
        extractor.setNextResponse(createSearchResponse(10L, 1000L, 2200L));

        InputStream inputStream1 = mock(InputStream.class);

        DataExtractor subExtactor1 = new StubSubExtractor(inputStream1);
        when(dataExtractorFactory.newExtractor(1000L, 2000L)).thenReturn(subExtactor1);

        assertThat(extractor.hasNext(), is(true));
        assertEquals(inputStream1, extractor.next().get());

        extractor.cancel();

        assertThat(extractor.isCancelled(), is(true));
        assertThat(extractor.hasNext(), is(true));
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));

        verify(dataExtractorFactory).newExtractor(1000L, 2000L);
        Mockito.verifyNoMoreInteractions(dataExtractorFactory);
    }

    public void testDataSummaryRequestIsNotOk() {
        chunkSpan = TimeValue.timeValueSeconds(2);
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2300L);
        extractor.setNextResponse(createErrorResponse());

        assertThat(extractor.hasNext(), is(true));
        expectThrows(IOException.class, () -> extractor.next());
    }

    public void testDataSummaryRequestHasShardFailures() {
        chunkSpan = TimeValue.timeValueSeconds(2);
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2300L);
        extractor.setNextResponse(createResponseWithShardFailures());

        assertThat(extractor.hasNext(), is(true));
        expectThrows(IOException.class, () -> extractor.next());
    }

    private SearchResponse createSearchResponse(long totalHits, long earliestTime, long latestTime) {
        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.status()).thenReturn(RestStatus.OK);
        SearchHit[] hits = new SearchHit[(int)totalHits];
        SearchHits searchHits = new SearchHits(hits, totalHits, 1);
        when(searchResponse.getHits()).thenReturn(searchHits);

        Aggregations aggs = mock(Aggregations.class);
        Min min = mock(Min.class);
        when(min.getValue()).thenReturn((double) earliestTime);
        when(aggs.get("earliest_time")).thenReturn(min);
        Max max = mock(Max.class);
        when(max.getValue()).thenReturn((double) latestTime);
        when(aggs.get("latest_time")).thenReturn(max);
        when(searchResponse.getAggregations()).thenReturn(aggs);
        return searchResponse;
    }

    private SearchResponse createErrorResponse() {
        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.status()).thenReturn(RestStatus.INTERNAL_SERVER_ERROR);
        return searchResponse;
    }

    private SearchResponse createResponseWithShardFailures() {
        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.status()).thenReturn(RestStatus.OK);
        when(searchResponse.getShardFailures()).thenReturn(
                new ShardSearchFailure[] { new ShardSearchFailure(new RuntimeException("shard failed"))});
        return searchResponse;
    }

    private ChunkedDataExtractorContext createContext(long start, long end) {
        return new ChunkedDataExtractorContext(jobId, timeField, indexes, types, query, scrollSize, start, end, chunkSpan);
    }

    private static class StubSubExtractor implements DataExtractor {
        List<InputStream> streams = new ArrayList<>();
        boolean hasNext = true;

        StubSubExtractor() {}

        StubSubExtractor(InputStream... streams) {
            for (InputStream stream : streams) {
                this.streams.add(stream);
            }
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public Optional<InputStream> next() throws IOException {
            if (streams.isEmpty()) {
                hasNext = false;
                return Optional.empty();
            }
            return Optional.of(streams.remove(0));
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void cancel() {
            // do nothing
        }
    }
}
