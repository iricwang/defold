# Copyright 2020-2026 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
#
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

"""Alpha contour tracing with GEOS outer hull simplification and constrained DT.

Runs in the user's existing spine-smart-mesh environment. No skeleton or
animation data is edited here; the editor transfers those after confirmation.
"""
import ctypes as C
import math
import cv2
import numpy as np
import shapely
from PIL import Image
from shapely.geometry import Polygon, LineString, MultiPoint, box
from shapely.geometry.polygon import orient


def outer_hull(polygon, fraction):
    # Use the public, reentrant GEOS C API; exchange WKB, never Shapely pointers.
    # Shapely does not yet expose PolygonHullSimplify as a Python operation.
    library = C.CDLL(shapely.lib.__file__)
    pointer = C.c_void_p
    def api(name, result, args):
        function = getattr(library, name)
        function.restype, function.argtypes = result, args
        return function
    init = api('GEOS_init_r', pointer, [])
    finish = api('GEOS_finish_r', None, [pointer])
    read = api('GEOSGeomFromWKB_buf_r', pointer, [pointer, C.c_char_p, C.c_size_t])
    write = api('GEOSGeomToWKB_buf_r', pointer, [pointer, pointer, C.POINTER(C.c_size_t)])
    simplify = api('GEOSPolygonHullSimplify_r', pointer, [pointer, pointer, C.c_uint, C.c_double])
    destroy = api('GEOSGeom_destroy_r', None, [pointer, pointer])
    free = api('GEOSFree_r', None, [pointer, pointer])
    context, original, result, buffer = init(), None, None, None
    try:
        wkb = shapely.to_wkb(polygon)
        original = read(context, wkb, len(wkb))
        if not original:
            raise ValueError('GEOS 无法读取图片轮廓。')
        result = simplify(context, original, 1, fraction)
        if not result:
            raise ValueError('GEOS 轮廓简化失败。')
        size = C.c_size_t()
        buffer = write(context, result, C.byref(size))
        if not buffer:
            raise ValueError('GEOS 无法输出图片轮廓。')
        return shapely.from_wkb(C.string_at(buffer, size.value))
    finally:
        if buffer:
            free(context, buffer)
        if result:
            destroy(context, result)
        if original:
            destroy(context, original)
        finish(context)


def optimize_outline(polygon, original, target, max_area, iterations, width, height):
    """Rank local edge collapses before running expensive topology predicates.

    Shoelace deltas touch only the changed edges. Check candidates in ascending
    added-area order and stop at the first valid, covering polygon. This keeps
    the same greedy objective without rebuilding/testing every whole polygon.
    """
    points = list(orient(polygon, sign=1).exterior.coords)[:-1]
    area_limit = original.area * (1 + max_area)

    def cross(a, b):
        return a[0]*b[1] - a[1]*b[0]

    for _ in range(iterations):
        if len(points) <= max(3, int(target)):
            break
        candidates = []
        n = len(points)
        area = polygon.area
        for i in range(n):
            a, b, c, d = (points[j % n] for j in (i-1, i, i+1, i+2))
            removal = (cross(a, c) - cross(a, b) - cross(b, c)) * .5
            if -1e-7 <= removal and area + removal <= area_limit + 1e-7:
                candidates.append((removal, i, 0, None))
            ux, uy = b[0]-a[0], b[1]-a[1]
            vx, vy = d[0]-c[0], d[1]-c[1]
            determinant = ux*vy - uy*vx
            if abs(determinant) > 1e-10:
                t = ((c[0]-a[0])*vy - (c[1]-a[1])*vx) / determinant
                intersection = (a[0]+t*ux, a[1]+t*uy)
                if 0 <= intersection[0] <= width and 0 <= intersection[1] <= height:
                    collapse = (cross(a, intersection) + cross(intersection, d)
                                - cross(a, b) - cross(b, c) - cross(c, d)) * .5
                    if -1e-7 <= collapse and area + collapse <= area_limit + 1e-7:
                        candidates.append((collapse, i, 1, intersection))
        accepted = False
        for _, i, kind, intersection in sorted(candidates):
            if kind:
                rotated = points[i:] + points[:i]
                proposal = [intersection] + rotated[2:]
            else:
                proposal = points[:i] + points[i+1:]
            candidate = Polygon(proposal)
            if not candidate.is_valid or candidate.area > area_limit:
                continue
            if candidate.area < area - 1e-7:
                continue
            # Retain full coverage verification, including floating tolerance.
            if not candidate.buffer(1e-7).covers(original):
                continue
            points, polygon = proposal, candidate
            accepted = True
            break
        if not accepted:
            break
    return polygon


