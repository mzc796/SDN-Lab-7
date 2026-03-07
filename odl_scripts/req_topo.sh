#!/bin/bash
curl -4 -v -u admin:admin "http://localhost:8181/rests/data/network-topology:network-topology?content=nonconfig" -o data/topo.json
