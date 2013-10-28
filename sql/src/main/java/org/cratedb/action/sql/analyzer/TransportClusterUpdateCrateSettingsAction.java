package org.cratedb.action.sql.analyzer;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import org.cratedb.service.SQLService;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsAction;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse;
import org.elasticsearch.action.admin.cluster.settings.TransportClusterUpdateSettingsAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.TimeoutClusterStateUpdateTask;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.settings.ClusterDynamicSettings;
import org.elasticsearch.cluster.settings.DynamicSettings;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Map;

public class TransportClusterUpdateCrateSettingsAction extends TransportClusterUpdateSettingsAction {

    public static final ImmutableList<String> ALLOWED_PREFIXES = ImmutableList.of(SQLService.CUSTOM_ANALYZER_SETTINGS_PREFIX);

    public TransportClusterUpdateCrateSettingsAction(Settings settings, TransportService transportService, ClusterService clusterService, ThreadPool threadPool, AllocationService allocationService, @ClusterDynamicSettings final DynamicSettings dynamicSettings) {
        super(settings, transportService, clusterService, threadPool, allocationService, dynamicSettings);
    }

    @Override
    protected String transportAction() {
        return ClusterUpdateSettingsAction.NAME;
    }

    /**
     *
     * checks if settingsKey starts with an allowed settings prefix
     *
     * @param settingsKey
     * @return true if settingsKey is an allowed Create Setting that can be updated dynamically, false otherwise
     */
    public boolean is_allowed(final String settingsKey) {
        for (int i=0;i<ALLOWED_PREFIXES.size();i++) {
            if (settingsKey.startsWith(ALLOWED_PREFIXES.get(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void masterOperation(final ClusterUpdateSettingsRequest request, final ClusterState state, final ActionListener<ClusterUpdateSettingsResponse> listener) throws ElasticSearchException {
        final ImmutableSettings.Builder transientUpdates = ImmutableSettings.settingsBuilder();
        final ImmutableSettings.Builder persistentUpdates = ImmutableSettings.settingsBuilder();

        clusterService.submitStateUpdateTask("cluster_update_settings", Priority.URGENT, new TimeoutClusterStateUpdateTask() {

            @Override
            public TimeValue timeout() {
                return request.masterNodeTimeout();
            }

            @Override
            public void onFailure(String source, Throwable t) {
                logger.debug("failed to perform [{}]", t, source);
                listener.onFailure(t);
            }

            @Override
            public ClusterState execute(final ClusterState currentState) {
                boolean changed = false;
                ImmutableSettings.Builder transientSettings = ImmutableSettings.settingsBuilder();
                transientSettings.put(currentState.metaData().transientSettings());
                for (Map.Entry<String, String> entry : request.transientSettings().getAsMap().entrySet()) {

                    transientSettings.put(entry.getKey(), entry.getValue());
                    transientUpdates.put(entry.getKey(), entry.getValue());
                    changed = true;

                }

                ImmutableSettings.Builder persistentSettings = ImmutableSettings.settingsBuilder();
                persistentSettings.put(currentState.metaData().persistentSettings());
                for (Map.Entry<String, String> entry : request.persistentSettings().getAsMap().entrySet()) {
                    if (entry.getKey())
                    persistentSettings.put(entry.getKey(), entry.getValue());
                    persistentUpdates.put(entry.getKey(), entry.getValue());
                    changed = true;

                }

                if (!changed) {
                    return currentState;
                }

                MetaData.Builder metaData = MetaData.builder().metaData(currentState.metaData())
                        .persistentSettings(persistentSettings.build())
                        .transientSettings(transientSettings.build());

                ClusterBlocks.Builder blocks = ClusterBlocks.builder().blocks(currentState.blocks());
                boolean updatedReadOnly = metaData.persistentSettings().getAsBoolean(MetaData.SETTING_READ_ONLY, false) || metaData.transientSettings().getAsBoolean(MetaData.SETTING_READ_ONLY, false);
                if (updatedReadOnly) {
                    blocks.addGlobalBlock(MetaData.CLUSTER_READ_ONLY_BLOCK);
                } else {
                    blocks.removeGlobalBlock(MetaData.CLUSTER_READ_ONLY_BLOCK);
                }

                return ClusterState.builder().state(currentState).metaData(metaData).blocks(blocks).build();
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                if (oldState == newState) {
                    // nothing changed...
                    listener.onResponse(new ClusterUpdateSettingsResponse(transientUpdates.build(), persistentUpdates.build()));
                    return;
                }
                // no rerouting necessary
            }
        });
    }
}
