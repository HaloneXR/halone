import subprocess

out = subprocess.run(["ps", "-A", "-o", "rss=,command="], capture_output=True, text=True).stdout
total = 0
kinds = {}
for line in out.splitlines():
    rss, _, command = line.strip().partition(" ")
    if "Halone CEF Spike" not in command:
        continue
    kind = "browser"
    for k in ("(GPU)", "(Renderer)", "(Plugin)", "(Alerts)"):
        if k in command:
            kind = k.strip("()")
    if kind == "browser" and "Helper" in command:
        kind = "utility"
    kinds.setdefault(kind, []).append(int(rss) / 1024)
    total += int(rss) / 1024
for kind, values in sorted(kinds.items()):
    print(f"{kind:9} {len(values)} processes, {sum(values):6.0f} MB")
print(f"total     {sum(len(v) for v in kinds.values())} processes, {total:6.0f} MB resident")
