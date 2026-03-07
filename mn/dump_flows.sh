#!/bin/bash
echo "dumping flow rules on switch $1"
ovs-ofctl -O OpenFlow13 dump-flows $1
