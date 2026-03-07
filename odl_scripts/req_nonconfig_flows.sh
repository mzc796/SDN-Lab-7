#!/bin/bash
echo "reading operational data store of switch $1"

curl -s -u admin:admin -X GET "http://localhost:8181/rests/data/opendaylight-inventory:nodes/node=$1/flow-node-inventory:table=0?content=nonconfig" -H "Content-Type: application/json" -o data/$1_nonconfig_flows.json
