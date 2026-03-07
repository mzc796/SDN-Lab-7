/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.shortestpath.core;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ExecutionException;
import org.opendaylight.l2switch.shortestpath.flow.ShortestPathFlowWriter;
import org.opendaylight.l2switch.shortestpath.topology.NetworkGraphManager;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.NotificationService.Listener;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
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
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.basepacket.rev140528.packet.chain.grp.PacketChain;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.basepacket.rev140528.packet.chain.grp.packet.chain.packet.RawPacket;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.basepacket.rev140528.packet.chain.grp.packet.chain.packet.raw.packet.RawPacketFields;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.ipv4.rev140528.Ipv4PacketReceived;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.ipv4.rev140528.ipv4.packet.received.packet.chain.packet.Ipv4Packet;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacket;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacketInputBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TpId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyKey;
import org.opendaylight.yangtools.binding.DataObjectIdentifier;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Ipv4PacketReceived notifications and installs shortest-path flows.
 */
public class ShortestPathPacketHandler implements Listener<Ipv4PacketReceived> {
    private static final Logger LOG = LoggerFactory.getLogger(ShortestPathPacketHandler.class);
    private static final String DEFAULT_TOPOLOGY_ID = "flow:1";

    private final DataBroker dataBroker;
    private final NetworkGraphManager graphManager;
    private final ShortestPathFlowWriter flowWriter;
    private final TransmitPacket transmitPacket;
    private final String topologyId;

    public ShortestPathPacketHandler(final DataBroker dataBroker, final NetworkGraphManager graphManager,
            final ShortestPathFlowWriter flowWriter, final TransmitPacket transmitPacket,
            final String topologyId) {
        this.dataBroker = requireNonNull(dataBroker);
        this.graphManager = requireNonNull(graphManager);
        this.flowWriter = requireNonNull(flowWriter);
        this.transmitPacket = requireNonNull(transmitPacket);
        this.topologyId = topologyId != null && !topologyId.isEmpty() ? topologyId : DEFAULT_TOPOLOGY_ID;
    }

    @Override
    public void onNotification(final Ipv4PacketReceived notification) {
        if (notification == null || notification.getPacketChain() == null) {
            return;
        }

        // Step 1: Extract raw packet and IPv4 packet from the chain
        RawPacketFields rawPacket = null;
        Ipv4Packet ipv4Packet = null;
        for (PacketChain pc : notification.getPacketChain()) {
            if (pc.getPacket() instanceof RawPacket rp) {
                rawPacket = rp.getRawPacketFields();
            } else if (pc.getPacket() instanceof Ipv4Packet ip) {
                ipv4Packet = ip;
            }
        }
        if (rawPacket == null || ipv4Packet == null) {
            return;
        }

        Ipv4Address dstIp = ipv4Packet.getDestinationIpv4();
        NodeConnectorRef ingressRef = rawPacket.getIngress();

        if (dstIp == null || ingressRef == null) {
            return;
        }

        // Step 2: Extract the ingress switch's inventory NodeId
        InstanceIdentifier<?> ingressIid = ((DataObjectIdentifier<?>) ingressRef.getValue()).toLegacy();
        NodeId ingressNodeId = ingressIid.firstKeyOf(Node.class).getId();

        // Step 3: Find destination host location via HostTracker topology (already transit-port filtered)
        HostLocation dst = findHostByIp(dstIp);
        if (dst == null) {
            LOG.debug("No host found for IP {}, will retry on next packet.", dstIp.getValue());
            return;
        }

        NodeId dstNodeId = dst.nodeId;
        TpId dstTpId = new TpId(dst.ncId.getValue());

        // Convert inventory NodeId to topology NodeId for graph lookups
        var topoIngress =
            new org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId(
                ingressNodeId.getValue());
        var topoDst =
            new org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId(
                dstNodeId.getValue());

        // Step 4: Same-switch case
        if (ingressNodeId.getValue().equals(dstNodeId.getValue())) {
            LOG.debug("Same-switch path for {}: node={} port={}", dstIp.getValue(),
                dstNodeId.getValue(), dstTpId.getValue());
            flowWriter.installFlow(dstNodeId, dstIp, dstTpId);
            sendPacket(notification.getPayload(), ingressRef,
                buildNodeConnectorRef(dstNodeId, dstTpId));
            return;
        }

        // Step 5: Multi-hop shortest path
        var path = graphManager.getPath(topoIngress, topoDst);

        if (path == null || path.isEmpty()) {
            LOG.warn("No path found from {} to {}", ingressNodeId.getValue(), dstNodeId.getValue());
            return;
        }

        var hops = graphManager.getHops(topoIngress, path);

        // Install a flow on each hop switch directing traffic to the next port
        for (var hop : hops) {
            NodeId hopNodeId = new NodeId(hop.getKey().getValue());
            flowWriter.installFlow(hopNodeId, dstIp, hop.getValue());
        }
        // Install a flow on the destination switch directing traffic to the host port
        flowWriter.installFlow(dstNodeId, dstIp, dstTpId);
    }

    /**
     * Looks up the host attachment point by IPv4 address using the HostTracker topology.
     * The HostTracker already filters out transit (switch-to-switch) ports, so the returned
     * attachment point is guaranteed to be the edge port where the host is directly connected.
     */
    private HostLocation findHostByIp(final Ipv4Address dstIp) {
        final Topology topo;
        try (ReadTransaction tx = dataBroker.newReadOnlyTransaction()) {
            var topoIid = DataObjectIdentifier.builder(NetworkTopology.class)
                .child(Topology.class, new TopologyKey(new TopologyId(topologyId)))
                .build();
            topo = tx.read(LogicalDatastoreType.OPERATIONAL, topoIid).get().orElse(null);
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("Failed to read topology for host lookup", e);
            return null;
        }
        if (topo == null) {
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

            // Check whether this host has the target IPv4 address
            boolean hasIp = false;
            for (Addresses addr : hostNode.nonnullAddresses().values()) {
                IpAddress ip = addr.getIp();
                if (ip != null && dstIp.equals(ip.getIpv4Address())) {
                    hasIp = true;
                    break;
                }
            }
            if (!hasIp) {
                continue;
            }

            // Return the first active attachment point
            for (AttachmentPoints ap : hostNode.nonnullAttachmentPoints().values()) {
                if (Boolean.TRUE.equals(ap.getActive())) {
                    String tpIdVal = ap.getTpId().getValue();
                    int lastColon = tpIdVal.lastIndexOf(':');
                    String switchId = tpIdVal.substring(0, lastColon);
                    return new HostLocation(new NodeId(switchId), new NodeConnectorId(tpIdVal));
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    private void sendPacket(final byte[] payload, final NodeConnectorRef ingress,
            final NodeConnectorRef egress) {
        InstanceIdentifier<Node> nodeIID = ((DataObjectIdentifier<?>) egress.getValue())
            .toLegacy().firstIdentifierOf(Node.class);
        transmitPacket.invoke(new TransmitPacketInputBuilder()
            .setPayload(payload)
            .setNode(new NodeRef(nodeIID.toIdentifier()))
            .setEgress(egress)
            .setIngress(ingress)
            .build());
    }

    @SuppressWarnings("unused")
    static NodeConnectorRef buildNodeConnectorRef(final NodeId nodeId, final TpId tpId) {
        return new NodeConnectorRef(DataObjectIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(nodeId))
            .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId(tpId.getValue())))
            .build());
    }

    /** Holder for a discovered host location in the inventory. */
    private record HostLocation(NodeId nodeId, NodeConnectorId ncId) {}
}
