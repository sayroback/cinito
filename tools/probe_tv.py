#!/usr/bin/env python3
"""Read-only LAN diagnostic tool for SSDP, Google Cast, webOS SSAP, and LG DLNA TVs."""

import argparse
import json
import socket
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


def detect_lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("1.1.1.1", 80))
        return s.getsockname()[0]
    except Exception:
        return "0.0.0.0"
    finally:
        s.close()


def ssdp_scan(bind_ip: str, timeout: float = 2.5) -> list[tuple[str, str, str]]:
    msg = (
        "M-SEARCH * HTTP/1.1\r\n"
        "HOST: 239.255.255.250:1900\r\n"
        'MAN: "ssdp:discover"\r\n'
        "MX: 2\r\n"
        "ST: ssdp:all\r\n\r\n"
    ).encode()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    if bind_ip != "0.0.0.0":
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(bind_ip))
    sock.settimeout(timeout)
    sock.sendto(msg, ("239.255.255.250", 1900))
    seen: set[tuple[str, str, str]] = set()
    results: list[tuple[str, str, str]] = []
    while True:
        try:
            data, addr = sock.recvfrom(4096)
        except socket.timeout:
            break
        txt = data.decode(errors="ignore")
        loc = next((line.split(":", 1)[1].strip() for line in txt.splitlines() if line.lower().startswith("location:")), "")
        st = next((line.split(":", 1)[1].strip() for line in txt.splitlines() if line.lower().startswith("st:")), "")
        key = (addr[0], st, loc)
        if key not in seen:
            seen.add(key)
            results.append(key)
    sock.close()
    return results


def check_port(host: str, port: int, timeout: float = 1.5) -> bool:
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        return True
    except Exception:
        return False
    finally:
        s.close()


def http_get(url: str, timeout: float = 2.5) -> tuple[int, str]:
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode(errors="ignore")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception:
        return 0, ""


def soap_post(url: str, service: str, action: str, inner_xml: str) -> str:
    body = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        f'<s:Body><u:{action} xmlns:u="{service}">{inner_xml}</u:{action}></s:Body>'
        "</s:Envelope>"
    ).encode()
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": f'"{service}#{action}"',
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=3.0) as resp:
        return resp.read().decode(errors="ignore")


def inspect_dlna_services(location_url: str) -> None:
    code, xml_text = http_get(location_url)
    if code != 200 or not xml_text:
        return
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return
    base = location_url.rstrip("/")
    parsed_loc = urllib.request.urlparse(location_url)
    origin = f"{parsed_loc.scheme}://{parsed_loc.netloc}"
    for svc in root.iter():
        if not svc.tag.endswith("service"):
            continue
        stype = ""
        ctrl = ""
        for ch in svc:
            if ch.tag.endswith("serviceType"):
                stype = (ch.text or "").strip()
            elif ch.tag.endswith("controlURL"):
                ctrl = (ch.text or "").strip()
        if not ctrl:
            continue
        ctrl_url = ctrl if ctrl.startswith("http") else f"{origin}{ctrl if ctrl.startswith('/') else '/' + ctrl}"
        if "AVTransport" in stype:
            try:
                resp = soap_post(ctrl_url, stype, "GetTransportInfo", "<InstanceID>0</InstanceID>")
                doc = ET.fromstring(resp)
                state = next((el.text for el in doc.iter() if el.tag.endswith("CurrentTransportState")), "?")
                print(f"  DLNA AVTransport ({ctrl_url}): state={state}")
            except Exception as exc:
                print(f"  DLNA AVTransport ({ctrl_url}): error={exc}")
        elif "RenderingControl" in stype:
            try:
                resp = soap_post(ctrl_url, stype, "X_GetSubtitle", "<InstanceID>0</InstanceID>")
                doc = ET.fromstring(resp)
                sub = next((el.text for el in doc.iter() if el.tag.endswith("CurrentSubtitle")), "?")
                print(f"  DLNA RenderingControl ({ctrl_url}): X_GetSubtitle={sub}")
            except Exception:
                print(f"  DLNA RenderingControl ({ctrl_url}): X_GetSubtitle unsupported")


def main() -> None:
    parser = argparse.ArgumentParser(description="Diagnose Cast/DLNA/webOS TVs on the local LAN.")
    parser.add_argument("--host", help="Optional TV IP address for direct port/descriptor checks")
    args = parser.parse_args()

    lan_ip = detect_lan_ip()
    print(f"Host LAN IP: {lan_ip}")
    print("\n--- SSDP Multicast Scan ---")
    ssdp = ssdp_scan(lan_ip)
    dlna_locations: set[str] = set()
    for ip, st, loc in ssdp:
        if args.host and ip != args.host:
            continue
        print(f"  {ip} | {st} | {loc}")
        if "MediaRenderer" in st or "AVTransport" in st:
            dlna_locations.add(loc)

    if args.host:
        print(f"\n--- Direct Port Probe ({args.host}) ---")
        for port, label in ((3000, "webOS SSAP ws"), (3001, "webOS SSAP wss"), (8008, "Cast HTTP"), (8009, "Cast V2 TLS"), (1900, "UPnP 1900")):
            print(f"  :{port} ({label}): {'OPEN' if check_port(args.host, port) else 'closed'}")
        code, body = http_get(f"http://{args.host}:8008/setup/eureka_info")
        if code == 200 and body:
            try:
                info = json.loads(body)
                print(f"  eureka_info: name={info.get('name')} build={info.get('cast_build_revision')}")
            except Exception:
                print(f"  eureka_info: HTTP 200")
        else:
            print(f"  eureka_info: HTTP {code}")

    if dlna_locations:
        print("\n--- DLNA MediaRenderer State ---")
        for loc in sorted(dlna_locations):
            inspect_dlna_services(loc)


if __name__ == "__main__":
    main()
