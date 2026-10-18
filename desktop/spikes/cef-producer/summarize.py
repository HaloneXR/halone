import re
import sys


def summarize(path):
    text = open(path).read()
    if "summary panel" not in text:
        return f"{path}: no summary"
    tail = text[text.index("summary panel"):]
    out = []
    for block in re.split(r"(?=^summary panel)", tail, flags=re.M):
        head = re.match(r"summary panel (\d+): ([\d.]+) new frames/s, (\d+) idle ticks, (\d+) skipped", block)
        if not head:
            continue
        def pct(name):
            m = re.search(rf"^  {name} .*?p50\s+([\d.]+) p95\s+([\d.]+)", block, re.M)
            return f"{m.group(1)}/{m.group(2)}" if m else "-"
        out.append(
            f"  panel {head.group(1)}: {head.group(2):>5} fps, {head.group(3):>3} idle, {head.group(4):>3} skipped"
            f" | paint {pct('paint'):>11} | blit {pct('blit'):>9} | total {pct('total'):>11} ms"
        )
    for panel in sorted(set(re.findall(r"^panel (\d+) console: raf fps", text, re.M))):
        values = re.findall(rf"^panel {panel} console: raf fps ([\d.]+)", text, re.M)
        out.append(f"  panel {panel} page rAF: {' '.join(values[-4:])}")
    for panel in sorted(set(re.findall(r"^panel (\d+) console: video decoded", text, re.M))):
        values = re.findall(rf"^panel {panel} console: video decoded ([\d.]+) fps, dropped (\d+)", text, re.M)
        out.append(f"  panel {panel} video decoded/dropped: {' '.join(f'{v}/{d}' for v, d in values[-4:])}")
    for line in re.findall(r"^panel \d+ console: video (?:playing|error).*$", text, re.M):
        out.append("  " + line)
    cpu = re.findall(r"^cpu: (\S+(?: total)?)\s+([\d.]+)%", text, re.M)
    out.append("  cpu: " + ", ".join(f"{k} {v}%" for k, v in cpu))
    return "\n".join(out)


for path in sys.argv[1:]:
    print(path.rsplit("/", 1)[-1])
    print(summarize(path))
