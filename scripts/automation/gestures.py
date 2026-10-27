"""Bounded GUI drag contracts and exact-node resolution, without guessing unknown geometry."""
import math
import layout


class GestureError(ValueError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


def properties(obj, selector):
    fraction = {'type': 'number', 'minimum': 0, 'maximum': 1}
    return {'selector': selector, 'destination': {'oneOf': [
        obj({'selector': selector}, ('selector',)),
        obj({'coordinate_space': {'const': 'frame_normalized'}, 'x': fraction, 'y': fraction},
            ('coordinate_space', 'x', 'y'))]},
        'steps': {'type': 'integer', 'minimum': 1, 'maximum': 120}}


def validate(destination, steps):
    if type(steps) is not int or not 1 <= steps <= 120:
        raise GestureError('INVALID_GESTURE', 'Drag steps must be an integer from 1 to 120')
    if set(destination) == {'selector'}:
        layout.select([], destination['selector'])
    elif set(destination) == {'coordinate_space', 'x', 'y'} and destination['coordinate_space'] == 'frame_normalized':
        if not all(type(destination[k]) in (int, float) and math.isfinite(destination[k]) and 0 <= destination[k] <= 1 for k in ('x', 'y')):
            raise GestureError('INVALID_GESTURE', 'Destination coordinates must be finite fractions from 0 to 1')
    else:
        raise GestureError('INVALID_GESTURE', 'Choose either one destination selector or a frame_normalized point')


def unique(items, selector):
    matches = layout.select(items, selector)
    if len(matches) != 1:
        raise GestureError('AMBIGUOUS_ELEMENT', 'Drag selector must identify exactly one element')
    return matches[0]


def refreshed(old, items, selector):
    matches = layout.select(items, {'element_id': old['element_id']})
    if len(matches) != 1 or not layout.select(matches, selector):
        raise GestureError('STALE_ELEMENT', 'Drag element has been destroyed, replaced or no longer matches the selector')
    item = matches[0]
    if item['geometry_precision'] != 'engine_gui_rectangle':
        raise GestureError('ELEMENT_NOT_INTERACTABLE', 'Drag requires verified visible GUI rectangle geometry')
    try:
        point = layout.point(item)
    except ValueError as error:
        raise GestureError('ELEMENT_NOT_INTERACTABLE', str(error)) from error
    return {'element_id': item['element_id'], 'id': item['id'], 'point': point}


def resolve(before, current, selector, destination, frame):
    source = refreshed(unique(before, selector), current, selector)
    if 'selector' in destination:
        target = refreshed(unique(before, destination['selector']), current, destination['selector'])
    else:
        target = {'coordinate_space': 'frame_normalized',
                  'point': [round(destination[k] * (frame[dimension] - 1)) for k, dimension in (('x', 'width'), ('y', 'height'))]}
    for endpoint in (source, target):
        if not (0 <= endpoint['point'][0] < frame['width'] and 0 <= endpoint['point'][1] < frame['height']):
            raise GestureError('ELEMENT_NOT_INTERACTABLE', 'Rounded drag point lies outside the image')
        endpoint['resolved_observation_id'] = frame['observation_id']
    return source, target
