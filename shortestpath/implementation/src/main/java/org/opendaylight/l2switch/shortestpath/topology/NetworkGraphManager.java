/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.shortestpath.topology;

import static java.util.Objects.requireNonNull;

import com.google.common.util.concurrent.FluentFuture;
import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath;
import edu.uci.ics.jung.graph.Graph;
import edu.uci.ics.jung.graph.SparseMultigraph;
import edu.uci.ics.jung.graph.util.EdgeType;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.DataObjectModification;
import org.opendaylight.mdsal.binding.api.DataTreeChangeListener;
import org.opendaylight.mdsal.binding.api.DataTreeModification;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TpId;
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
 * Manages the network topology graph for shortest-path routing.
 * Listens to topology link changes and builds a JUNG graph for Dijkstra queries.
 */
public class NetworkGraphManager implements DataTreeChangeListener<Link> {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkGraphManager.class);
    private static final String DEFAULT_TOPOLOGY_ID = "flow:1";
    private static final long GRAPH_REFRESH_DELAY_MS = 1000;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    private final DataBroker dataBroker;

    private String topologyId = DEFAULT_TOPOLOGY_ID;
    private volatile boolean refreshScheduled = false;
    private volatile boolean threadReschedule = false;

    @GuardedBy("this")
    private Graph<NodeId, Link> graph = null;
    @GuardedBy("this")
    private Set<String> linkAdded = new HashSet<>();

    public NetworkGraphManager(final DataBroker dataBroker) {
        this.dataBroker = requireNonNull(dataBroker);
    }

    public void setTopologyId(final String topologyId) {
        if (topologyId == null || topologyId.isEmpty()) {
            this.topologyId = DEFAULT_TOPOLOGY_ID;
        } else {
            this.topologyId = topologyId;
        }
    }

    public Registration registerAsDataChangeListener() {
        return dataBroker.registerLegacyTreeChangeListener(LogicalDatastoreType.OPERATIONAL,
            DataObjectReference.builder(NetworkTopology.class)
                .child(Topology.class, new TopologyKey(new TopologyId(topologyId)))
                .child(Link.class)
                .build(), this);
    }

    @Override
    public void onDataTreeChanged(final List<DataTreeModification<Link>> changes) {
        boolean isGraphUpdated = false;
        for (DataTreeModification<Link> change : changes) {
            DataObjectModification<Link> rootNode = change.getRootNode();
            switch (rootNode.modificationType()) {
                case WRITE:
                    Link createdLink = rootNode.dataAfter();
                    if (rootNode.dataBefore() == null && !createdLink.getLinkId().getValue().contains("host")) {
                        isGraphUpdated = true;
                        LOG.debug("Graph updated: added link {}", createdLink.getLinkId().getValue());
                    }
                    break;
                case DELETE:
                    Link deletedLink = rootNode.dataBefore();
                    if (!deletedLink.getLinkId().getValue().contains("host")) {
                        isGraphUpdated = true;
                        LOG.debug("Graph updated: removed link {}", deletedLink.getLinkId().getValue());
                    }
                    break;
                default:
                    break;
            }
        }

        if (!isGraphUpdated) {
            return;
        }
        if (!refreshScheduled) {
            synchronized (this) {
                if (!refreshScheduled) {
                    executor.schedule(this::processTopologyDataChangeEvents, GRAPH_REFRESH_DELAY_MS,
                        TimeUnit.MILLISECONDS);
                    refreshScheduled = true;
                    LOG.debug("Scheduled graph refresh.");
                }
            }
        } else {
            threadReschedule = true;
        }
    }

    private void processTopologyDataChangeEvents() {
        if (threadReschedule) {
            executor.schedule(this::processTopologyDataChangeEvents, GRAPH_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
            threadReschedule = false;
            return;
        }

        refreshScheduled = false;
        List<Link> links = getLinksFromTopology();
        if (links == null || links.isEmpty()) {
            LOG.debug("No links found in topology.");
            return;
        }
        rebuildGraph(links);
        LOG.debug("Graph rebuild complete with {} links.", links.size());
    }

    private synchronized void rebuildGraph(final List<Link> links) {
        graph = SparseMultigraph.<NodeId, Link>getFactory().get();
        linkAdded = new HashSet<>();
        for (Link link : links) {
            if (linkAlreadyAdded(link)) {
                continue;
            }
            NodeId srcNode = link.getSource().getSourceNode();
            NodeId dstNode = link.getDestination().getDestNode();
            graph.addVertex(srcNode);
            graph.addVertex(dstNode);
            graph.addEdge(link, srcNode, dstNode, EdgeType.UNDIRECTED);
        }
    }

    @GuardedBy("this")
    private boolean linkAlreadyAdded(final Link link) {
        String srcTp = link.getSource().getSourceTp().getValue();
        String dstTp = link.getDestination().getDestTp().getValue();
        String key = srcTp.hashCode() > dstTp.hashCode() ? srcTp + dstTp : dstTp + srcTp;
        if (linkAdded.contains(key)) {
            return true;
        }
        linkAdded.add(key);
        return false;
    }

    private List<Link> getLinksFromTopology() {
        DataObjectIdentifier<Topology> topoIid = DataObjectIdentifier.builder(NetworkTopology.class)
            .child(Topology.class, new TopologyKey(new TopologyId(topologyId)))
            .build();

        final FluentFuture<Optional<Topology>> readFuture;
        try (ReadTransaction tx = dataBroker.newReadOnlyTransaction()) {
            readFuture = tx.read(LogicalDatastoreType.OPERATIONAL, topoIid);
        }

        final Optional<Topology> topoOpt;
        try {
            topoOpt = readFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("Error reading topology {}", topoIid, e);
            return null;
        }

        if (topoOpt.isEmpty()) {
            return null;
        }
        Map<LinkKey, Link> links = topoOpt.orElseThrow().getLink();
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

    /**
     * Returns the shortest path (list of links) between src and dst using Dijkstra.
     * Uses topology NodeId (e.g. "openflow:1").
     */
    public synchronized List<Link> getPath(final NodeId src, final NodeId dst) {
        if (graph == null) {
            LOG.debug("Graph not yet initialized.");
            return Collections.emptyList();
        }
        try {
            List<Link> path = new DijkstraShortestPath<>(graph).getPath(src, dst);
            return path != null ? path : Collections.emptyList();
        } catch (IllegalArgumentException e) {
            LOG.warn("Failed to compute path from {} to {}: {}", src, dst, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Converts a list of links into an ordered list of (nodeId, egressTpId) hops.
     * The returned list does NOT include the final destination switch entry —
     * callers must install a separate flow on the destination switch to the host port.
     *
     * @param srcNodeId the topology NodeId of the ingress switch
     * @param path      the ordered list of links from getPath()
     * @return ordered list of Map.Entry(nodeId, egressTpId) per hop
     */
    public synchronized List<Map.Entry<NodeId, TpId>> getHops(final NodeId srcNodeId, final List<Link> path) {
        List<Map.Entry<NodeId, TpId>> hops = new ArrayList<>();
        NodeId cur = srcNodeId;
        for (Link edge : path) {
            NodeId linkSrcNode = edge.getSource().getSourceNode();
            TpId egressTp;
            NodeId next;
            if (linkSrcNode.equals(cur)) {
                egressTp = edge.getSource().getSourceTp();
                next = edge.getDestination().getDestNode();
            } else {
                egressTp = edge.getDestination().getDestTp();
                next = edge.getSource().getSourceNode();
            }
            hops.add(new AbstractMap.SimpleImmutableEntry<>(cur, egressTp));
            cur = next;
        }
        return hops;
    }
}
