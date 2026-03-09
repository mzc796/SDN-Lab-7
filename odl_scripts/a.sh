#!/bin/bash
source "$(dirname "$0")/set_mac_vars.sh"

echo "configuring s3"
./pois_flow.sh openflow:3 0 44 $S1_ETH2 2 200
./pois_flow.sh openflow:3 0 45 $S2_ETH1 1 200

#<<'END_COMMENT'
echo "configuring s1"
./pois_flow.sh openflow:1 0 44 $S4_ETH1 2 200
./pois_flow.sh openflow:1 0 45 $S3_ETH1 1 200
echo "configuring s4"
./pois_flow.sh openflow:4 0 44 $S1_ETH1 2 200
./pois_flow.sh openflow:4 0 45 $S3_ETH2 1 200
echo "configuring s5"
./pois_flow.sh openflow:5 0 44 $S1_ETH1 1 200
./pois_flow.sh openflow:5 0 45 $S3_ETH2 2 200
echo "configuring s2"
./pois_flow.sh openflow:2 0 44 $S1_ETH1 1 200
./pois_flow.sh openflow:2 0 45 $S3_ETH2 2 200
#END_COMMENT
