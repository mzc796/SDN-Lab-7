#!/bin/bash
# Source this file to set MAC address variables for switch interfaces.
# Usage: source set_mac_vars.sh
#
# After sourcing, variables like $S5_ETH2, $S1_ETH1, etc. are available.

for raw_iface in $(ip -o link show | awk -F': ' '{print $2}' | grep '^s[0-9]'); do
    # Strip the @ifN suffix (e.g. s1-eth3@if2 -> s1-eth3)
    iface="${raw_iface%%@*}"
    mac=$(ip link show "$iface" | awk '/ether/ {print $2}')
    # Convert interface name to variable name: s5-eth2 -> S5_ETH2
    var_name=$(echo "$iface" | tr '[:lower:]-' '[:upper:]_')
    export "$var_name=$mac"
    echo "$var_name=$mac"
done
