"""Self-contained SVG figures for offline prediction reports.

The project environment has no plotting package, so these small charts use
SVG directly. Each image is viewable without a server or network connection.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path
from xml.sax.saxutils import escape


WIDTH, HEIGHT = 1100, 520
LEFT, RIGHT, TOP, BOTTOM = 82, 46, 110, 430
BLUE, ORANGE, GRID, INK = "#2474a6", "#e09337", "#dce5ed", "#183044"
FONT = "Noto Sans CJK SC, Microsoft YaHei, Arial, sans-serif"


def _text(x: float, y: float, value: str, size: int = 15,
          anchor: str = "start", color: str = INK, weight: str = "normal") -> str:
    return (f'<text x="{x:.1f}" y="{y:.1f}" text-anchor="{anchor}" '
            f'fill="{color}" font-size="{size}" font-weight="{weight}" '
            f'font-family="{FONT}">{escape(str(value))}</text>')


def _save(path: Path, elements: list[str], title: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    body = "\n".join(elements)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" '
           f'viewBox="0 0 {WIDTH} {HEIGHT}" role="img" aria-label="{escape(title)}">'
           f'<rect width="100%" height="100%" fill="#ffffff"/>\n{body}\n</svg>\n')
    path.write_text(svg, encoding="utf-8")


def _frame(title: str, subtitle: str, maximum: float = 1.0,
           unit: str = "%") -> list[str]:
    elements = [_text(LEFT, 42, title, 23, weight="bold"),
                _text(LEFT, 68, subtitle, 14, color="#526879")]
    for step in range(5):
        value = maximum * step / 4
        y = BOTTOM - (BOTTOM - TOP) * step / 4
        elements.append(f'<line x1="{LEFT}" y1="{y:.1f}" x2="{WIDTH-RIGHT}" '
                        f'y2="{y:.1f}" stroke="{GRID}" stroke-width="1"/>')
        label = f"{value:.0%}" if unit == "%" else f"{value:.1f}"
        elements.append(_text(LEFT - 12, y + 5, label, 13, "end", "#526879"))
    return elements


def method_chart(path: Path, title: str, selection: dict) -> None:
    """Group model selection means for Recall@50 and NDCG@50."""
    names = list(selection)
    elements = _frame(title, "仅参数选择期；未来可观测文件上的离线代理指标")
    plot_width = WIDTH - LEFT - RIGHT
    group_width = plot_width / len(names)
    bar_width = min(42, group_width * 0.22)
    for index, name in enumerate(names):
        center = LEFT + group_width * (index + 0.5)
        for offset, key, color in ((-1, "recallAt50", BLUE), (0, "ndcgAt50", ORANGE)):
            value = selection[name]["mean"][key]
            x = center + offset * bar_width
            y = BOTTOM - value * (BOTTOM - TOP)
            elements.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_width-4:.1f}" '
                            f'height="{BOTTOM-y:.1f}" fill="{color}" rx="3"/>')
            elements.append(_text(x + (bar_width-4)/2, y - 7, f"{value:.1%}", 12, "middle"))
        elements.append(_text(center, BOTTOM + 28, name, 12, "middle"))
    elements += [f'<rect x="{LEFT}" y="478" width="16" height="16" fill="{BLUE}"/>',
                 _text(LEFT + 23, 491, "Recall@50", 13),
                 f'<rect x="{LEFT+165}" y="478" width="16" height="16" fill="{ORANGE}"/>',
                 _text(LEFT + 188, 491, "NDCG@50", 13)]
    _save(path, elements, title)


def trend_chart(path: Path, title: str, selected: dict, configured: dict,
                selected_name: str, configured_name: str) -> None:
    """Plot held-out Recall@50 for selected and currently configured models."""
    selected_rows = selected["rows"]
    configured_rows = configured["rows"]
    dates = sorted({date.fromisoformat(row["cutoffDate"])
                    for row in selected_rows + configured_rows})
    if not dates:
        raise ValueError("No test rows to plot")
    elements = _frame(title, "独立测试期逐切点 Recall@50；断档日期没有评价点")
    plot_width = WIDTH - LEFT - RIGHT
    first, last = dates[0], dates[-1]
    span = max(1, (last - first).days)
    x_map = {day: LEFT + plot_width * (day - first).days / span for day in dates}
    series = ((selected_rows, selected_name, BLUE),
              (configured_rows, configured_name, ORANGE))
    if selected_name == configured_name:
        series = series[:1]
    for rows, name, color in series:
        commands = []
        previous = None
        points = []
        for row in rows:
            day = date.fromisoformat(row["cutoffDate"])
            x = x_map[day]
            y = BOTTOM - row["recallAt50"] * (BOTTOM-TOP)
            action = "M" if previous is None or (day - previous).days > 1 else "L"
            commands.append(f"{action}{x:.1f},{y:.1f}")
            points.append((x, y))
            previous = day
        path_data = " ".join(commands)
        elements.append(f'<path d="{path_data}" fill="none" stroke="{color}" '
                        'stroke-width="2.5" stroke-linejoin="round"/>')
        for x, y in points:
            elements.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="2.4" fill="{color}"/>')
    for fraction in range(5):
        tick_day = first + (last - first) * fraction / 4
        tick_x = LEFT + plot_width * fraction / 4
        elements.append(_text(tick_x, BOTTOM+26, tick_day.strftime("%m-%d"), 12, "middle"))
    elements += [f'<line x1="{LEFT}" y1="484" x2="{LEFT+20}" y2="484" '
                 f'stroke="{BLUE}" stroke-width="3"/>',
                 _text(LEFT+28, 489, (f"回测选出与当前配置：{selected_name}"
                                       if selected_name == configured_name
                                       else f"回测选出：{selected_name}"), 13)]
    if selected_name != configured_name:
        elements += [f'<line x1="{LEFT+390}" y1="484" x2="{LEFT+410}" y2="484" '
                     f'stroke="{ORANGE}" stroke-width="3"/>',
                     _text(LEFT+418, 489, f"当前配置：{configured_name}", 13)]
    _save(path, elements, title)


def prediction_chart(path: Path, title: str, items: list[dict],
                     actual: dict[str, int]) -> None:
    """Top-20 predicted and observed bytes; absence is displayed as unknown."""
    shown = items[:20]
    maximum = max((max(int(item["predictedBytes"]), actual.get(item["fileKey"], 0))
                   for item in shown), default=0)
    scale, unit = (1, "B")
    for threshold, label in ((10**12, "TB"), (10**9, "GB"), (10**6, "MB")):
        if maximum >= threshold:
            scale, unit = threshold, label
            break
    axis_max = max(maximum / scale * 1.1, 1)
    elements = _frame(title, f"最近可评价切点；单位 {unit}（十进制）；未出现于未来观测的文件标为“—”",
                      axis_max, unit)
    if not shown:
        elements.append(_text(WIDTH/2, 270, "该切点没有候选文件", 20, "middle"))
    else:
        plot_width = WIDTH - LEFT - RIGHT
        group_width = plot_width / len(shown)
        bar_width = min(16, group_width * 0.32)
        for index, item in enumerate(shown):
            center = LEFT + group_width * (index + 0.5)
            predicted = int(item["predictedBytes"])
            y = BOTTOM - (predicted/scale/axis_max) * (BOTTOM-TOP)
            elements.append(f'<rect x="{center-bar_width-2:.1f}" y="{y:.1f}" '
                            f'width="{bar_width:.1f}" height="{BOTTOM-y:.1f}" '
                            f'fill="{BLUE}" rx="2"/>')
            observed = actual.get(item["fileKey"])
            if observed is None:
                elements.append(_text(center+bar_width/2, BOTTOM-7, "—", 15, "middle", "#778997"))
            else:
                y = BOTTOM - (observed/scale/axis_max) * (BOTTOM-TOP)
                elements.append(f'<rect x="{center+2:.1f}" y="{y:.1f}" '
                                f'width="{bar_width:.1f}" height="{BOTTOM-y:.1f}" '
                                f'fill="{ORANGE}" rx="2"/>')
            elements.append(_text(center, BOTTOM+24, str(item["rank"]), 12, "middle"))
    elements += [f'<rect x="{LEFT}" y="478" width="16" height="16" fill="{BLUE}"/>',
                 _text(LEFT+23, 491, "预测累计流量", 13),
                 f'<rect x="{LEFT+200}" y="478" width="16" height="16" fill="{ORANGE}"/>',
                 _text(LEFT+223, 491, "未来已观测累计流量", 13)]
    _save(path, elements, title)
