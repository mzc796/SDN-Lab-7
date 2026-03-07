/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.l2switch.shortestpath;

import org.opendaylight.l2switch.shortestpath.core.ShortestPathPacketHandler;
import org.opendaylight.l2switch.shortestpath.flow.ShortestPathFlowWriter;
import org.opendaylight.l2switch.shortestpath.topology.NetworkGraphManager;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.NotificationService;
import org.opendaylight.mdsal.binding.api.RpcService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.AddFlow;
import org.opendaylight.yang.gen.v1.urn.opendaylight.l2switch.shortest.path.config.rev140528.ShortestPathConfig;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.ipv4.rev140528.Ipv4PacketReceived;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacket;
import org.opendaylight.yangtools.concepts.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider/lifecycle bean for the shortest-path routing module.
 * Wired up via Blueprint; uses init-method="init" destroy-method="close".
 */
public class ShortestPathProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ShortestPathProvider.class);

    private final DataBroker dataBroker;
    private final NotificationService notificationService;
    private final RpcService rpcService;
    private final ShortestPathConfig config;

    private Registration topoListenerReg;
    private Registration ipv4ListenerReg;

    public ShortestPathProvider(final DataBroker dataBroker, final NotificationService notificationService,
            final RpcService rpcService, final ShortestPathConfig config) {
        this.dataBroker = dataBroker;
        this.notificationService = notificationService;
        this.rpcService = rpcService;
        this.config = config;
    }

    public void init() {
        // Build topology graph manager (Dijkstra)
        NetworkGraphManager graphManager = new NetworkGraphManager(dataBroker);
        graphManager.setTopologyId(config.getTopologyId());
        topoListenerReg = graphManager.registerAsDataChangeListener();

        // Build flow writer
        ShortestPathFlowWriter flowWriter = new ShortestPathFlowWriter(
            rpcService.getRpc(AddFlow.class),
            config.getFlowTableId(),
            config.getFlowPriority(),
            config.getFlowIdleTimeout(),
            config.getFlowHardTimeout());

        // Build packet handler
        ShortestPathPacketHandler handler = new ShortestPathPacketHandler(
            dataBroker, graphManager, flowWriter,
            rpcService.getRpc(TransmitPacket.class),
            config.getTopologyId());

        // Register for IPv4 packets
        ipv4ListenerReg = notificationService.registerListener(Ipv4PacketReceived.class, handler);

        LOG.info("ShortestPath initialized.");
    }

    public void close() {
        if (ipv4ListenerReg != null) {
            ipv4ListenerReg.close();
            ipv4ListenerReg = null;
        }
        if (topoListenerReg != null) {
            topoListenerReg.close();
            topoListenerReg = null;
        }
        LOG.info("ShortestPath (instance {}) torn down.", this);
    }
}
