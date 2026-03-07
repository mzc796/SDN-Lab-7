/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.arphandler.flow;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicLong;
import org.opendaylight.openflowplugin.api.OFConstants;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Uri;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.MacAddress;
import org.opendaylight.yang.gen.v1.urn.opendaylight.action.types.rev131112.action.action.OutputActionCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.action.types.rev131112.action.action.output.action._case.OutputActionBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.action.types.rev131112.action.list.ActionBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowCapableNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.Table;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.TableKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.Flow;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.FlowBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.FlowKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.AddFlow;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.AddFlowInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.FlowTableRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.FlowCookie;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.FlowModFlags;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.FlowRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.OutputPortValues;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.flow.InstructionsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.flow.MatchBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.instruction.ApplyActionsCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.instruction.apply.actions._case.ApplyActionsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.list.InstructionBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.NodeKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.l2.types.rev130827.EtherType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.ethernet.match.fields.EthernetDestinationBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.ethernet.match.fields.EthernetTypeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.match.EthernetMatchBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.ethernet.rev140528.KnownEtherType;
import org.opendaylight.yangtools.binding.util.BindingMap;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.Uint16;
import org.opendaylight.yangtools.yang.common.Uint32;
import org.opendaylight.yangtools.yang.common.Uint64;
import org.opendaylight.yangtools.yang.common.Uint8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs ARP forwarding flows on switches (flood or unicast output).
 * Each flow matches EtherType=ARP and a specific Ethernet destination MAC.
 */
public class ArpFlowWriter {
    private static final Logger LOG = LoggerFactory.getLogger(ArpFlowWriter.class);
    private static final String FLOW_ID_PREFIX = "ArpFlow-";
    private static final EtherType ARP_ETHER_TYPE = new EtherType(Uint32.valueOf(KnownEtherType.Arp.getIntValue()));

    private final AddFlow addFlow;
    private final Uint8 tableId;
    private final Uint16 priority;
    private final Uint16 idleTimeout;
    private final Uint16 hardTimeout;

    private final AtomicLong flowIdInc = new AtomicLong();
    private final AtomicLong flowCookieInc = new AtomicLong(0x2c00000000000000L);

    public ArpFlowWriter(final AddFlow addFlow, final Uint8 tableId, final Uint16 priority,
            final Uint16 idleTimeout, final Uint16 hardTimeout) {
        this.addFlow = requireNonNull(addFlow);
        this.tableId = requireNonNull(tableId);
        this.priority = requireNonNull(priority);
        this.idleTimeout = requireNonNull(idleTimeout);
        this.hardTimeout = requireNonNull(hardTimeout);
    }

    /**
     * Installs a flow that floods all ARP packets with the given destination MAC on the switch.
     * Match: EtherType=ARP, Ethernet dst=dstMac. Action: OUTPUT:FLOOD.
     */
    public void installFloodFlow(final NodeId nodeId, final MacAddress dstMac) {
        installFlow(nodeId, dstMac, OutputPortValues.FLOOD.toString());
    }

    /**
     * Installs a flow that forwards ARP packets with the given destination MAC to a specific port.
     * Match: EtherType=ARP, Ethernet dst=dstMac. Action: OUTPUT(portUri).
     */
    public void installForwardFlow(final NodeId nodeId, final MacAddress dstMac, final String portUri) {
        installFlow(nodeId, dstMac, portUri);
    }

    private void installFlow(final NodeId nodeId, final MacAddress dstMac, final String outputPort) {
        var flowId = new FlowId(FLOW_ID_PREFIX + flowIdInc.getAndIncrement());

        InstanceIdentifier<Node> nodeIID = InstanceIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(nodeId))
            .build();
        var tableIID = nodeIID.augmentation(FlowCapableNode.class).child(Table.class, new TableKey(tableId));
        var flowIID = tableIID.child(Flow.class, new FlowKey(flowId));

        Flow flow = buildFlow(flowId, dstMac, outputPort);

        LOG.debug("Installing ARP flow on node {} for dstMac {} -> port {}",
            nodeId.getValue(), dstMac.getValue(), outputPort);

        addFlow.invoke(new AddFlowInputBuilder(flow)
            .setNode(new NodeRef(nodeIID.toIdentifier()))
            .setFlowRef(new FlowRef(flowIID.toIdentifier()))
            .setFlowTable(new FlowTableRef(tableIID.toIdentifier()))
            .setTransactionUri(new Uri(flowId.getValue()))
            .build());
    }

    private Flow buildFlow(final FlowId flowId, final MacAddress dstMac, final String outputPort) {
        return new FlowBuilder()
            .setTableId(tableId)
            .setFlowName("arp-fwd-" + dstMac.getValue())
            .setId(flowId)
            .setMatch(new MatchBuilder()
                .setEthernetMatch(new EthernetMatchBuilder()
                    .setEthernetType(new EthernetTypeBuilder().setType(ARP_ETHER_TYPE).build())
                    .setEthernetDestination(new EthernetDestinationBuilder().setAddress(dstMac).build())
                    .build())
                .build())
            .setInstructions(new InstructionsBuilder()
                .setInstruction(BindingMap.of(new InstructionBuilder()
                    .setOrder(0)
                    .setInstruction(new ApplyActionsCaseBuilder()
                        .setApplyActions(new ApplyActionsBuilder()
                            .setAction(BindingMap.of(new ActionBuilder()
                                .setOrder(0)
                                .setAction(new OutputActionCaseBuilder()
                                    .setOutputAction(new OutputActionBuilder()
                                        .setOutputNodeConnector(new Uri(outputPort))
                                        .setMaxLength(Uint16.MAX_VALUE)
                                        .build())
                                    .build())
                                .build()))
                            .build())
                        .build())
                    .build()))
                .build())
            .setPriority(priority)
            .setBufferId(OFConstants.OFP_NO_BUFFER)
            .setHardTimeout(hardTimeout)
            .setIdleTimeout(idleTimeout)
            .setCookie(new FlowCookie(Uint64.valueOf(flowCookieInc.getAndIncrement())))
            .setFlags(new FlowModFlags(false, false, false, false, false))
            .build();
    }
}
