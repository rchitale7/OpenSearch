/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.support;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.ilm.DeleteAction;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleAction;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicy;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicyMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleType;
import org.elasticsearch.xpack.core.ilm.OperationMode;
import org.elasticsearch.xpack.core.ilm.TimeseriesLifecycleType;
import org.elasticsearch.xpack.core.ilm.action.PutLifecycleAction;
import org.elasticsearch.xpack.core.watcher.support.WatcherIndexTemplateRegistryField;
import org.elasticsearch.xpack.watcher.Watcher;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.mock.orig.Mockito.verify;
import static org.elasticsearch.mock.orig.Mockito.when;
import static org.elasticsearch.xpack.core.watcher.support.WatcherIndexTemplateRegistryField.INDEX_TEMPLATE_VERSION;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyZeroInteractions;

public class WatcherIndexTemplateRegistryTests extends ESTestCase {

    private WatcherIndexTemplateRegistry registry;
    private NamedXContentRegistry xContentRegistry;
    private ClusterService clusterService;
    private ThreadPool threadPool;
    private Client client;

    @Before
    public void createRegistryAndClient() {
        threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(threadPool.generic()).thenReturn(EsExecutors.newDirectExecutorService());

        client = mock(Client.class);
        when(client.threadPool()).thenReturn(threadPool);
        AdminClient adminClient = mock(AdminClient.class);
        IndicesAdminClient indicesAdminClient = mock(IndicesAdminClient.class);
        when(adminClient.indices()).thenReturn(indicesAdminClient);
        when(client.admin()).thenReturn(adminClient);
        doAnswer(invocationOnMock -> {
            ActionListener<AcknowledgedResponse> listener =
                    (ActionListener<AcknowledgedResponse>) invocationOnMock.getArguments()[1];
            listener.onResponse(new TestPutIndexTemplateResponse(true));
            return null;
        }).when(indicesAdminClient).putTemplate(any(PutIndexTemplateRequest.class), any(ActionListener.class));

        clusterService = mock(ClusterService.class);
        List<NamedXContentRegistry.Entry> entries = new ArrayList<>(ClusterModule.getNamedXWriteables());
        entries.addAll(Arrays.asList(
            new NamedXContentRegistry.Entry(LifecycleType.class, new ParseField(TimeseriesLifecycleType.TYPE),
                (p) -> TimeseriesLifecycleType.INSTANCE),
            new NamedXContentRegistry.Entry(LifecycleAction.class, new ParseField(DeleteAction.NAME), DeleteAction::parse)));
        xContentRegistry = new NamedXContentRegistry(entries);
        registry = new WatcherIndexTemplateRegistry(Settings.EMPTY, clusterService, threadPool, client, xContentRegistry);
    }

    public void testThatNonExistingTemplatesAreAddedImmediately() {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("node").add(node).build();

        ClusterChangedEvent event = createClusterChangedEvent(Collections.emptyMap(), nodes);
        registry.clusterChanged(event);
        ArgumentCaptor<PutIndexTemplateRequest> argumentCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(3)).putTemplate(argumentCaptor.capture(), anyObject());

