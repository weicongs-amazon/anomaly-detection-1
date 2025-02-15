/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.ad.transport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opensearch.action.ActionListener;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.ad.AnomalyDetectorRunner;
import org.opensearch.ad.TestHelpers;
import org.opensearch.ad.feature.FeatureManager;
import org.opensearch.ad.feature.Features;
import org.opensearch.ad.indices.AnomalyDetectionIndices;
import org.opensearch.ad.ml.ModelManager;
import org.opensearch.ad.model.AnomalyDetector;
import org.opensearch.ad.model.AnomalyResult;
import org.opensearch.ad.settings.AnomalyDetectorSettings;
import org.opensearch.ad.util.RestHandlerUtils;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.ToXContent;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.rest.RestStatus;
import org.opensearch.tasks.Task;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import com.amazon.opendistroforelasticsearch.commons.ConfigConstants;
import com.google.common.collect.ImmutableMap;

public class PreviewAnomalyDetectorTransportActionTests extends OpenSearchSingleNodeTestCase {
    private ActionListener<PreviewAnomalyDetectorResponse> response;
    private PreviewAnomalyDetectorTransportAction action;
    private AnomalyDetectorRunner runner;
    private ClusterService clusterService;
    private FeatureManager featureManager;
    private ModelManager modelManager;
    private Task task;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        task = mock(Task.class);
        clusterService = mock(ClusterService.class);
        ClusterSettings clusterSettings = new ClusterSettings(
            Settings.EMPTY,
            Collections
                .unmodifiableSet(
                    new HashSet<>(
                        Arrays.asList(AnomalyDetectorSettings.MAX_ANOMALY_FEATURES, AnomalyDetectorSettings.FILTER_BY_BACKEND_ROLES)
                    )
                )
        );
        when(clusterService.getClusterSettings()).thenReturn(clusterSettings);
        featureManager = mock(FeatureManager.class);
        modelManager = mock(ModelManager.class);
        runner = new AnomalyDetectorRunner(modelManager, featureManager, AnomalyDetectorSettings.MAX_PREVIEW_RESULTS);
        action = new PreviewAnomalyDetectorTransportAction(
            Settings.EMPTY,
            mock(TransportService.class),
            clusterService,
            mock(ActionFilters.class),
            client(),
            runner,
            xContentRegistry()
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testPreviewTransportAction() throws IOException, InterruptedException {
        final CountDownLatch inProgressLatch = new CountDownLatch(1);
        AnomalyDetector detector = TestHelpers.randomAnomalyDetector(ImmutableMap.of("testKey", "testValue"), Instant.now());
        PreviewAnomalyDetectorRequest request = new PreviewAnomalyDetectorRequest(
            detector,
            detector.getDetectorId(),
            Instant.now(),
            Instant.now()
        );
        ActionListener<PreviewAnomalyDetectorResponse> previewResponse = new ActionListener<PreviewAnomalyDetectorResponse>() {
            @Override
            public void onResponse(PreviewAnomalyDetectorResponse response) {
                try {
                    XContentBuilder previewBuilder = response.toXContent(TestHelpers.builder(), ToXContent.EMPTY_PARAMS);
                    Assert.assertNotNull(previewBuilder);
                    Map<String, Object> map = TestHelpers.XContentBuilderToMap(previewBuilder);
                    List<AnomalyResult> results = (List<AnomalyResult>) map.get("anomaly_result");
                    Assert.assertNotNull(results);
                    Assert.assertTrue(results.size() > 0);
                    inProgressLatch.countDown();
                } catch (IOException e) {
                    // Should not reach here
                    Assert.assertTrue(false);
                }
            }

            @Override
            public void onFailure(Exception e) {
                // onFailure should not be called
                Assert.assertTrue(false);
            }
        };

        doReturn(TestHelpers.randomThresholdingResults()).when(modelManager).getPreviewResults(any());

        doAnswer(responseMock -> {
            Long startTime = responseMock.getArgument(1);
            ActionListener<Features> listener = responseMock.getArgument(3);
            listener.onResponse(TestHelpers.randomFeatures());
            return null;
        }).when(featureManager).getPreviewFeatures(anyObject(), anyLong(), anyLong(), any());
        action.doExecute(task, request, previewResponse);
        assertTrue(inProgressLatch.await(100, TimeUnit.SECONDS));
    }

    @Test
    public void testPreviewTransportActionWithNoFeature() throws IOException, InterruptedException {
        // Detector with no feature, Preview should fail
        final CountDownLatch inProgressLatch = new CountDownLatch(1);
        AnomalyDetector detector = TestHelpers.randomAnomalyDetector(Collections.emptyList());
        PreviewAnomalyDetectorRequest request = new PreviewAnomalyDetectorRequest(
            detector,
            detector.getDetectorId(),
            Instant.now(),
            Instant.now()
        );
        ActionListener<PreviewAnomalyDetectorResponse> previewResponse = new ActionListener<PreviewAnomalyDetectorResponse>() {
            @Override
            public void onResponse(PreviewAnomalyDetectorResponse response) {
                Assert.assertTrue(false);
            }

            @Override
            public void onFailure(Exception e) {
                Assert.assertTrue(e.getMessage().contains("Can't preview detector without feature"));
                inProgressLatch.countDown();
            }
        };
        action.doExecute(task, request, previewResponse);
        assertTrue(inProgressLatch.await(100, TimeUnit.SECONDS));
    }

    @Test
    public void testPreviewTransportActionWithNoDetector() throws IOException, InterruptedException {
        // When detectorId is null, preview should fail
        final CountDownLatch inProgressLatch = new CountDownLatch(1);
        PreviewAnomalyDetectorRequest request = new PreviewAnomalyDetectorRequest(null, "", Instant.now(), Instant.now());
        ActionListener<PreviewAnomalyDetectorResponse> previewResponse = new ActionListener<PreviewAnomalyDetectorResponse>() {
            @Override
            public void onResponse(PreviewAnomalyDetectorResponse response) {
                Assert.assertTrue(false);
            }

            @Override
            public void onFailure(Exception e) {
                Assert.assertTrue(e.getMessage().contains("Wrong input, no detector id"));
                inProgressLatch.countDown();
            }
        };
        action.doExecute(task, request, previewResponse);
        assertTrue(inProgressLatch.await(100, TimeUnit.SECONDS));
    }

    @Test
    public void testPreviewTransportActionWithDetectorID() throws IOException, InterruptedException {
        // When AD index does not exist, cannot query the detector
        final CountDownLatch inProgressLatch = new CountDownLatch(1);
        PreviewAnomalyDetectorRequest request = new PreviewAnomalyDetectorRequest(null, "1234", Instant.now(), Instant.now());
        ActionListener<PreviewAnomalyDetectorResponse> previewResponse = new ActionListener<PreviewAnomalyDetectorResponse>() {
            @Override
            public void onResponse(PreviewAnomalyDetectorResponse response) {
                Assert.assertTrue(false);
            }

            @Override
            public void onFailure(Exception e) {
                Assert.assertTrue(e.getMessage().contains("Could not execute get query to find detector"));
                inProgressLatch.countDown();
            }
        };
        action.doExecute(task, request, previewResponse);
        assertTrue(inProgressLatch.await(100, TimeUnit.SECONDS));
    }

    @Test
    public void testPreviewTransportActionWithIndex() throws IOException, InterruptedException {
        // When AD index exists, and detector does not exist
        final CountDownLatch inProgressLatch = new CountDownLatch(1);
        PreviewAnomalyDetectorRequest request = new PreviewAnomalyDetectorRequest(null, "1234", Instant.now(), Instant.now());
        Settings indexSettings = Settings.builder().put("index.number_of_shards", 5).put("index.number_of_replicas", 1).build();
        CreateIndexRequest indexRequest = new CreateIndexRequest(AnomalyDetector.ANOMALY_DETECTORS_INDEX, indexSettings);
        client().admin().indices().create(indexRequest).actionGet();
        ActionListener<PreviewAnomalyDetectorResponse> previewResponse = new ActionListener<PreviewAnomalyDetectorResponse>() {
            @Override
            public void onResponse(PreviewAnomalyDetectorResponse response) {
                Assert.assertTrue(false);
            }

            @Override
            public void onFailure(Exception e) {
                Assert.assertTrue(e.getMessage().contains("Can't find anomaly detector with id:1234"));
                inProgressLatch.countDown();
            }
        };
        action.doExecute(task, request, previewResponse);
        assertTrue(inProgressLatch.await(100, TimeUnit.SECONDS));
    }

    @Test
    public void testPreviewTransportActionNoContext() throws IOException, InterruptedException {
        final CountDownLatch inProgressLatch = new CountDownLatch(1);
        Settings settings = Settings.builder().put(AnomalyDetectorSettings.FILTER_BY_BACKEND_ROLES.getKey(), true).build();
        Client client = mock(Client.class);
        ThreadContext threadContext = new ThreadContext(settings);
        threadContext.putTransient(ConfigConstants.OPENDISTRO_SECURITY_USER_INFO_THREAD_CONTEXT, "alice|odfe,aes|engineering,operations");
        org.opensearch.threadpool.ThreadPool mockThreadPool = mock(ThreadPool.class);
        when(client.threadPool()).thenReturn(mockThreadPool);
        when(mockThreadPool.getThreadContext()).thenReturn(threadContext);
        PreviewAnomalyDetectorTransportAction previewAction = new PreviewAnomalyDetectorTransportAction(
            settings,
            mock(TransportService.class),
            clusterService,
            mock(ActionFilters.class),
            client,
            runner,
            xContentRegistry()
        );
        AnomalyDetector detector = TestHelpers.randomAnomalyDetector(ImmutableMap.of("testKey", "testValue"), Instant.now());
        PreviewAnomalyDetectorRequest request = new PreviewAnomalyDetectorRequest(
            detector,
            detector.getDetectorId(),
            Instant.now(),
            Instant.now()
        );
        ActionListener<PreviewAnomalyDetectorResponse> previewResponse = new ActionListener<PreviewAnomalyDetectorResponse>() {
            @Override
            public void onResponse(PreviewAnomalyDetectorResponse response) {
                Assert.assertTrue(false);
            }

            @Override
            public void onFailure(Exception e) {
                Assert.assertTrue(e.getClass() == NullPointerException.class);
                inProgressLatch.countDown();
            }
        };
        previewAction.doExecute(task, request, previewResponse);
        assertTrue(inProgressLatch.await(100, TimeUnit.SECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testPreviewTransportActionWithDetector() throws IOException, InterruptedException {
        final CountDownLatch inProgressLatch = new CountDownLatch(1);
        CreateIndexResponse createResponse = TestHelpers
            .createIndex(client().admin(), AnomalyDetector.ANOMALY_DETECTORS_INDEX, AnomalyDetectionIndices.getAnomalyDetectorMappings());
        Assert.assertNotNull(createResponse);

        AnomalyDetector detector = TestHelpers.randomAnomalyDetector(ImmutableMap.of("testKey", "testValue"), Instant.now());
        IndexRequest indexRequest = new IndexRequest(AnomalyDetector.ANOMALY_DETECTORS_INDEX)
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            .source(detector.toXContent(XContentFactory.jsonBuilder(), RestHandlerUtils.XCONTENT_WITH_TYPE));
        IndexResponse indexResponse = client().index(indexRequest).actionGet(5_000);
        assertEquals(RestStatus.CREATED, indexResponse.status());

        PreviewAnomalyDetectorRequest request = new PreviewAnomalyDetectorRequest(
            null,
            indexResponse.getId(),
            Instant.now(),
            Instant.now()
        );
        ActionListener<PreviewAnomalyDetectorResponse> previewResponse = new ActionListener<PreviewAnomalyDetectorResponse>() {
            @Override
            public void onResponse(PreviewAnomalyDetectorResponse response) {
                try {
                    XContentBuilder previewBuilder = response.toXContent(TestHelpers.builder(), ToXContent.EMPTY_PARAMS);
                    Assert.assertNotNull(previewBuilder);
                    Map<String, Object> map = TestHelpers.XContentBuilderToMap(previewBuilder);
                    List<AnomalyResult> results = (List<AnomalyResult>) map.get("anomaly_result");
                    Assert.assertNotNull(results);
                    Assert.assertTrue(results.size() > 0);
                    inProgressLatch.countDown();
                } catch (IOException e) {
                    // Should not reach here
                    Assert.assertTrue(false);
                }
            }

            @Override
            public void onFailure(Exception e) {
                // onFailure should not be called
                Assert.assertTrue(false);
            }
        };
        doReturn(TestHelpers.randomThresholdingResults()).when(modelManager).getPreviewResults(any());

        doAnswer(responseMock -> {
            Long startTime = responseMock.getArgument(1);
            ActionListener<Features> listener = responseMock.getArgument(3);
            listener.onResponse(TestHelpers.randomFeatures());
            return null;
        }).when(featureManager).getPreviewFeatures(anyObject(), anyLong(), anyLong(), any());
        action.doExecute(task, request, previewResponse);
        assertTrue(inProgressLatch.await(100, TimeUnit.SECONDS));
    }
}
