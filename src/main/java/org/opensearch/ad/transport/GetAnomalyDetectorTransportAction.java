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

import static org.opensearch.ad.model.AnomalyDetector.ANOMALY_DETECTORS_INDEX;
import static org.opensearch.ad.model.AnomalyDetectorJob.ANOMALY_DETECTOR_JOB_INDEX;
import static org.opensearch.ad.settings.AnomalyDetectorSettings.FILTER_BY_BACKEND_ROLES;
import static org.opensearch.ad.util.ParseUtils.getUserContext;
import static org.opensearch.ad.util.ParseUtils.resolveUserAndExecute;
import static org.opensearch.ad.util.RestHandlerUtils.PROFILE;
import static org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionListener;
import org.opensearch.action.get.MultiGetItemResponse;
import org.opensearch.action.get.MultiGetRequest;
import org.opensearch.action.get.MultiGetResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.ad.AnomalyDetectorProfileRunner;
import org.opensearch.ad.EntityProfileRunner;
import org.opensearch.ad.Name;
import org.opensearch.ad.model.ADTask;
import org.opensearch.ad.model.AnomalyDetector;
import org.opensearch.ad.model.AnomalyDetectorJob;
import org.opensearch.ad.model.DetectorProfile;
import org.opensearch.ad.model.DetectorProfileName;
import org.opensearch.ad.model.EntityProfileName;
import org.opensearch.ad.settings.AnomalyDetectorSettings;
import org.opensearch.ad.task.ADTaskManager;
import org.opensearch.ad.util.DiscoveryNodeFilterer;
import org.opensearch.ad.util.RestHandlerUtils;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.CheckedConsumer;
import org.opensearch.common.Strings;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.rest.RestStatus;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import com.amazon.opendistroforelasticsearch.commons.authuser.User;
import com.google.common.collect.Sets;

public class GetAnomalyDetectorTransportAction extends HandledTransportAction<GetAnomalyDetectorRequest, GetAnomalyDetectorResponse> {

    private static final Logger LOG = LogManager.getLogger(GetAnomalyDetectorTransportAction.class);

    private final ClusterService clusterService;
    private final Client client;

    private final Set<String> allProfileTypeStrs;
    private final Set<DetectorProfileName> allProfileTypes;
    private final Set<DetectorProfileName> defaultDetectorProfileTypes;
    private final Set<String> allEntityProfileTypeStrs;
    private final Set<EntityProfileName> allEntityProfileTypes;
    private final Set<EntityProfileName> defaultEntityProfileTypes;
    private final NamedXContentRegistry xContentRegistry;
    private final DiscoveryNodeFilterer nodeFilter;
    private final TransportService transportService;
    private volatile Boolean filterByEnabled;
    private final ADTaskManager adTaskManager;

    @Inject
    public GetAnomalyDetectorTransportAction(
        TransportService transportService,
        DiscoveryNodeFilterer nodeFilter,
        ActionFilters actionFilters,
        ClusterService clusterService,
        Client client,
        Settings settings,
        NamedXContentRegistry xContentRegistry,
        ADTaskManager adTaskManager
    ) {
        super(GetAnomalyDetectorAction.NAME, transportService, actionFilters, GetAnomalyDetectorRequest::new);
        this.clusterService = clusterService;
        this.client = client;

        List<DetectorProfileName> allProfiles = Arrays.asList(DetectorProfileName.values());
        this.allProfileTypes = EnumSet.copyOf(allProfiles);
        this.allProfileTypeStrs = getProfileListStrs(allProfiles);
        List<DetectorProfileName> defaultProfiles = Arrays.asList(DetectorProfileName.ERROR, DetectorProfileName.STATE);
        this.defaultDetectorProfileTypes = new HashSet<DetectorProfileName>(defaultProfiles);

        List<EntityProfileName> allEntityProfiles = Arrays.asList(EntityProfileName.values());
        this.allEntityProfileTypes = EnumSet.copyOf(allEntityProfiles);
        this.allEntityProfileTypeStrs = getProfileListStrs(allEntityProfiles);
        List<EntityProfileName> defaultEntityProfiles = Arrays.asList(EntityProfileName.STATE);
        this.defaultEntityProfileTypes = new HashSet<EntityProfileName>(defaultEntityProfiles);

        this.xContentRegistry = xContentRegistry;
        this.nodeFilter = nodeFilter;
        filterByEnabled = AnomalyDetectorSettings.FILTER_BY_BACKEND_ROLES.get(settings);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(FILTER_BY_BACKEND_ROLES, it -> filterByEnabled = it);
        this.transportService = transportService;
        this.adTaskManager = adTaskManager;
    }

