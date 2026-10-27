"""Pixel evidence from immutable runtime captures; never infer UI geometry or business success."""
from PIL import Image, ImageChops, ImageDraw


def cutout_overlay(source, target, device, cutouts):
    with Image.open(source) as original:
        image = original.convert('RGB')
        draw = ImageDraw.Draw(image)
        for x,y,w,h in cutouts:
            draw.rectangle((round(x),round(image.height-y-h),round(x+w)-1,round(image.height-y)-1), fill=(0,0,0))
        draw.rectangle((device.get('left',0),device.get('top',0),image.width-device.get('right',0)-1,
                        image.height-device.get('bottom',0)-1), outline=(42,220,155),width=2)
        image.save(target,format='PNG')


def rectangle(region, size):
    values = [region.get(key) for key in ('x', 'y', 'width', 'height')]
    if any(type(value) is not int for value in values):
        raise ValueError('Region requires integer x/y/width/height')
    x, y, width, height = values
    if x < 0 or y < 0 or width < 1 or height < 1 or x + width > size[0] or y + height > size[1]:
        raise ValueError('Region lies outside the original frame')
    return x, y, x + width, y + height


def crop(source, target, region):
    with Image.open(source) as original:
        original.crop(rectangle(region, original.size)).save(target, format='PNG')


def compare(before, after, target, channel_threshold=8, max_changed_ratio=0.005, region=None, ignore=None):
    if type(channel_threshold) is not int or not 0 <= channel_threshold <= 255:
        raise ValueError('channel_threshold must be 0..255')
    if type(max_changed_ratio) not in (int, float) or not 0 <= max_changed_ratio <= 1:
        raise ValueError('max_changed_ratio must be finite and 0..1')
    if len(ignore or []) > 16:
        raise ValueError('At most 16 ignored rectangles')
    with Image.open(before) as first, Image.open(after) as second:
        if first.size != second.size:
            raise ValueError('Compare requires equal frame dimensions; images are never silently rescaled')
        bounds = rectangle(region, first.size) if region else (0, 0, *first.size)
        first, second = first.convert('RGB'), second.convert('RGB')
        mask = Image.new('L', first.size, 0)
        draw = ImageDraw.Draw(mask)
        x0, y0, x1, y1 = bounds
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=255)
        for item in ignore or []:
            x, y, right, bottom = rectangle(item, first.size)
            draw.rectangle((x, y, right - 1, bottom - 1), fill=0)
        compared = mask.histogram()[255]
        if not compared:
            raise ValueError('No pixels remain after ignored rectangles')
        channels = ImageChops.difference(first, second).split()
        maximum = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2])
        changed = maximum.point([255 if value > channel_threshold else 0 for value in range(256)])
        changed = ImageChops.darker(changed, mask)
        count = changed.histogram()[255]
        maximum = ImageChops.darker(maximum, mask)
        histogram = maximum.histogram()
        ratio = count / compared
        overlay = Image.blend(second, Image.new('RGB', second.size, 'black'), 0.7)
        overlay.paste((255, 52, 112), mask=changed)
        overlay.save(target, format='PNG')
        bbox = changed.getbbox()
        return {'verdict': 'match' if ratio <= max_changed_ratio else 'different',
                'comparison_kind': 'rgb_pixel_difference', 'business_confirmed': False,
                'compared_pixels': compared, 'changed_pixels': count, 'changed_ratio': ratio,
                'mean_max_channel_delta': sum(i * n for i, n in enumerate(histogram)) / compared,
                'max_channel_delta': maximum.getextrema()[1],
                'changed_bounds': None if bbox is None else dict(x=bbox[0], y=bbox[1], width=bbox[2]-bbox[0], height=bbox[3]-bbox[1]),
                'thresholds': {'channel_threshold': channel_threshold, 'max_changed_ratio': max_changed_ratio},
                'region': region, 'ignore': ignore or []}
