/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.loopremover.topology;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FluentFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.DataObjectModification;
import org.opendaylight.mdsal.binding.api.DataTreeChangeListener;
import org.opendaylight.mdsal.binding.api.DataTreeModification;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnectorKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.NodeKey;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyKey;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Link;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.LinkKey;
import org.opendaylight.yangtools.binding.DataObjectIdentifier;
import org.opendaylight.yangtools.binding.DataObjectReference;
import org.opendaylight.yangtools.concepts.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens to data change events on topology links {@link
 * org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Link}
 * and maintains a topology graph using provided NetworkGraphService
 * {@link org.opendaylight.l2switch.loopremover.topology.NetworkGraphService}.
 * It refreshes the graph after a delay(default 10 sec) to accommodate burst of
 * change events if they come in bulk. This is to avoid continuous refresh of
 * graph on a series of change events in short time.
 */
public class TopologyLinkDataChangeHandler implements DataTreeChangeListener<Link> {
    private static final Logger LOG = LoggerFactory.getLogger(TopologyLinkDataChangeHandler.class);
    private static final String DEFAULT_TOPOLOGY_ID = "flow:1";
    private static final long DEFAULT_GRAPH_REFRESH_DELAY = 1000;

    private final ScheduledExecutorService topologyDataChangeEventProcessor = Executors.newScheduledThreadPool(1);
    private final NetworkGraphService networkGraphService;
    private final DataBroker dataBroker;

    private volatile boolean networkGraphRefreshScheduled = false;
    private volatile boolean threadReschedule = false;
    private long graphRefreshDelay;
    private String topologyId;

    public TopologyLinkDataChangeHandler(final DataBroker dataBroker, final NetworkGraphService networkGraphService) {
        this.dataBroker = requireNonNull(dataBroker);
        this.networkGraphService = requireNonNull(networkGraphService);
    }

    public void setGraphRefreshDelay(final long graphRefreshDelay) {
        if (graphRefreshDelay < 0) {
            this.graphRefreshDelay = DEFAULT_GRAPH_REFRESH_DELAY;
        } else {
            this.graphRefreshDelay = graphRefreshDelay;
        }
    }

    public void setTopologyId(final String topologyId) {
        if (topologyId == null || topologyId.isEmpty()) {
            this.topologyId = DEFAULT_TOPOLOGY_ID;
        } else {
            this.topologyId = topologyId;
        }
    }

    /**
     * Registers as a data listener to receive changes done to {@link org.opendaylight.yang.gen.v1.urn.tbd.params
     * .xml.ns.yang.network.topology.rev131021.network.topology.topology.Link}
     * under
     * {@link org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology}
     * operation data root.
     */
    public Registration registerAsDataChangeListener() {
        return dataBroker.registerLegacyTreeChangeListener(LogicalDatastoreType.OPERATIONAL,
            DataObjectReference.builder(NetworkTopology.class)
                .child(Topology.class, new TopologyKey(new TopologyId(topologyId)))
                .child(Link.class)
                .build(), this);
    }

    /**
     * Handler for onDataChanged events and schedules the building of the
     * network graph.
     */
    @Override
    public void onDataTreeChanged(final List<DataTreeModification<Link>> changes) {
        boolean isGraphUpdated = false;

        for (DataTreeModification<Link> change: changes) {
            DataObjectModification<Link> rootNode = change.getRootNode();
            switch (rootNode.modificationType()) {
                case WRITE:
                    Link createdLink = rootNode.dataAfter();
                    if (rootNode.dataBefore() == null && !createdLink.getLinkId().getValue().contains("host")) {
                        isGraphUpdated = true;
                        LOG.debug("Graph is updated! Added Link {}", createdLink.getLinkId().getValue());
                    }
                    break;
                case DELETE:
                    Link deletedLink = rootNode.dataBefore();
                    if (!deletedLink.getLinkId().getValue().contains("host")) {
                        isGraphUpdated = true;
                        LOG.debug("Graph is updated! Removed Link {}", deletedLink.getLinkId().getValue());
                        break;
                    }
                    break;
                default:
                    break;
            }
        }

        if (!isGraphUpdated) {
            return;
        }
        if (!networkGraphRefreshScheduled) {
            synchronized (this) {
                if (!networkGraphRefreshScheduled) {
                    topologyDataChangeEventProcessor.schedule(this::processTopologyDataChangeEvents, graphRefreshDelay,
                            TimeUnit.MILLISECONDS);
                    networkGraphRefreshScheduled = true;
                    LOG.debug("Scheduled Graph for refresh.");
                }
            }
        } else {
            LOG.debug("Already scheduled for network graph refresh.");
            threadReschedule = true;
        }
    }

    private void processTopologyDataChangeEvents() {
        if (threadReschedule) {
            topologyDataChangeEventProcessor.schedule(this::processTopologyDataChangeEvents, graphRefreshDelay,
                TimeUnit.MILLISECONDS);
            threadReschedule = false;
            return;
        }

        LOG.debug("In network graph refresh thread.");
        networkGraphRefreshScheduled = false;
        networkGraphService.clear();
        List<Link> links = getLinksFromTopology();
        if (links == null || links.isEmpty()) {
            return;
        }
        networkGraphService.addLinks(links);
        LOG.debug("Done with network graph refresh thread.");
    }

    private List<Link> getLinksFromTopology() {
        final var topologyInstanceIdentifier = generateTopologyInstanceIdentifier(topologyId);
        final FluentFuture<Optional<Topology>> readFuture;
        try (ReadTransaction readOnlyTransaction = dataBroker.newReadOnlyTransaction()) {
            readFuture = readOnlyTransaction.read(LogicalDatastoreType.OPERATIONAL, topologyInstanceIdentifier);
        }

        final Optional<Topology> topologyOptional;
        try {
            topologyOptional = readFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("Error reading topology {}", topologyInstanceIdentifier);
            throw new RuntimeException(
                "Error reading from operational store, topology : " + topologyInstanceIdentifier, e);
        }

        if (topologyOptional.isEmpty()) {
            return null;
        }
        final Topology topology = topologyOptional.orElseThrow();
        final Map<LinkKey, Link> links = topology.getLink();
        if (links == null || links.isEmpty()) {
            return null;
        }

        List<Link> internalLinks = new ArrayList<>();
        for (Link link : links.values()) {
            if (!link.getLinkId().getValue().contains("host")) {
                internalLinks.add(link);
            }
        }
        return internalLinks;
    }

    @VisibleForTesting
    static DataObjectIdentifier<NodeConnector> createNodeConnectorIdentifier(final String nodeIdValue,
            final String nodeConnectorIdValue) {
        return DataObjectIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(new NodeId(nodeIdValue)))
            .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId(nodeConnectorIdValue)))
            .build();
    }

    @VisibleForTesting
    static DataObjectIdentifier<Topology> generateTopologyInstanceIdentifier(final String topologyId) {
        return DataObjectIdentifier.builder(NetworkTopology.class)
            .child(Topology.class, new TopologyKey(new TopologyId(topologyId)))
            .build();
    }
}
