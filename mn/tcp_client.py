# tcp_client.py
import socket

SERVER_IP = "10.0.0.2"  # change to server IP
PORT = 11

def main():
    print(f"[+] Connecting to {SERVER_IP}:{PORT}")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect((SERVER_IP, PORT))
        print("[+] Connected to server")

        while True:
            msg = input("Enter message (or 'exit'): ")
            if msg.lower() == "exit":
                break
            s.sendall(msg.encode())

    print("[+] Connection closed")

if __name__ == "__main__":
    main()
