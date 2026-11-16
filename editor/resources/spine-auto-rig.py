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

"""Deterministic alpha-mask centerline proposal for a single flexible part.
No model download; bounded-resolution thinning and sparse graph diameter.
"""
import cv2
import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import dijkstra


def centerline(path, alpha=8):
    image = Image.open(path).convert("RGBA")
    width, height = image.size
    if width * height > 16777216:
        raise ValueError("自动布骨图片不得超过 4096×4096 像素。")
    mask = (np.asarray(image)[:, :, 3] >= alpha).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    if count < 2:
        raise ValueError("图片没有可识别的透明轮廓。")
    main = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    main_area = stats[main, cv2.CC_STAT_AREA]
    if np.sum(stats[1:, cv2.CC_STAT_AREA]) - main_area > max(8, main_area * .02):
        raise ValueError("请选择一个连续部位；多个独立部位需要分别生成骨骼。")
    mask = (labels == main).astype(np.uint8)
    scale = min(1., 256. / max(width, height))
    small = cv2.resize(mask, (max(3, round(width*scale)), max(3, round(height*scale))), interpolation=cv2.INTER_NEAREST)
    distance = cv2.distanceTransform(small, cv2.DIST_L2, 5)
    # Zhang-Suen's two subiterations preserve connectivity and endpoints.
    thin = np.pad(small, 1)
    for _ in range(512):
        changed = False
        for phase in range(2):
            p = [thin[:-2,1:-1], thin[:-2,2:], thin[1:-1,2:], thin[2:,2:],
                 thin[2:,1:-1], thin[2:,:-2], thin[1:-1,:-2], thin[:-2,:-2]]
            neighbors = sum(p)
            transitions = sum(((a == 0) & (b != 0)).astype(np.uint8) for a,b in zip(p,p[1:]+p[:1]))
            groups = ((0,2,4),(2,4,6)) if phase == 0 else ((0,2,6),(0,4,6))
            remove = (thin[1:-1,1:-1] != 0) & (neighbors >= 2) & (neighbors <= 6) & (transitions == 1)
            for a,b,c in groups:
                remove &= (p[a]*p[b]*p[c] == 0)
            changed |= bool(np.any(remove))
            thin[1:-1,1:-1][remove] = 0
        if not changed:
            break
    ys, xs = np.nonzero(thin[1:-1,1:-1])
    if len(xs) < 3:
        raise ValueError("该部位无法提取骨骼链，请使用细长的尾巴或触手图片。")
    lookup = {(int(x), int(y)): i for i,(x,y) in enumerate(zip(xs,ys))}
    rows, cols, lengths = [], [], []
    for (x,y), i in lookup.items():
        for dx,dy in [(-1,-1),(0,-1),(1,-1),(-1,0),(1,0),(-1,1),(0,1),(1,1)]:
            j = lookup.get((x+dx,y+dy))
            if j is not None:
                rows.append(i); cols.append(j); lengths.append(float(np.hypot(dx,dy)))
    graph = coo_matrix((lengths,(rows,cols)),shape=(len(xs),len(xs))).tocsr()
    def farthest(start):
        d, previous = dijkstra(graph, directed=False, indices=start, return_predecessors=True)
        return int(np.argmax(np.where(np.isfinite(d),d,-1))), previous
    start, _ = farthest(0)
    end, previous = farthest(start)
    indices = [end]
    while indices[-1] != start:
        node = int(previous[indices[-1]])
        if node < 0:
            raise ValueError("部位中轴不连续，请调整图片。")
        indices.append(node)
    indices.reverse()
    # Orient the broad/root end first, but let the user reverse it explicitly.
    section = max(2,len(indices)//4)
    widths = distance[ys[indices],xs[indices]]
    if np.mean(widths[:section]) < np.mean(widths[-section:]):
        indices.reverse()
    points = [[(float(xs[i])+.5)/small.shape[1], (float(ys[i])+.5)/small.shape[0]] for i in indices]
    # Downsampling can push a very thin tip outside the source alpha. Snap
    # back to the original interior, not to the simplified outline or its hull.
    interior = cv2.distanceTransform(mask, cv2.DIST_L2, 5) >= 1.5
    if not np.any(interior):
        interior = mask != 0
    nearest = distance_transform_edt(~interior, return_distances=False, return_indices=True)
    corrected = []
    for u,v in points:
        x,y = min(width-1,int(u*width)), min(height-1,int(v*height))
        y,x = nearest[:,y,x]
        point = [(float(x)+.5)/width,(float(y)+.5)/height]
        if not corrected or point != corrected[-1]:
            corrected.append(point)
    return {"centerline": corrected, "size": [width,height]}
