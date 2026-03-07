#!/usr/bin/env python3
"""Draw the Mininet topology from the ODL topo.json file."""

import json
import math
import os
import matplotlib.pyplot as plt
import networkx as nx

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
TOPO_FILE = os.path.join(DATA_DIR, "topo.json")


def load_topology(filepath):
    with open(filepath) as f:
        data = json.load(f)
    topology = data["network-topology:network-topology"]["topology"][0]
    return topology


def build_graph(topology):
    G = nx.MultiDiGraph()

    switches = []
    hosts = []

    # Add nodes with their ports (excluding LOCAL ports)
    for node in topology.get("node", []):
        node_id = node["node-id"]
        ports = []
        for tp in node.get("termination-point", []):
            tp_id = tp["tp-id"]
            if "LOCAL" not in tp_id:
                ports.append(tp_id)

        is_host = node_id.startswith("host:")
        # For hosts, extract IP and attachment point
        if is_host:
            addrs = node.get("host-tracker-service:addresses", [])
            ip = addrs[0]["ip"] if addrs else ""
            attach = node.get("host-tracker-service:attachment-points", [])
            attach_tp = attach[0]["tp-id"] if attach else ""
            # attachment switch is derived from tp-id, e.g. "openflow:1:3" -> "openflow:1"
            attach_sw = ":".join(attach_tp.split(":")[:2]) if attach_tp else ""
            G.add_node(node_id, ports=sorted(ports), kind="host", ip=ip,
                       attach_sw=attach_sw)
            hosts.append(node_id)
        else:
            G.add_node(node_id, ports=sorted(ports), kind="switch")
            switches.append(node_id)

    # Add edges with port labels
    for link in topology.get("link", []):
        src_node = link["source"]["source-node"]
        src_port = link["source"]["source-tp"]
        dst_node = link["destination"]["dest-node"]
        dst_port = link["destination"]["dest-tp"]

        G.add_edge(src_node, dst_node, src_port=src_port, dst_port=dst_port)

    return G, sorted(switches), hosts


def compute_positions(G, switches, hosts):
    """Place switches evenly in a circle; hosts offset outward from their attachment switch."""
    pos = {}
    n = len(switches)
    radius = 2.0

    # Place switches evenly around a circle (top-centered)
    for i, sw in enumerate(switches):
        angle = math.pi / 2 + 2 * math.pi * i / n  # start from top, go clockwise
        pos[sw] = (radius * math.cos(angle), radius * math.sin(angle))

    # Place hosts outside the ring, offset from their attachment switch
    host_offset = 1.3
    host_count_per_sw = {}  # track multiple hosts on same switch
    for h in hosts:
        attach_sw = G.nodes[h].get("attach_sw", "")
        if attach_sw and attach_sw in pos:
            count = host_count_per_sw.get(attach_sw, 0)
            sx, sy = pos[attach_sw]
            # Direction: outward from center, with slight angular offset for multiple hosts
            angle = math.atan2(sy, sx) + count * 0.4
            pos[h] = (sx + host_offset * math.cos(angle),
                      sy + host_offset * math.sin(angle))
            host_count_per_sw[attach_sw] = count + 1
        else:
            # Fallback: place below the ring
            pos[h] = (0, -radius - 1.5)

    return pos


def get_display_label(node_id, G):
    """Short label for display."""
    if node_id.startswith("host:"):
        ip = G.nodes[node_id].get("ip", "")
        mac_short = node_id.replace("host:", "")
        return f"{ip}\n({mac_short})" if ip else mac_short
    else:
        # e.g. "openflow:1" -> "s1"
        num = node_id.split(":")[-1]
        return f"s{num}"


