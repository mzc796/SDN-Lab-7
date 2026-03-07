/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.arphandler.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opendaylight.l2switch.arphandler.inventory.InventoryReader;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.MacAddress;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnectorKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.NodeKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacket;
import org.opendaylight.yangtools.binding.DataObjectIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;

@ExtendWith(MockitoExtension.class)
class PacketDispatcherTest {
    @Mock
    private TransmitPacket transmitPacket;
    @Mock
    private InventoryReader inventoryReader;
    @Mock
    private HashMap<String, List<NodeConnectorRef>> switchNodeConnectors;
    @Mock
    private DataBroker dataBroker;

    private PacketDispatcher packetDispatcher;

    @BeforeEach
    void beforeEach() {
        packetDispatcher = spy(new PacketDispatcher(inventoryReader, transmitPacket, dataBroker));
    }

    @Test
    void testSendPacketOut() {
        doReturn(RpcResultBuilder.success().buildFuture()).when(transmitPacket).invoke(any());

        final var ncInsId1 = DataObjectIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(new NodeId("abc")))
            .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("1")))
            .build();
        packetDispatcher.sendPacketOut(null, new NodeConnectorRef(ncInsId1), new NodeConnectorRef(ncInsId1));
        verify(transmitPacket, times(1)).invoke(any());
    }

    @Test
    void testSendPacketOut_NullIngress() {
        packetDispatcher.sendPacketOut(null, null, new NodeConnectorRef(DataObjectIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(new NodeId("abc")))
            .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("def")))
            .build()));
        verify(transmitPacket, times(0)).invoke(any());
    }

    @Test
    void testSendPacketOut_NullEgress() {
        packetDispatcher.sendPacketOut(null, new NodeConnectorRef(DataObjectIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(new NodeId("abc")))
            .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("def")))
            .build()), null);
        verify(transmitPacket, times(0)).invoke(any());
    }

    @Test
    void testFloodPacket() {
        doReturn(RpcResultBuilder.success().buildFuture()).when(transmitPacket).invoke(any());

        var nodeConnectors = new ArrayList<NodeConnectorRef>();
        var ncInsId1 = DataObjectIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(new NodeId("abc")))
            .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("1")))
            .build();
        var ncInsId2 = DataObjectIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(new NodeId("abc")))
            .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("2")))
            .build();
        nodeConnectors.add(new NodeConnectorRef(ncInsId1));
        nodeConnectors.add(new NodeConnectorRef(ncInsId1));
        nodeConnectors.add(new NodeConnectorRef(ncInsId2));
        when(switchNodeConnectors.get(any(String.class))).thenReturn(nodeConnectors);
        when(inventoryReader.getSwitchNodeConnectors()).thenReturn(switchNodeConnectors);

        packetDispatcher.floodPacket("", null, new NodeConnectorRef(ncInsId2),
                new NodeConnectorRef(DataObjectIdentifier.builder(Nodes.class)
                    .child(Node.class, new NodeKey(new NodeId("abc")))
                    .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("def")))
                    .build()));
        verify(inventoryReader, times(0)).setRefreshData(true);
        verify(inventoryReader, times(0)).readInventory();
        verify(transmitPacket, times(2)).invoke(any());
    }

    @Test
    void testFloodPacket_NullList() {
        when(switchNodeConnectors.get(any(String.class))).thenReturn(null);
        when(inventoryReader.getSwitchNodeConnectors()).thenReturn(switchNodeConnectors);

        packetDispatcher.floodPacket("", null,
            new NodeConnectorRef(DataObjectIdentifier.builder(Nodes.class)
                .child(Node.class, new NodeKey(new NodeId("abc")))
                .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("def")))
                .build()),
            new NodeConnectorRef(DataObjectIdentifier.builder(Nodes.class)
                .child(Node.class, new NodeKey(new NodeId("abc")))
                .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("def")))
                .build()));
        verify(inventoryReader, times(1)).setRefreshData(true);
        verify(inventoryReader, times(1)).readInventory();
        verify(transmitPacket, times(0)).invoke(any());
    }

    // ---- dispatchPacket tests ----
    // findHostByMac() is stubbed via spy to avoid needing a full HostTracker topology mock.

    @Test
    void testDispatchPacket_noDispatch() {
        // findHostByMac returns null (host not known), controller connector map is empty
        doReturn(null).when(packetDispatcher).findHostByMac(any());
        when(inventoryReader.getControllerSwitchConnectors()).thenReturn(new HashMap<>());

        var ncInsId = DataObjectIdentifier.builder(Nodes.class)
                .child(Node.class, new NodeKey(new NodeId("1")))
                .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("1"))).build();

        packetDispatcher.dispatchPacket(null, new NodeConnectorRef(ncInsId), null,
            new MacAddress("aa:bb:cc:dd:ee:ff"));
        verify(inventoryReader, times(2)).readInventory();
        verify(inventoryReader, times(1)).setRefreshData(true);
        verify(transmitPacket, times(0)).invoke(any());
    }

    @Test
    void testDispatchPacket_toSendPacketOut() {
        doReturn(RpcResultBuilder.success().buildFuture()).when(transmitPacket).invoke(any());

        var ncInsId1 = DataObjectIdentifier.builder(Nodes.class)
                .child(Node.class, new NodeKey(new NodeId("1")))
                .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("1"))).build();
        var ncRef1 = new NodeConnectorRef(ncInsId1);

        // findHostByMac returns a connector on the same switch (node "1") → same-switch path
        doReturn(ncRef1).when(packetDispatcher).findHostByMac(any());

        // Controller connector for switch "1" is ncRef1
        var connMap = new HashMap<String, NodeConnectorRef>();
        connMap.put("1", ncRef1);
        when(inventoryReader.getControllerSwitchConnectors()).thenReturn(connMap);

        packetDispatcher.dispatchPacket(null, new NodeConnectorRef(ncInsId1), null,
            new MacAddress("aa:bb:cc:dd:ee:ff"));
        verify(inventoryReader, times(1)).readInventory();
        verify(inventoryReader, times(0)).setRefreshData(true);
        verify(transmitPacket, times(1)).invoke(any());
    }

    @Test
    void testDispatchPacket_toFloodPacket() {
        doReturn(RpcResultBuilder.success().buildFuture()).when(transmitPacket).invoke(any());

        var ncInsId1 = DataObjectIdentifier.builder(Nodes.class)
                .child(Node.class, new NodeKey(new NodeId("1")))
                .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("1"))).build();
        final var ncInsId2 = DataObjectIdentifier.builder(Nodes.class)
                .child(Node.class, new NodeKey(new NodeId("2")))
                .child(NodeConnector.class, new NodeConnectorKey(new NodeConnectorId("2"))).build();
        var ncRef1 = new NodeConnectorRef(ncInsId1);

        // findHostByMac returns null (host not found) → flood
        doReturn(null).when(packetDispatcher).findHostByMac(any());

        // Controller connector for switch "2"
        var connMap = new HashMap<String, NodeConnectorRef>();
        connMap.put("2", ncRef1);
        when(inventoryReader.getControllerSwitchConnectors()).thenReturn(connMap);

        var nodeConnectors = new ArrayList<NodeConnectorRef>();
        nodeConnectors.add(new NodeConnectorRef(ncInsId1));
        nodeConnectors.add(new NodeConnectorRef(ncInsId1));
        nodeConnectors.add(new NodeConnectorRef(ncInsId2));
        when(switchNodeConnectors.get(any(String.class))).thenReturn(nodeConnectors);
        when(inventoryReader.getSwitchNodeConnectors()).thenReturn(switchNodeConnectors);

        packetDispatcher.dispatchPacket(null, new NodeConnectorRef(ncInsId2), null,
            new MacAddress("ff:ff:ff:ff:ff:ff"));
        verify(inventoryReader, times(1)).readInventory();
        verify(inventoryReader, times(0)).setRefreshData(true);
        verify(transmitPacket, times(2)).invoke(any());
    }
}
