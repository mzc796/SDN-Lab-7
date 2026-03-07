/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.arphandler.core;

import static java.util.Objects.requireNonNull;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.opendaylight.l2switch.arphandler.inventory.InventoryReader;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.MacAddress;
import org.opendaylight.yang.gen.v1.urn.opendaylight.address.tracker.rev140617.address.node.connector.Addresses;
import org.opendaylight.yang.gen.v1.urn.opendaylight.host.tracker.rev140624.HostNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.host.tracker.rev140624.host.AttachmentPoints;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnectorKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.NodeKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacket;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacketInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacketInputBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyKey;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Link;
import org.opendaylight.yangtools.binding.BindingInstanceIdentifier;
import org.opendaylight.yangtools.binding.DataObjectIdentifier;
import org.opendaylight.yangtools.binding.PropertyIdentifier;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PacketDispatcher handles ARP packets via packet-out (no flow installation).
 * <ul>
 *   <li>Unknown or broadcast destination: floods the packet out all ports of the ingress switch.</li>
 *   <li>Known destination: BFS-computes the shortest path and packet-outs from the ingress switch
 *       to the first hop; subsequent switches re-send to the controller (ARP→CONTROLLER flow)
 *       for hop-by-hop delivery.</li>
 * </ul>
 * Not installing ARP forward flows ensures the controller always sees every ARP packet,
 * so HostTracker stays accurate and ShortestPath can always find every host's location.
 * Host locations are resolved via the HostTracker topology (NetworkTopology/Topology[flow:1]/Node[host:MAC]).
 */
