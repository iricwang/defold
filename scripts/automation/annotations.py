"""Deterministic same-frame layout evidence. Raw GPU images and coordinates remain unchanged."""
import html
import json

from PIL import Image, ImageDraw, ImageFont
import layout

COLORS = {'passed': (47, 212, 145), 'failed': (255, 82, 110), 'unknown': (255, 192, 75)}
RANK = {'passed': 0, 'unknown': 1, 'failed': 2}


def plan(items, result, frame):
    viewport = layout.rectangle(0, 0, frame['width'], frame['height'])
    device = frame['device']
    left, top = device.get('left', 0), device.get('top', 0)
    safe_area = layout.rectangle(left, top, frame['width'] - left - device.get('right', 0),
                                 frame['height'] - top - device.get('bottom', 0))
    entries = []
    for index, checked in enumerate(result['rules'], 1):
        rule = checked['rule']
        entry = {'index': index, **checked, 'targets': [], 'overlap_polygon': []}
        for role, suffix in (('selector', 'a'), ('other', 'b')):
            if role not in rule:
                continue
            matches = layout.select(items, rule[role])
            target = {'label': str(index) + suffix, 'role': role, 'selector': rule[role],
                      'matched': len(matches), 'drawable_polygon': []}
            if len(matches) == 1:
                item = matches[0]
                known = item['geometry_precision'] == 'engine_gui_rectangle' and item.get('quad') is not None
                target.update(element_id=item['element_id'], id=item['id'], geometry_precision=item['geometry_precision'],
                              quad=item['quad'] if known else None,
                              visible_polygon=item['visible_polygon'] if known else None)
                if known:
                    target['drawable_polygon'] = layout.clip(item['quad'], viewport)
                target['location_status'] = ('on_image' if target['drawable_polygon'] else
                                             'outside_viewport' if known else 'unknown_geometry')
            else:
                target['location_status'] = 'missing' if not matches else 'ambiguous'
            entry['targets'].append(target)
        if rule['kind'] == 'no_overlap' and checked['state'] == 'failed' and len(entry['targets']) == 2:
            a, b = entry['targets']
            if a.get('visible_polygon') and b.get('visible_polygon'):
                entry['overlap_polygon'] = layout.clip(a['visible_polygon'], b['visible_polygon'])
        entries.append(entry)
    return {'state': result['state'], 'rules': entries, 'safe_area': safe_area,
            'coordinate_space': 'frame_pixels_top_left', 'raw_image_unchanged': True,
            'geometry_scope': 'engine_gui_rectangles_not_pixel_coverage',
            'legend': {'passed': '#2fd491', 'failed': '#ff526e', 'unknown': '#ffc04b', 'safe_area': '#59c9ed'},
            'limitations': ['Outlines show pre-shader node rectangles, not pixel occlusion.',
                            'Missing, ambiguous and unsupported geometry receive no invented location.',
                            'Outlines are clipped to the viewport; numbered labels may be displaced to avoid collisions.']}


def render(source, target, annotation):
    with Image.open(source) as original:
        image = original.convert('RGBA')
    width, height = image.size
    scale = min(width, height)
    stroke = max(2, round(scale / 300))
    font = ImageFont.load_default(size=max(12, min(32, round(scale / 45))))
    shading = Image.new('RGBA', image.size)
    draw = ImageDraw.Draw(shading)
    for entry in annotation['rules']:
        polygon = entry['overlap_polygon']
        if polygon:
            draw.polygon([tuple(p) for p in polygon], fill=(*COLORS['failed'], 90))
    image = Image.alpha_composite(image, shading)
    draw = ImageDraw.Draw(image)
    safe = annotation['safe_area']
    draw.line([tuple(p) for p in safe + safe[:1]], fill=(89, 201, 237), width=stroke)
    nodes = {}
    for entry in annotation['rules']:
        for target_info in entry['targets']:
            if not target_info['drawable_polygon']:
                continue
            key = target_info['element_id']
            item = nodes.setdefault(key, {'polygon': target_info['drawable_polygon'], 'labels': [], 'state': 'passed'})
            item['labels'].append(target_info['label'])
            if RANK[entry['state']] > RANK[item['state']]:
                item['state'] = entry['state']
    # Reserve the status strip before placing labels, so nodes at the top edge remain identifiable.
    counts = {state: sum(entry['state'] == state for entry in annotation['rules']) for state in COLORS}
    summary = f"LAYOUT  PASS {counts['passed']}  FAIL {counts['failed']}  UNKNOWN {counts['unknown']}"
    summary_bounds = draw.textbbox((0, 0), summary, font=font)
    panel_height = summary_bounds[3] - summary_bounds[1] + 12
    occupied = [(0, 0, min(width, summary_bounds[2] + 12), panel_height)]
    for item in sorted(nodes.values(), key=lambda node: RANK[node['state']]):
        polygon = [tuple(p) for p in item['polygon']]
        color = COLORS[item['state']]
        draw.line(polygon + polygon[:1], fill=color, width=stroke)
        label = ','.join(item['labels'][:6]) + ('+' if len(item['labels']) > 6 else '')
        bounds = draw.textbbox((0, 0), label, font=font)
        box_width, box_height = bounds[2] - bounds[0] + 8, bounds[3] - bounds[1] + 8
        x = max(0, min(width - box_width, min(p[0] for p in polygon)))
        anchor_y = min(p[1] for p in polygon)
        y = max(0, min(height - box_height, anchor_y - box_height))
        for offset in range(16):
            candidate = max(0, min(height - box_height, y + offset * box_height))
            box = (x, candidate, x + box_width, candidate + box_height)
            if not any(box[0] < b[2] and box[2] > b[0] and box[1] < b[3] and box[3] > b[1] for b in occupied):
                y = candidate
                break
        occupied.append((x, y, x + box_width, y + box_height))
        anchor = min(polygon, key=lambda p: (p[1], p[0]))
        draw.line([anchor, (x + box_width / 2, y + box_height / 2)], fill=color, width=stroke)
        draw.rectangle(occupied[-1], fill=(20, 24, 30), outline=color, width=stroke)
        draw.text((x + 4 - bounds[0], y + 4 - bounds[1]), label, font=font, fill=color)
    # ASCII numbers refer to the full UTF-8 legend in metadata/HTML; avoid platform-dependent CJK fonts.
    draw.rectangle((0, 0, min(width - 1, summary_bounds[2] + 12), panel_height), fill=(20, 24, 30))
    draw.text((6, 6 - summary_bounds[1]), summary, font=font, fill=COLORS[annotation['state']])
    image.convert('RGB').save(target, format='PNG')


def legend_html(annotation):
    rows = []
    for entry in annotation['rules']:
        targets = '; '.join(f"{target['label']} {json.dumps(target['selector'], ensure_ascii=False)} [{target['location_status']}]"
                            for target in entry['targets'])
        detail = entry.get('reason', '')
        for metric in ('outside_area', 'overlap_area'):
            if metric in entry:
                detail += f" {metric}={entry[metric]:.2f} px²"
        values = (str(entry['index']), entry['rule']['kind'], entry['state'], targets, detail)
        rows.append('<tr>' + ''.join('<td>' + html.escape(value) + '</td>' for value in values) + '</tr>')
    return ('<table class="layout-legend"><thead><tr><th>#</th><th>检查</th><th>结果</th><th>图中编号 / 节点</th><th>依据</th></tr></thead>'
            '<tbody>' + ''.join(rows) + '</tbody></table><p>青色线为安全区；a 为目标，b 为比较对象。'
            '红色填充为矩形交集，不代表像素遮挡。未知位置不绘制猜测框。</p>')