def draw_topology(G, switches, hosts):
    pos = compute_positions(G, switches, hosts)

    fig, ax = plt.subplots(figsize=(12, 9))

    # Separate node lists
    sw_nodes = [n for n in G.nodes if G.nodes[n]["kind"] == "switch"]
    host_nodes = [n for n in G.nodes if G.nodes[n]["kind"] == "host"]

    # Draw switch nodes
    nx.draw_networkx_nodes(G, pos, nodelist=sw_nodes, ax=ax,
                           node_size=2500, node_color="lightblue",
                           node_shape="s", edgecolors="black", linewidths=2)
    # Draw host nodes
    nx.draw_networkx_nodes(G, pos, nodelist=host_nodes, ax=ax,
                           node_size=1800, node_color="lightgreen",
                           node_shape="o", edgecolors="black", linewidths=2)

    # Draw labels
    labels = {n: get_display_label(n, G) for n in G.nodes}
    nx.draw_networkx_labels(G, pos, labels=labels, ax=ax, font_size=9,
                            font_weight="bold")

    # Draw directed edges with arrows, curved so both directions are visible
    # Group edges by node pair to assign alternating curvature
    from collections import defaultdict
    edge_curves = {}
    pair_count = defaultdict(int)
    for u, v, key in G.edges(keys=True):
        pair = tuple(sorted([u, v]))
        idx = pair_count[pair]
        pair_count[pair] += 1
        # Alternate curvature: first edge curves one way, second the other
        curve = 0.15 if idx % 2 == 0 else -0.15
        # If only one edge between this pair (e.g. host links), no curve
        edge_curves[(u, v, key)] = curve

    for u, v, key, data in G.edges(keys=True, data=True):
        curve = edge_curves[(u, v, key)]
        # Check if there's only one edge for this pair
        pair = tuple(sorted([u, v]))
        if pair_count[pair] == 1:
            curve = 0.0
        nx.draw_networkx_edges(G, pos, edgelist=[(u, v)], ax=ax, width=2,
                               edge_color="gray", arrows=True,
                               arrowstyle="-|>", arrowsize=20,
                               connectionstyle=f"arc3,rad={curve}",
                               min_source_margin=25, min_target_margin=25)

    # Draw port labels on each edge
    for u, v, key, data in G.edges(keys=True, data=True):
        src_port = data["src_port"].split(":")[-1]
        dst_port = data["dst_port"].split(":")[-1]

        x_src, y_src = pos[u]
        x_dst, y_dst = pos[v]

        # Apply curvature offset to label positions
        curve = edge_curves[(u, v, key)]
        pair = tuple(sorted([u, v]))
        if pair_count[pair] == 1:
            curve = 0.0
        # Perpendicular offset for curved edges
        dx, dy = x_dst - x_src, y_dst - y_src
        length = math.sqrt(dx**2 + dy**2) or 1
        perp_x, perp_y = -dy / length, dx / length
        curve_offset_x = curve * perp_x * 0.5
        curve_offset_y = curve * perp_y * 0.5

        offset = 0.2
        # Label near the source node
        ax.text(x_src + offset * (x_dst - x_src) + curve_offset_x,
                y_src + offset * (y_dst - y_src) + curve_offset_y,
                f"port {src_port}", fontsize=8, color="red",
                ha="center", va="center",
                bbox=dict(boxstyle="round,pad=0.2", fc="lightyellow",
                          ec="red", alpha=0.8))
        # Label near the destination node
        ax.text(x_dst + offset * (x_src - x_dst) + curve_offset_x,
                y_dst + offset * (y_src - y_dst) + curve_offset_y,
                f"port {dst_port}", fontsize=8, color="blue",
                ha="center", va="center",
                bbox=dict(boxstyle="round,pad=0.2", fc="lightyellow",
                          ec="blue", alpha=0.8))

    # Legend
    from matplotlib.lines import Line2D
    legend_elements = [
        Line2D([0], [0], marker='s', color='w', markerfacecolor='lightblue',
               markersize=15, markeredgecolor='black', label='Switch'),
        Line2D([0], [0], marker='o', color='w', markerfacecolor='lightgreen',
               markersize=15, markeredgecolor='black', label='Host'),
    ]
    ax.legend(handles=legend_elements, loc="upper left", fontsize=10)

    ax.set_title("Mininet Topology (from ODL)", fontsize=14, fontweight="bold")
    ax.axis("off")
    plt.tight_layout()

    output_path = os.path.join(DATA_DIR, "topology.png")
    plt.savefig(output_path, dpi=150)
    print(f"Topology saved to {output_path}")
    plt.show()


if __name__ == "__main__":
    topology = load_topology(TOPO_FILE)
    G, switches, hosts = build_graph(topology)
    draw_topology(G, switches, hosts)