public class PacketDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(PacketDispatcher.class);
    private static final String DEFAULT_TOPOLOGY_ID = "flow:1";

    private final InventoryReader inventoryReader;
    private final TransmitPacket transmitPacket;
    private final DataBroker dataBroker;

    public PacketDispatcher(InventoryReader inventoryReader, TransmitPacket transmitPacket,
            DataBroker dataBroker) {
        this.inventoryReader = requireNonNull(inventoryReader);
        this.transmitPacket = requireNonNull(transmitPacket);
        this.dataBroker = requireNonNull(dataBroker);
    }

    /**
     * Dispatches an ARP packet via packet-out: floods for unknown/broadcast destination,
     * or forwards toward the known destination host.
     *
     * @param payload  raw Ethernet frame bytes
     * @param ingress  NodeConnector where the packet was received
     * @param srcMac   Ethernet source MAC
     * @param destMac  Ethernet destination MAC
     */
    public void dispatchPacket(byte[] payload, NodeConnectorRef ingress, MacAddress srcMac, MacAddress destMac) {
        inventoryReader.readInventory();

        final var ingressNodePath = getNodePath(ingress.getValue());
        String ingressNodeId = ingressNodePath.firstKeyOf(Node.class).getId().getValue();
        NodeConnectorRef ingressControllerRef = inventoryReader.getControllerSwitchConnectors().get(ingressNodeId);

        if (ingressControllerRef == null) {
            refreshInventoryReader();
            ingressControllerRef = inventoryReader.getControllerSwitchConnectors().get(ingressNodeId);
        }

        // Look up destination MAC via HostTracker topology (already transit-port filtered).
        // For broadcast/null destMac, findHostByMac returns null and we fall through to flood.
        NodeConnectorRef destNodeConnector = findHostByMac(destMac);

        if (destNodeConnector != null) {
            // Known dst: forward via packet-out along shortest path
            handleKnownDst(payload, ingress, ingressNodeId, destNodeConnector);
        } else if (ingressControllerRef != null) {
            // Unknown or broadcast destination: flood via packet-out.
            // No flow is installed so the controller always sees ARP traffic and can keep
            // HostTracker accurate for every host that sends or receives ARP.
            floodPacket(ingressNodeId, payload, ingress, ingressControllerRef);
        } else {
            LOG.info("Cannot dispatch ARP: controller connector unavailable for node {}.", ingressNodeId);
        }
    }

    /**
     * Looks up the host attachment point by Ethernet destination MAC using the HostTracker topology.
     * Returns {@code null} if destMac is null, broadcast, or not yet known to HostTracker.
     *
     * <p>HostTracker's {@code isNodeConnectorInternal()} is supposed to filter transit ports, but
     * it is timing-dependent: if LLDP topology links are not yet built when HostTracker first sees
     * a MAC (e.g. via a flooded ARP), it may record a transit port as an attachment point.
     * To guard against this, we rebuild the transit-port set ourselves from the switch-to-switch
     * links in the same topology snapshot and skip any attachment point that appears in it.
     */
    NodeConnectorRef findHostByMac(final MacAddress destMac) {
        if (destMac == null) {
            return null;
        }
        final Topology topo;
        try (ReadTransaction tx = dataBroker.newReadOnlyTransaction()) {
            var topoIid = DataObjectIdentifier.builder(NetworkTopology.class)
                .child(Topology.class, new TopologyKey(new TopologyId(DEFAULT_TOPOLOGY_ID)))
                .build();
            topo = tx.read(LogicalDatastoreType.OPERATIONAL, topoIid).get().orElse(null);
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("Failed to read topology for host MAC lookup", e);
            return null;
        }
        if (topo == null) {
            return null;
        }

        // Build set of transit TpIds from switch-to-switch links in the same topology snapshot.
        // This mirrors what HostTracker's isNodeConnectorInternal() does, but without the timing
        // dependency: both the links and the host nodes come from the same single read.
        var transitTps = new HashSet<String>();
        long switchCount = 0;
        for (var topoNode : topo.nonnullNode().values()) {
            if (!topoNode.getNodeId().getValue().startsWith("host:")) {
                switchCount++;
            }
        }
        for (Link link : topo.nonnullLink().values()) {
            String srcNode = link.getSource().getSourceNode().getValue();
            String dstNode = link.getDestination().getDestNode().getValue();
            if (!srcNode.startsWith("host:") && !dstNode.startsWith("host:")) {
                transitTps.add(link.getSource().getSourceTp().getValue());
                transitTps.add(link.getDestination().getDestTp().getValue());
            }
        }

        // In a multi-switch topology, if no LLDP links are present yet we cannot distinguish
        // transit ports from host-facing ports. HostTracker's isNodeConnectorInternal() has the
        // same blind spot: it also relies on these links. Without them, it may have recorded a
        // transit port (e.g. s2-eth1) as h1's attachment point, leading to wrong forwarding flows.
        // Return null to force safe flood delivery; forwarding flows will be installed correctly
        // once LLDP topology is established.
        if (switchCount > 1 && transitTps.isEmpty()) {
            LOG.debug("findHostByMac: LLDP topology not ready yet, forcing flood for MAC {}",
                destMac.getValue());
            return null;
        }

        for (var topoNode : topo.nonnullNode().values()) {
            if (!topoNode.getNodeId().getValue().startsWith("host:")) {
                continue;
            }
            HostNode hostNode = topoNode.augmentation(HostNode.class);
            if (hostNode == null) {
                continue;
            }

            // Check whether this host has the target MAC address (case-insensitive for safety)
            boolean hasMac = false;
            for (Addresses addr : hostNode.nonnullAddresses().values()) {
                if (addr.getMac() != null
                        && destMac.getValue().equalsIgnoreCase(addr.getMac().getValue())) {
                    hasMac = true;
                    break;
                }
            }
            if (!hasMac) {
                continue;
            }

            // Return the first active attachment point that is NOT a transit port
            for (AttachmentPoints ap : hostNode.nonnullAttachmentPoints().values()) {
                if (Boolean.TRUE.equals(ap.getActive())) {
                    String tpIdVal = ap.getTpId().getValue();
                    if (!transitTps.contains(tpIdVal)) {
                        int lastColon = tpIdVal.lastIndexOf(':');
                        String switchId = tpIdVal.substring(0, lastColon);
                        return buildNodeConnectorRef(switchId, tpIdVal);
                    }
                    LOG.debug("findHostByMac: skipping transit attachment {} for MAC {}",
                        tpIdVal, destMac.getValue());
                }
            }
        }
        return null;
    }

    /**
     * Forwards an ARP packet toward the known destination host via packet-out.
     * No flow entries are installed so the controller continues to see all ARP
     * traffic, keeping HostTracker accurate for every host.
     */
    private void handleKnownDst(byte[] payload, NodeConnectorRef ingress, String ingressNodeIdStr,
            NodeConnectorRef destConnector) {
        String dstNodeIdStr = getNodePath(destConnector.getValue()).firstKeyOf(Node.class).getId().getValue();

        if (ingressNodeIdStr.equals(dstNodeIdStr)) {
            // Same switch: send directly to the host port
            sendPacketOut(payload, ingress, destConnector);
            return;
        }

        // Multi-hop: find first egress port via BFS and packet-out from the ingress switch.
        // The next switch will send the packet to the controller again (ARP→CONTROLLER flow),
        // allowing hop-by-hop forwarding while always keeping HostTracker up to date.
        List<Map.Entry<String, String>> hops = computeArpPath(ingressNodeIdStr, dstNodeIdStr);
        if (!hops.isEmpty()) {
            String firstEgressUri = hops.get(0).getValue();
            sendPacketOut(payload, ingress, buildNodeConnectorRef(ingressNodeIdStr, firstEgressUri));
        }
    }

    /**
     * BFS through the network topology to find the shortest path from srcNodeId to dstNodeId.
     *
     * @return ordered list of (switchNodeId, egressPortUri) entries, one per hop switch,
     *         not including the final destination switch's host port entry
     */
    private List<Map.Entry<String, String>> computeArpPath(String srcNodeId, String dstNodeId) {
        List<Link> links = readTopologyLinks();
        if (links.isEmpty()) {
            LOG.warn("No topology links for ARP path {} -> {}", srcNodeId, dstNodeId);
            return Collections.emptyList();
        }

        // Build undirected adjacency: nodeId -> list of (neighborNodeId, egressTpId on this side)
        Map<String, List<Map.Entry<String, String>>> adj = new HashMap<>();
        for (Link link : links) {
            String src = link.getSource().getSourceNode().getValue();
            String srcTp = link.getSource().getSourceTp().getValue();
            String dst = link.getDestination().getDestNode().getValue();
            String dstTp = link.getDestination().getDestTp().getValue();
            adj.computeIfAbsent(src, k -> new ArrayList<>())
                .add(new AbstractMap.SimpleImmutableEntry<>(dst, srcTp));
            adj.computeIfAbsent(dst, k -> new ArrayList<>())
                .add(new AbstractMap.SimpleImmutableEntry<>(src, dstTp));
        }

        // BFS: prevNode[n] = the node we came from; prevTp[n] = egress TP on prevNode side
        Map<String, String> prevNode = new HashMap<>();
        Map<String, String> prevTp = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        visited.add(srcNodeId);
        queue.add(srcNodeId);

        outer:
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            for (var neighbor : adj.getOrDefault(curr, Collections.emptyList())) {
                String next = neighbor.getKey();
                if (!visited.contains(next)) {
                    visited.add(next);
                    prevNode.put(next, curr);
                    prevTp.put(next, neighbor.getValue());
                    if (next.equals(dstNodeId)) {
                        break outer;
                    }
                    queue.add(next);
                }
            }
        }

        if (!prevNode.containsKey(dstNodeId)) {
            LOG.warn("No path found from {} to {} in topology", srcNodeId, dstNodeId);
            return Collections.emptyList();
        }

        // Reconstruct path in forward order: [(srcNode, egressTp), ..., (penultimateNode, egressTp)]
        List<Map.Entry<String, String>> hops = new ArrayList<>();
        String cur = dstNodeId;
        while (prevNode.containsKey(cur)) {
            String prev = prevNode.get(cur);
            hops.add(0, new AbstractMap.SimpleImmutableEntry<>(prev, prevTp.get(cur)));
            cur = prev;
        }
        return hops;
    }

    /**
     * Reads switch-to-switch links from the flow:1 topology in the operational datastore.
     * Host attachment links (containing "host" in their ID) are excluded.
     */
    private List<Link> readTopologyLinks() {
        var topoIid = DataObjectIdentifier.builder(NetworkTopology.class)
            .child(Topology.class, new TopologyKey(new TopologyId(DEFAULT_TOPOLOGY_ID)))
            .build();
        try (ReadTransaction tx = dataBroker.newReadOnlyTransaction()) {
            Optional<Topology> opt = tx.read(LogicalDatastoreType.OPERATIONAL, topoIid).get();
            if (opt.isEmpty() || opt.orElseThrow().getLink() == null) {
                return Collections.emptyList();
            }
            List<Link> result = new ArrayList<>();
            for (Link link : opt.orElseThrow().getLink().values()) {
                if (!link.getLinkId().getValue().contains("host")) {
                    result.add(link);
                }
            }
            return result;
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("Failed to read topology links", e);
            return Collections.emptyList();
        }
    }

    /**
     * Floods the packet out all ports of the given switch except the ingress port.
     */
    public void floodPacket(String nodeId, byte[] payload, NodeConnectorRef origIngress,
            NodeConnectorRef controllerNodeConnector) {

        List<NodeConnectorRef> nodeConnectors = inventoryReader.getSwitchNodeConnectors().get(nodeId);

        if (nodeConnectors == null || nodeConnectors.isEmpty()) {
            refreshInventoryReader();
            nodeConnectors = inventoryReader.getSwitchNodeConnectors().get(nodeId);
            if (nodeConnectors == null || nodeConnectors.isEmpty()) {
                LOG.info("Cannot flood packets, as inventory doesn't have any node connectors for node {}", nodeId);
                return;
            }
        }
        for (NodeConnectorRef ncRef : nodeConnectors) {
            final var ncId = getNodeConnectorId(ncRef);
            if (!ncId.equals(getNodeConnectorId(origIngress))) {
                sendPacketOut(payload, origIngress, ncRef);
            }
        }
    }

    private static NodeConnectorId getNodeConnectorId(NodeConnectorRef ncRef) {
        final var container = switch (ncRef.getValue()) {
            case DataObjectIdentifier<?> doi -> doi;
            case PropertyIdentifier<?, ?> pi -> pi.container();
        };
        return container.toLegacy().firstKeyOf(NodeConnector.class).getId();
    }

    /**
     * Sends the specified packet out on the specified egress port.
     *
     * @param payload the raw packet bytes
     * @param ingress the original ingress NodeConnector
     * @param egress  the egress NodeConnector to send on
     */
    public void sendPacketOut(byte[] payload, NodeConnectorRef ingress, NodeConnectorRef egress) {
        if (ingress == null || egress == null) {
            return;
        }
        InstanceIdentifier<Node> egressNodePath = getNodePath(egress.getValue());
        TransmitPacketInput input = new TransmitPacketInputBuilder()
                .setPayload(payload)
                .setNode(new NodeRef(egressNodePath.toIdentifier()))
                .setEgress(egress)
                .setIngress(ingress)
                .build();

        Futures.addCallback(transmitPacket.invoke(input), new FutureCallback<RpcResult<?>>() {
            @Override
            public void onSuccess(RpcResult<?> result) {
                LOG.debug("transmitPacket was successful");
            }

            @Override
            public void onFailure(Throwable failure) {
                LOG.debug("transmitPacket for {} failed", input, failure);
            }
        }, MoreExecutors.directExecutor());
    }

    private static NodeConnectorRef buildNodeConnectorRef(String nodeIdStr, String ncIdStr) {
        return new NodeConnectorRef(DataObjectIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(new NodeId(nodeIdStr)))
            .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId(ncIdStr)))
            .build());
    }

    private void refreshInventoryReader() {
        inventoryReader.setRefreshData(true);
        inventoryReader.readInventory();
    }

    private static InstanceIdentifier<Node> getNodePath(final BindingInstanceIdentifier path) {
        return getNodePath(switch (path) {
            case DataObjectIdentifier<?> doi -> doi;
            case PropertyIdentifier<?, ?> pi -> pi.container();
        });
    }

    private static InstanceIdentifier<Node> getNodePath(final DataObjectIdentifier<?> nodeChild) {
        return nodeChild.toLegacy().firstIdentifierOf(Node.class);
    }
}