        // now delete one template from the cluster state and lets retry
        Map<String, Integer> existingTemplates = new HashMap<>();
        existingTemplates.put(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        existingTemplates.put(WatcherIndexTemplateRegistryField.TRIGGERED_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        ClusterChangedEvent newEvent = createClusterChangedEvent(existingTemplates, nodes);
        registry.clusterChanged(newEvent);
        ArgumentCaptor<PutIndexTemplateRequest> captor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(4)).putTemplate(captor.capture(), anyObject());
        PutIndexTemplateRequest req = captor.getAllValues().stream()
            .filter(r -> r.name().equals(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected the watch history template to be put"));
        assertThat(req.settings().get("index.lifecycle.name"), equalTo("watch-history-ilm-policy"));
    }

    public void testThatNonExistingTemplatesAreAddedEvenWithILMDisabled() {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("node").add(node).build();

        registry = new WatcherIndexTemplateRegistry(Settings.builder()
            .put(XPackSettings.INDEX_LIFECYCLE_ENABLED.getKey(), false).build(),
            clusterService, threadPool, client, xContentRegistry);
        ClusterChangedEvent event = createClusterChangedEvent(Settings.EMPTY, Collections.emptyMap(), Collections.emptyMap(), nodes);
        registry.clusterChanged(event);
        ArgumentCaptor<PutIndexTemplateRequest> argumentCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(3)).putTemplate(argumentCaptor.capture(), anyObject());

        // now delete one template from the cluster state and lets retry
        Map<String, Integer> existingTemplates = new HashMap<>();
        existingTemplates.put(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        existingTemplates.put(WatcherIndexTemplateRegistryField.TRIGGERED_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        ClusterChangedEvent newEvent = createClusterChangedEvent(existingTemplates, nodes);
        registry.clusterChanged(newEvent);
        ArgumentCaptor<PutIndexTemplateRequest> captor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(5)).putTemplate(captor.capture(), anyObject());
        captor.getAllValues().forEach(req -> assertNull(req.settings().get("index.lifecycle.name")));
        verify(client, times(0)).execute(eq(PutLifecycleAction.INSTANCE), anyObject(), anyObject());
        assertSettingDeprecationsAndWarnings(new Setting<?>[] { XPackSettings.INDEX_LIFECYCLE_ENABLED });
    }

    public void testThatNonExistingTemplatesAreAddedEvenWithILMUsageDisabled() {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("node").add(node).build();

        registry = new WatcherIndexTemplateRegistry(Settings.builder()
            .put(Watcher.USE_ILM_INDEX_MANAGEMENT.getKey(), false).build(),
            clusterService, threadPool, client, xContentRegistry);
        ClusterChangedEvent event = createClusterChangedEvent(Settings.EMPTY, Collections.emptyMap(), Collections.emptyMap(), nodes);
        registry.clusterChanged(event);
        ArgumentCaptor<PutIndexTemplateRequest> argumentCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(3)).putTemplate(argumentCaptor.capture(), anyObject());

        // now delete one template from the cluster state and lets retry
        Map<String, Integer> existingTemplates = new HashMap<>();
        existingTemplates.put(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        existingTemplates.put(WatcherIndexTemplateRegistryField.TRIGGERED_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        ClusterChangedEvent newEvent = createClusterChangedEvent(existingTemplates, nodes);
        registry.clusterChanged(newEvent);
        ArgumentCaptor<PutIndexTemplateRequest> captor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(5)).putTemplate(captor.capture(), anyObject());
        captor.getAllValues().forEach(req -> assertNull(req.settings().get("index.lifecycle.name")));
        verify(client, times(0)).execute(eq(PutLifecycleAction.INSTANCE), anyObject(), anyObject());
    }

    public void testThatNonExistingPoliciesAreAddedImmediately() {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("node").add(node).build();

        ClusterChangedEvent event = createClusterChangedEvent(Collections.emptyMap(), nodes);
        registry.clusterChanged(event);
        verify(client, times(1)).execute(eq(PutLifecycleAction.INSTANCE), anyObject(), anyObject());
    }

    public void testPolicyAlreadyExists() {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("node").add(node).build();

        Map<String, LifecyclePolicy> policyMap = new HashMap<>();
        List<LifecyclePolicy> policies = registry.getPolicyConfigs().stream()
            .map(policyConfig -> policyConfig.load(xContentRegistry))
            .collect(Collectors.toList());
        assertThat(policies, hasSize(1));
        LifecyclePolicy policy = policies.get(0);
        policyMap.put(policy.getName(), policy);
        ClusterChangedEvent event = createClusterChangedEvent(Collections.emptyMap(), policyMap, nodes);
        registry.clusterChanged(event);
        verify(client, times(0)).execute(eq(PutLifecycleAction.INSTANCE), anyObject(), anyObject());
    }

    public void testNoPolicyButILMDisabled() {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("node").add(node).build();

        registry = new WatcherIndexTemplateRegistry(Settings.builder()
            .put(XPackSettings.INDEX_LIFECYCLE_ENABLED.getKey(), false).build(),
            clusterService, threadPool, client, xContentRegistry);
        ClusterChangedEvent event = createClusterChangedEvent(Settings.EMPTY, Collections.emptyMap(), Collections.emptyMap(), nodes);
        registry.clusterChanged(event);
        verify(client, times(0)).execute(eq(PutLifecycleAction.INSTANCE), anyObject(), anyObject());
        assertSettingDeprecationsAndWarnings(new Setting<?>[] { XPackSettings.INDEX_LIFECYCLE_ENABLED });
    }

    public void testNoPolicyButILMManagementDisabled() {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("node").add(node).build();

        registry = new WatcherIndexTemplateRegistry(Settings.builder()
            .put(Watcher.USE_ILM_INDEX_MANAGEMENT.getKey(), false).build(),
            clusterService, threadPool, client, xContentRegistry);
        ClusterChangedEvent event = createClusterChangedEvent(Settings.EMPTY, Collections.emptyMap(), Collections.emptyMap(), nodes);
        registry.clusterChanged(event);
        verify(client, times(0)).execute(eq(PutLifecycleAction.INSTANCE), anyObject(), anyObject());
    }

    public void testPolicyAlreadyExistsButDiffers() throws IOException {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("node").add(node).build();

        Map<String, LifecyclePolicy> policyMap = new HashMap<>();
        String policyStr = "{\"phases\":{\"delete\":{\"min_age\":\"1m\",\"actions\":{\"delete\":{}}}}}";
        List<LifecyclePolicy> policies = registry.getPolicyConfigs().stream()
            .map(policyConfig -> policyConfig.load(xContentRegistry))
            .collect(Collectors.toList());
        assertThat(policies, hasSize(1));
        LifecyclePolicy policy = policies.get(0);
        try (XContentParser parser = XContentType.JSON.xContent()
            .createParser(xContentRegistry, LoggingDeprecationHandler.THROW_UNSUPPORTED_OPERATION, policyStr)) {
            LifecyclePolicy different = LifecyclePolicy.parse(parser, policy.getName());
            policyMap.put(policy.getName(), different);
            ClusterChangedEvent event = createClusterChangedEvent(Collections.emptyMap(), policyMap, nodes);
            registry.clusterChanged(event);
            verify(client, times(0)).execute(eq(PutLifecycleAction.INSTANCE), anyObject(), anyObject());
        }
    }

    public void testThatTemplatesExist() {
        {
            Map<String, Integer> existingTemplates = new HashMap<>();
            existingTemplates.put(".watch-history", INDEX_TEMPLATE_VERSION);
            assertThat(WatcherIndexTemplateRegistry.validate(createClusterState(existingTemplates)), is(false));
        }

        {
            Map<String, Integer> existingTemplates = new HashMap<>();
            existingTemplates.put(".watch-history", INDEX_TEMPLATE_VERSION);
            existingTemplates.put(".triggered_watches", INDEX_TEMPLATE_VERSION);
            existingTemplates.put(".watches", INDEX_TEMPLATE_VERSION);
            assertThat(WatcherIndexTemplateRegistry.validate(createClusterState(existingTemplates)), is(false));
        }

        {
            Map<String, Integer> existingTemplates = new HashMap<>();
            existingTemplates.put(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
            existingTemplates.put(".triggered_watches", INDEX_TEMPLATE_VERSION);
            existingTemplates.put(".watches", INDEX_TEMPLATE_VERSION);
            assertThat(WatcherIndexTemplateRegistry.validate(createClusterState(existingTemplates)), is(true));
        }
        {
            Map<String, Integer> existingTemplates = new HashMap<>();
            existingTemplates.put(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
            existingTemplates.put(".triggered_watches", INDEX_TEMPLATE_VERSION);
            existingTemplates.put(".watches", INDEX_TEMPLATE_VERSION);
            existingTemplates.put("whatever", null);
            existingTemplates.put("else", null);

            assertThat(WatcherIndexTemplateRegistry.validate(createClusterState(existingTemplates)), is(true));
        }
    }

    // if a node is newer than the master node, the template needs to be applied as well
    // otherwise a rolling upgrade would not work as expected, when the node has a .watches shard on it
    public void testThatTemplatesAreAppliedOnNewerNodes() {
        DiscoveryNode localNode = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode masterNode = new DiscoveryNode("master", ESTestCase.buildNewFakeTransportAddress(), Version.V_6_0_0);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("master").add(localNode).add(masterNode).build();

        Map<String, Integer> existingTemplates = new HashMap<>();
        existingTemplates.put(WatcherIndexTemplateRegistryField.TRIGGERED_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        existingTemplates.put(WatcherIndexTemplateRegistryField.WATCHES_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        existingTemplates.put(".watch-history-6", 6);
        ClusterChangedEvent event = createClusterChangedEvent(existingTemplates, nodes);
        registry.clusterChanged(event);

        ArgumentCaptor<PutIndexTemplateRequest> argumentCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(1)).putTemplate(argumentCaptor.capture(), anyObject());
        assertThat(argumentCaptor.getValue().name(), is(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME_10));
    }

    public void testThatTemplatesWithHiddenAreAppliedOnNewerNodes() {
        DiscoveryNode node = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode masterNode = new DiscoveryNode("master", ESTestCase.buildNewFakeTransportAddress(), Version.V_6_0_0);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("master").masterNodeId("master").add(node).add(masterNode).build();

        Map<String, Integer> existingTemplates = new HashMap<>();
        existingTemplates.put(WatcherIndexTemplateRegistryField.TRIGGERED_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        existingTemplates.put(WatcherIndexTemplateRegistryField.WATCHES_TEMPLATE_NAME, INDEX_TEMPLATE_VERSION);
        existingTemplates.put(".watch-history-6", 6);
        ClusterChangedEvent event = createClusterChangedEvent(existingTemplates, nodes);
        registry.clusterChanged(event);

        ArgumentCaptor<PutIndexTemplateRequest> argumentCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(1)).putTemplate(argumentCaptor.capture(), anyObject());
        assertThat(argumentCaptor.getValue().name(), is(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME_10));

        existingTemplates.remove(".watch-history-6");
        existingTemplates.put(".watch-history-10", 10);
        masterNode = new DiscoveryNode("master", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        nodes = DiscoveryNodes.builder().localNodeId("master").masterNodeId("master").add(masterNode).add(node).build();
        event = createClusterChangedEvent(existingTemplates, nodes);
        registry.clusterChanged(event);

        argumentCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        verify(client.admin().indices(), times(2)).putTemplate(argumentCaptor.capture(), anyObject());
        assertThat(argumentCaptor.getValue().name(), is(WatcherIndexTemplateRegistryField.HISTORY_TEMPLATE_NAME));
    }

    public void testThatTemplatesAreNotAppliedOnSameVersionNodes() {
        DiscoveryNode localNode = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode masterNode = new DiscoveryNode("master", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").masterNodeId("master").add(localNode).add(masterNode).build();

        Map<String, Integer> existingTemplates = new HashMap<>();
        existingTemplates.put(WatcherIndexTemplateRegistryField.TRIGGERED_TEMPLATE_NAME, null);
        existingTemplates.put(WatcherIndexTemplateRegistryField.WATCHES_TEMPLATE_NAME, null);
        existingTemplates.put(".watch-history-6", null);
        ClusterChangedEvent event = createClusterChangedEvent(existingTemplates, nodes);
        registry.clusterChanged(event);

        verifyZeroInteractions(client);
    }

    public void testThatMissingMasterNodeDoesNothing() {
        DiscoveryNode localNode = new DiscoveryNode("node", ESTestCase.buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node").add(localNode).build();

        Map<String, Integer> existingTemplates = new HashMap<>();
        existingTemplates.put(WatcherIndexTemplateRegistryField.TRIGGERED_TEMPLATE_NAME, null);
        existingTemplates.put(WatcherIndexTemplateRegistryField.WATCHES_TEMPLATE_NAME, null);
        existingTemplates.put(".watch-history-6", null);
        ClusterChangedEvent event = createClusterChangedEvent(existingTemplates, nodes);
        registry.clusterChanged(event);

        verifyZeroInteractions(client);
    }

    private ClusterChangedEvent createClusterChangedEvent(Map<String, Integer> existingTemplateNames, DiscoveryNodes nodes) {
        return createClusterChangedEvent(existingTemplateNames, Collections.emptyMap(), nodes);
    }

    private ClusterState createClusterState(Settings nodeSettings,
                                            Map<String, Integer> existingTemplates,
                                            Map<String, LifecyclePolicy> existingPolicies,
                                            DiscoveryNodes nodes) {
        ImmutableOpenMap.Builder<String, IndexTemplateMetadata> indexTemplates = ImmutableOpenMap.builder();
        for (Map.Entry<String, Integer> template : existingTemplates.entrySet()) {
            final IndexTemplateMetadata mockTemplate = mock(IndexTemplateMetadata.class);
            when(mockTemplate.version()).thenReturn(template.getValue());
            when(mockTemplate.getVersion()).thenReturn(template.getValue());
            indexTemplates.put(template.getKey(), mockTemplate);
        }

        Map<String, LifecyclePolicyMetadata> existingILMMeta = existingPolicies.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> new LifecyclePolicyMetadata(e.getValue(), Collections.emptyMap(), 1, 1)));
        IndexLifecycleMetadata ilmMeta = new IndexLifecycleMetadata(existingILMMeta, OperationMode.RUNNING);

        return ClusterState.builder(new ClusterName("test"))
            .metadata(Metadata.builder()
                .templates(indexTemplates.build())
                .transientSettings(nodeSettings)
                .putCustom(IndexLifecycleMetadata.TYPE, ilmMeta)
                .build())
            .blocks(new ClusterBlocks.Builder().build())
            .nodes(nodes)
            .build();
    }

    private ClusterChangedEvent createClusterChangedEvent(Map<String, Integer> existingTemplateNames,
                                                          Map<String, LifecyclePolicy> existingPolicies,
                                                          DiscoveryNodes nodes) {
        return createClusterChangedEvent(Settings.EMPTY, existingTemplateNames, existingPolicies, nodes);
    }

    private ClusterChangedEvent createClusterChangedEvent(Settings nodeSettings,
                                                          Map<String, Integer> existingTemplates,
                                                          Map<String, LifecyclePolicy> existingPolicies,
                                                          DiscoveryNodes nodes) {
        ClusterState cs = createClusterState(nodeSettings, existingTemplates, existingPolicies, nodes);
        ClusterChangedEvent realEvent = new ClusterChangedEvent("created-from-test", cs,
            ClusterState.builder(new ClusterName("test")).build());
        ClusterChangedEvent event = spy(realEvent);
        when(event.localNodeMaster()).thenReturn(nodes.isLocalNodeElectedMaster());
        when(clusterService.state()).thenReturn(cs);
        return event;
    }

    private ClusterState createClusterState(Map<String, Integer> existingTemplates) {
        Metadata.Builder metadataBuilder = Metadata.builder();
        for (Map.Entry<String, Integer> template : existingTemplates.entrySet()) {
            metadataBuilder.put(IndexTemplateMetadata.builder(template.getKey())
                    .version(template.getValue())
                    .patterns(Arrays.asList(generateRandomStringArray(10, 100, false, false))));
        }

        return ClusterState.builder(new ClusterName("foo")).metadata(metadataBuilder.build()).build();
    }

    private static class TestPutIndexTemplateResponse extends AcknowledgedResponse {
        TestPutIndexTemplateResponse(boolean acknowledged) {
            super(acknowledged);
        }
    }
}
