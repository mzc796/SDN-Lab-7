/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.shortestpath.flow;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicLong;
import org.opendaylight.openflowplugin.api.OFConstants;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Prefix;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Uri;
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
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.ethernet.match.fields.EthernetTypeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.match.EthernetMatchBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.match.layer._3.match.Ipv4MatchBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TpId;
import org.opendaylight.yangtools.binding.util.BindingMap;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.Uint16;
import org.opendaylight.yangtools.yang.common.Uint32;
import org.opendaylight.yangtools.yang.common.Uint64;
import org.opendaylight.yangtools.yang.common.Uint8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs IPv4 destination-match flows on switches along the shortest path.
 */
public class ShortestPathFlowWriter {
    private static final Logger LOG = LoggerFactory.getLogger(ShortestPathFlowWriter.class);
    private static final String FLOW_ID_PREFIX = "ShortestPath-";
    private static final EtherType IPV4_ETHER_TYPE = new EtherType(Uint32.valueOf(0x0800));

    private final AddFlow addFlow;
    private final Uint8 tableId;
    private final Uint16 priority;
    private final Uint16 idleTimeout;
    private final Uint16 hardTimeout;

    private final AtomicLong flowIdInc = new AtomicLong();
    private final AtomicLong flowCookieInc = new AtomicLong(0x3a00000000000000L);

    public ShortestPathFlowWriter(final AddFlow addFlow, final Uint8 tableId, final Uint16 priority,
            final Uint16 idleTimeout, final Uint16 hardTimeout) {
        this.addFlow = requireNonNull(addFlow);
        this.tableId = requireNonNull(tableId);
        this.priority = requireNonNull(priority);
        this.idleTimeout = requireNonNull(idleTimeout);
        this.hardTimeout = requireNonNull(hardTimeout);
    }

    /**
     * Installs an IPv4 destination flow on the given switch, outputting to egressTp.
     *
     * @param nodeId   inventory NodeId of the switch
     * @param dstIp    destination IPv4 address (matched as /32)
     * @param egressTp topology TpId of the egress port (its string value is the port URI)
     */
    public void installFlow(final NodeId nodeId, final Ipv4Address dstIp, final TpId egressTp) {
        FlowId flowId = new FlowId(FLOW_ID_PREFIX + flowIdInc.getAndIncrement());

        InstanceIdentifier<Node> nodeIID = InstanceIdentifier.builder(Nodes.class)
            .child(Node.class, new NodeKey(nodeId))
            .build();
        InstanceIdentifier<Table> tableIID = nodeIID
            .augmentation(FlowCapableNode.class)
            .child(Table.class, new TableKey(tableId));
        InstanceIdentifier<Flow> flowIID = tableIID
            .child(Flow.class, new FlowKey(flowId));

        Flow flow = buildFlow(flowId, dstIp, egressTp);

        LOG.debug("Installing flow on node {} for dst {} -> egress {}", nodeId.getValue(),
            dstIp.getValue(), egressTp.getValue());

        addFlow.invoke(new AddFlowInputBuilder(flow)
            .setNode(new NodeRef(nodeIID.toIdentifier()))
            .setFlowRef(new FlowRef(flowIID.toIdentifier()))
            .setFlowTable(new FlowTableRef(tableIID.toIdentifier()))
            .setTransactionUri(new Uri(flowId.getValue()))
            .build());
    }

    private Flow buildFlow(final FlowId flowId, final Ipv4Address dstIp, final TpId egressTp) {
        return new FlowBuilder()
            .setTableId(tableId)
            .setFlowName("sp-" + dstIp.getValue())
            .setId(flowId)
            .setMatch(new MatchBuilder()
                .setEthernetMatch(new EthernetMatchBuilder()
                    .setEthernetType(new EthernetTypeBuilder()
                        .setType(IPV4_ETHER_TYPE)
                        .build())
                    .build())
                .setLayer3Match(new Ipv4MatchBuilder()
                    .setIpv4Destination(new Ipv4Prefix(dstIp.getValue() + "/32"))
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
                                        .setOutputNodeConnector(new Uri(egressTp.getValue()))
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
