# SDN-Lab-7
The original OpenDaylight project is licensed under the Eclipse Public License (EPL).
All original license headers and copyright notices are preserved.
## Learning Objectives

In this lab, you will poison the topology view of an SDN controller by installing malicious OpenFlow flow entries. The goal is to fabricate deceptive links in the controller’s topology while leaving the actual network connectivity unchanged.

Building on the environment from previous labs, you will perform precise link manipulation, altering how discovery packets are forwarded so that the controller infers incorrect switch links.

Specifically, you will:

* Install malicious flow entries on selected switches.

* Observe how the controller’s discovered topology changes.

* Compare the real topology with the controller’s inferred topology.

* Incrementally modify flow entries to achieve the target deceptive link.

By the end of the lab, you should understand how manipulating flow rules can corrupt the controller’s topology view and exploit weaknesses in SDN discovery mechanisms.

## Virtual Machine Summary
Memory: >= 8GB

Storage: 50GB

CPU: 2 cores, AMD64 Architecture

Installation Disc: [ubuntu-22.04.4-desktop-amd64.iso](https://old-releases.ubuntu.com/releases/22.04/)

## References
[An Instant Virtual Network on your Laptop (or other PC)](https://mininet.org/)

[PICOS 4.4.3 Configuration G](https://pica8-fs.atlassian.net/wiki/spaces/PicOS443sp/overview?homepageId=10453009)

[OpenDaylight Flow Examples](https://docs.opendaylight.org/projects/openflowplugin/en/latest/users/flow-examples.html)

[L2switch User Guide](https://test-odl-docs.readthedocs.io/en/latest/user-guide/l2switch-user-guide.html)

[ONOS REST API](https://wiki.onosproject.org/display/ONOS/Appendix+B%3A+REST+API)

[ONOS flow rules configuration](https://wiki.onosproject.org/display/ONOS/Flow+Rules)
## Precise Link Manipulation

0. Install matplotlib and networkx for topology drawing.
   ```
   sudo apt install python3-matplotlib
   sudo apt install python3-networkx
   ```
1. Download the code:
   ```
   git clone https://github.com/mzc796/SDN-Lab-7.git
   ```
   NOTE: If you reuse the compiled ODL of SDN-Lab-6, jump to Step 3 to run OpenDaylight directly.
2. Build project:
   ```
   cd SDN-Lab-7/
   mvn clean install -DskipTests -Dcheckstyle.skip
   ```
3. Run OpenDaylight-ShortestPath
   ```
   cd distribution/karaf/target/assembly/bin/
   sudo ./karaf
   ```
4. Run Mininet. Open a new terminal:
   ```
   cd SDN-Lab-7/mn/
   sudo ./run_mn_ring.sh
   ```
   ```
            Switch_C----------------------Switch_B
               |                             |
   Host_1 --- Switch_A --- Switch_D --- Switch_E-----Host_2
   ```
5. Check connection.

   (1) Observe topology.
   ```
   cd SDN-Lab-7/odl-scripts/
   mkdir data
   python3 draw_topo.py
   ```
6. Start Poisoning

   (1) Extract switch ports' MAC addresses, and export them as variables:
   ```
   source set_mac_vars.sh
   ```
   (2) Use `pois_flow.sh` to set up poisonous flow entries to precisely manipulate links and make the topology view as shown below:
   ```
            Switch_B----------------------Switch_E-----Host_2
               |                             |
   Host_1 --- Switch_A --- Switch_C --- Switch_D
   ```
   For example:
   ```
   sudo ./pois_flow.sh openflow:1 0 11 $S1_ETH1 1 101
   ```
   (3) You can use `python3 draw_topo.py` to check out topology changes after each poisonous flow entry is set up. Please note that the OpenDaylight topology service is not robust. It occasionally does not show a reasonable topology discovery result or has a long delay. 
7. Utilities

   (1) Delete all config flow entries on a switch. For example:
   ```
   sudo ./del_all_flows.sh openflow:1
   ```
    
