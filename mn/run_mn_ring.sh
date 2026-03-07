#!/bin/bash
sudo mn --controller remote,ip=127.0.0.1 --custom motivating_example_topo.py --topo mytopo --switch ovs,protocols=OpenFlow13
