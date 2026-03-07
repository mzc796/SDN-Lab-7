# tcp_server.py
import socket

HOST = "0.0.0.0"   # listen on all interfaces
PORT = 11          # change if needed

def main():
    print(f"[+] Starting TCP server on port {PORT}")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind((HOST, PORT))
        s.listen(1)

        print("[+] Waiting for connection...")
        conn, addr = s.accept()

        with conn:
            print(f"[+] Connected by {addr}")

            while True:
                data = conn.recv(1024)
                if not data:
                    break
                print(f"[RECEIVED] {data.decode()}")

    print("[+] Connection closed")

if __name__ == "__main__":
    main()
