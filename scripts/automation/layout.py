"""GUI rectangles from engine matrices captured beside the GPU image; no editor-space estimates."""
import math

PIVOTS = {'PIVOT_CENTER': (.5, .5), 'PIVOT_NW': (0, 1), 'PIVOT_N': (.5, 1), 'PIVOT_NE': (1, 1),
          'PIVOT_W': (0, .5), 'PIVOT_E': (1, .5), 'PIVOT_SW': (0, 0), 'PIVOT_S': (.5, 0), 'PIVOT_SE': (1, 0)}


def area(polygon):
    return sum(a[0]*b[1] - b[0]*a[1] for a,b in zip(polygon, polygon[1:] + polygon[:1])) / 2


def clip(subject, boundary):
    if not subject or len(boundary) < 3: return []
    direction = 1 if area(boundary) >= 0 else -1
    for a,b in zip(boundary, boundary[1:] + boundary[:1]):
        before, subject = subject, []
        if not before: break
        def distance(p): return direction * ((b[0]-a[0])*(p[1]-a[1]) - (b[1]-a[1])*(p[0]-a[0]))
        previous, dp = before[-1], distance(before[-1])
        for current in before:
            dc = distance(current)
            if (dc >= 0) != (dp >= 0):
                fraction = dp / (dp - dc)
                subject.append([previous[i] + fraction*(current[i]-previous[i]) for i in (0,1)])
            if dc >= 0: subject.append(current)
            previous, dp = current, dc
    return subject


def rectangle(x,y,width,height):
    return [[x,y],[x+width,y],[x+width,y+height],[x,y+height]]


def quad(node, height):
    properties = node['properties']
    if properties.get('type') != 'gui_node_box': return None
    matrix, size = node.get('gui_world_matrix'), properties.get('size')
    pivot = PIVOTS.get(properties.get('pivot'))
    if not matrix or not size or not pivot or len(matrix) != 16: return None
    if not all(isinstance(x,(float,int)) and math.isfinite(x) for x in [*matrix,*size]): return None
    if any(abs(matrix[i]) > 1e-6 for i in (3,7,11)) or abs(matrix[15]-1) > 1e-6: return None
    x, y = -size[0]*pivot[0], -size[1]*pivot[1]
    return [[matrix[0]*px + matrix[4]*py + matrix[12], height - (matrix[1]*px + matrix[5]*py + matrix[13])]
            for px,py in rectangle(x,y,size[0],size[1])]


def elements(frame, profile):
    snapshot = frame.get('scene_snapshot', {})
    if not snapshot.get('supported') or snapshot.get('render_sequence') != frame['render_sequence']:
        raise ValueError('This engine does not provide a verified same-render scene snapshot')
    nodes = snapshot['nodes']; indexed = {node['index']: node for node in nodes}
    output = []
    for node in nodes:
        properties = node['properties']
        if not properties.get('type', '').startswith('gui_node_'): continue
        item = {'element_id': node['element_id'], 'id': properties.get('id'), 'type': properties.get('type'),
                'text': properties.get('text'), 'index': node['index'], 'parent': node['parent'],
                'geometry_precision': 'unknown', 'quad': None, 'visible_polygon': None,
                'enabled': node.get('enabled_recursive', False), 'visible': node.get('visible', False)}
        polygon = quad(node, frame['height']) if profile == 'default_gui' else None
        if polygon:
            item['quad'] = polygon
            visible = clip(polygon, rectangle(0,0,frame['width'],frame['height']))
            parent = indexed.get(node['parent']); known = True
            opacity = properties.get('color', [0,0,0,1])[3]
            inherit = node.get('inherit_alpha', False)
            while parent and parent.get('gui_world_matrix'):
                if inherit:
                    opacity *= parent['properties'].get('color',[0,0,0,1])[3]
                    inherit = parent.get('inherit_alpha',False)
                if parent.get('clipping'):
                    boundary = quad(parent, frame['height'])
                    if parent.get('clipping_inverted') or boundary is None:
                        known = False; break
                    visible = clip(visible, boundary)
                parent = indexed.get(parent['parent'])
            item['geometry_precision'] = 'engine_gui_rectangle' if known else 'unknown_clip'
            if known:
                item['visible_polygon'] = visible if item['enabled'] and item['visible'] else []
                if opacity <= 0 or (node.get('clipping') and not node.get('clipping_visible',True)):
                    item['visible_polygon'] = []
        output.append(item)
    return output


def select(items, selector):
    if not selector or not set(selector) <= {'id','element_id','type','text'}:
        raise ValueError('Use an exact id, element_id, type or text selector')
    return [item for item in items if all(item.get(key) == value for key,value in selector.items())]


def point(item):
    polygon = item.get('visible_polygon')
    if not polygon or abs(area(polygon)) < 1: raise ValueError('Element has no verified visible click area')
    # A convex clipped rectangle contains the mean of its vertices.
    return [round(sum(p[i] for p in polygon)/len(polygon)) for i in (0,1)]


def check(items, rules, frame):
    results = []
    for rule in rules:
        targets = select(items, rule['selector'])
        result = {'rule': rule, 'state': 'unknown'}
        if len(targets) != 1:
            result['reason'] = 'selector_missing_or_ambiguous'
        elif not targets[0]['quad'] or targets[0]['geometry_precision'] != 'engine_gui_rectangle':
            result['reason'] = 'geometry_or_clip_not_supported'
        elif rule['kind'] in ('inside_viewport','inside_safe_area'):
            d=frame['device']; left=d.get('left',0); top=d.get('top',0)
            bounds=rectangle(left,top,frame['width']-left-d.get('right',0),frame['height']-top-d.get('bottom',0)) if rule['kind']=='inside_safe_area' else rectangle(0,0,frame['width'],frame['height'])
            polygon=targets[0]['quad']; a=abs(area(polygon)); retained=abs(area(clip(polygon,bounds)))
            result.update(state='passed' if a > 0 and abs(a-retained) <= .01 else 'failed', outside_area=max(0,a-retained))
        elif rule['kind']=='no_overlap':
            other=select(items,rule['other'])
            if len(other)==1 and other[0].get('visible_polygon') is not None and targets[0].get('visible_polygon') is not None:
                overlap=abs(area(clip(targets[0]['visible_polygon'], other[0]['visible_polygon'])))
                result.update(state='passed' if overlap <= .01 else 'failed', overlap_area=overlap)
            else: result['reason']='other_geometry_missing_or_ambiguous'
        else: result['reason']='unsupported_rule'
        results.append(result)
    return {'state': 'failed' if any(x['state']=='failed' for x in results) else ('unknown' if any(x['state']=='unknown' for x in results) else 'passed'), 'rules':results}
