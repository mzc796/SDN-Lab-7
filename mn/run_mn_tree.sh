#!/bin/bash
sudo mn --topo tree,depth=3,fanout=2 --switch ovsk,protocols=OpenFlow13 --controller remote,ip=127.0.0.1,port=6653
