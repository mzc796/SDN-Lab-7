from scapy.all import *
import time

if len(sys.argv) < 3:
    print("Usage: python3 udp_sender.py <src_host_num> <dst_host_num> [count] [interval_sec]")
    sys.exit(1)

src_n = int(sys.argv[1])
dst_n = int(sys.argv[2])
count = int(sys.argv[3]) if len(sys.argv) >= 4 else 5
interval = float(sys.argv[4]) if len(sys.argv) >= 5 else 1.0

dst_ip = f"10.0.0.{dst_n}"  # hX IP
src_ip = f"10.0.0.{src_n}"  # hX IP

print("Sending custom IPv4 packets from", src_ip,"to", dst_ip)

for i in range(5):
    pkt = IP(src=src_ip, dst=dst_ip) / UDP(sport=1234, dport=4321) / Raw(load=f"Message {i}")
    send(pkt, verbose=False)
    print("Sent packet", i)
    time.sleep(1)

print("Done.")