def trace(path, settings):
    # New panels persist integer percentages; retain legacy ratio compatibility.
    if 'uniform-percent' in settings:
        settings = {**settings, 'uniform': float(settings['uniform-percent']) / 100.0}
    limits = {'detail': (0, 100, 20), 'concavity': (0, 100, 50),
              'refinement': (0, 100, 50), 'uniform': (0, 1, .3),
              'alpha': (1, 255, 8), 'padding': (0, 32, 0)}
    values = {}
    for name, (low, high, default) in limits.items():
        value = float(settings.get(name, default))
        if not math.isfinite(value) or not low <= value <= high:
            raise ValueError('描绘参数超出范围：' + name)
        values[name] = value
    image = Image.open(path).convert('RGBA')
    w, h = image.size
    if w*h > 16777216:
        raise ValueError('贴图过大，请使用不超过 4096×4096 像素的贴图。')
    alpha = np.asarray(image)[:, :, 3]
    mask = (alpha >= values['alpha']).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    components = [i for i in range(1, count) if stats[i, cv2.CC_STAT_AREA] > 2]
    if not components:
        raise ValueError('当前 Alpha 阈值下没有可描绘的图像。')
    if len(components) > 128:
        raise ValueError('独立像素区域过多，请提高 Alpha 阈值。')
    retained = np.isin(labels, components).astype(np.uint8)
    ignored = int(np.count_nonzero(mask)-np.count_nonzero(retained))
    contours, _ = cv2.findContours(retained, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    parts = []
    for contour in contours:
        points = contour[:, 0, :].astype(float)+.5
        # Keep needle tips and antialiased pixel islands. Buffering only a
        # repaired polygon can discard retraced, zero-width contour segments.
        vertices = MultiPoint(points).buffer(.8, quad_segs=1)
        if len(points) >= 3:
            shape = Polygon(points).buffer(0).union(LineString([*points, points[0]]).buffer(.8, join_style=2)).union(vertices)
        else:
            shape = MultiPoint(points).convex_hull.buffer(.8)
        parts.append(shape)
    original = shapely.union_all(parts).buffer(values['padding'], join_style=2)
    bridges = 0
    # Spine's single hull is connected with minimal narrow bridges, never a
    # global convex hull that would fill the tail's large concave empty region.
    while original.geom_type == 'MultiPolygon':
        parts = list(original.geoms)
        pairs = [(parts[i].distance(parts[j]), i, j) for i in range(len(parts)) for j in range(i)]
        _, i, j = min(pairs)
        bridge = shapely.shortest_line(parts[i], parts[j]).buffer(.8, cap_style=3)
        merged = original.union(bridge)
        if merged.geom_type == 'MultiPolygon' and len(merged.geoms) >= len(parts):
            raise ValueError('无法连接独立像素区域，请调整阈值或填充。')
        original = merged
        bridges += 1
    original = orient(Polygon(original.exterior).intersection(box(0, 0, w, h)), sign=1)
    target = 12 + values['detail'] * 2.6
    fraction = min(1.0, target / max(1, len(original.exterior.coords)-1))
    # Low detail may bridge fine fur notches; high concavity still protects them.
    # Bound added blank area, retaining the pixel-coverage test below.
    max_area = .005 + (1-values['concavity']/100) * (.18 + .16*(1-values['detail']/100))
    iterations = 3 + int(values['refinement']/10)
    low = fraction
    polygon = outer_hull(original, fraction)
    while polygon.area > original.area*(1+max_area) and fraction < 1:
        low = fraction
        fraction = min(1.0, fraction*1.5)
        polygon = outer_hull(original, fraction)
    high = fraction
    for _ in range(iterations):
        fraction = (low+high)*.5
        polygon = outer_hull(original, fraction)
        if polygon.area <= original.area*(1+max_area):
            high = fraction
        else:
            low = fraction
    polygon = outer_hull(original, high)
    polygon = optimize_outline(polygon, original, target, max_area,
                               20+int(values['refinement']*4), w, h)
    # Tie uniform spacing to the detail budget and perimeter. Image-size-based
    # spacing used to refill sparse outlines with dozens of redundant points.
    boundary = list(orient(polygon, sign=1).exterior.coords)[:-1]
    spacing = polygon.length / (target * (.25 + .75*values['uniform']))
    points = []
    for a, b in zip(boundary, boundary[1:]+boundary[:1]):
        subdivisions = max(1, math.ceil(math.dist(a, b)/spacing)) if values['uniform'] > 0 else 1
        points.extend([(a[0]+(b[0]-a[0])*i/subdivisions, a[1]+(b[1]-a[1])*i/subdivisions) for i in range(subdivisions)])
    if len(points) > 4096:
        raise ValueError('轮廓超过 4096 个顶点，请降低细节或精度。')
    polygon = Polygon(points)
    faces = shapely.constrained_delaunay_triangles(polygon)
    index = {tuple(p): i for i, p in enumerate(points)}
    triangles = [[index[tuple(p)] for p in list(face.exterior.coords)[:3]] for face in faces.geoms]
    if not triangles or not polygon.is_valid:
        raise ValueError('轮廓无法生成有效三角网格。')
    cover = np.zeros((h, w), np.uint8)
    fixed = np.round((np.asarray(points)-.5)*256).astype(np.int32)
    cv2.fillPoly(cover, [fixed], 1, shift=8)
    clipped = int(np.count_nonzero((retained > 0) & (cover == 0)))
    if clipped:
        raise ValueError(f'轮廓遗漏 {clipped} 个有效像素，未输出网格。')
    return {'points': points, 'uvs': (np.asarray(points)/[w, h]).flatten().tolist(),
            'triangles': [i for face in triangles for i in face],
            'edges': [i*2 for n in range(len(points)) for i in (n, (n+1)%len(points))],
            'size': [w, h], 'report': {'vertices': len(points), 'triangles': len(triangles), 'hull': len(points),
                                     'clipped_pixels': clipped, 'ignored_noise_pixels': ignored, 'islands_bridged': bridges,
                                     'added_area_ratio': polygon.area/original.area-1,
                                     'outline_error_px': float(shapely.hausdorff_distance(original.exterior, polygon.exterior)),
                                     'algorithm': 'OpenCV alpha / GEOS outer hull / constrained Delaunay'}}
