from scapy.all import sniff, conf, hexdump
import sys



# Usage:
#   python3 udp_receiver.py <host_num> [port]
# Example:
#   python3 udp_receiver.py 2
#   python3 udp_receiver.py 3 4321

if len(sys.argv) < 2:
    print("Usage: python3 udp_receiver.py <host_num>")
    sys.exit(1)

host_n = int(sys.argv[1])

my_ip = f"10.0.0.{host_n}"

IFACE = f"h{host_n}-eth0"

def handle(pkt):
    print("=== got packet ===")
    print(pkt.summary())
    try:
        pkt.show()
    except Exception as e:
        print("show() failed:", e)
    # Uncomment if you want raw bytes:
    # hexdump(pkt)

print("Scapy version OK. Sniffing on", IFACE)
print("conf.iface =", conf.iface)
sniff(iface=IFACE, filter="udp", prn=handle, store=False)
#sniff(iface=IFACE, prn=handle, store=False)

