#!/bin/bash
echo "Deleting flows on $1"
sudo ovs-ofctl -O OpenFlow13 del-flows $1
