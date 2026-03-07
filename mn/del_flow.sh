#!/bin/bash
echo "Deleting flow on $1 of flow entry with match $2"
sudo ovs-ofctl -O OpenFlow13 del-flows $1 $2