    @Override
    protected void doExecute(Task task, GetAnomalyDetectorRequest request, ActionListener<GetAnomalyDetectorResponse> listener) {
        String detectorID = request.getDetectorID();
        User user = getUserContext(client);
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            resolveUserAndExecute(
                user,
                detectorID,
                filterByEnabled,
                listener,
                () -> getExecute(request, listener),
                client,
                clusterService,
                xContentRegistry
            );
        } catch (Exception e) {
            LOG.error(e);
            listener.onFailure(e);
        }
    }

    protected void getExecute(GetAnomalyDetectorRequest request, ActionListener<GetAnomalyDetectorResponse> listener) {
        String detectorID = request.getDetectorID();
        String typesStr = request.getTypeStr();
        String rawPath = request.getRawPath();
        String entityValue = request.getEntityValue();
        boolean all = request.isAll();
        boolean returnJob = request.isReturnJob();
        boolean returnTask = request.isReturnTask();

        try {
            if (!Strings.isEmpty(typesStr) || rawPath.endsWith(PROFILE) || rawPath.endsWith(PROFILE + "/")) {
                if (entityValue != null) {
                    Set<EntityProfileName> entityProfilesToCollect = getEntityProfilesToCollect(typesStr, all);
                    EntityProfileRunner profileRunner = new EntityProfileRunner(
                        client,
                        xContentRegistry,
                        AnomalyDetectorSettings.NUM_MIN_SAMPLES
                    );
                    profileRunner
                        .profile(
                            detectorID,
                            entityValue,
                            entityProfilesToCollect,
                            ActionListener
                                .wrap(
                                    profile -> {
                                        listener
                                            .onResponse(
                                                new GetAnomalyDetectorResponse(
                                                    0,
                                                    null,
                                                    0,
                                                    0,
                                                    null,
                                                    null,
                                                    false,
                                                    null,
                                                    false,
                                                    null,
                                                    null,
                                                    profile,
                                                    true
                                                )
                                            );
                                    },
                                    e -> listener.onFailure(e)
                                )
                        );
                } else {
                    Set<DetectorProfileName> profilesToCollect = getProfilesToCollect(typesStr, all);
                    AnomalyDetectorProfileRunner profileRunner = new AnomalyDetectorProfileRunner(
                        client,
                        xContentRegistry,
                        nodeFilter,
                        AnomalyDetectorSettings.NUM_MIN_SAMPLES,
                        transportService,
                        adTaskManager
                    );
                    profileRunner.profile(detectorID, getProfileActionListener(listener), profilesToCollect);
                }
            } else {
                if (returnTask) {
                    adTaskManager
                        .getLatestADTask(
                            detectorID,
                            (adTask) -> getDetectorAndJob(detectorID, returnJob, returnTask, adTask, listener),
                            transportService,
                            listener
                        );
                } else {
                    getDetectorAndJob(detectorID, returnJob, returnTask, Optional.empty(), listener);
                }
            }
        } catch (Exception e) {
            LOG.error(e);
            listener.onFailure(e);
        }
    }

    private void getDetectorAndJob(
        String detectorID,
        boolean returnJob,
        boolean returnTask,
        Optional<ADTask> adTask,
        ActionListener<GetAnomalyDetectorResponse> listener
    ) {
        MultiGetRequest.Item adItem = new MultiGetRequest.Item(ANOMALY_DETECTORS_INDEX, detectorID);
        MultiGetRequest multiGetRequest = new MultiGetRequest().add(adItem);
        if (returnJob) {
            MultiGetRequest.Item adJobItem = new MultiGetRequest.Item(ANOMALY_DETECTOR_JOB_INDEX, detectorID);
            multiGetRequest.add(adJobItem);
        }
        client.multiGet(multiGetRequest, onMultiGetResponse(listener, returnJob, returnTask, adTask, detectorID));
    }

    private ActionListener<MultiGetResponse> onMultiGetResponse(
        ActionListener<GetAnomalyDetectorResponse> listener,
        boolean returnJob,
        boolean returnTask,
        Optional<ADTask> adTask,
        String detectorId
    ) {
        return new ActionListener<MultiGetResponse>() {
            @Override
            public void onResponse(MultiGetResponse multiGetResponse) {
                MultiGetItemResponse[] responses = multiGetResponse.getResponses();
                AnomalyDetector detector = null;
                AnomalyDetectorJob adJob = null;
                String id = null;
                long version = 0;
                long seqNo = 0;
                long primaryTerm = 0;

                for (MultiGetItemResponse response : responses) {
                    if (ANOMALY_DETECTORS_INDEX.equals(response.getIndex())) {
                        if (response.getResponse() == null || !response.getResponse().isExists()) {
                            listener
                                .onFailure(
                                    new OpenSearchStatusException("Can't find detector with id:  " + detectorId, RestStatus.NOT_FOUND)
                                );
                            return;
                        }
                        id = response.getId();
                        version = response.getResponse().getVersion();
                        primaryTerm = response.getResponse().getPrimaryTerm();
                        seqNo = response.getResponse().getSeqNo();
                        if (!response.getResponse().isSourceEmpty()) {
                            try (
                                XContentParser parser = RestHandlerUtils
                                    .createXContentParserFromRegistry(xContentRegistry, response.getResponse().getSourceAsBytesRef())
                            ) {
                                ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
                                detector = parser.namedObject(AnomalyDetector.class, AnomalyDetector.PARSE_FIELD_NAME, null);
                            } catch (Exception e) {
                                String message = "Failed to parse detector job " + detectorId;
                                listener.onFailure(buildInternalServerErrorResponse(e, message));
                                return;
                            }
                        }
                    }

                    if (ANOMALY_DETECTOR_JOB_INDEX.equals(response.getIndex())) {
                        if (response.getResponse() != null
                            && response.getResponse().isExists()
                            && !response.getResponse().isSourceEmpty()) {
                            try (
                                XContentParser parser = RestHandlerUtils
                                    .createXContentParserFromRegistry(xContentRegistry, response.getResponse().getSourceAsBytesRef())
                            ) {
                                ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
                                adJob = AnomalyDetectorJob.parse(parser);
                            } catch (Exception e) {
                                String message = "Failed to parse detector job " + detectorId;
                                listener.onFailure(buildInternalServerErrorResponse(e, message));
                                return;
                            }
                        }
                    }
                }
                listener
                    .onResponse(
                        new GetAnomalyDetectorResponse(
                            version,
                            id,
                            primaryTerm,
                            seqNo,
                            detector,
                            adJob,
                            returnJob,
                            adTask.orElse(null),
                            returnTask,
                            RestStatus.OK,
                            null,
                            null,
                            false
                        )
                    );
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        };
    }

    private ActionListener<DetectorProfile> getProfileActionListener(ActionListener<GetAnomalyDetectorResponse> listener) {
        return ActionListener.wrap(new CheckedConsumer<DetectorProfile, Exception>() {
            @Override
            public void accept(DetectorProfile profile) throws Exception {
                listener
                    .onResponse(new GetAnomalyDetectorResponse(0, null, 0, 0, null, null, false, null, false, null, profile, null, true));
            }
        }, exception -> { listener.onFailure(exception); });
    }

    private OpenSearchStatusException buildInternalServerErrorResponse(Exception e, String errorMsg) {
        LOG.error(errorMsg, e);
        return new OpenSearchStatusException(errorMsg, RestStatus.INTERNAL_SERVER_ERROR);
    }

    /**
    *
    * @param typesStr a list of input profile types separated by comma
    * @param all whether we should return all profile in the response
    * @return profiles to collect for a detector
    */
    private Set<DetectorProfileName> getProfilesToCollect(String typesStr, boolean all) {
        if (all) {
            return this.allProfileTypes;
        } else if (Strings.isEmpty(typesStr)) {
            return this.defaultDetectorProfileTypes;
        } else {
            // Filter out unsupported types
            Set<String> typesInRequest = new HashSet<>(Arrays.asList(typesStr.split(",")));
            return DetectorProfileName.getNames(Sets.intersection(allProfileTypeStrs, typesInRequest));
        }
    }

    /**
     *
     * @param typesStr a list of input profile types separated by comma
     * @param all whether we should return all profile in the response
     * @return profiles to collect for an entity
     */
    private Set<EntityProfileName> getEntityProfilesToCollect(String typesStr, boolean all) {
        if (all) {
            return this.allEntityProfileTypes;
        } else if (Strings.isEmpty(typesStr)) {
            return this.defaultEntityProfileTypes;
        } else {
            // Filter out unsupported types
            Set<String> typesInRequest = new HashSet<>(Arrays.asList(typesStr.split(",")));
            return EntityProfileName.getNames(Sets.intersection(allEntityProfileTypeStrs, typesInRequest));
        }
    }

    private Set<String> getProfileListStrs(List<? extends Name> profileList) {
        return profileList.stream().map(profile -> profile.getName()).collect(Collectors.toSet());
    }
}
